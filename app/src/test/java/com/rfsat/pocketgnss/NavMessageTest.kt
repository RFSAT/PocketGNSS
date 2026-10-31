package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.raw.GnssLog
import com.rfsat.pocketgnss.raw.GnssLogReader
import com.rfsat.pocketgnss.raw.NavMessage
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.forEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Ephemerides from the phone's own navigation messages, on a real Android GnssLogger file (Barcelona, July 2017: GPS
 * L1 C/A subframes and GLONASS strings, see resources/nav/README.md) with no caster at all: the engine has to find
 * every satellite's orbit in the messages and then position the phone where its own chip did.
 */
class NavMessageTest {
    private fun reader() = GnssLogReader(GZIPInputStream(javaClass.getResourceAsStream("/nav/nav_2017_barcelona.txt.gz")!!).bufferedReader())
    private val o = RtkOptions(galileo = false, beidou = false, qzss = false)
    /** Single-point positioning (mode 0), GPS + GLONASS: without a base, RTK mode gives no solution at all. */
    private fun start() = RtkNative.init(0, 0x01 or 0x04, 15.0, 0.0, 1, 0, 100.0, 3.0, 30.0, 5, 1)

    private class Run(val epochs: Int, val firstSolution: Int, val errors: List<Double>, val base: RtkBase)

