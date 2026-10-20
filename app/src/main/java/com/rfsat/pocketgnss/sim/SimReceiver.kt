package com.rfsat.pocketgnss.sim

import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaMsg
import com.rfsat.pocketgnss.protocol.StartType
import com.rfsat.pocketgnss.protocol.Ubx
import com.rfsat.pocketgnss.protocol.UbxFrame
import com.rfsat.pocketgnss.protocol.UbxPort
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class SimMotion { STATIC, SWATHS }

data class SimConfig(
    val motion: SimMotion = SimMotion.STATIC,
    /** Corrections available (as if the receiver's own NTRIP client streamed them); RTCM from the phone also counts. */
    val corrections: Boolean = true,
    /** Hardware version: how the receiver's own NTRIP client reaches the RTK service (timings from D3.5). */
    val variant: com.rfsat.pocketgnss.link.ReceiverVariant = com.rfsat.pocketgnss.link.ReceiverVariant.WIFI,
    /** Start of the simulated field (an orchard plot in Pella, Central Macedonia: AgriBIT pilot B). */
    val latitude: Double = 40.7567,
    val longitude: Double = 22.1213,
    val heightMsl: Double = 38.0,
)

/**
 * Simulated ZED-F9P behind the RFSAT receiver, for trying every screen and tool without hardware and for
 * Google Play review. Behaviour follows the D3.5 validation: time to first fix, then SBAS (~30 cm),
 * float, and RTK fixed (~1 cm) 25-30 s after corrections start; 1..20 Hz output; the default NMEA set of
 * D3.3 §4.7.22 plus GSV/GST/VTG. It answers UBX CFG-VALSET / VALGET / RST and MON-VER like the real chip.
 */
class SimReceiver(private var config: SimConfig = SimConfig(), seed: Long = 7) {
    private val rnd = Random(seed)

    // Navigation state
    private var t = 0.0                    // seconds since the last (re)start
    private var clockSod = 9 * 3600.0 + 15 * 60.0 // UTC seconds of day
    private var ttffSec = 26.0             // cold start
    private var rtcmLastAt = -1e9
    private var uptime = 0.0               // seconds since power-up (a GNSS restart keeps the network link)
    private var outageUntil = -1.0         // uptime until which the correction link is down
    private var lastCorrAt = -1e9          // uptime of the last correction received
    private var corrSince = -1.0           // uptime since corrections flow continuously, -1 = none
    private var everFixed = false          // first ambiguity resolution done since the last restart
    private var rateMs = 1000
    var enabled: MutableSet<NmeaMsg> = mutableSetOf(NmeaMsg.DTM, NmeaMsg.GGA, NmeaMsg.GLL, NmeaMsg.GNS, NmeaMsg.GSA, NmeaMsg.RMC,
        NmeaMsg.GSV, NmeaMsg.GST, NmeaMsg.VTG)
        private set

    // Errors: first-order Gauss-Markov per axis, metres
    private val err = DoubleArray(3)
    private var lastSecond = -1

    // Motion
    private var along = 0.0
    private var lastQuality = FixQuality.NONE

    data class Sat(val talker: String, val svid: Int, val systemId: Int, var az: Double, val el0: Double, val signals: List<String>)
    private val sats: List<Sat> = buildList {
        val gps = listOf(2, 10, 15, 16, 18, 23, 26, 27, 29, 31)
        gps.forEachIndexed { i, s -> add(Sat("GP", s, 1, i * 36.0 + 10, 12.0 + (i * 53) % 70, listOf("1", "6"))) }
        listOf(36, 49).forEachIndexed { i, s -> add(Sat("GP", s, 1, 166.0 + i * 40, 42.0, listOf("1"))) }   // EGNOS S123, S136
        val glo = listOf(67, 68, 69, 73, 74, 82, 83, 84)
        glo.forEachIndexed { i, s -> add(Sat("GL", s, 2, i * 45.0 + 30, 10.0 + (i * 41) % 60, listOf("1", "3"))) }
        val gal = listOf(2, 5, 9, 15, 25, 30, 34, 36)
        gal.forEachIndexed { i, s -> add(Sat("GA", s, 3, i * 45.0 + 5, 8.0 + (i * 47) % 64, listOf("7", "2"))) }
        val bds = listOf(8, 13, 19, 20, 22, 26, 29, 35)
        bds.forEachIndexed { i, s -> add(Sat("GB", s, 4, i * 45.0 + 20, 14.0 + (i * 37) % 58, listOf("1", "B"))) }
    }

