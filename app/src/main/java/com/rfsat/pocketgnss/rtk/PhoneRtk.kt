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
    /** Minimum satellites with carrier phase before ambiguities are fixed. */
    val minFixSats: Int = 5,
    /** A fix is reported only after this many consecutive fixed epochs (else the float solution is shown). */
    val confirmEpochs: Int = 3,
    /**
     * Precise point positioning instead of RTK: no base station; the caster stream carries SSR orbit, clock and code-bias
     * corrections (Galileo HAS Internet Data Distribution, IGS real-time "SSRA" streams), referred to the antenna phase centre.
     */
    val ppp: Boolean = false,
    /** PPP: ionosphere-free combination (phones with L1 + L5); false = broadcast ionosphere model (L1-only phones). */
    val ionoFree: Boolean = true,
) {
    /** Engine mode (prtk.h PRTK_MODE_*): 1 / 2 RTK kinematic / static, 3 / 4 PPP kinematic / static. */
    val engineMode: Int get() = if (ppp) mode.code + 2 else mode.code

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
    SINGLE(5, 1, "single"),
    /** Precise point positioning (float): reported in GGA as differential (2), never as RTK. */
    PPP(6, 2, "PPP");

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
    /** Float solution (before ambiguity fixing) and its standard deviations; NaN when not available. */
    val floatLatitude: Double = Double.NaN,
    val floatLongitude: Double = Double.NaN,
    val floatHeight: Double = Double.NaN,
    val floatStdEast: Double = Double.NaN,
    val floatStdNorth: Double = Double.NaN,
    val floatStdUp: Double = Double.NaN,
    /** Consecutive epochs with fixed ambiguities (RTKLIB). */
    val consecutiveFixes: Int = 0,
) {
    val hasFloat: Boolean get() = !floatLatitude.isNaN() && !(floatLatitude == 0.0 && floatLongitude == 0.0)
    val speed: Double get() = kotlin.math.hypot(velEast, velNorth)
    val courseDeg: Double? get() = if (speed > 0.2) (Math.toDegrees(kotlin.math.atan2(velEast, velNorth)) + 360) % 360 else null

    companion object {
        const val SIZE = 27
        fun from(v: DoubleArray): RtkSolution = RtkSolution(RtkStatus.of(v[0].toInt()), v[1].toInt(), v[2], v[3], v[4], v[5],
            v[9], v[10], v[11], v[12], v[13], v[14], v[15].toInt(), v[16], v[17], v[18], v[19],
            v[20], v[21], v[22], v[23], v[24], v[25], v[26].toInt())
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
    /** PPP: satellites with current SSR orbit and clock corrections, and with code biases. */
    val ssrSatellites: Int = 0,
    val codeBiasSatellites: Int = 0,
    /** GPS week and time of week of the latest SSR clock correction (0 when none). */
    val ssrWeek: Int = 0,
    val ssrTow: Double = 0.0,
) {
    companion object {
        const val SIZE = 18
        fun from(v: DoubleArray) = RtkBase(v[0] != 0.0, v[4], v[5], v[6], v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10],
            v[11].toInt(), v[12].toInt(), v[13].toLong(), v[14].toInt(), v[17].toInt(), v[15].toInt(), v[16])
    }
}

/**
 * PPP ionosphere model for the phone at hand. The ionosphere-free combination needs two frequencies; on a phone that
 * tracks L1 only it would leave no satellites, so after [switchAfter] epochs with fewer than [minDual] dual-frequency
 * satellites the engine falls back to the broadcast ionosphere model, and goes back when the phone delivers L5 / E5a
 * on at least [goodDual] satellites for [returnAfter] epochs.
 */
class IonoChooser(ionoFree: Boolean = true, private val minDual: Int = 4, private val goodDual: Int = 6,
                  private val switchAfter: Int = 30, private val returnAfter: Int = 60) {
    var ionoFree = ionoFree; private set
    private var run = 0

    /** One epoch with [dual] satellites carrying code and phase on two bands; returns the new choice when it changes. */
    fun update(dual: Int): Boolean? {
        run = if (ionoFree) (if (dual < minDual) run + 1 else 0) else (if (dual >= goodDual) run + 1 else 0)
        if (run >= (if (ionoFree) switchAfter else returnAfter)) { ionoFree = !ionoFree; run = 0; return ionoFree }
        return null
    }

    companion object {
        /** Satellites with pseudorange and carrier phase on L1 and on a second band in [e]. */
        fun dualSatellites(e: com.rfsat.pocketgnss.raw.RawEpoch): Int = e.obs.filter { it.pseudorange != null && it.phase != null && it.code.isNotEmpty() }
            .groupBy { it.sat }.count { (_, o) -> o.any { it.code[0] == '1' } && o.any { it.code[0] != '1' } }
    }
}

