package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.CoverageMap
import com.rfsat.pocketgnss.core.GuidanceSession
import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.data.Field
import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.protocol.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

class CoverageTest {
    private val lat0 = 40.76; private val lon0 = 22.52   // Pella

    /** Drives (e, n) points at [dt] s each, starting at [t0]. */
    private fun drive(m: CoverageMap, pts: List<Pair<Double, Double>>, width: Double, t0: Double, dt: Double = 0.2): Double {
        var t = t0
        for (i in 1 until pts.size) { m.paint(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, width, t); t += dt }
        return t
    }

    private fun line(e0: Double, n0: Double, e1: Double, n1: Double, step: Double = 0.4): List<Pair<Double, Double>> {
        val len = kotlin.math.hypot(e1 - e0, n1 - n0)
        val k = (len / step).toInt()
        return (0..k).map { i -> (e0 + (e1 - e0) * i / k) to (n0 + (n1 - n0) * i / k) }
    }

    @Test fun passesOverlapAndTurns() {
        val w = 4.0
        val m = CoverageMap(lat0, lon0, CoverageMap.cellFor(w))
        assertEquals(0.5, m.cell, 0.0)
        // first pass east, a U-turn (half circle of radius 1.75 m), second pass west 3.5 m to the north: 0.5 m overlap
        var t = drive(m, line(0.0, 0.0, 200.0, 0.0), w, 0.0)
        val turn = (0..30).map { i -> val a = -Math.PI / 2 + Math.PI * i / 30; (200.0 + 1.75 * cos(a)) to (1.75 + 1.75 * sin(a)) }
        t = drive(m, turn, w, t)
        assertEquals("the turn joins its own pass: no overlap", 0.0, m.overlapArea, 1e-9)
        t += 20.0
        drive(m, line(200.0, 3.5, 0.0, 3.5), w, t)
        // covered: 200 m x 7.5 m plus the turn's bulge; overlap: 200 m x 0.5 m (cells within a cell at the ends and edges)
        println("covered %.1f m², overlap %.1f m²".format(m.coveredArea, m.overlapArea))
        assertEquals(200 * 7.5, m.coveredArea, 0.03 * 200 * 7.5)
        assertTrue(m.overlapArea in 80.0..120.0)
    }

    /** Times far from zero (e.g. epoch milliseconds / 1000) must still separate passes. */
    @Test fun largeTimes() {
        val m = CoverageMap(lat0, lon0, 0.5)
        val t = drive(m, line(0.0, 0.0, 100.0, 0.0), 4.0, 1.76e9)
        drive(m, line(100.0, 3.0, 0.0, 3.0), 4.0, t + 30)
        assertEquals(100.0, m.overlapArea, 5.0)
    }

