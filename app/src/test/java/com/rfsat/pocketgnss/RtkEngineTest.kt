package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.link.PhoneFix
import com.rfsat.pocketgnss.link.PhoneNmea
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.raw.Band
import com.rfsat.pocketgnss.raw.RawEpoch
import com.rfsat.pocketgnss.raw.SignalObs
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The phone RTK engine end to end through its JNI bridge, on the host build of the same C code: one hour of a
 * real 3.3 km baseline (RTKLIB test data, GSI stations 0759 / 3040, GPS L1 + L2). The rover epochs come in the
 * arrays the phone side builds; the base comes as the RTCM 3 byte stream a caster sends (1005 station, 1019
 * ephemerides, 1077 MSM7). Expected: RTK fixed within a few minutes and the final position within 2 cm of RTKLIB's
 * own post-processed solution.
 */
class RtkEngineTest {
    private val reference = doubleArrayOf(-3976219.1892, 3382371.6070, 3652511.1670) // rnx2rtkp kinematic, last epoch

    @Test fun twoStationSession() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val o = RtkOptions(glonass = false, galileo = false, beidou = false, qzss = false)
        assertTrue(RtkNative.start(o))
        val lines = javaClass.getResourceAsStream("/rtk/two_station_3km.txt")!!.bufferedReader().readLines()
        val arrays = RoverArrays()
        val out = DoubleArray(RtkSolution.SIZE)
        var i = 0
        var fixes = 0; var floats = 0; var firstFix = -1; var epochs = 0
        var last: RtkSolution? = null
        RtkNative.setTime(1316, 518400.0)
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("B ")) {
                val hex = l.substring(2)
                val bytes = ByteArray(hex.length / 2) { k -> hex.substring(2 * k, 2 * k + 2).toInt(16).toByte() }
                RtkNative.inputRtcm(bytes, 0, bytes.size)
                i++
            } else if (l.startsWith("R ")) {
                val (_, week, tow, n) = l.split(' ')
                val obs = (1..n.toInt()).map { k ->
                    val f = lines[i + k].split(' ')
                    SignalObs(f[0][0], f[1].toInt(), f[2], f[3].toDouble().takeIf { it != 0.0 }, f[4].toDouble().takeIf { it != 0.0 },
                        f[5].toDouble(), f[6].toDouble(), f[7].toInt(), Band.L1)
                }
                i += n.toInt() + 1
                arrays.fill(RawEpoch(week.toInt(), tow.toDouble(), obs, false))
                val st = RtkNative.rover(week.toInt(), tow.toDouble(), arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l,
                    arrays.d, arrays.snr, arrays.lli, out)
                epochs++
                val raw = RtkSolution.from(out)
                assertEquals(st, raw.status.code)
                // What the app reports: fixes only once confirmed (FixGate).
                val s = com.rfsat.pocketgnss.rtk.FixGate.apply(raw, o)
                if (raw.status == RtkStatus.FIX) assertTrue("float alongside fix", raw.hasFloat)
                if (s.status == RtkStatus.FIX) { fixes++; if (firstFix < 0) firstFix = epochs }
                if (s.status == RtkStatus.FLOAT) floats++
                if (s.status != RtkStatus.NONE) last = s
            } else i++
        }
        val base = RtkBase.from(DoubleArray(RtkBase.SIZE).also { RtkNative.base(it) })
        RtkNative.free()

        assertEquals(120, epochs)
        assertTrue("fixed epochs $fixes", fixes >= 60)
        assertTrue("first fix at epoch $firstFix (30 s each)", firstFix in 1..20)
        assertEquals(3040, base.stationId)
        assertTrue(base.hasPosition && base.ephemerides >= 8)
        val s = last!!
        assertEquals(RtkStatus.FIX, s.status)
        val xyz = Geo.geodeticToEcef(s.latitude, s.longitude, s.height)
        val err = Math.sqrt((0..2).sumOf { (xyz[it] - reference[it]).let { d -> d * d } })
        assertTrue("distance to reference %.4f m".format(err), err < 0.02)
        assertEquals(3335.4, s.baseline, 0.5)
        assertTrue(s.stdEast < 0.1 && s.stdNorth < 0.1)
        // Japan, EGM96 geoid about 37 m above the ellipsoid near Tsukuba.
        assertTrue("geoid ${s.geoid}", s.geoid in 30.0..45.0)
    }

    @Test fun rtkSolutionAsNmea() {
        // A fixed solution as PhoneRtkLink emits it: GGA quality 4 with age and station, GST from the filter.
        val fix = PhoneFix(1_792_576_000_000L, 37.98, 23.73, 180.0, 140.0, 0.012f, 0.03f, 0f, null, quality = 4, diffAgeSec = 1.2,
            stationId = 3040, stdEast = 0.008, stdNorth = 0.009, stdUp = 0.02, satellitesUsed = 14)
        val lines = PhoneNmea.epoch(fix, emptyList())
        val gga = lines.first { it.startsWith("\$GNGGA") }.substringBefore('*').split(',')
        assertEquals("4", gga[6]); assertEquals("14", gga[7]); assertEquals("1.2", gga[13]); assertEquals("3040", gga[14])
        val rmc = lines.first { it.startsWith("\$GNRMC") }.substringBefore('*').split(',')
        assertEquals("R", rmc[12])
        val gst = lines.first { it.startsWith("\$GNGST") }.substringBefore('*').split(',')
        assertEquals(0.009, gst[6].toDouble(), 1e-9); assertEquals(0.008, gst[7].toDouble(), 1e-9); assertEquals(0.02, gst[8].toDouble(), 1e-9)
        // And the app's own NMEA model reads it back as RTK fixed with the measured accuracy.
        val m = com.rfsat.pocketgnss.core.GnssModel()
        val f = lines.mapNotNull { com.rfsat.pocketgnss.protocol.Nmea.parse(it)?.let(m::accept) }.lastOrNull()
        assertEquals(com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED, f?.quality)
        assertEquals(1.2, f?.diffAgeSec ?: 0.0, 1e-9)
    }

    @Test fun eachFixCarriesItsOwnAccuracy() {
        // Android's location (0.5 m) one second, an RTK fixed solution (1 cm) the next: the fix of each epoch must
        // carry that epoch's accuracy, not the previous one's (GST is sent before GGA for this reason).
        val m = com.rfsat.pocketgnss.core.GnssModel()
        fun fixOf(lines: List<String>) = lines.mapNotNull { com.rfsat.pocketgnss.protocol.Nmea.parse(it)?.let(m::accept) }.single()
        val t0 = 1_792_576_000_000L
        val a = fixOf(PhoneNmea.epoch(PhoneFix(t0, 37.98, 23.73, 180.0, 140.0, 0.5f, 1f, 0f, null), emptyList()))
        val b = fixOf(PhoneNmea.epoch(PhoneFix(t0 + 1000, 37.98, 23.73, 180.0, 140.0, 0.012f, 0.03f, 0f, null, quality = 4, diffAgeSec = 1.0,
            stationId = 27, stdEast = 0.008, stdNorth = 0.009, stdUp = 0.02), emptyList()))
        val c = fixOf(PhoneNmea.epoch(PhoneFix(t0 + 2000, 37.98, 23.73, 180.0, 140.0, 0.5f, 1f, 0f, null), emptyList()))
        assertEquals(0.5, a.horizontalAccuracy, 1e-3)
        assertEquals(Math.hypot(0.008, 0.009), b.horizontalAccuracy, 1e-3)
        assertEquals(com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED, b.quality)
        assertEquals(0.5, c.horizontalAccuracy, 1e-3)
    }

    @Test fun historyStatistics() {
        val h = com.rfsat.pocketgnss.core.SolutionHistory(java.io.File(System.getProperty("java.io.tmpdir")))
        val lat0 = 38.0; val lon0 = 23.7
        val mPerDegLat = Math.toRadians(1.0) * Geo.A
        // 50 fixed positions within 1 cm, 50 "Android" positions 1 m east claiming 0.4 m.
        for (i in 0 until 50) h.add(com.rfsat.pocketgnss.core.SolutionRecord(i * 1000L, "RTK fixed", com.rfsat.pocketgnss.protocol.FixQuality.RTK_FIXED,
            lat0 + (if (i % 2 == 0) 0.005 else -0.005) / mPerDegLat, lon0, 100.0, 0.01, 0.02, true, 20, 0.8, 1.0))
        for (i in 0 until 50) h.add(com.rfsat.pocketgnss.core.SolutionRecord(i * 1000L, "Android location", com.rfsat.pocketgnss.protocol.FixQuality.SINGLE,
            lat0, lon0 + 1.0 / (mPerDegLat * Math.cos(Math.toRadians(lat0))), 100.0, 0.4, 1.0, true, 20, 0.8, null))
        val ref = h.referencePoint()!!
        assertEquals(lat0, ref.first, 1e-9); assertEquals(lon0, ref.second, 1e-9) // mean of the best method (fixed)
        val st = com.rfsat.pocketgnss.core.SolutionHistory.stats(h.records(), ref)
        assertEquals("RTK fixed", st[0].method)
        assertEquals(0.005, st[0].rmsDist, 1e-4); assertEquals(1.0, st[0].within2Sigma, 1e-9)
        assertEquals(1.0, st[1].rmsDist, 1e-3); assertEquals(0.0, st[1].within2Sigma, 1e-9) // 1 m off while claiming 0.4 m
        assertEquals(0.0, st[1].rmsOwn, 1e-6)
        val csv = h.csv().lines()
        assertEquals(com.rfsat.pocketgnss.core.SolutionHistory.HEADER, csv[0])
        assertEquals(101, csv.count { it.isNotBlank() })
        val east = csv[60].split(',')[12].toDouble()
        assertEquals(1.0, east, 1e-3)
    }

    @Test fun fixGate() {
        val o = RtkOptions()
        val fix = RtkSolution(RtkStatus.FIX, 2440, 100.0, 38.0, 23.7, 100.0, 0.005, 0.006, 0.01, 0.0, 0.0, 0.0, 8, 12.0, 1.0, 5000.0, 35.0,
            38.00001, 23.70001, 100.2, 0.25, 0.3, 0.5, consecutiveFixes = 1)
        // A first-epoch fix is not trusted: float, with the float position and its standard deviations.
        val g = com.rfsat.pocketgnss.rtk.FixGate.apply(fix, o)
        assertEquals(RtkStatus.FLOAT, g.status); assertEquals(38.00001, g.latitude, 1e-12); assertEquals(0.3, g.stdNorth, 1e-12)
        // Held for three epochs with a good ratio and enough satellites: fixed.
        assertEquals(RtkStatus.FIX, com.rfsat.pocketgnss.rtk.FixGate.apply(fix.copy(consecutiveFixes = 3), o).status)
        // Low ratio or too few satellites: float.
        assertEquals(RtkStatus.FLOAT, com.rfsat.pocketgnss.rtk.FixGate.apply(fix.copy(consecutiveFixes = 5, ratio = 2.0), o).status)
        assertEquals(RtkStatus.FLOAT, com.rfsat.pocketgnss.rtk.FixGate.apply(fix.copy(consecutiveFixes = 5, satellites = 4), o).status)
        // Other states pass unchanged.
        assertEquals(RtkStatus.SINGLE, com.rfsat.pocketgnss.rtk.FixGate.apply(fix.copy(status = RtkStatus.SINGLE), o).status)
    }

    @Test fun positionCheckAgainstThePhonesOwnFix() {
        val c = com.rfsat.pocketgnss.rtk.PositionCheck(resetAfterMs = 10_000, resetIntervalMs = 30_000)
        val use = com.rfsat.pocketgnss.rtk.PositionCheck.Verdict.USE; val rej = com.rfsat.pocketgnss.rtk.PositionCheck.Verdict.REJECT
        val reset = com.rfsat.pocketgnss.rtk.PositionCheck.Verdict.REJECT_AND_RESET
        val lat = 40.76; val lon = 22.52
        val dLat = 1.0 / 111_000                                      // about 1 m north
        // a few metres apart: used; no phone fix: used unchecked
        assertEquals(use, c.check(0, lat + 5 * dLat, lon, 0.5, lat, lon, 4.0))
        assertEquals(use, c.check(0, lat + 5000 * dLat, lon, 0.5, null, null, null))
        // 25 m away with the phone reporting 12 m: still credible (limit 4 x 12 + 3 x 0.5 = 49.5 m)
        assertEquals(use, c.check(0, lat + 25 * dLat, lon, 0.5, lat, lon, 12.0))
        assertEquals(30.0, com.rfsat.pocketgnss.rtk.PositionCheck.limit(0.2, 3.0), 1e-9)
        // 300 m away while claiming 2 m: rejected; after 10 s of that, a filter restart, then not again for 30 s
        var t = 1_000L
        assertEquals(rej, c.check(t, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0))
        while (t < 10_000) { t += 1000; assertEquals(rej, c.check(t, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0)) }
        t += 1000; assertEquals(reset, c.check(t, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0))
        repeat(20) { t += 1000; assertEquals(rej, c.check(t, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0)) }
        t += 10_000; assertEquals(reset, c.check(t, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0))
        assertEquals(2, c.resets); assertEquals(32, c.rejected)
        // a good solution clears the run of bad ones
        assertEquals(use, c.check(t + 1000, lat, lon, 0.5, lat, lon, 5.0))
        assertEquals(rej, c.check(t + 2000, lat + 300 * dLat, lon, 2.0, lat, lon, 5.0))
    }

    @Test fun codeOnlySolutionsGiveWayToABetterPhoneFix() {
        val P = com.rfsat.pocketgnss.rtk.PositionCheck
        assertTrue(P.preferPhone(RtkStatus.DGPS, 1.5, 0.51))         // the case seen on the S24
        assertTrue(!P.preferPhone(RtkStatus.DGPS, 0.4, 0.51))
        assertTrue(P.preferPhone(RtkStatus.SINGLE, 3.0, 2.0))
        assertTrue(!P.preferPhone(RtkStatus.FLOAT, 1.5, 0.51))       // carrier phase: always the engine
        assertTrue(!P.preferPhone(RtkStatus.FIX, 0.9, 0.51))
        assertTrue(!P.preferPhone(RtkStatus.PPP, 1.5, 0.51))
        assertTrue(!P.preferPhone(RtkStatus.DGPS, 1.5, null))
    }

    @Test fun phoneLearning() {
        val o = RtkOptions()
        // A phone with 3 satellites with phase (never enough to fix): after 10 minutes, no ambiguity fixing.
        val weak = com.rfsat.pocketgnss.rtk.ProfileLearner(com.rfsat.pocketgnss.rtk.PhoneProfile("SM-S921B"), o.minFixSats)
        repeat(700) { weak.add(3, 0) }
        val pw = weak.profile()
        assertEquals(700L, pw.epochs); assertEquals(3.0, pw.meanPhaseSats, 1e-9); assertEquals(0.0, pw.fixableShare, 1e-9)
        assertEquals(com.rfsat.pocketgnss.rtk.PhoneTuning.Verdict.NO_AR, com.rfsat.pocketgnss.rtk.PhoneTuning.verdict(pw))
        assertEquals(0, com.rfsat.pocketgnss.rtk.PhoneTuning.tune(o, pw).arMode)
        // A good dual-frequency phone: 9 satellites with phase, rare slips: standard settings.
        val good = com.rfsat.pocketgnss.rtk.ProfileLearner(com.rfsat.pocketgnss.rtk.PhoneProfile("Pixel"), o.minFixSats)
        repeat(700) { i -> good.add(9, if (i % 120 == 0) 1 else 0) }
        assertEquals(com.rfsat.pocketgnss.rtk.PhoneTuning.Verdict.NORMAL, com.rfsat.pocketgnss.rtk.PhoneTuning.verdict(good.profile()))
        // Patchy: enough phase half of the time: fixes must hold longer.
        val patchy = com.rfsat.pocketgnss.rtk.ProfileLearner(com.rfsat.pocketgnss.rtk.PhoneProfile("X"), o.minFixSats)
        repeat(700) { i -> patchy.add(if (i % 2 == 0) 6 else 3, 0) }
        val t = com.rfsat.pocketgnss.rtk.PhoneTuning.tune(o, patchy.profile())
        assertEquals(10, t.confirmEpochs); assertEquals(4.0, t.arRatio, 1e-9)
        // Not enough data yet: defaults; and the profile survives storage.
        assertEquals(com.rfsat.pocketgnss.rtk.PhoneTuning.Verdict.LEARNING, com.rfsat.pocketgnss.rtk.PhoneTuning.verdict(com.rfsat.pocketgnss.rtk.PhoneProfile("Y", epochs = 100)))
        assertEquals(pw, com.rfsat.pocketgnss.rtk.PhoneProfile.decode(pw.encode()))
        // A later session merges with the stored profile (weighted by epochs).
        val again = com.rfsat.pocketgnss.rtk.ProfileLearner(pw, o.minFixSats); repeat(700) { again.add(7, 0) }
        assertEquals(5.0, again.profile().meanPhaseSats, 1e-9); assertEquals(0.5, again.profile().fixableShare, 1e-9)
    }
}
