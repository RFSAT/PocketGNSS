package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.data.CasterProfiles
import com.rfsat.pocketgnss.protocol.Mountpoint
import com.rfsat.pocketgnss.raw.Band
import com.rfsat.pocketgnss.raw.RawAnalyzer
import com.rfsat.pocketgnss.raw.RawClock
import com.rfsat.pocketgnss.raw.RawConvert
import com.rfsat.pocketgnss.raw.RawMeasurement
import com.rfsat.pocketgnss.raw.RawVerdict
import com.rfsat.pocketgnss.raw.Rinex
import com.rfsat.pocketgnss.raw.RinexHeaderInfo
import com.rfsat.pocketgnss.raw.RinexObsWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawTest {
    private val c = RawConvert.C
    private val transit = 70_000_000L // 70 ms
    // GPS week 2440, TOW 100000 s = 12 Oct 2026 03:46:40 GPST.
    private val tGps = 2440 * RawConvert.NS_WEEK + 100_000_000_000_000L
    private val clock = RawClock(timeNanos = 5_000_000_000_000L, fullBiasNanos = 5_000_000_000_000L - tGps, biasNanos = 0.0, leapSecond = 18)

    private fun meas(constellation: Int, svid: Int, received: Long, state: Int, carrier: Double? = 1575.42e6, code: String? = "C",
                     adrState: Int = 0, adr: Double = 0.0) = RawMeasurement(constellation, svid, carrier, code, 0.0, state, received, 20,
        35.0, -500.0, 0.05, adrState, adr, 0.003)

    private val gpsState = RawConvert.STATE_CODE_LOCK or RawConvert.STATE_TOW_DECODED

    @Test fun gpsPseudorange() {
        val m = meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit, gpsState)
        assertEquals(0.07 * c, RawConvert.pseudorange(clock, m)!!, 1e-3)
    }

    @Test fun pseudorangeNeedsTimeOfWeek() {
        assertNull(RawConvert.pseudorange(clock, meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit, RawConvert.STATE_CODE_LOCK)))
        assertNull(RawConvert.pseudorange(clock.copy(fullBiasNanos = null), meas(RawConvert.GPS, 5, 0, gpsState)))
    }

    @Test fun weekRollover() {
        // Satellite transmitted just before the week boundary, phone received just after it.
        val t = 2441 * RawConvert.NS_WEEK + 30_000_000L
        val k = clock.copy(fullBiasNanos = clock.timeNanos - t)
        val m = meas(RawConvert.GPS, 7, RawConvert.NS_WEEK + 30_000_000L - transit - RawConvert.NS_WEEK + RawConvert.NS_WEEK, gpsState)
        assertEquals(0.07 * c, RawConvert.pseudorange(k, m)!!, 1e-3)
    }

    @Test fun glonassBeidouGalileo() {
        val day = RawConvert.NS_DAY
        val glo = Math.floorMod(tGps - 18_000_000_000L + 3 * 3_600_000_000_000L - transit, day)
        val g = meas(RawConvert.GLONASS, 3, glo, RawConvert.STATE_CODE_LOCK or RawConvert.STATE_GLO_TOD_DECODED, carrier = 1602e6 + 2 * 562.5e3)
        assertEquals(0.07 * c, RawConvert.pseudorange(clock, g)!!, 1e-3)
        assertEquals(2, RawConvert.gloChannel(g.carrierHz))
        val bds = Math.floorMod(tGps - 14_000_000_000L - transit, RawConvert.NS_WEEK)
        assertEquals(0.07 * c, RawConvert.pseudorange(clock, meas(RawConvert.BEIDOU, 20, bds, gpsState, carrier = 1561.098e6))!!, 1e-3)
        // Galileo with only the 100 ms secondary code: still unambiguous for a 70 ms transit.
        val gal = Math.floorMod(tGps - transit, 100_000_000L)
        assertEquals(0.07 * c, RawConvert.pseudorange(clock,
            meas(RawConvert.GALILEO, 11, gal, RawConvert.STATE_CODE_LOCK or RawConvert.STATE_GAL_E1C_2ND_CODE_LOCK))!!, 1e-3)
        // Galileo E1 as a Galaxy S24 reports it: its own E1B/C code lock and TOW known, without the generic CODE_LOCK
        val galTow = Math.floorMod(tGps - transit, RawConvert.NS_WEEK)
        val s24 = RawConvert.STATE_GAL_E1BC_CODE_LOCK or RawConvert.STATE_GAL_E1C_2ND_CODE_LOCK or RawConvert.STATE_TOW_KNOWN
        assertEquals(0.07 * c, RawConvert.pseudorange(clock, meas(RawConvert.GALILEO, 25, galTow, s24))!!, 1e-3)
        // ...but no code lock of any kind: no pseudorange
        assertNull(RawConvert.pseudorange(clock, meas(RawConvert.GALILEO, 25, galTow, RawConvert.STATE_TOW_KNOWN)))
    }

    @Test fun signalCodes() {
        assertEquals("1C", RawConvert.rinexCode(meas(RawConvert.GPS, 1, 0, 0)))
        assertEquals("5Q", RawConvert.rinexCode(meas(RawConvert.GPS, 1, 0, 0, carrier = 1176.45e6, code = "Q")))
        assertEquals("5Q", RawConvert.rinexCode(meas(RawConvert.GALILEO, 1, 0, 0, carrier = 1176.45e6, code = null)))
        assertEquals("2I", RawConvert.rinexCode(meas(RawConvert.BEIDOU, 1, 0, 0, carrier = 1561.098e6, code = null)))
        assertEquals("1P", RawConvert.rinexCode(meas(RawConvert.BEIDOU, 1, 0, 0, carrier = 1575.42e6, code = null)))
        assertEquals(1, RawConvert.prn(RawConvert.QZSS, 193))
        assertNull(RawConvert.prn(RawConvert.GLONASS, 101)) // slot unknown (FCN + 100)
    }

    @Test fun phaseDopplerAndSlip() {
        val m = meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit, gpsState,
            adrState = RawConvert.ADR_VALID or RawConvert.ADR_CYCLE_SLIP, adr = 1000.0)
        val e = RawConvert.epoch(clock, listOf(m))!!
        val o = e.obs.single()
        val lambda = c / 1575.42e6
        assertEquals(1000.0 / lambda, o.phase!!, 1e-6)
        assertEquals(500.0 / lambda, o.doppler!!, 1e-6)
        assertEquals(1, o.lli)
        assertEquals("G05", o.sat)
        assertEquals(2440, e.gpsWeek)
        assertEquals(100_000.0, e.towSeconds, 1e-9)
    }

    @Test fun calendar() {
        assertEquals(listOf(1980, 1, 6, 0, 0, 0), Rinex.calendar(0).toList())
        assertEquals(listOf(2026, 10, 12, 3, 46, 40), Rinex.calendar(tGps).toList())
        assertEquals(listOf(2026, 10, 21, 9, 30, 15), Rinex.calendar(1_476_610_215_000_000_000L).toList())
    }

    /** Galileo's system letter "E" used to clash with the temporary file's epoch marker: every RINEX with Galileo was empty. */
    @Test fun rinexWithGalileo() {
        val galTow = Math.floorMod(tGps - transit, RawConvert.NS_WEEK)
        val s24 = RawConvert.STATE_GAL_E1BC_CODE_LOCK or RawConvert.STATE_GAL_E1C_2ND_CODE_LOCK or RawConvert.STATE_TOW_KNOWN
        val ms = listOf(meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit, gpsState),
            meas(RawConvert.GALILEO, 25, galTow, s24, adrState = RawConvert.ADR_VALID, adr = 4321.0))
        val dir = File(System.getProperty("java.io.tmpdir"), "rinex_gal_${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, "raw.obs")
        val w = RinexObsWriter(f, RinexHeaderInfo(markerName = "TEST", createdUtc = "20261023 090000 UTC"))
        repeat(3) { k -> w.add(RawConvert.epoch(clock.copy(timeNanos = clock.timeNanos + k * 1_000_000_000L), ms.map {
            it.copy(receivedSvTimeNanos = it.receivedSvTimeNanos + k * 1_000_000_000L) })!!) }
        assertTrue(w.finish(null))
        val lines = f.readLines()
        assertEquals(3, lines.count { it.startsWith("> ") })
        val e25 = lines.filter { it.startsWith("E25") }
        assertEquals(3, e25.size)
        assertEquals(0.07 * c, e25[0].substring(3, 17).trim().toDouble(), 2e-3)
        dir.deleteRecursively()
    }

    @Test fun rinexFile() {
        val ms = listOf(
            meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit, gpsState, adrState = RawConvert.ADR_VALID, adr = 1234.5),
            meas(RawConvert.GPS, 5, Math.floorMod(tGps, RawConvert.NS_WEEK) - transit - 10, gpsState, carrier = 1176.45e6, code = "Q"),
            meas(RawConvert.GLONASS, 3, Math.floorMod(tGps - 18_000_000_000L + 3 * 3_600_000_000_000L - transit, RawConvert.NS_DAY),
                RawConvert.STATE_CODE_LOCK or RawConvert.STATE_GLO_TOD_DECODED, carrier = 1602e6 - 562.5e3),
        )
        val dir = File(System.getProperty("java.io.tmpdir"), "rinex_test_${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, "raw.obs")
        val w = RinexObsWriter(f, RinexHeaderInfo(markerName = "TEST", createdUtc = "20261021 090000 UTC"))
        w.add(RawConvert.epoch(clock, ms)!!)
        w.add(RawConvert.epoch(clock.copy(timeNanos = clock.timeNanos + 1_000_000_000L), ms.map {
            it.copy(receivedSvTimeNanos = it.receivedSvTimeNanos + 1_000_000_000L) })!!)
        assertTrue(w.finish(doubleArrayOf(4_000_000.0, 2_000_000.0, 4_500_000.0)))
        val lines = f.readLines()
        val end = lines.indexOfFirst { it.trimEnd().endsWith("END OF HEADER") }
        assertTrue(end > 0)
        for (l in lines.take(end + 1)) assertEquals(l, 80, l.length)
        assertTrue(lines[0].startsWith("     3.04           OBSERVATION DATA    M"))
        val sys = lines.filter { it.trimEnd().endsWith("SYS / # / OBS TYPES") }
        assertEquals("G    8 C1C L1C D1C S1C C5Q L5Q D5Q S5Q", sys[0].substring(0, 38))
        assertTrue(sys[1].startsWith("R    4 C1C L1C D1C S1C"))
        assertTrue(lines.any { it.startsWith("  1 R03 -1") && it.trimEnd().endsWith("GLONASS SLOT / FRQ #") })
        assertEquals("> 2026 10 12 03 46 40.0000000  0  2", lines[end + 1])
        val g05 = lines[end + 2]
        assertTrue(g05.startsWith("G05"))
        assertEquals(0.07 * c, g05.substring(3, 17).trim().toDouble(), 2e-3)
        assertTrue(lines[end + 4].startsWith("> 2026 10 12 03 46 41.0000000  0  2"))
        assertNull(File(dir, "raw.obs.part").takeIf { it.exists() })
        System.getenv("RINEX_SAMPLE")?.let { f.copyTo(File(it), overwrite = true) }
        dir.deleteRecursively()
    }

    @Test fun analyzerVerdicts() {
        fun run(adr: Boolean, dual: Boolean, fullBias: Boolean = true): RawVerdict {
            val a = RawAnalyzer()
            for (i in 0 until 40) {
                val k = clock.copy(timeNanos = clock.timeNanos + i * 1_000_000_000L, fullBiasNanos = if (fullBias) clock.fullBiasNanos else null)
                val ms = (1..8).flatMap { sv ->
                    val rx = Math.floorMod(tGps + i * 1_000_000_000L, RawConvert.NS_WEEK) - transit
                    val st = if (adr) RawConvert.ADR_VALID else 0
                    listOfNotNull(meas(RawConvert.GPS, sv, rx, gpsState, adrState = st, adr = 100.0 + i),
                        if (dual) meas(RawConvert.GPS, sv, rx, gpsState, carrier = 1176.45e6, adrState = st, adr = 100.0 + i) else null)
                }
                a.add(k, ms)
            }
            val r = a.report()
            if (adr) assertEquals(1.0, r.bands.first { it.band == Band.L1 }.phaseOk, 1e-9)
            return r.verdict
        }
        assertEquals(RawVerdict.PHASE_DUAL, run(adr = true, dual = true))
        assertEquals(RawVerdict.PHASE_L1, run(adr = true, dual = false))
        assertEquals(RawVerdict.CODE_ONLY, run(adr = false, dual = true))
        assertEquals(RawVerdict.NO_RAW, run(adr = true, dual = true, fullBias = false))
    }

    @Test fun casterPresetsAndMountpointOrder() {
        val metrica = CasterProfiles.presets.first { it.config.host == "gr.nrtk.eu" }
        assertEquals(7801, metrica.config.port)
        assertEquals(10, metrica.config.ggaIntervalSec)
        assertNotNull(CasterProfiles.mountNote("MSM_iMAX"))
        fun mp(name: String, format: String, gga: Boolean, lat: Double) =
            Mountpoint(name, name, format, "", 2, "GPS", "", "GRC", lat, 23.7, gga, if (gga) 1 else 0, "B", false, null)
        val list = listOf(mp("FAR", "RTCM 3.3", false, 40.0), mp("CMR", "CMR+", true, 38.0), mp("VRS", "RTCM 3", true, 36.0),
            mp("NEAR1", "RTCM 3.2", false, 38.1))
        assertEquals(listOf("VRS", "NEAR1", "FAR", "CMR"), CasterProfiles.sortMountpoints(list, 38.0, 23.7).map { it.name })
        assertTrue(CasterProfiles.isRtcm3("RTCM 3.3")); assertTrue(!CasterProfiles.isRtcm3("RTCM 2"))
    }
}
