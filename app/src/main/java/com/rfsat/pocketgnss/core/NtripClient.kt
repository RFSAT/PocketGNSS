package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.BaseStation
import com.rfsat.pocketgnss.protocol.Dechunker
import com.rfsat.pocketgnss.protocol.Mountpoint
import com.rfsat.pocketgnss.protocol.Ntrip
import com.rfsat.pocketgnss.protocol.NtripConfig
import com.rfsat.pocketgnss.protocol.Rtcm
import com.rfsat.pocketgnss.protocol.StreamSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocketFactory

enum class NtripStatus { OFF, CONNECTING, STREAMING, WAITING, ERROR }

data class NtripState(
    val status: NtripStatus = NtripStatus.OFF,
    val message: String = "",
    val mountpoint: String = "",
    val bytes: Long = 0,
    val bytesPerSec: Double = 0.0,
    val lastDataMs: Long = 0,
    val frames: Long = 0,
    val badFrames: Long = 0,
    /** RTCM message type -> count. */
    val types: Map<Int, Long> = emptyMap(),
    val base: BaseStation? = null,
    val lastGgaSentMs: Long = 0,
    val reconnects: Int = 0,
)

/**
 * NTRIP client on the phone. Forwards every byte of the correction stream to [forward] (the receiver link)
 * and uploads the rover's GGA from [gga] every [NtripConfig.ggaIntervalSec] seconds. Reconnects with a
 * growing back-off (5 s .. 30 s) when the caster or the mobile network drops, or when no data comes for [stallMs]
 * (read at each check, so it follows the settings): an RTK base sends every second, but the Galileo HAS caster
 * (ntrip.gsc-europa.eu) pauses up to about 60 s between its SSR transmissions, and ephemeris streams send rarely.
 */
