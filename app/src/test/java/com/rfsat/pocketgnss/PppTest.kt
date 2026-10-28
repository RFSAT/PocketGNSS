package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssFix
import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.link.NmeaReplayLink
import com.rfsat.pocketgnss.link.PhoneRtkLink
import com.rfsat.pocketgnss.link.RawReplayLink
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.RtcmFrames
import com.rfsat.pocketgnss.rtk.IonoChooser
import com.rfsat.pocketgnss.rtk.PppConvergence
import com.rfsat.pocketgnss.rtk.RtkMode
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Precise point positioning with Galileo HAS: one hour of real HAS Internet Data Distribution corrections and the
 * observations of station OBE4 (resources/ppp/README.md), replayed through the app's own path (RawReplayLink -> RTKLIB
 * PPP), against the station's known position.
 */
class PppTest {
    /**
     * The station's ITRF position (4186704.2262, 834903.7677, 4723664.9337, as cssrlib's test) in ETRF2000 at the session
     * epoch (2023.625, PROJ 9.8.1): OBE4 is in Europe, so the app gives PPP results in ETRS89.
     */
    private val reference = doubleArrayOf(4186704.7938, 834903.2013, 4723664.5189)

    private fun resource(name: String) = GZIPInputStream(javaClass.getResourceAsStream("/ppp/$name")!!).readBytes()

    /** A phone raw session folder with the station's observations (optionally only some signals) and the HAS stream. */
    private fun session(keep: Map<Char, Set<String>>? = null): File {
        val dir = Files.createTempDirectory("raw_ppp").toFile()
        val obs = String(resource("obe4_2023229c_5s.obs.gz"), Charsets.US_ASCII)
        File(dir, "raw.obs").writeText(if (keep == null) obs else subset(obs, keep))
        File(dir, "base.rtcm3").writeBytes(resource("idd2023229c.rtc.gz"))
        return dir
    }

    /** RINEX 3 with only the signals (band + attribute) in [keep] per system. */
    private fun subset(text: String, keep: Map<Char, Set<String>>): String {
        val lines = text.split('\n'); val out = StringBuilder(); val types = HashMap<Char, List<String>>()
        var i = 0
        while (!lines[i].contains("END OF HEADER")) {
            val l = lines[i]
            if (l.drop(60).trim() == "SYS / # / OBS TYPES") {
                val t = l.substring(7, 58).trim().split(Regex("\\s+")); types[l[0]] = t
                val k = t.filter { it.substring(1) in keep[l[0]].orEmpty() }
                out.append("%-60s%s\n".format("%c  %3d ".format(l[0], k.size) + k.joinToString("") { " $it" }, "SYS / # / OBS TYPES"))
            } else out.append(l).append('\n')
            i++
        }
        out.append(lines[i++]).append('\n')
        while (i < lines.size) {
            val l = lines[i]
            if (!l.startsWith(">")) { i++; continue }
            val n = l.substring(32, 35).trim().toInt(); val rows = ArrayList<String>()
            for (r in lines.subList(i + 1, i + 1 + n)) {
                val t = types[r[0]] ?: continue
                val f = t.indices.filter { t[it].substring(1) in keep[r[0]].orEmpty() }.joinToString("") { m -> r.padEnd(3 + 16 * t.size).substring(3 + 16 * m, 19 + 16 * m) }
                if (f.isNotBlank()) rows.add((r.take(3) + f).trimEnd())
            }
            out.append(l.substring(0, 32)).append("%3d".format(rows.size)).append(l.drop(35)).append('\n')
            rows.forEach { out.append(it).append('\n') }
            i += n + 1
        }
        return out.toString()
    }

    private data class Run(val fixes: List<GnssFix>, val method: String, val ionoFree: Boolean)

    private fun replay(dir: File, o: RtkOptions): Run {
        val link = RawReplayLink(dir, o, 0.0)
        val model = GnssModel()
        val buf = ByteArray(8192); val text = StringBuilder()
        try { while (true) { val n = link.read(buf); text.append(String(buf, 0, n, Charsets.US_ASCII)) } } catch (e: IOException) { assertEquals(NmeaReplayLink.FINISHED, e.message) }
        val st = PhoneRtkLink.state.value
        link.close()
        dir.deleteRecursively()
        val fixes = text.lines().mapNotNull { l -> Nmea.parse(l)?.let { model.accept(it) } }
        return Run(fixes, st.source, st.ionoFree)
    }

    /** Horizontal and vertical error (m) of a fix against the reference. */
    private fun error(f: GnssFix): Pair<Double, Double> {
        val x = Geo.geodeticToEcef(f.latitude, f.longitude, f.ellipsoidHeight!!)
        val d = DoubleArray(3) { x[it] - reference[it] }
        val r = Geo.ecefToGeodetic(reference[0], reference[1], reference[2])
        val la = Math.toRadians(r.latitude); val lo = Math.toRadians(r.longitude)
        val e = -sin(lo) * d[0] + cos(lo) * d[1]
        val n = -sin(la) * cos(lo) * d[0] - sin(la) * sin(lo) * d[1] + cos(la) * d[2]
        val u = cos(la) * cos(lo) * d[0] + cos(la) * sin(lo) * d[1] + sin(la) * d[2]
        return hypot(e, n) to u
    }

