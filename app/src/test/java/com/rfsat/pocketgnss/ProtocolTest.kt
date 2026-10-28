package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.core.Statistics
import com.rfsat.pocketgnss.core.Reference
import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.Dechunker
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaMsg
import com.rfsat.pocketgnss.protocol.NmeaSentence
import com.rfsat.pocketgnss.protocol.Ntrip
import com.rfsat.pocketgnss.protocol.NtripConfig
import com.rfsat.pocketgnss.protocol.Rtcm
import com.rfsat.pocketgnss.protocol.RtcmFrame
import com.rfsat.pocketgnss.protocol.StartType
import com.rfsat.pocketgnss.protocol.StreamSplitter
import com.rfsat.pocketgnss.protocol.Ubx
import com.rfsat.pocketgnss.protocol.UbxFrame
import com.rfsat.pocketgnss.protocol.UbxLayer
import com.rfsat.pocketgnss.protocol.UbxPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentences recorded from the RFSAT AgriBIT receiver (D3.5 §3 and §4.3), RTK fixed, 15 May 2023. */
object D35 {
    val epoch = listOf(
        "\$GNRMC,113343.60,A,3745.51719,N,02307.16238,E,0.021,,150523,4.87,E,R,V*53",
        "\$GNVTG,,T,,M,0.021,N,0.039,K,D*31",
        "\$GNGGA,113343.60,3745.51719,N,02307.16238,E,4,12,0.78,7.7,M,32.7,M,,0089*4B",
        "\$GNGSA,A,3,02,10,18,23,26,29,27,15,16,,,,1.62,0.78,1.42,1*0E",
        "\$GNGSA,A,3,84,82,83,74,73,67,68,,,,,,1.62,0.78,1.42,2*0A",
        "\$GNGSA,A,3,02,30,36,15,25,34,,,,,,,1.62,0.78,1.42,3*0E",
        "\$GNGSA,A,3,20,19,08,22,26,13,29,35,,,,,1.62,0.78,1.42,4*00",
        "\$GPGSV,3,1,12,02,38,259,42,10,35,178,44,15,11,078,41,16,51,302,37,1*65",
        "\$GPGSV,3,2,12,18,62,032,35,23,59,135,46,26,64,232,45,27,28,303,25,1*68",
        "\$GPGSV,3,3,12,29,21,096,41,36,45,166,35,40,35,134,36,49,42,208,41,1*63",
        "\$GPGSV,2,1,08,10,35,178,40,15,11,078,29,18,62,032,36,23,59,135,41,6*63",
        "\$GPGSV,2,2,08,26,64,232,43,27,28,303,29,29,21,096,40,31,11,227,15,6*6F",
        "\$GLGSV,3,1,09,67,21,047,41,68,28,102,44,69,08,149,46,73,45,228,41,1*71",
        "\$GLGSV,3,2,09,74,43,298,30,80,08,183,35,82,11,063,35,83,44,030,28,1*73",
        "\$GLGSV,3,3,09,84,41,295,20,1*44",
        "\$GAGSV,2,1,08,02,54,186,47,05,03,136,28,09,10,088,39,15,17,250,41,2*78",
        "\$GAGSV,2,2,08,25,10,158,44,30,54,294,31,34,64,282,39,36,50,040,35,2*7E",
        "\$GBGSV,2,1,08,08,21,036,32,13,34,041,31,19,36,113,42,20,24,057,37,1*7A",
        "\$GBGSV,2,2,08,22,14,166,23,26,36,240,42,29,30,074,37,35,68,357,36,1*79",
        "\$GBGSV,1,1,02,08,21,036,38,13,34,041,22,3*73",
        "\$GNGLL,3745.51719,N,02307.16238,E,113343.60,A,D*75",
    )
    const val GNS = "\$GNGNS,132220.00,3756.48794,N,02352.43721,E,RRRRNN,23,0.82,134.3,33.1,1.0,0026,V*32"
    const val DTM = "\$GNDTM,W84,,0.0,N,0.0,E,0.0,W84*71"
    const val GSV_NO_SNR = "\$GPGSV,1,1,04,16,58,250,,21,02,263,,32,03,160,,49,42,209,,0*6F"
}

