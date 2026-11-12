package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaSentence
import com.rfsat.pocketgnss.protocol.Quectel
import com.rfsat.pocketgnss.protocol.QuectelProfile
import com.rfsat.pocketgnss.link.ReceiverVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quectel LG290P commands and RFSAT status sentences (master documentation RFSAT-RTK-DOC-001, V5 and P-2). */
class QuectelTest {
    /** The command table of the master documentation (V5 "Commands used"), checksums included. */
    private val documented = mapOf(
        "PQTMVERNO" to "\$PQTMVERNO*58",
        "PQTMCFGRCVRMODE,W,1" to "\$PQTMCFGRCVRMODE,W,1*2A",
        "PQTMCFGFIXRATE,W,1000" to "\$PQTMCFGFIXRATE,W,1000*59",
        "PQTMCFGFIXRATE,W,100" to "\$PQTMCFGFIXRATE,W,100*69",
        "PQTMCFGMSGRATE,W,1,3,GGA,1" to "\$PQTMCFGMSGRATE,W,1,3,GGA,1*08",
        "PQTMCFGMSGRATE,W,1,3,RMC,1" to "\$PQTMCFGMSGRATE,W,1,3,RMC,1*15",
        "PQTMCFGMSGRATE,W,1,3,GSA,1" to "\$PQTMCFGMSGRATE,W,1,3,GSA,1*1C",
        "PQTMCFGMSGRATE,W,1,3,GSV,1" to "\$PQTMCFGMSGRATE,W,1,3,GSV,1*0B",
        "PQTMCFGMSGRATE,W,1,3,GSV,0" to "\$PQTMCFGMSGRATE,W,1,3,GSV,0*0A",
        "PQTMCFGMSGRATE,W,1,3,GST,1" to "\$PQTMCFGMSGRATE,W,1,3,GST,1*09",
        "PQTMCFGMSGRATE,W,1,3,VTG,1" to "\$PQTMCFGMSGRATE,W,1,3,VTG,1*0C",
        "PQTMCFGMSGRATE,W,1,3,PQTMEPE,1,2" to "\$PQTMCFGMSGRATE,W,1,3,PQTMEPE,1,2*1F",
        "PQTMCFGMSGRATE,W,1,3,PQTMPPPNAV,1,1" to "\$PQTMCFGMSGRATE,W,1,3,PQTMPPPNAV,1,1*45",
        "PQTMCFGPPP,W,2,1,120,0.10,0.15" to "\$PQTMCFGPPP,W,2,1,120,0.10,0.15*68",
        "PQTMCFGPPP,W,0,0,0" to "\$PQTMCFGPPP,W,0,0,0*6D",
        "PQTMCFGNAVMODE,W,0" to "\$PQTMCFGNAVMODE,W,0*67",
        "PQTMCFGNAVMODE,W,14" to "\$PQTMCFGNAVMODE,W,14*52",
        "PQTMCFGELETHD,W,10" to "\$PQTMCFGELETHD,W,10*18",
        "PQTMCFGUART,W,3,460800" to "\$PQTMCFGUART,W,3,460800*0A",
        "PQTMCFGUART,W,3,38400" to "\$PQTMCFGUART,W,3,38400*3F",
        "PQTMCFGUART,W,3,115200" to "\$PQTMCFGUART,W,3,115200*07",
        "PQTMCFGUART,R,3" to "\$PQTMCFGUART,R,3*29",
        "PQTMCFGFIXRATE,R" to "\$PQTMCFGFIXRATE,R*71",
        "PQTMCFGMSGRATE,R,GGA" to "\$PQTMCFGMSGRATE,R,GGA*12",
        "PQTMSAVEPAR" to "\$PQTMSAVEPAR*5A",
        "PQTMRESTOREPAR" to "\$PQTMRESTOREPAR*13",
        "PQTMSRR" to "\$PQTMSRR*4B"
    )

    @Test fun commandsMatchTheDocumentation() {
        for ((body, line) in documented) assertEquals(body, line, Quectel.command(body))
    }

    @Test fun profilesUseOnlyDocumentedCommands() {
        for (p in QuectelProfile.entries) for (has in listOf(true, false)) for (c in p.commands(uart3 = true, has = has))
            assertTrue("$p: $c not in the documentation", c in documented)
        for (c in listOf(QuectelProfile.SAVE, QuectelProfile.RESTART, QuectelProfile.VERSION, QuectelProfile.FACTORY)) assertTrue(c in documented)
    }

