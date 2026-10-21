package com.rfsat.pocketgnss.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of a guidance curve in a local east / north plane (metres): a polyline, open (a recorded curve, continued
 * straight beyond both ends) or closed (a field boundary, for headland passes). The passes of a curve pattern are the
 * curves at whole multiples of the working width from it, i.e. the points at that distance from the polyline, so the
 * guidance solution is the signed distance to the nearest point of the polyline (+ = right of its direction).
 */
class Polyline(xs: DoubleArray, ys: DoubleArray, val closed: Boolean) {
    val x: DoubleArray
    val y: DoubleArray
    /** Arc length at each vertex (and, for a ring, at the return to the first vertex). */
    val s: DoubleArray
    val length: Double

    init {
        // drop repeated vertices and the closing duplicate of a ring
        val px = ArrayList<Double>(); val py = ArrayList<Double>()
        for (i in xs.indices) if (px.isEmpty() || hypot(xs[i] - px.last(), ys[i] - py.last()) > 1e-6) { px.add(xs[i]); py.add(ys[i]) }
        if (closed && px.size > 2 && hypot(px.first() - px.last(), py.first() - py.last()) < 1e-6) { px.removeAt(px.size - 1); py.removeAt(py.size - 1) }
        require(px.size >= 2 && (!closed || px.size >= 3)) { "too few distinct points" }
        x = px.toDoubleArray(); y = py.toDoubleArray()
        s = DoubleArray(segments + 1)
        for (i in 0 until segments) s[i + 1] = s[i] + hypot(x[(i + 1) % x.size] - x[i], y[(i + 1) % x.size] - y[i])
        length = s[segments]
    }

    val segments: Int get() = if (closed) x.size else x.size - 1

    /** Unit direction of segment [i]. */
    private fun dir(i: Int): DoubleArray {
        val j = (i + 1) % x.size; val dx = x[j] - x[i]; val dy = y[j] - y[i]; val l = hypot(dx, dy)
        return doubleArrayOf(dx / l, dy / l)
    }

    /**
     * Nearest point: [along] arc length (on an open curve, beyond the ends: negative or more than [length], on the
     * straight continuations), [cross] signed distance (+ right of the direction), [heading] direction of the curve
     * there (radians clockwise from north).
     */
    data class Projection(val along: Double, val cross: Double, val heading: Double)

    fun project(px: Double, py: Double): Projection {
        var best = Double.MAX_VALUE; var bi = 0; var bt = 0.0
        val n = segments
        for (i in 0 until n) {
            val j = (i + 1) % x.size
            val dx = x[j] - x[i]; val dy = y[j] - y[i]; val l2 = dx * dx + dy * dy
            var t = ((px - x[i]) * dx + (py - y[i]) * dy) / l2
            t = when {
                closed -> t.coerceIn(0.0, 1.0)
                n == 1 -> t                              // a single segment: a straight line
                i == 0 -> minOf(t, 1.0)                  // continued straight before the start
                i == n - 1 -> maxOf(t, 0.0)              // and after the end
                else -> t.coerceIn(0.0, 1.0)
            }
            val qx = x[i] + t * dx; val qy = y[i] + t * dy
            val d = (px - qx) * (px - qx) + (py - qy) * (py - qy)
            if (d < best - 1e-12) { best = d; bi = i; bt = t }
        }
        val u = dir(bi)
        var ux = u[0]; var uy = u[1]
        // at a vertex the side is decided by the bisector of the two segments meeting there
        val atStart = bt <= 0.0 && (closed || bi > 0); val atEnd = bt >= 1.0 && (closed || bi < n - 1)
        if (atStart || atEnd) {
            val v = dir(if (atStart) (bi - 1 + n) % n else (bi + 1) % n)
            val bx = ux + v[0]; val by = uy + v[1]; val bl = hypot(bx, by)
            if (bl > 1e-9) { ux = bx / bl; uy = by / bl }
        }
        val l = s[bi + 1] - s[bi]
        val qx = x[bi] + bt * (x[(bi + 1) % x.size] - x[bi]); val qy = y[bi] + bt * (y[(bi + 1) % x.size] - y[bi])
        val rx = px - qx; val ry = py - qy
        val sign = if (rx * uy - ry * ux >= 0) 1.0 else -1.0    // right of the direction (ux, uy) is (uy, -ux)
        return Projection(s[bi] + bt * l, sign * sqrt(best), atan2(ux, uy))
    }

