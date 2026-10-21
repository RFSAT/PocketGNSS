package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.data.GeoidGrid
import com.rfsat.pocketgnss.data.GeoidStore
import com.rfsat.pocketgnss.data.HeightModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** The geoid GeoTIFF reader and the height models against PROJ (resources/geoid/README.md). */
class GeoidTest {
    private fun resource(name: String): File {
        val f = File(Files.createTempDirectory("geoid").toFile(), name)
        f.writeBytes(javaClass.getResourceAsStream("/geoid/$name")!!.readBytes()); return f
    }

    /** grid name -> (lat, lon, N) computed by PROJ */
    private val reference: Map<String, List<Triple<Double, Double, Double>>> =
        String(javaClass.getResourceAsStream("/geoid/refpoints.txt")!!.readBytes()).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ') }.groupBy({ it[0] }, { Triple(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) })

    private fun check(grid: GeoidGrid, name: String) {
        val pts = reference.getValue(name)
        assertTrue(pts.size >= 6)
        for ((lat, lon, n) in pts) {
            val v = grid.value(lat, lon)
            org.junit.Assert.assertNotNull("$name $lat $lon: no value", v)
            assertEquals("$name $lat $lon", n, v!!, 1e-4)
        }
    }

    @Test fun matchesProjStripsTilesNoDataAndGlobal() {
        for (name in listOf("at_bev_GEOID_GRS80_Oesterreich", "be_ign_hBG18", "lv_lgia_lv14", "us_nga_egm96_15"))
            GeoidGrid(resource("$name.tif")).use { check(it, name) }
        // exactly on a no-data node (corner of the Latvian grid), outside a national grid: no value
        GeoidGrid(resource("lv_lgia_lv14.tif")).use { g -> assertNull(g.value(58.10014, 20.90014)); assertNull(g.value(52.0, 21.0)) }
        GeoidGrid(resource("at_bev_GEOID_GRS80_Oesterreich.tif")).use { g -> assertNull(g.value(40.0, 15.0)) }
        // global grid: across the date line and at the poles (PROJ values)
        GeoidGrid(resource("us_nga_egm96_15.tif")).use { g ->
            assertEquals(12.56016, g.value(10.3, 179.9)!!, 1e-4)
            assertEquals(12.43121, g.value(10.3, -179.95)!!, 1e-4)
            assertEquals(-29.56667, g.value(-89.9, 20.0)!!, 1e-4)
            assertEquals(13.66296, g.value(89.95, -100.0)!!, 1e-4)
        }
    }

