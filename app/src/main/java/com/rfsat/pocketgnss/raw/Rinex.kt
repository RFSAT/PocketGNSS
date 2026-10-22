package com.rfsat.pocketgnss.raw

import java.io.BufferedWriter
import java.io.File
import java.io.Writer
import java.util.Locale
import kotlin.math.floor

/** Header details for a RINEX observation file. */
data class RinexHeaderInfo(
    val markerName: String = "PHONE",
    val markerType: String = "NON_GEODETIC",
    val observer: String = "",
    val agency: String = "",
    val receiverNumber: String = "",
    val receiverType: String = "ANDROID",
    val receiverVersion: String = "",
    val antennaNumber: String = "",
    val antennaType: String = "",
    /** Approximate ECEF position, metres (0,0,0 when unknown). */
    val approxXyz: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    val antennaDeltaHen: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    val program: String = "PocketGNSS",
    val runBy: String = "",
    val createdUtc: String = "",
)

/**
 * RINEX 3.04 observation files (C, L, D, S per signal, GPS time). Because the observation types of the header are
 * known only at the end of a session, epochs are first written to a compact temporary file and the RINEX file is
 * rendered by [finish]. The formatting functions are pure and unit-tested.
 */
object Rinex {
    private const val TYPES = "CLDS"

    /** Header line: content padded to 60 columns, label in columns 61-80. */
    fun line(content: String, label: String): String = content.take(60).padEnd(60) + label.take(20).padEnd(20)

    /** Observation codes per system, in header order (e.g. 'G' -> [C1C, L1C, D1C, S1C, C5Q, ...]). */
    fun obsTypes(codes: Map<Char, Set<String>>): Map<Char, List<String>> = codes.toSortedMap(compareBy { "GRECJIS".indexOf(it) })
        .mapValues { (_, set) -> set.sortedWith(compareBy({ it[0] }, { it })).flatMap { c -> TYPES.map { t -> "$t$c" } } }

    /** Calendar date and time of GPS nanoseconds (no leap seconds: RINEX epochs are in GPS time). */
    fun calendar(gpsNanos: Long): IntArray {
        // 6 Jan 1980 is Julian day 2444245 (0h); days since then plus the civil-from-days algorithm.
        val secs = Math.floorDiv(gpsNanos, 1_000_000_000L)
        val days = Math.floorDiv(secs, 86400L)
        val sod = secs - days * 86400
        val z = days + 723_125L // civil-from-days epoch (0000-03-01): 719468 days to 1970-01-01, plus 3657 to 1980-01-06
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        return intArrayOf(y.toInt(), m.toInt(), d.toInt(), (sod / 3600).toInt(), ((sod % 3600) / 60).toInt(), (sod % 60).toInt())
    }

    private fun seconds(gpsNanos: Long): Double {
        val ns = Math.floorMod(gpsNanos, 60_000_000_000L)
        return ns / 1e9
    }