class ProtocolTest {

    @Test fun checksumsOfRecordedSentencesAreValid() {
        for (l in D35.epoch + D35.GNS + D35.DTM + D35.GSV_NO_SNR) assertTrue(l, Nmea.checksumOk(l))
        assertFalse(Nmea.checksumOk("\$GNGGA,113343.60,3745.51719,N,02307.16238,E,4,12,0.78,7.7,M,32.7,M,,0089*4C"))
        assertFalse(Nmea.checksumOk("\$GNGGA,113343.60"))
    }

    @Test fun ggaRtkFixed() {
        val g = Nmea.parse(D35.epoch[2]) as NmeaSentence.Gga
        assertEquals(FixQuality.RTK_FIXED, g.quality)
        assertEquals(37 + 45.51719 / 60, g.latitude!!, 1e-12)
        assertEquals(23 + 7.16238 / 60, g.longitude!!, 1e-12)
        assertEquals(12, g.satellitesUsed)
        assertEquals(0.78, g.hdop!!, 1e-9)
        assertEquals(7.7, g.altitudeMsl!!, 1e-9)
        assertEquals(32.7, g.geoidSeparation!!, 1e-9)
        assertEquals("0089", g.diffStation)
        assertEquals(11, g.time!!.hour); assertEquals(33, g.time!!.minute); assertEquals(43.6, g.time!!.second, 1e-9)
    }

    @Test fun rmcModeAndDate() {
        val r = Nmea.parse(D35.epoch[0]) as NmeaSentence.Rmc
        assertTrue(r.valid)
        assertEquals('R', r.mode)
        assertEquals(15, r.date!!.day); assertEquals(5, r.date!!.month); assertEquals(2023, r.date!!.year)
        assertEquals(4.87, r.magVariation!!, 1e-9)
    }

    @Test fun gnsModesPerConstellation() {
        val g = Nmea.parse(D35.GNS) as NmeaSentence.Gns
        assertEquals("RRRRNN", g.modes)
        assertEquals(23, g.satellitesUsed)
        assertEquals(1.0, g.diffAgeSec!!, 1e-9)
    }

    @Test fun gsaSystemIds() {
        val sys = D35.epoch.filter { it.contains("GSA") }.map { (Nmea.parse(it) as NmeaSentence.Gsa).system }
        assertEquals(listOf(Constellation.GPS, Constellation.GLONASS, Constellation.GALILEO, Constellation.BEIDOU), sys)
    }

    @Test fun gsvSignalIdsAndEmptyCn0() {
        val a = Nmea.parse(D35.epoch[10]) as NmeaSentence.Gsv
        assertEquals("6", a.signalId)
        assertEquals(4, a.satellites.size)
        val b = Nmea.parse(D35.GSV_NO_SNR) as NmeaSentence.Gsv
        assertEquals("0", b.signalId)
        assertNull(b.satellites[0].cn0)
        assertEquals(49, b.satellites[3].svid)
        assertEquals("EGNOS", Nmea.sbasSystem(Nmea.sbasPrn(49)))   // S136
        assertEquals("B2I", Nmea.signalName(Constellation.BEIDOU, "B"))
    }

    @Test fun datumIsWgs84() {
        assertEquals("W84", (Nmea.parse(D35.DTM) as NmeaSentence.Dtm).datum)
    }

    @Test fun modelAssemblesRtkFixedEpochWithSatellites() {
        val m = GnssModel { 1000L }
        val fixes = D35.epoch.mapNotNull { Nmea.parse(it)?.let(m::accept) }
        assertEquals(1, fixes.size)
        val f = fixes[0]
        assertEquals(FixQuality.RTK_FIXED, f.quality)
        assertEquals(0.021 * 0.514444, f.speedMs!!, 1e-9)
        assertEquals(40.4, f.ellipsoidHeight!!, 1e-9)
        assertTrue("estimated RTK accuracy at cm level", f.horizontalAccuracy < 0.02)
        val sats = m.satellites()
        assertEquals(12 + 1 + 9 + 8 + 8, sats.size) // GPS 12 (incl. 3 SBAS), GPS L2 adds G31, GLO 9, GAL 8, BDS 8
        val g10 = sats.first { it.constellation == Constellation.GPS && it.svid == 10 }
        assertEquals(44, g10.signals["L1C/A"]); assertEquals(40, g10.signals["L2CL"])
        assertTrue(g10.dualFrequency); assertTrue(g10.used)
        assertTrue(sats.any { it.constellation == Constellation.SBAS && it.label == "S123" })
        assertFalse(sats.first { it.label == "G31" }.used)
    }

