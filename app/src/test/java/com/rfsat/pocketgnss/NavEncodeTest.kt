package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.raw.NavMessage
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The navigation message decoders on messages built here bit by bit as the satellites send them (GPS LNAV with its
 * parity and the inverted words that follow D30* = 1, Galileo I/NAV pages with their CRC, BeiDou D1 with its
 * interleaved BCH code words) and laid out as Android delivers them. The 2017 phone of [NavMessageTest] sends GPS
 * without valid parity and no Galileo or BeiDou at all, so these paths are checked here.
 */
class NavEncodeTest {
    private val week = 2390          // GPS week (October 2025)
    private val tow = 302_400.0      // Wednesday 12:00 GPS time

    private class Bits(n: Int) {
        val b = ByteArray((n + 7) / 8)
        fun put(pos: Int, len: Int, v: Long) { for (i in 0 until len) set(pos + i, (v shr (len - 1 - i)) and 1L == 1L) }
        fun set(pos: Int, on: Boolean) { val m = 0x80 shr (pos % 8); b[pos / 8] = if (on) (b[pos / 8].toInt() or m).toByte() else (b[pos / 8].toInt() and m.inv()).toByte() }
        fun get(pos: Int, len: Int): Long { var v = 0L; for (i in 0 until len) v = (v shl 1) or ((b[(pos + i) / 8].toInt() shr (7 - (pos + i) % 8)) and 1).toLong(); return v }
    }

    private fun start() { RtkNative.init(0, 0x01 or 0x08 or 0x20, 15.0, 0.0, 1, 0, 100.0, 3.0, 30.0, 5, 1); RtkNative.setTime(week, tow) }
    private fun base() = RtkBase.from(DoubleArray(RtkBase.SIZE).also { RtkNative.base(it) })

    /** 30-bit words into Android's 40 bytes: each word in the low 30 bits of 4 bytes, MSB first. */
    private fun android40(words: LongArray) = ByteArray(40).also { out ->
        for (i in 0 until 10) for (k in 0 until 4) out[4 * i + k] = (words[i] shr (24 - 8 * k)).toByte()
    }

    // ---- GPS LNAV ----

    private val hamming = longArrayOf(0xBB1F3480, 0x5D8F9A40, 0xAEC7CD00, 0x5763E680, 0x6BB1F340, 0x8B7A89C0)

    /** One transmitted word from 24 data bits and D29* / D30* of the word before (IS-GPS-200 20.3.5). */
    private fun gpsWord(d: Long, prev: Long): Long {
        val w = (prev shl 30) or (d shl 6)
        var p = 0L
        for (h in hamming) p = (p shl 1) or (java.lang.Long.bitCount((w and h) ushr 6) and 1).toLong()
        val data = if (prev and 1L == 1L) d xor 0xFFFFFF else d
        return (data shl 6) or p
    }

    private fun gpsSubframe(id: Int, fill: (Bits) -> Unit, prev0: Long, corrected: Boolean): ByteArray {
        val s = Bits(240)
        s.put(0, 8, 0x8B)
        s.put(24, 17, ((tow + 6) / 6).toLong())   // HOW: start of the next subframe
        s.put(43, 3, id.toLong())
        fill(s)
        var prev = prev0
        val words = LongArray(10) { i ->
            val w = gpsWord(s.get(24 * i, 24), prev)
            prev = w and 3
            w
        }
        if (corrected) { // data bits given upright, parity as transmitted
            var p = prev0
            for (i in 0 until 10) { val raw = words[i]; if (p and 1L == 1L) words[i] = raw xor 0x3FFFFFC0; p = raw and 3 }
        }
        return android40(words)
    }

    private fun gpsFrames(corrected: Boolean): List<ByteArray> {
        val toe = 302_400L
        val iode = 0x5AL
        val sf1 = gpsSubframe(1, { s -> s.put(48, 10, (week % 1024).toLong()); s.put(70, 2, 0); s.put(168, 8, iode); s.put(176, 16, toe / 16) }, 3, corrected)
        val sf2 = gpsSubframe(2, { s ->
            s.put(48, 8, iode); s.put(136, 32, (0.01 * (1L shl 33)).toLong())
            s.put(184, 32, (5153.6 * (1L shl 19)).toLong()); s.put(216, 16, toe / 16)
        }, 1, corrected)
        val sf3 = gpsSubframe(3, { s -> s.put(216, 8, iode); s.put(64, 32, 0x12345678); s.put(112, 32, 0x2C000000) }, 2, corrected)
        return listOf(sf1, sf2, sf3)
    }

