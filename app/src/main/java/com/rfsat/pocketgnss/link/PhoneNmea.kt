package com.rfsat.pocketgnss.link

import com.rfsat.pocketgnss.protocol.Nmea
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

/** One satellite as Android's GnssStatus reports it (constellation codes of android.location.GnssStatus). */
data class PhoneSat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
    /** Carrier frequency in Hz, 0 when unknown (before Android 8 or not reported). */
    val carrierHz: Float,
)

/** The phone's location, as from android.location.Location. */
data class PhoneFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** WGS-84 ellipsoid height, metres (Android's Location altitude). */
    val altitude: Double?,
    /** Mean-sea-level altitude (Android 14+), metres. */
    val altitudeMsl: Double?,
    val accuracy: Float?,
    val verticalAccuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    /** GGA fix quality: 1 autonomous, 2 differential, 4 RTK fixed, 5 RTK float (phone RTK engine). */
    val quality: Int = 1,
    /** Age of the corrections, s, and the base station ID (GGA fields 13-14). */
    val diffAgeSec: Double? = null,
    val stationId: Int? = null,
    /** Standard deviations east / north / up from the RTK filter, m (GST); null = from [accuracy]. */
    val stdEast: Double? = null,
    val stdNorth: Double? = null,
    val stdUp: Double? = null,
    /** Satellites in the solution, when the engine says (else those Android marks "used in fix"). */
    val satellitesUsed: Int? = null,
)

/**
 * Turns the phone's own GNSS (Android Location + GnssStatus) into the same NMEA a receiver sends: GGA, RMC, VTG,
 * GSA per constellation, GSV per constellation and signal, and GST carrying Android's accuracy estimate. Then
 * every screen and tool works with the phone as the source. From Android's location the fix quality is 1
 * (autonomous); with the phone RTK engine the GGA carries its status (4 fixed, 5 float), correction age and base
 * station, and GST its east / north / up standard deviations. Pure Kotlin, unit-tested.
 */
object PhoneNmea {
    const val GPS = 1; const val SBAS = 2; const val GLONASS = 3; const val QZSS = 4; const val BEIDOU = 5; const val GALILEO = 6; const val IRNSS = 7

    /** NMEA talker, satellite number and signal ID for a satellite; null when NMEA cannot express it. */
    fun nmeaId(s: PhoneSat): Triple<String, Int, String>? {
        val f = s.carrierHz.toDouble()
        fun near(hz: Double) = f > 0 && abs(f - hz) < 2e6
        return when (s.constellation) {
            GPS -> if (s.svid in 1..32) Triple("GP", s.svid, if (near(1176.45e6)) "7" else if (near(1227.60e6)) "6" else "1") else null
            SBAS -> if (s.svid in 120..158) Triple("GP", s.svid - 87, "1") else null
            GLONASS -> if (s.svid in 1..24) Triple("GL", s.svid + 64, if (near(1246e6)) "3" else "1") else null
            GALILEO -> if (s.svid in 1..36) Triple("GA", s.svid, if (near(1176.45e6)) "1" else if (near(1207.14e6)) "2" else "7") else null
            BEIDOU -> if (s.svid in 1..63) Triple("GB", s.svid, when { near(1176.45e6) -> "5"; near(1575.42e6) -> "3"; near(1207.14e6) -> "B"; else -> "1" }) else null
            QZSS -> if (s.svid in 193..202) Triple("GQ", s.svid - 192, if (near(1176.45e6)) "7" else "1") else null
            IRNSS -> if (s.svid in 1..14) Triple("GI", s.svid, "1") else null
            else -> null
        }
    }

