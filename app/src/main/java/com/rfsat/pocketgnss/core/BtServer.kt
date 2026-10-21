package com.rfsat.pocketgnss.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

enum class BtServerStatus { OFF, LISTENING, NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, ERROR }

data class BtServerState(
    val status: BtServerStatus = BtServerStatus.OFF,
    /** Names (or addresses) of the connected devices. */
    val clients: List<String> = emptyList(),
    /** This phone's Bluetooth name, as the other device sees it. */
    val phoneName: String? = null,
    val message: String? = null,
    val sentLines: Long = 0,
)

/**
 * The phone as a Bluetooth GNSS receiver: a Bluetooth Classic serial-port (SPP) service that streams NMEA to any
 * paired device that connects, exactly like a Bluetooth GPS receiver. Laptops (Windows: the paired phone's
 * outgoing COM port), tablets and field controllers read it with their usual "Bluetooth GNSS" setting. Several
 * devices can be connected at once; each has its own sender thread and queue, so a slow client never holds up
 * the app (its oldest sentences are dropped instead).
 */
class BtNmeaServer(private val context: Context) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val _state = MutableStateFlow(BtServerState())
    val state: StateFlow<BtServerState> = _state.asStateFlow()

    private class Client(val socket: BluetoothSocket, val name: String) {
        val queue = ArrayBlockingQueue<ByteArray>(256)
        @Volatile var open = true
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var sent = 0L

    val running: Boolean get() = server != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (server != null) return
        val a = adapter
        if (a == null) { _state.value = BtServerState(BtServerStatus.NO_BLUETOOTH); return }
        val ss = try {
            if (!a.isEnabled) { _state.value = BtServerState(BtServerStatus.BLUETOOTH_OFF); return }
            a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        } catch (_: SecurityException) {
            _state.value = BtServerState(BtServerStatus.NO_PERMISSION); return
        } catch (e: IOException) {
            _state.value = BtServerState(BtServerStatus.ERROR, message = e.message); return
        }
        server = ss
        val phone = try { a.name } catch (_: SecurityException) { null }
        _state.value = BtServerState(BtServerStatus.LISTENING, phoneName = phone)
        Thread({
            while (server === ss) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                val name = try { s.remoteDevice.name ?: s.remoteDevice.address } catch (_: SecurityException) { "device" }
                val c = Client(s, name)
                clients.add(c)
                publish()
                Thread({ pump(c) }, "bt-nmea-${clients.size}").apply { isDaemon = true }.start()
            }
        }, "bt-nmea-accept").apply { isDaemon = true }.start()
    }

    private fun pump(c: Client) {
        val out = try { c.socket.outputStream } catch (_: IOException) { null }
        while (c.open && out != null) {
            val b = try { c.queue.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            try { out.write(b); out.flush() } catch (_: IOException) { break }
        }
        drop(c)
    }

    private fun drop(c: Client) {
        c.open = false
        try { c.socket.close() } catch (_: Exception) {}
        if (clients.remove(c)) publish()
    }

    private fun publish() {
        _state.value = _state.value.copy(clients = clients.map { it.name }, sentLines = sent)
    }

    /** One NMEA sentence to every connected device. */
    fun send(line: String) {
        if (clients.isEmpty()) return
        val b = (line + "\r\n").toByteArray(Charsets.US_ASCII)
        for (c in clients) {
            if (!c.queue.offer(b)) { c.queue.poll(); c.queue.offer(b) } // slow client: drop its oldest sentence
        }
        sent++
        if (sent % 50 == 0L) publish()
    }

    fun stop() {
        val s = server
        server = null
        try { s?.close() } catch (_: Exception) {}
        for (c in clients) drop(c)
        clients.clear()
        _state.value = BtServerState(BtServerStatus.OFF)
    }

    companion object {
        /** Standard Serial Port Profile UUID: what Bluetooth GNSS clients look for. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "PocketGNSS NMEA"

        /** Sentences a position consumer needs (the "position only" choice). */
        val POSITION_SENTENCES = setOf("GGA", "RMC", "GST", "GSA", "VTG", "GNS", "ZDA", "GLL")

        /** NMEA sentence type ("GGA") of a line, without talker. */
        fun typeOf(line: String): String = if (line.length > 6 && line[0] == '$') line.substring(3, 6) else ""
    }
}
