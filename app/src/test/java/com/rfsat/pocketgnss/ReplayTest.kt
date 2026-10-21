package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.link.NmeaReplayLink
import com.rfsat.pocketgnss.link.RawReplayLink
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.RtcmFrames
import com.rfsat.pocketgnss.raw.Band
import com.rfsat.pocketgnss.raw.RawEpoch
import com.rfsat.pocketgnss.raw.RinexHeaderInfo
import com.rfsat.pocketgnss.raw.RinexObsWriter
import com.rfsat.pocketgnss.raw.RinexReader
import com.rfsat.pocketgnss.raw.SignalObs
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Session replay: RINEX read-back, RTCM epoch times, and a phone raw session replayed through the RTK engine. */
class ReplayTest {
    private val reference = doubleArrayOf(-3976219.1892, 3382371.6070, 3652511.1670)

    /** The RtkEngineTest data written as a phone raw session folder (raw.obs + base.rtcm3). */
    fun session(): File {
        val dir = Files.createTempDirectory("raw_replay").toFile()
        val lines = javaClass.getResourceAsStream("/rtk/two_station_3km.txt")!!.bufferedReader().readLines()
        val w = RinexObsWriter(File(dir, "raw.obs"), RinexHeaderInfo())
        val rtcm = ByteArrayOutputStream()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("B ")) { val h = l.substring(2); rtcm.write(ByteArray(h.length / 2) { k -> h.substring(2 * k, 2 * k + 2).toInt(16).toByte() }); i++ }
            else if (l.startsWith("R ")) {
                val (_, week, tow, n) = l.split(' ')
                val obs = (1..n.toInt()).map { k ->
                    val f = lines[i + k].split(' ')
                    SignalObs(f[0][0], f[1].toInt(), f[2], f[3].toDouble().takeIf { it != 0.0 }, f[4].toDouble().takeIf { it != 0.0 },
                        f[5].toDouble(), f[6].toDouble(), f[7].toInt(), Band.L1)
                }
                w.add(RawEpoch(week.toInt(), tow.toDouble(), obs, false)); i += n.toInt() + 1
            } else i++
        }
        w.finish(null)
        File(dir, "base.rtcm3").writeBytes(rtcm.toByteArray())
        return dir
    }

    @Test fun rinexReadsBackAndRtcmTimes() {
        val dir = session()
        val epochs = RinexReader(File(dir, "raw.obs").bufferedReader()).epochs().toList()
        assertEquals(519600.001, epochs[40].towSeconds, 1e-9)
        assertEquals(120, epochs.size)
        assertEquals(1316, epochs[0].gpsWeek)
        assertTrue(epochs.all { it.obs.isNotEmpty() })
        val frames = RtcmFrames.split(File(dir, "base.rtcm3").readBytes())
        val msm = frames.filter { RtcmFrames.type(it) == 1077 }
        assertTrue(msm.size >= 100)
        val t = RtcmFrames.epochTowMs(msm.first(), (epochs[0].towSeconds * 1000).toLong())!!
        assertTrue("first base epoch $t vs rover ${epochs[0].towSeconds}", kotlin.math.abs(t / 1000.0 - epochs[0].towSeconds) <= 60)
        dir.deleteRecursively()
    }

    @Test fun rawSessionReplaysThroughPhoneRtk() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val dir = session()
        val link = RawReplayLink(dir, RtkOptions(glonass = false, galileo = false, beidou = false, qzss = false), 0.0)
        val model = GnssModel()
        val buf = ByteArray(8192); val text = StringBuilder()
        try { while (true) { val n = link.read(buf); text.append(String(buf, 0, n, Charsets.US_ASCII)) } } catch (e: IOException) { assertEquals(NmeaReplayLink.FINISHED, e.message) }
        link.close()
        var fixed = 0; var last: com.rfsat.pocketgnss.core.GnssFix? = null
        for (l in text.lines()) Nmea.parse(l)?.let { m -> model.accept(m)?.let { f -> last = f; if (f.quality == FixQuality.RTK_FIXED) fixed++ } }
        assertTrue("fixed epochs $fixed", fixed >= 60)
        val f = last!!
        val xyz = Geo.geodeticToEcef(f.latitude, f.longitude, f.ellipsoidHeight!!)
        val err = Math.sqrt((0..2).sumOf { (xyz[it] - reference[it]).let { d -> d * d } })
        assertTrue("distance to reference %.4f m".format(err), err < 0.03)
        dir.deleteRecursively()
    }

    @Test fun nmeaReplayPacesByTime() {
        val dir = Files.createTempDirectory("session_x").toFile()
        val f = File(dir, "raw.nmea")
        f.writeText((0 until 3).joinToString("") { k ->
            Nmea.sentence("GPGGA,1200%02d.00,4045.4020,N,02207.2780,E,4,12,0.7,38.0,M,36.0,M,0.6,0089".format(k)) + "\r\n" +
                Nmea.sentence("GPRMC,1200%02d.00,A,4045.4020,N,02207.2780,E,0.0,0.0,211026,,,R".format(k)) + "\r\n"
        })
        val link = NmeaReplayLink(f, 20.0)
        val buf = ByteArray(4096); val out = StringBuilder()
        val t0 = System.nanoTime()
        try { while (true) { val n = link.read(buf); out.append(String(buf, 0, n)) } } catch (_: IOException) {}
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(6, out.lines().count { it.startsWith("$") })
        assertTrue("2 s at 20x took $ms ms", ms in 80.0..1000.0)
        assertNotNull(NmeaReplayLink.timeOf(out.lines().first()))
        dir.deleteRecursively()
    }

    /** The ephemeris stream: 1019 frames through the navigation input give orbits but no base data. */
    @Test fun ephemerisStreamInput() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        val dir = session()
        val o = RtkOptions(glonass = false, galileo = false, beidou = false, qzss = false)
        RtkNative.init(o.mode.code, o.navsys, o.elevationMaskDeg, o.snrMaskDbHz, o.arMode, 0, o.errorRatio, o.arRatio, o.maxBaseAgeSec, o.minFixSats)
        RtkNative.setTime(1316, 518400.0)
        for (f in RtcmFrames.split(File(dir, "base.rtcm3").readBytes())) RtkNative.inputRtcmNav(f, 0, f.size)
        val b = com.rfsat.pocketgnss.rtk.RtkBase.from(DoubleArray(com.rfsat.pocketgnss.rtk.RtkBase.SIZE).also { RtkNative.base(it) })
        RtkNative.free()
        assertTrue("ephemerides ${b.ephemerides}", b.ephemerides >= 8)
        assertEquals(0, b.satellites)
        assertTrue(!b.hasPosition)
        dir.deleteRecursively()
    }
}
