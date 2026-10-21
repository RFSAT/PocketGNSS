package com.rfsat.pocketgnss.raw

import kotlin.math.abs
import kotlin.math.roundToLong

/** The receiver clock of one measurement epoch, as android.location.GnssClock reports it. */
data class RawClock(
    val timeNanos: Long,
    /** Null when the phone does not report it (then the epoch cannot be converted). */
    val fullBiasNanos: Long?,
    val biasNanos: Double = 0.0,
    val biasUncertaintyNanos: Double? = null,
    val driftNanosPerSecond: Double? = null,
    val driftUncertaintyNanosPerSecond: Double? = null,
    val timeUncertaintyNanos: Double? = null,
    val leapSecond: Int? = null,
    val hardwareClockDiscontinuityCount: Int = 0,
    /** Wall-clock time of the callback, for the GnssLogger file only. */
    val utcTimeMillis: Long = 0,
    val elapsedRealtimeNanos: Long? = null,
)

/** One signal of one satellite, as android.location.GnssMeasurement reports it (constellation codes of GnssStatus). */
data class RawMeasurement(
    val constellation: Int,
    val svid: Int,
    val carrierHz: Double?,
    /** RINEX attribute letter from getCodeType() (Android 10+): "C", "Q", "I", "X", "P"... null when unknown. */
    val codeType: String?,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double,
    val adrState: Int,
    val adrMeters: Double,
    val adrUncertaintyMeters: Double,
    val multipath: Int = 0,
    val snrDb: Double? = null,
    val agcDb: Double? = null,
    val basebandCn0DbHz: Double? = null,
    val fullIsbNanos: Double? = null,
    val fullIsbUncertaintyNanos: Double? = null,
    val satelliteIsbNanos: Double? = null,
    val satelliteIsbUncertaintyNanos: Double? = null,
)

/** One RINEX observation set for one signal: code (C, m), phase (L, cycles), Doppler (D, Hz) and C/N0 (S, dB-Hz). */
data class SignalObs(
    /** RINEX system letter: G R E C J I S. */
    val system: Char,
    val prn: Int,
    /** RINEX band + attribute, e.g. "1C", "5Q", "2I". */
    val code: String,
    val pseudorange: Double?,
    val phase: Double?,
    val doppler: Double?,
    val cn0: Double,
    /** RINEX loss-of-lock indicator: bit 0 = cycle slip / reset, bit 1 = half-cycle ambiguity. */
    val lli: Int,
    val band: Band,
    /** GLONASS frequency channel (-7..6), else null. */
    val gloChannel: Int? = null,
) {
    val sat: String get() = system + "%02d".format(prn)
}

/** One converted epoch in GPS time. */
data class RawEpoch(val gpsWeek: Int, val towSeconds: Double, val obs: List<SignalObs>, val clockReset: Boolean) {
    /** GPS time in nanoseconds since 6 Jan 1980. */
    val gpsNanos: Long get() = gpsWeek * RawConvert.NS_WEEK + (towSeconds * 1e9).roundToLong()
}

/** Frequency bands that phones report. */
enum class Band(val label: String) { L1("L1/E1/B1C"), L2("L2"), L5("L5/E5a/B2a"), B1I("B1I"), E5B("E5b/B2I"), G1("G1"), G2("G2"), OTHER("other") }

/**
 * Converts Android raw GNSS measurements (GnssClock + GnssMeasurement) into RINEX-style code, phase, Doppler and C/N0,
 * following the method of Google's GNSS Measurement Tools / the GSA white paper "Using GNSS Raw Measurements on
 * Android devices". Pure Kotlin so that the RINEX writer, the capability test and the phone RTK engine (stage B)
 * share it, and so that it is unit-tested.
 */
object RawConvert {
    const val C = 299_792_458.0
    const val NS_WEEK = 604_800_000_000_000L
    const val NS_DAY = 86_400_000_000_000L
    private const val NS_100MS = 100_000_000L

    // GnssStatus constellation types.
    const val GPS = 1; const val SBAS = 2; const val GLONASS = 3; const val QZSS = 4; const val BEIDOU = 5; const val GALILEO = 6; const val IRNSS = 7

    // GnssMeasurement state bits.
    const val STATE_CODE_LOCK = 1; const val STATE_TOW_DECODED = 8; const val STATE_GLO_TOD_DECODED = 128
    const val STATE_GAL_E1C_2ND_CODE_LOCK = 2048; const val STATE_TOW_KNOWN = 16384; const val STATE_GLO_TOD_KNOWN = 32768

    // Accumulated delta range state bits.
    const val ADR_VALID = 1; const val ADR_RESET = 2; const val ADR_CYCLE_SLIP = 4; const val ADR_HALF_RESOLVED = 8; const val ADR_HALF_REPORTED = 16

