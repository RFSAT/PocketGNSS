package com.rfsat.pocketgnss.link

import java.io.IOException

/**
 * Where the position comes from. [PHONE] is the phone's own GNSS chip: Android's location, or with the phone RTK engine
 * its raw measurements processed together with a caster's corrections (PhoneRtkLink).
 */
enum class LinkKind { USB, BLUETOOTH, TCP, PHONE, SIMULATOR,
    /** A recorded session played back (Log > sessions > Replay); never a Setup choice. */
    REPLAY }

/**
 * Hardware versions of the RFSAT receiver (D3.3 §4.6). Both run ESPrtk on the ESP32 and fetch RTK corrections with
 * its own NTRIP client; they differ in how that client reaches the internet.
 *  - [LTE]: three boards (ESP32-DevKitC, ESP_BG95 LTE modem with nano-SIM and SD slot, ESP_F9x); corrections over the
 *    mobile network, or Wi-Fi when configured. Power on the middle micro-USB.
 *  - [WIFI]: compact two-board build (ESP32-DevKitC on ESP_F9x, no modem); corrections only through a Wi-Fi access
 *    point: a field router or the phone's own hotspot (D3.3 §4.2, D3.5 GNSS-spec-13).
 */
enum class ReceiverVariant {
    UNSET, LTE, WIFI,
    /*
     * RFSAT's RTK rovers built on the Quectel LG290P (master documentation RFSAT-RTK-DOC-001, variants V1-V7;
     * Study S1 is a desk study, not a receiver).
     * Their firmware (PicoRTK, PostcardRTK, RTK Everywhere) sets the receiver and runs its own NTRIP client;
     * V5 is the receiver on its own, configured once with the profiles of the documentation.
     */
    /** V1: Waveshare LG290P module + Raspberry Pi Pico 2 W (PicoRTK firmware). */
    PICORTK,
    /**
     * V2: Waveshare LG290P module + ESP32-C6 SuperMini; V6: RFSAT's own LG290P + ESP32-C6-MINI-1 board. Both run the
     * PostcardRTK firmware for the C6 (Bluetooth LE only, USB-C on the C6's native USB). V6's M5 socket is the LG290P's
     * own UART (like V5).
     */
    LG290P_C6,
    /** V3: SparkFun RTK Postcard with the PostcardRTK firmware. */
    POSTCARDRTK,
    /** V4: SparkFun RTK Postcard + Portability Shield with RFSAT's RTK Everywhere fork; V7: the same with the IM19 tilt sensor (survey). */
    RTK_EVERYWHERE,
    /** V5: the LG290P on its own, over USB or its UART (no firmware of ours); also V6 through its M5 socket. */
    LG290P_WIRED;

    /** One of RFSAT's LG290P rovers (not the AgriBIT u-blox receiver). */
    val isRover: Boolean get() = this == PICORTK || this == LG290P_C6 || this == POSTCARDRTK || this == RTK_EVERYWHERE || this == LG290P_WIRED

    /**
     * USB port to open on two-port USB chips when Setup says "automatic": the Postcard's CH342 gives port A to the ESP32
     * (console, flashing) and port B straight to the LG290P, which is where the NMEA is.
     */
    val defaultUsbPort: Int get() = if (this == POSTCARDRTK || this == RTK_EVERYWHERE) 1 else 0

    companion object {
        val AGRIBIT = listOf(LTE, WIFI)
        val ROVERS = listOf(PICORTK, LG290P_C6, POSTCARDRTK, RTK_EVERYWHERE, LG290P_WIRED)
    }
}

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
    /** Raspberry Pi Pico 2 W (PicoRTK, variant V1): the RP2350's own USB serial (CDC), position and receiver bridge. */
    RASPBERRY_PI(0x2E8A, "Raspberry Pi Pico USB (PicoRTK)"),
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
    /** The phone's own GNSS with the RTK engine: takes RTCM from the NTRIP relay like a receiver. */
    val phoneRtk: Boolean = false,
) {
    /**
     * True when UBX commands certainly reach the GNSS chip (its own USB port, or the simulator).
     * Over the ESP32 (Bluetooth or its USB bridge) they may or may not be passed through.
     */
    val ubxDirect: Boolean get() = chip == UsbChip.UBLOX || kind == LinkKind.SIMULATOR

    /** True when corrections (RTCM) can be written to the receiver (u-blox commands only to real receivers). */
    val writable: Boolean get() = kind != LinkKind.PHONE || phoneRtk
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
