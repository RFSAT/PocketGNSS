package com.rfsat.pocketgnss.protocol

/**
 * Terrestrial reference frames for precise point positioning.
 *
 * PPP with Galileo HAS or IGS real-time corrections gives coordinates in the global frame of the satellite orbits
 * (ITRF2020 / GTRF, at the epoch of observation). Europe sits on the Eurasian plate, which moves about 2.5 cm a year
 * against that frame, so by 2026 a PPP position differs from ETRS89 (the frame of European RTK networks, national grids
 * and land registries) by about 0.8–1 m. Inside the ETRF area PocketGNSS therefore converts PPP results to ETRF2000, the
 * EUREF-recommended realisation of ETRS89, with EPSG transformation 10586 "ITRF2020 to ETRF2000 (1)"
 * (14-parameter time-dependent Helmert, position-vector convention, reference epoch 2015.0).
 */
object Datum {
    private const val ARCSEC = Math.PI / 180.0 / 3600.0

    /** EPSG "ITRF2020 to ETRF2000 (1)": translations m, rotations arc seconds, scale ppm; rates per year. */
    private val T = doubleArrayOf(0.0538, 0.0518, -0.0822)
    private val R = doubleArrayOf(0.002106, 0.01274, -0.020592)
    private const val S = 0.00225
    private val DT = doubleArrayOf(0.0001, 0.0, -0.0017)
    private val DR = doubleArrayOf(0.000081, 0.00049, -0.000792)
    private const val DS = 0.00011
    private const val T0 = 2015.0

    /** EPSG area of use of the transformation (Europe, ETRF extent). */
    fun inEtrfArea(latDeg: Double, lonDeg: Double): Boolean = latDeg in 33.26..84.73 && lonDeg in -16.1..38.01

    /** ITRF2020 ECEF (m) at decimal-year [epoch] to ETRF2000 ECEF (m). */
    fun itrf2020ToEtrf2000(x: DoubleArray, epoch: Double): DoubleArray {
        val dt = epoch - T0
        val tx = T[0] + DT[0] * dt; val ty = T[1] + DT[1] * dt; val tz = T[2] + DT[2] * dt
        val rx = (R[0] + DR[0] * dt) * ARCSEC; val ry = (R[1] + DR[1] * dt) * ARCSEC; val rz = (R[2] + DR[2] * dt) * ARCSEC
        val d = (S + DS * dt) * 1e-6
        // position vector convention: X' = T + (1 + D) X + R X, R = [[0, -rz, ry], [rz, 0, -rx], [-ry, rx, 0]]
        return doubleArrayOf(
            tx + (1 + d) * x[0] - rz * x[1] + ry * x[2],
            ty + rz * x[0] + (1 + d) * x[1] - rx * x[2],
            tz - ry * x[0] + rx * x[1] + (1 + d) * x[2])
    }

    /** Geodetic (deg, deg, m) ITRF2020 at [epoch] to ETRF2000, on the same ellipsoid. */
    fun itrf2020ToEtrf2000(latDeg: Double, lonDeg: Double, h: Double, epoch: Double): Geodetic {
        val x = itrf2020ToEtrf2000(Geo.geodeticToEcef(latDeg, lonDeg, h), epoch)
        return Geo.ecefToGeodetic(x[0], x[1], x[2])
    }

    /** Decimal year of a GPS week and time of week (as PROJ coordinate epochs: year + day fraction / days in year). */
    fun decimalYear(week: Int, tow: Double): Double {
        val ms = week * 604_800_000L + (tow * 1000).toLong() + 315_964_800_000L - 18_000L
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        val y = c.get(java.util.Calendar.YEAR)
        val start = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, 0, 1) }.timeInMillis
        val end = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y + 1, 0, 1) }.timeInMillis
        return y + (ms - start).toDouble() / (end - start)
    }
}
