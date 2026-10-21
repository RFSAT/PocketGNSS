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
 * A guidance pattern with the implement's working [width] (m): the parallel passes are [width] apart. A straight
 * A-B line, or with [curve] (latitude, longitude points) a curve: a recorded path whose passes follow it at whole
 * widths ([closed]: a field boundary, whose passes run inside it as headland passes, the first half a width in).
 * [shift] (m, + = to the right looking along the line from A) moves the whole pattern, e.g. to correct for drift or a
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
    val curve: List<Pair<Double, Double>> = emptyList(),
    val closed: Boolean = false,
) {
    val key: String get() = "$name@$timeMs"
    val isCurve: Boolean get() = curve.size >= 2
    private val ab = Geo.enu(aLat, aLon, 0.0, bLat, bLon, 0.0)
    val length: Double get() = if (isCurve) polyline!!.length else hypot(ab[0], ab[1])
    /** Unit vector A -> B (east, north). */
    val ue: Double get() = ab[0] / hypot(ab[0], ab[1])
    val un: Double get() = ab[1] / hypot(ab[0], ab[1])
    /** Bearing A -> B, degrees from north. */
    val bearing: Double get() = (Math.toDegrees(kotlin.math.atan2(ue, un)) + 360) % 360
    /** The curve in the plane at A (null for a straight line). */
    val polyline: Polyline? by lazy {
        if (!isCurve) null else {
            val e = curve.map { Geo.enu(aLat, aLon, 0.0, it.first, it.second, 0.0) }
            Polyline(DoubleArray(e.size) { e[it][0] }, DoubleArray(e.size) { e[it][1] }, closed)
        }
    }
    /** Offset of pass 0 from the curve: headland passes start half a width inside the boundary (on its left, see [headland]). */
    val baseOffset: Double get() = if (closed) -width / 2 else 0.0

    companion object {
        /** A line through A with a bearing (degrees), B placed 100 m away. */
        fun fromBearing(name: String, aLat: Double, aLon: Double, bearingDeg: Double, width: Double, timeMs: Long = 0): AbLine {
            val r = Math.toRadians(bearingDeg)
            val (bLat, bLon) = Geo.fromEnu(aLat, aLon, 100 * sin(r), 100 * cos(r))
            return AbLine(name, aLat, aLon, bLat, bLon, width, 0.0, timeMs)
        }

        /** A curve pattern through [points] (latitude, longitude), e.g. a recorded first pass along a contour. */
        fun curve(name: String, points: List<Pair<Double, Double>>, width: Double, closed: Boolean = false, timeMs: Long = 0): AbLine {
            require(points.size >= 2)
            val a = points.first(); val b = if (closed) points[points.size / 2] else points.last()
            return AbLine(name, a.first, a.second, b.first, b.second, width, 0.0, timeMs, points, closed)
        }

        /**
         * Headland passes round a field boundary: the ring is taken counter-clockwise, so the field lies on its left;
         * pass 0 runs half a width inside the boundary, pass 1 the next one in (shown as headland passes 1, 2, ...).
         */
        fun headland(name: String, ring: List<Pair<Double, Double>>, width: Double, timeMs: Long = 0): AbLine {
            val a = ring.first()
            val e = ring.map { Geo.enu(a.first, a.second, 0.0, it.first, it.second, 0.0) }
            val ccw = Polyline(DoubleArray(e.size) { e[it][0] }, DoubleArray(e.size) { e[it][1] }, true).area() > 0
            return curve(name, if (ccw) ring else ring.reversed(), width, closed = true, timeMs = timeMs)
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
        val along: Double; val crossRaw: Double; val heading: Double
        val poly = line.polyline
        if (poly != null) {
            val q = poly.project(p[0], p[1])
            along = q.along; crossRaw = q.cross; heading = (Math.toDegrees(q.heading) + 360) % 360
        } else {
            along = p[0] * line.ue + p[1] * line.un
            crossRaw = p[0] * line.un - p[1] * line.ue  // right normal = (un, -ue)
            heading = line.bearing
        }
        val cross = crossRaw - line.shift - line.baseOffset
        val w = line.width.coerceAtLeast(0.01)
        val k = (cross / w).roundToInt()
        val d = cross - k * w
        val forward = courseDeg?.let { abs(angle(it - heading)) <= 90 } ?: lastForward
        val he = courseDeg?.let { angle(it - if (forward) heading else heading + 180) }
        return GuidanceSolution(k, d, along, if (forward) d else -d, he, forward)
    }

    /** Headland pass number (1 = the outermost, along the boundary) of a solution on a headland pattern; 0 = outside the field. */
    fun headlandPass(s: GuidanceSolution): Int = if (s.pass > 0) 0 else 1 - s.pass

    /** Difference of angles in (-180, 180]. */
    fun angle(a: Double): Double { var x = a % 360; if (x > 180) x -= 360; if (x <= -180) x += 360; return x }

    /**
     * Lines to draw around a position: the nearest pass first, then [neighbours] on each side, each [halfLength] m
     * either side of the position along the line (curves: along the curve; headland rings whole). Points as
     * (latitude, longitude).
     */
    fun mapLines(line: AbLine, s: GuidanceSolution, neighbours: Int = 2, halfLength: Double = 300.0): List<List<Pair<Double, Double>>> {
        val order = listOf(0) + (1..neighbours).flatMap { listOf(it, -it) }
        val poly = line.polyline
        return order.map { dk ->
            val c = (s.pass + dk) * line.width + line.shift + line.baseOffset
            if (poly != null) poly.offset(c, s.along - halfLength, s.along + halfLength).map { (e, n) -> Geo.fromEnu(line.aLat, line.aLon, e, n) }
            else listOf(s.along - halfLength, s.along + halfLength).map { t ->
                val e = t * line.ue + c * line.un; val n = t * line.un - c * line.ue
                Geo.fromEnu(line.aLat, line.aLon, e, n)
            }
        }.filter { it.size >= 2 }
    }
}

data class GuidanceState(
    val line: AbLine? = null,
    val solution: GuidanceSolution? = null,
    /** Ground worked (m²), each square metre counted once (coverage map). */
    val worked: Double = 0.0,
    val offLine: Boolean = false,
    /** Coverage is being recorded (implement working). */
    val recording: Boolean = true,
    /** Ground worked twice or more (m²). */
    val overlap: Double = 0.0,
    /** Field whose coverage is reported, its covered share (0..1) and uncovered area (m²). */
    val field: String? = null,
    val fieldShare: Double? = null,
    val gaps: Double? = null,
    /** Coverage map version (changes when the map should be redrawn). */
    val coverageVersion: Long = 0,
)

/** The active A-B line, updated with every fix (Tools > A-B guidance), and the saved lines. */
class GuidanceSession(private val file: File, private val coverageFile: File = File(file.parentFile, "coverage.bin")) {
    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<AbLine>> = _lines.asStateFlow()
    private var lastLat = Double.NaN; private var lastLon = 0.0
    private var forward = true
    /** Off-line warning distance (m); 0 = off. */
    @Volatile var warnAt = 0.0
    /** Field boundaries, to find the field being worked (set by the engine). */
    @Volatile var fields: () -> List<com.rfsat.pocketgnss.data.Field> = { emptyList() }

    /** The worked-area map, kept across guidance sessions until cleared (saved in points/coverage.bin). */
    var coverage: CoverageMap? = CoverageMap.load(coverageFile); private set
    private var fieldKey: String? = null
    private val startMs = System.currentTimeMillis()
    private var savedMs = 0L

    @Synchronized fun activate(l: AbLine?) {
        if (l == null) saveCoverage()
        _state.value = withCoverage(GuidanceState(l, recording = _state.value.recording)); lastLat = Double.NaN
    }

    /** Starts or pauses recording coverage (the implement is working or lifted). */
    @Synchronized fun setRecording(on: Boolean) {
        if (!on) saveCoverage()
        _state.value = _state.value.copy(recording = on); lastLat = Double.NaN
    }

    /** Coverage map shapes for drawing, or null without coverage. */
    fun coverageShapes(): CoverageShapes? = coverage?.shapes()

    private fun saveCoverage() { coverage?.let { try { it.save(coverageFile) } catch (_: Exception) {} }; savedMs = System.currentTimeMillis() }

    private fun withCoverage(st: GuidanceState): GuidanceState {
        val c = coverage ?: return st.copy(worked = 0.0, overlap = 0.0, field = null, fieldShare = null, gaps = null, coverageVersion = 0)
        val f = fields().firstOrNull { it.key == fieldKey }
        return st.copy(worked = c.coveredArea, overlap = c.overlapArea, field = f?.name, fieldShare = c.fieldShare, gaps = c.gapArea,
            coverageVersion = c.version)
    }

    /** Uses the field that contains [lat] / [lon] for the coverage share, if the map has none yet. */
    private fun pickField(lat: Double, lon: Double) {
        val c = coverage ?: return
        if (fieldKey != null && fields().any { it.key == fieldKey }) return
        val f = fields().firstOrNull { it.vertices.size >= 3 && insideField(it, lat, lon) } ?: return
        fieldKey = f.key
        c.setField(f.vertices.map { it.latitude to it.longitude })
    }

    @Synchronized fun setShift(shift: Double) {
        val st = _state.value
        val l = st.line ?: return
        val n = l.copy(shift = shift)
        _state.value = st.copy(line = n)
        if (_lines.value.any { it.key == l.key }) save(_lines.value.map { if (it.key == l.key) n else it })
    }

    /** Clears the coverage map (a new job). */
    @Synchronized fun resetWorked() {
        coverage = null; fieldKey = null; coverageFile.delete(); lastLat = Double.NaN
        _state.value = withCoverage(_state.value)
    }

    /**
     * A new position: the guidance solution and, while recording, the swath since the last position painted on the
     * coverage map (steps of more than 50 m, e.g. after a gap in the fixes, are not painted).
     */
    @Synchronized fun onFix(lat: Double, lon: Double, courseDeg: Double?, nowMs: Long = System.currentTimeMillis()) {
        val st = _state.value
        val l = st.line ?: return
        val s = Guidance.solve(l, lat, lon, courseDeg, forward)
        forward = s.forward
        if (st.recording) {
            val c = coverage ?: CoverageMap(lat, lon, CoverageMap.cellFor(l.width)).also { coverage = it }
            pickField(lat, lon)
            if (!lastLat.isNaN()) {
                val a = c.local(lastLat, lastLon); val b = c.local(lat, lon)
                val step = hypot(b[0] - a[0], b[1] - a[1])
                if (step in 0.01..50.0) c.paint(a[0], a[1], b[0], b[1], l.width, (nowMs - startMs) / 1000.0)
            }
            lastLat = lat; lastLon = lon
            if (nowMs - savedMs > 30_000) saveCoverage()
        }
        _state.value = withCoverage(st.copy(solution = s, offLine = warnAt > 0 && abs(s.offset) > warnAt))
    }

    fun add(l: AbLine) = save(_lines.value + l)
    fun remove(l: AbLine) = save(_lines.value.filterNot { it.key == l.key })

    /** Recording a curve (Tools > A-B guidance > Record curve): the path driven, a point at least every [CURVE_STEP] m. */
    data class CurveRecording(val active: Boolean = false, val points: Int = 0, val length: Double = 0.0)
    private val _curveRec = MutableStateFlow(CurveRecording())
    val curveRecording: StateFlow<CurveRecording> = _curveRec.asStateFlow()
    private val curvePts = ArrayList<Pair<Double, Double>>()

    @Synchronized fun startCurve() { curvePts.clear(); _curveRec.value = CurveRecording(true) }
    @Synchronized fun cancelCurve() { curvePts.clear(); _curveRec.value = CurveRecording() }

    /** A position for the curve being recorded (any time, also without an active line). */
    @Synchronized fun recordCurve(lat: Double, lon: Double) {
        val r = _curveRec.value
        if (!r.active) return
        val last = curvePts.lastOrNull()
        val step = last?.let { Geo.distance(it.first, it.second, lat, lon) } ?: 0.0
        if (last != null && (step < CURVE_STEP || step > 50)) return
        curvePts.add(lat to lon)
        _curveRec.value = CurveRecording(true, curvePts.size, r.length + step)
    }

    /**
     * Ends the recording: the path, simplified to [toleranceM] (points closer than that to the result are dropped), becomes
     * a curve pattern, saved and activated. Null when it is too short (under 10 m).
     */
    @Synchronized fun finishCurve(name: String, width: Double, timeMs: Long, toleranceM: Double = 0.05): AbLine? {
        val pts = curvePts.toList(); val len = _curveRec.value.length
        cancelCurve()
        if (pts.size < 3 || len < 10) return null
        val a = pts.first()
        val e = pts.map { Geo.enu(a.first, a.second, 0.0, it.first, it.second, 0.0) }
        val keep = Polyline.simplify(DoubleArray(e.size) { e[it][0] }, DoubleArray(e.size) { e[it][1] }, toleranceM)
        val l = AbLine.curve(name, keep.map { pts[it] }, width, timeMs = timeMs)
        add(l); activate(l)
        return l
    }

    @Synchronized private fun save(list: List<AbLine>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("name;a_lat;a_lon;b_lat;b_lon;width_m;shift_m;time_ms;kind;points\n")
            for (l in list) w.write(listOf(l.name.replace(';', ','), "%.10f".format(Locale.US, l.aLat), "%.10f".format(Locale.US, l.aLon),
                "%.10f".format(Locale.US, l.bLat), "%.10f".format(Locale.US, l.bLon), "%.3f".format(Locale.US, l.width),
                "%.3f".format(Locale.US, l.shift), l.timeMs, if (!l.isCurve) "ab" else if (l.closed) "headland" else "curve",
                l.curve.joinToString("|") { "%.10f %.10f".format(Locale.US, it.first, it.second) }).joinToString(";") + "\n")
        }
        _lines.value = list
    }

    private fun load(): List<AbLine> {
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { r ->
            val f = r.split(';')
            if (f.size < 8) null else try {
                val curve = f.getOrNull(9)?.takeIf { it.isNotBlank() }?.split('|')?.map { p -> p.trim().split(' ').let { it[0].toDouble() to it[1].toDouble() } }
                    ?: emptyList()
                AbLine(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toLong(),
                    curve, f.getOrNull(8) == "headland")
            } catch (_: Exception) { null }
        }
    }

    companion object {
        /** Spacing of the points of a recorded curve (m). */
        const val CURVE_STEP = 0.5
    }
}

/** Point in a field boundary (latitude / longitude ring; fine for fields of a few kilometres). */
fun insideField(f: com.rfsat.pocketgnss.data.Field, lat: Double, lon: Double): Boolean {
    val v = f.vertices; var inside = false; var j = v.size - 1
    for (i in v.indices) {
        val yi = v[i].latitude; val xi = v[i].longitude; val yj = v[j].latitude; val xj = v[j].longitude
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