    /** All 23 models, when their grids are available locally (-Dgeoid.dir=...). */
    @Test fun allModelsMatchProj() {
        val dir = System.getProperty("geoid.dir")?.let { File(it) }?.takeIf { it.isDirectory } ?: return
        var n = 0
        for (m in HeightModels.all) {
            val f = File(dir, m.file)
            assertEquals(m.file, m.sizeBytes, f.length())
            assertEquals(m.file, m.sha256, MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) })
            GeoidGrid(f).use { check(it, m.file.removeSuffix(".tif")) }
            n++
        }
        println("checked $n height models against PROJ")
        assertEquals(HeightModels.all.size, n)
    }

    @Test fun catalogueAndSuggestions() {
        // bundled grids are byte-identical to the catalogue entries the app downloads
        for (name in listOf("at_bev_GEOID_GRS80_Oesterreich", "be_ign_hBG18", "lv_lgia_lv14", "us_nga_egm96_15")) {
            val m = HeightModels.all.first { it.file == "$name.tif" }
            val b = javaClass.getResourceAsStream("/geoid/$name.tif")!!.readBytes()
            assertEquals(m.sizeBytes, b.size.toLong())
            assertEquals(m.sha256, MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) })
        }
        assertEquals(HeightModels.all.size, HeightModels.all.map { it.id }.toSet().size)
        assertTrue(HeightModels.all.all { it.url.startsWith("https://cdn.proj.org/") })
        assertEquals("DE", HeightModels.suggest(52.52, 13.40).first().id)        // Berlin
        assertEquals("PL", HeightModels.suggest(52.23, 21.01).first().id)        // Warsaw
        assertEquals("IE", HeightModels.suggest(53.35, -6.26).first().id)        // Dublin
        assertEquals("NI", HeightModels.suggest(54.60, -5.93).first().id)        // Belfast
        assertEquals("FR", HeightModels.suggest(45.76, 4.84).first().id)         // Lyon
        assertEquals("EGM2008", HeightModels.suggest(40.76, 22.52).first().id)   // Pella: no open Greek model
        assertEquals("EGM2008", HeightModels.suggest(-33.9, 151.2).first().id)   // Sydney
    }

    @Test fun storeHeights() {
        val dir = Files.createTempDirectory("store").toFile()
        val m = HeightModels.byId("AT")!!
        val store = GeoidStore(dir)
        assertNull(store.height(m, 47.8, 12.3, 500.0))     // not downloaded
        resource(m.file).copyTo(File(dir, m.file))
        val (lat, lon, n) = reference.getValue("at_bev_GEOID_GRS80_Oesterreich")[0]
        assertEquals(500.0 - n, store.height(m, lat, lon, 500.0)!!, 1e-4)
        assertNull(store.height(m, lat, lon, Double.NaN))
        store.delete(m)
        assertNull(store.height(m, lat, lon, 500.0))
    }

    @Test fun nationalHeightsInExports() {
        val dir = Files.createTempDirectory("exp").toFile()
        val m = HeightModels.byId("AT")!!
        val store = GeoidStore(dir); resource(m.file).copyTo(File(dir, m.file))
        val (lat, lon, n) = reference.getValue("at_bev_GEOID_GRS80_Oesterreich")[1]
        val h = com.rfsat.pocketgnss.data.NationalHeights(m.system) { a, b, e -> store.height(m, a, b, e) }
        val p = com.rfsat.pocketgnss.data.SurveyPoint("P1", lat, lon, 600.0, 552.0, 0.01, 0.01, 0.02, 10,
            com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED, 1_760_000_000_000L)
        val csv = com.rfsat.pocketgnss.data.Exporters.pointsCsv(listOf(p), heights = h).lines()
        assertTrue(csv[0].endsWith(",height_system,h_national_m"))
        assertTrue(csv[1], csv[1].endsWith(",\"EVRF2000 Austria\",%.4f".format(java.util.Locale.US, 600.0 - n)))
        // without a model the columns are not there
        assertTrue(com.rfsat.pocketgnss.data.Exporters.pointsCsv(listOf(p)).lines()[0].endsWith(",photo"))
        val gj = com.rfsat.pocketgnss.data.Exporters.pointsGeoJson(listOf(p), h)
        assertTrue(gj, gj.contains("\"height_system\":\"EVRF2000 Austria\",\"h_national\":%.4f".format(java.util.Locale.US, 600.0 - n)))
        val shp = java.util.zip.ZipInputStream(com.rfsat.pocketgnss.data.Exporters.pointsShapefile(listOf(p), heights = h).inputStream())
        var dbf: ByteArray? = null
        while (true) { val e = shp.nextEntry ?: break; if (e.name.endsWith(".dbf")) dbf = shp.readBytes() }
        val text = String(dbf!!, Charsets.ISO_8859_1)
        assertTrue(text.contains("H_SYSTEM") && text.contains("H_NAT") && text.contains("EVRF2000 Austria"))
        assertTrue(text.contains("%.4f".format(java.util.Locale.US, 600.0 - n)))
        // JSON stream for the user's own apps
        val f = com.rfsat.pocketgnss.core.GnssFix(0L, null, null, lat, lon, 552.0, 48.0, com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED,
            12, 0.8, null, null, null, null, null, null, null, null, null, null)
        val js = com.rfsat.pocketgnss.core.PositionJson.of(f, null, null, m.system to store.height(m, lat, lon, f.ellipsoidHeight))
        assertTrue(js, js.contains("\"height\":{\"system\":\"EVRF2000 Austria\",\"h\":%.3f}".format(java.util.Locale.US, 600.0 - n)))
    }
}