/**
 * PPP convergence: a float PPP solution starts at metres and needs typically 20–30 minutes of continuous carrier phase
 * to reach its best. Reported as converged once [minMinutes] have passed since the first PPP solution and the filter's
 * horizontal standard deviation is at most [maxStdM].
 */
class PppConvergence(private val minMinutes: Double = 15.0, private val maxStdM: Double = 0.3) {
    private var startSec: Double? = null

    /** True while still converging; [s] is the latest solution. */
    fun converging(s: RtkSolution): Boolean {
        if (s.status != RtkStatus.PPP) { startSec = null; return false }
        val t = s.week * 604_800.0 + s.tow
        val t0 = startSec ?: t.also { startSec = it }
        return (t - t0) / 60.0 < minMinutes || kotlin.math.hypot(s.stdEast, s.stdNorth) > maxStdM
    }

    fun reset() { startSec = null }
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

/**
 * Trust in an RTK fix. RTKLIB reports a fix as soon as one epoch passes its ratio test; with a phone's few, noisy
 * carrier-phase signals such single-epoch fixes are often wrong (the "fixed" flashes seen in tests). A fix is passed
 * on only when it has held for [RtkOptions.confirmEpochs] consecutive epochs with an ambiguity ratio of at least
 * [RtkOptions.arRatio] and [RtkOptions.minFixSats] satellites; otherwise the solution is reported as float, with the
 * float position and its (larger, honest) standard deviations.
 */
object FixGate {
    fun apply(s: RtkSolution, o: RtkOptions): RtkSolution {
        if (s.status != RtkStatus.FIX) return s
        val trusted = s.consecutiveFixes >= o.confirmEpochs && s.ratio >= o.arRatio && s.satellites >= o.minFixSats
        if (trusted || !s.hasFloat) return if (trusted) s else s.copy(status = RtkStatus.FLOAT)
        return s.copy(status = RtkStatus.FLOAT, latitude = s.floatLatitude, longitude = s.floatLongitude, height = s.floatHeight,
            stdEast = s.floatStdEast, stdNorth = s.floatStdNorth, stdUp = s.floatStdUp)
    }
}

/**
 * Cross-check of the engine against the phone's own position (Android's GNSS fix, usually within 5-15 m). Phone
 * pseudoranges carry occasional gross errors (multipath, weak or badly tracked signals) that the filter takes for
 * metre-level ones: its solution can then sit tens to hundreds of metres away while reporting a few metres of
 * uncertainty. A solution further from the phone's fix than [limit] is not used (the phone's fix is shown instead);
 * when that lasts [resetAfterMs], the filter is restarted, at most once every [resetIntervalMs].
 */
class PositionCheck(private val resetAfterMs: Long = 10_000, private val resetIntervalMs: Long = 30_000) {
    var rejected = 0; private set
    var resets = 0; private set
    private var firstBadMs = 0L
    private var lastResetMs: Long? = null

    enum class Verdict { USE, REJECT, REJECT_AND_RESET }

    /**
     * [engineAcc] / [phoneAcc]: horizontal accuracies (m) of the engine solution and of the phone's fix; without a
     * recent phone fix ([phoneLat] null) the solution is used unchecked.
     */
    fun check(nowMs: Long, lat: Double, lon: Double, engineAcc: Double, phoneLat: Double?, phoneLon: Double?, phoneAcc: Double?): Verdict {
        if (phoneLat == null || phoneLon == null) return Verdict.USE
        val d = com.rfsat.pocketgnss.protocol.Geo.distance(lat, lon, phoneLat, phoneLon)
        if (d <= limit(engineAcc, phoneAcc)) { firstBadMs = 0; return Verdict.USE }
        rejected++
        if (firstBadMs == 0L) firstBadMs = nowMs
        if (nowMs - firstBadMs >= resetAfterMs && lastResetMs.let { it == null || nowMs - it >= resetIntervalMs }) {
            resets++; lastResetMs = nowMs; firstBadMs = 0
            return Verdict.REJECT_AND_RESET
        }
        return Verdict.REJECT
    }

