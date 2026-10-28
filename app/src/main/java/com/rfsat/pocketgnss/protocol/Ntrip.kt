package com.rfsat.pocketgnss.protocol

import java.util.Base64
import java.util.Locale

/*
 * NTRIP client protocol (v1 "ICY 200 OK" and v2 HTTP/1.1 with chunked transfer), sourcetable parsing.
 * The receiver can run its own NTRIP client over Wi-Fi or its LTE modem (ESPrtk "7 - NTRIP Client",
 * D3.3 §4.7.11); this one runs on the phone and forwards RTCM to the receiver over USB or Bluetooth, for
 * receivers without SIM / Wi-Fi, or to use the phone's data plan (the "smartphone as a relay" case of
 * D3.3 §4.2).
 */

data class NtripConfig(
    val host: String = "",
    val port: Int = 2101,
    val mountpoint: String = "",
    val user: String = "",
    val password: String = "",
    val version2: Boolean = true,
    val tls: Boolean = false,
    /** Seconds between GGA uploads (network / VRS mountpoints need the rover position); 0 = never. */
    val ggaIntervalSec: Int = 10,
) {
    val usable: Boolean get() = host.isNotBlank() && port in 1..65535 && mountpoint.isNotBlank()
    /** Encrypted connection: TLS switched on, or the HTTPS port 443 (Galileo HAS at ntrip.gsc-europa.eu:443 needs it). */
    val secure: Boolean get() = tls || port == 443
}

/** One STR line of a caster's sourcetable. */
data class Mountpoint(
    val name: String, val identifier: String, val format: String, val formatDetails: String, val carrier: Int,
    val navSystems: String, val network: String, val country: String, val latitude: Double?, val longitude: Double?,
    /** True when the caster wants the rover's GGA (network RTK / VRS). */
    val needsGga: Boolean, val solution: Int, val authentication: String, val fee: Boolean, val bitrate: Int?,
)

object Ntrip {
    const val USER_AGENT = "NTRIP PocketGNSS/1.0"

    fun basicAuth(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    /** GET request for a mountpoint, or for the sourcetable when [mountpoint] is empty. */
    fun request(c: NtripConfig, mountpoint: String = c.mountpoint): String {
        val path = "/" + mountpoint.trimStart('/')
        val sb = StringBuilder()
        if (c.version2 || c.secure) {   // NTRIP over TLS is NTRIP 2 (HTTP/1.1)
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(c.host).append(if (c.port != 80 && c.port != 443) ":${c.port}" else "").append("\r\n")
            sb.append("Ntrip-Version: Ntrip/2.0\r\n")
        } else {
            sb.append("GET ").append(path).append(" HTTP/1.0\r\n")
        }
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n")
        if (c.user.isNotEmpty()) sb.append("Authorization: ").append(basicAuth(c.user, c.password)).append("\r\n")
        sb.append("Accept: */*\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString()
    }

    enum class Reply { STREAM, SOURCETABLE, UNAUTHORIZED, NOT_FOUND, ERROR }

    data class Header(val reply: Reply, val statusLine: String, val chunked: Boolean, val headers: Map<String, String>)

    /** Interprets the response header block (status line + header lines, without the blank line). */
    fun parseHeader(block: String): Header {
        val lines = block.split("\r\n", "\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull() ?: ""
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase(Locale.US) to l.substring(i + 1).trim()
        }.toMap()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        val ctype = headers["content-type"]?.lowercase(Locale.US) ?: ""
        val reply = when {
            status.startsWith("ICY 200") -> Reply.STREAM
            status.startsWith("SOURCETABLE 200") -> Reply.SOURCETABLE
            code == 200 && ctype.contains("sourcetable") -> Reply.SOURCETABLE
            code == 200 -> Reply.STREAM
            code == 401 || code == 403 -> Reply.UNAUTHORIZED
            code == 404 -> Reply.NOT_FOUND
            else -> Reply.ERROR
        }
        return Header(reply, status, headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true, headers)
    }

    /** Parses the STR lines of a sourcetable (CAS / NET lines are ignored). */
    fun parseSourcetable(text: String): List<Mountpoint> = text.lineSequence().mapNotNull { l ->
        val f = l.trim().split(';')
        if (f.size < 12 || f[0] != "STR") null else Mountpoint(
            name = f[1], identifier = f.getOrElse(2) { "" }, format = f.getOrElse(3) { "" }, formatDetails = f.getOrElse(4) { "" },
            carrier = f.getOrElse(5) { "0" }.toIntOrNull() ?: 0, navSystems = f.getOrElse(6) { "" }, network = f.getOrElse(7) { "" },
            country = f.getOrElse(8) { "" }, latitude = f.getOrNull(9)?.toDoubleOrNull(), longitude = f.getOrNull(10)?.toDoubleOrNull(),
            needsGga = f.getOrNull(11) == "1", solution = f.getOrNull(12)?.toIntOrNull() ?: 0,
            authentication = f.getOrElse(15) { "" }, fee = f.getOrNull(16) == "Y", bitrate = f.getOrNull(17)?.toIntOrNull(),
        )
    }.toList()

    /** GGA sentence for the caster from a position (used when the receiver's own GGA is not available). */
    fun gga(secondsOfDay: Double, lat: Double, lon: Double, altMsl: Double, quality: Int = 1, sats: Int = 12, hdop: Double = 1.0): String {
        val (la, ns) = Nmea.formatLat(lat, 5)
        val (lo, ew) = Nmea.formatLon(lon, 5)
        val body = "GPGGA,${Nmea.formatTime(secondsOfDay)},$la,$ns,$lo,$ew,$quality,%02d,%.1f,%.1f,M,0.0,M,,".format(Locale.US, sats, hdop, altMsl)
        return Nmea.sentence(body)
    }
}

/**
 * Decoder for HTTP chunked transfer encoding (NTRIP v2 streams). Feed raw bytes, receive the payload.
 */
class Dechunker(private val out: (ByteArray, Int) -> Unit) {
    private var remaining = 0
    private var state = 0 // 0 size line, 1 data, 2 CRLF after data
    private val size = StringBuilder()

    fun feed(b: ByteArray, len: Int) {
        var i = 0
        while (i < len) {
            when (state) {
                0 -> {
                    val c = b[i].toInt().toChar()
                    if (c == '\n') {
                        val hex = size.toString().trim().substringBefore(';')
                        size.setLength(0)
                        if (hex.isNotEmpty()) {
                            remaining = hex.toIntOrNull(16) ?: 0
                            state = if (remaining > 0) 1 else 0
                        }
                    } else if (c != '\r') size.append(c)
                    i++
                }
                1 -> {
                    val n = minOf(remaining, len - i)
                    out(b.copyOfRange(i, i + n), n)
                    i += n; remaining -= n
                    if (remaining == 0) state = 2
                }
                else -> { if (b[i].toInt().toChar() == '\n') state = 0; i++ }
            }
        }
    }
}