    fun configure(c: SimConfig) { config = c }

    val periodMs: Int get() = rateMs
    val quality: FixQuality get() = lastQuality

    /** Notes that RTCM corrections arrived from the phone (NTRIP client in the app). */
    fun rtcmReceived() { rtcmLastAt = uptime }

    /** Test / demo hook: advances simulated time without output (e.g. straight to RTK fixed for screenshots). */
    fun skip(seconds: Double) {
        t += seconds; clockSod += seconds; uptime += seconds
        if (receiverLinkUp()) { lastCorrAt = uptime; if (corrSince < 0) corrSince = netDelay().coerceAtMost(uptime) }
    }

    fun restart(type: StartType) {
        t = 0.0
        ttffSec = when (type) { StartType.COLD -> 26.0; StartType.WARM -> 12.0; StartType.HOT -> 2.0 }
        err.fill(0.0)
        everFixed = false
    }

    /**
     * Correction link of the receiver's own NTRIP client. D3.5 GNSS-test-07 / -13: over LTE 55-60 s from power-up to
     * RTK data, 16-25 s to re-acquire, ~95 % of the time available; over a Wi-Fi router quicker and ~99 %.
     */
    private fun netDelay() = when (config.variant) { com.rfsat.pocketgnss.link.ReceiverVariant.LTE -> 57.0; else -> 15.0 }
    private fun receiverLinkUp() = config.corrections && uptime >= netDelay() && uptime >= outageUntil

    private fun updateCorrections(dt: Double) {
        if (receiverLinkUp()) {
            val lte = config.variant == com.rfsat.pocketgnss.link.ReceiverVariant.LTE
            // Outage starts: rate = downtime fraction / mean outage length.
            val meanOut = if (lte) 20.0 else 3.0
            val rate = (if (lte) 0.05 else 0.01) / meanOut
            if (rnd.nextDouble() < rate * dt) outageUntil = uptime + if (lte) 16.0 + 9.0 * rnd.nextDouble() else 1.0 + 3.0 * rnd.nextDouble()
        }
        val now = receiverLinkUp() || uptime - rtcmLastAt < 3.0
        if (now) { lastCorrAt = uptime; if (corrSince < 0) corrSince = uptime }
        else if (uptime - lastCorrAt > 5.0) corrSince = -1.0   // the receiver keeps RTK for ~5 s of correction age
    }

    /** Correction age (s) as GGA reports it, or null without corrections. */
    private fun correctionAge(): Double? = if (corrSince >= 0) uptime - lastCorrAt + 0.6 else null

    private fun gauss() = sqrt(-2 * kotlin.math.ln(rnd.nextDouble().coerceAtLeast(1e-12))) * cos(2 * PI * rnd.nextDouble())

    private fun currentQuality(): FixQuality {
        if (t < ttffSec) return FixQuality.NONE
        val sinceFix = t - ttffSec
        if (sinceFix < 6) return FixQuality.SINGLE
        if (sinceFix < 10 || corrSince < 0) return FixQuality.DGNSS
        // Float until the ambiguities fix: 25 s the first time (D3.5: 25-30 s after RTK data), a few s on re-acquisition.
        val start = maxOf(corrSince, uptime - (sinceFix - 10))
        val need = if (everFixed) 4.0 else 25.0
        return if (uptime - start < need) FixQuality.RTK_FLOAT else { everFixed = true; FixQuality.RTK_FIXED }
    }