    @Test fun formatRoundTrip() {
        val (la, ns) = Nmea.formatLat(-37.758619833, 7)
        val (lo, ew) = Nmea.formatLon(23.119373, 7)
        assertEquals("S", ns); assertEquals("E", ew)
        assertEquals(-37.758619833, Nmea.lat(la, ns)!!, 1e-9)
        assertEquals(23.119373, Nmea.lon(lo, ew)!!, 1e-9)
        val gga = Ntrip.gga(41623.6, 37.75862, 23.11937, 7.7)
        assertTrue(Nmea.checksumOk(gga))
        val p = Nmea.parse(gga) as NmeaSentence.Gga
        assertEquals(37.75862, p.latitude!!, 1e-7)
    }

    @Test fun ubxFramesAndChecksums() {
        // MON-VER poll: B5 62 0A 04 00 00 0E 34 (u-blox reference value)
        assertEquals("B5620A0400000E34", Ubx.pollMonVer().hex())
        // CFG-RST cold start, GNSS-only: B5 62 06 04 04 00 FF FF 02 00 0E 61
        assertEquals("B56206040400FFFF02000E61", Ubx.reset(StartType.COLD).hex())
        assertEquals(1, Ubx.valueSize(NmeaMsg.GGA.key(UbxPort.USB)))
        assertEquals(0x209100bdL, NmeaMsg.GGA.key(UbxPort.USB))
        assertEquals(0x209100aeL, NmeaMsg.RMC.key(UbxPort.USB))
        assertEquals(0x209100bbL, NmeaMsg.GGA.key(UbxPort.UART1))
        assertEquals(2, Ubx.valueSize(Ubx.KEY_RATE_MEAS))
        val set = Ubx.valset(Ubx.rateKeys(20), UbxLayer.ALL)
        // header 6 + payload 4 + 2*(4+2) + checksum 2
        assertEquals(24, set.size)
        assertEquals(0x07, set[7].toInt())
        assertEquals(50, (set[14].toInt() and 0xFF) or ((set[15].toInt() and 0xFF) shl 8))
    }

    @Test fun valgetResponseDecodes() {
        val payload = byteArrayOf(1, 0, 0, 0,
            0xbd.toByte(), 0x00, 0x91.toByte(), 0x20, 1,
            0x01, 0x00, 0x21, 0x30, 0x64, 0x00)
        val v = Ubx.parseValget(payload)
        assertEquals(1L, v[0x209100bdL]); assertEquals(100L, v[Ubx.KEY_RATE_MEAS])
    }

    @Test fun monVerParses() {
        val p = ByteArray(40 + 3 * 30)
        "EXT CORE 1.00 (a59682)".toByteArray().copyInto(p, 0)
        "00190000".toByteArray().copyInto(p, 30)
        "FWVER=HPG 1.32".toByteArray().copyInto(p, 40)
        "PROTVER=27.31".toByteArray().copyInto(p, 70)
        "MOD=ZED-F9P".toByteArray().copyInto(p, 100)
        val v = Ubx.parseMonVer(p)!!
        assertEquals("ZED-F9P", v.module); assertEquals("HPG 1.32", v.firmware); assertEquals("27.31", v.protocol)
    }

