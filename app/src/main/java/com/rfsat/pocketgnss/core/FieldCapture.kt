package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.data.simplify
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a boundary is captured: by walking / driving along it, or by standing on each corner. */
enum class CaptureMode { WALK, CORNERS }

data class CaptureState(
    val running: Boolean = false,
    val mode: CaptureMode = CaptureMode.WALK,
    val minQuality: FixQuality = FixQuality.RTK_FIXED,
    /** Walk: a vertex every this many metres. */
    val spacingM: Double = 1.0,
    /** Corners: seconds of fixes averaged per corner (0 = the current fix). */
    val averageSec: Int = 5,
    val vertices: List<Vertex> = emptyList(),
    /** Worst fix type among the vertices. */
    val worst: FixQuality = FixQuality.RTK_FIXED,
    /** Fixes not used because they were below [minQuality]. */
    val rejected: Int = 0,
    /** Corners: an average is being taken (fixes so far). */
    val averaging: Int? = null,
    /** Walk: paused (e.g. while turning at a headland or going round an obstacle). */
    val paused: Boolean = false,
)

/**
 * Field boundary capture (Tools > Field boundary). Walk mode adds a vertex whenever the position has moved
 * [CaptureState.spacingM] from the last one; corner mode averages the fixes of a few seconds when a corner is marked.
 * Only fixes of at least [CaptureState.minQuality] are used. Pure logic, unit-tested.
 */
class FieldCapture {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var avgEnd = 0L
    private var sLat = 0.0; private var sLon = 0.0; private var sH = 0.0; private var nH = 0; private var n = 0; private var avgWorst = FixQuality.RTK_FIXED
    private var last: Vertex? = null; private var lastQ = FixQuality.NONE; private var lastMs = 0L

    @Synchronized fun start(mode: CaptureMode, minQuality: FixQuality, spacingM: Double, averageSec: Int) {
        n = 0; last = null
        _state.value = CaptureState(true, mode, minQuality, spacingM.coerceAtLeast(0.1), averageSec.coerceAtLeast(0), worst = FixQuality.RTK_FIXED)
    }

    @Synchronized fun onFix(lat: Double, lon: Double, h: Double?, q: FixQuality, now: Long) {
        val st = _state.value
        if (!st.running) return
        if (q.rank < st.minQuality.rank) { _state.value = st.copy(rejected = st.rejected + 1); return }
        val v = Vertex(lat, lon, h ?: Double.NaN)
        last = v; lastQ = q; lastMs = now
        if (st.averaging != null) {
            sLat += lat; sLon += lon; if (h != null) { sH += h; nH++ }; n++
            if (q.rank < avgWorst.rank) avgWorst = q
            if (now >= avgEnd) addVertex(Vertex(sLat / n, sLon / n, if (nH > 0) sH / nH else Double.NaN), avgWorst)
            else _state.value = st.copy(averaging = n)
            return
        }
        if (st.mode == CaptureMode.WALK && !st.paused) {
            val prev = st.vertices.lastOrNull()
            if (prev == null || Geo.distance(prev.latitude, prev.longitude, lat, lon) >= st.spacingM) addVertex(v, q)
        }
    }

    /**
     * Marks a corner here: starts averaging, or with no averaging takes the latest usable fix (if under 2 s old).
     * In walk mode it forces a vertex at a sharp corner. Returns false when there is no usable fix.
     */
    @Synchronized fun addCorner(now: Long): Boolean {
        val st = _state.value
        if (!st.running || st.averaging != null) return false
        if (st.mode == CaptureMode.CORNERS && st.averageSec > 0) {
            avgEnd = now + st.averageSec * 1000L; sLat = 0.0; sLon = 0.0; sH = 0.0; nH = 0; n = 0; avgWorst = FixQuality.RTK_FIXED
            _state.value = st.copy(averaging = 0)
            return true
        }
        val v = last ?: return false
        if (now - lastMs > 2000) return false
        addVertex(v, lastQ)
        return true
    }

    @Synchronized fun undo() {
        val st = _state.value
        if (st.averaging != null) { _state.value = st.copy(averaging = null); return }
        if (st.vertices.isNotEmpty()) _state.value = st.copy(vertices = st.vertices.dropLast(1))
    }

    @Synchronized fun setPaused(p: Boolean) { _state.value = _state.value.copy(paused = p) }

    @Synchronized fun cancel() { _state.value = _state.value.copy(running = false, vertices = emptyList(), averaging = null, paused = false) }

    /** Ends the capture: the boundary's vertices (walked ones simplified to [toleranceM]) and the worst fix type used. */
    @Synchronized fun finish(toleranceM: Double = 0.02): Pair<List<Vertex>, FixQuality> {
        val st = _state.value
        var v = st.vertices
        // A walked loop usually ends close to its start: drop the closing vertices that duplicate it.
        if (st.mode == CaptureMode.WALK) {
            while (v.size > 3 && Geo.distance(v.first().latitude, v.first().longitude, v.last().latitude, v.last().longitude) < st.spacingM / 2) v = v.dropLast(1)
            v = simplify(v, toleranceM)
        }
        _state.value = st.copy(running = false, vertices = emptyList(), averaging = null, paused = false)
        return v to st.worst
    }

    private fun addVertex(v: Vertex, q: FixQuality) {
        val st = _state.value
        _state.value = st.copy(vertices = st.vertices + v, worst = if (q.rank < st.worst.rank) q else st.worst, averaging = null)
    }
}
