package com.rfsat.pocketgnss.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssFix
import com.rfsat.pocketgnss.data.CoordFormat
import com.rfsat.pocketgnss.data.HeightRef
import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.FixQuality
import java.util.Locale
import kotlin.math.abs

@StringRes fun fixLabel(q: FixQuality): Int = when (q) {
    FixQuality.NONE -> R.string.fix_none
    FixQuality.SINGLE -> R.string.fix_single
    FixQuality.DGNSS -> R.string.fix_dgnss
    FixQuality.PPS -> R.string.fix_pps
    FixQuality.RTK_FIXED -> R.string.fix_rtk_fixed
    FixQuality.RTK_FLOAT -> R.string.fix_rtk_float
    FixQuality.DEAD_RECKONING -> R.string.fix_dr
    FixQuality.MANUAL -> R.string.fix_manual
    FixQuality.SIMULATION -> R.string.fix_simulation
}

/** Expected accuracy class shown under the fix type (from the D3.5 validation). */
@StringRes fun fixHint(q: FixQuality): Int = when (q) {
    FixQuality.RTK_FIXED -> R.string.fixhint_fixed
    FixQuality.RTK_FLOAT -> R.string.fixhint_float
    FixQuality.DGNSS -> R.string.fixhint_dgnss
    FixQuality.SINGLE, FixQuality.PPS -> R.string.fixhint_single
    FixQuality.DEAD_RECKONING -> R.string.fixhint_dr
    else -> R.string.fixhint_none
}

/** Fix colours, also used for the map marker and track: the same in both themes. */
fun fixColor(q: FixQuality): Color = when (q) {
    FixQuality.RTK_FIXED -> Color(0xFF2E7D32)
    FixQuality.RTK_FLOAT -> Color(0xFFF9A825)
    FixQuality.DGNSS -> Color(0xFF1E88E5)
    FixQuality.SINGLE, FixQuality.PPS -> Color(0xFF757575)
    FixQuality.DEAD_RECKONING -> Color(0xFF8E24AA)
    else -> Color(0xFFC62828)
}

fun fixColorHex(q: FixQuality): String = "#%06X".format(fixColor(q).value.toLong().ushr(32).toInt() and 0xFFFFFF)

/** "±8 mm", "±1.4 cm", "±35 cm", "±1.2 m". */
fun formatAccuracy(m: Double): String = "±" + formatDistance(m)

fun formatDistance(m: Double): String {
    val a = abs(m)
    return when {
        a < 0.01 -> "%.0f mm".format(Locale.US, m * 1000)
        a < 0.1 -> "%.1f cm".format(Locale.US, m * 100)
        a < 1.0 -> "%.0f cm".format(Locale.US, m * 100)
        a < 1000 -> "%.2f m".format(Locale.US, m)
        else -> "%.2f km".format(Locale.US, m / 1000)
    }
}

/** Signed centimetres for offsets: "+1.2 cm", "-35.0 cm", "+2.41 m". */
fun formatOffset(m: Double): String = (if (m >= 0) "+" else "−") + formatDistance(abs(m))

fun formatLat(v: Double, f: CoordFormat) = formatAngle(v, f, if (v >= 0) "N" else "S", 9)
fun formatLon(v: Double, f: CoordFormat) = formatAngle(v, f, if (v >= 0) "E" else "W", 9)

private fun formatAngle(v: Double, f: CoordFormat, hemi: String, decimals: Int): String = when (f) {
    CoordFormat.DECIMAL -> "%.${decimals}f°".format(Locale.US, v)
    CoordFormat.DMS -> {
        val a = abs(v)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        "%d° %02d′ %08.5f″ %s".format(Locale.US, d, m, s, hemi)
    }
}

fun heightOf(f: GnssFix, ref: HeightRef): Double? = when (ref) {
    HeightRef.MSL -> f.altitudeMsl
    HeightRef.ELLIPSOID -> f.ellipsoidHeight
}

fun formatHeight(h: Double?) = h?.let { "%.3f m".format(Locale.US, it) } ?: "—"

fun constellationColor(c: Constellation): Color = when (c) {
    Constellation.GPS -> Color(0xFF1E88E5)
    Constellation.GLONASS -> Color(0xFFE53935)
    Constellation.GALILEO -> Color(0xFF3949AB)
    Constellation.BEIDOU -> Color(0xFFFB8C00)
    Constellation.QZSS -> Color(0xFF8E24AA)
    Constellation.NAVIC -> Color(0xFF00897B)
    Constellation.SBAS -> Color(0xFF6D4C41)
    Constellation.UNKNOWN -> Color(0xFF9E9E9E)
}

@StringRes fun constellationName(c: Constellation): Int = when (c) {
    Constellation.GPS -> R.string.cons_gps
    Constellation.GLONASS -> R.string.cons_glonass
    Constellation.GALILEO -> R.string.cons_galileo
    Constellation.BEIDOU -> R.string.cons_beidou
    Constellation.QZSS -> R.string.cons_qzss
    Constellation.NAVIC -> R.string.cons_navic
    Constellation.SBAS -> R.string.cons_sbas
    Constellation.UNKNOWN -> R.string.cons_unknown
}

fun formatDuration(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@StringRes fun ntripStatusLabel(s: com.rfsat.pocketgnss.core.NtripStatus): Int = when (s) {
    com.rfsat.pocketgnss.core.NtripStatus.OFF -> R.string.ntrip_st_off
    com.rfsat.pocketgnss.core.NtripStatus.CONNECTING -> R.string.ntrip_st_connecting
    com.rfsat.pocketgnss.core.NtripStatus.STREAMING -> R.string.ntrip_st_streaming
    com.rfsat.pocketgnss.core.NtripStatus.WAITING -> R.string.ntrip_st_waiting
    com.rfsat.pocketgnss.core.NtripStatus.ERROR -> R.string.ntrip_st_error
}