    /**
     * Uncertainty limit for a usable pseudorange: 50 ns = 15 m. Well-tracked signals report a few to 20 ns; above
     * that the code is often off by tens to hundreds of metres, and RTKLIB, which weights by elevation and signal
     * strength only, would take it for a metre-level measurement and jump (up to 1.12.0 the limit was 500 ns = 150 m).
     */
    const val MAX_TIME_UNCERTAINTY_NS = 50L

    private const val DEFAULT_LEAP = 18
    private const val BDS_GPS_OFFSET_NS = 14_000_000_000L

    fun systemLetter(constellation: Int): Char? = when (constellation) {
        GPS -> 'G'; GLONASS -> 'R'; GALILEO -> 'E'; BEIDOU -> 'C'; QZSS -> 'J'; IRNSS -> 'I'; SBAS -> 'S'; else -> null
    }

    /** RINEX satellite number, null when the phone's svid cannot be expressed (e.g. GLONASS with unknown slot). */
    fun prn(constellation: Int, svid: Int): Int? = when (constellation) {
        GPS -> svid.takeIf { it in 1..32 }
        GLONASS -> svid.takeIf { it in 1..27 }
        GALILEO -> svid.takeIf { it in 1..36 }
        BEIDOU -> svid.takeIf { it in 1..63 }
        QZSS -> (svid - 192).takeIf { it in 1..10 }
        IRNSS -> svid.takeIf { it in 1..14 }
        SBAS -> (svid - 100).takeIf { it in 20..58 }
        else -> null
    }

    private fun near(f: Double, target: Double) = abs(f - target) < 1.0e6

    /** The band of a signal from its carrier frequency; L1 when the phone does not report frequencies (older phones). */
    fun band(constellation: Int, carrierHz: Double?): Band {
        val f = carrierHz ?: return if (constellation == GLONASS) Band.G1 else if (constellation == BEIDOU) Band.B1I else Band.L1
        return when {
            constellation == GLONASS && f in 1.597e9..1.607e9 -> Band.G1
            constellation == GLONASS && f in 1.241e9..1.250e9 -> Band.G2
            near(f, 1575.42e6) -> Band.L1
            near(f, 1176.45e6) -> Band.L5
            near(f, 1227.60e6) -> Band.L2
            near(f, 1561.098e6) -> Band.B1I
            near(f, 1207.14e6) -> Band.E5B
            else -> Band.OTHER
        }
    }

    /** RINEX 3 band digit. */
    fun rinexBandDigit(constellation: Int, band: Band): Char? = when (band) {
        Band.L1, Band.G1 -> '1'
        Band.L2, Band.G2 -> '2'
        Band.L5 -> '5'
        Band.B1I -> '2' // BeiDou B1I is band 2 in RINEX 3
        Band.E5B -> '7'
        Band.OTHER -> null
    }.takeIf { !(constellation == IRNSS && band != Band.L5) }

    /** RINEX attribute when the phone does not say (typical phone signals). */
    fun defaultAttribute(constellation: Int, band: Band): Char = when (constellation) {
        GPS, QZSS -> when (band) { Band.L5 -> 'Q'; Band.L2 -> 'L'; else -> 'C' }
        GALILEO -> when (band) { Band.L5, Band.E5B -> 'Q'; else -> 'C' }
        BEIDOU -> when (band) { Band.B1I -> 'I'; Band.E5B -> 'I'; else -> 'P' }
        IRNSS -> 'A'
        else -> 'C'
    }

    fun rinexCode(m: RawMeasurement): String? {
        val b = band(m.constellation, m.carrierHz)
        val d = rinexBandDigit(m.constellation, b) ?: return null
        val a = m.codeType?.trim()?.takeIf { it.length == 1 && it[0].isLetter() && it != "N" }?.get(0)?.uppercaseChar()
            ?: defaultAttribute(m.constellation, b)
        return "$d$a"
    }

    /** Carrier frequency, with the nominal value when the phone does not report it. */
    fun frequency(m: RawMeasurement): Double = m.carrierHz ?: when (m.constellation) {
        GLONASS -> 1602.0e6
        BEIDOU -> 1561.098e6
        else -> 1575.42e6
    }

    /** GLONASS FDMA channel number from the carrier frequency (G1: 1602 MHz + k·562.5 kHz). */
    fun gloChannel(carrierHz: Double?): Int? {
        val f = carrierHz ?: return null
        val k = when {
            f in 1.597e9..1.607e9 -> (f - 1602.0e6) / 562.5e3
            f in 1.241e9..1.250e9 -> (f - 1246.0e6) / 437.5e3
            else -> return null
        }
        return Math.round(k).toInt().takeIf { it in -7..6 }
    }

