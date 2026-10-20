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
    // Corrections from the phone
    val ntripEnabled: Boolean = false,
    val ntrip: NtripConfig = NtripConfig(),
    // Outputs
    val mockLocation: Boolean = false,
    val nmeaServer: Boolean = false,
    val nmeaServerPort: Int = 10110,
    val nmeaServerLocalOnly: Boolean = true,
    // Display
    val coordFormat: CoordFormat = CoordFormat.DECIMAL,
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
class SettingsStore(context: Context) {
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
            ntripEnabled = prefs.getBoolean("ntripOn", d.ntripEnabled),
            ntrip = NtripConfig(
                host = prefs.getString("ntripHost", n.host) ?: "",
                port = prefs.getInt("ntripPort", n.port),
                mountpoint = prefs.getString("ntripMount", n.mountpoint) ?: "",
                user = prefs.getString("ntripUser", n.user) ?: "",
                password = prefs.getString("ntripPass", n.password) ?: "",
                version2 = prefs.getBoolean("ntripV2", n.version2),
                tls = prefs.getBoolean("ntripTls", n.tls),
                ggaIntervalSec = prefs.getInt("ntripGga", n.ggaIntervalSec),
            ),
            mockLocation = prefs.getBoolean("mock", d.mockLocation),
            nmeaServer = prefs.getBoolean("server", d.nmeaServer),
            nmeaServerPort = prefs.getInt("serverPort", d.nmeaServerPort),
            nmeaServerLocalOnly = prefs.getBoolean("serverLocal", d.nmeaServerLocalOnly),
            coordFormat = enumOr(prefs.getString("coordFormat", null), d.coordFormat),
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
            .putBoolean("ntripOn", s.ntripEnabled)
            .putString("ntripHost", s.ntrip.host).putInt("ntripPort", s.ntrip.port)
            .putString("ntripMount", s.ntrip.mountpoint).putString("ntripUser", s.ntrip.user).putString("ntripPass", s.ntrip.password)
            .putBoolean("ntripV2", s.ntrip.version2).putBoolean("ntripTls", s.ntrip.tls).putInt("ntripGga", s.ntrip.ggaIntervalSec)
            .putBoolean("mock", s.mockLocation)
            .putBoolean("server", s.nmeaServer).putInt("serverPort", s.nmeaServerPort).putBoolean("serverLocal", s.nmeaServerLocalOnly)
            .putString("coordFormat", s.coordFormat.name).putString("heightRef", s.heightRef.name)
            .putBoolean("screenOn", s.keepScreenOn).putString("mapLayer", s.mapLayer.name)
            .putString("logMinQ", s.logMinQuality.name).putFloat("logInterval", s.logIntervalSec.toFloat())
            .putString("receiverId", s.receiverId).putString("deviceNumber", s.deviceNumber)
            .putString("variant", s.receiverVariant.name)
            .putString("simMotion", s.simMotion.name).putBoolean("simCorr", s.simCorrections)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
