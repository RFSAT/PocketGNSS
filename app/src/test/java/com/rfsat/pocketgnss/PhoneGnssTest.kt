package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.link.PhoneFix
import com.rfsat.pocketgnss.link.PhoneNmea
import com.rfsat.pocketgnss.link.PhoneSat
import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Nmea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGnssTest {
    private val L1 = 1575.42e6f; private val L5 = 1176.45e6f
    private val sats = listOf(
        PhoneSat(PhoneNmea.GPS, 10, 41f, 66f, 172f, true, L1), PhoneSat(PhoneNmea.GPS, 10, 37f, 66f, 172f, true, L5),
        PhoneSat(PhoneNmea.GPS, 15, 30f, 12f, 52f, false, L1),
        PhoneSat(PhoneNmea.SBAS, 123, 35f, 35f, 160f, false, L1),
        PhoneSat(PhoneNmea.GLONASS, 5, 33f, 38f, 61f, true, 1602.5e6f),
        PhoneSat(PhoneNmea.GALILEO, 25, 40f, 23f, 180f, true, L1), PhoneSat(PhoneNmea.GALILEO, 25, 38f, 23f, 180f, true, L5),
        PhoneSat(PhoneNmea.BEIDOU, 28, 39f, 37f, 83f, true, 1561.098e6f),
        PhoneSat(PhoneNmea.QZSS, 194, 28f, 20f, 140f, false, L1),
    )

    @Test fun phoneEpochParsesAsAutonomousFixWithItsAccuracy() {
        val fix = PhoneFix(1_792_500_000_000, 37.9302, 23.7554, 220.5, 182.0, 3.0f, 5.0f, 1.2f, 90f)
        val lines = PhoneNmea.epoch(fix, sats)
        lines.forEach { assertTrue(it, Nmea.checksumOk(it)) }
        val m = GnssModel()
        val f = lines.mapNotNull { Nmea.parse(it)?.let(m::accept) }.single()
        assertEquals(FixQuality.SINGLE, f.quality)
        assertEquals(37.9302, f.latitude, 1e-7); assertEquals(23.7554, f.longitude, 1e-7)
        assertEquals(182.0, f.altitudeMsl!!, 1e-3); assertEquals(220.5, f.ellipsoidHeight!!, 1e-3)
        // A late GST is used (same epoch here): Android's 3 m accuracy comes through.
        val m2 = GnssModel(); lines.forEach { Nmea.parse(it)?.let(m2::accept) }
        val f2 = lines.mapNotNull { Nmea.parse(it)?.let(m2::accept) }.single()
        assertEquals(3.0, f2.horizontalAccuracy, 0.01)
        assertEquals(1.2, f.speedMs!!, 0.01)
        val s = m2.satellites()
        assertTrue(s.first { it.label == "G10" }.dualFrequency)
        assertTrue(s.first { it.label == "E25" }.dualFrequency)
        assertTrue(s.any { it.label == "S123" }); assertTrue(s.any { it.label == "R69" }); assertTrue(s.any { it.label == "J2" })
        assertTrue(s.first { it.label == "C28" }.used)
        assertEquals(Constellation.QZSS, s.first { it.label == "J2" }.constellation)
        // DOP from the used satellites' geometry, in this epoch's fix (GSA comes before GGA)
        val d = PhoneNmea.dops(listOf(172.0 to 66.0, 61.0 to 38.0, 180.0 to 23.0, 83.0 to 37.0))!!
        assertEquals(d.second, f.hdop!!, 0.01); assertEquals(d.third, f.vdop!!, 0.01); assertEquals(d.first, f.pdop!!, 0.01)
    }

    @Test fun dopFromGeometry() {
        // Zenith + three at 0° elevation, 120° apart: HDOP = VDOP-free textbook case checked against the normal equations
        val good = PhoneNmea.dops(listOf(0.0 to 90.0, 0.0 to 10.0, 120.0 to 10.0, 240.0 to 10.0, 60.0 to 45.0, 300.0 to 45.0))!!
        assertTrue(good.second in 0.6..1.5); assertTrue(good.third in 0.8..3.0)
        assertEquals(good.first * good.first, good.second * good.second + good.third * good.third, 1e-9)
        // all satellites in one direction: much worse
        val bad = PhoneNmea.dops(listOf(0.0 to 30.0, 10.0 to 40.0, 20.0 to 35.0, 5.0 to 60.0))
        assertTrue(bad == null || bad.second > 5 * good.second)
        assertNull(PhoneNmea.dops(listOf(0.0 to 90.0, 0.0 to 10.0, 120.0 to 10.0)))
    }

    @Test fun searchingEpochHasSatellitesButNoFix() {
        val m = GnssModel()
        val fixes = PhoneNmea.epoch(null, sats, 1_792_500_000_000).mapNotNull { Nmea.parse(it)?.let(m::accept) }
        assertTrue(fixes.isEmpty())
        assertEquals(7, m.satellites().size) // G10 and E25 report L1 + L5: one satellite each
        assertNull(PhoneNmea.nmeaId(PhoneSat(PhoneNmea.GLONASS, 100, 30f, 10f, 10f, false, 0f)))
    }
}