    /**
     * Dilution of precision from the geometry of the satellites used, (PDOP, HDOP, VDOP), one receiver clock as in
     * RTKLIB's dops(); null with fewer than 4 satellites or a degenerate geometry. Azimuth / elevation in degrees.
     */
    fun dops(azEl: List<Pair<Double, Double>>): Triple<Double, Double, Double>? {
        if (azEl.size < 4) return null
        val n = Array(4) { DoubleArray(4) }
        for ((az, el) in azEl) {
            val a = Math.toRadians(az); val e = Math.toRadians(el)
            val h = doubleArrayOf(Math.cos(e) * Math.sin(a), Math.cos(e) * Math.cos(a), Math.sin(e), 1.0)
            for (i in 0..3) for (j in 0..3) n[i][j] += h[i] * h[j]
        }
        // invert the 4 x 4 normal matrix (Gauss-Jordan with partial pivoting)
        val q = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }
        for (c in 0..3) {
            val p = (c..3).maxByOrNull { abs(n[it][c]) }!!
            if (abs(n[p][c]) < 1e-12) return null
            if (p != c) { val t = n[p]; n[p] = n[c]; n[c] = t; val u = q[p]; q[p] = q[c]; q[c] = u }
            val d = n[c][c]
            for (j in 0..3) { n[c][j] /= d; q[c][j] /= d }
            for (r in 0..3) if (r != c) {
                val f = n[r][c]
                if (f != 0.0) for (j in 0..3) { n[r][j] -= f * n[c][j]; q[r][j] -= f * q[c][j] }
            }
        }
        if (q[0][0] < 0 || q[1][1] < 0 || q[2][2] < 0) return null
        return Triple(sqrt(q[0][0] + q[1][1] + q[2][2]), sqrt(q[0][0] + q[1][1]), sqrt(q[2][2]))
    }

    /** Elevation mask of the phone RTK engine (degrees): its solutions' DOP counts satellites at or above it. */
    const val ENGINE_MASK_DEG = 15.0

    private fun systemId(talker: String) = when (talker) { "GP" -> 1; "GL" -> 2; "GA" -> 3; "GB" -> 4; "GQ" -> 5; "GI" -> 6; else -> 0 }

    /** NMEA sentences for one epoch. [fix] null = no position yet (satellites only). */
    fun epoch(fix: PhoneFix?, sats: List<PhoneSat>, nowMs: Long = fix?.timeMs ?: System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>()
        fun add(body: String) = out.add(Nmea.sentence(body))
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = nowMs }
        val sod = cal.get(Calendar.HOUR_OF_DAY) * 3600.0 + cal.get(Calendar.MINUTE) * 60.0 + cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        val tm = Nmea.formatTime(sod)
        val date = "%02d%02d%02d".format(Locale.US, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100)
        val mapped = sats.mapNotNull { s -> nmeaId(s)?.let { it to s } }
        val used = mapped.filter { it.second.usedInFix }.distinctBy { it.first.first to it.first.second }
        // DOP from the satellites used; for the engine's solutions (differential / RTK) those above its mask.
        val dop = dops(used.map { it.second }.filter { fix == null || fix.quality == 1 || it.elevation >= ENGINE_MASK_DEG }
            .map { it.azimuth.toDouble() to it.elevation.toDouble() })
        // GSA: one per constellation with satellites used, with PDOP / HDOP / VDOP of the whole solution.
        val gsa = used.groupBy { it.first.first }.map { (talker, list) ->
            val ids = list.map { "%02d".format(it.first.second) }.take(12)
            val d = dop?.let { "%.2f,%.2f,%.2f".format(Locale.US, it.first, it.second, it.third) } ?: ",,"
            "GNGSA,A,${if (fix != null) 3 else 1},${(ids + List(12 - ids.size) { "" }).joinToString(",")},$d,${systemId(talker)}"
        }

        if (fix != null) {
            val (la, ns) = Nmea.formatLat(fix.latitude, 7)
            val (lo, ew) = Nmea.formatLon(fix.longitude, 7)
            val kn = (fix.speed ?: 0f) / 0.514444f
            val course = fix.bearing?.let { "%.1f".format(Locale.US, it) } ?: ""
            // Height split into MSL + geoid separation when Android provides both, else ellipsoid height with separation 0.
            val msl = fix.altitudeMsl ?: fix.altitude
            val sep = if (fix.altitudeMsl != null && fix.altitude != null) fix.altitude - fix.altitudeMsl else 0.0
            // HDOP from the geometry; without satellite positions a nominal value from the accuracy keeps estimators sensible.
            val hdop = dop?.second?.toFloat() ?: ((fix.accuracy ?: 5f) / 2.5f).coerceIn(0.5f, 20f)
            // NMEA 4.1 mode letter: A autonomous, D differential, R RTK fixed, F RTK float.
            val mode = when (fix.quality) { 4 -> "R"; 5 -> "F"; 2 -> "D"; else -> "A" }
            val nUsed = fix.satellitesUsed ?: used.size
            add("GNRMC,$tm,A,$la,$ns,$lo,$ew,%.3f,$course,$date,,,$mode,V".format(Locale.US, kn))
            add("GNVTG,$course,T,,M,%.3f,N,%.3f,K,$mode".format(Locale.US, kn, kn * 1.852f))
            val age = if (fix.quality != 1 && fix.diffAgeSec != null) "%.1f".format(Locale.US, fix.diffAgeSec) else ""
            val station = if (age.isNotEmpty() && fix.stationId != null && fix.stationId > 0) "%04d".format(Locale.US, fix.stationId % 10000) else ""
            // GST before GGA: the fix is assembled when GGA arrives, so its accuracy must already be there
            // (otherwise each fix would carry the previous epoch's accuracy, possibly from another method).
            if (fix.stdEast != null && fix.stdNorth != null) {
                val rms = sqrt(fix.stdEast * fix.stdEast + fix.stdNorth * fix.stdNorth)
                add("GNGST,$tm,%.3f,%.3f,%.3f,0.0,%.3f,%.3f,%s".format(Locale.US, rms, maxOf(fix.stdEast, fix.stdNorth), minOf(fix.stdEast, fix.stdNorth),
                    fix.stdNorth, fix.stdEast, fix.stdUp?.let { "%.3f".format(Locale.US, it) } ?: ""))
            } else fix.accuracy?.let { a ->
                val sd = a / sqrt(2.0)
                add("GNGST,$tm,,%.3f,%.3f,0.0,%.3f,%.3f,%s".format(Locale.US, sd, sd, sd, sd,
                    fix.verticalAccuracy?.let { "%.3f".format(Locale.US, it) } ?: ""))
            }
            // GSA before GGA too, so this epoch's fix carries this epoch's DOP.
            gsa.forEach(::add)
            add("GNGGA,$tm,$la,$ns,$lo,$ew,${fix.quality},%02d,%.2f,%s,M,%.3f,M,$age,$station".format(Locale.US, nUsed.coerceAtMost(99), hdop,
                msl?.let { "%.3f".format(Locale.US, it) } ?: "", sep))
        } else {
            add("GNRMC,$tm,V,,,,,,,$date,,,N,V")
            add("GNGGA,$tm,,,,,0,00,99.99,,,,,,")
            gsa.forEach(::add)
        }
        // GSV: per constellation and signal, four satellites per sentence.
        for ((key, list) in mapped.groupBy { it.first.first to it.first.third }) {
            val (talker, sig) = key
            val pages = (list.size + 3) / 4
            list.chunked(4).forEachIndexed { i, chunk ->
                val f = chunk.joinToString(",") { (id, s) ->
                    "%02d,%02d,%03d,%s".format(Locale.US, id.second, s.elevation.toInt().coerceIn(0, 90), ((s.azimuth.toInt() % 360) + 360) % 360,
                        if (s.cn0 > 0) "%02d".format(Locale.US, s.cn0.toInt()) else "")
                }
                add("${talker}GSV,$pages,${i + 1},%02d,$f,$sig".format(Locale.US, list.size))
            }
        }
        return out
    }
}
