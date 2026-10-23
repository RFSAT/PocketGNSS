package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.RtcmFrames
import com.rfsat.pocketgnss.raw.GnssLogReader
import com.rfsat.pocketgnss.raw.RawConvert
import com.rfsat.pocketgnss.raw.RawEpoch
import com.rfsat.pocketgnss.rtk.RoverArrays
import com.rfsat.pocketgnss.rtk.forEngine
import com.rfsat.pocketgnss.rtk.RtkNative
import com.rfsat.pocketgnss.rtk.RtkOptions
import com.rfsat.pocketgnss.rtk.RtkSolution
import com.rfsat.pocketgnss.rtk.RtkStatus
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Replays a phone raw session (gnss_log.txt + base.rtcm3, as Tools > Phone raw GNSS records it) through the phone RTK
 * engine, for analysing a phone's carrier phase. Skipped unless -PsessionDir=<folder> is given; writes
 * build/session/<variant>.csv (one row per epoch) and build/session/summary.txt.
 */
class SessionAnalysis {
    data class Variant(val name: String, val options: RtkOptions, val edit: (RawEpoch) -> RawEpoch = { it }, val engineMode: Int? = null,
                       val tune: Map<Int, Double> = emptyMap())

    private fun epochs(dir: File) = GnssLogReader(File(dir, "gnss_log.txt").bufferedReader()).use { r -> r.epochs().toList() }
    private inline fun <T> GnssLogReader.use(f: (GnssLogReader) -> T): T = try { f(this) } finally { close() }

    data class Row(val tow: Double, val status: RtkStatus, val lat: Double, val lon: Double, val h: Double, val sdH: Double, val ns: Int,
                   val ratio: Double, val phaseSignals: Int, val msg: String, val codeRms: Double = 0.0)

