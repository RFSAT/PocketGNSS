package com.rfsat.pocketgnss.protocol

import java.util.Locale

/*
 * Quectel LG290P proprietary sentences and commands, and the status sentence of RFSAT's own firmware ($PRFSM).
 *
 * The RFSAT RTK rovers (master documentation RFSAT-RTK-DOC-001) all use the LG290P: PicoRTK (V1), LG290P + ESP32-C6
 * (V2), PostcardRTK (V3), the Postcard with RTK Everywhere (V4) and the receiver wired on its own (V5, V6).
 * Field layouts follow RFSAT's firmware parsers (PostcardRTK nmea.cpp / ladder.cpp) and SparkFun's LG290P library.
 * Pure Kotlin, so everything here runs in plain JVM unit tests.
 */

/** $PQTMEPE,2,north,east,down,2D,3D: estimated position error in metres. */
data class QuectelEpe(val north: Double, val east: Double, val down: Double, val horizontal: Double, val spherical: Double)

/**
 * $PQTMPPPNAV: the receiver's PPP solution. [solution] 0 none, 1 single, 2 SBAS, 5 DGNSS, 6 PPP converging,
 * 7 PPP converged, 8 RTK float, 12 RTK fixed; [diffId] 9002 = Galileo HAS, 9001 = BeiDou B2b.
 */
data class QuectelPppNav(val solution: Int, val latStd: Double?, val lonStd: Double?, val altStd: Double?, val diffId: Int?,
                         val diffAgeSec: Double?, val satellitesUsed: Int?) {
    val converging: Boolean get() = solution == 6
    val converged: Boolean get() = solution == 7
    val galileoHas: Boolean get() = diffId == 9002
}

/** $PQTMVERNO,version,date,time: firmware version, e.g. LG290P03AANR02A02S. */
data class QuectelVersion(val version: String, val date: String?, val time: String?) {
    val module: String get() = when {
        version.startsWith("LG290P") -> "Quectel LG290P"
        version.startsWith("LG580P") -> "Quectel LG580P"
        else -> "Quectel " + version.takeWhile { it != '0' && it != 'R' }.ifEmpty { version }
    }
}

/**
 * $PRFSM,ladder,method,corrections,age,station,estH,estV: RFSAT method-ladder status, sent after every GGA by
 * PostcardRTK (and PicoRTK when corrections.statusSentence is on).
 */
data class RfsatStatus(val ladder: String, val method: String, val corrections: String, val ageSec: Double?, val station: String?,
                       val estH: Double?, val estV: Double?)

object Quectel {
    /** NMEA checksum of [body] (between '$' and '*'), two upper-case hex digits. */
    fun checksum(body: String): String = "%02X".format(Locale.US, body.fold(0) { a, c -> a xor c.code })

    /** Full command line, e.g. "PQTMVERNO" -> "$PQTMVERNO*58". */
    fun command(body: String): String = "$" + body + "*" + checksum(body)

    /** The sentence name of a command or reply ("PQTMCFGUART,W,3,38400" -> "PQTMCFGUART"). */
    fun name(body: String): String = body.removePrefix("$").substringBefore(',').substringBefore('*')

    /** Reply to a command: OK, ERROR (with the code if any), or a read-back value. */
    sealed class Reply {
        object Ok : Reply()
        data class Error(val code: String?) : Reply()
        data class Value(val fields: List<String>) : Reply()
    }

    /** Parses the receiver's reply line to the command [name]; null when [line] is not that reply. */
    fun reply(name: String, line: String): Reply? {
        val l = line.trim()
        if (!l.startsWith("$$name,") && !l.startsWith("$$name*")) return null
        val body = l.substring(1).substringBefore('*')
        val f = body.split(',').drop(1)
        return when {
            f.firstOrNull() == "OK" -> Reply.Ok
            f.firstOrNull() == "ERROR" -> Reply.Error(f.getOrNull(1))
            else -> Reply.Value(f)
        }
    }

