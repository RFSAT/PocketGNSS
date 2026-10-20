package com.rfsat.pocketgnss.core

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.rfsat.pocketgnss.protocol.FixQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

enum class MockStatus { OFF, ACTIVE, NOT_ALLOWED, ERROR }

/**
 * Publishes the receiver's position as the phone's GPS position ("mock location"), so that any app (the
 * AGENSO Demarkator, field-mapping or GIS apps, Google Maps) uses the centimetre RTK fix. The user selects
 * PocketGNSS once in Developer options > Select mock location app.
 */
class MockLocation(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val state = MutableStateFlow(MockStatus.OFF)
    val status: StateFlow<MockStatus> = state.asStateFlow()
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }

    fun start() {
        try {
            for (p in providers) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 31) {
                    lm.addTestProvider(p, ProviderProperties.Builder().setHasAltitudeSupport(true).setHasSpeedSupport(true)
                        .setHasBearingSupport(true).setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build())
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(p, false, false, false, false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
                }
                lm.setTestProviderEnabled(p, true)
            }
            state.value = MockStatus.ACTIVE
        } catch (_: SecurityException) {
            state.value = MockStatus.NOT_ALLOWED
        } catch (_: Exception) {
            state.value = MockStatus.ERROR
        }
    }

    fun stop() {
        if (state.value == MockStatus.OFF) return
        for (p in providers) try { lm.setTestProviderEnabled(p, false); lm.removeTestProvider(p) } catch (_: Exception) {}
        state.value = MockStatus.OFF
    }

    fun push(f: GnssFix) {
        if (state.value != MockStatus.ACTIVE || f.quality == FixQuality.NONE) return
        for (p in providers) {
            val loc = Location(p).apply {
                latitude = f.latitude; longitude = f.longitude
                // Android's Location altitude is above the WGS-84 ellipsoid.
                f.ellipsoidHeight?.let { altitude = it }
                accuracy = f.horizontalAccuracy.toFloat()
                verticalAccuracyMeters = f.verticalAccuracy.toFloat()
                f.speedMs?.let { speed = it.toFloat() }
                f.courseDeg?.let { bearing = it.toFloat() }
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = Bundle().apply {
                    putInt("satellites", f.satellitesUsed ?: 0)
                    putInt("fixQuality", f.quality.code)
                    putString("source", "PocketGNSS")
                }
            }
            try { lm.setTestProviderLocation(p, loc) } catch (_: SecurityException) { state.value = MockStatus.NOT_ALLOWED; return } catch (_: Exception) {}
        }
    }
}

/**
 * Serves the receiver's NMEA to TCP clients (other apps on the phone at 127.0.0.1, or PCs on the same
 * network): the role EOS Tools Pro plays on port 3967 in D3.3 §4.3, scenario 1(a) / 1(b).
 */
class NmeaServer {
    private val clients = CopyOnWriteArrayList<Pair<Socket, OutputStream>>()
    @Volatile private var server: ServerSocket? = null
    private val state = MutableStateFlow(0)
    /** Number of connected clients. */
    val clientCount: StateFlow<Int> = state.asStateFlow()
    var error: String? = null; private set

    fun start(port: Int, localOnly: Boolean) {
        stop()
        error = null
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(if (localOnly) InetSocketAddress(InetAddress.getLoopbackAddress(), port) else InetSocketAddress(port))
            server = s
            Thread({
                while (!s.isClosed) {
                    try {
                        val c = s.accept()
                        c.tcpNoDelay = true
                        clients.add(c to c.getOutputStream()); state.value = clients.size
                    } catch (_: IOException) { break }
                }
            }, "nmea-server").apply { isDaemon = true }.start()
        } catch (e: IOException) {
            error = e.message
        }
    }

    fun send(line: String) {
        if (clients.isEmpty()) return
        val b = (line + "\r\n").toByteArray(Charsets.US_ASCII)
        for (c in clients) {
            try { c.second.write(b) } catch (_: IOException) { try { c.first.close() } catch (_: Exception) {}; clients.remove(c); state.value = clients.size }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (c in clients) try { c.first.close() } catch (_: Exception) {}
        clients.clear(); state.value = 0
    }

    val running: Boolean get() = server?.isClosed == false
}
