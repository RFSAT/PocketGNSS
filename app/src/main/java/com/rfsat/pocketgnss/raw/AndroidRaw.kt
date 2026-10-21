package com.rfsat.pocketgnss.raw

import android.location.GnssClock
import android.location.GnssMeasurement
import android.os.Build

/** Android GnssClock / GnssMeasurement to the plain types of [RawConvert] (shared by the raw logger and phone RTK). */
object AndroidRaw {
    fun clock(k: GnssClock) = RawClock(
        timeNanos = k.timeNanos,
        fullBiasNanos = if (k.hasFullBiasNanos()) k.fullBiasNanos else null,
        biasNanos = if (k.hasBiasNanos()) k.biasNanos else 0.0,
        biasUncertaintyNanos = if (k.hasBiasUncertaintyNanos()) k.biasUncertaintyNanos else null,
        driftNanosPerSecond = if (k.hasDriftNanosPerSecond()) k.driftNanosPerSecond else null,
        driftUncertaintyNanosPerSecond = if (k.hasDriftUncertaintyNanosPerSecond()) k.driftUncertaintyNanosPerSecond else null,
        timeUncertaintyNanos = if (k.hasTimeUncertaintyNanos()) k.timeUncertaintyNanos else null,
        leapSecond = if (k.hasLeapSecond()) k.leapSecond else null,
        hardwareClockDiscontinuityCount = k.hardwareClockDiscontinuityCount,
        utcTimeMillis = System.currentTimeMillis(),
        elapsedRealtimeNanos = if (Build.VERSION.SDK_INT >= 29 && k.hasElapsedRealtimeNanos()) k.elapsedRealtimeNanos else null,
    )

    @Suppress("DEPRECATION")
    fun measurement(m: GnssMeasurement) = RawMeasurement(
        constellation = m.constellationType,
        svid = m.svid,
        carrierHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
        codeType = if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) m.codeType else null,
        timeOffsetNanos = m.timeOffsetNanos,
        state = m.state,
        receivedSvTimeNanos = m.receivedSvTimeNanos,
        receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos,
        cn0DbHz = m.cn0DbHz,
        pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
        pseudorangeRateUncertaintyMps = m.pseudorangeRateUncertaintyMetersPerSecond,
        adrState = m.accumulatedDeltaRangeState,
        adrMeters = m.accumulatedDeltaRangeMeters,
        adrUncertaintyMeters = m.accumulatedDeltaRangeUncertaintyMeters,
        multipath = m.multipathIndicator,
        snrDb = if (m.hasSnrInDb()) m.snrInDb else null,
        agcDb = if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null,
        basebandCn0DbHz = if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else null,
        fullIsbNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else null,
        fullIsbUncertaintyNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos else null,
        satelliteIsbNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else null,
        satelliteIsbUncertaintyNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasSatelliteInterSignalBiasUncertaintyNanos()) m.satelliteInterSignalBiasUncertaintyNanos else null,
    )
}