    @Test fun rtcmCrcAndBasePosition() {
        // 1005 for a point in Attica built from known ECEF coordinates.
        val ecef = Geo.geodeticToEcef(37.7586, 23.1194, 40.4)
        val payload = BitWriter().apply {
            put(1005, 12); put(89, 12); put(0, 6); put(1, 1); put(1, 1); put(1, 1); put(0, 1)
            put(Math.round(ecef[0] / 0.0001), 38); put(0, 1); put(0, 1)
            put(Math.round(ecef[1] / 0.0001), 38); put(0, 2)
            put(Math.round(ecef[2] / 0.0001), 38)
        }.bytes()
        val frame = Rtcm.frame(payload)
        assertEquals(1005, Rtcm.type(frame)); assertEquals(89, Rtcm.stationId(frame))
        val base = Rtcm.baseStation(frame)!!
        assertEquals(37.7586, base.geodetic.latitude, 1e-8)
        assertEquals(23.1194, base.geodetic.longitude, 1e-8)
        assertEquals(40.4, base.geodetic.height, 1e-3)
        // CRC-24Q check value of "123456789" is 0xCDE703
        assertEquals(0xCDE703, Rtcm.crc24q("123456789".toByteArray(), 0, 9))
    }

    @Test fun splitterSeparatesMixedStream() {
        val lines = ArrayList<String>(); val ubx = ArrayList<UbxFrame>(); val rtcm = ArrayList<RtcmFrame>()
        val sp = StreamSplitter({ lines.add(it) }, { ubx.add(it) }, { rtcm.add(it) })
        val ack = Ubx.frame(Ubx.CLS_ACK, Ubx.ID_ACK_ACK, byteArrayOf(0x06, 0x8A.toByte()))
        val rt = Rtcm.frame(byteArrayOf(0x43, 0x50, 0x00, 0x11))
        val data = "ets Jun  8 2016 boot\r\n".toByteArray() + (D35.epoch[2] + "\r\n").toByteArray() + ack + rt +
            (D35.epoch[0] + "\r\n").toByteArray() + "\$GNGGA,broken*00\r\n".toByteArray()
        // Feed in odd-sized pieces, as USB / Bluetooth deliver them.
        var i = 0
        while (i < data.size) { val n = minOf(7, data.size - i); sp.feed(data.copyOfRange(i, i + n)); i += n }
        assertEquals(2, lines.size)
        assertEquals(1, ubx.size); assertEquals(Ubx.CLS_CFG to Ubx.ID_CFG_VALSET, Ubx.ackedOf(ubx[0]))
        assertEquals(1, rtcm.size); assertEquals(1077, rtcm[0].type)
        assertEquals(1L, sp.badChecksums)
        assertTrue(sp.garbageBytes > 10)
    }

    @Test fun ntripRequestAndReplies() {
        val c = NtripConfig("gr.nrtk.eu", 2101, "MAC_RTCM32", "user", "pass", version2 = true)
        val r = Ntrip.request(c)
        assertTrue(r.startsWith("GET /MAC_RTCM32 HTTP/1.1\r\n"))
        assertTrue(r.contains("Ntrip-Version: Ntrip/2.0\r\n"))
        assertTrue(r.contains("Authorization: Basic dXNlcjpwYXNz\r\n"))
        assertTrue(r.endsWith("\r\n\r\n"))
        assertEquals(Ntrip.Reply.STREAM, Ntrip.parseHeader("ICY 200 OK").reply)
        assertEquals(Ntrip.Reply.SOURCETABLE, Ntrip.parseHeader("SOURCETABLE 200 OK\r\nContent-Type: text/plain").reply)
        val h = Ntrip.parseHeader("HTTP/1.1 200 OK\r\nContent-Type: gnss/data\r\nTransfer-Encoding: chunked")
        assertEquals(Ntrip.Reply.STREAM, h.reply); assertTrue(h.chunked)
        assertEquals(Ntrip.Reply.UNAUTHORIZED, Ntrip.parseHeader("HTTP/1.1 401 Unauthorized").reply)
    }

