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
import com.rfsat.pocketgnss.rtk.IonoChooser
import com.rfsat.pocketgnss.rtk.PppConvergence
import com.rfsat.pocketgnss.rtk.PhoneProfile
import com.rfsat.pocketgnss.rtk.PositionCheck
import com.rfsat.pocketgnss.rtk.ProfileLearner
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.forEngine
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
    /** Method of the last position emitted: "RTK fixed", "RTK float", "PPP", "PPP converging", "Code DGNSS", "Single (RTKLIB)",
     *  "Android location". */
    val source: String = "",
    /** PPP mode: the engine uses the ionosphere-free combination (false: broadcast ionosphere, L1 only). */
    val ionoFree: Boolean = true,
    /** Engine solutions not used because they were too far from the phone's own fix ([PositionCheck]), and filter restarts. */
    val rejected: Int = 0,
    val resets: Int = 0,
    /** The phone's own fix beside the engine's: its reported horizontal accuracy (m) and its distance from the engine solution (m). */
    val phoneAcc: Double? = null,
    val phoneDistance: Double? = null,
    /** Android has held its fix on one point for [PhoneRtkLink.FROZEN_MS] or more ("static hold" while the phone is still). */
    val phoneFrozen: Boolean = false,
    /** Epochs since the engine last produced a solution (0 = this one did). */
    val epochsWithoutSolution: Int = 0,
    /** C/N0 mask in use (dB-Hz, 0 = none) and the configured one: lower while too few satellites pass the configured one. */
    val snrMask: Double = 0.0,
    val snrMaskConfigured: Double = 0.0,
    /** GPS time of the latest rover epoch (for the age of the corrections while there is no solution). */
    val epochWeek: Int = 0,
    val epochTow: Double = 0.0,
)

