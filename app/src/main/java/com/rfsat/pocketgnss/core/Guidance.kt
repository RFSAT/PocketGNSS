package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A straight A-B guidance line with the implement's working [width] (m): the parallel passes are [width] apart.
 * [shift] (m, + = to the right looking from A to B) moves the whole pattern, e.g. to correct for drift or a
 * different implement. Work is in the local east / north plane at A (straight to well under a centimetre across a
 * field of a few kilometres).
 */
data class AbLine(
    val name: String,
    val aLat: Double, val aLon: Double,
    val bLat: Double, val bLon: Double,
    val width: Double,
    val shift: Double = 0.0,
    val timeMs: Long = 0,
) {
    val key: String get() = "$name@$timeMs"
    private val ab = Geo.enu(aLat, aLon, 0.0, bLat, bLon, 0.0)
    val length: Double get() = hypot(ab[0], ab[1])
    /** Unit vector A -> B (east, north). */
    val ue: Double get() = ab[0] / length
    val un: Double get() = ab[1] / length
    /** Bearing A -> B, degrees from north. */
    val bearing: Double get() = (Math.toDegrees(kotlin.math.atan2(ue, un)) + 360) % 360

    companion object {
        /** A line through A with a bearing (degrees), B placed 100 m away. */
        fun fromBearing(name: String, aLat: Double, aLon: Double, bearingDeg: Double, width: Double, timeMs: Long = 0): AbLine {
            val r = Math.toRadians(bearingDeg)
            val (bLat, bLon) = Geo.fromEnu(aLat, aLon, 100 * sin(r), 100 * cos(r))
            return AbLine(name, aLat, aLon, bLat, bLon, width, 0.0, timeMs)
        }
    }
}

/**
 * Where the vehicle is relative to the pattern: [pass] is the number of the nearest line (0 = the A-B line, + to the
 * right looking from A to B), [offset] the distance from it (+ = right of it, looking from A to B), [along] the
 * distance from A along the line. [steer] is the same offset as the driver sees it: + = the vehicle is to the right
 * of the line, so steer left. [headingError] (degrees, + = heading right of the line) needs a course over ground.
 */
data class GuidanceSolution(val pass: Int, val offset: Double, val along: Double, val steer: Double, val headingError: Double?, val forward: Boolean)

object Guidance {
    fun solve(line: AbLine, lat: Double, lon: Double, courseDeg: Double?, lastForward: Boolean = true): GuidanceSolution {
        val p = Geo.enu(line.aLat, line.aLon, 0.0, lat, lon, 0.0)
        val along = p[0] * line.ue + p[1] * line.un
        val cross = p[0] * line.un - p[1] * line.ue - line.shift  // right normal = (un, -ue)
        val w = line.width.coerceAtLeast(0.01)
        val k = (cross / w).roundToInt()
        val d = cross - k * w
        val forward = courseDeg?.let { abs(angle(it - line.bearing)) <= 90 } ?: lastForward
        val he = courseDeg?.let { angle(it - if (forward) line.bearing else line.bearing + 180) }
        return GuidanceSolution(k, d, along, if (forward) d else -d, he, forward)
    }

    /** Difference of angles in (-180, 180]. */
    fun angle(a: Double): Double { var x = a % 360; if (x > 180) x -= 360; if (x <= -180) x += 360; return x }

    /**
     * Lines to draw around a position: the nearest pass first, then [neighbours] on each side, each [halfLength] m
     * either side of the position along the line. Points as (latitude, longitude).
     */
    fun mapLines(line: AbLine, s: GuidanceSolution, neighbours: Int = 2, halfLength: Double = 300.0): List<List<Pair<Double, Double>>> {
        val order = listOf(0) + (1..neighbours).flatMap { listOf(it, -it) }
        return order.map { dk ->
            val c = (s.pass + dk) * line.width + line.shift
            listOf(s.along - halfLength, s.along + halfLength).map { t ->
                val e = t * line.ue + c * line.un; val n = t * line.un - c * line.ue
                Geo.fromEnu(line.aLat, line.aLon, e, n)
            }
        }
    }
}

data class GuidanceState(
    val line: AbLine? = null,
    val solution: GuidanceSolution? = null,
    /** Distance driven within half a width of a line, times the width: area worked, overlaps counted (m²). */
    val worked: Double = 0.0,
    val offLine: Boolean = false,
)

/** The active A-B line, updated with every fix (Tools > A-B guidance), and the saved lines. */
class GuidanceSession(private val file: File) {
    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<AbLine>> = _lines.asStateFlow()
    private var lastLat = Double.NaN; private var lastLon = 0.0
    private var forward = true
    /** Off-line warning distance (m); 0 = off. */
    @Volatile var warnAt = 0.0

    @Synchronized fun activate(l: AbLine?) {
        _state.value = GuidanceState(l); lastLat = Double.NaN
    }

    @Synchronized fun setShift(shift: Double) {
        val st = _state.value
        val l = st.line ?: return
        val n = l.copy(shift = shift)
        _state.value = st.copy(line = n)
        if (_lines.value.any { it.key == l.key }) save(_lines.value.map { if (it.key == l.key) n else it })
    }

    @Synchronized fun resetWorked() { _state.value = _state.value.copy(worked = 0.0) }

    @Synchronized fun onFix(lat: Double, lon: Double, courseDeg: Double?) {
        val st = _state.value
        val l = st.line ?: return
        val s = Guidance.solve(l, lat, lon, courseDeg, forward)
        forward = s.forward
        var worked = st.worked
        if (!lastLat.isNaN() && abs(s.offset) <= l.width / 2) {
            val d = Geo.enu(lastLat, lastLon, 0.0, lat, lon, 0.0)
            val step = hypot(d[0], d[1])
            if (step < 50) worked += step * l.width
        }
        lastLat = lat; lastLon = lon
        _state.value = st.copy(solution = s, worked = worked, offLine = warnAt > 0 && abs(s.offset) > warnAt)
    }

    fun add(l: AbLine) = save(_lines.value + l)
    fun remove(l: AbLine) = save(_lines.value.filterNot { it.key == l.key })

    @Synchronized private fun save(list: List<AbLine>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("name;a_lat;a_lon;b_lat;b_lon;width_m;shift_m;time_ms\n")
            for (l in list) w.write(listOf(l.name.replace(';', ','), "%.10f".format(Locale.US, l.aLat), "%.10f".format(Locale.US, l.aLon),
                "%.10f".format(Locale.US, l.bLat), "%.10f".format(Locale.US, l.bLon), "%.3f".format(Locale.US, l.width),
                "%.3f".format(Locale.US, l.shift), l.timeMs).joinToString(";") + "\n")
        }
        _lines.value = list
    }

    private fun load(): List<AbLine> {
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { r ->
            val f = r.split(';')
            if (f.size < 8) null else try {
                AbLine(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toLong())
            } catch (_: NumberFormatException) { null }
        }
    }
}
