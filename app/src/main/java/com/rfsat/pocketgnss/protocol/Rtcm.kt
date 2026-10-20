package com.rfsat.pocketgnss.protocol

import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * RTCM 3.x framing (0xD3, 6 reserved bits + 10-bit length, payload, CRC-24Q), as streamed by NTRIP
 * casters (METRICA / HEXAGON, EUREF-IP, a DJI D-RTK2 or EOS Arrow Gold base, D3.3 §4.3-4.5).
 * The app does not decode observations; it checks each frame, counts message types and reads the
 * base station's antenna reference point (1005 / 1006) to show the baseline length, which drives
 * RTK accuracy (D3.3 §4.2: keep it under ~50 km).
 */

data class RtcmFrame(val type: Int, val stationId: Int?, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is RtcmFrame && other.type == type && other.bytes.contentEquals(bytes)
    override fun hashCode() = type * 31 + bytes.contentHashCode()
}

/** Base station antenna reference point (ECEF, metres) from message 1005 / 1006. */
data class BaseStation(val stationId: Int, val x: Double, val y: Double, val z: Double, val antennaHeight: Double?) {
    val geodetic: Geodetic get() = Geo.ecefToGeodetic(x, y, z)
}

object Rtcm {
    const val PREAMBLE = 0xD3

    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 16
            repeat(8) { c = c shl 1; if (c and 0x1000000 != 0) c = c xor 0x1864CFB }
            t[i] = c and 0xFFFFFF
        }
    }

    fun crc24q(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) crc = ((crc shl 8) and 0xFFFFFF) xor crcTable[((crc shr 16) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }

    /** Builds a frame around [payload] (for tests and the simulator). */
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 6)
        out[0] = PREAMBLE.toByte(); out[1] = ((payload.size shr 8) and 0x03).toByte(); out[2] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 3)
        val crc = crc24q(out, 0, payload.size + 3)
        out[payload.size + 3] = (crc shr 16).toByte(); out[payload.size + 4] = (crc shr 8).toByte(); out[payload.size + 5] = crc.toByte()
        return out
    }

    /** Reads [len] bits big-endian starting at bit [pos] of [b] (unsigned). */
    fun bits(b: ByteArray, pos: Int, len: Int): Long {
        var v = 0L
        for (i in pos until pos + len) {
            val bit = (b[i / 8].toInt() shr (7 - i % 8)) and 1
            v = (v shl 1) or bit.toLong()
        }
        return v
    }

    fun signedBits(b: ByteArray, pos: Int, len: Int): Long {
        val v = bits(b, pos, len)
        return if (v and (1L shl (len - 1)) != 0L) v - (1L shl len) else v
    }

    /** Message type (first 12 bits of the payload) of a complete frame. */
    fun type(frame: ByteArray): Int = if (frame.size >= 5) bits(frame, 24, 12).toInt() else 0

    /** Reference station ID of the common message families (1001-1012, 1019+, MSM 1071-1137, 1005/1006, 1033). */
    fun stationId(frame: ByteArray): Int? = if (frame.size >= 6) bits(frame, 36, 12).toInt() else null

    /** Base station position from a 1005 or 1006 frame; null for other types. */
    fun baseStation(frame: ByteArray): BaseStation? {
        val t = type(frame)
        if (t != 1005 && t != 1006) return null
        if (frame.size < 3 + (if (t == 1005) 19 else 21) + 3) return null
        val o = 24 // payload starts after the 3-byte header
        val station = bits(frame, o + 12, 12).toInt()
        // type 12, station 12, ITRF year 6, GPS/GLO/GAL/ref-station flags 4 -> X at bit 34
        val x = signedBits(frame, o + 34, 38) * 0.0001
        // single receiver oscillator 1, reserved 1
        val y = signedBits(frame, o + 74, 38) * 0.0001
        // quarter cycle 2
        val z = signedBits(frame, o + 114, 38) * 0.0001
        val h = if (t == 1006) bits(frame, o + 152, 16) * 0.0001 else null
        return BaseStation(station, x, y, z, h)
    }

    /** Short description of a message type for the corrections panel. */
    fun describe(type: Int): String = when (type) {
        1005, 1006 -> "Base position"
        1007, 1008, 1033 -> "Antenna / receiver"
        1019 -> "GPS ephemeris"
        1020 -> "GLONASS ephemeris"
        1042 -> "BeiDou ephemeris"
        1045, 1046 -> "Galileo ephemeris"
        1230 -> "GLONASS biases"
        in 1001..1004 -> "GPS L1/L2 obs (legacy)"
        in 1009..1012 -> "GLONASS obs (legacy)"
        in 1071..1077 -> "GPS MSM${type - 1070}"
        in 1081..1087 -> "GLONASS MSM${type - 1080}"
        in 1091..1097 -> "Galileo MSM${type - 1090}"
        in 1101..1107 -> "SBAS MSM${type - 1100}"
        in 1111..1117 -> "QZSS MSM${type - 1110}"
        in 1121..1127 -> "BeiDou MSM${type - 1120}"
        in 1131..1137 -> "NavIC MSM${type - 1130}"
        4072 -> "u-blox proprietary"
        else -> "Type $type"
    }

    /** True for observation messages (the ones a rover needs every epoch). */
    fun isObservation(type: Int) = type in 1001..1004 || type in 1009..1012 || type in 1071..1137
}

