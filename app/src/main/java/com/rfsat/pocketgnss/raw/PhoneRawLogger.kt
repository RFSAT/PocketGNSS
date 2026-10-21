package com.rfsat.pocketgnss.raw

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.rfsat.pocketgnss.protocol.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/** What the Android API says about the phone's GNSS (null = not reported on this Android version). */
data class RawCapabilities(
    val androidSdk: Int,
    val hardware: String?,
    val year: Int?,
    val measurements: Boolean?,
    val navigationMessages: Boolean?,
    val accumulatedDeltaRange: Boolean?,
    val antennaInfo: Boolean?,
    val fullTrackingRequest: Boolean,
)

data class RawState(
    val running: Boolean = false,
    val recording: Boolean = false,
    val dir: File? = null,
    val startedMs: Long = 0,
    val report: RawReport? = null,
    val capabilities: RawCapabilities? = null,
    val rinexEpochs: Int = 0,
    val rtcmBytes: Long = 0,
    val rawLines: Long = 0,
    val error: String? = null,
    /** Callbacks report the measurements but the phone says they are unsupported or location is off. */
    val status: String? = null,
)

/**
 * The phone's raw GNSS measurements: a capability test (what the chip delivers, per system and band) and session
 * recording for post-processing: RINEX 3.04 observations (raw.obs), the same data in GnssLogger format
 * (gnss_log.txt, for Google's MATLAB GNSS Measurement Tools) and, optionally, the caster's RTCM stream (base.rtcm3)
 * recorded at the same time. Files go to a "raw_..." folder next to the NMEA sessions (Log tab).
 */
