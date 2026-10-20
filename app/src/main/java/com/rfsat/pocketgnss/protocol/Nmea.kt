package com.rfsat.pocketgnss.protocol

import java.util.Locale

/*
 * NMEA-0183 (4.10 / 4.11 as output by u-blox ZED-F9P / ZED-F9R) parsing.
 *
 * The RFSAT AgriBIT receiver ships with DTM, GGA, GLL, GNS, GSA and RMC enabled (D3.3 §4.7.22); GSV, VTG
 * and GST appear when enabled in u-center or with this app's NMEA output tool. AGENSO's applications use
 * GGA and RMC (D3.5 §4.3). Everything here is pure Kotlin so it runs in plain JVM unit tests.
 */

/** UTC time of day from an hhmmss.ss field. */
data class UtcTime(val hour: Int, val minute: Int, val second: Double) {
    val secondsOfDay: Double get() = hour * 3600.0 + minute * 60.0 + second
    override fun toString() = "%02d:%02d:%05.2f".format(Locale.US, hour, minute, second)
}

data class UtcDate(val day: Int, val month: Int, val year: Int)

/** GNSS a satellite or sentence belongs to. */
enum class Constellation(val short: String) {
    GPS("GPS"), GLONASS("GLO"), GALILEO("GAL"), BEIDOU("BDS"), QZSS("QZS"), NAVIC("IRN"), SBAS("SBAS"), UNKNOWN("?");

    companion object {
        /** NMEA 4.10 GSA system ID (last field). */
        fun fromSystemId(id: Int?): Constellation = when (id) {
            1 -> GPS; 2 -> GLONASS; 3 -> GALILEO; 4 -> BEIDOU; 5 -> QZSS; 6 -> NAVIC
            else -> UNKNOWN
        }

        /** Constellation of a GSV sentence (talker), refined by satellite number for SBAS on GP. */
        fun fromTalker(talker: String, svid: Int = 0): Constellation = when (talker) {
            "GP" -> if (svid in 33..64 || svid in 120..158) SBAS else GPS
            "GL" -> GLONASS
            "GA" -> GALILEO
            "GB", "BD" -> BEIDOU
            "GQ", "QZ" -> QZSS
            "GI" -> NAVIC
            else -> UNKNOWN
        }
    }
}

/** NMEA GGA fix quality indicator. */
enum class FixQuality(val code: Int) {
    NONE(0), SINGLE(1), DGNSS(2), PPS(3), RTK_FIXED(4), RTK_FLOAT(5), DEAD_RECKONING(6), MANUAL(7), SIMULATION(8);

    /** Rank for "at least this good" filters: none < DR < single < DGNSS < float < fixed. */
    val rank: Int get() = when (this) {
        NONE, MANUAL, SIMULATION -> 0
        DEAD_RECKONING -> 1
        SINGLE, PPS -> 2
        DGNSS -> 3
        RTK_FLOAT -> 4
        RTK_FIXED -> 5
    }

    companion object {
        fun of(code: Int?) = entries.firstOrNull { it.code == code } ?: NONE
        /** From an RMC / GNS / GLL mode character (NMEA 2.3+). */
        fun fromMode(c: Char?): FixQuality = when (c) {
            'A' -> SINGLE; 'D' -> DGNSS; 'R' -> RTK_FIXED; 'F' -> RTK_FLOAT; 'E' -> DEAD_RECKONING
            'M' -> MANUAL; 'S' -> SIMULATION; 'P' -> SINGLE
            else -> NONE
        }
    }
}

data class SatInView(val svid: Int, val elevationDeg: Int?, val azimuthDeg: Int?, val cn0: Int?)

sealed class NmeaSentence {
    abstract val talker: String
    abstract val type: String

    data class Gga(
        override val talker: String, val time: UtcTime?, val latitude: Double?, val longitude: Double?,
        val quality: FixQuality, val satellitesUsed: Int?, val hdop: Double?, val altitudeMsl: Double?,
        val geoidSeparation: Double?, val diffAgeSec: Double?, val diffStation: String?,
    ) : NmeaSentence() { override val type = "GGA" }