    /** 1-sigma error per axis (east, north, up) for a fix quality, from the D3.5 results. */
    private fun sigma(q: FixQuality) = when (q) {
        FixQuality.RTK_FIXED -> doubleArrayOf(0.006, 0.007, 0.012)
        FixQuality.RTK_FLOAT -> doubleArrayOf(0.12, 0.14, 0.25)
        FixQuality.DGNSS -> doubleArrayOf(0.10, 0.12, 0.22)
        FixQuality.SINGLE -> doubleArrayOf(0.45, 0.55, 1.0)
        else -> doubleArrayOf(0.0, 0.0, 0.0)
    }

    /** Truth position east/north (m) of the simulated antenna and its speed / course. */
    private fun truth(): DoubleArray {
        if (config.motion == SimMotion.STATIC) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        // Boustrophedon swaths: 120 m rows, 6 m apart, at 2.5 m/s, half-circle turns.
        val row = 120.0; val gap = 6.0; val v = 2.5
        val turn = PI * gap / 2
        val cycle = row + turn
        val s = along % (cycle * 2 * 8)
        val pair = (s / cycle).toInt(); val u = s - pair * cycle
        val eastBase = pair * gap
        val northward = pair % 2 == 0
        return if (u < row) {
            val n = if (northward) u else row - u
            doubleArrayOf(eastBase, n, v, if (northward) 0.0 else 180.0)
        } else {
            val a = (u - row) / (gap / 2) // 0..pi
            val cx = eastBase + gap / 2
            val cy = if (northward) row else 0.0
            val e = cx - (gap / 2) * cos(a)
            val n = if (northward) cy + (gap / 2) * sin(a) else cy - (gap / 2) * sin(a)
            val course = if (northward) Math.toDegrees(PI / 2 - a) else Math.toDegrees(PI / 2 + a)
            doubleArrayOf(e, n, v, (course + 360) % 360)
        }
    }

