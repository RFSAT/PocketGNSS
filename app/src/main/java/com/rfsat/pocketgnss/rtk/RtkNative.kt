package com.rfsat.pocketgnss.rtk

/**
 * The phone RTK engine in native code (src/main/cpp: RTKLIB demo5 + prtk.c). One engine per process; callers
 * serialise access (PhoneRtkLink holds a lock). [available] is false when the library cannot be loaded (JVM unit
 * tests, an ABI without the library): the app then falls back to the phone's ordinary location.
 */
object RtkNative {
    val available: Boolean = try { System.loadLibrary("prtk"); true } catch (_: Throwable) { false }

    external fun init(mode: Int, navsys: Int, elmask: Double, snrmask: Double, armode: Int, gloAr: Int, eratio: Double,
                      thresar: Double, maxage: Double, minfixsats: Int, ionoFree: Int): Boolean
    /** PPP: 1 = ionosphere-free combination, 0 = broadcast ionosphere model; the filter restarts, orbits and corrections stay. */
    external fun setIono(ionoFree: Int)
    /** Restarts the position filter, keeping ephemerides, corrections and the base station. */
    external fun reset()
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