    fun run(v: Variant, epochs: List<RawEpoch>, frames: List<ByteArray>): List<Row> {
        val out = ArrayList<Row>()
        val arrays = RoverArrays(); val sol = DoubleArray(RtkSolution.SIZE)
        for (k in 0 until 9) RtkNative.tune(k, v.tune[k] ?: -1.0)
        if (v.engineMode != null) { val o = v.options; check(RtkNative.init(v.engineMode, o.navsys, o.elevationMaskDeg, o.snrMaskDbHz, o.arMode, 0, o.errorRatio,
            o.arRatio, o.maxBaseAgeSec, o.minFixSats, 1)) } else check(RtkNative.startFor(v.options, this))
        try {
            var fi = 0
            RtkNative.setTime(epochs[0].gpsWeek, epochs[0].towSeconds)
            for (e0 in epochs) {
                val e = v.edit(e0)
                val towMs = (e.towSeconds * 1000).toLong()
                while (fi < frames.size) {
                    var j = fi
                    while (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs) == null) j++
                    if (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs)!! > towMs + 50) break
                    while (fi <= minOf(j, frames.size - 1)) { RtkNative.inputRtcm(frames[fi], 0, frames[fi].size); fi++ }
                }
                arrays.fill(e.forEngine(v.options))
                val st = RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d,
                    arrays.snr, arrays.lli, sol)
                val s = RtkSolution.from(sol)
                val msg = RtkNative.message().lines().filter { it.isNotBlank() }.joinToString(" | ") { it.replace(Regex("^\\d\\d:\\d\\d:\\d\\d\\.\\d+: "), "") }
                out.add(Row(e.towSeconds, if (st == 0) RtkStatus.NONE else s.status, s.latitude, s.longitude, s.height, Math.hypot(s.stdEast, s.stdNorth),
                    s.satellites, s.ratio, e.obs.count { it.phase != null }, msg, s.codeRms))
            }
        } finally { if (v.engineMode != null) RtkNative.free() else RtkNative.freeFor(this); for (k in 0 until 9) RtkNative.tune(k, -1.0) }
        return out
    }

    /** Mean and RMS DD code residual per satellite over the session at trial positions (name -> lat, lon, h). */
    fun residuals(epochs: List<RawEpoch>, frames: List<ByteArray>, trials: Map<String, Triple<Double, Double, Double>>, f: Int): String {
        val sums = trials.keys.associateWith { HashMap<Int, DoubleArray>() }   // sat -> n, sum, sumsq, sum elev
        val arrays = RoverArrays(); val sol = DoubleArray(RtkSolution.SIZE)
        val o = RtkOptions()
        check(RtkNative.init(5, o.navsys, o.elevationMaskDeg, 0.0, 0, 0, o.errorRatio, o.arRatio, o.maxBaseAgeSec, o.minFixSats, 1))
        val sats = IntArray(80); val res = DoubleArray(80); val el = DoubleArray(80)
        val ecef = trials.mapValues { (_, t) -> Geo.geodeticToEcef(t.first, t.second, t.third) }
        try {
            var fi = 0
            RtkNative.setTime(epochs[0].gpsWeek, epochs[0].towSeconds)
            for (e in epochs) {
                val towMs = (e.towSeconds * 1000).toLong()
                while (fi < frames.size) {
                    var j = fi
                    while (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs) == null) j++
                    if (j < frames.size && RtcmFrames.epochTowMs(frames[j], towMs)!! > towMs + 50) break
                    while (fi <= minOf(j, frames.size - 1)) { RtkNative.inputRtcm(frames[fi], 0, frames[fi].size); fi++ }
                }
                arrays.fill(e)
                RtkNative.rover(e.gpsWeek, e.towSeconds, arrays.n, arrays.sys, arrays.prn, arrays.codes, arrays.p, arrays.l, arrays.d, arrays.snr, arrays.lli, sol)
                for ((name, rr) in ecef) {
                    val n = RtkNative.ddCode(rr, f, sats, res, el)
                    for (k in 0 until n) sums[name]!!.getOrPut(sats[k]) { DoubleArray(4) }.let { a -> a[0]++; a[1] += res[k]; a[2] += res[k] * res[k]; a[3] += el[k] }
                }
            }
        } finally { RtkNative.free() }
        val sb = StringBuilder()
        for ((name, m) in sums) {
            val all = m.values.fold(DoubleArray(3)) { acc, a -> acc[0] += a[0]; acc[1] += a[1]; acc[2] += a[2]; acc }
            sb.append("\n-- residuals at $name (slot $f): RMS %.2f m over %d · per satellite: mean (RMS) at elevation\n".format(Locale.US, Math.sqrt(all[2] / all[0]), all[0].toInt()))
            for ((sat, a) in m.toSortedMap()) if (a[0] > 30) sb.append("   sat %3d: %7.2f (%5.2f) el %4.1f n %d\n".format(Locale.US, sat, a[1] / a[0], Math.sqrt(a[2] / a[0]), a[3] / a[0], a[0].toInt()))
        }
        return sb.toString()
    }

    @Test fun analyse() {
        val path = System.getProperty("session.dir")
        assumeTrue("no session (-PsessionDir)", path != null && RtkNative.available)
        val dir = File(path!!)
        val all = epochs(dir)
        System.getProperty("session.dump")?.let { which -> File("build/session").mkdirs(); File("build/session/dump.txt").writeText(all.take(600).joinToString("\n") { e ->
            "%.1f ".format(Locale.US, e.towSeconds) + e.obs.filter { o -> which.split(',').any { o.sat == it } }.joinToString(" ") { o ->
                "${o.sat} ${o.code} P=${o.pseudorange?.let { "%.1f".format(Locale.US, it) }} L=${o.phase?.let { "%.1f".format(Locale.US, it) }} lli=${o.lli}" } }) }
        if (System.getProperty("session.rinex") != null) {
            val w = com.rfsat.pocketgnss.raw.RinexObsWriter(File("build/session/raw_test.obs"), com.rfsat.pocketgnss.raw.RinexHeaderInfo(markerName = "TEST",
                receiverNumber = "1", receiverType = "SM-S921B", receiverVersion = "16", antennaType = "INTERNAL", program = "PocketGNSS", runBy = "RFSAT", createdUtc = "20261022 000000 UTC"))
            all.forEach { w.add(it) }
            try { println("RINEX finish: " + w.finish(null) + " size " + File("build/session/raw_test.obs").length()) } catch (e: Exception) { println("RINEX finish failed: " + e); e.printStackTrace() }
        }
        if (System.getProperty("session.replay") != null) {
            val link = com.rfsat.pocketgnss.link.RawReplayLink(dir, RtkOptions(), 0.0)
            val model = com.rfsat.pocketgnss.core.GnssModel(); val buf = ByteArray(8192); val text = StringBuilder()
            try { while (true) { val n = link.read(buf); text.append(String(buf, 0, n, Charsets.US_ASCII)) } } catch (e: java.io.IOException) { println("REPLAY ended: ${e.message}") }
            println("REPLAY epochs read ${com.rfsat.pocketgnss.link.Replay.state.value.done} of ${com.rfsat.pocketgnss.link.Replay.state.value.total}; state ${com.rfsat.pocketgnss.link.PhoneRtkLink.state.value.epochs} epochs, msg ${com.rfsat.pocketgnss.link.PhoneRtkLink.state.value.message}, sol ${com.rfsat.pocketgnss.link.PhoneRtkLink.state.value.solution?.status}; text ${text.length}")
            link.close()
            val fixes = text.lines().mapNotNull { l -> com.rfsat.pocketgnss.protocol.Nmea.parse(l)?.let { model.accept(it) } }
            println("REPLAY fixes ${fixes.size}: " + fixes.groupingBy { it.quality }.eachCount() + " · reported accuracy median %.2f m".format(Locale.US,
                fixes.map { it.horizontalAccuracy }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }))
        }
        val frames = RtcmFrames.split(File(dir, "base.rtcm3").readBytes())
        val outDir = File("build/session").apply { mkdirs() }
        val summary = StringBuilder()
        frames.firstNotNullOfOrNull { com.rfsat.pocketgnss.protocol.Rtcm.baseStation(it) }?.let { b ->
            val g = Geo.ecefToGeodetic(b.x, b.y, b.z)
            summary.append("base station ${b.stationId}: %.9f %.9f h %.3f (antenna height ${b.antennaHeight})\n".format(Locale.US, g.latitude, g.longitude, g.height))
        }
        val firstMsm = frames.first { RtcmFrames.type(it) == 1074 }
        summary.append("first rover epoch tow %.3f, first GPS MSM epoch tow %.3f\n".format(Locale.US, all[0].towSeconds,
            RtcmFrames.epochTowMs(firstMsm, (all[0].towSeconds * 1000).toLong())!! / 1000.0))
        summary.append("epochs ${all.size}, frames ${frames.size}, types ${frames.groupingBy { RtcmFrames.type(it) }.eachCount().toSortedMap()}\n")

        // the phone's own flags: how often phase is valid, reset, slipped or half-cycle unresolved, per system and band
        val flags = HashMap<String, IntArray>()
        GnssLogReader(File(dir, "gnss_log.txt").bufferedReader()).use { r ->
            while (true) {
                val (_, ms) = r.nextRaw() ?: break
                for (m in ms) {
                    val sys = RawConvert.systemLetter(m.constellation) ?: continue
                    val band = if ((m.carrierHz ?: 1.57542e9) < 1.3e9) "L5" else "L1"
                    val a = flags.getOrPut("$sys $band") { IntArray(7) }
                    a[0]++
                    val s = m.adrState
                    if (s and 1 != 0) a[1]++                 // valid
                    if (s and 2 != 0) a[2]++                 // reset
                    if (s and 4 != 0) a[3]++                 // cycle slip
                    if (s and 16 != 0 && s and 8 == 0) a[4]++ // half cycle reported, not resolved
                    if (s and 8 != 0) a[5]++                 // half cycle resolved
                    if (m.receivedSvTimeUncertaintyNanos <= RawConvert.MAX_TIME_UNCERTAINTY_NS) a[6]++
                }
            }
            summary.append("phone fixes ${r.fixes.size}\n")
            if (r.fixes.isNotEmpty()) {
                val la = r.fixes.map { it.latitude }.sorted()[r.fixes.size / 2]; val lo = r.fixes.map { it.longitude }.sorted()[r.fixes.size / 2]
                val d = r.fixes.map { Geo.distance(la, lo, it.latitude, it.longitude) }.sorted()
                summary.append("phone own fix: median %.9f %.9f, distance from median 50%% %.2f m, 95%% %.2f m, max %.2f m, mean reported acc %.2f m\n".format(Locale.US,
                    la, lo, d[d.size / 2], d[(d.size * 95) / 100], d.last(), r.fixes.mapNotNull { it.accuracy }.average()))
                File(outDir, "phone_fixes.csv").writeText("t,lat,lon,acc\n" + r.fixes.joinToString("\n") { "${it.timeMs},${it.latitude},${it.longitude},${it.accuracy}" })
            }
        }
        summary.append("signal      n   valid  reset  slip  half-unres  half-res  code-ok\n")
        for ((k, a) in flags.toSortedMap()) summary.append("%-6s %6d %6.1f%% %5.1f%% %5.1f%% %8.1f%% %8.1f%% %7.1f%%\n".format(Locale.US, k, a[0],
            *(1..6).map { 100.0 * a[it] / a[0] }.toTypedArray()))

        val noHalf: (RawEpoch) -> RawEpoch = { e -> e.copy(obs = e.obs.map { it.copy(lli = it.lli and 1) }) }
        val variants = listOf(
            Variant("single", RtkOptions(), engineMode = 0),
            Variant("single_gps", RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false), engineMode = 0),
            Variant("single_gal", RtkOptions(gps = false, glonass = false, beidou = false, qzss = false), engineMode = 0),
            Variant("single_bds", RtkOptions(gps = false, galileo = false, glonass = false, qzss = false), engineMode = 0),
            Variant("single_glo", RtkOptions(gps = false, galileo = false, beidou = false, qzss = false), engineMode = 0),
            Variant("rtk_gps", RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false)),
            Variant("rtk_gal", RtkOptions(gps = false, glonass = false, beidou = false, qzss = false)),
            Variant("rtk_bds", RtkOptions(gps = false, galileo = false, glonass = false, qzss = false)),
            Variant("app", RtkOptions()),
            Variant("single_iflc", RtkOptions(), engineMode = 0, tune = mapOf(2 to 1.0)),
            Variant("w_l1_3000_l5_100", RtkOptions(), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("w_l1_1000_l5_100", RtkOptions(), tune = mapOf(6 to 1000.0, 7 to 100.0)),
            Variant("w_l1_3000_l5_300", RtkOptions(), tune = mapOf(6 to 3000.0, 7 to 300.0)),
            Variant("w_gps", RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("w_bds", RtkOptions(gps = false, galileo = false, glonass = false, qzss = false), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("w_gal", RtkOptions(gps = false, glonass = false, beidou = false, qzss = false), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("w_noglo", RtkOptions(glonass = false), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("w_noglo_static", RtkOptions(glonass = false, mode = com.rfsat.pocketgnss.rtk.RtkMode.STATIC), tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("noglo_nohalf", RtkOptions(glonass = false), noHalf),
            Variant("glo", RtkOptions(glonass = true, glonassPhase = true)),
            Variant("glo_nophase", RtkOptions(glonass = true, glonassPhase = true), { e -> e.copy(obs = e.obs.map { if (it.system == 'R') it.copy(phase = null) else it }) }),
            Variant("glo_nocode", RtkOptions(glonass = true, glonassPhase = true), { e -> e.copy(obs = e.obs.map { if (it.system == 'R') it.copy(pseudorange = null) else it }) }),
            Variant("glo_code5", RtkOptions(glonass = true, glonassPhase = true), tune = mapOf(8 to 5.0)),
            Variant("glo_code10", RtkOptions(glonass = true, glonassPhase = true), tune = mapOf(8 to 10.0)),
            Variant("glo_code30", RtkOptions(glonass = true, glonassPhase = true), tune = mapOf(8 to 30.0)),
            Variant("glo_code100", RtkOptions(glonass = true, glonassPhase = true), tune = mapOf(8 to 100.0)),
            Variant("noglo_noslip", RtkOptions(glonass = false), { e -> e.copy(obs = e.obs.map { it.copy(lli = it.lli and 2) }) }),
            Variant("noglo_noflags", RtkOptions(glonass = false), { e -> e.copy(obs = e.obs.map { it.copy(lli = 0) }) }),
            Variant("noglo_noflags_static", RtkOptions(glonass = false, mode = com.rfsat.pocketgnss.rtk.RtkMode.STATIC), { e -> e.copy(obs = e.obs.map { it.copy(lli = 0) }) }),
            Variant("noglo_l5w", RtkOptions(glonass = false), tune = mapOf(6 to 1000.0, 7 to 300.0)),
            Variant("noglo_l5w2", RtkOptions(glonass = false), tune = mapOf(6 to 600.0, 7 to 150.0)),
            Variant("noglo_er100", RtkOptions(glonass = false, errorRatio = 100.0)),
            Variant("noglo_er1000", RtkOptions(glonass = false, errorRatio = 1000.0)),
            Variant("noglo_el10", RtkOptions(glonass = false, elevationMaskDeg = 10.0)),
            Variant("noglo_el20", RtkOptions(glonass = false, elevationMaskDeg = 20.0)),
            Variant("noglo_snr25", RtkOptions(glonass = false, snrMaskDbHz = 25.0)),
            Variant("dgps", RtkOptions(), engineMode = 5),
            Variant("dgpsw", RtkOptions(), engineMode = 5, tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("dgpsw_gps", RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false), engineMode = 5, tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("dgpsw_bds", RtkOptions(gps = false, galileo = false, glonass = false, qzss = false), engineMode = 5, tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("dgpsw_gal", RtkOptions(gps = false, glonass = false, beidou = false, qzss = false), engineMode = 5, tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("dgpsw_noglo", RtkOptions(glonass = false), engineMode = 5, tune = mapOf(6 to 3000.0, 7 to 100.0)),
            Variant("dgps_gps", RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false), engineMode = 5),
            Variant("dgps_el30", RtkOptions(), engineMode = 5, tune = mapOf(5 to 30.0)),
            Variant("rtk_start_iflc", RtkOptions(), tune = mapOf(3 to 1.0)),
            Variant("rtk_code_inno_1000", RtkOptions(), tune = mapOf(0 to 1000.0)),
            Variant("rtk_start_iflc_inno_1000", RtkOptions(), tune = mapOf(3 to 1.0, 0 to 1000.0)),
            Variant("rtk_eratio100_start_iflc", RtkOptions(errorRatio = 100.0), tune = mapOf(3 to 1.0)),
        ) + listOf(-0.2, -0.1, -0.05, -0.02, 0.02, 0.05, 0.1, 0.2).map { d ->
            Variant("shift_gps_%+.2f".format(Locale.US, d), RtkOptions(galileo = false, glonass = false, beidou = false, qzss = false),
                { e -> e.copy(towSeconds = e.towSeconds + d) }, engineMode = 0)
        } + listOf(

            Variant("no_l5_phase", RtkOptions(), { e -> e.copy(obs = e.obs.map { if (it.band == com.rfsat.pocketgnss.raw.Band.L5) it.copy(phase = null, lli = 0) else it }) }),
            Variant("no_l5", RtkOptions(), { e -> e.copy(obs = e.obs.filter { it.band != com.rfsat.pocketgnss.raw.Band.L5 }) }),
            Variant("no_l1_phase", RtkOptions(), { e -> e.copy(obs = e.obs.map { if (it.band == com.rfsat.pocketgnss.raw.Band.L1) it.copy(phase = null, lli = 0) else it }) }),
            Variant("code_only_dgps", RtkOptions(), engineMode = 5),
            Variant("no_half_cycle_flag", RtkOptions(), noHalf),
            Variant("no_glonass", RtkOptions(glonass = false)),
            Variant("no_half_no_glo", RtkOptions(glonass = false), noHalf),
            Variant("eratio100_no_half", RtkOptions(errorRatio = 100.0), noHalf),
            Variant("snr25_no_half", RtkOptions(snrMaskDbHz = 25.0), noHalf),
            Variant("static_no_half", RtkOptions(mode = com.rfsat.pocketgnss.rtk.RtkMode.STATIC), noHalf),
        ).filter { v -> System.getProperty("session.variants")?.split(',')?.contains(v.name) ?: true }
        for (v in variants) {
            val rows = run(v, all, frames)
            File(outDir, "${v.name}.csv").writeText("tow,status,lat,lon,h,sdH,ns,ratio,phase,codeRms,msg\n" + rows.joinToString("\n") { r ->
                "%.3f,%s,%.9f,%.9f,%.3f,%.3f,%d,%.1f,%d,%.2f,\"%s\"".format(Locale.US, r.tow, r.status, r.lat, r.lon, r.h, r.sdH, r.ns, r.ratio, r.phaseSignals, r.codeRms, r.msg.replace("\"", "'")) })
            val counts = rows.groupingBy { it.status }.eachCount()
            val good = rows.filter { it.status == RtkStatus.FIX || it.status == RtkStatus.FLOAT || it.status == RtkStatus.DGPS }
            // scatter about the median of the last half (the phone was still)
            val tail = good.drop(good.size / 2)
            val la = tail.map { it.lat }.sorted().getOrNull(tail.size / 2) ?: 0.0; val lo = tail.map { it.lon }.sorted().getOrNull(tail.size / 2) ?: 0.0
            fun pct(rs: List<Row>, p: Int) = rs.map { Geo.distance(la, lo, it.lat, it.lon) }.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size - 1) * p / 100] }
            val msgs = rows.flatMap { it.msg.split(" | ") }.filter { it.isNotBlank() }.map { it.substringBefore('(').trim() }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(6).joinToString("; ") { "${it.key} ×${it.value}" }
            summary.append("\n== ${v.name}: ${counts.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }}\n")
            summary.append("   float/fix/dgps scatter about final median: 50%% %.2f m, 95%% %.2f m; float only 95%% %.2f m; mean reported sdH %.2f m; mean phase sats %.1f\n".format(Locale.US,
                pct(good, 50), pct(good, 95), pct(good.filter { it.status == RtkStatus.FLOAT || it.status == RtkStatus.FIX }, 95), good.map { it.sdH }.average(),
                rows.filter { it.status == RtkStatus.FLOAT || it.status == RtkStatus.FIX }.map { it.ns }.average()))
            summary.append("   median %.9f %.9f · messages: %s\n".format(Locale.US, la, lo, msgs))
        }
        System.getProperty("session.trials")?.let { t ->
            val trials = t.split(';').associate { x -> val p = x.split(':'); val v = p[1].split(',').map { it.toDouble() }; p[0] to Triple(v[0], v[1], v[2]) }
            for (f in (System.getProperty("session.slots") ?: "0").split(',').map { it.toInt() }) summary.append(residuals(all, frames, trials, f))
        }
        File(outDir, "summary.txt").writeText(summary.toString())
        println(summary)
    }
}