class NtripClient(private val scope: CoroutineScope, private val forward: (ByteArray) -> Unit, private val gga: () -> String?,
                  private val stallMs: () -> Long = { STALL_RTK_MS }) {
    companion object {
        /** No data for this long ends the connection (then it reconnects): RTK base data arrives every second. */
        const val STALL_RTK_MS = 30_000L
        /** SSR (PPP) streams pause up to about 60 s; RTKLIB uses SSR corrections up to 90 s old. */
        const val STALL_SSR_MS = 90_000L
        /** Broadcast ephemeris streams send a message only when an ephemeris changes. */
        const val STALL_EPH_MS = 300_000L
    }
    private val state = MutableStateFlow(NtripState())
    val ntrip: StateFlow<NtripState> = state.asStateFlow()
    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    /**
     * Number of the current connection loop. start() and stop() are synchronized and each loop only touches the state
     * and the socket while it is still the current one: before 1.12.17 two quick setting changes could leave an
     * earlier loop running beside the new one (two logins to the caster, interleaved "Reconnecting" lines, an old
     * mountpoint kept alive), and casters that allow one login per account then starved one of them.
     */
    @Volatile private var generation = 0L

    val running: Boolean get() = job?.isActive == true

    /** Updates the state only while the loop [gen] is the current one. */
    private inline fun update(gen: Long, f: (NtripState) -> NtripState) {
        synchronized(this) { if (gen == generation) state.value = f(state.value) }
    }

    @Synchronized fun start(cfg: NtripConfig) {
        stop()
        if (!cfg.usable) { state.value = NtripState(NtripStatus.ERROR, "Set caster, port and mountpoint in Setup"); return }
        val gen = generation
        job = scope.launch(Dispatchers.IO) {
            var backoff = 5_000L
            var reconnects = 0
            while (isActive && gen == generation) {
                update(gen) { it.copy(status = NtripStatus.CONNECTING, message = "${cfg.host}:${cfg.port}", mountpoint = cfg.mountpoint,
                    reconnects = reconnects) }
                try {
                    stream(cfg, gen)
                    backoff = 5_000L
                } catch (e: Exception) {
                    if (!isActive || gen != generation) break
                    update(gen) { it.copy(status = NtripStatus.ERROR, message = e.message ?: e.javaClass.simpleName) }
                }
                if (!isActive || gen != generation) break
                update(gen) { it.copy(status = NtripStatus.WAITING) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                reconnects++
            }
        }
    }

    /** Not connecting, with [message] as the reason (shown in Setup and the app log). */
    @Synchronized fun refuse(message: String) {
        if (job == null && state.value.status == NtripStatus.ERROR && state.value.message == message) return
        stop()
        state.value = NtripState(NtripStatus.ERROR, message)
    }

    @Synchronized fun stop() {
        generation++
        job?.cancel(); job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        state.value = NtripState()
    }

    private fun open(cfg: NtripConfig): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(cfg.host, cfg.port), 8000)
        val s = if (!cfg.secure) plain else try {
            // SNI with the caster's name; the certificate must be valid and issued for that name.
            val t = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, cfg.host, cfg.port, true) as javax.net.ssl.SSLSocket
            t.soTimeout = 10_000
            t.startHandshake()
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(cfg.host, t.session)) {
                t.close(); throw IOException("TLS: the caster's certificate is not for ${cfg.host}")
            }
            t
        } catch (e: javax.net.ssl.SSLException) {
            try { plain.close() } catch (_: Exception) {}
            throw IOException("TLS connection to ${cfg.host}:${cfg.port} failed: ${e.message ?: e.javaClass.simpleName}")
        }
        s.soTimeout = 1000
        return s
    }

    private suspend fun stream(cfg: NtripConfig, gen: Long) = withContext(Dispatchers.IO) {
        val s = open(cfg)
        synchronized(this@NtripClient) {
            // stopped or restarted while connecting: this connection is not wanted any more
            if (gen != generation) { try { s.close() } catch (_: Exception) {}; throw IOException("Stopped") }
            socket = s
        }
        try {
            s.getOutputStream().apply { write(Ntrip.request(cfg).toByteArray(Charsets.US_ASCII)); flush() }
            val input = s.getInputStream()
            val header = Ntrip.parseHeader(readHeader(input))
            when (header.reply) {
                Ntrip.Reply.STREAM -> {}
                Ntrip.Reply.SOURCETABLE -> throw IOException("Mountpoint '${cfg.mountpoint}' not on this caster (it answered with its sourcetable)")
                Ntrip.Reply.UNAUTHORIZED -> throw IOException("Caster refused the user name / password (${header.statusLine})")
                Ntrip.Reply.NOT_FOUND -> throw IOException("Mountpoint '${cfg.mountpoint}' not found")
                Ntrip.Reply.ERROR -> throw IOException("Caster replied: ${header.statusLine.ifEmpty { "nothing" }}")
            }
            update(gen) { it.copy(status = NtripStatus.STREAMING, message = header.statusLine) }
            val types = HashMap(state.value.types)
            var base = state.value.base
            val checker = StreamSplitter(onNmea = {}, onRtcm = { f ->
                types[f.type] = (types[f.type] ?: 0) + 1
                Rtcm.baseStation(f.bytes)?.let { base = it }
            })
            var total = state.value.bytes
            var windowBytes = 0L; var windowStart = System.currentTimeMillis()
            val sink: (ByteArray, Int) -> Unit = { b, n ->
                val chunk = if (n == b.size) b else b.copyOf(n)
                try { forward(chunk) } catch (_: Exception) {}
                checker.feed(chunk)
                total += n; windowBytes += n
            }
            val dechunk = if (header.chunked) Dechunker(sink) else null
            val buf = ByteArray(4096)
            var lastGga = 0L
            var lastPublish = 0L
            var lastData = System.currentTimeMillis()
            sendGga(s, cfg)?.let { lastGga = it }
            while (isActive && gen == generation) {
                val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                val now = System.currentTimeMillis()
                if (n < 0) throw IOException("Caster closed the connection")
                if (n > 0) { lastData = now; if (dechunk != null) dechunk.feed(buf, n) else sink(buf, n) }
                val stall = stallMs()
                if (now - lastData > stall) throw IOException("No data for ${stall / 1000} s")
                if (cfg.ggaIntervalSec > 0 && now - lastGga >= cfg.ggaIntervalSec * 1000L) sendGga(s, cfg)?.let { lastGga = it } ?: run { lastGga = now }
                if (now - lastPublish >= 500) {
                    val dt = (now - windowStart).coerceAtLeast(1)
                    update(gen) { it.copy(bytes = total, bytesPerSec = windowBytes * 1000.0 / dt, lastDataMs = lastData,
                        frames = checker.rtcmFrames, badFrames = checker.badChecksums,
                        types = HashMap(types), base = base, lastGgaSentMs = lastGga) }
                    if (dt > 5000) { windowBytes = 0; windowStart = now }
                    lastPublish = now
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
            synchronized(this@NtripClient) { if (socket === s) socket = null }
        }
    }

    private fun sendGga(s: Socket, cfg: NtripConfig): Long? {
        if (cfg.ggaIntervalSec <= 0) return null
        val line = gga() ?: return null
        s.getOutputStream().apply { write((line + "\r\n").toByteArray(Charsets.US_ASCII)); flush() }
        return System.currentTimeMillis()
    }

    private fun readHeader(input: InputStream): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 10_000
        while (sb.length < 8192) {
            val c = try { input.read() } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) throw IOException("No answer from the caster"); continue
            }
            if (c < 0) break
            sb.append(c.toChar())
            // v1 casters may answer "ICY 200 OK\r\n" followed directly by data; stop after the status line then.
            if (sb.startsWith("ICY 200") && sb.endsWith("\r\n")) break
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) break
        }
        return sb.toString().trim()
    }

    /** Downloads and parses a caster's sourcetable. */
    suspend fun sourcetable(cfg: NtripConfig): List<Mountpoint> = withContext(Dispatchers.IO) {
        val s = open(cfg)
        try {
            s.soTimeout = 10_000
            s.getOutputStream().apply { write(Ntrip.request(cfg, mountpoint = "").toByteArray(Charsets.US_ASCII)); flush() }
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val body = if (text.contains("transfer-encoding: chunked", ignoreCase = true)) {
                val out = java.io.ByteArrayOutputStream()
                val raw = text.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                Dechunker { b, n -> out.write(b, 0, n) }.feed(raw, raw.size)
                out.toString(Charsets.ISO_8859_1.name())
            } else text
            val list = Ntrip.parseSourcetable(body)
            if (list.isEmpty()) throw IOException(text.lineSequence().firstOrNull()?.let { "Caster replied: $it" } ?: "Empty sourcetable")
            list
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}
