package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.link.PhoneRtkLink
import com.rfsat.pocketgnss.protocol.Datum
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ITRF2020 -> ETRF2000 (EPSG 10586) against PROJ 9.8.1 (pyproj 3.8.0, `TransformerGroup("EPSG:9988", "EPSG:7930")`). */
class DatumTest {
    private class Case(val x: Double, val y: Double, val z: Double, val epoch: Double, val ex: Double, val ey: Double, val ez: Double)

    private val cases = listOf(
        Case(4608306.6568, 2025783.7105, 3903786.7125, 2026.8, 4608307.3728, 2025783.0427, 3903786.2404), // Athens
        Case(3792779.4645, -416046.8246, 5093929.9634, 2026.8, 3792779.9300, -416047.4003, 5093929.5325), // Dublin
        Case(4919625.1583, -791519.3367, 3968162.8784, 2021.25, 4919625.4332, -791519.9467, 3968162.4103), // Lisbon
        Case(2884097.1774, 1341201.4494, 5509940.3031, 2015.0, 2884097.7119, 1341201.1600, 5509940.0688), // Helsinki
        Case(4469156.3701, 1853013.1339, 4142311.0041, 2030.0, 4469157.1380, 1853012.4227, 4142310.5071), // Pella
    )

    @Test fun matchesProj() {
        for (c in cases) {
            val r = Datum.itrf2020ToEtrf2000(doubleArrayOf(c.x, c.y, c.z), c.epoch)
            assertEquals(c.ex, r[0], 2e-4); assertEquals(c.ey, r[1], 2e-4); assertEquals(c.ez, r[2], 2e-4)
        }
    }

    @Test fun decimalYearAndArea() {
        // 17 Aug 2023 02:30 GPST = week 2275, Thursday
        assertEquals(2023.6249, Datum.decimalYear(2275, 4 * 86400.0 + 9000.0), 1e-4)
        assertTrue(Datum.inEtrfArea(40.76, 22.52))     // Pella
        assertTrue(Datum.inEtrfArea(53.35, -6.26))     // Dublin
        assertFalse(Datum.inEtrfArea(-33.9, 151.2))    // Sydney: stays ITRF2020
        assertFalse(Datum.inEtrfArea(40.7, -74.0))     // New York
    }

    @Test fun pppFixesAreEtrs89InEurope() {
        val g = Geo.ecefToGeodetic(4608306.6568, 2025783.7105, 3903786.7125)   // Athens, ITRF2020
        val week = 2440; val tow = 3 * 86400.0
        fun sol(st: RtkStatus) = RtkSolution(st, week, tow, g.latitude, g.longitude, g.height, 0.1, 0.1, 0.2, 0.0, 0.0, 0.0, 12, 0.0, 1.0, 0.0, 0.0)
        val base = RtkBase(false, 0.0, 0.0, 0.0, 0, 0, 0, 0.0, 0, 0, 0L)
        val ppp = PhoneRtkLink.fixOf(sol(RtkStatus.PPP), base)
        val want = Datum.itrf2020ToEtrf2000(g.latitude, g.longitude, g.height, Datum.decimalYear(week, tow))
        assertEquals(want.latitude, ppp.latitude, 1e-10); assertEquals(want.longitude, ppp.longitude, 1e-10)
        // about 1 m between the frames in 2026, mostly to the south-west
        val shift = Geo.distance(g.latitude, g.longitude, ppp.latitude, ppp.longitude)
        assertTrue("shift $shift m", shift in 0.6..1.3)
        // RTK keeps the frame of its base station
        val rtk = PhoneRtkLink.fixOf(sol(RtkStatus.FLOAT), base)
        assertEquals(g.latitude, rtk.latitude, 0.0); assertEquals(g.longitude, rtk.longitude, 0.0)
    }
}
