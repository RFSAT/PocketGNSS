package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.core.NtripCaster
import com.rfsat.pocketgnss.core.PositionJson
import com.rfsat.pocketgnss.data.Json
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.Ntrip
import com.rfsat.pocketgnss.protocol.Rtcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

class CasterTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun request(port: Int, req: String, readFor: Long = 800, onOpen: (() -> Unit)? = null): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 300
            s.getOutputStream().write(req.toByteArray()); s.getOutputStream().flush()
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + readFor
            var opened = false
            while (System.currentTimeMillis() < end) {
                val n = try { s.getInputStream().read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
                if (n < 0) break
                out.write(buf, 0, n)
                if (!opened && out.toString(Charsets.ISO_8859_1).contains("\r\n\r\n")) { opened = true; onOpen?.invoke() }
            }
            return out.toString(Charsets.ISO_8859_1)
        }
    }

    @Test fun servesSourcetableAndStreams() {
        val c = NtripCaster(); val port = freePort()
        c.start(port, "FARM", "rover", "s3cret"); c.position = 40.75 to 22.12
        assertTrue(c.running)
        val table = request(port, "GET / HTTP/1.0\r\nUser-Agent: NTRIP test\r\n\r\n")
        assertTrue(table, table.startsWith("SOURCETABLE 200 OK"))
        val mp = Ntrip.parseSourcetable(table.substringAfter("\r\n\r\n")).single()
        assertEquals("FARM", mp.name); assertEquals(40.75, mp.latitude!!, 1e-9)
        // wrong password
        val bad = Base64.getEncoder().encodeToString("rover:x".toByteArray())
        assertTrue(request(port, "GET /FARM HTTP/1.0\r\nAuthorization: Basic $bad\r\n\r\n").startsWith("HTTP/1.0 401"))
        // NTRIP 1: ICY 200 OK, raw bytes
        val good = Base64.getEncoder().encodeToString("rover:s3cret".toByteArray())
        val frame = Rtcm.frame(ByteArray(19) { (it * 7).toByte() })
        val v1 = request(port, "GET /FARM HTTP/1.0\r\nAuthorization: Basic $good\r\n\r\n", 1200) { Thread.sleep(150); c.send(frame) }
        assertTrue(v1, v1.startsWith("ICY 200 OK\r\n\r\n"))
        assertEquals(frame.toList(), v1.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1).toList())
        // NTRIP 2: chunked
        val v2 = request(port, "GET /FARM HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\nAuthorization: Basic $good\r\n\r\n", 1200) { Thread.sleep(150); c.send(frame) }
        assertTrue(v2, v2.startsWith("HTTP/1.1 200 OK") && v2.contains("Transfer-Encoding: chunked"))
        val body = v2.substringAfter("\r\n\r\n")
        assertTrue(body, body.startsWith("%x\r\n".format(frame.size)))
        c.stop()
        assertTrue(!c.running)
    }

    @Test fun positionJson() {
        val m = GnssModel()
        val f = listOf("GPGGA,120000.00,4045.4020000,N,02207.2780000,E,4,12,0.7,38.000,M,36.000,M,0.6,0089",
            "GPRMC,120000.00,A,4045.4020000,N,02207.2780000,E,0.0,0.0,211026,,,R").mapNotNull { Nmea.parse(Nmea.sentence(it))?.let(m::accept) }.last()
        val j = Json(PositionJson.of(f, com.rfsat.pocketgnss.protocol.Grid.utm(f.latitude, f.longitude), "RTK fixed")).parse() as Map<*, *>
        assertEquals("RTK_FIXED", j["fix"]); assertEquals(74.0, j["h_ell"] as Double, 1e-6); assertEquals(40.7567, j["lat"] as Double, 1e-9)
        assertEquals("12:00:00.00", j["utc"])
        assertEquals("ETRS89 / UTM 34N", (j["grid"] as Map<*, *>)["name"])
        assertEquals("RTK fixed", j["method"])
    }
}
