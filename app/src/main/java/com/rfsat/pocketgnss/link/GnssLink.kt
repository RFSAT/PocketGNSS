package com.rfsat.pocketgnss.link

import java.io.IOException

enum class LinkKind { USB, BLUETOOTH, TCP, SIMULATOR }

/**
 * Hardware versions of the RFSAT receiver (D3.3 §4.6). Both run ESPrtk on the ESP32 and fetch RTK corrections with
 * its own NTRIP client; they differ in how that client reaches the internet.
 *  - [LTE]: three boards (ESP32-DevKitC, ESP_BG95 LTE modem with nano-SIM and SD slot, ESP_F9x); corrections over the
 *    mobile network, or Wi-Fi when configured. Power on the middle micro-USB.
 *  - [WIFI]: compact two-board build (ESP32-DevKitC on ESP_F9x, no modem); corrections only through a Wi-Fi access
 *    point: a field router or the phone's own hotspot (D3.3 §4.2, D3.5 GNSS-spec-13).
 */
enum class ReceiverVariant { UNSET, LTE, WIFI }

/** USB bridge chip of a receiver, from its vendor ID. */
enum class UsbChip(val vendorId: Int, val label: String) {
    /**
     * The ZED-F9x's own USB port: the bottom micro-USB of the RFSAT receiver ("not normally used", D3.3 §4.7.1),
     * the port u-center configures through. NMEA, UBX and RTCM all reach the GNSS chip directly.
     */
    UBLOX(0x1546, "u-blox chip port (bottom USB)"),
    /**
     * ESP32-DevKitC V4 (ESPrtk), the top module: the receiver's upper micro-USB, its data / COM port, carrying the
     * NMEA the ESP32 sends to TX_UART0 at 115200 bd (D3.3 §4.7.1 and §4.7.7, D3.5 GNSS-test-11).
     */
    CP210X(0x10C4, "ESP32 data port (CP210x, upper USB)"),
    CH34X(0x1A86, "ESP32 data port (CH34x)"),
    ESPRESSIF(0x303A, "Espressif native USB (ESP32)"),
    FTDI(0x0403, "FTDI serial"),
    OTHER(0, "USB serial");

    /** Bridges wired to an ESP32's EN / IO0 through DTR / RTS: toggling those lines would reset the board. */
    val resetsOnControlLines: Boolean get() = this == CP210X || this == CH34X

    companion object {
        fun of(vendorId: Int) = entries.firstOrNull { it.vendorId == vendorId } ?: OTHER
        val gnssVendorIds = entries.filter { it != OTHER }.map { it.vendorId }.toSet()
    }
}

data class LinkInfo(
    val kind: LinkKind,
    /** Shown in the title bar: "BTU-ESPrtk : AJG03", "u-blox ZED-F9x (USB)", "192.168.4.1:9000"... */
    val name: String,
    val detail: String,
    val chip: UsbChip? = null,
    val baud: Int? = null,
) {
    /**
     * True when UBX commands certainly reach the GNSS chip (its own USB port, or the simulator).
     * Over the ESP32 (Bluetooth or its USB bridge) they may or may not be passed through.
     */
    val ubxDirect: Boolean get() = chip == UsbChip.UBLOX || kind == LinkKind.SIMULATOR
}

/**
 * A byte pipe to the receiver. [read] blocks for at most a short time; it returns the number of bytes read,
 * 0 on timeout, or throws [IOException] when the link is gone.
 */
interface GnssLink {
    val info: LinkInfo

    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    @Throws(IOException::class)
    fun write(data: ByteArray)

    fun close()

    /** USB only: change the baud rate of a UART bridge (auto-baud search). */
    fun setBaud(baud: Int) {}
}
