package com.rfsat.pocketgnss.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.data.FixCsv
import com.rfsat.pocketgnss.data.PointStore
import com.rfsat.pocketgnss.data.SessionRecorder
import com.rfsat.pocketgnss.data.SettingsStore
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.data.TrackPoint
import com.rfsat.pocketgnss.link.BleNusLink
import com.rfsat.pocketgnss.link.BluetoothSppLink
import com.rfsat.pocketgnss.link.GnssLink
import com.rfsat.pocketgnss.link.LinkInfo
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.link.SimulatedLink
import com.rfsat.pocketgnss.link.TcpLink
import com.rfsat.pocketgnss.link.UsbChip
import com.rfsat.pocketgnss.link.UsbSerialLink
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaMsg
import com.rfsat.pocketgnss.protocol.NmeaSentence
import com.rfsat.pocketgnss.protocol.Ntrip
import com.rfsat.pocketgnss.protocol.Quectel
import com.rfsat.pocketgnss.protocol.QuectelEpe
import com.rfsat.pocketgnss.protocol.QuectelPppNav
import com.rfsat.pocketgnss.protocol.QuectelProfile
import com.rfsat.pocketgnss.protocol.RfsatStatus
import com.rfsat.pocketgnss.protocol.StartType
import com.rfsat.pocketgnss.protocol.StreamSplitter
import com.rfsat.pocketgnss.protocol.Ubx
import com.rfsat.pocketgnss.protocol.UbxFrame
import com.rfsat.pocketgnss.protocol.UbxPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

sealed class Connection {
    data object NoDevice : Connection()
    data class Connecting(val label: String) : Connection()
    data object AwaitingPermission : Connection()
    data class Ready(val info: LinkInfo) : Connection()
    data class Failed(val message: String) : Connection()
}

data class ReceiverInfo(val module: String? = null, val firmware: String? = null, val protocol: String? = null,
                        val datum: String? = null, val lastText: String? = null, val ubxAnswered: Boolean = false)

/**
 * Status of an RFSAT LG290P rover (master documentation, P-2): RFSAT's method ladder ($PRFSM), the receiver's PPP / HAS
 * state ($PQTMPPPNAV) and its estimated position error ($PQTMEPE), each with the time it last arrived.
 */
data class RoverStatus(val rfsat: RfsatStatus? = null, val rfsatMs: Long = 0, val ppp: QuectelPppNav? = null, val pppMs: Long = 0,
                       val epe: QuectelEpe? = null, val epeMs: Long = 0,
                       /** Horizontal 1-sigma from GST, shown next to the EPE for comparison. */
                       val gstH: Double? = null, val gstMs: Long = 0) {
    val any: Boolean get() = rfsat != null || ppp != null || epe != null
}

/**
 * Who corrects a receiver that has its own NTRIP client (AgriBIT, PicoRTK, the C6 rover, PostcardRTK, RTK Everywhere)
 * while the phone's NTRIP relay is on (1.13.1). Two correction streams at once break RTK: the receiver's firmware passes
 * bytes from Wi-Fi and from Bluetooth to the GNSS chip as they come, so RTCM frames interleave and fail their CRC, and a
 * caster may allow one login per account. The relay therefore stands by while the receiver's own corrections are in
 * use and takes over when they stop.
 *  - [DIRECT]: the relay feeds the receiver as before (receiver without its own client, or relay off).
 *  - [CHECKING]: relay held back for up to 25 s, watching whether the receiver's own corrections are in use.
 *  - [RECEIVER_OWN]: they are; the relay is disconnected from the caster and takes over 20 s after they stop.
 *  - [FEEDING]: the relay feeds the receiver; if no corrections are in use within the wait, it checks again.
 */
enum class RelayRole { DIRECT, CHECKING, RECEIVER_OWN, FEEDING }

data class RelayArbiter(val role: RelayRole = RelayRole.DIRECT, val sinceMs: Long = 0)

data class StreamStats(val bytes: Long = 0, val nmeaLines: Long = 0, val badChecksums: Long = 0, val garbageBytes: Long = 0,
                       val ubxFrames: Long = 0, val rates: List<RateMeter.Row> = emptyList(), val lastDataMs: Long = 0, val baud: Int? = null)

data class RecordingState(val active: Boolean = false, val dir: File? = null, val fixes: Int = 0, val lines: Long = 0, val startedMs: Long = 0)

data class SurveyState(
    val running: Boolean = false,
    val minQuality: FixQuality = FixQuality.RTK_FIXED,
    val samples: Int = 0,
    val rejected: Int = 0,
    val stats: PositionStats? = null,
    val startedMs: Long = 0,
    val reference: Reference? = null,
    val meanMsl: Double? = null,
)

data class TtffState(val current: TtffRun? = null, val history: List<TtffRun> = emptyList(),
                     /** RTK fixed reacquisition times (s) after the fix was lost, D3.5 GNSS-test-07 (3). */
                     val reacquisitions: List<Double> = emptyList())

sealed class UbxResult {
    data object Ack : UbxResult()
    data object Nak : UbxResult()
    data object Timeout : UbxResult()
    data class Data(val frame: UbxFrame) : UbxResult()
}

/**
 * The receiver connection and everything fed by it. One instance per process (held by [com.rfsat.pocketgnss.GnssApp]).
 */
