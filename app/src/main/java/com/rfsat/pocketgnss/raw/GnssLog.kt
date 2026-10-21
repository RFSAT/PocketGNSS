package com.rfsat.pocketgnss.raw

import java.util.Locale

/**
 * Raw measurements in the text format of Google's GnssLogger app ("Raw," lines), so that a session can be opened by
 * Google's GNSS Measurement Tools (MATLAB: ReadGnssLogger / ProcessGnssMeasScript), by gnss_lib_py and by the common
 * GnssLogger-to-RINEX converters, alongside the RINEX file that PocketGNSS writes itself.
 */
object GnssLog {
    const val RAW_FIELDS = "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos," +
        "DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State," +
        "ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
        "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters," +
        "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty," +
        "MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos," +
        "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos," +
        "CodeType,ChipsetElapsedRealtimeNanos"
    const val FIX_FIELDS = "Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees," +
        "UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters"

    fun header(device: String, android: String, app: String): String = buildString {
        append("#\n# Header Description:\n#\n")
        append("# Version: $app Platform: $android Manufacturer/Model: $device\n#\n")
        append("# ").append(RAW_FIELDS).append('\n')
        append("#\n# ").append(FIX_FIELDS).append("\n#\n")
    }

    private fun d(v: Double?) = v?.let { if (it == it.toLong().toDouble() && kotlin.math.abs(it) < 1e15) it.toLong().toString() else "%s".format(Locale.US, it) } ?: ""

    fun raw(c: RawClock, m: RawMeasurement): String = listOf(
        "Raw", c.utcTimeMillis.toString(), c.timeNanos.toString(), c.leapSecond?.toString() ?: "", d(c.timeUncertaintyNanos),
        c.fullBiasNanos?.toString() ?: "", d(c.biasNanos), d(c.biasUncertaintyNanos), d(c.driftNanosPerSecond),
        d(c.driftUncertaintyNanosPerSecond), c.hardwareClockDiscontinuityCount.toString(), m.svid.toString(), d(m.timeOffsetNanos),
        m.state.toString(), m.receivedSvTimeNanos.toString(), m.receivedSvTimeUncertaintyNanos.toString(), d(m.cn0DbHz),
        d(m.pseudorangeRateMps), d(m.pseudorangeRateUncertaintyMps), m.adrState.toString(), d(m.adrMeters), d(m.adrUncertaintyMeters),
        d(m.carrierHz), "", "", "", m.multipath.toString(), d(m.snrDb), m.constellation.toString(), d(m.agcDb), d(m.basebandCn0DbHz),
        d(m.fullIsbNanos), d(m.fullIsbUncertaintyNanos), d(m.satelliteIsbNanos), d(m.satelliteIsbUncertaintyNanos),
        m.codeType ?: "", c.elapsedRealtimeNanos?.toString() ?: "",
    ).joinToString(",")

    fun fix(provider: String, lat: Double, lon: Double, alt: Double?, speed: Float?, acc: Float?, bearing: Float?, timeMs: Long,
            elapsedNanos: Long?, vAcc: Float?): String = listOf(
        "Fix", provider, "%.9f".format(Locale.US, lat), "%.9f".format(Locale.US, lon), alt?.let { "%.3f".format(Locale.US, it) } ?: "",
        speed?.toString() ?: "", acc?.toString() ?: "", bearing?.toString() ?: "", timeMs.toString(), "", "", elapsedNanos?.toString() ?: "",
        vAcc?.toString() ?: "",
    ).joinToString(",")
}
