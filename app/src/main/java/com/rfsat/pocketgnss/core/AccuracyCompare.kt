package com.rfsat.pocketgnss.core

import java.util.Locale
import kotlin.math.hypot

/**
 * Side-by-side record of the error figures an LG290P receiver reports, taken automatically at every GGA epoch (1.13.3),
 * so the accuracy shown by PocketGNSS can be checked against the receiver's own display without reading two screens:
 *  - GST: 1-sigma latitude / longitude / height errors; horizontal = hypot(lat, lon);
 *  - $PQTMEPE: the receiver's estimated position error; its 2-D value is the figure on the RTK Everywhere display;
 *  - what PocketGNSS displayed, and which of the two (or its rough HDOP estimate) it came from.
 * Pure Kotlin, unit-tested.
 */
object AccuracyCompare {
    const val HEADER = "time_utc,epoch_ms,gga_utc,quality,fix,satellites,hdop,diff_age_s,diff_station," +
        "gst_age_ms,gst_rms_m,gst_sd_lat_m,gst_sd_lon_m,gst_sd_alt_m,gst_h_m," +
        "epe_age_ms,epe_north_m,epe_east_m,epe_down_m,epe_2d_m,epe_3d_m," +
        "ppp_solution,app_h_m,app_source,gst_minus_epe_m,gst_over_epe"

    /** Where the displayed horizontal accuracy came from. */
    enum class Source { EPE, GST, ESTIMATE }

    /** One epoch. GST and EPE are null when not received in the last [FRESH_MS]. */
    data class Row(
        val timeMs: Long, val fix: GnssFix,
        val gstAgeMs: Long?, val gstRms: Double?, val gstSdLat: Double?, val gstSdLon: Double?, val gstSdAlt: Double?,
        val epeAgeMs: Long?, val epe: com.rfsat.pocketgnss.protocol.QuectelEpe?,
        val pppSolution: Int?, val appH: Double, val source: Source,
    ) {
        val gstH: Double? get() = if (gstSdLat != null && gstSdLon != null) hypot(gstSdLat, gstSdLon) else null
        val epe2d: Double? get() = epe?.horizontal
        val diff: Double? get() { val g = gstH; val e = epe2d; return if (g != null && e != null) g - e else null }
        val ratio: Double? get() { val g = gstH; val e = epe2d; return if (g != null && e != null && e > 0) g / e else null }

        fun csv(): String {
            fun d(v: Double?, p: Int) = v?.let { "%.${p}f".format(Locale.US, it) } ?: ""
            val f = fix
            return listOf(com.rfsat.pocketgnss.data.isoTime(timeMs), timeMs.toString(), f.time?.toString() ?: "", f.quality.code.toString(),
                f.quality.name, f.satellitesUsed?.toString() ?: "", d(f.hdop, 2), d(f.diffAgeSec, 1), f.diffStation ?: "",
                gstAgeMs?.toString() ?: "", d(gstRms, 4), d(gstSdLat, 4), d(gstSdLon, 4), d(gstSdAlt, 4), d(gstH, 4),
                epeAgeMs?.toString() ?: "", d(epe?.north, 4), d(epe?.east, 4), d(epe?.down, 4), d(epe?.horizontal, 4), d(epe?.spherical, 4),
                pppSolution?.toString() ?: "", d(appH, 4), source.name, d(diff, 4), d(ratio, 3)).joinToString(",")
        }
    }

    /** A GST or EPE older than this (at the GGA) is not paired with it. */
    const val FRESH_MS = 1500L

    /** Running comparison over the connection (epochs that had both GST and EPE). */
    data class Summary(val epochs: Int, val paired: Int, val medianDiff: Double?, val meanDiff: Double?, val medianRatio: Double?,
                       val minDiff: Double?, val maxDiff: Double?, val appFromEpe: Int, val appFromGst: Int, val appEstimated: Int,
                       /** Source of the accuracy shown at the last epoch. */
                       val lastSource: Source?)

    class Stats(private val cap: Int = 36_000) {
        private val diffs = ArrayList<Double>()
        private val ratios = ArrayList<Double>()
        private var epochs = 0
        private val bySource = IntArray(Source.entries.size)
        private var last: Source? = null

        @Synchronized fun add(r: Row) {
            epochs++
            bySource[r.source.ordinal]++
            last = r.source
            val d = r.diff ?: return
            if (diffs.size >= cap) { diffs.removeAt(0); ratios.removeAt(0) }
            diffs.add(d); ratios.add(r.ratio ?: Double.NaN)
        }

        @Synchronized fun reset() { diffs.clear(); ratios.clear(); epochs = 0; bySource.fill(0); last = null }

        @Synchronized fun summary(): Summary {
            fun median(v: List<Double>): Double? = v.filter { !it.isNaN() }.sorted().let { s ->
                if (s.isEmpty()) null else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
            return Summary(epochs, diffs.size, median(diffs), diffs.takeIf { it.isNotEmpty() }?.average(), median(ratios),
                diffs.minOrNull(), diffs.maxOrNull(), bySource[Source.EPE.ordinal], bySource[Source.GST.ordinal], bySource[Source.ESTIMATE.ordinal], last)
        }
    }
}
