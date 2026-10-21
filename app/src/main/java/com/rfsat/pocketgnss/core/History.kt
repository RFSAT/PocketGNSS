package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * One computed position with the method that produced it, the accuracy the source reported and, for phone RTK,
 * the engine's internals (for comparing methods and debugging).
 */
data class SolutionRecord(
    val timeMs: Long,
    /** "RTK fixed", "RTK float", "Code DGNSS", "Single (RTKLIB)", "Android location", or the receiver's fix type. */
    val method: String,
    val quality: FixQuality,
    val latitude: Double,
    val longitude: Double,
    /** Ellipsoidal height, m (null when unknown). */
    val height: Double?,
    /** Reported 1-sigma accuracy, horizontal and vertical, m. */
    val accH: Double,
    val accV: Double,
    val accMeasured: Boolean,
    val satellites: Int?,
    val hdop: Double?,
    val ageSec: Double?,
    // Phone RTK engine (null for other sources)
    val ratio: Double? = null,
    val signals: Int? = null,
    val phaseSignals: Int? = null,
    val baseSatellites: Int? = null,
    val ephemerides: Int? = null,
    val baselineM: Double? = null,
    val engineMessage: String? = null,
)

/** Statistics of one method against the reference point. */
data class MethodStats(
    val method: String,
    val count: Int,
    /** Mean reported horizontal accuracy, m. */
    val meanAccH: Double,
    /** Actual scatter: RMS horizontal distance from the reference, m. */
    val rmsDist: Double,
    /** Actual scatter around this method's own mean, m (precision, independent of the reference). */
    val rmsOwn: Double,
    val maxDist: Double,
    /** Share of fixes within their reported 2-sigma (2 x accH) of the reference. */
    val within2Sigma: Double,
)

/**
 * The last positions in memory (for the Position history tool) and, optionally, all of them in a CSV file next to
 * the sessions (Log tab). Offsets are local east / north / up from a reference point: by default the mean of the
 * best method present (RTK fixed, else float, ...), or a point the user sets.
 */
class SolutionHistory(private val root: File, private val capacity: Int = 7200) {
    private val buf = ArrayDeque<SolutionRecord>()
    private var file: BufferedWriter? = null
    var fileName: String? = null; private set
    var fileRows = 0L; private set
    @Volatile var version = 0L; private set
    /** User-set reference (lat, lon, h); null = automatic. */
    var reference: Triple<Double, Double, Double?>? = null

    @Synchronized fun add(r: SolutionRecord) {
        buf.addLast(r)
        while (buf.size > capacity) buf.removeFirst()
        file?.let { w ->
            try { w.write(csvRow(r, null)); w.write("\n"); fileRows++; if (fileRows % 10 == 0L) w.flush() } catch (_: Exception) {}
        }
        version++
    }

    @Synchronized fun records(): List<SolutionRecord> = buf.toList()

    @Synchronized fun clear() { buf.clear(); version++ }

    val logging: Boolean get() = file != null

    @Synchronized fun startFile(): File {
        stopFile()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root, "history_$stamp").apply { mkdirs() }
        val f = File(dir, "positions.csv")
        file = f.bufferedWriter().also { it.write(HEADER); it.write("\n") }
        fileName = dir.name; fileRows = 0
        version++
        return f
    }

    @Synchronized fun stopFile() {
        try { file?.close() } catch (_: Exception) {}
        file = null
        version++
    }

    /** The reference point: the user's, or the mean of the best method present. */
    fun referencePoint(list: List<SolutionRecord> = records()): Triple<Double, Double, Double?>? {
        reference?.let { return it }
        if (list.isEmpty()) return null
        val best = list.maxOf { it.quality.rank }
        val sel = list.filter { it.quality.rank == best }
        val hs = sel.mapNotNull { it.height }
        return Triple(sel.map { it.latitude }.average(), sel.map { it.longitude }.average(), if (hs.isEmpty()) null else hs.average())
    }

    /** CSV of the records in memory, offsets against the current reference. */
    fun csv(): String {
        val list = records()
        val ref = referencePoint(list)
        return buildString {
            append(HEADER).append('\n')
            for (r in list) append(csvRow(r, ref)).append('\n')
        }
    }

    companion object {
        const val HEADER = "utc_time,method,gga_quality,latitude,longitude,height_ell_m,acc_h_m,acc_v_m,acc_from_gst,satellites,hdop," +
            "diff_age_s,east_m,north_m,up_m,ar_ratio,signals,phase_signals,base_satellites,ephemerides,baseline_m,engine_message"

        private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** Local east / north / up of a record from a reference (m); flat-earth, fine within kilometres. */
        fun enu(r: SolutionRecord, ref: Triple<Double, Double, Double?>): DoubleArray {
            val n = Math.toRadians(r.latitude - ref.first) * Geo.A
            val e = Math.toRadians(r.longitude - ref.second) * Geo.A * cos(Math.toRadians(ref.first))
            val rh = ref.third
            val u = if (r.height != null && rh != null) r.height - rh else Double.NaN
            return doubleArrayOf(e, n, u)
        }

        fun csvRow(r: SolutionRecord, ref: Triple<Double, Double, Double?>?): String {
            val o = ref?.let { enu(r, it) }
            fun f(v: Double?, d: Int) = v?.takeIf { !it.isNaN() }?.let { "%.${d}f".format(Locale.US, it) } ?: ""
            return listOf(synchronized(iso) { iso.format(Date(r.timeMs)) }, r.method, r.quality.code.toString(), "%.9f".format(Locale.US, r.latitude),
                "%.9f".format(Locale.US, r.longitude), f(r.height, 3), f(r.accH, 3), f(r.accV, 3), if (r.accMeasured) "1" else "0",
                r.satellites?.toString() ?: "", f(r.hdop, 2), f(r.ageSec, 1), f(o?.get(0), 3), f(o?.get(1), 3), f(o?.get(2), 3),
                f(r.ratio, 1), r.signals?.toString() ?: "", r.phaseSignals?.toString() ?: "", r.baseSatellites?.toString() ?: "",
                r.ephemerides?.toString() ?: "", f(r.baselineM, 1), (r.engineMessage ?: "").replace(',', ';').replace('\n', ' ').take(120),
            ).joinToString(",")
        }

        /** Per-method statistics against [ref], best method first. */
        fun stats(list: List<SolutionRecord>, ref: Triple<Double, Double, Double?>): List<MethodStats> =
            list.groupBy { it.method }.map { (m, rs) ->
                val d = rs.map { r -> enu(r, ref).let { sqrt(it[0] * it[0] + it[1] * it[1]) } }
                val own = Triple(rs.map { it.latitude }.average(), rs.map { it.longitude }.average(), null as Double?)
                val dOwn = rs.map { r -> enu(r, own).let { it[0] * it[0] + it[1] * it[1] } }
                MethodStats(m, rs.size, rs.map { it.accH }.average(), sqrt(d.map { it * it }.average()), sqrt(dOwn.average()), d.max(),
                    rs.indices.count { d[it] <= 2 * rs[it].accH }.toDouble() / rs.size)
            }.sortedByDescending { s -> list.first { it.method == s.method }.quality.rank }
    }
}
