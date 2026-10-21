package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.protocol.Grid
import com.rfsat.pocketgnss.protocol.GridSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values from PROJ 9 (pyproj): EU national grids from ETRS89 (EPSG:4258), UTM, and EPSG:2100 with its 3-parameter shift. */
class GridTest {
    private data class P(val lat: Double, val lon: Double, val h: Double, val egsaE: Double, val egsaN: Double, val utmE: Double, val utmN: Double, val zone: Int)
    private val pts = listOf(
        P(37.9715, 23.7267, 150.0, 475846.009, 4202400.196, 739511.762, 4206160.915, 34), // Athens
        P(40.7600, 22.5200, 50.0, 374926.805, 4512882.779, 628299.148, 4513226.590, 34),  // Pella
        P(35.3387, 25.1442, 30.0, 603831.136, 3910916.120, 331346.100, 3912184.811, 35),  // Heraklion
        P(39.6650, 20.8537, 500.0, 229948.327, 4395022.267, 487451.062, 4390586.799, 34), // Ioannina
    )

    @Test fun utmMatchesProj() {
        for (p in pts) {
            val g = Grid.utm(p.lat, p.lon)
            assertEquals("ETRS89 / UTM ${p.zone}N", g.label)
            assertEquals(p.utmE, g.easting, 0.002); assertEquals(p.utmN, g.northing, 0.002)
        }
    }

    @Test fun egsa87MatchesProj() {
        for (p in pts) {
            val g = Grid.egsa87(p.lat, p.lon, p.h)
            assertEquals(p.egsaE, g.easting, 0.005); assertEquals(p.egsaN, g.northing, 0.005)
        }
    }

    private data class N(val sys: GridSystem, val lat: Double, val lon: Double, val e: Double, val n: Double)
    private val national = listOf(
        N(GridSystem.IE_ITM, 53.35, -6.26, 715845.936, 734720.331),            // Dublin, EPSG:2157
        N(GridSystem.FR_LAMBERT93, 48.8566, 2.3522, 652469.023, 6862035.259),  // Paris, EPSG:2154
        N(GridSystem.PL_CS92, 52.23, 21.01, 637231.090, 486786.394),           // Warsaw, EPSG:2180
        N(GridSystem.PT_TM06, 38.72, -9.14, -87567.123, -104793.555),          // Lisbon, EPSG:3763
        N(GridSystem.BE_LAMBERT2008, 50.85, 4.35, 648679.018, 671067.056),     // Brussels, EPSG:3812
        N(GridSystem.SE_SWEREF99TM, 59.33, 18.07, 674647.882, 6580824.576),    // Stockholm, EPSG:3006
        N(GridSystem.FI_TM35FIN, 60.17, 24.94, 385700.421, 6672126.743),       // Helsinki, EPSG:3067
        N(GridSystem.EE_LEST97, 59.44, 24.75, 542555.357, 6589368.181),        // Tallinn, EPSG:3301
        N(GridSystem.LV_LKS92, 56.95, 24.11, 506691.224, 311825.560),          // Riga, EPSG:3059
        N(GridSystem.LT_LKS94, 54.69, 25.28, 582524.028, 6062260.637),         // Vilnius, EPSG:3346
        N(GridSystem.HR_HTRS96TM, 45.81, 15.98, 459585.490, 5074591.534),      // Zagreb, EPSG:3765
        N(GridSystem.SI_D96TM, 46.05, 14.51, 462080.996, 101250.088),          // Ljubljana, EPSG:3794
        N(GridSystem.LAEA_EU, 50.0, 10.0, 4321000.000, 2987510.567),           // EPSG:3035
        N(GridSystem.LAEA_EU, 38.0, 23.7, 5525743.241, 1766074.554),           // EPSG:3035, far from the centre
        N(GridSystem.GR_TM07, 37.97, 23.73, 476285.046, 2202520.844),          // Athens, HTRS07 / TM07
    )

    @Test fun europeanGridsMatchProj() {
        for (p in national) {
            val g = Grid.of(p.sys, p.lat, p.lon, 0.0)!!
            assertEquals(p.sys.name, p.e, g.easting, 0.002); assertEquals(p.sys.name, p.n, g.northing, 0.002)
            assertTrue(p.sys.name, p.sys.area!!.contains(p.lat, p.lon))
        }
    }

    @Test fun utmWorldwide() {
        // lat, lon, zone, E, N: EPSG:326xx / 327xx
        val w = listOf(
            doubleArrayOf(-33.87, 151.21, 56.0, 334435.706, 6250816.398), doubleArrayOf(40.71, -74.01, 18.0, 583625.006, 4507036.364),
            doubleArrayOf(-23.55, -46.63, 23.0, 333624.181, 7394647.522), doubleArrayOf(35.68, 139.69, 54.0, 381454.346, 3949247.237),
            doubleArrayOf(64.15, -21.94, 27.0, 454270.465, 7114066.846), doubleArrayOf(28.12, -15.43, 28.0, 457768.022, 3110570.194),
        )
        for (v in w) {
            val g = Grid.of(GridSystem.UTM, v[0], v[1], null)!!
            assertTrue(g.label, g.label.endsWith("UTM ${v[2].toInt()}${if (v[0] < 0) "S" else "N"}"))
            assertEquals(v[3], g.easting, 0.002); assertEquals(v[4], g.northing, 0.002)
        }
        assertTrue(Grid.utm(-33.87, 151.21).label.startsWith("WGS 84"))
        // A fixed zone (Denmark uses zone 32 east of 12°E): EPSG:25832
        val cph = Grid.of(GridSystem.UTM, 55.68, 12.57, null, utmZone = 32)!!
        assertEquals("ETRS89 / UTM 32N", cph.label)
        assertEquals(724436.415, cph.easting, 0.002); assertEquals(6176243.255, cph.northing, 0.002)
        assertEquals(null, Grid.of(GridSystem.UTM, 86.0, 0.0, null))
        assertEquals(null, Grid.of(GridSystem.NONE, 50.0, 10.0, null))
    }

    @Test fun pointsCsvHasGridColumns() {
        val pt = com.rfsat.pocketgnss.data.SurveyPoint("P1", 52.23, 21.01, 120.0, 90.0, 0.01, 0.01, 0.02, 60, com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED, 0L)
        val plain = com.rfsat.pocketgnss.data.Exporters.pointsCsv(listOf(pt)).lines()
        assertEquals(11, plain[0].split(',').size)
        val csv = com.rfsat.pocketgnss.data.Exporters.pointsCsv(listOf(pt), GridSystem.PL_CS92).lines()
        assertTrue(csv[0].endsWith(",grid,easting_m,northing_m"))
        val f = csv[1].split(',')
        assertEquals("\"PL-1992\"", f[11]); assertEquals(637231.090, f[12].toDouble(), 0.002); assertEquals(486786.394, f[13].toDouble(), 0.002)
    }
}
