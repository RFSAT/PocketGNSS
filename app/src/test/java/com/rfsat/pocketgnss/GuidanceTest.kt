package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.core.Guidance
import com.rfsat.pocketgnss.core.GuidanceSession
import com.rfsat.pocketgnss.protocol.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuidanceTest {
    // A-B runs east, 6 m implement: right of A-B (looking east) is south.
    private val a = 50.0 to 10.0
    private val b = Geo.fromEnu(50.0, 10.0, 200.0, 0.0)
    private val line = AbLine("AB", a.first, a.second, b.first, b.second, 6.0)
    private fun at(east: Double, north: Double) = Geo.fromEnu(a.first, a.second, east, north)

    @Test fun passesAndOffsets() {
        assertEquals(90.0, line.bearing, 1e-6)
        // 0.12 m south of A-B, driving east: right of the line, steer left
        var p = at(50.0, -0.12)
        var s = Guidance.solve(line, p.first, p.second, 90.0)
        assertEquals(0, s.pass); assertEquals(0.12, s.offset, 1e-4); assertEquals(0.12, s.steer, 1e-4); assertEquals(50.0, s.along, 1e-3)
        // the same spot driving west: the driver is left of the line, steer right
        s = Guidance.solve(line, p.first, p.second, 270.0)
        assertFalse(s.forward); assertEquals(-0.12, s.steer, 1e-4)
        // third pass to the left (north), 0.3 m past it
        p = at(-40.0, 18.3)
        s = Guidance.solve(line, p.first, p.second, 85.0)
        assertEquals(-3, s.pass); assertEquals(-0.3, s.offset, 1e-4); assertEquals(-40.0, s.along, 1e-3); assertEquals(-5.0, s.headingError!!, 0.01)
        // heading error driving back on a pass
        s = Guidance.solve(line, p.first, p.second, 272.0)
        assertEquals(2.0, s.headingError!!, 0.01)
        // A shift of 1 m to the right moves every pass south
        s = Guidance.solve(line.copy(shift = 1.0), at(10.0, -1.0).first, at(10.0, -1.0).second, null)
        assertEquals(0, s.pass); assertEquals(0.0, s.offset, 1e-4)
    }

    @Test fun lineFromBearing() {
        val l = AbLine.fromBearing("N", 50.0, 10.0, 30.0, 3.0)
        assertEquals(30.0, l.bearing, 1e-6)
        val p = Geo.fromEnu(50.0, 10.0, 3.0 * Math.cos(Math.toRadians(30.0)), -3.0 * Math.sin(Math.toRadians(30.0)))
        val s = Guidance.solve(l, p.first, p.second, 30.0)
        assertEquals(1, s.pass); assertEquals(0.0, s.offset, 1e-3)
    }

    @Test fun mapLinesFollowPasses() {
        val p = at(100.0, 6.1)
        val s = Guidance.solve(line, p.first, p.second, 90.0)
        val ls = Guidance.mapLines(line, s, neighbours = 1, halfLength = 50.0)
        assertEquals(3, ls.size)
        val mid = ls[0].let { (it[0].first + it[1].first) / 2 to (it[0].second + it[1].second) / 2 }
        val e = Geo.enu(a.first, a.second, 0.0, mid.first, mid.second, 0.0)
        assertEquals(100.0, e[0], 0.01); assertEquals(6.0, e[1], 0.01)
    }

    @Test fun sessionWorkedAreaAndStore() {
        val dir = Files.createTempDirectory("guide").toFile()
        val g = GuidanceSession(File(dir, "g.csv"))
        g.add(line); g.activate(line); g.warnAt = 0.25
        for (i in 0..100) { val p = at(i * 1.0, 0.05); g.onFix(p.first, p.second, 90.0) }
        assertEquals(100.0 * 6, g.state.value.worked, 1.0)
        assertFalse(g.state.value.offLine)
        val off = at(101.0, 0.4); g.onFix(off.first, off.second, 90.0)
        assertTrue(g.state.value.offLine)
        g.setShift(0.1)
        assertEquals(0.1, GuidanceSession(File(dir, "g.csv")).lines.value.single().shift, 1e-9)
        dir.deleteRecursively()
    }
}