    @Test fun gpsWithParity() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        for (corrected in listOf(false, true)) {
            start()
            val f = gpsFrames(corrected)
            assertEquals(0, RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 1, f[0], -99))
            assertEquals(0, RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 2, f[1], -99))
            // with valid parity one copy is enough
            assertEquals("corrected=$corrected", 1, RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 3, f[2], -99))
            assertEquals(1, base().phoneEphemerides)
            assertEquals(0, RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 3, f[2], -99)) // the same again: nothing new
            RtkNative.free()
        }
    }

    @Test fun gpsParityErrorWithoutSecondCopyIsNotUsed() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        start()
        val f = gpsFrames(false)
        RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 1, f[0], -99)
        RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 2, f[1], -99)
        val bad = f[2].copyOf().also { it[22] = (it[22].toInt() xor 0x01).toByte() }   // a bit of word 6
        // parity fails: rejected, or at most kept unconfirmed (as data without parity); either way no ephemeris
        assertTrue(RtkNative.navMessage(NavMessage.GPS_L1CA, 7, -1, 3, bad, -99) <= 0)
        assertEquals(0, base().ephemerides)
        RtkNative.free()
    }

    // ---- Galileo I/NAV ----

    private fun crc24q(b: ByteArray, len: Int): Long {
        var crc = 0L
        for (i in 0 until len) {
            crc = crc xor ((b[i].toLong() and 0xFF) shl 16)
            for (k in 0 until 8) { crc = crc shl 1; if (crc and 0x1000000L != 0L) crc = crc xor 0x1864CFBL }
        }
        return crc and 0xFFFFFF
    }

    private fun galPage(word: Bits): ByteArray {
        val p = Bits(232)
        p.put(0, 1, 0); p.put(1, 1, 0)
        for (i in 0 until 112) p.put(2 + i, 1, word.get(i, 1))
        p.put(114, 1, 1); p.put(115, 1, 0)
        for (i in 0 until 16) p.put(116 + i, 1, word.get(112 + i, 1))
        val c = Bits(200)
        for (i in 0 until 114) c.put(4 + i, 1, p.get(i, 1))
        for (i in 0 until 82) c.put(118 + i, 1, p.get(114 + i, 1))
        p.put(196, 24, crc24q(c.b, 25))
        return p.b.copyOf(29)
    }

    private fun galWords(svid: Int): List<ByteArray> {
        val iod = 77L
        val toe = 302_400L
        val words = (1..5).map { t -> Bits(128).also { it.put(0, 6, t.toLong()); if (t <= 4) it.put(6, 10, iod) } }
        words[0].put(16, 14, toe / 60); words[0].put(62, 32, (0.0003 * (1L shl 33)).toLong()); words[0].put(94, 32, (5440.6 * (1L shl 19)).toLong())
        words[1].put(16, 32, 0x1234567); words[1].put(48, 32, 0x2C000000)
        words[3].put(16, 6, svid.toLong()); words[3].put(54, 14, toe / 60)
        words[4].put(73, 12, (week - 1024).toLong()); words[4].put(85, 20, tow.toLong())
        return words.map { galPage(it) }
    }

    @Test fun galileoInav() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        start()
        val pages = galWords(11)
        for (i in 0 until 4) assertEquals(0, RtkNative.navMessage(NavMessage.GAL_I, 11, 1, i + 1, pages[i], -99))
        // a bit flipped: the CRC fails
        val bad = pages[4].copyOf().also { it[5] = (it[5].toInt() xor 0x08).toByte() }
        assertEquals(-1, RtkNative.navMessage(NavMessage.GAL_I, 11, 1, 5, bad, -99))
        assertEquals(1, RtkNative.navMessage(NavMessage.GAL_I, 11, 1, 5, pages[4], -99))
        val b = base()
        assertEquals(1, b.ephemerides); assertEquals(1, b.phoneEphemerides); assertEquals(1L, b.navRejected)
        // another satellite's words are not this one's: svid in word 4 decides
        RtkNative.free()
    }

    // ---- BeiDou D1 ----

    private fun bch(info: Long): Long { // 11 info bits -> 4 parity bits, g(x) = x^4 + x + 1
        var r = info shl 4
        for (i in 14 downTo 4) if (r and (1L shl i) != 0L) r = r xor (0x13L shl (i - 4))
        return r and 0xF
    }

    /** Subframe (300 bits, as RTKLIB's decoder reads it) into Android words, transmitted (interleaved) or de-interleaved. */
    private fun bdsAndroid(s: Bits, interleaved: Boolean): ByteArray {
        val words = LongArray(10)
        val w1info = s.get(15, 11)
        words[0] = (s.get(0, 15) shl 15) or (w1info shl 4) or bch(w1info)
        for (i in 1 until 10) {
            val i1 = s.get(30 * i, 11); val i2 = s.get(30 * i + 11, 11)
            val c1 = (i1 shl 4) or bch(i1); val c2 = (i2 shl 4) or bch(i2)
            words[i] = if (!interleaved) (i1 shl 19) or (i2 shl 8) or ((c1 and 0xF) shl 4) or (c2 and 0xF) else {
                var w = 0L
                for (b in 14 downTo 0) w = (w shl 2) or (((c1 shr b) and 1) shl 1) or ((c2 shr b) and 1)
                w
            }
        }
        return android40(words)
    }

    private fun bdsSubframes(interleaved: Boolean): List<ByteArray> {
        val bdtWeek = (week - 1356).toLong()
        val sow = (tow - 14).toLong() - 12      // BDT, three subframes 6 s apart ending now
        val toe = ((tow - 14).toLong() / 8) * 8 - 8 * 100
        fun sf(frn: Int, fill: (Bits) -> Unit) = Bits(304).also { s ->
            s.put(0, 11, 0x712); s.put(15, 3, frn.toLong())
            val t = sow + 6 * (frn - 1)
            s.put(18, 8, t shr 12); s.put(30, 12, t and 0xFFF)
            fill(s)
        }
        val s1 = sf(1) { s -> s.put(60, 13, bdtWeek); s.put(73, 9, (toe / 8) shr 8); s.put(90, 8, (toe / 8) and 0xFF); s.put(287, 5, 3) }
        val sqrtA = (5282.6 * (1L shl 19)).toLong()
        val e = (0.001 * (1L shl 33)).toLong()
        val s2 = sf(2) { s ->
            s.put(132, 10, e shr 22); s.put(150, 22, e and 0x3FFFFF)
            s.put(250, 12, sqrtA shr 20); s.put(270, 20, sqrtA and 0xFFFFF); s.put(290, 2, (toe / 8) shr 15)
        }
        val s3 = sf(3) { s -> s.put(42, 10, ((toe / 8) and 0x7FFF) shr 5); s.put(60, 5, (toe / 8) and 0x1F) }
        return listOf(s1, s2, s3).map { bdsAndroid(it, interleaved) }
    }

    @Test fun beidouD1() {
        assumeTrue("host build of the RTK engine not available", RtkNative.available)
        for (interleaved in listOf(true, false)) {
            start()
            val f = bdsSubframes(interleaved)
            assertEquals(0, RtkNative.navMessage(NavMessage.BDS_D1, 21, 1, 1, f[0], -99))
            assertEquals(0, RtkNative.navMessage(NavMessage.BDS_D1, 21, 1, 2, f[1], -99))
            assertEquals("interleaved=$interleaved", 1, RtkNative.navMessage(NavMessage.BDS_D1, 21, 1, 3, f[2], -99))
            assertEquals(1, base().phoneEphemerides)
            // a bit flipped: the BCH code fails
            val bad = f[1].copyOf().also { it[13] = (it[13].toInt() xor 0x02).toByte() }
            assertEquals(-1, RtkNative.navMessage(NavMessage.BDS_D1, 21, 1, 2, bad, -99))
            // geostationary satellites (D2) are not used
            assertEquals(0, RtkNative.navMessage(NavMessage.BDS_D1, 3, 1, 1, f[0], -99))
            RtkNative.free()
        }
    }
}