    /** Signed area (+ = counter-clockwise), for rings. */
    fun area(): Double {
        var a = 0.0
        for (i in x.indices) { val j = (i + 1) % x.size; a += x[i] * y[j] - x[j] * y[i] }
        return a / 2
    }

    /**
     * The parallel curve at [offset] metres (+ right, - left) for drawing: on the outer side of a bend an arc round the
     * vertex, on the inner side the vertex moved along the bisector, dropping points that end up nearer to the curve
     * than the offset (the loops an offset forms inside bends tighter than itself). For an open curve, the part from
     * arc length [from] to [to], continued straight beyond the ends when they are outside; a ring is drawn whole.
     */
    fun offset(offset: Double, from: Double = 0.0, to: Double = length): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val tol = 0.02 + 1e-3 * abs(offset)
        fun add(px: Double, py: Double, check: Boolean = true) {
            if (!check || abs(project(px, py).cross - offset) < tol) out.add(px to py)
        }
        val n = x.size
        if (!closed && from < 0) {
            val u = dir(0); val e = minOf(-from, 500.0)
            add(x[0] - u[0] * e + u[1] * offset, y[0] - u[1] * e - u[0] * offset)
        }
        for (i in 0 until n) {
            if (!closed && (s[i] < from - 1 || s[i] > to + 1)) continue
            val prev = if (closed || i > 0) dir((i - 1 + n) % n) else null
            val next = if (closed || i < n - 1) dir(i) else null
            if (prev == null || next == null) {
                val u = prev ?: next!!
                add(x[i] + u[1] * offset, y[i] - u[0] * offset); continue
            }
            val turn = prev[0] * next[1] - prev[1] * next[0]     // > 0: turning left
            if (offset * turn > 1e-12) {
                // outer side: arc from the previous segment's normal to the next one's
                val a0 = atan2(-prev[0], prev[1]); var a1 = atan2(-next[0], next[1])   // angle of (uy, -ux) as atan2(y, x)
                var d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI
                val steps = maxOf(1, (abs(d) / Math.toRadians(10.0)).toInt())
                for (k in 0..steps) { val a = a0 + d * k / steps; add(x[i] + cos(a) * offset, y[i] + sin(a) * offset, check = false) }
            } else {
                val bx = prev[0] + next[0]; val by = prev[1] + next[1]; val bl = hypot(bx, by)
                if (bl < 1e-9) continue
                val ux = bx / bl; val uy = by / bl
                val c = (ux * next[0] + uy * next[1]).coerceAtLeast(0.2)
                add(x[i] + uy * offset / c, y[i] - ux * offset / c)
            }
        }
        if (closed) { if (out.size > 2) out.add(out.first()) }
        else if (to > length) {
            val u = dir(n - 2); val e = minOf(to - length, 500.0)
            add(x[n - 1] + u[0] * e + u[1] * offset, y[n - 1] + u[1] * e - u[0] * offset)
        }
        return out
    }

    companion object {
        /** Ramer–Douglas–Peucker: indices of the points to keep so that no dropped point is more than [tol] from the result. */
        fun simplify(xs: DoubleArray, ys: DoubleArray, tol: Double): IntArray {
            if (xs.size <= 2) return xs.indices.toList().toIntArray()
            val keep = BooleanArray(xs.size); keep[0] = true; keep[xs.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(0 to xs.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b <= a + 1) continue
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]; val l = hypot(dx, dy)
                var dmax = -1.0; var im = -1
                for (i in a + 1 until b) {
                    val d = if (l < 1e-9) hypot(xs[i] - xs[a], ys[i] - ys[a]) else abs((xs[i] - xs[a]) * dy - (ys[i] - ys[a]) * dx) / l
                    if (d > dmax) { dmax = d; im = i }
                }
                if (dmax > tol) { keep[im] = true; stack.add(a to im); stack.add(im to b) }
            }
            return keep.indices.filter { keep[it] }.toIntArray()
        }
    }
}
