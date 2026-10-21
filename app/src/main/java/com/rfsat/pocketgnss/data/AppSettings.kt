package com.rfsat.pocketgnss.data

import android.content.Context
import android.content.SharedPreferences
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.NtripConfig
import com.rfsat.pocketgnss.sim.SimConfig
import com.rfsat.pocketgnss.sim.SimMotion
import com.rfsat.pocketgnss.ui.map.MapLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoordFormat { DECIMAL, DMS }
enum class HeightRef { MSL, ELLIPSOID }

data class AppSettings(
    // Source
    val source: LinkKind = LinkKind.USB,
    /** UART bridge baud rate; 0 = automatic search (115200 first, then 9600, 38400, 57600, 230400, 460800). */
    val usbBaud: Int = 0,
    val btAddress: String = "",
    val btName: String = "",
    val tcpHost: String = "192.168.4.1",
    val tcpPort: Int = 3967,
    val autoReconnect: Boolean = true,
    /** Phone source: RTK engine on the phone's raw measurements (with the NTRIP relay), else Android's location. */
    val phoneRtk: Boolean = true,
    /** Phone RTK: phone held still on a point (static) instead of moving (kinematic). */
    val phoneRtkStatic: Boolean = false,
    /** Adapt the RTK engine to what this phone has shown in past sessions (PhoneTuning). */
    val phoneRtkAuto: Boolean = true,
    /** Learned profile of this phone (PhoneProfile.encode), "" = none yet. */
    val phoneProfile: String = "",
    // Corrections from the phone
    val ntripEnabled: Boolean = false,
    val ntrip: NtripConfig = NtripConfig(),
    /** Saved caster accounts; [activeProfile] is the one last loaded into [ntrip] ("" = none). */
    val profiles: List<CasterProfile> = emptyList(),
    val activeProfile: String = "",
    // Outputs
    val mockLocation: Boolean = false,
    val nmeaServer: Boolean = false,
    /** The phone as a Bluetooth GNSS receiver (SPP server streaming NMEA). */
    val btServer: Boolean = false,
    /** Sound / vibrate / notify when the fix drops below [alertFloat]-or-fixed or corrections go stale. */
    val alertEnabled: Boolean = true,
    /** Alert threshold: RTK float (true) or RTK fixed (false). */
    val alertFloat: Boolean = false,
    val alertStaleSec: Int = 10,
    val alertSound: Boolean = true,
    val alertVibrate: Boolean = true,
    /** Set once the user has gone through the Bluetooth sharing set-up (Home then switches it on directly). */
    val btServerConfigured: Boolean = false,
    /** Send only the position sentences (GGA, RMC, GST, GSA, VTG, GNS, ZDA, GLL), not satellites in view. */
    val btServerPositionOnly: Boolean = false,
    val nmeaServerPort: Int = 10110,
    val nmeaServerLocalOnly: Boolean = true,
    // Display
    val coordFormat: CoordFormat = CoordFormat.DECIMAL,
    /** Grid (plane) coordinates shown beside latitude / longitude and added to point exports. */
    val gridSystem: com.rfsat.pocketgnss.protocol.GridSystem = com.rfsat.pocketgnss.protocol.GridSystem.NONE,
    /** UTM zone: 0 = the zone of the position, else a fixed zone (1..60). */
    val utmZone: Int = 0,
    val heightRef: HeightRef = HeightRef.MSL,
    val keepScreenOn: Boolean = true,
    val mapLayer: MapLayer = MapLayer.SATELLITE,
    // Logging
    val logMinQuality: FixQuality = FixQuality.NONE,
    /** Seconds between logged fixes; 0 = every epoch. Raw NMEA is always complete. */
    val logIntervalSec: Double = 0.0,
    // Receiver web server (ESPrtk): ID shown on the LCD, e.g. AJG03, and the number written on the case.
    val receiverId: String = "",
    val deviceNumber: String = "",
    /** Hardware version: with the LTE modem board (SIM) or the compact Wi-Fi-only build. */
    val receiverVariant: com.rfsat.pocketgnss.link.ReceiverVariant = com.rfsat.pocketgnss.link.ReceiverVariant.UNSET,
    // Simulator
    val simMotion: SimMotion = SimMotion.STATIC,
    val simCorrections: Boolean = true,
) {
    val simConfig: SimConfig get() = SimConfig(simMotion, simCorrections,
        if (receiverVariant == com.rfsat.pocketgnss.link.ReceiverVariant.LTE) com.rfsat.pocketgnss.link.ReceiverVariant.LTE else com.rfsat.pocketgnss.link.ReceiverVariant.WIFI)
    val useSimulator: Boolean get() = source == LinkKind.SIMULATOR
}