    /**
     * Receiver time of the epoch in GPS nanoseconds since 6 Jan 1980, or null without the full bias. Integer part
     * only: absolute nanoseconds (~1.5e18) do not fit a Double exactly (256 ns steps = 77 m of range), so the
     * sub-nanosecond terms (BiasNanos, TimeOffsetNanos) are always added after the modulo, as Doubles.
     */
    fun epochGpsNanosLong(c: RawClock): Long? = c.fullBiasNanos?.let { c.timeNanos - it }

    fun epochGpsNanos(c: RawClock): Double? = epochGpsNanosLong(c)?.let { it - c.biasNanos }

    /**
     * Pseudorange in metres, or null when the code is not yet unambiguous (time of week / time of day not decoded),
     * too uncertain, or out of the plausible range.
     */
    fun pseudorange(c: RawClock, m: RawMeasurement): Double? {
        val tLong = epochGpsNanosLong(c) ?: return null
        if (m.state and STATE_CODE_LOCK == 0) return null
        if (m.receivedSvTimeUncertaintyNanos > MAX_TIME_UNCERTAINTY_NS) return null
        val frac = m.timeOffsetNanos - c.biasNanos
        val towOk = m.state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) != 0
        val (shift, period) = when (m.constellation) {
            GPS, QZSS, IRNSS -> if (towOk) 0L to NS_WEEK else return null
            GALILEO -> when {
                towOk -> 0L to NS_WEEK
                m.state and STATE_GAL_E1C_2ND_CODE_LOCK != 0 -> 0L to NS_100MS
                else -> return null
            }
            BEIDOU -> if (towOk) -BDS_GPS_OFFSET_NS to NS_WEEK else return null
            GLONASS -> if (m.state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN) != 0) {
                val leap = (c.leapSecond ?: DEFAULT_LEAP) * 1_000_000_000L
                (3 * 3_600_000_000_000L - leap) to NS_DAY
            } else return null
            else -> return null
        }
        val tRxSys = Math.floorMod(tLong + shift, period)
        var dt = (tRxSys - m.receivedSvTimeNanos).toDouble() + frac
        if (period == NS_100MS) {
            // Only the 100 ms secondary code is known: transit times are 60-90 ms, so take dt in [0, 100 ms).
            dt = ((dt % period) + period) % period
        } else {
            // Roll-over of the week / day between transmission and reception.
            if (dt > period / 2) dt -= period
            if (dt < -period / 2) dt += period
        }
        val pr = dt * 1e-9 * C
        return pr.takeIf { it in 1.0e7..4.5e7 }
    }

    /** Converts one GnssMeasurementsEvent. [previousDiscontinuity] = the clock's discontinuity count at the previous epoch. */
    fun epoch(c: RawClock, ms: List<RawMeasurement>, previousDiscontinuity: Int? = null): RawEpoch? {
        val t = epochGpsNanosLong(c) ?: return null
        val week = Math.floorDiv(t, NS_WEEK).toInt()
        val tow = (Math.floorMod(t, NS_WEEK) - c.biasNanos) / 1e9
        val reset = previousDiscontinuity != null && previousDiscontinuity != c.hardwareClockDiscontinuityCount
        val out = ArrayList<SignalObs>(ms.size)
        for (m in ms) {
            val sys = systemLetter(m.constellation) ?: continue
            val prn = prn(m.constellation, m.svid) ?: continue
            val code = rinexCode(m) ?: continue
            val f = frequency(m)
            val lambda = C / f
            val pr = pseudorange(c, m)
            val adrOk = m.adrState and ADR_VALID != 0 && m.adrState and ADR_RESET == 0 && m.adrMeters != 0.0
            val phase = if (adrOk) m.adrMeters / lambda else null
            var lli = 0
            if (adrOk && (m.adrState and (ADR_CYCLE_SLIP or ADR_RESET) != 0 || reset)) lli = lli or 1
            if (adrOk && m.adrState and ADR_HALF_REPORTED != 0 && m.adrState and ADR_HALF_RESOLVED == 0) lli = lli or 2
            val doppler = if (m.pseudorangeRateUncertaintyMps > 0 || m.pseudorangeRateMps != 0.0) -m.pseudorangeRateMps / lambda else null
            if (pr == null && phase == null) continue
            out.add(SignalObs(sys, prn, code, pr, phase, doppler, m.cn0DbHz, lli, band(m.constellation, m.carrierHz),
                if (m.constellation == GLONASS) gloChannel(m.carrierHz) else null))
        }
        return RawEpoch(week, tow, out.sortedWith(compareBy({ "GRECJIS".indexOf(it.system) }, { it.prn }, { it.code })), reset)
    }
}
