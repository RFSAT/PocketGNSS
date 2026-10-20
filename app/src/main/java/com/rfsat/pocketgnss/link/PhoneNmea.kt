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
)

/**
 * Turns the phone's own GNSS (Android Location + GnssStatus) into the same NMEA a receiver sends: GGA, RMC, VTG,
 * GSA per constellation, GSV per constellation and signal, and GST carrying Android's accuracy estimate. Then
 * every screen and tool works with the phone as the source. Phones give single-frequency or L1/L5 autonomous fixes
 * (no RTK), so the fix quality is 1 (autonomous). Pure Kotlin, unit-tested.
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

        if (fix != null) {
            val (la, ns) = Nmea.formatLat(fix.latitude, 7)
            val (lo, ew) = Nmea.formatLon(fix.longitude, 7)
            val kn = (fix.speed ?: 0f) / 0.514444f
            val course = fix.bearing?.let { "%.1f".format(Locale.US, it) } ?: ""
            // Height split into MSL + geoid separation when Android provides both, else ellipsoid height with separation 0.
            val msl = fix.altitudeMsl ?: fix.altitude
            val sep = if (fix.altitudeMsl != null && fix.altitude != null) fix.altitude - fix.altitudeMsl else 0.0
            // HDOP is not given by Android: a nominal value derived from the accuracy keeps estimators sensible.
            val hdop = ((fix.accuracy ?: 5f) / 2.5f).coerceIn(0.5f, 20f)
            add("GNRMC,$tm,A,$la,$ns,$lo,$ew,%.3f,$course,$date,,,A,V".format(Locale.US, kn))
            add("GNVTG,$course,T,,M,%.3f,N,%.3f,K,A".format(Locale.US, kn, kn * 1.852f))
            add("GNGGA,$tm,$la,$ns,$lo,$ew,1,%02d,%.2f,%s,M,%.1f,M,,".format(Locale.US, used.size.coerceAtMost(99), hdop,
                msl?.let { "%.3f".format(Locale.US, it) } ?: "", sep))
            fix.accuracy?.let { a ->
                val sd = a / sqrt(2.0)
                add("GNGST,$tm,,%.3f,%.3f,0.0,%.3f,%.3f,%s".format(Locale.US, sd, sd, sd, sd,
                    fix.verticalAccuracy?.let { "%.3f".format(Locale.US, it) } ?: ""))
            }
        } else {
            add("GNRMC,$tm,V,,,,,,,$date,,,N,V")
            add("GNGGA,$tm,,,,,0,00,99.99,,,,,,")
        }
        // GSA: one per constellation with satellites used.
        for ((talker, list) in used.groupBy { it.first.first }) {
            val ids = list.map { "%02d".format(it.first.second) }.take(12)
            add("GNGSA,A,${if (fix != null) 3 else 1},${(ids + List(12 - ids.size) { "" }).joinToString(",")},,,,${systemId(talker)}")
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
