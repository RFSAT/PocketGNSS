package com.rfsat.pocketgnss.rtk

import com.rfsat.pocketgnss.raw.RawEpoch

/** How the phone is used: moving (kinematic, the default) or held still on a point (static). */
enum class RtkMode(val code: Int) { KINEMATIC(1), STATIC(2) }

/** Engine settings. Defaults suit phones: noisy code (error ratio 300), 15° mask, no GLONASS ambiguity fixing. */
data class RtkOptions(
    val mode: RtkMode = RtkMode.KINEMATIC,
    val gps: Boolean = true,
    val galileo: Boolean = true,
    val glonass: Boolean = true,
    val beidou: Boolean = true,
    val qzss: Boolean = true,
    val elevationMaskDeg: Double = 15.0,
    val snrMaskDbHz: Double = 0.0,
    /** 0 off, 1 continuous, 3 fix-and-hold (RTKLIB ARMODE_*). */
    val arMode: Int = 1,
    val glonassAr: Boolean = false,
    val errorRatio: Double = 300.0,
    val arRatio: Double = 3.0,
    /** Base observations older than this are not used (s). */
    val maxBaseAgeSec: Double = 30.0,
) {
    val navsys: Int get() = (if (gps) 0x01 else 0) or (if (glonass) 0x04 else 0) or (if (galileo) 0x08 else 0) or
        (if (qzss) 0x10 else 0) or (if (beidou) 0x20 else 0)
}

/** Solution status, as RTKLIB's SOLQ_* values. */
enum class RtkStatus(val code: Int, val ggaQuality: Int, val label: String) {
    NONE(0, 0, "none"),
    FIX(1, 4, "RTK fixed"),
    FLOAT(2, 5, "RTK float"),
    SBAS(3, 2, "SBAS"),
    DGPS(4, 2, "DGNSS"),
    SINGLE(5, 1, "single");

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: NONE }
}

data class RtkSolution(
    val status: RtkStatus,
    val week: Int,
    val tow: Double,
    val latitude: Double,
    val longitude: Double,
    /** Ellipsoidal height, m. */
    val height: Double,
    val stdEast: Double,
    val stdNorth: Double,
    val stdUp: Double,
    val velEast: Double,
    val velNorth: Double,
    val velUp: Double,
    val satellites: Int,
    val ratio: Double,
    /** Age of differential, s. */
    val age: Double,
    /** Distance to the base, m (0 without a base). */
    val baseline: Double,
    /** Geoid separation (EGM96), m: height above sea level = [height] - [geoid]. */
    val geoid: Double,
) {
    val speed: Double get() = kotlin.math.hypot(velEast, velNorth)
    val courseDeg: Double? get() = if (speed > 0.2) (Math.toDegrees(kotlin.math.atan2(velEast, velNorth)) + 360) % 360 else null

    companion object {
        const val SIZE = 20
        fun from(v: DoubleArray): RtkSolution = RtkSolution(RtkStatus.of(v[0].toInt()), v[1].toInt(), v[2], v[3], v[4], v[5],
            v[9], v[10], v[11], v[12], v[13], v[14], v[15].toInt(), v[16], v[17], v[18], v[19])
    }
}

/** What the engine has from the caster. */
data class RtkBase(
    val hasPosition: Boolean,
    val latitude: Double,
    val longitude: Double,
    val height: Double,
    val stationId: Int,
    val satellites: Int,
    val obsWeek: Int,
    val obsTow: Double,
    val ephemerides: Int,
    val glonassEphemerides: Int,
    val frames: Long,
) {
    companion object {
        const val SIZE = 14
        fun from(v: DoubleArray) = RtkBase(v[0] != 0.0, v[4], v[5], v[6], v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10],
            v[11].toInt(), v[12].toInt(), v[13].toLong())
    }
}

/** A rover epoch as the parallel arrays [RtkNative.rover] takes. */
class RoverArrays(capacity: Int = 256) {
    var n = 0; private set
    var sys = ByteArray(capacity); private set
    var prn = IntArray(capacity); private set
    var codes = ByteArray(capacity * 2); private set
    var p = DoubleArray(capacity); private set
    var l = DoubleArray(capacity); private set
    var d = FloatArray(capacity); private set
    var snr = FloatArray(capacity); private set
    var lli = IntArray(capacity); private set

    /** Fills the arrays from a converted epoch (missing values as 0, as RTKLIB expects). */
    fun fill(e: RawEpoch): RoverArrays {
        if (e.obs.size > sys.size) {
            val c = e.obs.size
            sys = ByteArray(c); prn = IntArray(c); codes = ByteArray(c * 2); p = DoubleArray(c); l = DoubleArray(c)
            d = FloatArray(c); snr = FloatArray(c); lli = IntArray(c)
        }
        n = 0
        for (o in e.obs) {
            if (o.code.length != 2) continue
            sys[n] = o.system.code.toByte()
            prn[n] = o.prn
            codes[2 * n] = o.code[0].code.toByte(); codes[2 * n + 1] = o.code[1].code.toByte()
            p[n] = o.pseudorange ?: 0.0
            l[n] = o.phase ?: 0.0
            d[n] = (o.doppler ?: 0.0).toFloat()
            snr[n] = o.cn0.toFloat()
            lli[n] = o.lli
            n++
        }
        return this
    }
}