    /** Advances by one output period and returns the NMEA lines of that epoch. */
    fun epoch(): List<String> {
        val dt = rateMs / 1000.0
        t += dt; clockSod += dt; uptime += dt
        updateCorrections(dt)
        val q = currentQuality()
        if (config.motion == SimMotion.SWATHS && q != FixQuality.NONE) along += 2.5 * dt
        val sg = sigma(q)
        val tau = when (q) { FixQuality.RTK_FIXED -> 4.0; FixQuality.RTK_FLOAT -> 15.0; else -> 40.0 }
        val phi = exp(-dt / tau)
        for (i in 0..2) err[i] = phi * err[i] + sqrt(1 - phi * phi) * sg[i] * gauss()
        // A real receiver jumps when the fix type changes (e.g. ambiguities fixed): restart the error at the new level.
        if (q != lastQuality) for (i in 0..2) err[i] = sg[i] * gauss()
        lastQuality = q
        for (s in sats) s.az = (s.az + dt * 0.004) % 360

        val tr = truth()
        val (lat, lon) = Geo.offset(config.latitude, config.longitude, tr[0] + err[0], tr[1] + err[1])
        val h = config.heightMsl + err[2]
        val tm = Nmea.formatTime(clockSod)
        val date = "201026"
        val (la, ns) = Nmea.formatLat(lat, 7)
        val (lo, ew) = Nmea.formatLon(lon, 7)
        val valid = q != FixQuality.NONE
        val mode = when (q) { FixQuality.RTK_FIXED -> "R"; FixQuality.RTK_FLOAT -> "F"; FixQuality.DGNSS -> "D"; FixQuality.SINGLE -> "A"; else -> "N" }
        val used = if (valid) sats.filter { elev(it) > 10 } else emptyList()
        val hdop = if (valid) 0.62 + 0.1 * sin(t / 50) else 99.99
        val pdop = hdop * 1.9; val vdop = hdop * 1.6
        val speedKn = tr[2] / 0.514444 * (if (valid) 1.0 else 0.0) + (if (valid) abs(gauss()) * 0.01 else 0.0)
        val course = if (tr[2] > 0) "%.2f".format(Locale.US, tr[3]) else ""
        val ageS = correctionAge()
        val age = if (q.rank >= 4 && ageS != null) "%.1f".format(Locale.US, ageS) else ""
        val station = if (q.rank >= 4 && ageS != null) "0089" else ""
        val out = ArrayList<String>()
        fun add(body: String) = out.add(Nmea.sentence(body))
        val fullSecond = clockSod.toInt() != lastSecond
        lastSecond = clockSod.toInt()

        if (NmeaMsg.RMC in enabled) add("GNRMC,$tm,${if (valid) "A" else "V"},${if (valid) "$la,$ns,$lo,$ew" else ",,,"},%.3f,$course,$date,4.87,E,$mode,V".format(Locale.US, speedKn))
        if (NmeaMsg.VTG in enabled) add("GNVTG,$course,T,,M,%.3f,N,%.3f,K,$mode".format(Locale.US, speedKn, speedKn * 1.852))
        if (NmeaMsg.GGA in enabled) add("GNGGA,$tm,${if (valid) "$la,$ns,$lo,$ew" else ",,,"},${q.code},%02d,%.2f,%s,M,%.1f,M,$age,$station".format(Locale.US,
            used.size.coerceAtMost(12), hdop, if (valid) "%.3f".format(Locale.US, h) else "", 36.4))
        if (NmeaMsg.GNS in enabled) {
            val m = if (valid) mode.repeat(4) + "NN" else "NNNNNN"
            add("GNGNS,$tm,${if (valid) "$la,$ns,$lo,$ew" else ",,,"},$m,%02d,%.2f,%s,36.4,$age,$station,V".format(Locale.US, used.size, hdop,
                if (valid) "%.3f".format(Locale.US, h) else ""))
        }
        if (NmeaMsg.GSA in enabled) for (sys in 1..4) {
            val ids = used.filter { it.systemId == sys && !(sys == 1 && it.svid > 32) }.map { "%02d".format(it.svid) }.take(12)
            add("GNGSA,A,${if (valid) 3 else 1},${(ids + List(12 - ids.size) { "" }).joinToString(",")},%.2f,%.2f,%.2f,$sys".format(Locale.US, pdop, hdop, vdop))
        }
        if (NmeaMsg.GSV in enabled && fullSecond) for (talker in listOf("GP", "GL", "GA", "GB")) {
            val mine = sats.filter { it.talker == talker }
            for (sig in mine.first().signals) {
                val list = mine.filter { sig in it.signals }
                val pages = (list.size + 3) / 4
                list.chunked(4).forEachIndexed { pi, chunk ->
                    val f = chunk.joinToString(",") { s ->
                        val e = elev(s)
                        val cn0 = if (!valid && t < 4) "" else "%02d".format((28 + e * 0.32 - (if (sig == s.signals[0]) 0 else 4) + rnd.nextInt(3)).toInt())
                        "%02d,%02d,%03d,%s".format(s.svid, e.toInt(), s.az.toInt(), cn0)
                    }
                    add("${talker}GSV,$pages,${pi + 1},%02d,$f,$sig".format(list.size))
                }
            }
        }
        if (NmeaMsg.GST in enabled && valid) add("GNGST,$tm,%.1f,%.3f,%.3f,0.0,%.3f,%.3f,%.3f".format(Locale.US,
            sg[0] * 20, sg[1] * 1.1, sg[0] * 0.9, sg[1] * (0.9 + 0.2 * rnd.nextDouble()), sg[0] * (0.9 + 0.2 * rnd.nextDouble()), sg[2] * (0.9 + 0.2 * rnd.nextDouble())))
        if (NmeaMsg.GLL in enabled) add("GNGLL,${if (valid) "$la,$ns,$lo,$ew" else ",,,"},$tm,${if (valid) "A" else "V"},$mode")
        if (NmeaMsg.ZDA in enabled) add("GNZDA,$tm,20,10,2026,00,00")
        if (NmeaMsg.DTM in enabled && fullSecond && clockSod.toInt() % 10 == 0) add("GNDTM,W84,,0.0,N,0.0,E,0.0,W84")
        return out
    }

