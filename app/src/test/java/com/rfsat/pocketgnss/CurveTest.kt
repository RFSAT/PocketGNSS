package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.core.Guidance
import com.rfsat.pocketgnss.core.Polyline
import com.rfsat.pocketgnss.protocol.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Curved and headland guidance: distances to a polyline, parallel passes, rings. */
class CurveTest {
    private val lat0 = 52.23; private val lon0 = 21.01

    /** A quarter circle of radius 100 m around (0, 0), from (100, 0) to (0, 100), as a polyline of 1 m steps. */
    private fun arc(): Polyline {
        val n = 158
        val xs = DoubleArray(n + 1) { 100 * cos(PI / 2 * it / n) }; val ys = DoubleArray(n + 1) { 100 * sin(PI / 2 * it / n) }
        return Polyline(xs, ys, false)
    }

    @Test fun distanceToAnArcIsTheRadialDistance() {
        val c = arc()
        // the curve runs counter-clockwise, so the centre is on its left: a point at radius r is (r - 100) m to its right
        for (r in listOf(70.0, 94.0, 100.0, 106.0, 130.0)) for (a in listOf(0.2, 0.7, 1.2)) {
            val p = c.project(r * cos(a), r * sin(a))
            assertEquals("r $r a $a", r - 100, p.cross, 0.02)
            // along a polyline of 1 m chords, off the curve the distance along it is measured on the nearest chord
            assertEquals(100 * a, p.along, 0.2)
        }
        // before its start the curve continues straight along its first chord
        val ux = c.x[1] - c.x[0]; val uy = c.y[1] - c.y[0]; val ul = hypot(ux, uy)
        val p = c.project(110.0, -50.0)
        val rx = 110.0 - c.x[0]; val ry = -50.0 - c.y[0]
        assertEquals((rx * uy - ry * ux) / ul, p.cross, 1e-9)
        assertEquals((rx * ux + ry * uy) / ul, p.along, 1e-9)
        assertTrue(p.along < -49)
    }

    @Test fun offsetCurvesAreParallel() {
        val c = arc()
        for (o in listOf(-18.0, -6.0, 6.0, 30.0)) {
            val pts = c.offset(o)
            assertTrue(pts.size > 50)
            for ((x, y) in pts) if (hypot(x, y) > 1) assertEquals("offset $o", 100 + o, hypot(x, y), 0.05)
        }
        // a hairpin 2 m wide: outside it the offset runs round the bend at 10 m; inside it there is no room for a
        // pass 10 m away, so nothing is drawn there
        val xs = doubleArrayOf(0.0, 50.0, 52.0, 0.0); val ys = doubleArrayOf(0.0, 0.0, 2.0, 2.0)
        val u = Polyline(xs, ys, false)
        val outside = u.offset(10.0)
        assertTrue(outside.size > 5)
        for ((x, y) in outside) assertEquals(10.0, u.project(x, y).cross, 0.05)
        assertTrue(u.offset(-10.0).isEmpty())
    }

    @Test fun ringInsideIsLeftOfACounterClockwiseRing() {
        val sq = Polyline(doubleArrayOf(0.0, 100.0, 100.0, 0.0), doubleArrayOf(0.0, 0.0, 100.0, 100.0), true)
        assertTrue(sq.area() > 0)
        assertEquals(-10.0, sq.project(50.0, 10.0).cross, 1e-9)     // inside, 10 m from the south edge
        assertEquals(5.0, sq.project(50.0, -5.0).cross, 1e-9)       // outside
        assertEquals(-3.0, sq.project(97.0, 97.0).cross, 1e-9)      // inside, near the north-east corner
        assertEquals(400.0, sq.length, 1e-9)
        val o = sq.offset(-3.0)
        for ((x, y) in o) assertEquals(-3.0, sq.project(x, y).cross, 1e-6)
    }

    @Test fun curvePatternGuidance() {
        // a curve pattern from geodetic points along the arc, 6 m passes
        val c = arc()
        val pts = c.x.indices.map { Geo.fromEnu(lat0, lon0, c.x[it], c.y[it]) }
        val l = AbLine.curve("Contour", pts, 6.0, closed = false, timeMs = 1)
        // inside the arc, 13.5 m from it: pass -2 (12 m in), 1.5 m further in
        val p = Geo.fromEnu(lat0, lon0, 86.5 * cos(0.8), 86.5 * sin(0.8))
        val s = Guidance.solve(l, p.first, p.second, null)
        assertEquals(-2, s.pass)
        assertEquals(-1.5, s.offset, 0.03)
        assertEquals(80.0, s.along, 0.1)
        // driving along the curve (counter-clockwise: heading = 90 + 0.8 rad... in compass terms)
        val tangent = Math.toDegrees(kotlin.math.atan2(-sin(0.8), cos(0.8)))
        val fw = Guidance.solve(l, p.first, p.second, (tangent + 360) % 360)
        assertTrue(fw.forward); assertEquals(0.0, fw.headingError!!, 0.5)
        val back = Guidance.solve(l, p.first, p.second, (tangent + 180 + 360) % 360)
        assertTrue(!back.forward); assertEquals(1.5, back.steer, 0.03)
        // map lines are parallel curves
        val lines = Guidance.mapLines(l, s, neighbours = 1, halfLength = 50.0)
        assertEquals(3, lines.size)
        for ((la, lo) in lines[0]) { val e = Geo.enu(lat0, lon0, 0.0, la, lo, 0.0); assertEquals(88.0, hypot(e[0], e[1]), 0.1) }
    }

