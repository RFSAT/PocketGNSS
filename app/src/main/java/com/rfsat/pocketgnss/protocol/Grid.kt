package com.rfsat.pocketgnss.protocol

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Plane (grid) coordinates: easting and northing in metres, with a label such as "ETRS89 / UTM 34N". */
data class GridPoint(val easting: Double, val northing: Double, val label: String)

/** Where a grid is meant to be used (degrees); outside it the app still computes but warns. */
data class GridArea(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double) {
    fun contains(lat: Double, lon: Double) = lat in latMin..latMax && lon in lonMin..lonMax
}

/**
 * Grid systems the app can show and export. European Union first: ETRS89 / UTM (the INSPIRE / EU-wide default and the
 * national grid in Germany, Spain, Italy, Denmark, Austria, Czechia, Norway...), ETRS89-LAEA (EU statistics, INSPIRE),
 * and the ETRS89-based national grids that are not plain UTM. Outside Europe, UTM on WGS 84 covers the world.
 *
 * Every grid here is a pure projection of ETRS89 (GRS80), so it is exactly as accurate as the position: centimetres
 * with RTK from a European network (whose corrections are in ETRS89). The exception is [GR_EGSA87], on the older
 * GGRS87 datum, reached by a 3-parameter shift that is only good to about 1 m ([metreLevel]).
 */
enum class GridSystem(val label: String, val epsg: Int?, val area: GridArea?, val metreLevel: Boolean = false) {
    NONE("", null, null),
    UTM("UTM", null, GridArea(-80.0, 84.0, -180.0, 180.0)),
    LAEA_EU("ETRS89-LAEA Europe", 3035, GridArea(27.0, 72.0, -32.0, 45.0)),
    BE_LAMBERT2008("Belgian Lambert 2008", 3812, GridArea(49.4, 51.6, 2.4, 6.5)),
    EE_LEST97("L-EST97", 3301, GridArea(57.5, 60.0, 21.7, 28.3)),
    FI_TM35FIN("ETRS-TM35FIN", 3067, GridArea(59.3, 70.2, 19.0, 31.6)),
    FR_LAMBERT93("RGF93 / Lambert-93", 2154, GridArea(41.2, 51.6, -5.3, 9.7)),
    GR_TM07("HTRS07 / TM07", null, GridArea(34.7, 41.8, 19.3, 30.0)),
    GR_EGSA87("GGRS87 / EGSA87", 2100, GridArea(34.7, 41.8, 19.3, 30.0), metreLevel = true),
    HR_HTRS96TM("HTRS96 / TM", 3765, GridArea(42.3, 46.6, 13.0, 19.5)),
    IE_ITM("Irish Transverse Mercator", 2157, GridArea(51.3, 55.5, -10.7, -5.3)),
    LT_LKS94("LKS94", 3346, GridArea(53.8, 56.5, 20.8, 26.9)),
    LV_LKS92("LKS-92", 3059, GridArea(55.6, 58.1, 20.8, 28.3)),
    PL_CS92("PL-1992", 2180, GridArea(49.0, 55.0, 14.1, 24.2)),
    PT_TM06("PT-TM06", 3763, GridArea(36.9, 42.2, -9.6, -6.1)),
    SE_SWEREF99TM("SWEREF 99 TM", 3006, GridArea(55.2, 69.1, 10.9, 24.2)),
    SI_D96TM("D96/TM", 3794, GridArea(45.4, 46.9, 13.3, 16.7)),
}

/**
 * Map projections, all on GRS80 (WGS 84 differs by 0.1 mm): Transverse Mercator by the Krüger series to 6th order
 * (Karney 2011; sub-millimetre within a zone), Lambert Conformal Conic (2 standard parallels) and Lambert Azimuthal
 * Equal Area (ellipsoidal, EPSG method 9820). Each grid is checked against PROJ in the unit tests.
 */
object Grid {
    private const val A = 6378137.0
    private const val GRS80_F = 1 / 298.257222101

    /** Rough extent of ETRS89 use (EUREF area): UTM there is labelled ETRS89, elsewhere WGS 84. */
    private val europe = GridArea(27.0, 84.0, -32.0, 45.0)

    /** Transverse Mercator (Krüger series, Karney 2011 coefficients to n^6), with a latitude of origin [lat0Deg]. */
    fun transverseMercator(latDeg: Double, lonDeg: Double, lon0Deg: Double, k0: Double, falseE: Double, falseN: Double,
                           a: Double = A, f: Double = GRS80_F, lat0Deg: Double = 0.0): Pair<Double, Double> {
        val (x, y) = tmUnit(latDeg, lonDeg - lon0Deg, a, f)
        val y0 = if (lat0Deg == 0.0) 0.0 else tmUnit(lat0Deg, 0.0, a, f).second
        return Pair(falseE + k0 * x, falseN + k0 * (y - y0))
    }

