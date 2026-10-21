package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.CaptureMode
import com.rfsat.pocketgnss.core.FieldCapture
import com.rfsat.pocketgnss.data.Exporters
import com.rfsat.pocketgnss.data.Field
import com.rfsat.pocketgnss.data.FieldGeometry
import com.rfsat.pocketgnss.data.FieldStore
import com.rfsat.pocketgnss.data.ImportIssue
import com.rfsat.pocketgnss.data.Importer
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.data.simplify
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Grid
import com.rfsat.pocketgnss.protocol.GridRef
import com.rfsat.pocketgnss.protocol.GridSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FieldTest {
    private val irregular = listOf(52.2301 to 21.0102, 52.2312 to 21.0121, 52.2305 to 21.0150, 52.2290 to 21.0139, 52.2288 to 21.0110).map { Vertex(it.first, it.second) }
    private val big = listOf(40.70 to 22.50, 40.72 to 22.50, 40.72 to 22.53, 40.70 to 22.53).map { Vertex(it.first, it.second) }

    /** Geodesic area and perimeter on GRS80 from PROJ (pyproj Geod.polygon_area_perimeter). */
    @Test fun areaAndPerimeterMatchGeodesic() {
        val g = FieldGeometry.of(irregular)
        assertEquals(57818.592, g.area, 0.05)
        assertEquals(928.400, g.perimeter, 0.01)
        assertFalse(g.selfIntersecting)
        val b = FieldGeometry.of(big)
        assertEquals(5630340.16, b.area, 5630340.16 * 2e-6)
        assertEquals(9512.104, b.perimeter, 9512.104 * 2e-6)
        assertEquals(40.71, b.centroidLat, 1e-4); assertEquals(22.515, b.centroidLon, 1e-4)
    }

    @Test fun selfIntersectionAndSimplify() {
        val bow = listOf(Vertex(50.0, 10.0), Vertex(50.001, 10.001), Vertex(50.0, 10.001), Vertex(50.001, 10.0))
        assertTrue(FieldGeometry.of(bow).selfIntersecting)
        // Collinear points along an edge go; the corners stay.
        val line = (0..10).map { Vertex(50.0, 10.0 + it * 0.0001) } + Vertex(50.001, 10.001)
        val s = simplify(line, 0.02)
        assertEquals(3, s.size)
    }

    @Test fun walkCaptureClosesAndMeasures() {
        val c = FieldCapture()
        c.start(CaptureMode.WALK, FixQuality.RTK_FIXED, 1.0, 0)
        // A 100 m x 50 m rectangle near 52°N, walked at 0.25 m steps, with float epochs that must be skipped.
        val lat0 = 52.0; val lon0 = 20.0
        val dLat = 1 / 111_250.0; val dLon = 1 / (111_320.0 * Math.cos(Math.toRadians(52.0)))
        val path = ArrayList<Pair<Double, Double>>()
        for (i in 0..400) path.add(0.0 to i * 0.25)
        for (i in 1..200) path.add(i * 0.25 to 100.0)
        for (i in 1..400) path.add(50.0 to 100.0 - i * 0.25)
        for (i in 1..200) path.add(50.0 - i * 0.25 to 0.0)
        var t = 0L
        path.forEachIndexed { k, (n, e) ->
            c.onFix(lat0 + n * dLat, lon0 + e * dLon, 100.0, if (k % 50 == 7) FixQuality.RTK_FLOAT else FixQuality.RTK_FIXED, t); t += 200
        }
        assertTrue(c.state.value.rejected > 0)
        val (v, worst) = c.finish()
        assertEquals(FixQuality.RTK_FIXED, worst)
        assertTrue("corners kept: ${v.size}", v.size in 4..8)
        val g = FieldGeometry.of(v)
        assertEquals(5000.0, g.area, 30.0)
        assertEquals(300.0, g.perimeter, 1.5)
    }

    @Test fun cornerAveraging() {
        val c = FieldCapture()
        // Without averaging a corner needs a recent usable fix.
        c.start(CaptureMode.CORNERS, FixQuality.RTK_FLOAT, 1.0, 0)
        assertFalse(c.addCorner(0))
        c.onFix(50.0, 10.0, 1.0, FixQuality.RTK_FIXED, 100)
        assertTrue(c.addCorner(500)); assertEquals(1, c.state.value.vertices.size)
        c.cancel()
        c.start(CaptureMode.CORNERS, FixQuality.RTK_FLOAT, 1.0, 2)
        assertTrue(c.addCorner(1000))
        c.onFix(50.0, 10.0, 1.0, FixQuality.RTK_FIXED, 1500)
        c.onFix(50.00002, 10.00002, 3.0, FixQuality.RTK_FLOAT, 2500)
        c.onFix(50.0, 10.0, 2.0, FixQuality.SINGLE, 2800) // rejected
        assertEquals(2, c.state.value.averaging)
        c.onFix(50.00001, 10.00001, 2.0, FixQuality.RTK_FIXED, 3100)
        val v = c.state.value.vertices.single()
        assertEquals(50.00001, v.latitude, 1e-9); assertEquals(2.0, v.height, 1e-9)
        assertEquals(FixQuality.RTK_FLOAT, c.state.value.worst)
        assertNull(c.state.value.averaging)
    }

    @Test fun storeRoundTrip() {
        val dir = Files.createTempDirectory("fields").toFile()
        val st = FieldStore(File(dir, "fields.csv"))
        st.add(Field("A;1", irregular, 5, FixQuality.RTK_FIXED, "note|x"))
        st.add(Field("B", big, 6))
        val l = st.load()
        assertEquals(2, l.size); assertEquals("A,1", l[0].name); assertEquals(5, l[0].vertices.size)
        assertEquals(57818.592, l[0].geometry.area, 0.05)
        st.rename(l[1], "Big"); st.remove(l[0])
        assertEquals(listOf("Big"), st.load().map { it.name })
        dir.deleteRecursively()
    }

    @Test fun gridInverseRoundTrips() {
        val pts = mapOf(
            GridSystem.IE_ITM to (53.35 to -6.26), GridSystem.FR_LAMBERT93 to (48.8566 to 2.3522), GridSystem.PL_CS92 to (52.23 to 21.01),
            GridSystem.PT_TM06 to (38.72 to -9.14), GridSystem.BE_LAMBERT2008 to (50.85 to 4.35), GridSystem.SE_SWEREF99TM to (59.33 to 18.07),
            GridSystem.FI_TM35FIN to (60.17 to 24.94), GridSystem.EE_LEST97 to (59.44 to 24.75), GridSystem.LV_LKS92 to (56.95 to 24.11),
            GridSystem.LT_LKS94 to (54.69 to 25.28), GridSystem.HR_HTRS96TM to (45.81 to 15.98), GridSystem.SI_D96TM to (46.05 to 14.51),
            GridSystem.LAEA_EU to (38.0 to 23.7), GridSystem.GR_TM07 to (37.97 to 23.73), GridSystem.GR_EGSA87 to (40.76 to 22.52),
        )
        for ((sys, p) in pts) {
            val g = Grid.of(sys, p.first, p.second, 0.0)!!
            val back = Grid.inverse(sys, g.easting, g.northing)!!
            assertEquals(sys.name, p.first, back.first, 1e-9); assertEquals(sys.name, p.second, back.second, 1e-9)
        }
        val u = Grid.utm(-33.87, 151.21)
        val b = Grid.inverse(GridSystem.UTM, u.easting, u.northing, 56, south = true)!!
        assertEquals(-33.87, b.first, 1e-9); assertEquals(151.21, b.second, 1e-9)
        assertNull(Grid.inverse(GridSystem.UTM, u.easting, u.northing, 0))
    }

    @Test fun crsRecognition() {
        assertEquals(GridRef(GridSystem.UTM, 34), Grid.fromEpsg(25834))
        assertEquals(GridRef(GridSystem.UTM, 56, true), Grid.fromEpsg(32756))
        assertEquals(GridRef(GridSystem.PL_CS92), Grid.fromEpsg(2180))
        assertEquals(GridRef(GridSystem.NONE), Grid.fromEpsg(4326))
        assertEquals(GridRef(GridSystem.UTM, 33), Grid.fromWkt("PROJCS[\"ETRS_1989_UTM_Zone_33N\",GEOGCS[\"GCS_ETRS_1989\"]]"))
        assertEquals(GridRef(GridSystem.UTM, 23, true), Grid.fromWkt("PROJCS[\"WGS 84 / UTM zone 23S\",GEOGCS[\"WGS 84\"],AUTHORITY[\"EPSG\",\"32723\"]]"))
        assertEquals(GridRef(GridSystem.FR_LAMBERT93), Grid.fromWkt("PROJCS[\"RGF_1993_Lambert_93\",GEOGCS[\"GCS_RGF_1993\"]]"))
        assertEquals(GridRef(GridSystem.NONE), Grid.fromWkt("GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\"]]"))
        assertNull(Grid.fromWkt("PROJCS[\"Amersfoort / RD New\"]"))
    }

    private fun imp(text: String, grid: GridRef? = null) = Importer(grid, 1000, "test").read(text.toByteArray())

    @Test fun importCsv() {
        val r = imp("name;latitude;longitude;h_msl_m\nA;52,2301;21,0102;101,5\nB;52.2312;21.0121;\nbad;x;y;\n")
        assertEquals(2, r.points.size); assertEquals(1, r.skipped); assertEquals(ImportIssue.SKIPPED, r.issue)
        assertEquals("A", r.points[0].name); assertEquals(52.2301, r.points[0].latitude, 1e-12); assertEquals(101.5, r.points[0].heightMsl!!, 1e-9)
        assertTrue(r.points[0].heightEllipsoid.isNaN())
        // Our own export reads back.
        val p = SurveyPoint("P1", 52.23, 21.01, 120.0, 90.0, 0.01, 0.01, 0.02, 60, FixQuality.RTK_FIXED, 0L)
        val r2 = imp(Exporters.pointsCsv(listOf(p), GridSystem.PL_CS92))
        assertEquals(52.23, r2.points.single().latitude, 1e-9); assertEquals(120.0, r2.points.single().heightEllipsoid, 1e-9)
        // Grid coordinates without a grid: asks for one; with PL-1992 (and in northing-first order) they convert.
        assertEquals(ImportIssue.NO_GRID, imp("P1,637231.090,486786.394,95.0").issue)
        val g = imp("P1,637231.090,486786.394,95.0", GridRef(GridSystem.PL_CS92))
        assertEquals(52.23, g.points.single().latitude, 1e-8); assertEquals(21.01, g.points.single().longitude, 1e-8)
        // Northing first is recognised where the two cannot be confused (SWEREF 99 TM)
        val sw = imp("S,6580824.576,674647.882", GridRef(GridSystem.SE_SWEREF99TM))
        assertEquals(59.33, sw.points.single().latitude, 1e-8); assertEquals(18.07, sw.points.single().longitude, 1e-8)
        // x / y headers in degrees are longitude / latitude
        val xy = imp("id,x,y\n7,21.01,52.23")
        assertEquals("7", xy.points.single().name); assertEquals(52.23, xy.points.single().latitude, 1e-12)
    }

    @Test fun importGeoJsonKmlGpx() {
        val f = Field("Plot 1", irregular, 0)
        val gj = imp(Exporters.fieldsGeoJson(listOf(f)))
        assertEquals("Plot 1", gj.fields.single().name)
        assertEquals(57818.592, gj.fields.single().geometry.area, 0.05)
        val kml = imp(Exporters.fieldsKml(listOf(f)) )
        assertEquals(5, kml.fields.single().vertices.size)
        val pk = imp(Exporters.pointsKml(listOf(SurveyPoint("Ś1", 50.0, 10.0, 200.0, 150.0, 0.0, 0.0, 0.0, 1, FixQuality.RTK_FIXED, 0))))
        assertEquals("Ś1", pk.points.single().name); assertEquals(150.0, pk.points.single().heightMsl!!, 1e-9)
        val crs = imp("""{"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::2180"}},
            "features":[{"type":"Feature","properties":{"NAME":"S"},"geometry":{"type":"Point","coordinates":[637231.090,486786.394]}}]}""")
        assertEquals("S", crs.points.single().name); assertEquals(52.23, crs.points.single().latitude, 1e-8)
        val gpx = imp("""<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><wpt lat="45.1" lon="7.2"><ele>300</ele><name>W</name></wpt></gpx>""")
        assertEquals("W", gpx.points.single().name); assertEquals(300.0, gpx.points.single().heightMsl!!, 1e-9)
        assertEquals(ImportIssue.NOTHING_FOUND, imp("{\"type\":\"FeatureCollection\",\"features\":[]}").issue)
        assertEquals(ImportIssue.UNKNOWN_FORMAT, imp("<html></html>").issue)
    }

    /** A parcel exported by pyshp in PL-1992 with an ESRI .prj and a hole: the outer ring comes back, in ETRS89. */
    @Test fun importShapefileProjected() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("parcel_pl1992_shp.zip")!!.readBytes()
        val r = Importer(null, 0, "parcel").read(bytes)
        assertNull(r.issue)
        val f = r.fields.single()
        assertEquals("Działka 12/3", f.name)
        assertEquals(5, f.vertices.size)
        assertEquals(57818.592, f.geometry.area, 0.1)
        assertTrue(f.vertices.any { kotlin.math.abs(it.latitude - 52.2301) < 1e-8 && kotlin.math.abs(it.longitude - 21.0102) < 1e-8 })
    }

    @Test fun shapefileWriterRoundTrip() {
        val fields = listOf(Field("Pole Ł", irregular, 0, FixQuality.RTK_FIXED), Field("Big", big.reversed(), 0))
        val zip = Exporters.fieldsShapefile(fields)
        File("build/tmp").mkdirs(); File("build/tmp/fields_shp.zip").writeBytes(zip)
        val back = Importer(null, 0, "x").read(zip)
        assertEquals(listOf("Pole Ł", "Big"), back.fields.map { it.name })
        assertEquals(57818.592, back.fields[0].geometry.area, 0.05)
        assertEquals(5630340.16, back.fields[1].geometry.area, 20.0)
        val pts = Exporters.pointsShapefile(listOf(SurveyPoint("A", 50.0, 10.0, Double.NaN, 99.0, 0.0, 0.0, 0.0, 0, FixQuality.NONE, 0)), GridSystem.UTM)
        File("build/tmp/points_shp.zip").writeBytes(pts)
        val pb = Importer(null, 0, "x").read(pts)
        assertEquals(50.0, pb.points.single().latitude, 1e-12)
        assertNotNull(pb.points.single().name)
    }

    @Test fun pointAttributesRoundTrip() {
        val dir = Files.createTempDirectory("pts").toFile()
        val p = SurveyPoint("T1", 50.0, 10.0, 200.0, 150.0, 0.01, 0.01, 0.02, 30, FixQuality.RTK_FIXED, 7, "leaning; old", "tree", "T1_7.jpg")
        val st = com.rfsat.pocketgnss.data.PointStore(File(dir, "p.csv"))
        st.add(p)
        val back = st.load().single()
        assertEquals("tree", back.code); assertEquals("T1_7.jpg", back.photo); assertEquals("leaning, old", back.note)
        val r = imp(Exporters.pointsCsv(listOf(p)))
        assertEquals("tree", r.points.single().code); assertEquals("leaning; old", r.points.single().note)
        val g = imp(Exporters.pointsGeoJson(listOf(p)))
        assertEquals("tree", g.points.single().code)
        File(dir, "T1_7.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val kmz = Exporters.pointsKmz(listOf(p), dir)
        val names = java.util.zip.ZipInputStream(kmz.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("doc.kml", "files/T1_7.jpg"), names)
        dir.deleteRecursively()
    }
}
