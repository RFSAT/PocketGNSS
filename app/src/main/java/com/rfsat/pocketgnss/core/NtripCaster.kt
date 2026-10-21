package com.rfsat.pocketgnss.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class CasterState(val running: Boolean = false, val port: Int = 0, val clients: List<String> = emptyList(), val bytes: Long = 0, val error: String? = null)

/**
 * A small NTRIP caster on the phone (Setup > Outputs): other rovers on the same Wi-Fi or on the phone's hotspot
 * connect to it as to any caster (NTRIP 1 or 2, one mountpoint, optional Basic login) and receive the RTCM 3 stream
 * the phone's NTRIP relay gets from the real caster. GGA sent by the rovers is read and ignored (a VRS stream is
 * computed for the phone's own position). Pure sockets, unit-tested on localhost.
 */
class NtripCaster {
    private val _state = MutableStateFlow(CasterState())
    val state: StateFlow<CasterState> = _state.asStateFlow()
    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var mount = "POCKETGNSS"
    @Volatile private var auth: String? = null
    @Volatile var position: Pair<Double, Double>? = null
    private var bytes = 0L

    private class Client(val socket: Socket, val out: OutputStream, val chunked: Boolean, val name: String) {
        val queue = ArrayBlockingQueue<ByteArray>(256)
        @Volatile var alive = true
    }

    /** Starts listening on all interfaces; [user] empty = no login. */
    @Synchronized fun start(port: Int, mountpoint: String, user: String, password: String) {
        stop()
        mount = mountpoint.trim().ifEmpty { "POCKETGNSS" }
        auth = if (user.isBlank()) null else Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        try {
            val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); server = s
            _state.value = CasterState(true, s.localPort)
            Thread({
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: IOException) { break }
                    Thread({ handle(c) }, "caster-client").apply { isDaemon = true }.start()
                }
            }, "ntrip-caster").apply { isDaemon = true }.start()
        } catch (e: IOException) {
            _state.value = CasterState(false, port, error = e.message ?: "cannot listen on $port")
        }
    }

    @Synchronized fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (c in clients) close(c)
        clients.clear()
        _state.value = CasterState()
    }

    val running: Boolean get() = server?.isClosed == false

    /** RTCM bytes from the relay, to every connected rover (dropped for a rover that does not keep up). */
    fun send(data: ByteArray) {
        if (clients.isEmpty()) return
        for (c in clients) if (!c.queue.offer(data)) { c.queue.clear() }
        bytes += data.size
    }

    private fun publish() { _state.value = _state.value.copy(clients = clients.map { it.name }, bytes = bytes) }

    private fun readHeader(i: InputStream): List<String>? {
        val lines = ArrayList<String>(); val sb = StringBuilder()
        while (lines.size < 50) {
            val b = i.read()
            if (b < 0) return null
            if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); if (l.isEmpty()) return lines; lines.add(l); sb.clear() }
            else if (sb.length < 2000) sb.append(b.toChar())
        }
        return lines
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15_000; sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val head = readHeader(input) ?: run { sock.close(); return }
            val req = head.firstOrNull()?.split(' ') ?: run { sock.close(); return }
            val v2 = head.any { it.lowercase(Locale.US).startsWith("ntrip-version:") && it.contains("2.0") }
            if (req.size < 2 || req[0] != "GET") { reply(out, if (v2) "HTTP/1.1 405 Method Not Allowed" else "HTTP/1.0 405 Method Not Allowed"); sock.close(); return }
            val path = req[1].removePrefix("/").substringBefore('?')
            if (path.isEmpty() || path != mount) {
                val table = sourcetable()
                if (v2) reply(out, "HTTP/1.1 200 OK", "Ntrip-Version: Ntrip/2.0", "Content-Type: gnss/sourcetable", "Content-Length: ${table.size}", body = table)
                else reply(out, "SOURCETABLE 200 OK", "Content-Type: text/plain", "Content-Length: ${table.size}", body = table)
                sock.close(); return
            }
            val a = auth
            if (a != null) {
                val given = head.firstOrNull { it.lowercase(Locale.US).startsWith("authorization:") }?.substringAfter(':')?.trim()
                if (given == null || !given.startsWith("Basic ") || given.removePrefix("Basic ").trim() != a) {
                    reply(out, if (v2) "HTTP/1.1 401 Unauthorized" else "HTTP/1.0 401 Unauthorized", "WWW-Authenticate: Basic realm=\"/$mount\"")
                    sock.close(); return
                }
            }
            if (v2) reply(out, "HTTP/1.1 200 OK", "Ntrip-Version: Ntrip/2.0", "Content-Type: gnss/data", "Transfer-Encoding: chunked", "Cache-Control: no-store")
            else { out.write("ICY 200 OK\r\n\r\n".toByteArray()); out.flush() }
            val c = Client(sock, out, v2, sock.inetAddress.hostAddress ?: "?")
            clients.add(c); publish()
            // Rovers send GGA now and then: read and discard, so a closed connection is noticed.
            Thread({
                try { sock.soTimeout = 0; val buf = ByteArray(512); while (c.alive && input.read(buf) >= 0) {} } catch (_: IOException) {}
                c.alive = false
            }, "caster-gga").apply { isDaemon = true }.start()
            while (c.alive) {
                val d = c.queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (c.chunked) { out.write("%x\r\n".format(d.size).toByteArray()); out.write(d); out.write("\r\n".toByteArray()) } else out.write(d)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            clients.firstOrNull { it.socket === sock }?.let { close(it) }
            try { sock.close() } catch (_: Exception) {}
            publish()
        }
    }

    private fun close(c: Client) { c.alive = false; clients.remove(c); try { c.socket.close() } catch (_: Exception) {} }

    private fun reply(out: OutputStream, status: String, vararg headers: String, body: ByteArray? = null) {
        val sb = StringBuilder(status).append("\r\n").append("Server: PocketGNSS caster\r\n")
        for (h in headers) sb.append(h).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII)); body?.let { out.write(it) }; out.flush()
    }

    /** The sourcetable: one RTCM 3 stream at the phone's position. */
    fun sourcetable(): ByteArray {
        val (la, lo) = position ?: (0.0 to 0.0)
        val str = "STR;$mount;PocketGNSS relay;RTCM 3;;2;GNSS;PocketGNSS;;%.2f;%.2f;0;0;PocketGNSS;none;%s;N;0;".format(Locale.US, la, lo, if (auth != null) "B" else "N")
        return (str + "\r\nENDSOURCETABLE\r\n").toByteArray(Charsets.US_ASCII)
    }

    companion object {
        /** IPv4 addresses of this phone on Wi-Fi / hotspot networks (what rovers connect to). */
        fun addresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }
}

