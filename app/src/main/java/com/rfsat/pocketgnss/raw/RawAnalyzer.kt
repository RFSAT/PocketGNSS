package com.rfsat.pocketgnss.raw


/** How well a phone's raw measurements suit precise positioning. */
enum class RawVerdict {
    /** Not enough data yet. */
    WAITING,
    /** The phone gives no raw measurements (or no full clock bias): Location only. */
    NO_RAW,
    /** Code and Doppler only: decimetre-to-metre DGNSS / SPP at best. */
    CODE_ONLY,
    /** Usable carrier phase on L1/E1: RTK float, fixed only on short baselines with long static sessions. */
    PHASE_L1,
    /** Usable carrier phase on L1 and L5/E5a: best case for phone RTK. */
    PHASE_DUAL,
}

/** Statistics of one system + band (e.g. GPS L5). */
data class BandStats(
    val system: Char,
    val band: Band,
    /** Satellites with this signal in the last epoch. */
    val satsNow: Int,
    /** Signal-epochs seen. */
    val samples: Long,
    /** Share with a usable pseudorange. */
    val codeOk: Double,
    /** Share with valid accumulated delta range (carrier phase). */
    val phaseOk: Double,
    /** Cycle slips / resets per satellite-minute of valid phase. */
    val slipsPerSatMin: Double,
    val meanCn0: Double,
)

data class RawReport(
    val epochs: Int,
    val seconds: Double,
    /** Median interval between epochs, s. */
    val interval: Double?,
    /** Share of epoch intervals longer than 1.5 x the median (duty cycling, gaps). */
    val gapShare: Double,
    val clockDiscontinuities: Int,
    val hasFullBias: Boolean,
    val bands: List<BandStats>,
    val verdict: RawVerdict,
    /** True when phase is reported but keeps resetting: typical of duty cycling (power saving) still on. */
    val dutyCyclingSuspected: Boolean,
)

/**
 * Accumulates raw GNSS epochs and judges whether the phone can do carrier-phase (RTK) positioning. Pure Kotlin,
 * unit-tested; the thresholds follow published phone RTK experience (usable ADR on most epochs and few resets).
 */
class RawAnalyzer {
    private class Acc { var samples = 0L; var code = 0L; var phase = 0L; var slips = 0L; var cn0 = 0.0; var now = 0; var lastEpoch = -1 }
    private val acc = LinkedHashMap<Pair<Char, Band>, Acc>()
    private val intervals = ArrayList<Double>()
    private var lastT: Long? = null
    private var firstT: Long? = null
    private var epochs = 0
    private var discont = 0
    private var lastDisc: Int? = null
    private var fullBias = false
    private var rawSeen = 0

    @Synchronized fun add(clock: RawClock, ms: List<RawMeasurement>) {
        rawSeen++
        if (clock.fullBiasNanos != null) fullBias = true
        lastDisc?.let { if (it != clock.hardwareClockDiscontinuityCount) discont++ }
        lastDisc = clock.hardwareClockDiscontinuityCount
        val t = clock.timeNanos
        lastT?.let { intervals.add((t - it) / 1e9) }
        if (firstT == null) firstT = t
        lastT = t
        epochs++
        for (a in acc.values) a.now = 0
        val seen = HashSet<Pair<Char, Int>>()
        for (m in ms) {
            val sys = RawConvert.systemLetter(m.constellation) ?: continue
            val key = sys to RawConvert.band(m.constellation, m.carrierHz)
            val a = acc.getOrPut(key) { Acc() }
            a.samples++; a.cn0 += m.cn0DbHz
            if (RawConvert.pseudorange(clock, m) != null) a.code++
            val adrOk = m.adrState and RawConvert.ADR_VALID != 0 && m.adrState and RawConvert.ADR_RESET == 0
            if (adrOk) a.phase++
            if (adrOk && m.adrState and RawConvert.ADR_CYCLE_SLIP != 0 || m.adrState and RawConvert.ADR_RESET != 0) a.slips++
            if (seen.add(sys to m.svid * 10 + key.second.ordinal)) a.now++
            a.lastEpoch = epochs
        }
    }

    @Synchronized fun report(): RawReport {
        val med = intervals.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val gaps = if (med == null || intervals.isEmpty()) 0.0 else intervals.count { it > 1.5 * med }.toDouble() / intervals.size
        val seconds = if (firstT != null && lastT != null) (lastT!! - firstT!!) / 1e9 else 0.0
        val bands = acc.entries.map { (k, a) ->
            val satMin = (a.phase * (med ?: 1.0)) / 60.0
            BandStats(k.first, k.second, if (a.lastEpoch == epochs) a.now else 0, a.samples,
                if (a.samples > 0) a.code.toDouble() / a.samples else 0.0,
                if (a.samples > 0) a.phase.toDouble() / a.samples else 0.0,
                if (satMin > 0) a.slips / satMin else if (a.slips > 0) 99.0 else 0.0,
                if (a.samples > 0) a.cn0 / a.samples else 0.0)
        }.sortedWith(compareBy({ "GRECJIS".indexOf(it.system) }, { it.band.ordinal }))
        fun good(b: BandStats) = b.phaseOk >= 0.7 && b.slipsPerSatMin < 2.0 && b.samples >= 20
        val l1 = bands.any { (it.system == 'G' || it.system == 'E') && it.band == Band.L1 && good(it) }
        val l5 = bands.any { (it.system == 'G' || it.system == 'E') && it.band == Band.L5 && good(it) }
        val anyCode = bands.any { it.codeOk >= 0.5 }
        val phaseReported = bands.any { it.phaseOk > 0.05 }
        val resetting = bands.any { it.phaseOk > 0.05 && it.slipsPerSatMin >= 2.0 }
        val verdict = when {
            rawSeen == 0 && epochs == 0 -> RawVerdict.WAITING
            !fullBias && epochs >= 5 -> RawVerdict.NO_RAW
            epochs < 20 -> RawVerdict.WAITING
            l1 && l5 -> RawVerdict.PHASE_DUAL
            l1 -> RawVerdict.PHASE_L1
            anyCode -> RawVerdict.CODE_ONLY
            else -> RawVerdict.NO_RAW
        }
        return RawReport(epochs, seconds, med, gaps, discont, fullBias, bands, verdict,
            dutyCyclingSuspected = (phaseReported && resetting) || gaps > 0.05)
    }
}
