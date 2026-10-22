package com.rfsat.pocketgnss.raw

import java.io.BufferedReader

/**
 * Reads a GnssLogger text file ("Raw," and "Fix," lines), as PocketGNSS writes it (gnss_log.txt) and as Google's
 * GnssLogger app does. Columns are found by name from the "# Raw,..." header line, so files of other GnssLogger
 * versions read too; without a header the PocketGNSS column order ([GnssLog.RAW_FIELDS]) is assumed. Measurements are
 * grouped into epochs by TimeNanos and converted with [RawConvert], as the live engine does.
 */
class GnssLogReader(private val r: BufferedReader) {
    /** The phone's own position at one moment (a "Fix," line). */
    data class Fix(val timeMs: Long, val latitude: Double, val longitude: Double, val altitude: Double?, val accuracy: Double?, val provider: String)

    private var rawCols: Map<String, Int> = GnssLog.RAW_FIELDS.split(',').withIndex().associate { it.value to it.index }
    private var fixCols: Map<String, Int> = GnssLog.FIX_FIELDS.split(',').withIndex().associate { it.value to it.index }
    private var pending: Pair<RawClock, RawMeasurement>? = null
    private var lastDisc: Int? = null
    /** The phone's own fixes read so far. */
    val fixes = ArrayList<Fix>()

    private fun col(f: List<String>, name: String): String? = rawCols[name]?.let { f.getOrNull(it) }?.takeIf { it.isNotEmpty() }
    private fun d(f: List<String>, name: String) = col(f, name)?.toDoubleOrNull()
    private fun l(f: List<String>, name: String) = col(f, name)?.toDoubleOrNull()?.toLong()

    private fun parseRaw(f: List<String>): Pair<RawClock, RawMeasurement>? {
        val timeNanos = l(f, "TimeNanos") ?: return null
        val clock = RawClock(timeNanos, l(f, "FullBiasNanos"), d(f, "BiasNanos") ?: 0.0, d(f, "BiasUncertaintyNanos"), d(f, "DriftNanosPerSecond"),
            d(f, "DriftUncertaintyNanosPerSecond"), d(f, "TimeUncertaintyNanos"), col(f, "LeapSecond")?.toIntOrNull(),
            col(f, "HardwareClockDiscontinuityCount")?.toIntOrNull() ?: 0, l(f, "utcTimeMillis") ?: 0, l(f, "ChipsetElapsedRealtimeNanos"))
        val m = RawMeasurement(
            constellation = col(f, "ConstellationType")?.toIntOrNull() ?: return null, svid = col(f, "Svid")?.toIntOrNull() ?: return null,
            carrierHz = d(f, "CarrierFrequencyHz"), codeType = col(f, "CodeType"), timeOffsetNanos = d(f, "TimeOffsetNanos") ?: 0.0,
            state = col(f, "State")?.toIntOrNull() ?: 0, receivedSvTimeNanos = l(f, "ReceivedSvTimeNanos") ?: return null,
            receivedSvTimeUncertaintyNanos = l(f, "ReceivedSvTimeUncertaintyNanos") ?: Long.MAX_VALUE, cn0DbHz = d(f, "Cn0DbHz") ?: 0.0,
            pseudorangeRateMps = d(f, "PseudorangeRateMetersPerSecond") ?: 0.0,
            pseudorangeRateUncertaintyMps = d(f, "PseudorangeRateUncertaintyMetersPerSecond") ?: 0.0,
            adrState = col(f, "AccumulatedDeltaRangeState")?.toIntOrNull() ?: 0, adrMeters = d(f, "AccumulatedDeltaRangeMeters") ?: 0.0,
            adrUncertaintyMeters = d(f, "AccumulatedDeltaRangeUncertaintyMeters") ?: 0.0, multipath = col(f, "MultipathIndicator")?.toIntOrNull() ?: 0,
            snrDb = d(f, "SnrInDb"), agcDb = d(f, "AgcDb"), basebandCn0DbHz = d(f, "BasebandCn0DbHz"), fullIsbNanos = d(f, "FullInterSignalBiasNanos"),
            fullIsbUncertaintyNanos = d(f, "FullInterSignalBiasUncertaintyNanos"), satelliteIsbNanos = d(f, "SatelliteInterSignalBiasNanos"),
            satelliteIsbUncertaintyNanos = d(f, "SatelliteInterSignalBiasUncertaintyNanos"))
        return clock to m
    }

    private fun parseFix(f: List<String>) {
        fun c(n: String) = fixCols[n]?.let { f.getOrNull(it) }?.takeIf { it.isNotEmpty() }
        val la = c("LatitudeDegrees")?.toDoubleOrNull() ?: return
        val lo = c("LongitudeDegrees")?.toDoubleOrNull() ?: return
        fixes.add(Fix(c("UnixTimeMillis")?.toLongOrNull() ?: 0, la, lo, c("AltitudeMeters")?.toDoubleOrNull(), c("AccuracyMeters")?.toDoubleOrNull(),
            c("Provider") ?: ""))
    }

    /** The next epoch's clock and measurements, or null at the end of the file. */
    fun nextRaw(): Pair<RawClock, List<RawMeasurement>>? {
        var clock: RawClock? = pending?.first
        val ms = ArrayList<RawMeasurement>()
        pending?.let { ms.add(it.second) }; pending = null
        while (true) {
            val line = r.readLine() ?: return clock?.let { it to ms }
            if (line.startsWith("# Raw,")) { rawCols = line.removePrefix("# ").split(',').withIndex().associate { it.value.trim() to it.index }; continue }
            if (line.startsWith("# Fix,")) { fixCols = line.removePrefix("# ").split(',').withIndex().associate { it.value.trim() to it.index }; continue }
            if (line.startsWith("Fix,")) { parseFix(line.split(',')); continue }
            if (!line.startsWith("Raw,")) continue
            val p = parseRaw(line.split(',')) ?: continue
            if (clock == null) { clock = p.first; ms.add(p.second); continue }
            if (p.first.timeNanos != clock.timeNanos) { pending = p; return clock to ms }
            ms.add(p.second)
        }
    }

    /** The next epoch converted for RTKLIB (epochs without a usable clock are skipped), or null at the end. */
    fun next(): RawEpoch? {
        while (true) {
            val (c, ms) = nextRaw() ?: return null
            val e = RawConvert.epoch(c, ms, lastDisc)
            lastDisc = c.hardwareClockDiscontinuityCount
            if (e != null && e.obs.isNotEmpty()) return e
        }
    }

    fun epochs(): Sequence<RawEpoch> = generateSequence { next() }
    fun close() = r.close()
}