    @Test fun fieldShareGapsShapesAndSave() {
        val w = 4.0
        val m = CoverageMap(lat0, lon0, CoverageMap.cellFor(w))
        // field 100 m x 12 m, worked by two passes 4 m wide (8 m of 12 m): two thirds covered, 400 m² gaps
        val ring = listOf(-2.0 to -2.0, 98.0 to -2.0, 98.0 to 10.0, -2.0 to 10.0).map { (e, n) -> Geo.fromEnu(lat0, lon0, e, n) }
        var t = drive(m, line(-2.0, 0.0, 98.0, 0.0), w, 0.0)
        m.setField(ring)                               // set after work: the covered cells are counted again
        drive(m, line(98.0, 4.0, -2.0, 4.0), w, t + 30)
        assertEquals(1200.0, m.fieldArea, 1.0)
        assertEquals(2.0 / 3, m.fieldShare!!, 0.02)
        assertEquals(400.0, m.gapArea!!, 20.0)
        // rectangles cover exactly the covered cells at full resolution
        val sh = m.shapes()
        fun area(r: List<Pair<Double, Double>>): Double {
            val p = r.map { Geo.enu(lat0, lon0, 0.0, it.first, it.second, 0.0) }
            return kotlin.math.abs((0 until p.size - 1).sumOf { p[it][0] * p[it + 1][1] - p[it + 1][0] * p[it][1] }) / 2
        }
        assertEquals(m.coveredArea, sh.covered.sumOf { area(it) }, 0.01 * m.coveredArea)
        assertTrue("merged rows and columns: ${sh.covered.size}", sh.covered.size < 20)
        // GeoJSON export: read back by the app's own importer, the rectangles add up to the worked area
        val gj = com.rfsat.pocketgnss.data.Exporters.coverageGeoJson(sh, m.coveredArea, m.overlapArea, "North")
        val back = com.rfsat.pocketgnss.data.Importer(null, 0L, "t").read(gj.toByteArray())
        assertEquals(sh.covered.size + sh.overlap.size, back.fields.size)
        assertEquals(m.coveredArea + m.overlapArea, back.fields.sumOf { it.geometry.area }, 0.01 * m.coveredArea)
        // a cap on the number of shapes coarsens the drawing instead
        val big = CoverageMap(lat0, lon0, 0.25)
        for (k in 0 until 40) drive(big, line(0.0, k * 3.0, 50.0 + k, k * 3.0 + 1.0), 1.0, k * 100.0)
        assertTrue(big.shapes(maxRects = 50).let { it.covered.size + it.overlap.size } <= 50)
        // save and load
        val f = File(Files.createTempDirectory("cov").toFile(), "coverage.bin")
        m.save(f)
        val l = CoverageMap.load(f)!!
        assertEquals(m.coveredCells, l.coveredCells); assertEquals(m.overlapCells, l.overlapCells)
        assertEquals(m.cell, l.cell, 0.0); assertEquals(m.originLat, l.originLat, 0.0)
        for (cx in -10..210 step 7) for (cy in -10..20 step 3) assertEquals(m.count(cx, cy), l.count(cx, cy))
    }

    @Test fun guidanceRecordsCoverageInTheField() {
        val dir = Files.createTempDirectory("guide").toFile()
        val a = Geo.fromEnu(lat0, lon0, 0.0, 0.0); val b = Geo.fromEnu(lat0, lon0, 100.0, 0.0)
        val ab = AbLine("AB", a.first, a.second, b.first, b.second, 6.0)
        val field = Field("North", listOf(-5.0 to -5.0, 105.0 to -5.0, 105.0 to 20.0, -5.0 to 20.0).map { (e, n) ->
            Geo.fromEnu(lat0, lon0, e, n).let { Vertex(it.first, it.second) } }, 0L, com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED, "")
        val g = GuidanceSession(File(dir, "g.csv")); g.fields = { listOf(field) }
        g.activate(ab)
        var ms = 1_000_000L
        for (i in 0..100) { val p = Geo.fromEnu(lat0, lon0, i * 1.0, 0.0); g.onFix(p.first, p.second, 90.0, ms); ms += 200 }
        val st = g.state.value
        assertEquals("North", st.field)
        assertEquals(600.0, st.worked, 6.0)
        assertEquals(600.0 / (110 * 25), st.fieldShare!!, 0.01)
        // paused: nothing more is painted
        g.setRecording(false)
        for (i in 0..50) { val p = Geo.fromEnu(lat0, lon0, 100.0 - i, 6.0); g.onFix(p.first, p.second, 270.0, ms); ms += 200 }
        assertEquals(st.worked, g.state.value.worked, 0.0)
        // kept after a restart, cleared on request
        g.activate(null)
        val g2 = GuidanceSession(File(dir, "g.csv")); g2.fields = { listOf(field) }; g2.activate(ab)
        assertEquals(st.worked, g2.state.value.worked, 0.0)
        g2.resetWorked()
        assertEquals(0.0, g2.state.value.worked, 0.0)
        assertTrue(!File(dir, "coverage.bin").exists())
        dir.deleteRecursively()
    }
}