    /** Unscaled TM coordinates (easting, northing from the equator) for a longitude offset [dLonDeg]. */
    private fun tmUnit(latDeg: Double, dLonDeg: Double, a: Double, f: Double): Pair<Double, Double> {
        val n = f / (2 - f)
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        val aHat = a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
        val alpha = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
            61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
            49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
            34729 * n5 / 80640 - 3418889 * n6 / 1995840,
            212378941 * n6 / 319334400,
        )
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(dLonDeg)
        val e = sqrt(f * (2 - f))
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = kotlin.math.atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1 + t * t))
        var xi = xiP; var eta = etaP
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return Pair(aHat * eta, aHat * xi)
    }

    /** Lambert Conformal Conic, two standard parallels (EPSG method 9802); [lat0Deg] is the latitude of the false origin. */
    fun lambertConic(latDeg: Double, lonDeg: Double, lat0Deg: Double, lon0Deg: Double, lat1Deg: Double, lat2Deg: Double,
                     falseE: Double, falseN: Double, a: Double = A, f: Double = GRS80_F): Pair<Double, Double> {
        val e = sqrt(f * (2 - f))
        fun m(p: Double) = cos(p) / sqrt(1 - e * e * sin(p) * sin(p))
        fun t(p: Double) = tan(Math.PI / 4 - p / 2) / ((1 - e * sin(p)) / (1 + e * sin(p))).pow(e / 2)
        val p1 = Math.toRadians(lat1Deg); val p2 = Math.toRadians(lat2Deg)
        val nn = (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
        val ff = m(p1) / (nn * t(p1).pow(nn))
        val rho = a * ff * t(Math.toRadians(latDeg)).pow(nn)
        val rho0 = a * ff * t(Math.toRadians(lat0Deg)).pow(nn)
        val theta = nn * Math.toRadians(lonDeg - lon0Deg)
        return Pair(falseE + rho * sin(theta), falseN + rho0 - rho * cos(theta))
    }

    /** Lambert Azimuthal Equal Area on the ellipsoid (EPSG method 9820). */
    fun lambertAzimuthal(latDeg: Double, lonDeg: Double, lat0Deg: Double, lon0Deg: Double, falseE: Double, falseN: Double,
                         a: Double = A, f: Double = GRS80_F): Pair<Double, Double> {
        val e2 = f * (2 - f); val e = sqrt(e2)
        fun q(p: Double): Double { val s = sin(p); return (1 - e2) * (s / (1 - e2 * s * s) - 1 / (2 * e) * ln((1 - e * s) / (1 + e * s))) }
        val qp = q(Math.PI / 2)
        val p0 = Math.toRadians(lat0Deg)
        val b0 = asin(q(p0) / qp)
        val b = asin(q(Math.toRadians(latDeg)) / qp)
        val rq = a * sqrt(qp / 2)
        val d = a * (cos(p0) / sqrt(1 - e2 * sin(p0) * sin(p0))) / (rq * cos(b0))
        val dl = Math.toRadians(lonDeg - lon0Deg)
        val bb = rq * sqrt(2 / (1 + sin(b0) * sin(b) + cos(b0) * cos(b) * cos(dl)))
        return Pair(falseE + bb * d * cos(b) * sin(dl), falseN + bb / d * (cos(b0) * sin(b) - sin(b0) * cos(b) * cos(dl)))
    }

    fun utmZone(lonDeg: Double): Int = (floor((lonDeg + 180) / 6).toInt() % 60) + 1

    /** UTM in the zone of the point, or a fixed [zone] (1..60; national practice often extends one zone). */
    fun utm(latDeg: Double, lonDeg: Double, zone: Int = utmZone(lonDeg)): GridPoint {
        val lon0 = zone * 6 - 183.0
        val (e, n) = transverseMercator(latDeg, lonDeg, lon0, 0.9996, 500000.0, if (latDeg < 0) 10_000_000.0 else 0.0)
        val datum = if (europe.contains(latDeg, lonDeg)) "ETRS89" else "WGS 84"
        return GridPoint(e, n, "$datum / UTM $zone${if (latDeg < 0) "S" else "N"}")
    }

    /** ETRS89 / WGS-84 latitude, longitude, ellipsoidal height to the Greek Grid (EGSA87, EPSG:2100), ~1 m datum accuracy. */
    fun egsa87(latDeg: Double, lonDeg: Double, h: Double = 0.0): GridPoint {
        val x = Geo.geodeticToEcef(latDeg, lonDeg, h)
        // GGRS87 = WGS84 - (dX, dY, dZ), with (dX, dY, dZ) = (-199.87, 74.79, 246.62) the EPSG:2100 GGRS87 -> WGS84 shift
        val g = doubleArrayOf(x[0] + 199.87, x[1] - 74.79, x[2] - 246.62)
        val (la, lo) = ecefToLatLon(g, A, GRS80_F)
        val (e, n) = transverseMercator(la, lo, 24.0, 0.9996, 500000.0, 0.0)
        return GridPoint(e, n, GridSystem.GR_EGSA87.label)
    }

    /**
     * Grid coordinates of a point in [system]; null for [GridSystem.NONE]. [utmZone] 0 = the point's own zone.
     * The input is the position as delivered (ETRS89 with European RTK corrections; ITRF / WGS 84 otherwise).
     */
    fun of(system: GridSystem, latDeg: Double, lonDeg: Double, h: Double?, utmZone: Int = 0): GridPoint? {
        fun g(p: Pair<Double, Double>) = GridPoint(p.first, p.second, system.label)
        return when (system) {
            GridSystem.NONE -> null
            GridSystem.UTM -> if (latDeg < -80 || latDeg > 84) null else utm(latDeg, lonDeg, if (utmZone in 1..60) utmZone else utmZone(lonDeg))
            GridSystem.LAEA_EU -> g(lambertAzimuthal(latDeg, lonDeg, 52.0, 10.0, 4321000.0, 3210000.0))
            GridSystem.BE_LAMBERT2008 -> g(lambertConic(latDeg, lonDeg, 50.797815, 4.359215833333333, 49.833333333333333, 51.166666666666667, 649328.0, 665262.0))
            GridSystem.EE_LEST97 -> g(lambertConic(latDeg, lonDeg, 57.517553930555556, 24.0, 59.333333333333333, 58.0, 500000.0, 6375000.0))
            GridSystem.FI_TM35FIN -> g(transverseMercator(latDeg, lonDeg, 27.0, 0.9996, 500000.0, 0.0))
            GridSystem.FR_LAMBERT93 -> g(lambertConic(latDeg, lonDeg, 46.5, 3.0, 49.0, 44.0, 700000.0, 6600000.0))
            GridSystem.GR_TM07 -> g(transverseMercator(latDeg, lonDeg, 24.0, 0.9996, 500000.0, -2000000.0))
            GridSystem.GR_EGSA87 -> egsa87(latDeg, lonDeg, h ?: 0.0)
            GridSystem.HR_HTRS96TM -> g(transverseMercator(latDeg, lonDeg, 16.5, 0.9999, 500000.0, 0.0))
            GridSystem.IE_ITM -> g(transverseMercator(latDeg, lonDeg, -8.0, 0.99982, 600000.0, 750000.0, lat0Deg = 53.5))
            GridSystem.LT_LKS94 -> g(transverseMercator(latDeg, lonDeg, 24.0, 0.9998, 500000.0, 0.0))
            GridSystem.LV_LKS92 -> g(transverseMercator(latDeg, lonDeg, 24.0, 0.9996, 500000.0, -6000000.0))
            GridSystem.PL_CS92 -> g(transverseMercator(latDeg, lonDeg, 19.0, 0.9993, 500000.0, -5300000.0))
            GridSystem.PT_TM06 -> g(transverseMercator(latDeg, lonDeg, -8.133108333333333, 1.0, 0.0, 0.0, lat0Deg = 39.668258333333333))
            GridSystem.SE_SWEREF99TM -> g(transverseMercator(latDeg, lonDeg, 15.0, 0.9996, 500000.0, 0.0))
            GridSystem.SI_D96TM -> g(transverseMercator(latDeg, lonDeg, 15.0, 0.9999, 500000.0, -5000000.0))
        }
    }

    /** ECEF to latitude / longitude (degrees) on an ellipsoid (Bowring, iterated; sub-millimetre). */
    private fun ecefToLatLon(x: DoubleArray, a: Double, f: Double): Pair<Double, Double> {
        val e2 = f * (2 - f)
        val p = sqrt(x[0] * x[0] + x[1] * x[1])
        var lat = atan(x[2] / (p * (1 - e2)))
        repeat(6) {
            val nr = a / sqrt(1 - e2 * sin(lat) * sin(lat))
            val hh = p / cos(lat) - nr
            lat = atan(x[2] / (p * (1 - e2 * nr / (nr + hh))))
        }
        return Pair(Math.toDegrees(lat), Math.toDegrees(kotlin.math.atan2(x[1], x[0])))
    }
}