    @Test fun profileOrder() {
        val g = QuectelProfile.GENERAL.commands()
        assertEquals("PQTMCFGRCVRMODE,W,1", g.first()); assertEquals("PQTMCFGNAVMODE,W,0", g.last())
        val t = QuectelProfile.TRACTOR.commands()
        assertEquals("PQTMCFGUART,W,3,38400", t.last())
        assertTrue("PQTMCFGFIXRATE,W,100" in t && "PQTMCFGNAVMODE,W,14" in t && "PQTMCFGELETHD,W,10" in t)
        // For the port in use: no UART3 prefix and no speed change (it would cut the link).
        val here = QuectelProfile.TRACTOR.commands(uart3 = false)
        assertTrue(here.none { it.startsWith("PQTMCFGUART") })
        assertTrue("PQTMCFGMSGRATE,W,GGA,1" in here)
        assertTrue("PQTMCFGPPP,W,0,0,0" in QuectelProfile.FLEET.commands(has = false))
    }

    @Test fun replies() {
        assertEquals(Quectel.Reply.Ok, Quectel.reply("PQTMSAVEPAR", "\$PQTMSAVEPAR,OK*72"))
        assertEquals(Quectel.Reply.Error("1"), Quectel.reply("PQTMCFGUART", "\$PQTMCFGUART,ERROR,1*15"))
        assertEquals(Quectel.Reply.Ok, Quectel.reply("PQTMCFGUART", "\$PQTMCFGUART,OK,3,460800*00"))
        val v = Quectel.reply("PQTMVERNO", "\$PQTMVERNO,LG290P03AANR02A02S,2025/01/10,10:00:00*00")
        assertTrue(v is Quectel.Reply.Value)
        assertNull(Quectel.reply("PQTMSRR", "\$PQTMSAVEPAR,OK*72"))
    }

    @Test fun parsers() {
        fun fields(line: String): List<String> = (Nmea.parse(Quectel.command(line)) as NmeaSentence.Other).fields
        val e = Quectel.epe(fields("PQTMEPE,2,0.012,0.010,0.025,0.016,0.030"))
        assertNotNull(e); assertEquals(0.016, e!!.horizontal, 1e-9); assertEquals(0.025, e.down, 1e-9)
        val ver = Quectel.version(fields("PQTMVERNO,LG290P03AANR02A02S,2025/01/10,10:00:00"))
        assertEquals("Quectel LG290P", ver!!.module)
        val r = Quectel.rfsat(fields("PRFSM,RTK,FIX,live,1.0,CAST1,0.012,0.020"))
        assertEquals("FIX", r!!.method); assertEquals("live", r.corrections); assertEquals(0.012, r.estH!!, 1e-9)
        // PQTMPPPNAV: solution at 10, standard deviations 18-20, differential id 23, age 24, satellites 27.
        val f = MutableList(28) { "" }
        f[0] = "1"; f[10] = "7"; f[18] = "0.10"; f[19] = "0.12"; f[20] = "0.20"; f[23] = "9002"; f[24] = "5"; f[27] = "24"
        val p = Quectel.pppNav(f)
        assertTrue(p!!.converged && p.galileoHas); assertEquals(24, p.satellitesUsed)
        assertNull(Quectel.pppNav(f.take(20)))
    }

    /** 1.13.1: the LG290P's $PQTMEPE (the figure on the RTK Everywhere display) is preferred over its larger GST. */
    @Test fun epePreferredOverGst() {
        fun parse(body: String) = Nmea.parse(Quectel.command(body))!!
        val gga = "GNGGA,120000.00,3758.0000,N,02345.0000,E,4,30,0.5,100.0,M,30.0,M,1.0,0001"
        val gst = "GNGST,120000.00,0.5,0.1,0.1,0,0.050,0.040,0.080"
        val withGst = com.rfsat.pocketgnss.core.GnssModel()
        withGst.accept(parse(gst))
        val f1 = withGst.accept(parse(gga))!!
        assertEquals(kotlin.math.hypot(0.05, 0.04), f1.horizontalAccuracy, 1e-9)
        val both = com.rfsat.pocketgnss.core.GnssModel()
        both.accept(parse(gst))
        both.epe = Quectel.epe(fields("PQTMEPE,2,0.004,0.003,0.008,0.005,0.009")); both.epeMs = System.currentTimeMillis()
        val f2 = both.accept(parse(gga))!!
        assertEquals(0.005, f2.horizontalAccuracy, 1e-9)
        assertTrue(f2.measuredAccuracy)
    }

    private fun fields(line: String): List<String> = (Nmea.parse(Quectel.command(line)) as NmeaSentence.Other).fields

    @Test fun variants() {
        assertEquals(1, ReceiverVariant.POSTCARDRTK.defaultUsbPort)
        assertEquals(0, ReceiverVariant.PICORTK.defaultUsbPort)
        assertTrue(ReceiverVariant.ROVERS.all { it.isRover })
        assertTrue(ReceiverVariant.AGRIBIT.none { it.isRover })
    }
}
