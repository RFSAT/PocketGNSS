package com.rfsat.pocketgnss.protocol

import java.io.ByteArrayOutputStream

/*
 * u-blox UBX binary protocol, the subset needed to configure a ZED-F9P / ZED-F9R (generation 9,
 * configuration-key interface) from the phone instead of u-center (D3.3 §4.7.22 steps 1-9):
 *   UBX-CFG-VALSET / VALGET  message outputs per port, navigation rate, saved to RAM + BBR + Flash
 *   UBX-CFG-RST              cold / warm / hot start for the time-to-first-fix test (D3.5 GNSS-test-07)
 *   UBX-MON-VER              module name and firmware version (identifies F9P vs F9R)
 *   UBX-ACK-ACK / ACK-NAK    confirmation of each command
 *
 * UBX reaches the GNSS chip only on a port wired to it: the F9x's own USB port (CDC-ACM, VID 0x1546) for
 * sure; through the ESP32 (ESPrtk firmware) over Bluetooth or the CP210x port only if the firmware passes
 * binary data through. The app therefore always waits for ACK and reports when none comes.
 */

data class UbxFrame(val cls: Int, val id: Int, val payload: ByteArray) {
    val name: String get() = Ubx.name(cls, id)
    override fun equals(other: Any?) = other is UbxFrame && other.cls == cls && other.id == id && other.payload.contentEquals(payload)
    override fun hashCode() = (cls * 256 + id) * 31 + payload.contentHashCode()
}

/** u-blox F9 port of a configuration key. */
enum class UbxPort(val offset: Int, val label: String) { I2C(0, "I2C"), UART1(1, "UART1"), UART2(2, "UART2"), USB(3, "USB"), SPI(4, "SPI") }

/**
 * NMEA messages with their CFG-MSGOUT-NMEA_ID_xxx_I2C key; the other ports follow at fixed offsets
 * (I2C +0, UART1 +1, UART2 +2, USB +3, SPI +4), as in the u-blox F9 HPG / HPS interface description.
 */
enum class NmeaMsg(val baseKey: Long, val help: String) {
    DTM(0x209100a6, "Datum reference (WGS-84 check)"),
    RMC(0x209100ab, "Position, velocity, time, date"),
    VTG(0x209100b0, "Course and speed over ground"),
    GNS(0x209100b5, "Fix with RTK mode per constellation"),
    GGA(0x209100ba, "Position, fix quality, satellites, HDOP, altitude"),
    GSA(0x209100bf, "DOP and satellites used"),
    GSV(0x209100c4, "Satellites in view with signal strength"),
    GLL(0x209100c9, "Latitude, longitude, time, status"),
    GRS(0x209100ce, "Range residuals"),
    GST(0x209100d3, "Position error statistics (1-sigma accuracy)"),
    ZDA(0x209100d8, "Time and date");

    fun key(port: UbxPort): Long = baseKey + port.offset
}

/** Configuration layers of CFG-VALSET (bit mask). */
object UbxLayer { const val RAM = 1; const val BBR = 2; const val FLASH = 4; const val ALL = RAM or BBR or FLASH }

/** Reset types of UBX-CFG-RST (navBbrMask). */
enum class StartType(val navBbrMask: Int) { HOT(0x0000), WARM(0x0001), COLD(0xFFFF) }

object Ubx {
    const val SYNC1 = 0xB5
    const val SYNC2 = 0x62

    const val CLS_NAV = 0x01; const val CLS_ACK = 0x05; const val CLS_CFG = 0x06; const val CLS_MON = 0x0A
    const val ID_ACK_NAK = 0x00; const val ID_ACK_ACK = 0x01
    const val ID_CFG_RST = 0x04; const val ID_CFG_VALSET = 0x8A; const val ID_CFG_VALGET = 0x8B
    const val ID_MON_VER = 0x04

    /** CFG-RATE-MEAS: measurement period in ms (U2). 50 ms = 20 Hz, the maximum validated in D3.5 GNSS-test-08. */
    const val KEY_RATE_MEAS = 0x30210001L
    /** CFG-RATE-NAV: measurements per navigation solution (U2). */
    const val KEY_RATE_NAV = 0x30210002L
    /** CFG-UART1-BAUDRATE (U4). */
    const val KEY_UART1_BAUD = 0x40520001L
    /** CFG-USBINPROT-RTCM3X: RTCM3 accepted on USB (L). */
    const val KEY_USBINPROT_RTCM3X = 0x10770004L
    /** CFG-UART1INPROT-RTCM3X (L). */
    const val KEY_UART1INPROT_RTCM3X = 0x10730004L

    fun name(cls: Int, id: Int): String = when (cls to id) {
        CLS_ACK to ID_ACK_ACK -> "ACK-ACK"
        CLS_ACK to ID_ACK_NAK -> "ACK-NAK"
        CLS_CFG to ID_CFG_VALSET -> "CFG-VALSET"
        CLS_CFG to ID_CFG_VALGET -> "CFG-VALGET"
        CLS_CFG to ID_CFG_RST -> "CFG-RST"
        CLS_MON to ID_MON_VER -> "MON-VER"
        CLS_NAV to 0x07 -> "NAV-PVT"
        CLS_NAV to 0x14 -> "NAV-HPPOSLLH"
        else -> "UBX-%02X-%02X".format(cls, id)
    }

