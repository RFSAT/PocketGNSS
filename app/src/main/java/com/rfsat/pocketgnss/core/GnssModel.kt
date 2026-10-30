package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.NmeaSentence
import com.rfsat.pocketgnss.protocol.UtcDate
import com.rfsat.pocketgnss.protocol.UtcTime
import kotlin.math.sqrt

/** One navigation epoch as assembled from GGA (+ RMC, GSA, GST, GNS, VTG of the same epoch). */
data class GnssFix(
    val receivedAtMs: Long,
    val time: UtcTime?,
    val date: UtcDate?,
    val latitude: Double,
    val longitude: Double,
    /** Height above mean sea level (GGA), metres. */
    val altitudeMsl: Double?,
    val geoidSeparation: Double?,
    val quality: FixQuality,
    val satellitesUsed: Int?,
    val hdop: Double?,
    val vdop: Double?,
    val pdop: Double?,
    val speedMs: Double?,
    val courseDeg: Double?,
    val diffAgeSec: Double?,
    val diffStation: String?,
    /** 1-sigma errors from GST, metres (null when GST is not enabled). */
    val sdLatitude: Double?,
    val sdLongitude: Double?,
    val sdAltitude: Double?,
    /** GNS mode characters per constellation, e.g. "RRRRNN". */
    val modes: String?,
    /** For a DGNSS fix (GGA quality 2), where the corrections come from when that can be told (see [DgnssSource]). */
    val dgnss: DgnssSource? = null,
) {
    /** Height above the WGS-84 ellipsoid (MSL + geoid separation), metres. */
    val ellipsoidHeight: Double? get() = altitudeMsl?.let { it + (geoidSeparation ?: 0.0) }

    /** True when the horizontal accuracy comes from GST rather than an estimate from the fix type. */
    val measuredAccuracy: Boolean get() = sdLatitude != null && sdLongitude != null

    /**
     * Horizontal accuracy (metres, ~68 %). From GST when present; otherwise a conservative estimate from
     * the fix type and HDOP, using the accuracies validated in D3.5 (RTK fixed ~1 cm, SBAS ~30 cm, autonomous ~1 m).
     */
    val horizontalAccuracy: Double get() {
        if (sdLatitude != null && sdLongitude != null) return sqrt(sdLatitude * sdLatitude + sdLongitude * sdLongitude)
        val h = (hdop ?: 1.0).coerceIn(0.5, 20.0)
        return when (quality) {
            FixQuality.RTK_FIXED -> 0.014 * h
            FixQuality.RTK_FLOAT -> 0.25 * h
            FixQuality.DGNSS -> 0.40 * h
            FixQuality.SINGLE, FixQuality.PPS -> 1.2 * h
            FixQuality.DEAD_RECKONING -> 5.0 * h
            else -> 50.0
        }
    }

    val verticalAccuracy: Double get() = sdAltitude ?: (horizontalAccuracy * 1.8)
}

/**
 * GGA quality 2 ("differential") covers several methods that differ greatly in accuracy: code corrections from a
 * base station (RTCM, about 0.5-1 m on a receiver), SBAS (EGNOS: about 1 m), and phone PPP.
 */
enum class DgnssSource {
    SBAS, BASE, PPP;

    companion object {
        /**
         * For an external receiver: corrections from a base when the app relays an NTRIP stream to it, else SBAS when
         * an SBAS satellite is used in the solution (GSA), else unknown (e.g. a receiver with its own NTRIP client).
         */
        fun of(quality: FixQuality, sats: List<SatInfo>, relayingCorrections: Boolean): DgnssSource? = when {
            quality != FixQuality.DGNSS -> null
            relayingCorrections -> BASE
            sats.any { it.constellation == Constellation.SBAS && it.used } -> SBAS
            else -> null
        }

        /** For phone RTK: from the engine's method (PhoneRtkLink.state.source). */
        fun ofPhoneMethod(quality: FixQuality, method: String): DgnssSource? = when {
            quality != FixQuality.DGNSS -> null
            method == "Code DGNSS" -> BASE
            method.startsWith("PPP") -> PPP
            method == "SBAS" -> SBAS
            else -> null
        }
    }
}