    private fun d(s: String?) = s?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    private fun i(s: String?) = d(s)?.toInt()

    /** From a parsed proprietary sentence (Nmea.parse gives talker "P", type "QTMEPE", fields after the address). */
    fun epe(fields: List<String>): QuectelEpe? {
        if (fields.size < 6) return null
        return QuectelEpe(d(fields[1]) ?: return null, d(fields[2]) ?: return null, d(fields[3]) ?: return null,
            d(fields[4]) ?: return null, d(fields[5]) ?: return null)
    }

    fun pppNav(fields: List<String>): QuectelPppNav? {
        if (fields.size < 28) return null
        val sol = i(fields[10]) ?: return null
        return QuectelPppNav(sol, d(fields[18]), d(fields[19]), d(fields[20]), i(fields[23]), d(fields[24]), i(fields[27]))
    }

    fun version(fields: List<String>): QuectelVersion? {
        val v = fields.getOrNull(0)?.takeIf { it.isNotBlank() && it != "OK" && it != "ERROR" } ?: return null
        return QuectelVersion(v, fields.getOrNull(1)?.takeIf { it.isNotBlank() }, fields.getOrNull(2)?.takeIf { it.isNotBlank() })
    }

    fun rfsat(fields: List<String>): RfsatStatus? {
        if (fields.size < 7) return null
        return RfsatStatus(fields[0], fields[1], fields[2], d(fields[3]), fields[4].takeIf { it.isNotEmpty() }, d(fields[5]), d(fields[6]))
    }
}

/** Receiver profiles of the master documentation (V5 "Profiles"): the commands, in order, without save and restart. */
enum class QuectelProfile { GENERAL, FLEET, TRACTOR;

    /**
     * Command bodies (no '$' or checksum). [uart3]: messages set for UART3, the receiver's 4-pin socket (port form
     * W,1,3,...), as in the documentation; otherwise for the port the commands arrive on. [has]: Galileo HAS on or off.
     * The Tractor profile ends with UART3 at 38400 bit/s (only for UART3: the link in use would be cut).
     */
    fun commands(uart3: Boolean = true, has: Boolean = true): List<String> {
        fun msg(m: String, rate: Int, ver: Int? = null) =
            "PQTMCFGMSGRATE,W," + (if (uart3) "1,3," else "") + "$m,$rate" + (ver?.let { ",$it" } ?: "")
        val ppp = if (has) "PQTMCFGPPP,W,2,1,120,0.10,0.15" else "PQTMCFGPPP,W,0,0,0"
        return when (this) {
            GENERAL -> listOf("PQTMCFGRCVRMODE,W,1", "PQTMCFGFIXRATE,W,1000",
                msg("GGA", 1), msg("RMC", 1), msg("GSA", 1), msg("GSV", 1), msg("GST", 1), msg("PQTMEPE", 1, 2), msg("PQTMPPPNAV", 1, 1),
                ppp, "PQTMCFGNAVMODE,W,0")
            FLEET -> listOf("PQTMCFGRCVRMODE,W,1", "PQTMCFGFIXRATE,W,1000",
                msg("GGA", 1), msg("RMC", 1), msg("GST", 1), msg("PQTMEPE", 1, 2), msg("GSV", 0), ppp, "PQTMCFGNAVMODE,W,0")
            TRACTOR -> listOf("PQTMCFGRCVRMODE,W,1", "PQTMCFGFIXRATE,W,100",
                msg("GGA", 1), msg("GSA", 1), msg("VTG", 1), msg("RMC", 1), msg("GSV", 0), ppp, "PQTMCFGNAVMODE,W,14", "PQTMCFGELETHD,W,10") +
                (if (uart3) listOf("PQTMCFGUART,W,3,38400") else emptyList())
        }
    }

    companion object {
        const val SAVE = "PQTMSAVEPAR"
        const val RESTART = "PQTMSRR"
        const val VERSION = "PQTMVERNO"
        const val FACTORY = "PQTMRESTOREPAR"
    }
}