    /** Up to [maxEpochs] epochs through the engine; nav messages fed unless [nav] is false. */
    private fun run(maxEpochs: Int = Int.MAX_VALUE, nav: Boolean = true, before: () -> Unit = {}): Run {
        assertTrue(start())
        before()
        val r = reader()
        val arrays = RoverArrays()
        val out = DoubleArray(RtkSolution.SIZE)
        var epochs = 0; var first = -1
        val errors = ArrayList<Double>()
        while (epochs < maxEpochs) {
            val e = r.next() ?: break
            epochs++
            arrays.fill(e.forEngine(o))
            val st = RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d,
                arrays.snr, arrays.lli, out)
            for (m in r.takeNav()) if (nav) RtkNative.navMessage(m.type, m.svid, m.messageId, m.submessageId, m.data, r.channel(m))
            if (st == 0) continue
            if (first < 0) first = epochs
            val s = RtkSolution.from(out)
            r.fixes.lastOrNull()?.let { f -> errors.add(Geo.distance(s.latitude, s.longitude, f.latitude, f.longitude)) }
        }
        r.close()
        val base = RtkBase.from(DoubleArray(RtkBase.SIZE).also { RtkNative.base(it) })
        return Run(epochs, first, errors, base)
    }

    @Test fun ephemeridesFromThePhonesMessages() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val r = run()
        RtkNative.free()
        assertTrue("epochs ${r.epochs}", r.epochs >= 230)
        // every message passes its parity / Hamming test; GPS orbits after one 30 s frame, GLONASS after strings 1-4
        assertEquals(0L, r.base.navRejected)
        assertTrue("messages ${r.base.navMessages}", r.base.navMessages >= 600)
        assertTrue("GPS ephemerides ${r.base.ephemerides}", r.base.ephemerides >= 7)
        assertTrue("GLONASS ephemerides ${r.base.glonassEphemerides}", r.base.glonassEphemerides >= 4)
        assertEquals(r.base.ephemerides + r.base.glonassEphemerides, r.base.phoneEphemerides)
        // this phone's GPS parity bits are meaningless, so its subframes count once seen twice: 60-90 s from the start
        assertTrue("first solution at epoch ${r.firstSolution}", r.firstSolution in 1..100)
        // the engine's single-point solution where the phone's own chip put it (the chip's fix: ±6 m)
        val median = r.errors.sorted()[r.errors.size / 2]
        assertTrue("median distance to the phone's fix %.1f m".format(median), median < 10.0)
        assertTrue("solutions ${r.errors.size}", r.errors.size >= r.epochs - 100)
    }

    @Test fun noMessagesNoPosition() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val r = run(maxEpochs = 120, nav = false)
        RtkNative.free()
        assertEquals(-1, r.firstSolution)
        assertEquals(0, r.base.ephemerides)
    }

    @Test fun ephemeridesKeptAcrossRestarts() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val file = File.createTempFile("eph", ".bin").apply { deleteOnExit() }
        val a = run(maxEpochs = 120)
        val saved = RtkNative.navSave(file.path)
        RtkNative.free()
        assertEquals(a.base.ephemerides + a.base.glonassEphemerides, saved)
        // a new start reads them: positions from the first epoch on, without a single navigation message
        var loaded = 0
        val b = run(maxEpochs = 30, nav = false) { loaded = RtkNative.navLoad(file.path) }
        assertEquals(saved, loaded)
        assertEquals(1, b.firstSolution)
        // single-point positions of the first half minute (the phone was just switched on, its own fix converging too)
        assertTrue(b.errors.sorted()[b.errors.size / 2] < 25.0)
        // they still count as from the phone
        assertEquals(b.base.ephemerides + b.base.glonassEphemerides, b.base.phoneEphemerides)
        RtkNative.free()

        // five hours later none is valid any more: all dropped at the first epoch
        assertTrue(start())
        assertEquals(saved, RtkNative.navLoad(file.path))
        val e = reader().let { r -> r.next()!!.also { r.close() } }
        val arrays = RoverArrays().apply { fill(e.forEngine(o)) }
        val out = DoubleArray(RtkSolution.SIZE)
        assertEquals(0, RtkNative.rover(e.gpsWeek, e.towSeconds + 5 * 3600.0, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l,
            arrays.d, arrays.snr, arrays.lli, out))
        val base = RtkBase.from(DoubleArray(RtkBase.SIZE).also { RtkNative.base(it) })
        assertEquals(0, base.ephemerides + base.glonassEphemerides)
        RtkNative.free()
        // no file, or not one of ours: nothing read
        assertTrue(start())
        assertEquals(-1, RtkNative.navLoad(File(file.parentFile, "missing-${System.nanoTime()}.bin").path))
        val junk = File.createTempFile("junk", ".bin").apply { writeText("not ephemerides"); deleteOnExit() }
        assertEquals(-1, RtkNative.navLoad(junk.path))
        RtkNative.free()
    }

    @Test fun corruptedMessagesAreRejected() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        assertTrue(start())
        val r = reader()
        val e = r.next()!!
        val arrays = RoverArrays().apply { fill(e.forEngine(o)) }
        RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d, arrays.snr,
            arrays.lli, DoubleArray(RtkSolution.SIZE))
        val msgs = ArrayList(r.takeNav())
        repeat(40) { r.next(); msgs.addAll(r.takeNav()) }
        r.close()
        val gps = msgs.first { it.type == NavMessage.GPS_L1CA }
        val glo = msgs.first { it.type == NavMessage.GLO_L1CA }
        assertTrue(RtkNative.navMessage(gps.type, gps.svid, gps.messageId, gps.submessageId, gps.data, -99) >= 0)
        assertTrue(RtkNative.navMessage(glo.type, glo.svid, glo.messageId, glo.submessageId, glo.data, -99) >= 0)
        // GPS: this phone's parity bits are meaningless, so a subframe counts only once the same data came twice; one
        // with a bit flipped is never confirmed. A wrong subframe number or a time of week far from now is rejected.
        val g2 = gps.data.copyOf().also { it[9] = (it[9].toInt() xor 0x10).toByte() }
        assertTrue(RtkNative.navMessage(gps.type, gps.svid, gps.messageId, gps.submessageId, g2, -99) <= 0)
        val wrongSub = if (gps.submessageId == 1) 2 else 1
        assertEquals(-1, RtkNative.navMessage(gps.type, gps.svid, gps.messageId, wrongSub, gps.data, -99))
        val tow = gps.data.copyOf().also { it[4] = (it[4].toInt() xor 0x20).toByte() }   // HOW time of week 4.5 days away
        assertEquals(-1, RtkNative.navMessage(gps.type, gps.svid, gps.messageId, gps.submessageId, tow, -99))
        val r3 = glo.data.copyOf().also { it[4] = (it[4].toInt() xor 0x05).toByte() } // two bits: beyond the Hamming code
        assertEquals(-1, RtkNative.navMessage(glo.type, glo.svid, glo.messageId, glo.submessageId, r3, -99))
        // kinds the engine does not decode (GPS CNAV)
        assertEquals(-1, RtkNative.navMessage(0x0103, 5, 0, 0, ByteArray(40), -99))
        RtkNative.free()
    }

    @Test fun logLinesRoundTrip() {
        val m = NavMessage(NavMessage.GAL_I, 11, 1, 3, 2, byteArrayOf(1, -2, 127, -128))
        val line = GnssLog.nav(m)
        assertEquals("Nav,11,1537,1,3,2,1,-2,127,-128", line)
        val p = NavMessage.parse(line)!!
        assertEquals(NavMessage.GAL_I, p.type); assertEquals(11, p.svid); assertEquals(3, p.messageId); assertEquals(2, p.submessageId)
        assertArrayEquals(m.data, p.data)
        assertEquals("Galileo", p.system)
        assertEquals(1, NavMessage.glonassChannel(1_602_562_500.0))
        assertEquals(-7, NavMessage.glonassChannel(1_598_062_500.0))
        assertEquals(-99, NavMessage.glonassChannel(1_575_420_000.0))
    }
}