/** One satellite with the strongest C/N0 per signal. */
data class SatInfo(
    val constellation: Constellation,
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    /** Signal name -> C/N0 dB-Hz (null when tracked without C/N0). */
    val signals: Map<String, Int?>,
    val used: Boolean,
    val dualFrequency: Boolean,
) {
    val bestCn0: Int? get() = signals.values.filterNotNull().maxOrNull()
    /** Label as u-center shows it: G10, E25, R69, C13, S123... */
    val label: String get() = when (constellation) {
        Constellation.GPS -> "G$svid"
        Constellation.GLONASS -> "R$svid"
        Constellation.GALILEO -> "E$svid"
        Constellation.BEIDOU -> "C$svid"
        Constellation.QZSS -> "J$svid"
        Constellation.NAVIC -> "I$svid"
        Constellation.SBAS -> "S${Nmea.sbasPrn(svid)}"
        Constellation.UNKNOWN -> "?$svid"
    }
}

/**
 * Assembles epochs from parsed sentences. Pure (no Android) so the D3.5 recorded sentences can be replayed
 * in unit tests. Emits a fix on every GGA; if the stream has no GGA (RMC-only set-ups) it emits on RMC.
 */
class GnssModel(private val clock: () -> Long = System::currentTimeMillis) {

    private var rmc: NmeaSentence.Rmc? = null
    private var gst: NmeaSentence.Gst? = null
    private var gns: NmeaSentence.Gns? = null
    private var vtg: NmeaSentence.Vtg? = null
    private var dops = Triple<Double?, Double?, Double?>(null, null, null)
    private var lastGgaMs = 0L
    private var rmcSinceGga = 0

    /** constellation -> satellites used in the solution (GSA). */
    private val usedBy = HashMap<Constellation, Pair<Long, Set<Int>>>()
    /** (constellation, svid) -> data, time-stamped so satellites that set disappear. */
    private val sats = HashMap<Pair<Constellation, Int>, Pair<Long, SatInfo>>()

    var datum: String? = null; private set
    var lastText: String? = null; private set

    /**
     * Feeds one sentence; returns a completed fix when this sentence closes an epoch. Synchronized with [satellites]:
     * the link thread feeds sentences while the engine reads the sky (a ConcurrentModificationException crashed
     * 1.12.16 when the two met).
     */
    @Synchronized fun accept(s: NmeaSentence): GnssFix? {
        val now = clock()
        when (s) {
            is NmeaSentence.Gga -> { lastGgaMs = now; rmcSinceGga = 0; return fromGga(s, now) }
            is NmeaSentence.Rmc -> {
                rmc = s
                rmcSinceGga++
                if (rmcSinceGga >= 3 && now - lastGgaMs > 2500 && s.latitude != null && s.longitude != null) return fromRmc(s, now)
            }
            is NmeaSentence.Gst -> gst = s
            is NmeaSentence.Gns -> gns = s
            is NmeaSentence.Vtg -> vtg = s
            is NmeaSentence.Gsa -> {
                dops = Triple(s.pdop, s.hdop, s.vdop)
                // Pre-4.10 GSA (no system ID) from a GN talker lists mixed IDs; classify by number range.
                if (s.system != Constellation.UNKNOWN) usedBy[s.system] = now to s.satellites.toSet()
                else for ((c, ids) in s.satellites.groupBy { legacyConstellation(s.talker, it) }) usedBy[c] = now to ids.toSet()
            }
            is NmeaSentence.Gsv -> acceptGsv(s, now)
            is NmeaSentence.Dtm -> datum = s.datum
            is NmeaSentence.Txt -> lastText = s.text
            else -> {}
        }
        return null
    }

