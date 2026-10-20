package com.rfsat.pocketgnss.link

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone's own GNSS chip as a source, for using the app without the RFSAT receiver (or comparing against it).
 * Location and GnssStatus are converted to NMEA by [PhoneNmea]. Read-only: corrections (RTCM) and u-blox commands
 * cannot be sent to the phone's chip. Positions that are themselves mock locations (for example this app's own
 * "Publish as phone location" output) are ignored, so the app never reads back its own output.
 */
class PhoneGnssLink private constructor(private val context: Context) : GnssLink {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var sats: List<PhoneSat> = emptyList()
    @Volatile private var lastFixAt = 0L

    override val info = LinkInfo(LinkKind.PHONE, "Phone GNSS (${Build.MANUFACTURER} ${Build.MODEL})".replace("  ", " "),
        "Android location" + (if (Build.VERSION.SDK_INT >= 28) lm.gnssHardwareModelName?.let { " · $it" } ?: "" else ""))

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            sats = (0 until status.satelliteCount).map { i ->
                PhoneSat(status.getConstellationType(i), status.getSvid(i), status.getCn0DbHz(i), status.getElevationDegrees(i),
                    status.getAzimuthDegrees(i), status.usedInFix(i), if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f)
            }
            // Without a position yet, still report the satellites once a second (searching).
            if (System.currentTimeMillis() - lastFixAt > 1500) emit(null)
        }
    }

    private val listener = LocationListener { loc -> if (!isMock(loc)) { lastFixAt = System.currentTimeMillis(); emit(loc) } }

    @Suppress("DEPRECATION")
    private fun isMock(l: Location) = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    private fun emit(l: Location?) {
        if (closed) return
        val fix = l?.let {
            PhoneFix(it.time.takeIf { t -> t > 0 } ?: System.currentTimeMillis(), it.latitude, it.longitude,
                if (it.hasAltitude()) it.altitude else null,
                if (Build.VERSION.SDK_INT >= 34 && it.hasMslAltitude()) it.mslAltitudeMeters else null,
                if (it.hasAccuracy()) it.accuracy else null,
                if (it.hasVerticalAccuracy()) it.verticalAccuracyMeters else null,
                if (it.hasSpeed()) it.speed else null, if (it.hasBearing()) it.bearing else null)
        }
        val text = PhoneNmea.epoch(fix, sats).joinToString("") { it + "\r\n" }
        queue.offer(text.toByteArray(Charsets.US_ASCII))
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) throw IOException("Location is off on this phone: turn it on in quick settings")
        main.post {
            try {
                lm.registerGnssStatusCallback(statusCallback, main)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) { closed = true }
        }
    }

    private var rest: ByteArray? = null

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("Phone GNSS stopped")
        val b = rest ?: queue.poll(200, TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(b.size, buffer.size)
        b.copyInto(buffer, 0, 0, n)
        rest = if (n < b.size) b.copyOfRange(n, b.size) else null // a long epoch continues before the next one
        return n
    }

    /** The phone's chip accepts neither RTCM nor UBX: writes are dropped. */
    override fun write(data: ByteArray) {}

    override fun close() {
        closed = true
        main.post {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            try { lm.unregisterGnssStatusCallback(statusCallback) } catch (_: Exception) {}
        }
    }

    companion object {
        @Throws(IOException::class)
        fun open(context: Context): PhoneGnssLink = PhoneGnssLink(context.applicationContext).also { it.start() }
    }
}
