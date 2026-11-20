package com.rfsat.pocketgnss.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy serial link over the Nordic UART Service (NUS), the BLE output of RFSAT's PicoRTK and
 * PostcardRTK firmware (and of the ESP32-C6 rovers, V2 and V6, which have no Bluetooth classic). NMEA arrives as notifications
 * on the TX characteristic; RTCM from the NTRIP relay is written to the RX characteristic in MTU-sized pieces.
 * BLE devices are not paired: Setup finds them with a scan (BLUETOOTH_SCAN, "never for location").
 */
class BleNusLink private constructor(
    private val gatt: BluetoothGatt,
    private val state: State,
    override val info: LinkInfo,
) : GnssLink {

    /** Shared with the GATT callback, which runs on a binder thread. */
    class State {
        val incoming = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        @Volatile var mtu = 23
        @Volatile var rx: BluetoothGattCharacteristic? = null
        @Volatile var tx: BluetoothGattCharacteristic? = null
        @Volatile var gattStatus = 0
        val connected = CountDownLatch(1)
        val mtuDone = CountDownLatch(1)
        val discovered = CountDownLatch(1)
        val notifying = CountDownLatch(1)
        /** One GATT operation at a time: released when a write completes. */
        val writeDone = Semaphore(0)
    }

    private var pending: ByteArray? = null
    private var pendingPos = 0

    override fun read(buffer: ByteArray): Int {
        if (state.closed && state.incoming.isEmpty() && pending == null) throw IOException("Bluetooth LE link closed")
        val chunk = pending ?: state.incoming.poll(200, TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(buffer.size, chunk.size - pendingPos)
        System.arraycopy(chunk, pendingPos, buffer, 0, n)
        pendingPos += n
        if (pendingPos >= chunk.size) { pending = null; pendingPos = 0 } else pending = chunk
        return n
    }

    @SuppressLint("MissingPermission")
    override fun write(data: ByteArray) {
        val ch = state.rx ?: throw IOException("Bluetooth LE: no RX characteristic")
        val step = (state.mtu - 3).coerceAtLeast(20)
        val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            if (state.closed) throw IOException("Bluetooth LE link closed")
            val piece = data.copyOfRange(i, minOf(data.size, i + step))
            val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(ch, piece, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = piece; gatt.writeCharacteristic(ch) }
            if (!started) { Thread.sleep(10); continue } // the stack is busy: try the same piece again
            if (!state.writeDone.tryAcquire(1, TimeUnit.SECONDS)) throw IOException("Bluetooth LE write timed out")
            i += piece.size
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        state.closed = true
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        /** Written by the phone (receiver's RX). */
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        /** Notified by the receiver (receiver's TX). */
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private fun await(l: CountDownLatch, sec: Long, what: String, s: State) {
            if (!l.await(sec, TimeUnit.SECONDS)) throw IOException("Bluetooth LE: $what timed out")
            if (s.closed) throw IOException("Bluetooth LE: disconnected during $what (status ${s.gattStatus})")
        }

        @SuppressLint("MissingPermission")
        @Throws(IOException::class)
        fun open(context: Context, device: BluetoothDevice): BleNusLink {
            val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
            val s = State()
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    s.gattStatus = status
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) s.connected.countDown()
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        s.closed = true
                        // release every waiter so open() and write() fail at once
                        s.connected.countDown(); s.mtuDone.countDown(); s.discovered.countDown(); s.notifying.countDown(); s.writeDone.release()
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) s.mtu = mtu
                    s.mtuDone.countDown()
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    g.getService(NUS_SERVICE)?.let { svc -> s.rx = svc.getCharacteristic(NUS_RX); s.tx = svc.getCharacteristic(NUS_TX) }
                    s.discovered.countDown()
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { s.notifying.countDown() }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { s.writeDone.release() }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    if (c.uuid == NUS_TX) s.incoming.offer(value.copyOf())
                }
                @Deprecated("Android 12 and older")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION") val v = c.value
                    if (Build.VERSION.SDK_INT < 33 && c.uuid == NUS_TX && v != null) s.incoming.offer(v.copyOf())
                }
            }
            val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Bluetooth LE: could not start a connection to $name")
            try {
                await(s.connected, 15, "connection to $name", s)
                gatt.requestMtu(247)
                s.mtuDone.await(5, TimeUnit.SECONDS) // a refused MTU keeps 23 bytes; carry on
                if (!gatt.discoverServices()) throw IOException("Bluetooth LE: service discovery did not start")
                await(s.discovered, 10, "service discovery", s)
                val tx = s.tx ?: throw IOException("$name has no Nordic UART Service: is its Bluetooth set to BLE?")
                if (s.rx == null) throw IOException("$name: Nordic UART Service without RX characteristic")
                gatt.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD) ?: throw IOException("$name: notifications not available")
                val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, on) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run { d.value = on; gatt.writeDescriptor(d) }
                if (!started) throw IOException("Bluetooth LE: could not switch notifications on")
                await(s.notifying, 5, "switching notifications on", s)
            } catch (e: IOException) {
                s.closed = true
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                throw e
            }
            return BleNusLink(gatt, s, LinkInfo(LinkKind.BLUETOOTH, name, "Bluetooth LE · ${device.address} · MTU ${s.mtu}"))
        }
    }
}