    private fun elev(s: Sat) = (s.el0 + 6 * sin(Math.toRadians(s.az * 3))).coerceIn(3.0, 88.0)

    /** Handles a UBX command written to the simulated receiver; returns the reply frames (ACK, data). */
    fun handleUbx(f: UbxFrame): List<ByteArray> {
        fun ack(ok: Boolean = true) = Ubx.frame(Ubx.CLS_ACK, if (ok) Ubx.ID_ACK_ACK else Ubx.ID_ACK_NAK, byteArrayOf(f.cls.toByte(), f.id.toByte()))
        return when (f.cls to f.id) {
            Ubx.CLS_CFG to Ubx.ID_CFG_VALSET -> {
                var i = 4
                while (i + 4 <= f.payload.size) {
                    val key = Ubx.u32(f.payload, i); i += 4
                    val n = Ubx.valueSize(key)
                    var v = 0L
                    for (b in 0 until n) v = v or ((f.payload[i + b].toLong() and 0xFF) shl (8 * b))
                    i += n
                    NmeaMsg.entries.firstOrNull { it.key(UbxPort.USB) == key }?.let { m -> if (v > 0) enabled.add(m) else enabled.remove(m) }
                    if (key == Ubx.KEY_RATE_MEAS) rateMs = v.toInt().coerceIn(50, 10000)
                }
                listOf(ack())
            }
            Ubx.CLS_CFG to Ubx.ID_CFG_VALGET -> {
                val p = java.io.ByteArrayOutputStream()
                p.write(1); p.write(0); p.write(0); p.write(0)
                var i = 4
                while (i + 4 <= f.payload.size) {
                    val key = Ubx.u32(f.payload, i); i += 4
                    val v: Long = NmeaMsg.entries.firstOrNull { m -> UbxPort.entries.any { m.key(it) == key } }?.let { if (it in enabled) 1L else 0L }
                        ?: if (key == Ubx.KEY_RATE_MEAS) rateMs.toLong() else if (key == Ubx.KEY_RATE_NAV) 1L else continue
                    for (b in 0 until 4) p.write(((key shr (8 * b)) and 0xFF).toInt())
                    for (b in 0 until Ubx.valueSize(key)) p.write(((v shr (8 * b)) and 0xFF).toInt())
                }
                listOf(Ubx.frame(Ubx.CLS_CFG, Ubx.ID_CFG_VALGET, p.toByteArray()), ack())
            }
            Ubx.CLS_CFG to Ubx.ID_CFG_RST -> {
                val mask = (f.payload.getOrElse(0) { 0 }.toInt() and 0xFF) or ((f.payload.getOrElse(1) { 0 }.toInt() and 0xFF) shl 8)
                restart(StartType.entries.firstOrNull { it.navBbrMask == mask } ?: StartType.COLD)
                emptyList() // the real chip does not acknowledge a reset
            }
            Ubx.CLS_MON to Ubx.ID_MON_VER -> {
                val p = ByteArray(40 + 4 * 30)
                "EXT CORE 1.00 (simulated)".toByteArray().copyInto(p, 0)
                "00190000".toByteArray().copyInto(p, 30)
                listOf("ROM BASE 0x118B2060", "FWVER=HPG 1.32", "PROTVER=27.31", "MOD=ZED-F9P (simulated)").forEachIndexed { k, s ->
                    s.toByteArray().copyInto(p, 40 + 30 * k)
                }
                listOf(Ubx.frame(Ubx.CLS_MON, Ubx.ID_MON_VER, p))
            }
            else -> listOf(ack(false))
        }
    }
}