/** SharedPreferences-backed settings exposed as a StateFlow (same pattern as PocketSpectro). */
class SettingsStore(context: Context, private val cipher: SecretCipher = KeystoreCipher) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pocketgnss", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val n = d.ntrip
        return AppSettings(
            source = enumOr(prefs.getString("source", null), d.source),
            usbBaud = prefs.getInt("usbBaud", d.usbBaud),
            btAddress = prefs.getString("btAddress", d.btAddress) ?: "",
            btName = prefs.getString("btName", d.btName) ?: "",
            tcpHost = prefs.getString("tcpHost", d.tcpHost) ?: d.tcpHost,
            tcpPort = prefs.getInt("tcpPort", d.tcpPort),
            autoReconnect = prefs.getBoolean("autoReconnect", d.autoReconnect),
            phoneRtk = prefs.getBoolean("phoneRtk", d.phoneRtk),
            phoneRtkStatic = prefs.getBoolean("phoneRtkStatic", d.phoneRtkStatic),
            phoneRtkAuto = prefs.getBoolean("phoneRtkAuto", d.phoneRtkAuto),
            phoneProfile = prefs.getString("phoneProfile", d.phoneProfile) ?: "",
            ntripEnabled = prefs.getBoolean("ntripOn", d.ntripEnabled),
            profiles = CasterProfiles.decode(prefs.getString("casterProfiles", null), cipher),
            activeProfile = prefs.getString("casterActive", "") ?: "",
            ntrip = NtripConfig(
                host = prefs.getString("ntripHost", n.host) ?: "",
                port = prefs.getInt("ntripPort", n.port),
                mountpoint = prefs.getString("ntripMount", n.mountpoint) ?: "",
                user = prefs.getString("ntripUser", n.user) ?: "",
                password = loadPassword(),
                version2 = prefs.getBoolean("ntripV2", n.version2),
                tls = prefs.getBoolean("ntripTls", n.tls),
                ggaIntervalSec = prefs.getInt("ntripGga", n.ggaIntervalSec),
            ),
            mockLocation = prefs.getBoolean("mock", d.mockLocation),
            nmeaServer = prefs.getBoolean("server", d.nmeaServer),
            btServer = prefs.getBoolean("btServer", d.btServer),
            alertEnabled = prefs.getBoolean("alertOn", d.alertEnabled),
            alertFloat = prefs.getBoolean("alertFloat", d.alertFloat),
            alertStaleSec = prefs.getInt("alertStale", d.alertStaleSec),
            alertSound = prefs.getBoolean("alertSound", d.alertSound),
            alertVibrate = prefs.getBoolean("alertVibrate", d.alertVibrate),
            btServerConfigured = prefs.getBoolean("btServerConfigured", d.btServerConfigured),
            btServerPositionOnly = prefs.getBoolean("btServerPosOnly", d.btServerPositionOnly),
            nmeaServerPort = prefs.getInt("serverPort", d.nmeaServerPort),
            nmeaServerLocalOnly = prefs.getBoolean("serverLocal", d.nmeaServerLocalOnly),
            coordFormat = enumOr(prefs.getString("coordFormat", null), d.coordFormat),
            gridSystem = enumOr(prefs.getString("gridSystem", null), d.gridSystem),
            utmZone = prefs.getInt("utmZone", d.utmZone).coerceIn(0, 60),
            heightRef = enumOr(prefs.getString("heightRef", null), d.heightRef),
            keepScreenOn = prefs.getBoolean("screenOn", d.keepScreenOn),
            mapLayer = enumOr(prefs.getString("mapLayer", null), d.mapLayer),
            logMinQuality = enumOr(prefs.getString("logMinQ", null), d.logMinQuality),
            logIntervalSec = prefs.getFloat("logInterval", d.logIntervalSec.toFloat()).toDouble(),
            receiverId = prefs.getString("receiverId", d.receiverId) ?: "",
            deviceNumber = prefs.getString("deviceNumber", d.deviceNumber) ?: "",
            receiverVariant = enumOr(prefs.getString("variant", null), d.receiverVariant),
            simMotion = enumOr(prefs.getString("simMotion", null), d.simMotion),
            simCorrections = prefs.getBoolean("simCorr", d.simCorrections),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putInt("usbBaud", s.usbBaud)
            .putString("btAddress", s.btAddress).putString("btName", s.btName)
            .putString("tcpHost", s.tcpHost).putInt("tcpPort", s.tcpPort)
            .putBoolean("autoReconnect", s.autoReconnect)
            .putBoolean("phoneRtk", s.phoneRtk).putBoolean("phoneRtkStatic", s.phoneRtkStatic)
            .putBoolean("phoneRtkAuto", s.phoneRtkAuto).putString("phoneProfile", s.phoneProfile)
            .putBoolean("ntripOn", s.ntripEnabled)
            .putString("ntripHost", s.ntrip.host).putInt("ntripPort", s.ntrip.port)
            .putString("ntripMount", s.ntrip.mountpoint).putString("ntripUser", s.ntrip.user)
            .putString("ntripPassEnc", if (s.ntrip.password.isEmpty()) "" else cipher.encrypt(s.ntrip.password) ?: "")
            .remove("ntripPass")
            .putString("casterProfiles", CasterProfiles.encode(s.profiles, cipher)).putString("casterActive", s.activeProfile)
            .putBoolean("ntripV2", s.ntrip.version2).putBoolean("ntripTls", s.ntrip.tls).putInt("ntripGga", s.ntrip.ggaIntervalSec)
            .putBoolean("mock", s.mockLocation)
            .putBoolean("alertOn", s.alertEnabled).putBoolean("alertFloat", s.alertFloat).putInt("alertStale", s.alertStaleSec)
            .putBoolean("alertSound", s.alertSound).putBoolean("alertVibrate", s.alertVibrate)
            .putBoolean("btServer", s.btServer).putBoolean("btServerConfigured", s.btServerConfigured)
            .putBoolean("btServerPosOnly", s.btServerPositionOnly)
            .putBoolean("server", s.nmeaServer).putInt("serverPort", s.nmeaServerPort).putBoolean("serverLocal", s.nmeaServerLocalOnly)
            .putString("coordFormat", s.coordFormat.name).putString("gridSystem", s.gridSystem.name).putInt("utmZone", s.utmZone).putString("heightRef", s.heightRef.name)
            .putBoolean("screenOn", s.keepScreenOn).putString("mapLayer", s.mapLayer.name)
            .putString("logMinQ", s.logMinQuality.name).putFloat("logInterval", s.logIntervalSec.toFloat())
            .putString("receiverId", s.receiverId).putString("deviceNumber", s.deviceNumber)
            .putString("variant", s.receiverVariant.name)
            .putString("simMotion", s.simMotion.name).putBoolean("simCorr", s.simCorrections)
            .apply()
    }

    /** The caster password, decrypted; a plain-text one from version 1.1.x is moved into encrypted storage. */
    private fun loadPassword(): String {
        prefs.getString("ntripPass", null)?.let { old ->
            prefs.edit().remove("ntripPass").putString("ntripPassEnc", if (old.isEmpty()) "" else cipher.encrypt(old) ?: "").apply()
            return old
        }
        val enc = prefs.getString("ntripPassEnc", "") ?: ""
        return if (enc.isEmpty()) "" else cipher.decrypt(enc) ?: ""
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