    data class Rmc(
        override val talker: String, val time: UtcTime?, val valid: Boolean, val latitude: Double?, val longitude: Double?,
        val speedKnots: Double?, val courseDeg: Double?, val date: UtcDate?, val magVariation: Double?,
        val mode: Char?, val navStatus: Char?,
    ) : NmeaSentence() { override val type = "RMC" }

    /** GNS: one mode character per constellation (GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC). */
    data class Gns(
        override val talker: String, val time: UtcTime?, val latitude: Double?, val longitude: Double?, val modes: String,
        val satellitesUsed: Int?, val hdop: Double?, val altitudeMsl: Double?, val geoidSeparation: Double?,
        val diffAgeSec: Double?, val diffStation: String?, val navStatus: Char?,
    ) : NmeaSentence() { override val type = "GNS" }

    data class Gsa(
        override val talker: String, val selection: Char?, val fixType: Int, val satellites: List<Int>,
        val pdop: Double?, val hdop: Double?, val vdop: Double?, val system: Constellation,
    ) : NmeaSentence() { override val type = "GSA" }

    data class Gsv(
        override val talker: String, val messages: Int, val index: Int, val inView: Int,
        val satellites: List<SatInView>, val signalId: String?,
    ) : NmeaSentence() { override val type = "GSV" }

    /** GST: pseudorange error statistics; the 1-sigma lat / lon / alt errors give the real accuracy. */
    data class Gst(
        override val talker: String, val time: UtcTime?, val rmsRange: Double?, val semiMajor: Double?, val semiMinor: Double?,
        val orientationDeg: Double?, val sdLatitude: Double?, val sdLongitude: Double?, val sdAltitude: Double?,
    ) : NmeaSentence() { override val type = "GST" }

    data class Vtg(
        override val talker: String, val courseTrue: Double?, val speedKnots: Double?, val speedKmh: Double?, val mode: Char?,
    ) : NmeaSentence() { override val type = "VTG" }

    data class Gll(
        override val talker: String, val latitude: Double?, val longitude: Double?, val time: UtcTime?, val valid: Boolean, val mode: Char?,
    ) : NmeaSentence() { override val type = "GLL" }

    data class Dtm(override val talker: String, val datum: String, val subDatum: String, val refDatum: String) : NmeaSentence() {
        override val type = "DTM"
    }

    data class Zda(override val talker: String, val time: UtcTime?, val date: UtcDate?) : NmeaSentence() { override val type = "ZDA" }

    data class Txt(override val talker: String, val severity: Int?, val text: String) : NmeaSentence() { override val type = "TXT" }

    /** Any other sentence (proprietary $PUBX, $PQTM...) kept for counting and the console. */
    data class Other(override val talker: String, override val type: String, val fields: List<String>) : NmeaSentence()
}

object Nmea {

    /** XOR of the characters between '$' and '*', as two upper-case hex digits. */
    fun checksum(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "%02X".format(x)
    }

    /** Builds a complete sentence ("$" + body + "*" + checksum) without line ending. */
    fun sentence(body: String) = "$" + body + "*" + checksum(body)

    /** True when [line] is "$...*hh" with a correct checksum (a line without checksum is rejected). */
    fun checksumOk(line: String): Boolean {
        val l = line.trim()
        if (l.length < 4 || (l[0] != '$' && l[0] != '!')) return false
        val star = l.lastIndexOf('*')
        if (star < 0 || star + 3 > l.length) return false
        return l.substring(star + 1, star + 3).equals(checksum(l.substring(1, star)), ignoreCase = true)
    }

