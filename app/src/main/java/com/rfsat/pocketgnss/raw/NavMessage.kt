package com.rfsat.pocketgnss.raw

/**
 * One navigation message as the phone's GNSS chip demodulated it (Android GnssNavigationMessage): a GPS / QZSS / BeiDou
 * subframe, a Galileo I/NAV page or a GLONASS string. The phone RTK engine decodes broadcast ephemerides from them, so
 * it needs none from the caster. [type] and [data] as Android defines them (TYPE_GPS_L1CA = 0x0101, ...).
 */
class NavMessage(val type: Int, val svid: Int, val status: Int, val messageId: Int, val submessageId: Int, val data: ByteArray) {
    /** The system, for counts ("GPS", "Galileo", ...); null for kinds the engine does not use. */
    val system: String? get() = systemOf(type)

    companion object {
        const val GPS_L1CA = 0x0101
        const val QZS_L1CA = 0x0401
        const val GLO_L1CA = 0x0301
        const val BDS_D1 = 0x0501
        const val GAL_I = 0x0601

        fun systemOf(type: Int): String? = when (type) {
            GPS_L1CA -> "GPS"; QZS_L1CA -> "QZSS"; GLO_L1CA -> "GLONASS"; BDS_D1 -> "BeiDou"; GAL_I -> "Galileo"; else -> null
        }

        /** A "Nav," line of a GnssLogger file. */
        fun parse(line: String): NavMessage? {
            val f = line.split(',')
            if (f.size < 7 || f[0] != "Nav") return null
            val svid = f[1].trim().toIntOrNull() ?: return null
            val type = f[2].trim().toIntOrNull() ?: return null
            val status = f[3].trim().toIntOrNull() ?: 0
            val id = f[4].trim().toIntOrNull() ?: -1
            val sub = f[5].trim().toIntOrNull() ?: -1
            val data = f.drop(6).mapNotNull { it.trim().toIntOrNull()?.toByte() }.toByteArray()
            return NavMessage(type, svid, status, id, sub, data)
        }

        /** GLONASS frequency channel (-7..6) from a G1 carrier frequency (Hz), or -99. */
        fun glonassChannel(carrierHz: Double?): Int {
            val f = carrierHz ?: return -99
            val k = Math.round((f - 1_602_000_000.0) / 562_500.0).toInt()
            return if (k in -7..6 && kotlin.math.abs(f - (1_602_000_000.0 + k * 562_500.0)) < 1_000.0) k else -99
        }
    }
}