class GnssEngine(private val context: Context, val store: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connection = MutableStateFlow<Connection>(Connection.NoDevice)
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    private val _fix = MutableStateFlow<GnssFix?>(null)
    val fix: StateFlow<GnssFix?> = _fix.asStateFlow()
    private val _sats = MutableStateFlow<List<SatInfo>>(emptyList())
    val satellites: StateFlow<List<SatInfo>> = _sats.asStateFlow()
    private val _stream = MutableStateFlow(StreamStats())
    val stream: StateFlow<StreamStats> = _stream.asStateFlow()
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()
    private val _receiver = MutableStateFlow(ReceiverInfo())
    val receiver: StateFlow<ReceiverInfo> = _receiver.asStateFlow()
    private val _rover = MutableStateFlow(RoverStatus())
    /** RFSAT rover status sentences, when the receiver sends them. */
    val rover: StateFlow<RoverStatus> = _rover.asStateFlow()
    /** Waiters for Quectel command replies: sentence name and the deferred line. */
    private val quectelWaiters = CopyOnWriteArrayList<Pair<String, CompletableDeferred<String>>>()
    /** Last GST, paired with each GGA for the accuracy comparison. */
    @Volatile private var lastGst: NmeaSentence.Gst? = null
    @Volatile private var lastGstMs = 0L
    private val accStats = AccuracyCompare.Stats()
    private val _accuracy = MutableStateFlow<AccuracyCompare.Summary?>(null)
    /** Running comparison of the receiver's GST and $PQTMEPE over this connection (1.13.3). */
    val accuracy: StateFlow<AccuracyCompare.Summary?> = _accuracy.asStateFlow()
    private val _recording = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()
    private val _track = MutableStateFlow<List<TrackPoint>>(emptyList())
    val track: StateFlow<List<TrackPoint>> = _track.asStateFlow()
    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points: StateFlow<List<SurveyPoint>> = _points.asStateFlow()
    private val _fields = MutableStateFlow<List<com.rfsat.pocketgnss.data.Field>>(emptyList())
    /** Field boundaries (Tools > Field boundary), captured or imported. */
    val fields: StateFlow<List<com.rfsat.pocketgnss.data.Field>> = _fields.asStateFlow()
    /** Boundary capture in progress. */
    val fieldCapture = FieldCapture()
    private val _survey = MutableStateFlow(SurveyState())
    val survey: StateFlow<SurveyState> = _survey.asStateFlow()
    private val _ttff = MutableStateFlow(TtffState())
    val ttff: StateFlow<TtffState> = _ttff.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The phone relay's RTCM reaches the receiver only while this is open (see [RelayRole]). */
    @Volatile private var relayGateOpen = true
    private val _relay = MutableStateFlow(RelayArbiter())
    /** Relay arbitration for receivers with their own NTRIP client. */
    val relay: StateFlow<RelayArbiter> = _relay.asStateFlow()
    private val relayArb = RelayArbitration()
    private var relayCheckedMs = 0L

    val ntripClient = NtripClient(scope, forward = { if (relayGateOpen) writeRaw(it); raw.onRtcm(it); caster.send(it) }, gga = { ggaForCaster() },
        stallMs = { if (store.value.phonePpp && store.value.phoneRtk) NtripClient.STALL_SSR_MS else NtripClient.STALL_RTK_MS })
    /** Local NTRIP caster for other rovers (Setup > Outputs). */
    val caster = NtripCaster()
    /** App interface: JSON position lines on TCP (Setup > Outputs). */
    val jsonServer = NmeaServer()
    private var lastBroadcastMs = 0L
    /** Phone RTK: a second caster stream with broadcast ephemerides only (Setup > Phone > Ephemeris stream). */
    val ephClient = NtripClient(scope, forward = { com.rfsat.pocketgnss.link.PhoneRtkLink.current?.writeNav(it); raw.onRtcmNav(it) }, gga = { null },
        stallMs = { NtripClient.STALL_EPH_MS })

    /**
     * What goes into the app log (Tools > App log): changes of the connection, of the correction streams, of the fix
     * and of the phone RTK engine's state, so a session can be followed afterwards. Repeating conditions are
     * throttled; positions and passwords are never logged.
     */
    private fun logWatchers() {
        scope.launch {
            connection.collect { c ->
                when (c) {
                    is Connection.Connecting -> AppLog.i("Link", "Connecting: ${c.label}")
                    is Connection.Ready -> AppLog.i("Link", "Connected: ${c.info.name}" + (c.info.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
                    is Connection.Failed -> AppLog.w("Link", "Connection failed or lost: ${c.message}")
                    Connection.AwaitingPermission -> AppLog.i("Link", "Waiting for permission")
                    Connection.NoDevice -> AppLog.i("Link", "Not connected")
                }
            }
        }
        for ((client, tag) in listOf(ntripClient to "NTRIP", ephClient to "Ephemeris stream")) scope.launch {
            var last: Pair<NtripStatus, String>? = null
            client.ntrip.collect { st ->
                val key = st.status to st.message
                if (key == last) return@collect
                last = key
                when (st.status) {
                    NtripStatus.CONNECTING -> AppLog.i(tag, "Connecting to ${st.message} / ${st.mountpoint}")
                    NtripStatus.STREAMING -> AppLog.i(tag, "Streaming ${st.mountpoint}: ${st.message}")
                    NtripStatus.ERROR -> AppLog.w(tag, "Error: ${st.message}")
                    NtripStatus.WAITING -> AppLog.i(tag, "Reconnecting (attempt ${st.reconnects + 1})")
                    NtripStatus.OFF -> AppLog.i(tag, "Stopped")
                }
            }
        }
        scope.launch {
            var lastQ: FixQuality? = null
            fix.collect { f ->
                val q = f?.quality ?: FixQuality.NONE
                if (q == lastQ) return@collect
                lastQ = q
                AppLog.throttled("fix-$q", 30_000, AppLog.Level.INFO, "Fix", "Fix type: ${q.name}" + (f?.let { " · ${it.satellitesUsed ?: "?"} satellites" } ?: ""))
            }
        }
        scope.launch {
            var resets = 0; var rejected = 0; var noPhase = false; var source = ""
            com.rfsat.pocketgnss.link.PhoneRtkLink.state.collect { st ->
                if (st.resets > resets) AppLog.w("Phone RTK", "Filter restarted: its solution stayed far from the phone's own position")
                if (st.rejected > rejected && rejected == 0) AppLog.w("Phone RTK", "A solution far from the phone's own position was not used")
                else if (st.rejected > rejected) AppLog.throttled("rtk-rej", 60_000, AppLog.Level.WARN, "Phone RTK", "${st.rejected} solutions not used so far (far from the phone's own position)")
                resets = st.resets; rejected = st.rejected
                if (st.noPhase != noPhase && st.epochs > 5) {
                    noPhase = st.noPhase
                    if (noPhase) AppLog.w("Phone RTK", "No carrier phase from the phone (${st.signals} signals)") else AppLog.i("Phone RTK", "Carrier phase back: ${st.withPhase} of ${st.signals} signals")
                }
                st.solution?.takeIf { it.codeRms > com.rfsat.pocketgnss.rtk.HonestAccuracy.REFLECTED_RMS && it.codeResiduals >= 4 }?.let { sol ->
                    AppLog.throttled("rtk-reflected", 300_000, AppLog.Level.WARN, "Phone RTK",
                        "Code residuals %.0f m RMS: signals reach the phone by reflection, the position may be off by metres or more".format(java.util.Locale.US, sol.codeRms))
                }
                if (st.source.isNotEmpty() && st.source != source) {
                    source = st.source
                    AppLog.throttled("rtk-src-$source", 60_000, AppLog.Level.INFO, "Phone RTK", "Position from: $source · ${st.withPhase} of ${st.signals} signals with carrier phase" +
                        (st.solution?.let { " · ${it.satellites} satellites" } ?: ""))
                }
                // The engine's own messages (outliers, slips, missing data) explain why carrier phase is not used.
                // RTKLIB starts its messages with the epoch time ("20:27:32.00: ..."): grouped without it.
                st.message.takeIf { it.isNotBlank() }?.let { m ->
                    val text = m.replace(RTK_TIME_PREFIX, "")
                    AppLog.throttled("rtk-msg-" + text.substringBefore('(').take(40), 60_000, AppLog.Level.INFO, "RTK engine", text)
                }
            }
        }
    }

    private val RTK_TIME_PREFIX = Regex("^\\d{1,2}:\\d\\d:\\d\\d(\\.\\d+)?:\\s*")

    /** Starts or stops the ephemeris stream to match the settings and the link. */
    @Volatile private var ephConfig: com.rfsat.pocketgnss.protocol.NtripConfig? = null
    fun updateEphStream() {
        val s = store.value
        val want = s.ephStream && linkInfo?.let { it.phoneRtk && it.kind == LinkKind.PHONE } == true
        val p = s.profiles.firstOrNull { it.name == s.ephProfile }
        if (want && p != null && s.ephMount.isNotBlank()) {
            val cfg = p.config.copy(mountpoint = s.ephMount.trim(), ggaIntervalSec = 0)
            val main = s.ntrip
            if (s.ntripEnabled && cfg.user.isNotBlank() && cfg.user == main.user && cfg.port == main.port &&
                cfg.host.trim().equals(main.host.trim(), ignoreCase = true)) {
                // A second login with the account of the main stream: casters allowing one login per account (Galileo
                // HAS, Metrica) then feed only one of the two, and the main stream stalls (October 2026 logs).
                ephConfig = null
                ephClient.refuse(context.getString(com.rfsat.pocketgnss.R.string.eph_same_account))
                return
            }
            // restarted when the account changes too (a corrected password), not only the mountpoint
            if (!ephClient.running || ephConfig != cfg) { ephConfig = cfg; ephClient.start(cfg) }
        } else { ephConfig = null; if (ephClient.running) ephClient.stop() }
    }
    /** The phone's raw GNSS measurements: capability test and RINEX / RTCM recording (Tools > Phone raw GNSS). */
    val raw: com.rfsat.pocketgnss.raw.PhoneRawLogger by lazy {
        com.rfsat.pocketgnss.raw.PhoneRawLogger(context, sessionsRoot) { on -> setRtcmCapture(on) }
    }
    @Volatile private var rtcmCapture = false
    val mock = MockLocation(context)
    val server = NmeaServer()
    /** The phone as a Bluetooth GNSS receiver (Home > Phone as GNSS receiver, Setup > Share position). */
    val btServer = BtNmeaServer(context)

    /** Re-tries the Bluetooth server after a permission was granted or Bluetooth switched on. */
    fun restartBtServer() { btServer.stop(); if (store.value.btServer) btServer.start() }

    /**
     * True when the phone has a GNSS receiver of its own. Without one, PocketGNSS is only useful with an external
     * receiver (USB, Bluetooth, network); the screens say so and the phone source and raw tools are not offered.
     */
    val phoneHasGnss: Boolean get() = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION_GPS)

    val sessionsRoot: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }
    /** Last positions with method and accuracy (Tools > Position history), optionally logged to CSV. */
    val history = SolutionHistory(sessionsRoot)
    private val pointStore = PointStore(File(File(context.getExternalFilesDir(null) ?: context.filesDir, "points"), "points.csv"))
    /** A-B guidance (Tools > A-B guidance): saved lines and the active one. */
    val guidance = GuidanceSession(File(File(context.getExternalFilesDir(null) ?: context.filesDir, "points"), "guidance.csv")).also { g ->
        g.fields = { fields.value }
    }
    private var lastGuideBeep = 0L
    private val fieldStore = com.rfsat.pocketgnss.data.FieldStore(File(File(context.getExternalFilesDir(null) ?: context.filesDir, "points"), "fields.csv"))

    @Volatile private var link: GnssLink? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private val writeLock = Any()
    private var model = GnssModel()
    private val rates = RateMeter()
    private val consoleBuf = ArrayDeque<String>()
    private var splitter = newSplitter()
    private var bytes = 0L
    private var lastDataMs = 0L
    @Volatile private var lastGgaLine: String? = null
    private var recorder: SessionRecorder? = null
    private var lastLoggedMs = 0L
    private val trackBuf = ArrayList<TrackPoint>()
    private var lastTrackMs = 0L
    /** The map trace survives app restarts: saved every 15 s while it grows, and on shutdown; deleted only by Clear. */
    private val trackStore = com.rfsat.pocketgnss.data.TrackStore(File(File(context.getExternalFilesDir(null) ?: context.filesDir, "points"), "track.bin"))
    @Volatile private var trackDirty = false
    private var trackSavedMs = 0L
    private val surveySamples = ArrayList<DoubleArray>()
    private var surveyMslSum = 0.0
    private var surveyMslCount = 0
    private var lastSurveyCalc = 0L
    private var fixedLostAt: Long? = null
    private var lastQuality = FixQuality.NONE
    private val waiters = CopyOnWriteArrayList<Pair<(UbxFrame) -> Boolean, CompletableDeferred<UbxFrame>>>()

    val simulator: SimulatedLink? get() = link as? SimulatedLink
    val linkInfo: LinkInfo? get() = (connection.value as? Connection.Ready)?.info

    init {
        _points.value = pointStore.load()
        _fields.value = fieldStore.load()
        synchronized(trackBuf) { trackBuf.addAll(trackStore.load()) }
        _track.value = synchronized(trackBuf) { trackBuf.toList() }
        scope.launch {
            while (isActive) {
                delay(300)
                publish()
            }
        }
        logWatchers()
        // The ephemeris stream is checked every 20 s as well, so it starts as soon as phone RTK is up and its settings
        // are complete, whatever order they arrived in (no need to switch it off and on).
        scope.launch { while (isActive) { delay(20_000); if (store.value.ephStream) updateEphStream() } }
        // Outputs follow the settings.
        scope.launch {
            store.settings.collect { s ->
                if (AppLog.enabled != s.appLog) {
                    if (!s.appLog) AppLog.i("App", "Log recording switched off")
                    AppLog.enabled = s.appLog
                    if (s.appLog) AppLog.i("App", "Log recording switched on")
                }
                simulator?.configure(s.simConfig)
                guidance.warnAt = s.guideWarn
                if (s.ephStream || ephClient.running) updateEphStream()
                if (s.mockLocation) { if (mock.status.value == MockStatus.OFF) mock.start() } else mock.stop()
                if (s.nmeaServer) { if (!server.running) server.start(s.nmeaServerPort, s.nmeaServerLocalOnly) } else if (server.running) server.stop()
                if (s.casterOn) { if (!caster.running || caster.state.value.port != s.casterPort) caster.start(s.casterPort, s.casterMount, s.casterUser, s.casterPass) }
                else if (caster.running) caster.stop()
                if (s.apiJson) { if (!jsonServer.running) jsonServer.start(s.apiJsonPort, s.apiJsonLocalOnly) } else if (jsonServer.running) jsonServer.stop()
                if (s.btServer) { if (!btServer.running) btServer.start() } else if (btServer.running || btServer.state.value.status != BtServerStatus.OFF) btServer.stop()
            }
        }
    }

    private fun newSplitter() = StreamSplitter(onNmea = ::onNmea, onUbx = ::onUbx)

    // ---------------------------------------------------------------- connections

    fun awaitingPermission() { _connection.value = Connection.AwaitingPermission }

    fun disconnect(message: String? = null) {
        reconnectJob?.cancel(); reconnectJob = null
        closeLink()
        _connection.value = if (message != null) Connection.Failed(message) else Connection.NoDevice
    }

    private fun closeLink() {
        if (ephClient.running) ephClient.stop()
        readerJob?.cancel(); readerJob = null
        link?.close(); link = null
        for (w in waiters) w.second.cancel()
        waiters.clear()
        for (w in quectelWaiters) w.second.cancel()
        quectelWaiters.clear()
    }

    fun connectUsb(manager: UsbManager, device: UsbDevice) {
        val s = store.value
        open(context.getString(R.string.conn_opening_usb)) {
            val chip = UsbChip.of(device.vendorId)
            // RFSAT's LG290P rovers: the receiver's own UART runs at 460800 (V5, Postcard port B); the Pico ignores the rate.
            val rover = s.receiverVariant.isRover
            val baud = if (chip == UsbChip.UBLOX) 115200 else (s.usbBaud.takeIf { it > 0 } ?: if (rover) 460800 else 115200)
            val port = if (s.usbPort >= 0) s.usbPort else s.receiverVariant.defaultUsbPort
            UsbSerialLink.open(manager, device, baud, port)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
        open(context.getString(R.string.conn_opening_bt, name), retry = true) { BluetoothSppLink.open(device) }
    }

    /** Bluetooth LE (Nordic UART Service): PicoRTK, PostcardRTK and the ESP32-C6 rover. */
    @SuppressLint("MissingPermission")
    fun connectBle(device: BluetoothDevice) {
        val name = try { device.name } catch (_: SecurityException) { null } ?: device.address
        open(context.getString(R.string.conn_opening_ble, name), retry = true) { BleNusLink.open(context, device) }
    }

    fun connectTcp(host: String, port: Int) {
        open("$host:$port", retry = true) { TcpLink.open(host, port) }
    }

    /** The phone's own GNSS chip (needs ACCESS_FINE_LOCATION, asked by the activity). */
    /**
     * The phone's own GNSS (needs ACCESS_FINE_LOCATION, asked by the activity). With phone RTK on (default) the raw
     * measurements go through the RTK engine and the NTRIP relay feeds it; if the engine cannot run on this phone,
     * Android's location is used instead.
     */
    /** The learned profile of this phone model, if any (a profile of another model is ignored). */
    fun currentPhoneProfile(): com.rfsat.pocketgnss.rtk.PhoneProfile? =
        com.rfsat.pocketgnss.rtk.PhoneProfile.decode(store.value.phoneProfile)?.takeIf { it.model == android.os.Build.MODEL }

    /**
     * Phone RTK engine options from the settings. RTK always runs kinematic: in static mode the position has no process
     * noise, so a start on reflected code locks in. A Galaxy S24 on a car roof for 24 minutes ended 17 m above the
     * code average in static mode against 2 m in kinematic. PPP keeps the moving / still choice (error ratio 300).
     */
    private fun rtkOptions(s: com.rfsat.pocketgnss.data.AppSettings) = com.rfsat.pocketgnss.rtk.RtkOptions(
        mode = if (s.phonePpp && s.phoneRtkStatic) com.rfsat.pocketgnss.rtk.RtkMode.STATIC else com.rfsat.pocketgnss.rtk.RtkMode.KINEMATIC,
        ppp = s.phonePpp, errorRatio = if (s.phonePpp) 300.0 else 100.0, snrMaskDbHz = if (s.phonePpp) 0.0 else 30.0)

    fun connectPhone() {
        if (!phoneHasGnss) { disconnect(context.getString(R.string.no_gnss_short)); return }
        val s = store.value
        val rtk = s.phoneRtk && com.rfsat.pocketgnss.link.PhoneRtkLink.engineAvailable
        if (!rtk && ntripClient.running && !rtcmCapture) ntripClient.stop()
        open(context.getString(if (rtk) R.string.conn_opening_phone_rtk else R.string.conn_opening_phone)) {
            if (rtk) {
                try {
                    val base = rtkOptions(s)
                    val profile = currentPhoneProfile()
                    val options = if (s.phoneRtkAuto) com.rfsat.pocketgnss.rtk.PhoneTuning.tune(base, profile) else base
                    com.rfsat.pocketgnss.link.PhoneRtkLink.open(context, options, profile ?: com.rfsat.pocketgnss.rtk.PhoneProfile(android.os.Build.MODEL)) { p ->
                        store.update { it.copy(phoneProfile = p.encode()) }
                    }
                } catch (e: IOException) {
                    if (e.message?.startsWith("Location") == true) throw e
                    com.rfsat.pocketgnss.link.PhoneGnssLink.open(context)
                }
            } else com.rfsat.pocketgnss.link.PhoneGnssLink.open(context)
        }
    }

    /** Plays a recorded session back as the source (NMEA sessions as they were; phone raw sessions through phone RTK). */
    fun replay(dir: File, speed: Double) {
        val s = store.value
        open(context.getString(R.string.replay_opening, dir.name)) {
            if (dir.name.startsWith("raw_")) {
                val base = rtkOptions(s)
                com.rfsat.pocketgnss.link.RawReplayLink(dir, if (s.phoneRtkAuto) com.rfsat.pocketgnss.rtk.PhoneTuning.tune(base, currentPhoneProfile()) else base, speed)
            } else com.rfsat.pocketgnss.link.NmeaReplayLink(File(dir, "raw.nmea"), speed)
        }
    }

    fun connectSimulator() {
        open(context.getString(R.string.conn_opening_sim)) { SimulatedLink(store.value.simConfig) }
    }

    private fun open(label: String, retry: Boolean = false, factory: () -> GnssLink) {
        reconnectJob?.cancel(); reconnectJob = null
        closeLink()
        _connection.value = Connection.Connecting(label)
        reconnectJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                try {
                    val l = factory()
                    attachLink(l)
                    // Wait here while the link lives; on loss, fall through to a retry when wanted.
                    readerJob?.join()
                    if (!isActive) break
                } catch (e: IOException) {
                    _connection.value = Connection.Failed(e.message ?: "I/O error")
                } catch (e: SecurityException) {
                    _connection.value = Connection.Failed(context.getString(R.string.conn_bt_permission)); break
                }
                if (!retry || !store.value.autoReconnect) break
                attempt++
                delay(if (attempt < 3) 3000L else 8000L)
                _connection.value = Connection.Connecting(label)
            }
        }
    }

    private fun attachLink(l: GnssLink) {
        link = l
        model = GnssModel(); rates.clear(); splitter = newSplitter(); bytes = 0; lastDataMs = 0
        _receiver.value = ReceiverInfo(); _fix.value = null; _sats.value = emptyList(); _rover.value = RoverStatus()
        accStats.reset(); _accuracy.value = null; lastGst = null
        alertLogic.reset(); _alerts.value = AlertState()
        _connection.value = Connection.Ready(l.info)
        readerJob = scope.launch(Dispatchers.IO) { readLoop(l) }
        scope.launch {
            delay(1500)
            // An LG290P answers $PQTMVERNO, not UBX. Only a direct link reaches it (V5 USB, Postcard port B); through
            // PicoRTK / PostcardRTK the command gets no reply, which is harmless.
            if (store.value.receiverVariant.isRover) quectelVersion() else pollVersion()
        }
        _relay.value = RelayArbiter(); relayGateOpen = true; relayArb.reset()
        // A receiver with its own NTRIP client: the arbiter decides (no second stream at connection)
        if (store.value.ntripEnabled && l.info.writable && !ntripClient.running && !receiverHasOwnNtrip(l.info)) ntripClient.start(store.value.ntrip)
        updateEphStream()
    }

    private suspend fun readLoop(l: GnssLink) {
        val buf = ByteArray(4096)
        val start = System.currentTimeMillis()
        val bauds = if (store.value.receiverVariant.isRover) listOf(460800, 115200, 38400, 9600, 57600, 230400)
            else listOf(115200, 9600, 38400, 57600, 230400, 460800)
        var baudIdx = 0
        var baudSince = start
        val autobaud = l.info.kind == LinkKind.USB && l.info.chip != UsbChip.UBLOX && store.value.usbBaud == 0
        var currentBaud = l.info.baud
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val n = try { l.read(buf) } catch (e: IOException) {
                if (link === l) {
                    link = null
                    _connection.value = Connection.Failed(context.getString(R.string.conn_lost, e.message ?: ""))
                }
                return
            } catch (e: InterruptedException) { return }
            val now = System.currentTimeMillis()
            if (n > 0) {
                bytes += n; lastDataMs = now
                splitter.feed(buf, n)
            }
            if (autobaud && splitter.nmeaLines == 0L && now - baudSince > 2500) {
                baudIdx = (baudIdx + 1) % bauds.size
                currentBaud = bauds[baudIdx]
                l.setBaud(currentBaud); splitter.reset(); baudSince = now
                _stream.value = _stream.value.copy(baud = currentBaud)
            }
        }
    }

    // ---------------------------------------------------------------- incoming data

    private fun onNmea(line: String) {
        val now = System.currentTimeMillis()
        val s = Nmea.parse(line) ?: return
        rates.mark(s.type, now)
        synchronized(consoleBuf) { consoleBuf.addLast(line); while (consoleBuf.size > 2000) consoleBuf.removeFirst() }
        recorder?.line(line)
        server.send(line)
        if (!store.value.btServerPositionOnly || BtNmeaServer.typeOf(line) in BtNmeaServer.POSITION_SENTENCES) btServer.send(line)
        if (s is NmeaSentence.Gga && s.quality != FixQuality.NONE) lastGgaLine = line
        if (s is NmeaSentence.Other && s.talker == "P") onProprietary(s.type, s.fields, line, now)
        if (s is NmeaSentence.Gst && s.sdLatitude != null && s.sdLongitude != null)
            _rover.value = _rover.value.copy(gstH = kotlin.math.hypot(s.sdLatitude, s.sdLongitude), gstMs = now)
        if (s is NmeaSentence.Gst) { lastGst = s; lastGstMs = now }
        val f0 = model.accept(s) ?: return
        val f = if (f0.quality != FixQuality.DGNSS) f0 else f0.copy(dgnss = if (link?.info?.phoneRtk == true)
            DgnssSource.ofPhoneMethod(f0.quality, com.rfsat.pocketgnss.link.PhoneRtkLink.state.value.source)
        else DgnssSource.of(f0.quality, model.satellites(), ntripClient.ntrip.value.let { it.status == NtripStatus.STREAMING && now - it.lastDataMs < 30_000 }))
        _fix.value = f
        if (s is NmeaSentence.Gga) compareAccuracy(f, now)
        recordHistory(f, now)
        // Mock location from the phone's own chip would only feed the phone its own position back.
        if (link?.info?.kind != LinkKind.PHONE && link?.info?.kind != LinkKind.REPLAY) mock.push(f)
        onFix(f, now)
    }

    /**
     * The receiver's error figures at this GGA epoch, side by side with the accuracy shown: running statistics for Live,
     * and a row of accuracy.csv while recording (1.13.3).
     */
    private fun compareAccuracy(f: GnssFix, now: Long) {
        val g = lastGst?.takeIf { now - lastGstMs < AccuracyCompare.FRESH_MS }
        val rv = _rover.value
        val e = rv.epe?.takeIf { now - rv.epeMs < AccuracyCompare.FRESH_MS }
        val ppp = rv.ppp?.takeIf { now - rv.pppMs < 3000 }
        // Same rule as GnssModel.fromGga: EPE of the last 3 s first, then GST, else the rough HDOP estimate
        val source = when {
            model.epe != null && now - model.epeMs < 3000 -> AccuracyCompare.Source.EPE
            f.measuredAccuracy -> AccuracyCompare.Source.GST
            else -> AccuracyCompare.Source.ESTIMATE
        }
        val row = AccuracyCompare.Row(now, f, g?.let { now - lastGstMs }, g?.rmsRange, g?.sdLatitude, g?.sdLongitude, g?.sdAltitude,
            e?.let { now - rv.epeMs }, e, ppp?.solution, f.horizontalAccuracy, source)
        accStats.add(row)
        recorder?.accuracy(row)
    }

    /** Quectel replies and status sentences, and RFSAT's $PRFSM. */
    private fun onProprietary(type: String, fields: List<String>, line: String, now: Long) {
        val name = "P$type"
        for (w in quectelWaiters) if (w.first == name) { w.second.complete(line); quectelWaiters.remove(w) }
        when (name) {
            "PQTMEPE" -> Quectel.epe(fields)?.let { e -> _rover.value = _rover.value.copy(epe = e, epeMs = now); model.epe = e; model.epeMs = now }
            "PQTMPPPNAV" -> Quectel.pppNav(fields)?.let { _rover.value = _rover.value.copy(ppp = it, pppMs = now) }
            "PRFSM" -> Quectel.rfsat(fields)?.let { _rover.value = _rover.value.copy(rfsat = it, rfsatMs = now) }
            "PQTMVERNO" -> Quectel.version(fields)?.let { v ->
                _receiver.value = _receiver.value.copy(module = v.module, firmware = v.version, protocol = "NMEA · Quectel PQTM")
            }
        }
    }

    /**
     * Sends a Quectel command ([body] without '$' and checksum) and waits for the reply of the same name: OK, ERROR
     * or the value read back. Null when nothing came back in [timeoutMs] (no direct link to the receiver).
     */
    suspend fun quectel(body: String, timeoutMs: Long = 1500): Quectel.Reply? {
        if (link == null) return null
        val name = Quectel.name(body)
        val d = CompletableDeferred<String>()
        val w = name to d
        quectelWaiters.add(w)
        withContext(Dispatchers.IO) { writeRaw((Quectel.command(body) + "\r\n").toByteArray(Charsets.US_ASCII)) }
        val line = withTimeoutOrNull(timeoutMs) { d.await() }
        quectelWaiters.removeIf { it === w }
        return line?.let { Quectel.reply(name, it) }
    }

    suspend fun quectelVersion(): Boolean = quectel(QuectelProfile.VERSION) != null

    /** Every fix into the position history, with the method and, for phone RTK, the engine's state. */
    private fun recordHistory(f: GnssFix, now: Long) {
        if (f.quality == FixQuality.NONE) return
        val rtk = if (link?.info?.phoneRtk == true) com.rfsat.pocketgnss.link.PhoneRtkLink.state.value else null
        val method = rtk?.source?.takeIf { it.isNotEmpty() } ?: when (f.quality) {
            FixQuality.RTK_FIXED -> "RTK fixed"; FixQuality.RTK_FLOAT -> "RTK float"; FixQuality.DGNSS -> "DGNSS / SBAS"
            FixQuality.DEAD_RECKONING -> "Dead reckoning"; else -> "Autonomous"
        }
        history.add(SolutionRecord(now, method, f.quality, f.latitude, f.longitude, f.ellipsoidHeight, f.horizontalAccuracy, f.verticalAccuracy,
            f.measuredAccuracy, f.satellitesUsed, f.hdop, f.diffAgeSec,
            ratio = rtk?.solution?.ratio, signals = rtk?.signals, phaseSignals = rtk?.withPhase, baseSatellites = rtk?.base?.satellites,
            ephemerides = rtk?.base?.let { it.ephemerides + it.glonassEphemerides }, baselineM = rtk?.solution?.baseline?.takeIf { it > 0 },
            engineMessage = rtk?.message))
    }

    // ---------------------------------------------------------------- alerts

    private val alertLogic = FixAlertLogic()
    private val alerter by lazy { Alerter(context) }
    private val _alerts = MutableStateFlow(AlertState())
    /** Current alert state (fix lost / corrections stale), for the Live banner. */
    val alerts: StateFlow<AlertState> = _alerts.asStateFlow()

    /** Evaluated once a second and on every fix; the simulator and a disconnected source never alert. */
    private fun checkAlerts(now: Long) = synchronized(alertLogic) {
        val s = store.value
        if (!s.alertEnabled || simulator != null || connection.value !is Connection.Ready) {
            if (_alerts.value != AlertState()) { alertLogic.reset(); _alerts.value = AlertState() }
            return
        }
        alertLogic.threshold = if (s.alertFloat) FixQuality.RTK_FLOAT else FixQuality.RTK_FIXED
        alertLogic.staleSec = s.alertStaleSec.toDouble()
        val f = _fix.value
        val events = alertLogic.update(now, f?.quality ?: FixQuality.NONE, f?.diffAgeSec)
        for (e in events) {
            val detail = f?.let { context.getString(R.string.alert_detail, context.getString(com.rfsat.pocketgnss.ui.fixLabel(it)),
                it.diffAgeSec?.let { a -> "%.0f s".format(java.util.Locale.US, a) } ?: "—") } ?: context.getString(R.string.top_no_fix)
            alerter.signal(e, s.alertSound, s.alertVibrate, detail)
        }
        val st = AlertState(alertLogic.fixLost, alertLogic.correctionsStale)
        if (st != _alerts.value) _alerts.value = st
    }

    private fun onFix(f: GnssFix, now: Long) {
        checkAlerts(now)
        val st = store.value
        recorder?.let { r ->
            if (f.quality.rank >= st.logMinQuality.rank && (st.logIntervalSec <= 0 || now - lastLoggedMs >= st.logIntervalSec * 1000 - 20)) {
                r.fix(f); lastLoggedMs = now
            }
        }
        if (f.quality != FixQuality.NONE && now - lastTrackMs >= 500) {
            synchronized(trackBuf) {
                trackBuf.add(TrackPoint(now, f.latitude, f.longitude, f.ellipsoidHeight, f.quality))
                if (trackBuf.size > 7200) trackBuf.subList(0, 1200).clear()
            }
            trackDirty = true
            lastTrackMs = now
        }
        fieldCapture.onFix(f.latitude, f.longitude, f.ellipsoidHeight, f.quality, now)
        appInterface(f, st, now)
        guidance.recordCurve(f.latitude, f.longitude)
        if (guidance.state.value.line != null) {
            guidance.onFix(f.latitude, f.longitude, f.courseDeg?.takeIf { (f.speedMs ?: 0.0) > 0.5 })
            if (guidance.state.value.offLine && now - lastGuideBeep > 1500) { lastGuideBeep = now; alerter.beep() }
        }
        // Survey averaging.
        val sv = _survey.value
        if (sv.running) {
            val h = f.ellipsoidHeight
            if (f.quality.rank >= sv.minQuality.rank && h != null && surveySamples.size < 100_000) {
                synchronized(surveySamples) { surveySamples.add(doubleArrayOf(f.latitude, f.longitude, h)) }
                f.altitudeMsl?.let { surveyMslSum += it; surveyMslCount++ }
            } else _survey.value = sv.copy(rejected = sv.rejected + 1)
        }
        // TTFF and RTK reacquisition.
        val t = _ttff.value
        var next = t
        t.current?.let { run ->
            val u = run.update(f.quality, now)
            next = if (u.done || now - run.startMs > 300_000) next.copy(current = null, history = (listOf(u) + t.history).take(20)) else next.copy(current = u)
        }
        if (lastQuality == FixQuality.RTK_FIXED && f.quality != FixQuality.RTK_FIXED) fixedLostAt = now
        if (f.quality == FixQuality.RTK_FIXED && fixedLostAt != null) {
            next = next.copy(reacquisitions = (listOf((now - fixedLostAt!!) / 1000.0) + next.reacquisitions).take(30)); fixedLostAt = null
        }
        if (next != t) _ttff.value = next
        lastQuality = f.quality
    }

    private fun onUbx(f: UbxFrame) {
        for (w in waiters) if (w.first(f)) { w.second.complete(f); waiters.remove(w) }
        if (f.cls == Ubx.CLS_MON && f.id == Ubx.ID_MON_VER) Ubx.parseMonVer(f.payload)?.let { v ->
            _receiver.value = _receiver.value.copy(module = v.module, firmware = v.firmware ?: v.software, protocol = v.protocol, ubxAnswered = true)
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        arbitrateRelay(now)
        if (link != null) _accuracy.value = accStats.summary().takeIf { it.epochs > 0 }
        if (link != null) {
            _sats.value = model.satellites()
            _console.value = synchronized(consoleBuf) { consoleBuf.toList() }
            _stream.value = StreamStats(bytes, splitter.nmeaLines, splitter.badChecksums, splitter.garbageBytes, splitter.ubxFrames,
                rates.snapshot(now), lastDataMs, _stream.value.baud ?: link?.info?.baud)
            val r = _receiver.value
            if (r.datum != model.datum || r.lastText != model.lastText) _receiver.value = r.copy(datum = model.datum, lastText = model.lastText)
            // Older than 5 s: the fix is stale (receiver stopped sending).
            _fix.value?.let { if (now - it.receivedAtMs > 5000) _fix.value = null }
            checkAlerts(now)
        }
        _track.value = synchronized(trackBuf) { trackBuf.toList() }
        if (trackDirty && now - trackSavedMs >= 15_000) saveTrack(now)
        recorder?.let { _recording.value = _recording.value.copy(fixes = it.fixes, lines = it.lines) }
        val sv = _survey.value
        val n = synchronized(surveySamples) { surveySamples.size }
        if (sv.running && n != sv.samples && now - lastSurveyCalc > (if (n > 5000) 3000 else 900)) {
            val copy = synchronized(surveySamples) { ArrayList(surveySamples) }
            _survey.value = sv.copy(samples = copy.size, stats = Statistics.compute(copy, sv.reference),
                meanMsl = if (surveyMslCount > 0) surveyMslSum / surveyMslCount else null)
            lastSurveyCalc = now
        }
    }

    private fun ggaForCaster(): String? {
        lastGgaLine?.let { return it }
        val sodNow = (System.currentTimeMillis() / 1000.0) % 86400
        val f = _fix.value
        if (f != null) return Ntrip.gga(f.time?.secondsOfDay ?: sodNow, f.latitude, f.longitude, f.altitudeMsl ?: 0.0)
        // No receiver: the phone's own position from a raw GNSS session (enough for a VRS / nearest-base caster).
        val l = if (rtcmCapture) raw.location else null
        return l?.let { Ntrip.gga(sodNow, it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else 0.0) }
    }

    /**
     * RTCM recording for a phone raw session: runs the NTRIP client even without a writable receiver link. It
     * stops again afterwards unless the user had the relay on.
     */
    private fun setRtcmCapture(on: Boolean) {
        rtcmCapture = on
        val cfg = store.value.ntrip
        if (on) { if (!ntripClient.running && cfg.usable) ntripClient.start(cfg) }
        else if (!(store.value.ntripEnabled && linkInfo?.writable == true)) ntripClient.stop()
    }

    // ---------------------------------------------------------------- writing / UBX

    /** Writes raw bytes to the receiver (RTCM from the NTRIP client, UBX commands). */
    fun writeRaw(data: ByteArray) {
        val l = link ?: return
        try { synchronized(writeLock) { l.write(data) } } catch (_: IOException) {}
    }

    /** Sends a UBX command and waits for the matching ACK/NAK or data reply. */
    suspend fun ubx(frame: ByteArray, timeoutMs: Long = 1500, expectData: Pair<Int, Int>? = null): UbxResult {
        val cls = frame[2].toInt() and 0xFF; val id = frame[3].toInt() and 0xFF
        val d = CompletableDeferred<UbxFrame>()
        val match: (UbxFrame) -> Boolean = { f ->
            if (expectData != null) f.cls == expectData.first && f.id == expectData.second
            else Ubx.ackedOf(f) == (cls to id)
        }
        waiters.add(match to d)
        withContext(Dispatchers.IO) { writeRaw(frame) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        // removeIf, not Kotlin's removeAll { }: that walks the list by index and fails (index out of bounds) when the
        // reader thread or a disconnect removes a waiter at the same moment; CopyOnWriteArrayList.removeIf is atomic.
        waiters.removeIf { it.second === d }
        return when {
            r == null -> UbxResult.Timeout
            expectData != null -> UbxResult.Data(r)
            r.id == Ubx.ID_ACK_ACK -> UbxResult.Ack
            else -> UbxResult.Nak
        }.also { if (it !is UbxResult.Timeout) _receiver.value = _receiver.value.copy(ubxAnswered = true) }
    }

    /** Applies configuration keys in messages of up to 60 pairs. Returns the first non-ACK result, or Ack. */
    suspend fun ubxSet(values: List<Pair<Long, Long>>, layers: Int): UbxResult {
        for (chunk in values.chunked(60)) {
            val r = ubx(Ubx.valset(chunk, layers))
            if (r != UbxResult.Ack) return r
        }
        return UbxResult.Ack
    }

    suspend fun ubxGet(keys: List<Long>): Map<Long, Long>? {
        val out = HashMap<Long, Long>()
        for (chunk in keys.chunked(60)) {
            val r = ubx(Ubx.valget(chunk), expectData = Ubx.CLS_CFG to Ubx.ID_CFG_VALGET)
            if (r !is UbxResult.Data) return null
            out.putAll(Ubx.parseValget(r.frame.payload))
        }
        return out
    }

    suspend fun pollVersion(): Boolean = ubx(Ubx.pollMonVer(), expectData = Ubx.CLS_MON to Ubx.ID_MON_VER) is UbxResult.Data

    /** Reads which NMEA messages are on for [port] and the navigation rate (Hz). */
    suspend fun readNmeaConfig(port: UbxPort): Pair<Set<NmeaMsg>, Int>? {
        val keys = NmeaMsg.entries.map { it.key(port) } + Ubx.KEY_RATE_MEAS
        val v = ubxGet(keys) ?: return null
        val on = NmeaMsg.entries.filter { (v[it.key(port)] ?: 0L) > 0 }.toSet()
        val ms = v[Ubx.KEY_RATE_MEAS] ?: 1000L
        return on to (1000 / ms.coerceAtLeast(1)).toInt()
    }

    // ---------------------------------------------------------------- recording, points, tools

    fun startRecording() {
        if (recorder != null) return
        val r = SessionRecorder(SessionRecorder.newDir(sessionsRoot))
        r.line("# PocketGNSS session ${r.dir.name} · ${linkInfo?.name ?: ""} · ${_receiver.value.module ?: ""}".trim())
        recorder = r; lastLoggedMs = 0
        _recording.value = RecordingState(true, r.dir, 0, 0, r.startedMs)
        AppLog.i("Recording", "Started ${r.dir.name}")
    }

    fun stopRecording() {
        recorder?.let { AppLog.i("Recording", "Stopped ${it.dir.name}: ${it.fixes} fixes") }
        recorder?.close(); recorder = null
        _recording.value = _recording.value.copy(active = false)
    }

    fun sessions(): List<File> = sessionsRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    fun sessionTrack(dir: File) = FixCsv.read(File(dir, "fixes.csv"))
    fun clearTrack() {
        synchronized(trackBuf) { trackBuf.clear() }
        trackDirty = false; trackStore.clear()
        _track.value = emptyList()
    }

    private fun saveTrack(now: Long = System.currentTimeMillis()) {
        trackDirty = false; trackSavedMs = now
        val copy = synchronized(trackBuf) { trackBuf.toList() }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { synchronized(trackStore) { trackStore.save(copy) } }
    }

    /** The app went to the background: save what is not saved yet, in the background. */
    fun saveTrackNow() { if (trackDirty) saveTrack() }

    /** Saves what is not saved yet, before stopping. */
    fun flushTrack() { if (trackDirty) { trackDirty = false; val copy = synchronized(trackBuf) { trackBuf.toList() }; synchronized(trackStore) { trackStore.save(copy) } } }

    fun startSurvey(minQuality: FixQuality, reference: Reference?) {
        synchronized(surveySamples) { surveySamples.clear() }
        surveyMslSum = 0.0; surveyMslCount = 0
        _survey.value = SurveyState(true, minQuality, 0, 0, null, System.currentTimeMillis(), reference)
    }

    fun stopSurvey() { publishSurveyNow(); _survey.value = _survey.value.copy(running = false) }

    private fun publishSurveyNow() {
        val copy = synchronized(surveySamples) { ArrayList(surveySamples) }
        val sv = _survey.value
        _survey.value = sv.copy(samples = copy.size, stats = Statistics.compute(copy, sv.reference),
            meanMsl = if (surveyMslCount > 0) surveyMslSum / surveyMslCount else null)
    }

    fun setSurveyReference(r: Reference?) {
        _survey.value = _survey.value.copy(reference = r)
        lastSurveyCalc = 0
        if (!_survey.value.running) publishSurveyNow()
    }

    /** Saves the averaged survey as a named point. */
    fun saveSurveyPoint(name: String, note: String = "", code: String = "", photo: String = ""): SurveyPoint? {
        val sv = _survey.value
        val s = sv.stats ?: return null
        val p = SurveyPoint(name, s.meanLatitude, s.meanLongitude, s.meanHeight, sv.meanMsl, s.sdEast, s.sdNorth, s.sdUp, s.count,
            sv.minQuality, System.currentTimeMillis(), note, code, photo)
        pointStore.add(p); _points.value = pointStore.load()
        return p
    }

    /** Saves the current single fix as a point (quick mark from the map or live screen). */
    fun markPoint(name: String): SurveyPoint? {
        val f = _fix.value ?: return null
        val sd = f.horizontalAccuracy / Math.sqrt(2.0)
        val p = SurveyPoint(name, f.latitude, f.longitude, f.ellipsoidHeight ?: f.altitudeMsl ?: 0.0, f.altitudeMsl, sd, sd, f.verticalAccuracy,
            1, f.quality, System.currentTimeMillis())
        pointStore.add(p); _points.value = pointStore.load()
        return p
    }

    fun deletePoint(p: SurveyPoint) {
        pointStore.remove(p); _points.value = pointStore.load()
        if (p.photo.isNotBlank() && _points.value.none { it.photo == p.photo }) File(photoDir, p.photo).delete()
    }

    /** Replaces a point's name and attributes (same position and time). */
    fun updatePoint(old: SurveyPoint, new: SurveyPoint) {
        pointStore.save(pointStore.load().map { if (it.name == old.name && it.timeMs == old.timeMs) new else it }); _points.value = pointStore.load()
        if (old.photo.isNotBlank() && old.photo != new.photo && _points.value.none { it.photo == old.photo }) File(photoDir, old.photo).delete()
    }

    /** Photos of points (points/photos), shared through the FileProvider. */
    /** Downloaded national height models (geoid grids), in the app's private storage. */
    val geoid = com.rfsat.pocketgnss.data.GeoidStore(File(context.filesDir, "geoid"))

    /** Progress of a height model download: model id, fraction done, error message (null while running or after success). */
    data class GeoidDownload(val id: String = "", val running: Boolean = false, val progress: Float = 0f, val error: String? = null)
    private val _geoidDownload = MutableStateFlow(GeoidDownload())
    val geoidDownload: StateFlow<GeoidDownload> = _geoidDownload

    /** The chosen height model when heights are shown in a national system and it is downloaded. */
    fun heightModel(): com.rfsat.pocketgnss.data.HeightModel? = store.value.takeIf { it.heightRef == com.rfsat.pocketgnss.data.HeightRef.MODEL }
        ?.let { com.rfsat.pocketgnss.data.HeightModels.byId(it.heightModel) }?.takeIf { geoid.installed(it) }

    /** Height in the chosen national height system, or null (no model, not downloaded, or outside its grid). */
    fun nationalHeight(lat: Double, lon: Double, hEll: Double?): Double? = heightModel()?.let { geoid.height(it, lat, lon, hEll) }

    /** National heights for the exporters, when a model is chosen and downloaded. */
    fun nationalHeights(): com.rfsat.pocketgnss.data.NationalHeights? = heightModel()?.let { m ->
        com.rfsat.pocketgnss.data.NationalHeights(m.system) { lat, lon, h -> geoid.height(m, lat, lon, h) } }

    fun downloadHeightModel(m: com.rfsat.pocketgnss.data.HeightModel) {
        if (_geoidDownload.value.running) return
        _geoidDownload.value = GeoidDownload(m.id, true)
        scope.launch(Dispatchers.IO) {
            try {
                geoid.download(m) { f -> _geoidDownload.value = GeoidDownload(m.id, true, f) }
                _geoidDownload.value = GeoidDownload(m.id, false, 1f)
            } catch (e: Exception) {
                _geoidDownload.value = GeoidDownload(m.id, false, 0f, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun deleteHeightModel(m: com.rfsat.pocketgnss.data.HeightModel) { geoid.delete(m); _geoidDownload.value = GeoidDownload() }

    val photoDir: File get() = File(File(context.getExternalFilesDir(null) ?: context.filesDir, "points"), "photos").apply { mkdirs() }

    /** A new, empty photo file name for the camera. */
    fun newPhotoFile(prefix: String): File = File(photoDir, prefix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30) + "_" + System.currentTimeMillis() + ".jpg")

    /** Codes used so far, most frequent first (offered when naming points). */
    fun recentCodes(): List<String> = _points.value.map { it.code }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }.take(8)

    /** JSON stream and broadcast for the user's own apps (docs/app-interface.md). */
    private fun appInterface(f: GnssFix, st: com.rfsat.pocketgnss.data.AppSettings, now: Long) {
        caster.position = f.latitude to f.longitude
        if (!jsonServer.running && !st.apiBroadcast) return
        val method = if (link?.info?.phoneRtk == true) com.rfsat.pocketgnss.link.PhoneRtkLink.state.value.source.ifEmpty { null } else null
        if (jsonServer.running) jsonServer.send(PositionJson.of(f, com.rfsat.pocketgnss.protocol.Grid.of(st.gridSystem, f.latitude, f.longitude, f.ellipsoidHeight, st.utmZone), method,
            heightModel()?.let { m -> m.system to geoid.height(m, f.latitude, f.longitude, f.ellipsoidHeight) }))
        if (st.apiBroadcast && now - lastBroadcastMs >= 200) {
            lastBroadcastMs = now
            val i = android.content.Intent(ACTION_POSITION).putExtra("lat", f.latitude).putExtra("lon", f.longitude)
                .putExtra("fix", f.quality.name).putExtra("gga_quality", f.quality.code).putExtra("h_acc", f.horizontalAccuracy).putExtra("v_acc", f.verticalAccuracy)
                .putExtra("time_ms", now)
            f.ellipsoidHeight?.let { i.putExtra("h_ell", it) }; f.altitudeMsl?.let { i.putExtra("h_msl", it) }
            f.speedMs?.let { i.putExtra("speed", it) }; f.courseDeg?.let { i.putExtra("course", it) }
            f.diffAgeSec?.let { i.putExtra("age", it) }; f.satellitesUsed?.let { i.putExtra("sats", it) }
            method?.let { i.putExtra("method", it) }
            heightModel()?.let { m -> geoid.height(m, f.latitude, f.longitude, f.ellipsoidHeight)?.let { i.putExtra("h_national", it).putExtra("height_system", m.system) } }
            try { context.sendBroadcast(i, PERMISSION_POSITION) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- fields and import

    /** Ends the boundary capture and saves it; null when it has fewer than 3 vertices. */
    fun finishField(name: String, note: String = ""): com.rfsat.pocketgnss.data.Field? {
        val (v, worst) = fieldCapture.finish()
        if (v.size < 3) return null
        val f = com.rfsat.pocketgnss.data.Field(name, v, System.currentTimeMillis(), worst, note)
        fieldStore.add(f); _fields.value = fieldStore.load()
        return f
    }

    fun deleteField(f: com.rfsat.pocketgnss.data.Field) { fieldStore.remove(f); _fields.value = fieldStore.load() }
    fun renameField(f: com.rfsat.pocketgnss.data.Field, name: String) { fieldStore.rename(f, name); _fields.value = fieldStore.load() }

    fun nextFieldName(): String {
        val used = _fields.value.map { it.name }.toSet()
        var i = _fields.value.size + 1
        while ("F$i" in used) i++
        return "F$i"
    }

    /**
     * Imports points and boundaries from a file (CSV, GeoJSON, KML, GPX, zipped shapefile). Grid coordinates without a
     * named grid use the one chosen in Setup (UTM: its fixed zone, else the zone of the current position).
     */
    fun importFile(bytes: ByteArray, name: String): com.rfsat.pocketgnss.data.ImportResult {
        val st = store.value
        val f = _fix.value
        val zone = if (st.utmZone in 1..60) st.utmZone else f?.let { com.rfsat.pocketgnss.protocol.Grid.utmZone(it.longitude) } ?: 0
        val ref = com.rfsat.pocketgnss.protocol.GridRef(st.gridSystem, zone, (f?.latitude ?: 1.0) < 0)
            .takeIf { st.gridSystem != com.rfsat.pocketgnss.protocol.GridSystem.NONE }
        val r = com.rfsat.pocketgnss.data.Importer(ref, System.currentTimeMillis(), name).read(bytes)
        if (r.points.isNotEmpty()) { pointStore.save(pointStore.load() + r.points); _points.value = pointStore.load() }
        if (r.fields.isNotEmpty()) { fieldStore.addAll(r.fields); _fields.value = fieldStore.load() }
        return r
    }

    fun nextPointName(prefix: String = "P"): String {
        val used = _points.value.map { it.name }.toSet()
        var i = _points.value.size + 1
        while ("$prefix$i" in used) i++
        return "$prefix$i"
    }

    /** Starts a time-to-first-fix run; restarts the GNSS with UBX when possible ([type] null = user power-cycles). */
    fun startTtff(type: StartType?, label: String) {
        _ttff.value = _ttff.value.copy(current = TtffRun(label, System.currentTimeMillis()))
        if (type != null) scope.launch { writeRaw(Ubx.reset(type)) }
    }

    fun cancelTtff() { _ttff.value = _ttff.value.copy(current = null) }

    fun setNtrip(on: Boolean) {
        store.update { it.copy(ntripEnabled = on) }
        val own = linkInfo?.let { receiverHasOwnNtrip(it) } == true
        _relay.value = RelayArbiter(); relayGateOpen = true; relayArb.reset()
        if (on && linkInfo?.writable != false && !own) ntripClient.start(store.value.ntrip) else if (!rtcmCapture) ntripClient.stop()
    }


    /** The connected receiver fetches corrections itself (its hardware version as chosen in Setup). */
    private fun receiverHasOwnNtrip(info: LinkInfo): Boolean =
        (info.kind == LinkKind.USB || info.kind == LinkKind.BLUETOOTH || info.kind == LinkKind.TCP) && store.value.receiverVariant in OWN_NTRIP

    /**
     * The receiver is using corrections now: RTK float or fixed, or DGNSS from a base (not an EGNOS satellite, station
     * 120-158), with a recent correction age; for RFSAT firmware also "$PRFSM ... live".
     */
    private fun correctionsInUse(now: Long): Boolean {
        val r = _rover.value
        if (r.rfsat != null && now - r.rfsatMs < 5000 && r.rfsat.corrections.equals("live", true)) return true
        val f = _fix.value ?: return false
        if (now - f.receivedAtMs > 5000) return false
        // 1.13.5: RTK only counts while its corrections are fresh. The LG290P keeps reporting RTK fixed for up to
        // about 2 min after its corrections stop (correction age climbing), which held the relay back that long.
        if (f.quality == FixQuality.RTK_FIXED || f.quality == FixQuality.RTK_FLOAT) return (f.diffAgeSec ?: 0.0) < 15
        val sbas = f.diffStation?.trim()?.toIntOrNull()?.let { it in 120..158 } == true
        return f.quality == FixQuality.DGNSS && f.diffAgeSec != null && f.diffAgeSec < 15 && !sbas
    }

    /** Applies a relay role: the gate to the receiver, and the relay's caster connection. */
    private fun applyRelay(role: RelayRole, now: Long) {
        _relay.value = RelayArbiter(role, now)
        val feed = role == RelayRole.FEEDING || role == RelayRole.DIRECT
        relayGateOpen = feed
        if (role == RelayRole.FEEDING) { if (!ntripClient.running && store.value.ntrip.usable) ntripClient.start(store.value.ntrip) }
        else if (role == RelayRole.DIRECT) {
            // back to plain relaying (e.g. the receiver choice changed to one without its own client)
            if (store.value.ntripEnabled && linkInfo?.writable == true && !ntripClient.running && store.value.ntrip.usable) ntripClient.start(store.value.ntrip)
        } else if (!rtcmCapture && ntripClient.running) ntripClient.stop() // also frees a shared caster login
        AppLog.i("Relay", "NTRIP relay: " + role.name.lowercase())
    }

    /** Once a second (from [publish]): who corrects a receiver with its own NTRIP client. */
    private fun arbitrateRelay(now: Long) {
        if (now - relayCheckedMs < 1000) return
        relayCheckedMs = now
        val info = linkInfo
        val active = info != null && info.writable && store.value.ntripEnabled && receiverHasOwnNtrip(info)
        relayArb.step(now, active, active && correctionsInUse(now))?.let { applyRelay(it, now) }
    }

    fun toast(msg: String) { _messages.tryEmit(msg) }

    /** True while something should keep running with the screen off (real receiver only). */
    fun needsBackground(): Boolean {
        val s = store.value
        if (raw.state.value.recording) return true
        // The phone's own GNSS (also phone RTK) stops when the screen turns off unless a foreground service holds it.
        return connection.value is Connection.Ready && simulator == null && (linkInfo?.kind == LinkKind.PHONE ||
            recording.value.active || ntripClient.running || (s.ntripEnabled && _relay.value.role != RelayRole.DIRECT) || s.mockLocation || s.nmeaServer || (s.btServer && btServer.running) || s.casterOn || s.apiJson || s.apiBroadcast)
    }

    /** Stops background activity (notification "Stop" action). */
    fun stopBackground() {
        if (raw.state.value.running) raw.stop()
        stopRecording()
        if (ntripClient.running) setNtrip(false)
        store.update { it.copy(mockLocation = false, nmeaServer = false, btServer = false) }
    }

    fun shutdown() {
        flushTrack()
        history.stopFile()
        if (raw.state.value.running) raw.stop()
        stopRecording(); ntripClient.stop(); ephClient.stop(); caster.stop(); jsonServer.stop(); mock.stop(); server.stop(); btServer.stop()
        disconnect()
    }

    fun close() { shutdown(); scope.cancel() }

    companion object {

        /** Receivers with their own NTRIP client (the relay then arbitrates, [RelayRole]). The wired LG290P (V5) has none. */
        val OWN_NTRIP = setOf(com.rfsat.pocketgnss.link.ReceiverVariant.LTE, com.rfsat.pocketgnss.link.ReceiverVariant.WIFI,
            com.rfsat.pocketgnss.link.ReceiverVariant.PICORTK, com.rfsat.pocketgnss.link.ReceiverVariant.LG290P_C6,
            com.rfsat.pocketgnss.link.ReceiverVariant.POSTCARDRTK, com.rfsat.pocketgnss.link.ReceiverVariant.RTK_EVERYWHERE)
        /** Broadcast of each position (at most 5 per second) to apps holding [PERMISSION_POSITION]. */
        const val ACTION_POSITION = "com.PocketGNSS.action.POSITION"
        const val PERMISSION_POSITION = "com.PocketGNSS.permission.POSITION"
    }
}