    /** 8-bit Fletcher checksum over class, id, length and payload. */
    fun checksum(data: ByteArray, from: Int, to: Int): Pair<Int, Int> {
        var a = 0; var b = 0
        for (i in from until to) { a = (a + (data[i].toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
        return a to b
    }

    fun frame(cls: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = SYNC1.toByte(); out[1] = SYNC2.toByte(); out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte(); out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        val (a, b) = checksum(out, 2, 6 + payload.size)
        out[6 + payload.size] = a.toByte(); out[7 + payload.size] = b.toByte()
        return out
    }

    /** Size in bytes of a configuration key's value, from bits 28..30 of the key ID. */
    fun valueSize(key: Long): Int = when (((key shr 28) and 0x7).toInt()) {
        1 -> 1 // one bit, stored in a byte
        2 -> 1
        3 -> 2
        4 -> 4
        5 -> 8
        else -> throw IllegalArgumentException("Bad key size in 0x%08X".format(key))
    }

    private fun ByteArrayOutputStream.le(v: Long, bytes: Int) { for (i in 0 until bytes) write(((v shr (8 * i)) and 0xFF).toInt()) }

    /** UBX-CFG-VALSET (version 0) for the given key/value pairs on [layers]. At most 64 pairs per message. */
    fun valset(values: List<Pair<Long, Long>>, layers: Int = UbxLayer.RAM): ByteArray {
        require(values.size in 1..64)
        val p = ByteArrayOutputStream()
        p.write(0); p.write(layers); p.write(0); p.write(0)
        for ((k, v) in values) { p.le(k, 4); p.le(v, valueSize(k)) }
        return frame(CLS_CFG, ID_CFG_VALSET, p.toByteArray())
    }

    /** UBX-CFG-VALGET (version 0) reading [keys] from layer 0 = RAM (the values in use). */
    fun valget(keys: List<Long>, layer: Int = 0): ByteArray {
        require(keys.size in 1..64)
        val p = ByteArrayOutputStream()
        p.write(0); p.write(layer); p.write(0); p.write(0)
        for (k in keys) p.le(k, 4)
        return frame(CLS_CFG, ID_CFG_VALGET, p.toByteArray())
    }

    /** Decodes a VALGET response (version 1) into key -> value. */
    fun parseValget(payload: ByteArray): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>()
        var i = 4
        while (i + 4 <= payload.size) {
            val key = u32(payload, i); i += 4
            val n = try { valueSize(key) } catch (_: IllegalArgumentException) { return out }
            if (i + n > payload.size) break
            var v = 0L
            for (b in 0 until n) v = v or ((payload[i + b].toLong() and 0xFF) shl (8 * b))
            out[key] = v; i += n
        }
        return out
    }

    /** UBX-CFG-RST. resetMode 0x02 = controlled software reset of the GNSS only (the USB link stays up). */
    fun reset(start: StartType, resetMode: Int = 0x02): ByteArray =
        frame(CLS_CFG, ID_CFG_RST, byteArrayOf((start.navBbrMask and 0xFF).toByte(), (start.navBbrMask shr 8).toByte(), resetMode.toByte(), 0))

    fun pollMonVer(): ByteArray = frame(CLS_MON, ID_MON_VER)

    data class Version(val software: String, val hardware: String, val extensions: List<String>) {
        /** "ZED-F9P" / "ZED-F9R" from the MOD= extension, when present. */
        val module: String? get() = extensions.firstOrNull { it.startsWith("MOD=") }?.removePrefix("MOD=")
        val firmware: String? get() = extensions.firstOrNull { it.startsWith("FWVER=") }?.removePrefix("FWVER=")
        val protocol: String? get() = extensions.firstOrNull { it.startsWith("PROTVER=") }?.removePrefix("PROTVER=")
    }

    fun parseMonVer(p: ByteArray): Version? {
        if (p.size < 40) return null
        fun str(from: Int, len: Int) = String(p, from, len, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val ext = ArrayList<String>()
        var i = 40
        while (i + 30 <= p.size) { ext.add(str(i, 30)); i += 30 }
        return Version(str(0, 30), str(30, 10), ext)
    }

    /** For ACK-ACK / ACK-NAK: the class and id of the acknowledged message. */
    fun ackedOf(f: UbxFrame): Pair<Int, Int>? =
        if (f.cls == CLS_ACK && f.payload.size >= 2) (f.payload[0].toInt() and 0xFF) to (f.payload[1].toInt() and 0xFF) else null

    fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    /** Configuration for a set of NMEA outputs on [ports]: rate 1 = every navigation epoch, 0 = off. */
    fun nmeaOutputs(enabled: Set<NmeaMsg>, ports: Set<UbxPort>): List<Pair<Long, Long>> =
        NmeaMsg.entries.flatMap { m -> ports.map { p -> m.key(p) to (if (m in enabled) 1L else 0L) } }

    /** CFG-RATE-MEAS value for a navigation rate in Hz (1..25). */
    fun rateKeys(hz: Int): List<Pair<Long, Long>> = listOf(KEY_RATE_MEAS to (1000L / hz.coerceIn(1, 25)), KEY_RATE_NAV to 1L)
}
