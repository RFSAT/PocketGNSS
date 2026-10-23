package com.rfsat.pocketgnss.link

import com.rfsat.pocketgnss.protocol.RtcmFrames
import com.rfsat.pocketgnss.raw.RinexReader
import com.rfsat.pocketgnss.rtk.FixGate
import com.rfsat.pocketgnss.rtk.IonoChooser
import com.rfsat.pocketgnss.rtk.PppConvergence
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.forEngine
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Progress of a replay, for the Log screen and the top bar. */
data class ReplayState(val active: Boolean = false, val name: String = "", val done: Int = 0, val total: Int = 0, val speed: Double = 1.0,
                       val paused: Boolean = false)

object Replay {
    val state = MutableStateFlow(ReplayState())
    val flow: StateFlow<ReplayState> = state.asStateFlow()
    /** Replay speeds offered: real time, faster, as fast as possible (0). */
    val speeds = listOf(1.0, 5.0, 20.0, 0.0)

    /** Holds the replay at the current epoch (the screens keep showing it) or lets it go on. */
    fun setPaused(on: Boolean) { state.value = state.value.copy(paused = on && state.value.active) }
}

/**
 * Plays a recorded session's raw NMEA (raw.nmea of a session_ folder) back as if a receiver sent it, paced by the GGA / RMC
 * times ([speed] × real time; 0 = as fast as the app reads). Every screen and tool works on it as on a live
 * receiver; nothing is written back. Ends with an IOException("Replay finished").
 */
class NmeaReplayLink(file: File, private val speed: Double) : GnssLink {
    private val lines = file.readLines().filter { it.startsWith("$") }
    private var i = 0
    private var lastTime: Double? = null
    private var nextNs = System.nanoTime()
    @Volatile private var closed = false
    override val info = LinkInfo(LinkKind.REPLAY, "Replay: " + (file.parentFile?.name ?: file.name), "recorded NMEA · %s".format(speedLabel(speed)))

    init { Replay.state.value = ReplayState(true, info.name, 0, lines.size, speed) }

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        if (Replay.state.value.paused) { Thread.sleep(100); nextNs = System.nanoTime(); return 0 }
        if (i >= lines.size) { Replay.state.value = Replay.state.value.copy(active = false, done = lines.size); throw IOException(FINISHED) }
        val wait = nextNs - System.nanoTime()
        if (wait > 0) { Thread.sleep(minOf(wait / 1_000_000, 200)); if (nextNs - System.nanoTime() > 0) return 0 }
        // One epoch: lines up to the next change of time.
        val sb = StringBuilder()
        while (i < lines.size && sb.length < buffer.size - 200) {
            val l = lines[i]
            val t = timeOf(l)
            if (t != null && lastTime != null && t != lastTime && sb.isNotEmpty()) {
                var dt = t - lastTime!!; if (dt < 0) dt += 86400.0
                if (speed > 0 && dt < 30) nextNs = System.nanoTime() + (dt / speed * 1e9).toLong()
                lastTime = t
                break
            }
            if (t != null) lastTime = t
            sb.append(l).append("\r\n"); i++
        }
        if (i % 50 == 0 || i >= lines.size) Replay.state.value = Replay.state.value.copy(done = i)
        val b = sb.toString().toByteArray(Charsets.US_ASCII)
        val n = minOf(b.size, buffer.size)
        b.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun write(data: ByteArray) {}
    override fun close() { closed = true; Replay.state.value = Replay.state.value.copy(active = false) }

    companion object {
        const val FINISHED = "Replay finished"

        /** Seconds of day of a GGA / RMC / GNS sentence. */
        fun timeOf(l: String): Double? {
            val type = l.drop(3).take(3)
            if (type != "GGA" && type != "RMC" && type != "GNS") return null
            val f = l.split(',').getOrNull(1) ?: return null
            if (f.length < 6) return null
            return (f.substring(0, 2).toIntOrNull() ?: return null) * 3600.0 + (f.substring(2, 4).toIntOrNull() ?: return null) * 60 +
                (f.substring(4).toDoubleOrNull() ?: return null)
        }

        fun speedLabel(s: Double) = if (s <= 0) "max speed" else "%.0f×".format(java.util.Locale.US, s)
    }
}

/**
 * Plays a phone raw GNSS session (raw_ folders: raw.obs RINEX and the caster's base.rtcm3) through the phone RTK engine
 * again, epoch by epoch: the base stream is fed up to each rover epoch's time. Shows exactly what phone RTK does with
 * those measurements under the current engine settings, so settings can be compared on the same data.
 */
