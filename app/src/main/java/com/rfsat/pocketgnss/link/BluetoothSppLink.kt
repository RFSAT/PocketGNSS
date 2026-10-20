package com.rfsat.pocketgnss.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic serial port (SPP / RFCOMM) to the receiver's ESP32 adapter, configured in ESPrtk as
 * "RTK Radio Over: Bluetooth Classic – BTU" and advertised as "BTU-ESPrtk : <ID>_<suffix>" (D3.3 §4.7.14).
 * The phone must be paired with it first (Android Bluetooth settings); the app only connects to paired
 * devices, so it needs no scanning permission.
 */
class BluetoothSppLink private constructor(
    private val socket: BluetoothSocket,
    override val info: LinkInfo,
) : GnssLink {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override fun read(buffer: ByteArray): Int {
        // available() keeps the reader responsive to close(); the blocking read follows when data is there.
        val n = input.available()
        if (n <= 0) { Thread.sleep(15); return 0 }
        val r = input.read(buffer, 0, minOf(n, buffer.size))
        if (r < 0) throw IOException("Bluetooth link closed")
        return r
    }

    override fun write(data: ByteArray) {
        output.write(data); output.flush()
    }

    override fun close() { try { socket.close() } catch (_: Exception) {} }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** True for names of the ESPrtk Bluetooth adapter ("BTU-ESPrtk : AJG03_8T0K2") and similar RTK receivers. */
        fun looksLikeReceiver(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return "esprtk" in n || "rtk" in n || "gnss" in n || "f9p" in n || "f9r" in n || n.startsWith("btu")
        }

        @SuppressLint("MissingPermission")
        @Throws(IOException::class)
        fun open(device: BluetoothDevice): BluetoothSppLink {
            val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
            var last: IOException? = null
            for (secure in listOf(true, false)) {
                val s = try {
                    if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
                } catch (e: IOException) { last = e; continue }
                try {
                    s.connect()
                    return BluetoothSppLink(s, LinkInfo(LinkKind.BLUETOOTH, name, "Bluetooth SPP · ${device.address}"))
                } catch (e: IOException) {
                    last = e
                    try { s.close() } catch (_: Exception) {}
                }
            }
            throw IOException("Could not connect to $name: ${last?.message ?: "no answer"}. Is the receiver on and in range?")
        }
    }
}
