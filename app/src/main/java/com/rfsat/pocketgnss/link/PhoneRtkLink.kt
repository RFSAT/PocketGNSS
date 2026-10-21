package com.rfsat.pocketgnss.link

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.rfsat.pocketgnss.raw.AndroidRaw
import com.rfsat.pocketgnss.raw.RawConvert
import com.rfsat.pocketgnss.rtk.FixGate
import com.rfsat.pocketgnss.rtk.PhoneProfile
import com.rfsat.pocketgnss.rtk.ProfileLearner
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.RtkBase
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the phone RTK engine is doing, for the Live and Corrections screens. */
data class PhoneRtkState(
    val solution: RtkSolution? = null,
    val base: RtkBase? = null,
    val epochs: Long = 0,
    val signals: Int = 0,
    val withPhase: Int = 0,
    val message: String = "",
    /** True while measurements arrive without carrier phase (code-only phone or duty cycling). */
    val noPhase: Boolean = false,
    /** Method of the last position emitted: "RTK fixed", "RTK float", "Code DGNSS", "Single (RTKLIB)", "Android location". */
    val source: String = "",
)

/**
 * Phone RTK: the phone's own GNSS chip as an RTK rover. Raw measurements (GnssMeasurement API, full tracking) are
 * converted by [RawConvert] and processed every epoch by RTKLIB demo5 in native code ([RtkNative]) together with the
 * caster's RTCM 3, which the existing NTRIP relay writes to this link exactly as it would to an external receiver
 * (the relay also sends the caster this link's GGA). The solution is emitted as NMEA (GGA quality 4 / 5 for RTK fixed
 * / float, GST from the filter's covariance), so every screen, the recorder and the outputs work unchanged.
 *
 * Without a caster the engine gives a single-point solution; without usable raw measurements (or before the first
 * solution) Android's own location is used, as with the plain phone source.
 */