    private fun legacyConstellation(talker: String, id: Int): Constellation = when {
        talker == "GL" || id in 65..96 -> Constellation.GLONASS
        talker == "GA" -> Constellation.GALILEO
        talker == "GB" || talker == "BD" -> Constellation.BEIDOU
        id in 33..64 || id in 120..158 -> Constellation.SBAS
        else -> Constellation.GPS
    }

    private fun acceptGsv(s: NmeaSentence.Gsv, now: Long) {
        for (v in s.satellites) {
            val c = Constellation.fromTalker(s.talker, v.svid)
            val key = c to v.svid
            val old = sats[key]?.second
            val sig = Nmea.signalName(c, s.signalId)
            val signals = (old?.signals ?: emptyMap()) + (sig to v.cn0)
            val usedSet = usedBy[if (c == Constellation.SBAS) Constellation.GPS else c]?.second ?: emptySet()
            sats[key] = now to SatInfo(c, v.svid, v.elevationDeg ?: old?.elevationDeg, v.azimuthDeg ?: old?.azimuthDeg,
                signals, used = v.svid in usedSet, dualFrequency = (old?.dualFrequency == true) || (v.cn0 != null && Nmea.isSecondFrequency(c, s.signalId)))
        }
    }

    /** Satellites heard in the last [maxAgeMs], sorted by constellation then number. */
    @Synchronized fun satellites(maxAgeMs: Long = 4000): List<SatInfo> {
        val now = clock()
        sats.entries.removeAll { now - it.value.first > maxAgeMs }
        return sats.values.map { (_, s) ->
            val usedSet = usedBy[if (s.constellation == Constellation.SBAS) Constellation.GPS else s.constellation]
                ?.takeIf { now - it.first <= maxAgeMs }?.second ?: emptySet()
            s.copy(used = s.svid in usedSet)
        }.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid }))
    }

    private fun sameEpoch(a: UtcTime?, b: UtcTime?) = a != null && b != null && kotlin.math.abs(a.secondsOfDay - b.secondsOfDay) < 0.01

    /**
     * u-blox sends RMC and VTG before GGA but GNS and GST after it, so for those the latest sentence within
     * 2 s is used (one epoch late at 1 Hz; accuracy and modes change slowly).
     */
    private fun recent(a: UtcTime?, b: UtcTime?): Boolean {
        if (a == null || b == null) return false
        val d = kotlin.math.abs(a.secondsOfDay - b.secondsOfDay)
        return d < 2.0 || d > 86398.0
    }

    private fun fromGga(g: NmeaSentence.Gga, now: Long): GnssFix? {
        val lat = g.latitude ?: return null
        val lon = g.longitude ?: return null
        val r = rmc?.takeIf { sameEpoch(it.time, g.time) || it.time == null } ?: rmc?.takeIf { recent(it.time, g.time) }
        val st = gst?.takeIf { recent(it.time, g.time) }
        val gn = gns?.takeIf { recent(it.time, g.time) }
        val speed = r?.speedKnots ?: vtg?.speedKnots
        return GnssFix(now, g.time, r?.date, lat, lon, g.altitudeMsl, g.geoidSeparation, g.quality, g.satellitesUsed,
            g.hdop ?: dops.second, dops.third, dops.first, speed?.let { it * 0.514444 }, r?.courseDeg ?: vtg?.courseTrue,
            g.diffAgeSec, g.diffStation, st?.sdLatitude, st?.sdLongitude, st?.sdAltitude, gn?.modes)
    }

    private fun fromRmc(r: NmeaSentence.Rmc, now: Long): GnssFix? {
        if (!r.valid) return null
        return GnssFix(now, r.time, r.date, r.latitude!!, r.longitude!!, null, null, FixQuality.fromMode(r.mode), null,
            dops.second, dops.third, dops.first, r.speedKnots?.let { it * 0.514444 }, r.courseDeg, null, null, null, null, null, null)
    }
}