    /** Parses one sentence; null when the checksum is wrong or the line is not NMEA. */
    fun parse(line: String): NmeaSentence? {
        val l = line.trim()
        if (!checksumOk(l)) return null
        val body = l.substring(1, l.lastIndexOf('*'))
        val f = body.split(',')
        val address = f[0]
        if (address.isEmpty()) return null
        if (address[0] == 'P') return NmeaSentence.Other("P", address.drop(1), f.drop(1))
        if (address.length < 5) return null
        val talker = address.substring(0, 2)
        val type = address.substring(2)
        fun s(i: Int) = f.getOrNull(i)?.takeIf { it.isNotEmpty() }
        fun d(i: Int) = s(i)?.toDoubleOrNull()
        fun n(i: Int) = s(i)?.toIntOrNull()
        fun ch(i: Int) = s(i)?.firstOrNull()
        return try {
            when (type) {
                "GGA" -> NmeaSentence.Gga(talker, time(s(1)), lat(s(2), s(3)), lon(s(4), s(5)), FixQuality.of(n(6)), n(7), d(8), d(9),
                    d(11), d(13), s(14))
                "RMC" -> NmeaSentence.Rmc(talker, time(s(1)), s(2) == "A", lat(s(3), s(4)), lon(s(5), s(6)), d(7), d(8), date(s(9)),
                    d(10)?.let { if (s(11) == "W") -it else it }, ch(12), ch(13))
                "GNS" -> NmeaSentence.Gns(talker, time(s(1)), lat(s(2), s(3)), lon(s(4), s(5)), s(6) ?: "", n(7), d(8), d(9), d(10),
                    d(11), s(12), ch(13))
                "GSA" -> NmeaSentence.Gsa(talker, ch(1), n(2) ?: 1, (3..14).mapNotNull { n(it) }, d(15), d(16), d(17),
                    Constellation.fromSystemId(if (f.size > 18) n(18) else null))
                "GSV" -> {
                    val sats = ArrayList<SatInView>()
                    var i = 4
                    // Groups of four fields; an odd trailing field is the NMEA 4.10 signal ID.
                    while (i + 3 < f.size) {
                        val id = n(i)
                        if (id != null) sats.add(SatInView(id, n(i + 1), n(i + 2), n(i + 3)))
                        i += 4
                    }
                    val signal = if ((f.size - 4) % 4 == 1) s(f.size - 1) else null
                    NmeaSentence.Gsv(talker, n(1) ?: 1, n(2) ?: 1, n(3) ?: sats.size, sats, signal)
                }
                "GST" -> NmeaSentence.Gst(talker, time(s(1)), d(2), d(3), d(4), d(5), d(6), d(7), d(8))
                "VTG" -> NmeaSentence.Vtg(talker, d(1), d(5), d(7), ch(9))
                "GLL" -> NmeaSentence.Gll(talker, lat(s(1), s(2)), lon(s(3), s(4)), time(s(5)), s(6) == "A", ch(7))
                "DTM" -> NmeaSentence.Dtm(talker, s(1) ?: "", s(2) ?: "", s(8) ?: "")
                "ZDA" -> NmeaSentence.Zda(talker, time(s(1)),
                    if (n(2) != null && n(3) != null && n(4) != null) UtcDate(n(2)!!, n(3)!!, n(4)!!) else null)
                "TXT" -> NmeaSentence.Txt(talker, n(3), s(4) ?: "")
                else -> NmeaSentence.Other(talker, type, f.drop(1))
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun time(v: String?): UtcTime? {
        if (v == null || v.length < 6) return null
        val h = v.substring(0, 2).toIntOrNull() ?: return null
        val m = v.substring(2, 4).toIntOrNull() ?: return null
        val sec = v.substring(4).toDoubleOrNull() ?: return null
        return UtcTime(h, m, sec)
    }

    fun date(v: String?): UtcDate? {
        if (v == null || v.length != 6) return null
        val d = v.substring(0, 2).toIntOrNull() ?: return null
        val m = v.substring(2, 4).toIntOrNull() ?: return null
        val y = v.substring(4, 6).toIntOrNull() ?: return null
        return UtcDate(d, m, 2000 + y)
    }

    /** ddmm.mmmmm + N/S to signed degrees. */
    fun lat(v: String?, hemi: String?): Double? = angle(v, 2)?.let { if (hemi == "S") -it else it }

    /** dddmm.mmmmm + E/W to signed degrees. */
    fun lon(v: String?, hemi: String?): Double? = angle(v, 3)?.let { if (hemi == "W") -it else it }

    private fun angle(v: String?, degDigits: Int): Double? {
        if (v == null || v.length < degDigits + 2) return null
        val deg = v.substring(0, degDigits).toIntOrNull() ?: return null
        val min = v.substring(degDigits).toDoubleOrNull() ?: return null
        return deg + min / 60.0
    }

    /** Formats degrees as NMEA ddmm.mmmmmmm with hemisphere, for building GGA (NTRIP upload, simulator). */
    fun formatLat(lat: Double, decimals: Int = 7): Pair<String, String> {
        val a = kotlin.math.abs(lat)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return "%02d%0${3 + decimals}.${decimals}f".format(Locale.US, d, m) to (if (lat < 0) "S" else "N")
    }

    fun formatLon(lon: Double, decimals: Int = 7): Pair<String, String> {
        val a = kotlin.math.abs(lon)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return "%03d%0${3 + decimals}.${decimals}f".format(Locale.US, d, m) to (if (lon < 0) "W" else "E")
    }

    fun formatTime(secondsOfDay: Double): String {
        val t = ((secondsOfDay % 86400.0) + 86400.0) % 86400.0
        val h = (t / 3600).toInt()
        val m = ((t - h * 3600) / 60).toInt()
        val s = t - h * 3600 - m * 60
        return "%02d%02d%05.2f".format(Locale.US, h, m, s)
    }

    /** Human name of an NMEA 4.10/4.11 signal ID for a constellation (u-blox F9 assignments). */
    fun signalName(c: Constellation, id: String?): String = when (c to id) {
        Constellation.GPS to "1", Constellation.SBAS to "1" -> "L1C/A"
        Constellation.GPS to "6" -> "L2CL"
        Constellation.GPS to "5" -> "L2CM"
        Constellation.GPS to "7" -> "L5I"
        Constellation.GPS to "8" -> "L5Q"
        Constellation.GLONASS to "1" -> "L1OF"
        Constellation.GLONASS to "3" -> "L2OF"
        Constellation.GALILEO to "7" -> "E1"
        Constellation.GALILEO to "2" -> "E5b"
        Constellation.GALILEO to "1" -> "E5a"
        Constellation.BEIDOU to "1" -> "B1I"
        Constellation.BEIDOU to "B" -> "B2I"
        Constellation.BEIDOU to "3" -> "B1C"
        Constellation.BEIDOU to "5" -> "B2a"
        Constellation.QZSS to "1" -> "L1C/A"
        Constellation.QZSS to "4" -> "L1S"
        Constellation.QZSS to "5" -> "L2CM"
        Constellation.QZSS to "6" -> "L2CL"
        Constellation.NAVIC to "1" -> "L5"
        else -> if (id == null || id == "0") "all" else "sig $id"
    }

    /** True for the second-frequency signals (L2 / E5b / B2I / L5), shown as "dual frequency". */
    fun isSecondFrequency(c: Constellation, id: String?): Boolean = when (c) {
        Constellation.GPS -> id == "5" || id == "6" || id == "7" || id == "8"
        Constellation.GLONASS -> id == "3"
        Constellation.GALILEO -> id == "1" || id == "2"
        Constellation.BEIDOU -> id == "B" || id == "5"
        Constellation.QZSS -> id == "5" || id == "6"
        else -> false
    }

    /** SBAS PRN (120..158) from an NMEA GPS-talker satellite number 33..64 (u-blox convention: PRN - 87). */
    fun sbasPrn(svid: Int) = if (svid in 33..64) svid + 87 else svid

    /** Name of a European / American / Asian SBAS from its PRN (EGNOS: 123, 126, 136; WAAS: 131, 133, 135, 138...). */
    fun sbasSystem(prn: Int): String = when (prn) {
        120, 121, 123, 124, 126, 136 -> "EGNOS"
        122, 131, 133, 134, 135, 138 -> "WAAS"
        127, 128, 132 -> "GAGAN"
        129, 137 -> "MSAS"
        125, 140, 141 -> "SDCM"
        130, 143, 144 -> "BDSBAS"
        else -> "SBAS"
    }
}