class PhoneRtkLink private constructor(
    private val context: Context,
    private val options: RtkOptions,
    profile: PhoneProfile,
    private val onProfile: (PhoneProfile) -> Unit,
) : GnssLink {
    private val learner = ProfileLearner(profile, options.minFixSats)
    private var learnedEpochs = 0
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val engineLock = Any()
    private val arrays = RoverArrays()
    private val solOut = DoubleArray(RtkSolution.SIZE)
    private val baseOut = DoubleArray(RtkBase.SIZE)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var sats: List<PhoneSat> = emptyList()
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastSolutionMs = 0L
    private var lastDisc: Int? = null
    private var lastEmitMs = 0L

    private val _state get() = shared

    override val info = LinkInfo(LinkKind.PHONE, "Phone RTK (${Build.MANUFACTURER} ${Build.MODEL})".replace("  ", " "),
        "RTKLIB demo5 · raw GNSS" + (if (Build.VERSION.SDK_INT >= 28) lm.gnssHardwareModelName?.let { " · $it" } ?: "" else ""),
        phoneRtk = true)

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            sats = (0 until status.satelliteCount).map { i ->
                PhoneSat(status.getConstellationType(i), status.getSvid(i), status.getCn0DbHz(i), status.getElevationDegrees(i),
                    status.getAzimuthDegrees(i), status.usedInFix(i), if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f)
            }
        }
    }

    private val listener = LocationListener { loc ->
        if (isMock(loc)) return@LocationListener
        lastLocation = loc
        // Before the engine has a solution (or when raw measurements stop), Android's position keeps the link alive.
        if (System.currentTimeMillis() - lastSolutionMs > 3000) emitLocation(loc)
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) { epoch(event) }
    }

    @Suppress("DEPRECATION")
    private fun isMock(l: Location) = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) throw IOException("Location is off on this phone: turn it on in quick settings")
        val ok = synchronized(engineLock) {
            RtkNative.init(options.mode.code, options.navsys, options.elevationMaskDeg, options.snrMaskDbHz, options.arMode,
                if (options.glonassAr) 1 else 0, options.errorRatio, options.arRatio, options.maxBaseAgeSec, options.minFixSats)
        }
        if (!ok) throw IOException("Phone RTK engine could not start")
        shared.value = PhoneRtkState()
        val t = HandlerThread("phone-rtk").apply { start() }
        thread = t
        val h = Handler(t.looper); handler = h
        val executor = Executor { r -> h.post(r) }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Full tracking: no duty cycling, so the carrier phase stays continuous.
                lm.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), executor, measurementCallback)
            } else {
                @Suppress("DEPRECATION") lm.registerGnssMeasurementsCallback(measurementCallback, h)
            }
            lm.registerGnssStatusCallback(statusCallback, h)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, t.looper)
        } catch (e: SecurityException) {
            close(); throw IOException("Location permission missing")
        }
    }

    private fun epoch(event: GnssMeasurementsEvent) {
        if (closed) return
        val c = AndroidRaw.clock(event.clock)
        val ms = event.measurements.map { AndroidRaw.measurement(it) }
        val e = RawConvert.epoch(c, ms, lastDisc)
        lastDisc = c.hardwareClockDiscontinuityCount
        if (e == null || e.obs.isEmpty()) {
            _state.value = _state.value.copy(message = if (e == null) "no full clock bias from the phone" else "no usable signals")
            return
        }
        arrays.fill(e)
        // Learn this phone: satellites with carrier phase, and how many slipped, per epoch; saved about once a minute.
        val phaseSats = e.obs.filter { it.phase != null }.map { it.sat }.toSet()
        val slipSats = e.obs.filter { it.phase != null && it.lli and 1 != 0 }.map { it.sat }.toSet()
        learner.add(phaseSats.size, slipSats.size)
        if (++learnedEpochs % 60 == 0) onProfile(learner.profile())
        val (status, sol, base, msg) = synchronized(engineLock) {
            val st = RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d,
                arrays.snr, arrays.lli, solOut)
            RtkNative.base(baseOut)
            // Untrusted fixes are reported as float (with the float position), see FixGate.
            val gated = FixGate.apply(RtkSolution.from(solOut), options)
            Quad(gated.status.code.takeIf { st != 0 } ?: 0, gated, RtkBase.from(baseOut), RtkNative.message())
        }
        val withPhase = e.obs.count { it.phase != null }
        _state.value = PhoneRtkState(if (status != 0) sol else _state.value.solution, base, _state.value.epochs + 1, e.obs.size, withPhase,
            msg.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "", noPhase = withPhase == 0, source = _state.value.source)
        if (status == RtkStatus.NONE.code) return
        lastSolutionMs = System.currentTimeMillis()
        emitSolution(sol, base)
    }

    private fun emitSolution(s: RtkSolution, base: RtkBase) {
        if (emit(fixOf(s, base), System.currentTimeMillis())) _state.value = _state.value.copy(source = methodName(s.status))
    }

    private fun emitLocation(l: Location) {
        val fix = PhoneFix(l.time.takeIf { it > 0 } ?: System.currentTimeMillis(), l.latitude, l.longitude,
            if (l.hasAltitude()) l.altitude else null,
            if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else null,
            if (l.hasAccuracy()) l.accuracy else null, if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
            if (l.hasSpeed()) l.speed else null, if (l.hasBearing()) l.bearing else null)
        if (emit(fix, System.currentTimeMillis())) _state.value = _state.value.copy(source = METHOD_ANDROID)
    }

    /** Queues one NMEA epoch; false when dropped (at most 5 epochs per second). */
    private fun emit(fix: PhoneFix, now: Long): Boolean {
        if (closed || now - lastEmitMs < 200) return false
        lastEmitMs = now
        queue.offer(PhoneNmea.epoch(fix, sats).joinToString("") { it + "\r\n" }.toByteArray(Charsets.US_ASCII))
        return true
    }

    private var rest: ByteArray? = null

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("Phone RTK stopped")
        val b = rest ?: queue.poll(200, TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(b.size, buffer.size)
        b.copyInto(buffer, 0, 0, n)
        rest = if (n < b.size) b.copyOfRange(n, b.size) else null
        return n
    }

    /** RTCM 3 from the NTRIP relay (caster). UBX commands and anything else are ignored by the decoder. */
    override fun write(data: ByteArray) {
        if (closed) return
        synchronized(engineLock) { RtkNative.inputRtcm(data, 0, data.size) }
    }

    /** Broadcast ephemerides from a second caster stream (e.g. BKG's BCEP00BKG0): orbits only, never base data. */
    fun writeNav(data: ByteArray) {
        if (closed) return
        synchronized(engineLock) { RtkNative.inputRtcmNav(data, 0, data.size) }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (learnedEpochs > 0) onProfile(learner.profile())
        try { lm.unregisterGnssMeasurementsCallback(measurementCallback) } catch (_: Exception) {}
        try { lm.unregisterGnssStatusCallback(statusCallback) } catch (_: Exception) {}
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        val h = handler
        if (h != null) h.post { synchronized(engineLock) { RtkNative.free() }; thread?.quitSafely() }
        else synchronized(engineLock) { RtkNative.free() }
        current = null
    }

    private data class Quad(val a: Int, val b: RtkSolution, val c: RtkBase, val d: String)

    companion object {
        const val METHOD_ANDROID = "Android location"

        fun methodName(st: RtkStatus) = when (st) {
            RtkStatus.FIX -> "RTK fixed"
            RtkStatus.FLOAT -> "RTK float"
            RtkStatus.DGPS -> "Code DGNSS"
            RtkStatus.SBAS -> "SBAS"
            RtkStatus.SINGLE -> "Single (RTKLIB)"
            RtkStatus.NONE -> "none"
        }

        /** The NMEA position of an engine solution (GGA quality, GST from the filter, correction age and station). */
        fun fixOf(s: RtkSolution, base: RtkBase): PhoneFix {
            // GPS time of the solution -> UTC milliseconds for the NMEA time fields.
            val leap = 18_000L
            val utcMs = (s.week * 604_800_000L + (s.tow * 1000).toLong()) + GPS_EPOCH_MS - leap
            val differential = s.status == RtkStatus.FIX || s.status == RtkStatus.FLOAT || s.status == RtkStatus.DGPS
            return PhoneFix(timeMs = utcMs, latitude = s.latitude, longitude = s.longitude, altitude = s.height,
                altitudeMsl = s.height - s.geoid, accuracy = kotlin.math.hypot(s.stdEast, s.stdNorth).toFloat(), verticalAccuracy = s.stdUp.toFloat(),
                speed = s.speed.toFloat(), bearing = s.courseDeg?.toFloat(), quality = s.status.ggaQuality,
                diffAgeSec = if (differential) s.age else null, stationId = if (differential) base.stationId else null,
                stdEast = s.stdEast, stdNorth = s.stdNorth, stdUp = s.stdUp, satellitesUsed = s.satellites)
        }

        /** One NMEA epoch (bytes) for a solution, without satellites in view (session replay). */
        fun nmea(s: RtkSolution, base: RtkBase): ByteArray =
            PhoneNmea.epoch(fixOf(s, base), emptyList()).joinToString("") { it + "\r\n" }.toByteArray(Charsets.US_ASCII)

        /** Lets a session replay show its engine state where phone RTK shows it. */
        fun publish(st: PhoneRtkState) { shared.value = st }

        /** 6 Jan 1980 00:00:00 UTC in Unix milliseconds. */
        private const val GPS_EPOCH_MS = 315_964_800_000L

        /** The running link, for the screens (one at a time). */
        @Volatile var current: PhoneRtkLink? = null
            private set

        private val shared = MutableStateFlow(PhoneRtkState())

        /** Engine state of the running (or last) phone RTK link. */
        val state: StateFlow<PhoneRtkState> = shared.asStateFlow()

        /** True when the RTK engine is built into this app for this phone's processor. */
        val engineAvailable: Boolean get() = RtkNative.available

        @Throws(IOException::class)
        fun open(context: Context, options: RtkOptions, profile: PhoneProfile, onProfile: (PhoneProfile) -> Unit): PhoneRtkLink {
            if (!RtkNative.available) throw IOException("Phone RTK engine not available on this phone")
            current?.close()
            return PhoneRtkLink(context.applicationContext, options, profile, onProfile).also { it.start(); current = it }
        }
    }
}