data class Geodetic(val latitude: Double, val longitude: Double, val height: Double)

/** WGS-84 geodesy: geodetic <-> ECEF <-> local east/north/up, distances and bearings. */
object Geo {
    const val A = 6378137.0
    const val F = 1 / 298.257223563
    val E2 = F * (2 - F)

    fun geodeticToEcef(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val sl = Math.sin(lat); val cl = Math.cos(lat)
        val n = A / sqrt(1 - E2 * sl * sl)
        return doubleArrayOf((n + h) * cl * Math.cos(lon), (n + h) * cl * Math.sin(lon), (n * (1 - E2) + h) * sl)
    }

    /** Bowring-style iteration; millimetre-exact for terrestrial points. */
    fun ecefToGeodetic(x: Double, y: Double, z: Double): Geodetic {
        val lon = atan2(y, x)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - E2))
        var h = 0.0
        repeat(6) {
            val sl = Math.sin(lat)
            val n = A / sqrt(1 - E2 * sl * sl)
            h = p / Math.cos(lat) - n
            lat = atan2(z, p * (1 - E2 * n / (n + h)))
        }
        return Geodetic(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }

    /** East, north, up (metres) of a point relative to a reference point. */
    fun enu(refLat: Double, refLon: Double, refH: Double, lat: Double, lon: Double, h: Double): DoubleArray {
        val r = geodeticToEcef(refLat, refLon, refH)
        val p = geodeticToEcef(lat, lon, h)
        val dx = p[0] - r[0]; val dy = p[1] - r[1]; val dz = p[2] - r[2]
        val la = Math.toRadians(refLat); val lo = Math.toRadians(refLon)
        val e = -Math.sin(lo) * dx + Math.cos(lo) * dy
        val n = -Math.sin(la) * Math.cos(lo) * dx - Math.sin(la) * Math.sin(lo) * dy + Math.cos(la) * dz
        val u = Math.cos(la) * Math.cos(lo) * dx + Math.cos(la) * Math.sin(lo) * dy + Math.sin(la) * dz
        return doubleArrayOf(e, n, u)
    }

    /** Point at east/north offsets (metres) from a reference (inverse of [enu] for small offsets, used by the simulator). */
    fun offset(lat: Double, lon: Double, east: Double, north: Double): Pair<Double, Double> {
        val la = Math.toRadians(lat)
        val sl = Math.sin(la)
        val rn = A / sqrt(1 - E2 * sl * sl)
        val rm = rn * (1 - E2) / (1 - E2 * sl * sl)
        return (lat + Math.toDegrees(north / rm)) to (lon + Math.toDegrees(east / (rn * Math.cos(la))))
    }

    /** Ellipsoidal distance approximation (haversine on the mean radius); fine for baselines and stake-out. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees (0 = north, clockwise). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