    private val gal = RtkOptions(mode = RtkMode.KINEMATIC, ppp = true, glonass = false, beidou = false, qzss = false, errorRatio = 100.0,
        elevationMaskDeg = 10.0)

    @Test fun hasPppReachesDecimetres() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val r = replay(session(), gal)
        assertTrue("PPP epochs ${r.fixes.size}", r.fixes.size >= 700)
        // PPP is differential in GGA (2), never RTK
        assertTrue(r.fixes.all { it.quality == FixQuality.DGNSS })
        // after 30 minutes: within 0.3 m horizontally and 0.5 m vertically, every epoch
        val late = r.fixes.drop(360)
        val worst = late.maxOf { error(it).first }
        val worstUp = late.maxOf { kotlin.math.abs(error(it).second) }
        val mean = late.map { error(it).first }.average()
        println("HAS PPP after 30 min: worst horizontal %.3f m, mean %.3f m, worst vertical %.3f m".format(worst, mean, worstUp))
        assertTrue("worst horizontal %.3f m, mean %.3f m".format(worst, mean), worst < 0.3)
        assertTrue("worst vertical %.3f m".format(worstUp), worstUp < 0.5)
        assertEquals(PhoneRtkLink.METHOD_PPP, r.method)
        assertTrue(r.ionoFree)
    }

    /** Signals of a dual-frequency phone: GPS L1 C/A + L5, Galileo E1 + E5a (no L2 / E5b). */
    @Test fun phoneSignalsL1L5() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val r = replay(session(mapOf('G' to setOf("1C", "5Q"), 'E' to setOf("1C", "5Q"))), gal.copy(errorRatio = 300.0, elevationMaskDeg = 15.0))
        val late = r.fixes.drop(360).map { error(it).first }
        assertTrue("PPP epochs ${r.fixes.size}", r.fixes.size >= 700)
        println("L1/L5 PPP after 30 min: worst horizontal %.3f m, mean %.3f m".format(late.max(), late.average()))
        assertTrue("worst horizontal %.3f m".format(late.max()), late.max() < 0.8)
        assertTrue(r.ionoFree)
    }

    /** An L1-only phone: the ionosphere-free combination has no satellites, so the app falls back to the broadcast model. */
    @Test fun l1OnlyFallsBackToBroadcastIonosphere() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val r = replay(session(mapOf('G' to setOf("1C"), 'E' to setOf("1C"))), gal.copy(errorRatio = 300.0, elevationMaskDeg = 15.0))
        assertTrue(!r.ionoFree)
        val late = r.fixes.drop(360).map { error(it).first }
        assertTrue("PPP epochs ${r.fixes.size}", r.fixes.size >= 600)
        println("L1-only PPP after 30 min: worst horizontal %.3f m, mean %.3f m".format(late.max(), late.average()))
        assertTrue("worst horizontal %.3f m".format(late.max()), late.max() < 1.8)
    }

    @Test fun ssrEpochTimesAndChooser() {
        val frames = RtcmFrames.split(resource("idd2023229c.rtc.gz"))
        val types = frames.groupingBy { RtcmFrames.type(it) }.eachCount()
        assertEquals(setOf(1019, 1046, 1059, 1060, 1242, 1243), types.keys)
        // 17 Aug 2023 02:00 GPST = Thursday 02:00 -> 4 days 2 h into the week
        val first = frames.first { RtcmFrames.type(it) == 1060 }
        val tow = RtcmFrames.epochTowMs(first, 4 * 86_400_000L + 7_200_000L)!! / 1000
        assertTrue("SSR epoch $tow", tow in (4 * 86400 + 7200 - 120)..(4 * 86400 + 7200 + 120))
        val c = IonoChooser(true)
        repeat(89) { assertEquals(null, c.update(1)) }
        assertEquals(false, c.update(0))
        repeat(59) { assertEquals(null, c.update(8)) }
        assertEquals(true, c.update(8))
        val conv = PppConvergence()
        fun sol(min: Double, sd: Double) = RtkSolution(RtkStatus.PPP, 2275, 352800.0 + min * 60, 0.0, 0.0, 0.0, sd, sd, sd, 0.0, 0.0, 0.0, 10, 0.0, 0.0, 0.0, 0.0)
        assertTrue(conv.converging(sol(0.0, 0.1)))
        assertTrue(conv.converging(sol(16.0, 0.5)))
        assertTrue(!conv.converging(sol(16.0, 0.2)))
    }
}
