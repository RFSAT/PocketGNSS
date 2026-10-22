package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AppLog
import com.rfsat.pocketgnss.core.DgnssSource
import com.rfsat.pocketgnss.core.SatInfo
import com.rfsat.pocketgnss.data.TrackPoint
import com.rfsat.pocketgnss.data.TrackStore
import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.FixQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The map trace kept between runs, the app log, and naming the source of DGNSS fixes. */
class DiagnosticsTest {
    @Test fun traceSurvivesARestartUntilCleared() {
        val dir = Files.createTempDirectory("track").toFile()
        val store = TrackStore(java.io.File(dir, "points/track.bin"))
        assertTrue(store.load().isEmpty())
        val pts = (0 until 7200).map { TrackPoint(1_760_000_000_000L + it * 1000L, 40.76 + it * 1e-7, 22.52 - it * 1e-7, if (it % 3 == 0) null else 81.5 + it * 0.01,
            FixQuality.entries[it % FixQuality.entries.size]) }
        store.save(pts)
        val back = TrackStore(java.io.File(dir, "points/track.bin")).load()
        assertEquals(pts.size, back.size)
        for (i in pts.indices step 97) {
            assertEquals(pts[i].timeMs, back[i].timeMs); assertEquals(pts[i].latitude, back[i].latitude, 0.0); assertEquals(pts[i].longitude, back[i].longitude, 0.0)
            assertEquals(pts[i].quality, back[i].quality)
            if (pts[i].height == null) assertNull(back[i].height) else assertEquals(pts[i].height!!, back[i].height!!, 1e-3)
        }
        assertTrue(java.io.File(dir, "points/track.bin").length() < 220_000)
        // a damaged file is ignored, not a crash
        java.io.File(dir, "points/track.bin").writeText("rubbish")
        assertTrue(store.load().isEmpty())
        store.save(pts.take(3)); store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test fun logLinesReadBack() {
        val dir = Files.createTempDirectory("log").toFile()
        AppLog.init(dir)
        AppLog.i("NTRIP", "Streaming MSM_NEAR: ICY 200 OK")
        AppLog.w("Phone RTK", "No carrier phase from the phone (31 signals)")
        AppLog.e("Link", "Connection lost", IllegalStateException("socket closed"))
        repeat(5) { AppLog.throttled("k", 60_000, AppLog.Level.INFO, "RTK engine", "outlier rejected", now = 1_000L + it) }
        val e = AppLog.entries.value
        assertEquals(4, e.size)                                    // the throttled message once
        assertEquals(AppLog.Level.WARN, e[1].level); assertEquals("Phone RTK", e[1].tag)
        assertTrue(e[2].message.contains("IllegalStateException: socket closed"))
        val parsed = AppLog.parse(e[0].line())!!
        assertEquals(e[0].tag, parsed.tag); assertEquals(e[0].message, parsed.message); assertEquals(e[0].timeMs, parsed.timeMs)
        // the file is written in the background: wait for it, then a restart shows the same entries
        val f = java.io.File(dir, "app.log")
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !(f.isFile && f.readText().contains("outlier rejected"))) Thread.sleep(20)
        AppLog.init(dir)
        assertEquals(listOf("NTRIP", "Phone RTK", "Link", "RTK engine"), AppLog.entries.value.map { it.tag })
        assertTrue(AppLog.text().contains("\n    \tat com.rfsat"))     // stack trace kept in the shared text, indented
        AppLog.clear()
        assertTrue(AppLog.entries.value.isEmpty())
    }

    @Test fun dgnssSourceIsNamed() {
        fun sat(c: Constellation, id: Int, used: Boolean) = SatInfo(c, id, 30, 120, mapOf("L1" to 40), used, false)
        val withSbas = listOf(sat(Constellation.GPS, 5, true), sat(Constellation.SBAS, 36, true))
        val sbasNotUsed = listOf(sat(Constellation.GPS, 5, true), sat(Constellation.SBAS, 36, false))
        assertEquals(DgnssSource.SBAS, DgnssSource.of(FixQuality.DGNSS, withSbas, relayingCorrections = false))
        assertEquals(DgnssSource.BASE, DgnssSource.of(FixQuality.DGNSS, withSbas, relayingCorrections = true))
        assertNull(DgnssSource.of(FixQuality.DGNSS, sbasNotUsed, relayingCorrections = false))
        assertNull(DgnssSource.of(FixQuality.RTK_FLOAT, withSbas, relayingCorrections = true))
        assertEquals(DgnssSource.BASE, DgnssSource.ofPhoneMethod(FixQuality.DGNSS, "Code DGNSS"))
        assertEquals(DgnssSource.PPP, DgnssSource.ofPhoneMethod(FixQuality.DGNSS, "PPP converging"))
        assertNull(DgnssSource.ofPhoneMethod(FixQuality.SINGLE, "Android location"))
    }
}