/**
 * The position as one line of JSON, for the app interface (TCP JSON stream): what your own programs need without
 * parsing NMEA. Fields absent when unknown. [grid] adds the grid coordinates chosen in Setup.
 */
object PositionJson {
    private fun n(v: Double?, d: Int) = v?.takeIf { !it.isNaN() }?.let { "%.${d}f".format(Locale.US, it) } ?: "null"

    /** One JSON line; [height] = (height system, height in it) when a national height model is chosen (docs/app-interface.md). */
    fun of(f: GnssFix, grid: com.rfsat.pocketgnss.protocol.GridPoint?, method: String? = null, height: Pair<String, Double?>? = null): String = buildString {
        append("{\"utc\":").append(f.time?.let { "\"$it\"" } ?: "null")
        append(",\"date\":").append(f.date?.let { "\"%04d-%02d-%02d\"".format(Locale.US, it.year, it.month, it.day) } ?: "null")
        append(",\"lat\":").append(n(f.latitude, 9)).append(",\"lon\":").append(n(f.longitude, 9))
        append(",\"h_ell\":").append(n(f.ellipsoidHeight, 3)).append(",\"h_msl\":").append(n(f.altitudeMsl, 3))
        append(",\"fix\":\"").append(f.quality.name).append("\",\"gga_quality\":").append(f.quality.code)
        if (method != null) append(",\"method\":\"").append(method.replace("\"", "'")).append('"')
        append(",\"h_acc\":").append(n(f.horizontalAccuracy, 3)).append(",\"v_acc\":").append(n(f.verticalAccuracy, 3))
        append(",\"sd_lat\":").append(n(f.sdLatitude, 3)).append(",\"sd_lon\":").append(n(f.sdLongitude, 3)).append(",\"sd_h\":").append(n(f.sdAltitude, 3))
        append(",\"sats\":").append(f.satellitesUsed?.toString() ?: "null").append(",\"hdop\":").append(n(f.hdop, 2))
        append(",\"speed\":").append(n(f.speedMs, 3)).append(",\"course\":").append(n(f.courseDeg, 2))
        append(",\"age\":").append(n(f.diffAgeSec, 1)).append(",\"station\":").append(f.diffStation?.let { "\"" + it.replace("\"", "") + "\"" } ?: "null")
        if (grid != null) append(",\"grid\":{\"name\":\"").append(grid.label).append("\",\"e\":").append(n(grid.easting, 3)).append(",\"n\":").append(n(grid.northing, 3)).append('}')
        if (height != null) append(",\"height\":{\"system\":\"").append(height.first.replace("\"", "'")).append("\",\"h\":").append(n(height.second, 3)).append('}')
        append('}')
    }

}