    @Test fun sourcetable() {
        val t = "SOURCETABLE 200 OK\r\nCAS;euref-ip.net;2101;EUREF;BKG;0;DEU;50.12;8.69;;\r\n" +
            "STR;NOA100GRC0;Athens;RTCM 3.3;1006(10),1077(1);2;GPS+GLO+GAL+BDS;EUREF;GRC;38.05;23.86;0;0;LEICA;none;B;N;5400;\r\n" +
            "STR;VRS3;Network;RTCM 3.2;;2;GPS+GLO;NRTK;GRC;37.90;23.70;1;1;sNTRIP;none;B;Y;9600;\r\nENDSOURCETABLE\r\n"
        val m = Ntrip.parseSourcetable(t)
        assertEquals(2, m.size)
        assertEquals("NOA100GRC0", m[0].name); assertFalse(m[0].needsGga); assertEquals(38.05, m[0].latitude!!, 1e-9)
        assertTrue(m[1].needsGga); assertTrue(m[1].fee)
    }

    @Test fun dechunker() {
        val out = java.io.ByteArrayOutputStream()
        val d = Dechunker { b, n -> out.write(b, 0, n) }
        val raw = "4\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\n\r\n".toByteArray()
        d.feed(raw.copyOfRange(0, 9), 9); d.feed(raw.copyOfRange(9, raw.size), raw.size - 9)
        assertEquals("Wikipedia in\r\n\r\nchunks.", out.toString())
    }

    @Test fun geodesyRoundTripAndStats() {
        val e = Geo.geodeticToEcef(40.76, 22.05, 120.0)
        val g = Geo.ecefToGeodetic(e[0], e[1], e[2])
        assertEquals(40.76, g.latitude, 1e-10); assertEquals(120.0, g.height, 1e-6)
        val (la, lo) = Geo.offset(40.76, 22.05, 3.0, 4.0)
        val enu = Geo.enu(40.76, 22.05, 120.0, la, lo, 120.0)
        assertEquals(3.0, enu[0], 1e-4); assertEquals(4.0, enu[1], 1e-4)
        assertEquals(5.0, Geo.distance(40.76, 22.05, la, lo), 0.02)
        // Four points at 1 cm east/west/north/south around the reference.
        val pts = listOf(0.01 to 0.0, -0.01 to 0.0, 0.0 to 0.01, 0.0 to -0.01).map { (de, dn) ->
            val (a, b) = Geo.offset(40.76, 22.05, de, dn); doubleArrayOf(a, b, 120.0)
        }
        val s = Statistics.compute(pts, Reference(40.76, 22.05, 120.0))!!
        assertEquals(0.01, s.cep50, 1e-5); assertEquals(0.0, s.biasHorizontal!!, 1e-6); assertEquals(0.01, s.rmsHorizontal!!, 1e-5)
    }
}

fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

/** MSB-first bit packer for building RTCM test messages. */
class BitWriter {
    private val bits = ArrayList<Boolean>()
    fun put(v: Long, n: Int) { for (i in n - 1 downTo 0) bits.add((v shr i) and 1L == 1L) }
    fun put(v: Int, n: Int) = put(v.toLong(), n)
    fun bytes(): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, b -> if (b) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte() }
        return out
    }

    @Test fun galileoHasCasterIsNtrip2OverTls() {
        val has = com.rfsat.pocketgnss.data.CasterProfiles.presets.first { it.name.startsWith("Galileo HAS") }.config
        assertTrue(has.secure && has.port == 443 && has.mountpoint == "SSRA00EUH0" && has.ggaIntervalSec == 0)
        // port 443 alone means TLS and NTRIP 2, even with both switches left off
        val c = com.rfsat.pocketgnss.protocol.NtripConfig(host = "ntrip.gsc-europa.eu", port = 443, mountpoint = "SSRA00EUH0", user = "u", password = "p",
            version2 = false, tls = false)
        assertTrue(c.secure)
        val req = com.rfsat.pocketgnss.protocol.Ntrip.request(c)
        assertTrue(req.startsWith("GET /SSRA00EUH0 HTTP/1.1\r\n"))
        assertTrue(req.contains("Host: ntrip.gsc-europa.eu\r\n") && req.contains("Ntrip-Version: Ntrip/2.0"))
        assertTrue(!com.rfsat.pocketgnss.protocol.NtripConfig(host = "x", port = 2101, mountpoint = "m").secure)
    }
}
