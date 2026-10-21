package com.rfsat.pocketgnss.raw

import java.io.BufferedReader

/**
 * Reads RINEX 3 observation files (as written by PocketGNSS and most receivers) back into [RawEpoch]s, for session
 * replay through the phone RTK engine. C, L, D and S observations per signal; LLI kept; epochs with an event flag
 * above 1 are skipped.
 */
class RinexReader(private val r: BufferedReader) {
    private val types = HashMap<Char, List<String>>()
    private val gloChannels = HashMap<Int, Int>()
    var version = 0.0; private set

    init { header() }

    private fun header() {
        var pendingSys: Char? = null; var pendingList = ArrayList<String>(); var pendingN = 0
        while (true) {
            val l = r.readLine() ?: break
            val label = if (l.length > 60) l.substring(60).trim() else ""
            when {
                label == "RINEX VERSION / TYPE" -> version = l.take(9).trim().toDoubleOrNull() ?: 0.0
                label == "SYS / # / OBS TYPES" -> {
                    if (l[0] != ' ') { pendingSys = l[0]; pendingN = l.substring(3, 6).trim().toInt(); pendingList = ArrayList() }
                    pendingList.addAll(l.substring(7, 60).trim().split(Regex("\\s+")).filter { it.isNotBlank() })
                    if (pendingList.size >= pendingN) pendingSys?.let { types[it] = pendingList.take(pendingN) }
                }
                label == "GLONASS SLOT / FRQ #" -> Regex("""R(\d\d)\s+(-?\d+)""").findAll(l.take(60)).forEach { m ->
                    gloChannels[m.groupValues[1].toInt()] = m.groupValues[2].toInt()
                }
                label == "END OF HEADER" -> return
            }
        }
    }

    /** The next epoch, or null at the end of the file. */
    fun next(): RawEpoch? {
        while (true) {
            val l = r.readLine() ?: return null
            if (!l.startsWith(">")) continue
            val y = l.substring(2, 6).trim().toInt(); val mo = l.substring(7, 9).trim().toInt(); val d = l.substring(10, 12).trim().toInt()
            val h = l.substring(13, 15).trim().toInt(); val mi = l.substring(16, 18).trim().toInt(); val sec = l.substring(18, 29).trim().toDouble()
            val flag = l.substring(31, 32).trim().toIntOrNull() ?: 0
            val n = l.substring(32, 35).trim().toInt()
            val lines = (0 until n).mapNotNull { r.readLine() }
            if (flag > 1) continue
            // Whole seconds as integers so the time of week keeps the file's precision (a double sum loses ~50 ns).
            val whole = daysSinceGpsEpoch(y, mo, d) * 86400L + h * 3600 + mi * 60
            val week = Math.floorDiv(whole, 604800L).toInt()
            val tow = (whole - week * 604800L) + sec
            val obs = ArrayList<SignalObs>()
            for (s in lines) {
                if (s.length < 3) continue
                val sys = s[0]; val prn = s.substring(1, 3).trim().toIntOrNull() ?: continue
                val list = types[sys] ?: continue
                class Acc { var c: Double? = null; var lph: Double? = null; var dop: Double? = null; var snr = 0.0; var lli = 0 }
                val byCode = LinkedHashMap<String, Acc>()
                list.forEachIndexed { i, t ->
                    val st = 3 + 16 * i
                    if (st >= s.length) return@forEachIndexed
                    val field = s.substring(st, minOf(st + 16, s.length))
                    val v = field.take(14).trim().toDoubleOrNull() ?: return@forEachIndexed
                    val a = byCode.getOrPut(t.substring(1)) { Acc() }
                    when (t[0]) {
                        'C' -> a.c = v
                        'L' -> { a.lph = v; a.lli = field.getOrNull(14)?.digitToIntOrNull() ?: 0 }
                        'D' -> a.dop = v
                        'S' -> a.snr = v
                    }
                }
                for ((code, a) in byCode) {
                    if (a.c == null && a.lph == null) continue
                    obs.add(SignalObs(sys, prn, code, a.c, a.lph, a.dop, a.snr, a.lli, band(sys, code[0]), if (sys == 'R') gloChannels[prn] else null))
                }
            }
            return RawEpoch(week, tow, obs, false)
        }
    }

    fun epochs(): Sequence<RawEpoch> = generateSequence { next() }

    fun close() = r.close()

    companion object {
        fun band(sys: Char, b: Char): Band = when {
            sys == 'R' && b == '1' -> Band.G1
            sys == 'R' && b == '2' -> Band.G2
            sys == 'C' && b == '2' -> Band.B1I
            b == '1' -> Band.L1
            b == '2' -> Band.L2
            b == '5' -> Band.L5
            b == '7' -> Band.E5B
            else -> Band.OTHER
        }

        /** Days from 6 Jan 1980 to a calendar date. */
        fun daysSinceGpsEpoch(y: Int, m: Int, d: Int): Long = daysFromCivil(y, m, d) - daysFromCivil(1980, 1, 6)

        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = (if (m <= 2) y0 - 1 else y0).toLong()
            val era = Math.floorDiv(y, 400L)
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }
    }
}