/**
 * Phone RTK: the phone's own GNSS chip as an RTK rover. Raw measurements (GnssMeasurement API, full tracking) are
 * converted by [RawConvert] and processed every epoch by RTKLIB demo5 in native code ([RtkNative]) together with the
 * caster's RTCM 3, which the existing NTRIP relay writes to this link exactly as it would to an external receiver
 * (the relay also sends the caster this link's GGA). The solution is emitted as NMEA (GGA quality 4 / 5 for RTK fixed
 * / float, GST from the filter's covariance), so every screen, the recorder and the outputs work unchanged.
 *
 * With [RtkOptions.ppp] there is no base: the caster stream carries SSR corrections (Galileo HAS, IGS real-time) and the
 * engine runs float precise point positioning, reported in GGA as differential (quality 2), never as RTK.
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
    /** The one native engine's lock (shared with every other user of the engine). */
    private val engineLock = RtkNative.lock
    private val arrays = RoverArrays()
    private val solOut = DoubleArray(RtkSolution.SIZE)
    private val baseOut = DoubleArray(RtkBase.SIZE)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var sats: List<PhoneSat> = emptyList()
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastLocationMs = 0L
    /** When the phone's own fix last changed position. */
    @Volatile private var phoneMovedMs = 0L
    /** L1 code down-weighted for reflections in the last solution (logged when it changes). */
    private var l1Down = false
    private var snrMask = options.snrMaskDbHz
    private var noDataSince = 0L
    private val check = PositionCheck()
    @Volatile private var lastSolutionMs = 0L
    private var lastDisc: Int? = null
    private var lastEmitMs = 0L
    private val iono = IonoChooser(options.ionoFree)
    private val convergence = PppConvergence()

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
        val prev = lastLocation
        if (prev == null || prev.latitude != loc.latitude || prev.longitude != loc.longitude) phoneMovedMs = System.currentTimeMillis()
        lastLocation = loc; lastLocationMs = System.currentTimeMillis()
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
        val ok = RtkNative.startFor(options, this)
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
            // The first seconds after the GNSS starts (also after the screen comes back on) have no full clock bias yet.
            val now = System.currentTimeMillis()
            if (noDataSince == 0L) noDataSince = now
            if (now - noDataSince > 20_000) com.rfsat.pocketgnss.core.AppLog.throttled("raw-empty", 60_000, com.rfsat.pocketgnss.core.AppLog.Level.WARN, "Phone RTK",
                if (e == null) "No usable raw measurements for ${(now - noDataSince) / 1000} s: the phone gives no full clock bias" else "No usable signals in the phone's measurements")
            _state.value = _state.value.copy(message = if (e == null) "no full clock bias from the phone" else "no usable signals")
            return
        }
        noDataSince = 0L
        arrays.fill(e.forEngine(options))
        // Learn this phone: satellites with carrier phase, and how many slipped, per epoch; saved about once a minute.
        val phaseSats = e.obs.filter { it.phase != null }.map { it.sat }.toSet()
        val slipSats = e.obs.filter { it.phase != null && it.lli and 1 != 0 }.map { it.sat }.toSet()
        learner.add(phaseSats.size, slipSats.size)
        if (++learnedEpochs % 60 == 0) onProfile(learner.profile())
        // PPP on a phone without a second frequency: broadcast ionosphere instead of the ionosphere-free combination.
        val dual = if (options.ppp) IonoChooser.dualSatellites(e) else 0
        if (options.ppp) iono.update(dual)?.let { f ->
            synchronized(engineLock) { if (RtkNative.owner === this) RtkNative.setIono(if (f) 1 else 0) }; convergence.reset()
            com.rfsat.pocketgnss.core.AppLog.i("Phone RTK", if (f) "PPP: dual frequency ($dual satellites with L1 + L5 code and carrier phase), ionosphere-free combination"
                else "PPP: single frequency (only $dual satellites with L1 + L5 code and carrier phase for 90 s), broadcast ionosphere model")
        }
        val (status, sol, base, msg) = synchronized(engineLock) {
            if (closed || RtkNative.owner !== this) return
            val st = RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d,
                arrays.snr, arrays.lli, solOut)
            RtkNative.base(baseOut)
            // Untrusted fixes are reported as float (with the float position), see FixGate.
            val gated = com.rfsat.pocketgnss.rtk.HonestAccuracy.apply(FixGate.apply(RtkSolution.from(solOut), options))
            Quad(gated.status.code.takeIf { st != 0 } ?: 0, gated, RtkBase.from(baseOut), RtkNative.message())
        }
        if (status != 0 && sol.l1DownWeighted != l1Down) {
            l1Down = sol.l1DownWeighted
            com.rfsat.pocketgnss.core.AppLog.i("Phone RTK", if (l1Down)
                "L1 code reflected (residuals %.0f m RMS): L1 code given 10x less weight than L5".format(java.util.Locale.US, sol.l1CodeRms)
                else "L1 code clean again (residuals %.0f m RMS): full weight".format(java.util.Locale.US, sol.l1CodeRms))
        }
        if (!options.ppp && sol.snrMask != snrMask) {
            com.rfsat.pocketgnss.core.AppLog.i("Phone RTK", when {
                sol.snrMask > snrMask -> "Signals stronger again: the engine uses signals from %.0f dB-Hz".format(java.util.Locale.US, sol.snrMask)
                sol.snrMask > 0 -> "Weak signals (fewer than 8 satellites at %.0f dB-Hz or more): the engine now uses signals down to %.0f dB-Hz"
                    .format(java.util.Locale.US, snrMask, sol.snrMask)
                else -> "Weak signals (fewer than 8 satellites at %.0f dB-Hz or more): the engine now uses all signals".format(java.util.Locale.US, snrMask)
            })
            snrMask = sol.snrMask
        }
        val withPhase = e.obs.count { it.phase != null }
        val now = System.currentTimeMillis()
        val phone = lastLocation?.takeIf { now - lastLocationMs <= 2_500 }
        val shown = if (status != 0) sol else _state.value.solution
        val idle = if (status != 0) 0 else _state.value.epochsWithoutSolution + 1
        _state.value = PhoneRtkState(shown, base, _state.value.epochs + 1, e.obs.size, withPhase,
            msg.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "", noPhase = withPhase == 0, source = _state.value.source,
            ionoFree = iono.ionoFree, rejected = check.rejected, resets = check.resets,
            phoneAcc = phone?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            phoneDistance = if (phone != null && status != 0 && shown != null && shown.status != RtkStatus.NONE)
                com.rfsat.pocketgnss.protocol.Geo.distance(shown.latitude, shown.longitude, phone.latitude, phone.longitude) else null,
            phoneFrozen = phone != null && phoneMovedMs > 0 && now - phoneMovedMs >= FROZEN_MS, epochsWithoutSolution = idle,
            snrMask = snrMask, snrMaskConfigured = if (options.ppp) 0.0 else options.snrMaskDbHz,
            epochWeek = e.gpsWeek, epochTow = e.towSeconds)
        if (status == RtkStatus.NONE.code) {
            // A filter that has gone wrong (PPP 21 m from the phone's fix on a roof among houses, then every carrier
            // phase rejected as an outlier) gives no solution and is never checked against the phone's fix, so it
            // would stay stuck: with corrections, plenty of carrier phase and still no solution for STUCK_EPOCHS, restart it.
            val corrected = if (options.ppp) base.ssrSatellites > 0 else base.hasPosition && base.satellites > 0
            if (corrected && idle >= STUCK_EPOCHS && idle % STUCK_EPOCHS == 0 && withPhase >= STUCK_MIN_PHASE) {
                synchronized(engineLock) { if (RtkNative.owner === this) RtkNative.reset() }
                convergence.reset()
                com.rfsat.pocketgnss.core.AppLog.w("Phone RTK", "No solution for $idle s although $withPhase signals have carrier phase" +
                    (msg.trim().lineSequence().lastOrNull { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": the engine's filter is restarted")
            }
            return
        }
        // The phone's own fix as a sanity check: a solution far from it is not used, and a filter stuck there restarts.
        val verdict = check.check(now, sol.latitude, sol.longitude, kotlin.math.hypot(sol.stdEast, sol.stdNorth),
            phone?.latitude, phone?.longitude, phone?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
        if (verdict != PositionCheck.Verdict.USE) {
            if (verdict == PositionCheck.Verdict.REJECT_AND_RESET) { synchronized(engineLock) { if (RtkNative.owner === this) RtkNative.reset() }; convergence.reset() }
            _state.value = _state.value.copy(rejected = check.rejected, resets = check.resets)
            phone?.let { emitLocation(it) }
            return
        }
        lastSolutionMs = now
        val phoneAcc = phone?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        val engineAcc = kotlin.math.hypot(sol.stdEast, sol.stdNorth)
        val dist = phone?.let { com.rfsat.pocketgnss.protocol.Geo.distance(sol.latitude, sol.longitude, it.latitude, it.longitude) }
        if (phone != null && PositionCheck.preferPhone(sol.status, engineAcc, phoneAcc, dist)) {
            if (sol.status == RtkStatus.FLOAT) com.rfsat.pocketgnss.core.AppLog.throttled("floatDisagrees", 60_000, com.rfsat.pocketgnss.core.AppLog.Level.INFO,
                "Phone RTK", "RTK float %.0f m from the phone's own fix (float ±%.1f m, phone ±%.1f m): still converging, the phone's fix is shown until they agree"
                    .format(java.util.Locale.US, dist, engineAcc, phoneAcc))
            emitLocation(phone); return
        }
        emitSolution(sol, base, convergence.converging(sol))
    }

    private fun emitSolution(s: RtkSolution, base: RtkBase, converging: Boolean) {
        if (emit(fixOf(s, base), System.currentTimeMillis())) _state.value = _state.value.copy(source = methodName(s.status, converging))
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
        synchronized(engineLock) { if (RtkNative.owner === this) RtkNative.inputRtcm(data, 0, data.size) }
    }

    /** Broadcast ephemerides from a second caster stream (e.g. BKG's BCEP00BKG0): orbits only, never base data. */
    fun writeNav(data: ByteArray) {
        if (closed) return
        synchronized(engineLock) { if (RtkNative.owner === this) RtkNative.inputRtcmNav(data, 0, data.size) }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (learnedEpochs > 0) onProfile(learner.profile())
        try { lm.unregisterGnssMeasurementsCallback(measurementCallback) } catch (_: Exception) {}
        try { lm.unregisterGnssStatusCallback(statusCallback) } catch (_: Exception) {}
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        val h = handler
        // Freed here and now (not later on the engine thread, where it could hit the next link's engine); an epoch
        // still queued on that thread finds the engine no longer its own and returns.
        RtkNative.freeFor(this)
        if (h != null) h.post { thread?.quitSafely() }
        current = null
    }

    private data class Quad(val a: Int, val b: RtkSolution, val c: RtkBase, val d: String)

    companion object {
        const val METHOD_ANDROID = "Android location"
        const val METHOD_PPP = "PPP"
        const val METHOD_PPP_CONVERGING = "PPP converging"

        fun methodName(st: RtkStatus, converging: Boolean = false) = when (st) {
            RtkStatus.FIX -> "RTK fixed"
            RtkStatus.FLOAT -> "RTK float"
            RtkStatus.PPP -> if (converging) METHOD_PPP_CONVERGING else METHOD_PPP
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
            val ppp = s.status == RtkStatus.PPP
            // PPP is in ITRF2020 at the observation epoch; in Europe it is given in ETRS89 (ETRF2000) like RTK networks.
            val p = if (ppp && com.rfsat.pocketgnss.protocol.Datum.inEtrfArea(s.latitude, s.longitude))
                com.rfsat.pocketgnss.protocol.Datum.itrf2020ToEtrf2000(s.latitude, s.longitude, s.height,
                    com.rfsat.pocketgnss.protocol.Datum.decimalYear(s.week, s.tow))
            else com.rfsat.pocketgnss.protocol.Geodetic(s.latitude, s.longitude, s.height)
            return PhoneFix(timeMs = utcMs, latitude = p.latitude, longitude = p.longitude, altitude = p.height,
                altitudeMsl = p.height - s.geoid, accuracy = kotlin.math.hypot(s.stdEast, s.stdNorth).toFloat(), verticalAccuracy = s.stdUp.toFloat(),
                speed = s.speed.toFloat(), bearing = s.courseDeg?.toFloat(), quality = s.status.ggaQuality,
                diffAgeSec = if (differential) s.age else if (ppp && base.ssrTow > 0) pppAge(s, base) else null,
                stationId = if (differential) base.stationId else null,
                stdEast = s.stdEast, stdNorth = s.stdNorth, stdUp = s.stdUp, satellitesUsed = s.satellites)
        }

        /** Age of the SSR clock corrections at the solution time, s. */
        /** Age of the SSR corrections at the rover's latest epoch (s). */
        fun ssrAge(st: PhoneRtkState, base: RtkBase): Double =
            ((st.epochWeek - base.ssrWeek) * 604_800.0 + st.epochTow - base.ssrTow).coerceAtLeast(0.0)

        fun pppAge(s: RtkSolution, base: RtkBase): Double =
            ((s.week - base.ssrWeek) * 604_800.0 + s.tow - base.ssrTow).coerceAtLeast(0.0)

        /** One NMEA epoch (bytes) for a solution, without satellites in view (session replay). */
        fun nmea(s: RtkSolution, base: RtkBase): ByteArray =
            PhoneNmea.epoch(fixOf(s, base), emptyList()).joinToString("") { it + "\r\n" }.toByteArray(Charsets.US_ASCII)

        /** Lets a session replay show its engine state where phone RTK shows it. */
        fun publish(st: PhoneRtkState) { shared.value = st }

        /** The phone's own fix counts as held ("static hold") after this long on exactly one point. */
        const val FROZEN_MS = 30_000L
        /** Epochs (s) without a solution, despite [STUCK_MIN_PHASE] signals with carrier phase, after which the filter restarts. */
        const val STUCK_EPOCHS = 60
        const val STUCK_MIN_PHASE = 10

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
