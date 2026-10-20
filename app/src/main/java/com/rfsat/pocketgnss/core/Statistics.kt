package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.Geo
import kotlin.math.sqrt

/**
 * Static accuracy statistics as used in the AgriBIT validation (D3.5 GNSS-test-03/04/05): mean position,
 * east / north / up scatter, CEP50 / CEP95, 2DRMS and, when a reference coordinate is known (e.g. from the
 * EOS Arrow Gold), the bias and RMS error against it.
 */
data class PositionStats(
    val count: Int,
    val meanLatitude: Double,
    val meanLongitude: Double,
    val meanHeight: Double,
    val sdEast: Double,
    val sdNorth: Double,
    val sdUp: Double,
    /** Horizontal radius containing 50 % / 95 % of the points, around the mean (or the reference). */
    val cep50: Double,
    val cep95: Double,
    val maxHorizontal: Double,
    val drms2: Double,
    /** Against the reference: horizontal and vertical offset of the mean, and RMS errors. Null without reference. */
    val biasHorizontal: Double?,
    val biasUp: Double?,
    val rmsHorizontal: Double?,
    val rmsUp: Double?,
    /** East / north / up of each sample relative to the centre used (mean or reference), for the scatter plot. */
    val enu: List<DoubleArray>,
)

data class Reference(val latitude: Double, val longitude: Double, val height: Double)

object Statistics {

    /** [samples] are (latitude, longitude, ellipsoid height). Returns null for fewer than 2 samples. */
    fun compute(samples: List<DoubleArray>, reference: Reference? = null, keepPoints: Int = 2000): PositionStats? {
        if (samples.size < 2) return null
        val mLat = samples.sumOf { it[0] } / samples.size
        val mLon = samples.sumOf { it[1] } / samples.size
        val mH = samples.sumOf { it[2] } / samples.size
        val aroundMean = samples.map { Geo.enu(mLat, mLon, mH, it[0], it[1], it[2]) }
        fun sd(i: Int) = sqrt(aroundMean.sumOf { it[i] * it[i] } / (samples.size - 1))
        val sdE = sd(0); val sdN = sd(1); val sdU = sd(2)

        val centred = if (reference != null) samples.map { Geo.enu(reference.latitude, reference.longitude, reference.height, it[0], it[1], it[2]) } else aroundMean
        val radii = centred.map { sqrt(it[0] * it[0] + it[1] * it[1]) }.sorted()
        var bH: Double? = null; var bU: Double? = null; var rH: Double? = null; var rU: Double? = null
        if (reference != null) {
            val m = Geo.enu(reference.latitude, reference.longitude, reference.height, mLat, mLon, mH)
            bH = sqrt(m[0] * m[0] + m[1] * m[1]); bU = m[2]
            rH = sqrt(centred.sumOf { it[0] * it[0] + it[1] * it[1] } / centred.size)
            rU = sqrt(centred.sumOf { it[2] * it[2] } / centred.size)
        }
        val step = maxOf(1, centred.size / keepPoints)
        return PositionStats(samples.size, mLat, mLon, mH, sdE, sdN, sdU, percentile(radii, 0.50), percentile(radii, 0.95),
            radii.last(), 2 * sqrt(sdE * sdE + sdN * sdN), bH, bU, rH, rU, centred.filterIndexed { i, _ -> i % step == 0 })
    }

    /** Linear-interpolated percentile of sorted values. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val x = p * (sorted.size - 1)
        val lo = x.toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (x - lo)
    }
}

/** Time to first fix, measured as in D3.5 GNSS-test-07: from a start (reset or power-up) to each fix level. */
data class TtffRun(
    val label: String,
    val startMs: Long,
    val firstFixMs: Long? = null,
    val firstDgnssMs: Long? = null,
    val firstFloatMs: Long? = null,
    val firstFixedMs: Long? = null,
) {
    fun seconds(t: Long?) = t?.let { (it - startMs) / 1000.0 }
    val done: Boolean get() = firstFixedMs != null

    fun update(quality: com.rfsat.pocketgnss.protocol.FixQuality, now: Long): TtffRun {
        val r = quality.rank
        return copy(
            firstFixMs = firstFixMs ?: now.takeIf { r >= 2 },
            firstDgnssMs = firstDgnssMs ?: now.takeIf { r >= 3 },
            firstFloatMs = firstFloatMs ?: now.takeIf { r >= 4 },
            firstFixedMs = firstFixedMs ?: now.takeIf { r >= 5 },
        )
    }
}

/** Message rate per sentence type, over a sliding window (D3.5 GNSS-test-08: up to 20 Hz). */
class RateMeter(private val windowMs: Long = 5000) {
    private val times = HashMap<String, ArrayDeque<Long>>()
    private val counts = HashMap<String, Long>()

    @Synchronized fun mark(type: String, now: Long) {
        val q = times.getOrPut(type) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        counts[type] = (counts[type] ?: 0) + 1
    }

    data class Row(val type: String, val hz: Double, val total: Long, val maxGapMs: Long)

    @Synchronized fun snapshot(now: Long): List<Row> = times.map { (t, q) ->
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        val hz = if (q.size >= 2) (q.size - 1) * 1000.0 / (q.last() - q.first()).coerceAtLeast(1) else 0.0
        val gap = q.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        Row(t, hz, counts[t] ?: 0, gap)
    }.sortedBy { it.type }

    @Synchronized fun clear() { times.clear(); counts.clear() }
}