    fun header(info: RinexHeaderInfo, types: Map<Char, List<String>>, firstGpsNanos: Long, gloChannels: Map<Int, Int>): String {
        val sb = StringBuilder()
        fun add(content: String, label: String) { sb.append(line(content, label)).append('\n') }
        add("%9.2f%11s%-20s%-20s".format(Locale.US, 3.04, "", "OBSERVATION DATA", "M"), "RINEX VERSION / TYPE")
        add("%-20s%-20s%-20s".format(Locale.US, info.program.take(20), info.runBy.take(20), info.createdUtc.take(20)), "PGM / RUN BY / DATE")
        add("Android raw GNSS measurements (GnssMeasurement API)", "COMMENT")
        add(info.markerName, "MARKER NAME")
        add(info.markerType, "MARKER TYPE")
        add("%-20s%-40s".format(Locale.US, info.observer.take(20), info.agency.take(40)), "OBSERVER / AGENCY")
        add("%-20s%-20s%-20s".format(Locale.US, info.receiverNumber.take(20), info.receiverType.take(20), info.receiverVersion.take(20)), "REC # / TYPE / VERS")
        add("%-20s%-20s".format(Locale.US, info.antennaNumber.take(20), info.antennaType.take(20)), "ANT # / TYPE")
        add("%14.4f%14.4f%14.4f".format(Locale.US, info.approxXyz[0], info.approxXyz[1], info.approxXyz[2]), "APPROX POSITION XYZ")
        add("%14.4f%14.4f%14.4f".format(Locale.US, info.antennaDeltaHen[0], info.antennaDeltaHen[1], info.antennaDeltaHen[2]), "ANTENNA: DELTA H/E/N")
        for ((sys, list) in types) {
            list.chunked(13).forEachIndexed { i, chunk ->
                val head = if (i == 0) "%c  %3d".format(Locale.US, sys, list.size) else "      "
                add(head + chunk.joinToString("") { " %-3s".format(Locale.US, it) }, "SYS / # / OBS TYPES")
            }
        }
        add("%-60s".format(Locale.US, "DBHZ"), "SIGNAL STRENGTH UNIT")
        val t = calendar(firstGpsNanos)
        add("%6d%6d%6d%6d%6d%13.7f%5s%-3s".format(Locale.US, t[0], t[1], t[2], t[3], t[4], seconds(firstGpsNanos), "", "GPS"), "TIME OF FIRST OBS")
        if (types.containsKey('R')) {
            val slots = gloChannels.toSortedMap()
            if (slots.isEmpty()) add("  0", "GLONASS SLOT / FRQ #")
            slots.entries.chunked(8).forEachIndexed { i, chunk ->
                val head = if (i == 0) "%3d ".format(Locale.US, slots.size) else "    "
                add(head + chunk.joinToString("") { (s, k) -> "R%02d %2d ".format(Locale.US, s, k) }, "GLONASS SLOT / FRQ #")
            }
            add(" C1C    0.000 C1P    0.000 C2C    0.000 C2P    0.000", "GLONASS COD/PHS/BIS")
        }
        add("", "END OF HEADER")
        return sb.toString()
    }

    /** RINEX signal strength indicator 1-9 from C/N0 (dB-Hz). */
    fun ssi(cn0: Double): Int = (floor((cn0 - 12.0) / 6.0).toInt() + 2).coerceIn(1, 9)

    private fun value(v: Double?, lli: Int, ssi: Int): String =
        if (v == null || v.isNaN() || kotlin.math.abs(v) >= 1e10) " ".repeat(16)
        else "%14.3f%s%s".format(Locale.US, v, if (lli > 0) "$lli" else " ", if (ssi > 0) "$ssi" else " ")

