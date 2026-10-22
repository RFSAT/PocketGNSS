package com.rfsat.pocketgnss.rtk

/**
 * The phone RTK engine in native code (src/main/cpp: RTKLIB demo5 + prtk.c). One engine per process, so every call
 * holds [lock], and the engine belongs to the link that started it ([owner]): a link being closed may neither use nor
 * free an engine that its successor has already started. (A link switch used to free the engine on the old link's
 * thread while the new one initialised it: a double free, seen as a native crash on a Galaxy S24.) [available] is
 * false when the library cannot be loaded (JVM unit tests, an ABI without it): the app then uses the phone's location.
 */
object RtkNative {
    val available: Boolean = try { System.loadLibrary("prtk"); true } catch (_: Throwable) { false }

    /** Held for every native call. */
    val lock = Any()
    /** The link the running engine belongs to, or null. Read and written under [lock]. */
    @Volatile var owner: Any? = null
        private set

    /** Starts the engine for [who]; a previous owner's engine is replaced. */
    fun startFor(o: RtkOptions, who: Any): Boolean = synchronized(lock) {
        val ok = start(o)
        owner = if (ok) who else null
        ok
    }

    /** Frees the engine if it still belongs to [who] (a newer link's engine is left alone). */
    fun freeFor(who: Any) = synchronized(lock) { if (owner === who) { free(); owner = null } }

    external fun init(mode: Int, navsys: Int, elmask: Double, snrmask: Double, armode: Int, gloAr: Int, eratio: Double,
                      thresar: Double, maxage: Double, minfixsats: Int, ionoFree: Int): Boolean
    /** PPP: 1 = ionosphere-free combination, 0 = broadcast ionosphere model; the filter restarts, orbits and corrections stay. */
    external fun setIono(ionoFree: Int)
    /** Restarts the position filter, keeping ephemerides, corrections and the base station. */
    external fun reset()
    /** Tuning knob (prtk.h PRTK_TUNE_*), applied at the next start; negative = default. */
    external fun tune(key: Int, value: Double)
    /** Analysis: DD code residuals (m) of the last epoch at trial position [rr] (ECEF) on slot [f]; returns the count. */
    external fun ddCode(rr: DoubleArray, f: Int, sats: IntArray, res: DoubleArray, elev: DoubleArray): Int
    external fun free()
    external fun setTime(week: Int, tow: Double)
    /** RTCM 3 from the caster; returns 1 = base epoch, 2 = ephemeris, 4 = station position (bit mask). */
    external fun inputRtcm(data: ByteArray, off: Int, len: Int): Int
    external fun inputRtcmNav(data: ByteArray, off: Int, len: Int): Int
    /** One rover epoch; [out] ([RtkSolution.SIZE] values) receives the solution, see [RtkSolution.from]. Returns the status. */
    external fun rover(week: Int, tow: Double, n: Int, sys: ByteArray, prn: IntArray, codes: ByteArray, p: DoubleArray, l: DoubleArray,
                       d: FloatArray, snr: FloatArray, lli: IntArray, out: DoubleArray): Int
    /** [out] ([RtkBase.SIZE] values): see [RtkBase.from]. */
    external fun base(out: DoubleArray)
    external fun message(): String

    /** Starts the engine with [o]. */
    fun start(o: RtkOptions): Boolean = init(o.engineMode, o.navsys, o.elevationMaskDeg, o.snrMaskDbHz, o.arMode, if (o.glonassAr) 1 else 0,
        o.errorRatio, o.arRatio, o.maxBaseAgeSec, o.minFixSats, if (o.ionoFree) 1 else 0)
}