class RawReplayLink(dir: File, private val options: RtkOptions, private val speed: Double) : GnssLink {
    // RINEX when it has observations, else the GnssLogger file (sessions recorded before 1.12.5 with Galileo have an
    // empty RINEX; a GnssLogger file from Google's app works too)
    private val rinexEpochs = File(dir, "raw.obs").takeIf { it.isFile }?.useLines { s -> s.count { it.startsWith(">") } } ?: 0
    private val logEpochs = File(dir, "gnss_log.txt").takeIf { it.isFile }?.useLines { s ->
        var n = 0; var last = ""
        for (l in s) if (l.startsWith("Raw,")) { val t = l.split(',', limit = 4)[2]; if (t != last) { n++; last = t } }
        n
    } ?: 0
    private val fromLog = logEpochs > rinexEpochs + rinexEpochs / 10
    private val rinex = if (fromLog) null else RinexReader(File(dir, "raw.obs").bufferedReader())
    private val log = if (fromLog) com.rfsat.pocketgnss.raw.GnssLogReader(File(dir, "gnss_log.txt").bufferedReader()) else null
    private fun nextEpoch() = rinex?.next() ?: log?.next()
    private val frames = File(dir, "base.rtcm3").takeIf { it.isFile }?.readBytes()?.let { RtcmFrames.split(it) } ?: emptyList()
    private var fi = 0
    private val total = if (fromLog) logEpochs else rinexEpochs
    private var done = 0
    private val arrays = RoverArrays()
    private val solOut = DoubleArray(RtkSolution.SIZE)
    private val baseOut = DoubleArray(RtkBase.SIZE)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var lastTow: Double? = null
    private var nextNs = System.nanoTime()
    private val iono = IonoChooser(options.ionoFree)
    private val convergence = PppConvergence()
    @Volatile private var closed = false
    override val info = LinkInfo(LinkKind.REPLAY, "Replay: " + dir.name, "phone raw GNSS through phone RTK · %s".format(NmeaReplayLink.speedLabel(speed)),
        phoneRtk = true)

    init {
        if (!RtkNative.available) throw IOException("Phone RTK engine not available on this phone")
        val ok = RtkNative.startFor(options, this)
        if (!ok) throw IOException("Phone RTK engine could not start")
        PhoneRtkLink.publish(PhoneRtkState())
        Replay.state.value = ReplayState(true, info.name, 0, total, speed)
    }

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        queue.poll()?.let { b -> val n = minOf(b.size, buffer.size); b.copyInto(buffer, 0, 0, n); return n }
        if (Replay.state.value.paused) { Thread.sleep(100); nextNs = System.nanoTime(); return 0 }
        val wait = nextNs - System.nanoTime()
        if (wait > 0) { Thread.sleep(minOf(wait / 1_000_000, 200)); if (nextNs - System.nanoTime() > 0) return 0 }
        val e = nextEpoch() ?: run { Replay.state.value = Replay.state.value.copy(active = false, done = total); throw IOException(NmeaReplayLink.FINISHED) }
        done++
        if (done % 20 == 0) Replay.state.value = Replay.state.value.copy(done = done)
        lastTow?.let { p -> val dt = e.towSeconds - p; if (speed > 0 && dt in 0.0..30.0) nextNs = System.nanoTime() + (dt / speed * 1e9).toLong() }
        lastTow = e.towSeconds
        val engine = synchronized(RtkNative.lock) {
            if (RtkNative.owner !== this) throw IOException("closed")
            if (done == 1) RtkNative.setTime(e.gpsWeek, e.towSeconds)
            // Base data up to this epoch (frames without an epoch time, e.g. ephemerides, go in stream order).
            val towMs = (e.towSeconds * 1000).toLong()
            // Frames without a time (station, ephemerides) belong with the observations that follow them.
            while (fi < frames.size) {
                var j = fi
                while (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs) == null) j++
                if (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs)!! > towMs + 50) break
                while (fi <= minOf(j, frames.size - 1)) { RtkNative.inputRtcm(frames[fi], 0, frames[fi].size); fi++ }
            }
            arrays.fill(e.forEngine(options))
            if (options.ppp) iono.update(IonoChooser.dualSatellites(e))?.let { f -> RtkNative.setIono(if (f) 1 else 0); convergence.reset() }
            val rst = RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d,
                arrays.snr, arrays.lli, solOut)
            RtkNative.base(baseOut)
            Pair(rst, RtkNative.message())
        }
        val st = engine.first
        val sol = com.rfsat.pocketgnss.rtk.HonestAccuracy.apply(FixGate.apply(RtkSolution.from(solOut), options))
        val base = RtkBase.from(baseOut)
        val withPhase = e.obs.count { it.phase != null }
        val prev = PhoneRtkLink.state.value
        val status = if (st != 0) sol.status else RtkStatus.NONE
        PhoneRtkLink.publish(PhoneRtkState(if (status != RtkStatus.NONE) sol else prev.solution, base, prev.epochs + 1, e.obs.size, withPhase,
            engine.second.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "", noPhase = withPhase == 0,
            source = if (status != RtkStatus.NONE) PhoneRtkLink.methodName(status, convergence.converging(sol)) else prev.source,
            ionoFree = iono.ionoFree))
        if (status == RtkStatus.NONE) return 0
        val nmea = PhoneRtkLink.nmea(sol, base)
        val n = minOf(nmea.size, buffer.size)
        nmea.copyInto(buffer, 0, 0, n)
        if (n < nmea.size) queue.add(nmea.copyOfRange(n, nmea.size))
        return n
    }

    /** Corrections from a live caster are not mixed into a replay. */
    override fun write(data: ByteArray) {}

    override fun close() {
        if (closed) return
        closed = true
        try { rinex?.close(); log?.close() } catch (_: Exception) {}
        RtkNative.freeFor(this)
        Replay.state.value = Replay.state.value.copy(active = false)
    }
}