    /** One epoch: the epoch line and one line per satellite, observations in [types] order. */
    fun epochRecord(e: RawEpoch, types: Map<Char, List<String>>): String {
        val bySat = e.obs.groupBy { it.sat }.toSortedMap(compareBy({ "GRECJIS".indexOf(it[0]) }, { it }))
            .filterKeys { types.containsKey(it[0]) }
        val t = calendar(e.gpsNanos)
        val sb = StringBuilder()
        sb.append(">%5d %02d %02d %02d %02d%11.7f  %d%3d\n".format(Locale.US, t[0], t[1], t[2], t[3], t[4], seconds(e.gpsNanos), 0, bySat.size))
        for ((sat, list) in bySat) {
            val order = types[sat[0]] ?: continue
            val byCode = list.associateBy { it.code }
            sb.append(sat)
            for (ot in order) {
                val o = byCode[ot.substring(1)]
                sb.append(when {
                    o == null -> " ".repeat(16)
                    ot[0] == 'C' -> value(o.pseudorange, 0, 0)
                    ot[0] == 'L' -> value(o.phase, o.lli, if (o.phase != null) ssi(o.cn0) else 0)
                    ot[0] == 'D' -> value(o.doppler, 0, 0)
                    else -> value(o.cn0, 0, 0)
                })
            }
            // Trailing blanks are allowed to be dropped.
            var end = sb.length
            while (end > 0 && sb[end - 1] == ' ') end--
            sb.setLength(end)
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---- compact temporary form: one line per signal, tab-separated ----

    private const val EPOCH_MARK = "#E"

    fun encodeEpoch(e: RawEpoch): String {
        val sb = StringBuilder()
        // "#E" marks an epoch: a plain "E" was also Galileo's system letter, so with Galileo every RINEX came out empty
        sb.append(EPOCH_MARK).append('\t').append(e.gpsWeek).append('\t').append("%.9f".format(Locale.US, e.towSeconds)).append('\t').append(if (e.clockReset) 1 else 0).append('\n')
        for (o in e.obs) {
            sb.append(o.system).append('\t').append(o.prn).append('\t').append(o.code).append('\t')
                .append(o.pseudorange?.let { "%.4f".format(Locale.US, it) } ?: "").append('\t')
                .append(o.phase?.let { "%.4f".format(Locale.US, it) } ?: "").append('\t')
                .append(o.doppler?.let { "%.4f".format(Locale.US, it) } ?: "").append('\t')
                .append("%.2f".format(Locale.US, o.cn0)).append('\t').append(o.lli).append('\t')
                .append(o.band.name).append('\t').append(o.gloChannel?.toString() ?: "").append('\n')
        }
        return sb.toString()
    }

    fun decodeEpochs(lines: Sequence<String>): Sequence<RawEpoch> = sequence {
        var head: List<String>? = null
        val obs = ArrayList<SignalObs>()
        suspend fun SequenceScope<RawEpoch>.flush() {
            val h = head ?: return
            yield(RawEpoch(h[1].toInt(), h[2].toDouble(), ArrayList(obs), h[3] == "1"))
            obs.clear()
        }
        for (l in lines) {
            if (l.isEmpty()) continue
            val f = l.split('\t')
            if (f[0] == EPOCH_MARK) { flush(); head = f; continue }
            if (f.size < 10) continue
            obs.add(SignalObs(f[0][0], f[1].toInt(), f[2], f[3].toDoubleOrNull(), f[4].toDoubleOrNull(), f[5].toDoubleOrNull(),
                f[6].toDouble(), f[7].toInt(), Band.valueOf(f[8]), f[9].toIntOrNull()))
        }
        flush()
    }
}

/**
 * Streams epochs of a session to a temporary file and renders the RINEX observation file at the end.
 */
class RinexObsWriter(private val out: File, private val info: RinexHeaderInfo) {
    private val tmp = File(out.parentFile, out.name + ".part")
    private val body: BufferedWriter = tmp.bufferedWriter()
    private val codes = HashMap<Char, MutableSet<String>>()
    private val glo = HashMap<Int, Int>()
    private var first: Long? = null
    var epochs = 0; private set

    @Synchronized fun add(e: RawEpoch) {
        if (e.obs.isEmpty()) return
        if (first == null) first = e.gpsNanos
        for (o in e.obs) {
            codes.getOrPut(o.system) { HashSet() }.add(o.code)
            if (o.system == 'R' && o.gloChannel != null) glo[o.prn] = o.gloChannel
        }
        body.write(Rinex.encodeEpoch(e)); epochs++
        if (epochs % 30 == 0) body.flush()
    }

    /** Writes the RINEX file; returns false when nothing was recorded. */
    @Synchronized fun finish(approxXyz: DoubleArray?): Boolean {
        try { body.close() } catch (_: Exception) {}
        val f = first
        if (f == null) { tmp.delete(); return false }
        val types = Rinex.obsTypes(codes)
        val hdr = Rinex.header(if (approxXyz != null) info.copy(approxXyz = approxXyz) else info, types, f, glo)
        out.bufferedWriter().use { w: Writer ->
            w.write(hdr)
            tmp.bufferedReader().useLines { lines -> for (e in Rinex.decodeEpochs(lines)) w.write(Rinex.epochRecord(e, types)) }
        }
        tmp.delete()
        return true
    }
}