class PhoneRawLogger(
    private val context: Context,
    private val sessionsRoot: File,
    /** Starts / stops the NTRIP stream for RTCM recording; the engine then calls [onRtcm]. */
    private val rtcmControl: (Boolean) -> Unit,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _state = MutableStateFlow(RawState())
    val state: StateFlow<RawState> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var analyzer = RawAnalyzer()
    private var rinex: RinexObsWriter? = null
    private var log: BufferedWriter? = null
    private var rtcm: BufferedOutputStream? = null
    private var lastDisc: Int? = null
    @Volatile private var lastLocation: Location? = null
    private var lastPublish = 0L
    private var rtcmBytes = 0L
    private var rawLines = 0L
    private var gotEvents = false

    /** Last position from the phone, for the caster's GGA when nothing else gives one. */
    val location: Location? get() = lastLocation

    fun capabilities(): RawCapabilities {
        val sdk = Build.VERSION.SDK_INT
        val caps = if (sdk >= 30) try { lm.javaClass.getMethod("getGnssCapabilities").invoke(lm) } catch (_: Exception) { null } else null
        fun has(vararg names: String): Boolean? {
            val c = caps ?: return null
            for (n in names) {
                val m = try { c.javaClass.getMethod(n) } catch (_: Exception) { null } ?: continue
                return when (val v = try { m.invoke(c) } catch (_: Exception) { null }) {
                    is Boolean -> v
                    is Int -> if (v == 0) null else v == 1 // CAPABILITY_UNKNOWN = 0, SUPPORTED = 1, UNSUPPORTED = 2
                    else -> null
                }
            }
            return null
        }
        return RawCapabilities(
            androidSdk = sdk,
            hardware = if (Build.VERSION.SDK_INT >= 28) lm.gnssHardwareModelName else null,
            year = if (Build.VERSION.SDK_INT >= 28) lm.gnssYearOfHardware.takeIf { it > 0 } else null,
            measurements = has("hasMeasurements"),
            navigationMessages = has("hasNavigationMessages"),
            accumulatedDeltaRange = has("hasAccumulatedDeltaRange"),
            antennaInfo = has("hasAntennaInfo", "hasGnssAntennaInfo"),
            fullTrackingRequest = sdk >= 31,
        )
    }

    private val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) { handle(event) }
        override fun onStatusChanged(status: Int) {
            val s = when (status) {
                GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED -> "not supported"
                GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED -> "location off"
                GnssMeasurementsEvent.Callback.STATUS_NOT_ALLOWED -> "not allowed"
                else -> null
            }
            _state.value = _state.value.copy(status = s)
        }
    }

    private val listener = LocationListener { loc -> lastLocation = loc; logFix(loc) }

    /** Starts the test; with [record] also writes the files, with [withRtcm] also the caster's stream. */
    @SuppressLint("MissingPermission")
    fun start(record: Boolean, withRtcm: Boolean, markerName: String) {
        if (_state.value.running) stop()
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _state.value = RawState(error = "Location is off on this phone: turn it on in quick settings"); return
        }
        analyzer = RawAnalyzer(); lastDisc = null; rtcmBytes = 0; rawLines = 0; gotEvents = false
        val caps = capabilities()
        var dir: File? = null
        if (record) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            dir = File(sessionsRoot, "raw_$stamp").apply { mkdirs() }
            val utc = SimpleDateFormat("yyyyMMdd HHmmss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            rinex = RinexObsWriter(File(dir, "raw.obs"), RinexHeaderInfo(
                markerName = markerName.ifBlank { "PHONE" }, receiverNumber = "UNKNOWN",
                receiverType = "${Build.MANUFACTURER} ${Build.MODEL}".take(20), receiverVersion = "Android ${Build.VERSION.RELEASE}",
                antennaType = "INTERNAL", program = "PocketGNSS", runBy = "RFSAT", createdUtc = utc))
            log = File(dir, "gnss_log.txt").bufferedWriter().also {
                it.write(GnssLog.header("${Build.MANUFACTURER}::${Build.MODEL}", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", "PocketGNSS"))
            }
            if (withRtcm) { rtcm = BufferedOutputStream(File(dir, "base.rtcm3").outputStream()); rtcmControl(true) }
        }
        val t = HandlerThread("raw-gnss").apply { start() }
        thread = t; val h = Handler(t.looper); handler = h
        val executor = Executor { r -> h.post(r) }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Full tracking switches off duty cycling, which otherwise breaks the carrier phase.
                lm.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), executor, callback)
            } else {
                @Suppress("DEPRECATION") lm.registerGnssMeasurementsCallback(callback, h)
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, t.looper)
        } catch (e: SecurityException) {
            _state.value = RawState(error = "Location permission missing"); closeFiles(); return
        } catch (e: Exception) {
            _state.value = RawState(error = e.message ?: e.javaClass.simpleName); closeFiles(); return
        }
        _state.value = RawState(running = true, recording = record, dir = dir, startedMs = System.currentTimeMillis(), capabilities = caps)
    }

    fun stop() {
        try { lm.unregisterGnssMeasurementsCallback(callback) } catch (_: Exception) {}
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        val h = handler
        if (h != null) {
            // Finish the files on the logging thread, after any pending event.
            val done = java.util.concurrent.CountDownLatch(1)
            h.post { closeFiles(); done.countDown() }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } else closeFiles()
        thread?.quitSafely(); thread = null; handler = null
        _state.value = _state.value.copy(running = false, recording = false, report = analyzer.report())
    }

    private fun closeFiles() {
        val r = rinex; rinex = null
        val xyz = lastLocation?.let { Geo.geodeticToEcef(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else 0.0) }
        try { r?.finish(xyz) } catch (_: Exception) {}
        try { log?.close() } catch (_: Exception) {}; log = null
        if (rtcm != null) { try { rtcm?.close() } catch (_: Exception) {}; rtcm = null; rtcmControl(false) }
    }

    /** RTCM bytes from the NTRIP client (any thread). */
    fun onRtcm(data: ByteArray) {
        val out = rtcm ?: return
        synchronized(out) { try { out.write(data); rtcmBytes += data.size } catch (_: Exception) {} }
    }

    private fun handle(event: GnssMeasurementsEvent) {
        gotEvents = true
        val c = toClock(event.clock)
        val ms = event.measurements.map { toMeasurement(it) }
        analyzer.add(c, ms)
        log?.let { w -> for (m in ms) { w.write(GnssLog.raw(c, m)); w.write("\n"); rawLines++ } }
        rinex?.let { w -> RawConvert.epoch(c, ms, lastDisc)?.let { w.add(it) } }
        lastDisc = c.hardwareClockDiscontinuityCount
        val now = System.currentTimeMillis()
        if (now - lastPublish >= 1000) {
            lastPublish = now
            log?.flush()
            _state.value = _state.value.copy(report = analyzer.report(), rinexEpochs = rinex?.epochs ?: 0, rtcmBytes = rtcmBytes, rawLines = rawLines)
        }
    }

    private fun logFix(l: Location) {
        val w = log ?: return
        handler?.post {
            try {
                w.write(GnssLog.fix(l.provider ?: "gps", l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
                    if (l.hasSpeed()) l.speed else null, if (l.hasAccuracy()) l.accuracy else null, if (l.hasBearing()) l.bearing else null,
                    l.time, l.elapsedRealtimeNanos, if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null))
                w.write("\n")
            } catch (_: Exception) {}
        }
    }

    private fun toClock(k: GnssClock) = RawClock(
        timeNanos = k.timeNanos,
        fullBiasNanos = if (k.hasFullBiasNanos()) k.fullBiasNanos else null,
        biasNanos = if (k.hasBiasNanos()) k.biasNanos else 0.0,
        biasUncertaintyNanos = if (k.hasBiasUncertaintyNanos()) k.biasUncertaintyNanos else null,
        driftNanosPerSecond = if (k.hasDriftNanosPerSecond()) k.driftNanosPerSecond else null,
        driftUncertaintyNanosPerSecond = if (k.hasDriftUncertaintyNanosPerSecond()) k.driftUncertaintyNanosPerSecond else null,
        timeUncertaintyNanos = if (k.hasTimeUncertaintyNanos()) k.timeUncertaintyNanos else null,
        leapSecond = if (k.hasLeapSecond()) k.leapSecond else null,
        hardwareClockDiscontinuityCount = k.hardwareClockDiscontinuityCount,
        utcTimeMillis = System.currentTimeMillis(),
        elapsedRealtimeNanos = if (Build.VERSION.SDK_INT >= 29 && k.hasElapsedRealtimeNanos()) k.elapsedRealtimeNanos else null,
    )

    @Suppress("DEPRECATION")
    private fun toMeasurement(m: GnssMeasurement) = RawMeasurement(
        constellation = m.constellationType,
        svid = m.svid,
        carrierHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
        codeType = if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) m.codeType else null,
        timeOffsetNanos = m.timeOffsetNanos,
        state = m.state,
        receivedSvTimeNanos = m.receivedSvTimeNanos,
        receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos,
        cn0DbHz = m.cn0DbHz,
        pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
        pseudorangeRateUncertaintyMps = m.pseudorangeRateUncertaintyMetersPerSecond,
        adrState = m.accumulatedDeltaRangeState,
        adrMeters = m.accumulatedDeltaRangeMeters,
        adrUncertaintyMeters = m.accumulatedDeltaRangeUncertaintyMeters,
        multipath = m.multipathIndicator,
        snrDb = if (m.hasSnrInDb()) m.snrInDb else null,
        agcDb = if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null,
        basebandCn0DbHz = if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else null,
        fullIsbNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else null,
        fullIsbUncertaintyNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos else null,
        satelliteIsbNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else null,
        satelliteIsbUncertaintyNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasSatelliteInterSignalBiasUncertaintyNanos()) m.satelliteInterSignalBiasUncertaintyNanos else null,
    )

    /** True when measurement events arrived (false after a test = the phone delivers no raw measurements). */
    val receivedAny: Boolean get() = gotEvents
}