    @Test fun headlandPasses() {
        // a 200 m x 100 m field, headland passes 6 m wide: the first runs 3 m inside the boundary
        val ring = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 100.0, 0.0 to 100.0).map { (e, n) -> Geo.fromEnu(lat0, lon0, e, n) }
        val l = AbLine.headland("Headland", ring, 6.0, timeMs = 2)
        assertTrue(l.closed)
        fun at(e: Double, n: Double) = Geo.fromEnu(lat0, lon0, e, n)
        val a = at(100.0, 3.0); val s0 = Guidance.solve(l, a.first, a.second, null)
        assertEquals(0, s0.pass); assertEquals(0.0, s0.offset, 0.02)
        val b = at(100.0, 9.5); val s1 = Guidance.solve(l, b.first, b.second, null)
        assertEquals(1, Guidance.headlandPass(s0)); assertEquals(2, Guidance.headlandPass(s1)); assertEquals(0.5, kotlin.math.abs(s1.offset), 0.02)
        val out = at(100.0, -4.0); assertEquals(0, Guidance.headlandPass(Guidance.solve(l, out.first, out.second, null)))
        // the drawn passes run parallel inside the boundary, also round the corners
        for ((la, lo) in Guidance.mapLines(l, s0, neighbours = 1)[0]) {
            val e = Geo.enu(lat0, lon0, 0.0, la, lo, 0.0)
            assertEquals(3.0, minOf(e[0], 200 - e[0], e[1], 100 - e[1]), 0.05)
        }
        // a clockwise boundary gives the same passes
        val l2 = AbLine.headland("H", ring.reversed(), 6.0)
        assertEquals(0.0, Guidance.solve(l2, a.first, a.second, null).offset, 0.02)
    }

    @Test fun recordStoreAndReload() {
        val dir = java.nio.file.Files.createTempDirectory("curve").toFile()
        val g = com.rfsat.pocketgnss.core.GuidanceSession(java.io.File(dir, "g.csv"))
        g.startCurve()
        // an S-curve 120 m long, positions every 0.2 m with 1 cm noise
        val rnd = java.util.Random(3)
        for (i in 0..600) {
            val e = i * 0.2; val n = 15 * sin(e / 120 * 2 * PI)
            val p = Geo.fromEnu(lat0, lon0, e + rnd.nextGaussian() * 0.01, n + rnd.nextGaussian() * 0.01)
            g.recordCurve(p.first, p.second)
        }
        assertTrue(g.curveRecording.value.points in 200..260)
        val l = g.finishCurve("S", 4.0, 7L)!!
        assertTrue("simplified to ${l.curve.size} points", l.curve.size in 10..150)
        assertEquals(l.key, g.state.value.line!!.key)
        // the simplified curve stays within a few centimetres of the S
        for (x in listOf(10.0, 37.0, 64.0, 99.0)) {
            val p = Geo.fromEnu(lat0, lon0, x, 15 * sin(x / 120 * 2 * PI))
            assertEquals(0.0, Guidance.solve(l, p.first, p.second, null).offset, 0.06)
        }
        g.add(AbLine.headland("H", listOf(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0).map { (e, n) -> Geo.fromEnu(lat0, lon0, e, n) }, 3.0, 8L))
        val back = com.rfsat.pocketgnss.core.GuidanceSession(java.io.File(dir, "g.csv")).lines.value
        assertEquals(2, back.size)
        assertEquals(l.curve.size, back[0].curve.size); assertTrue(!back[0].closed)
        assertTrue(back[1].closed && back[1].isCurve)
        assertEquals(l.curve.last().first, back[0].curve.last().first, 1e-9)
        // too short a recording gives no line
        g.startCurve(); g.recordCurve(lat0, lon0); assertEquals(null, g.finishCurve("x", 3.0, 9L))
        dir.deleteRecursively()
    }
}