    companion object {
        /** Largest credible distance: 30 m, or more when either position says it is that uncertain. */
        fun limit(engineAcc: Double, phoneAcc: Double?): Double =
            maxOf(30.0, 4.0 * (phoneAcc ?: 10.0) + 3.0 * engineAcc.coerceIn(0.0, 50.0))

        /**
         * Code-only solutions (code DGNSS, single) from raw, unsmoothed phone code are often no better than the chip's
         * own fix, which smooths its ranges with the carrier phase: then the one reporting the better accuracy is
         * used, so phone RTK is never worse than the phone alone. RTK float / fixed and PPP always come from the engine.
         */
        fun preferPhone(status: RtkStatus, engineAcc: Double, phoneAcc: Double?): Boolean =
            (status == RtkStatus.DGPS || status == RtkStatus.SINGLE) && phoneAcc != null && phoneAcc > 0 && phoneAcc < engineAcc
    }
}

/**
 * What PocketGNSS has learned about this phone's raw measurements over past phone RTK sessions: how often enough
 * satellites carried carrier phase to fix ambiguities, and how often the phase slipped. Stored per phone model.
 */
data class PhoneProfile(
    val model: String,
    val epochs: Long = 0,
    /** Mean number of satellites with carrier phase per epoch. */
    val meanPhaseSats: Double = 0.0,
    /** Share of epochs with at least [RtkOptions.minFixSats] satellites with carrier phase. */
    val fixableShare: Double = 0.0,
    /** Cycle slips / resets per satellite-minute of carrier phase. */
    val slipsPerSatMin: Double = 0.0,
) {
    /** Enough data to judge (about 10 minutes at 1 Hz). */
    val learned: Boolean get() = epochs >= 600

    fun encode(): String = listOf(model.replace('\t', ' '), epochs, meanPhaseSats, fixableShare, slipsPerSatMin).joinToString("\t")

    companion object {
        fun decode(s: String?): PhoneProfile? = s?.split('\t')?.takeIf { it.size >= 5 }?.let { f ->
            PhoneProfile(f[0], f[1].toLongOrNull() ?: 0, f[2].toDoubleOrNull() ?: 0.0, f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0)
        }
    }
}

/** Accumulates a session's statistics into a [PhoneProfile] (running means weighted by epochs). */
class ProfileLearner(private var p: PhoneProfile, private val minFixSats: Int) {
    private var phaseSum = 0.0; private var fixable = 0L; private var slips = 0L; private var phaseSatEpochs = 0L; private var n = 0L

    /** One epoch: satellites with carrier phase, and how many of them flagged a slip. */
    fun add(phaseSats: Int, slipSats: Int) {
        n++; phaseSum += phaseSats; phaseSatEpochs += phaseSats; slips += slipSats
        if (phaseSats >= minFixSats) fixable++
    }

    /** The profile merged with this session ([epochSec] = epoch interval). */
    fun profile(epochSec: Double = 1.0): PhoneProfile {
        if (n == 0L) return p
        val total = p.epochs + n
        fun mix(old: Double, new: Double) = (old * p.epochs + new * n) / total
        val satMin = phaseSatEpochs * epochSec / 60.0
        val slipRate = if (satMin > 0) slips / satMin else 0.0
        return p.copy(epochs = total, meanPhaseSats = mix(p.meanPhaseSats, phaseSum / n), fixableShare = mix(p.fixableShare, fixable.toDouble() / n),
            slipsPerSatMin = mix(p.slipsPerSatMin, slipRate))
    }
}

/**
 * Engine settings derived from a phone's profile. Phones that rarely have enough carrier phase get no ambiguity
 * fixing at all (honest float / code solutions instead of false fixes); phones whose phase slips often need a fix to
 * hold longer before it is reported. Until a phone is [PhoneProfile.learned], the defaults apply.
 */
object PhoneTuning {
    enum class Verdict { LEARNING, NO_AR, CAUTIOUS, NORMAL }

    fun verdict(p: PhoneProfile?): Verdict = when {
        p == null || !p.learned -> Verdict.LEARNING
        p.fixableShare < 0.2 -> Verdict.NO_AR
        p.slipsPerSatMin > 0.5 || p.fixableShare < 0.6 -> Verdict.CAUTIOUS
        else -> Verdict.NORMAL
    }

    fun tune(base: RtkOptions, p: PhoneProfile?): RtkOptions = when (verdict(p)) {
        Verdict.NO_AR -> base.copy(arMode = 0)
        Verdict.CAUTIOUS -> base.copy(confirmEpochs = maxOf(base.confirmEpochs, 10), arRatio = maxOf(base.arRatio, 4.0))
        else -> base
    }
}
