package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.core.Statistics
import com.rfsat.pocketgnss.core.TtffRun
import com.rfsat.pocketgnss.data.Exporters
import com.rfsat.pocketgnss.data.PointStore
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.data.TrackPoint
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaMsg
import com.rfsat.pocketgnss.protocol.StartType
import com.rfsat.pocketgnss.protocol.StreamSplitter
import com.rfsat.pocketgnss.protocol.Ubx
import com.rfsat.pocketgnss.protocol.UbxFrame
import com.rfsat.pocketgnss.protocol.UbxLayer
import com.rfsat.pocketgnss.protocol.UbxPort
import com.rfsat.pocketgnss.link.ReceiverVariant
import com.rfsat.pocketgnss.sim.SimConfig
import com.rfsat.pocketgnss.sim.SimMotion
import com.rfsat.pocketgnss.sim.SimReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SimulatorTest {

    /** Runs the simulator through the model, as the engine does, for [seconds] at 1 Hz. */
    private fun run(sim: SimReceiver, seconds: Int, model: GnssModel = GnssModel()): List<com.rfsat.pocketgnss.core.GnssFix> {
        val out = ArrayList<com.rfsat.pocketgnss.core.GnssFix>()
        repeat(seconds) { for (l in sim.epoch()) { assertTrue(l, Nmea.checksumOk(l)); Nmea.parse(l)?.let(model::accept)?.let(out::add) } }
        return out
    }

    @Test fun coldStartReachesRtkFixedWithCentimetreScatter() {
        val sim = SimReceiver(SimConfig(), seed = 1)
        val model = GnssModel()
        var first = -1
        val fixes = ArrayList<com.rfsat.pocketgnss.core.GnssFix>()
        for (epoch in 1..120) for (l in sim.epoch()) Nmea.parse(l)?.let(model::accept)?.let { fixes.add(it); if (first < 0) first = epoch }
        assertTrue("TTFF about 26 s, got $first", first in 24..28)
        val fixed = fixes.filter { it.quality == FixQuality.RTK_FIXED }
        assertTrue(fixed.size > 50)
        assertTrue("GST gives measured accuracy", fixed.last().measuredAccuracy)
        val st = Statistics.compute(fixed.map { doubleArrayOf(it.latitude, it.longitude, it.ellipsoidHeight!!) })!!
        assertTrue("RTK fixed scatter ${st.cep95}", st.cep95 < 0.03)
        // Order of states follows D3.5: single -> DGNSS -> float -> fixed.
        val order = fixes.map { it.quality }.distinct()
        assertEquals(listOf(FixQuality.SINGLE, FixQuality.DGNSS, FixQuality.RTK_FLOAT, FixQuality.RTK_FIXED), order)
    }

    @Test fun withoutCorrectionsStaysOnSbasUntilRtcmArrives() {
        val sim = SimReceiver(SimConfig(corrections = false), seed = 2)
        val fixes = run(sim, 80)
        assertFalse(fixes.any { it.quality.rank >= FixQuality.RTK_FLOAT.rank })
        sim.rtcmReceived()
        val more = run(sim, 6)
        assertTrue(more.any { it.quality == FixQuality.RTK_FLOAT || it.quality == FixQuality.RTK_FIXED })
    }

    @Test fun ubxValsetChangesOutputAndRateAndIsAcknowledged() {
        val sim = SimReceiver(SimConfig(), seed = 3)
        val frames = ArrayList<UbxFrame>()
        val sp = StreamSplitter({}, { frames.add(it) })
        val cmd = Ubx.valset(Ubx.nmeaOutputs(setOf(NmeaMsg.GGA, NmeaMsg.RMC), setOf(UbxPort.USB, UbxPort.UART1)) + Ubx.rateKeys(10), UbxLayer.ALL)
        StreamSplitter({}, { f -> sim.handleUbx(f).forEach { sp.feed(it) } }).feed(cmd)
        assertEquals(Ubx.CLS_CFG to Ubx.ID_CFG_VALSET, Ubx.ackedOf(frames.single()))
        assertEquals(Ubx.ID_ACK_ACK, frames.single().id)
        assertEquals(100, sim.periodMs)
        repeat(40) { sim.epoch() }
        val types = sim.epoch().mapNotNull { Nmea.parse(it)?.type }.toSet()
        assertEquals(setOf("GGA", "RMC"), types)
        // VALGET reads back what was set.
        frames.clear()
        StreamSplitter({}, { f -> sim.handleUbx(f).forEach { sp.feed(it) } }).feed(Ubx.valget(listOf(NmeaMsg.GGA.key(UbxPort.USB), NmeaMsg.GSV.key(UbxPort.USB), Ubx.KEY_RATE_MEAS)))
        val v = Ubx.parseValget(frames.first { it.cls == Ubx.CLS_CFG }.payload)
        assertEquals(1L, v[NmeaMsg.GGA.key(UbxPort.USB)]); assertEquals(0L, v[NmeaMsg.GSV.key(UbxPort.USB)]); assertEquals(100L, v[Ubx.KEY_RATE_MEAS])
    }

    @Test fun resetRestartsTtffAndMonVerIdentifies() {
        val sim = SimReceiver(SimConfig(), seed = 4)
        run(sim, 70)
        assertEquals(FixQuality.RTK_FIXED, sim.quality)
        val f = UbxFrame(Ubx.CLS_CFG, Ubx.ID_CFG_RST, Ubx.reset(StartType.HOT).copyOfRange(6, 10))
        assertTrue(sim.handleUbx(f).isEmpty())
        var run = TtffRun("hot", 0)
        val fixes = run(sim, 60)
        fixes.forEachIndexed { i, x -> run = run.update(x.quality, (i + 1) * 1000L) }
        assertTrue("hot start fix in ~2 s", run.seconds(run.firstFixMs)!! <= 3.0)
        assertNotNull(run.firstFixedMs)
        val ver = sim.handleUbx(UbxFrame(Ubx.CLS_MON, Ubx.ID_MON_VER, ByteArray(0))).single()
        val v = Ubx.parseMonVer(ver.copyOfRange(6, ver.size - 2))!!
        assertTrue(v.module!!.startsWith("ZED-F9P"))
    }

    /** First epoch (1 Hz) at which [q] is reached. */
    private fun firstEpoch(sim: SimReceiver, q: FixQuality, max: Int): Int? {
        val m = GnssModel()
        for (e in 1..max) for (l in sim.epoch()) Nmea.parse(l)?.let(m::accept)?.let { if (it.quality == q) return e }
        return null
    }

    @Test fun lteVersionNeedsAboutAMinuteForCorrections() {
        val lte = firstEpoch(SimReceiver(SimConfig(variant = ReceiverVariant.LTE), seed = 11), FixQuality.RTK_FIXED, 200)!!
        val wifi = firstEpoch(SimReceiver(SimConfig(variant = ReceiverVariant.WIFI), seed = 11), FixQuality.RTK_FIXED, 200)!!
        // D3.5 GNSS-test-07: 55-60 s to RTK data over LTE, then 25-30 s to fixed.
        assertTrue("LTE fixed at $lte s", lte in 78..92)
        assertTrue("Wi-Fi fixed at $wifi s", wifi in 55..70)
        assertEquals(FixQuality.RTK_FLOAT, run(SimReceiver(SimConfig(variant = ReceiverVariant.LTE), seed = 11), 70).last().quality)
    }

    @Test fun outagesFollowEachVersionsAvailability() {
        fun hour(v: ReceiverVariant): Pair<Double, List<Double>> {
            val sim = SimReceiver(SimConfig(variant = v), seed = 21)
            val fixes = run(sim, 3600).drop(120)
            var lostAt = -1; val reacq = ArrayList<Double>()
            fixes.forEachIndexed { i, f ->
                if (f.quality != FixQuality.RTK_FIXED && lostAt < 0 && i > 0 && fixes[i - 1].quality == FixQuality.RTK_FIXED) lostAt = i
                if (f.quality == FixQuality.RTK_FIXED && lostAt >= 0) { reacq.add((i - lostAt).toDouble()); lostAt = -1 }
            }
            return fixes.count { it.quality == FixQuality.RTK_FIXED }.toDouble() / fixes.size to reacq
        }
        val (lteAvail, lteReacq) = hour(ReceiverVariant.LTE)
        val (wifiAvail, _) = hour(ReceiverVariant.WIFI)
        assertTrue("LTE RTK fixed $lteAvail", lteAvail in 0.88..0.99)
        assertTrue("Wi-Fi RTK fixed $wifiAvail", wifiAvail > 0.97 && wifiAvail > lteAvail)
        assertTrue("LTE outages seen", lteReacq.isNotEmpty())
    }

    @Test fun swathsMove() {
        val sim = SimReceiver(SimConfig(motion = SimMotion.SWATHS), seed = 5)
        val fixes = run(sim, 120).filter { it.quality == FixQuality.RTK_FIXED }
        val d = com.rfsat.pocketgnss.protocol.Geo.distance(fixes.first().latitude, fixes.first().longitude, fixes.last().latitude, fixes.last().longitude)
        assertTrue("moved $d m", d > 20)
        assertTrue(fixes.last().speedMs!! > 2.0)
    }

    @Test fun exportersAndPointStore() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rtk-test-" + System.nanoTime()).apply { mkdirs() }
        val store = PointStore(File(dir, "points.csv"))
        val p = SurveyPoint("Tree; 7", 40.7567123456, 22.1213, 74.4, 38.0, 0.004, 0.005, 0.009, 300, FixQuality.RTK_FIXED, 1_792_000_000_000)
        store.add(p); store.add(p.copy(name = "B", timeMs = 1))
        val back = store.load()
        assertEquals(2, back.size); assertEquals("Tree, 7", back[0].name); assertEquals(40.7567123456, back[0].latitude, 1e-10)
        store.remove(back[1]); assertEquals(1, store.load().size)
        val kml = Exporters.pointsKml(back)
        assertTrue(kml.contains("<coordinates>22.1213000000,40.7567123456,38.0000</coordinates>"))
        assertTrue(Exporters.pointsGeoJson(back).startsWith("{\"type\":\"FeatureCollection\""))
        val gpx = Exporters.trackGpx(listOf(TrackPoint(0, 40.0, 22.0, 10.0, FixQuality.RTK_FIXED)), "t")
        assertTrue(gpx.contains("<fix>dgps</fix>"))
        dir.deleteRecursively()
    }
}
