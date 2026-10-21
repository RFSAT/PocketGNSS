package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Grid
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/** A boundary vertex: latitude / longitude (degrees) and ellipsoidal height (m, NaN when unknown). */
data class Vertex(val latitude: Double, val longitude: Double, val height: Double = Double.NaN)

/**
 * A field (parcel) boundary: one closed ring, stored without repeating the first vertex. [quality] is the worst fix
 * type used for its vertices (NONE for imported boundaries).
 */
data class Field(
    val name: String,
    val vertices: List<Vertex>,
    val timeMs: Long,
    val quality: FixQuality = FixQuality.NONE,
    val note: String = "",
) {
    val geometry: FieldGeometry by lazy { FieldGeometry.of(vertices) }
    val key: String get() = "$name@$timeMs"
}

/** Area (m²), perimeter (m), centroid and validity of a boundary. */
data class FieldGeometry(val area: Double, val perimeter: Double, val centroidLat: Double, val centroidLon: Double, val selfIntersecting: Boolean) {
    val hectares: Double get() = area / 10_000.0

    companion object {
        /**
         * Computed in a Transverse Mercator projection centred on the field (scale 1 on its central meridian): for a
         * field a few kilometres across, area and lengths are within 1e-6 of the ellipsoid's.
         */
        fun of(v: List<Vertex>): FieldGeometry {
            if (v.isEmpty()) return FieldGeometry(0.0, 0.0, Double.NaN, Double.NaN, false)
            val lon0 = v.sumOf { it.longitude } / v.size
            val xy = v.map { Grid.transverseMercator(it.latitude, it.longitude, lon0, 1.0, 0.0, 0.0) }
            val n = xy.size
            var a2 = 0.0; var cx = 0.0; var cy = 0.0; var per = 0.0
            for (i in 0 until n) {
                val (x1, y1) = xy[i]; val (x2, y2) = xy[(i + 1) % n]
                val c = x1 * y2 - x2 * y1
                a2 += c; cx += (x1 + x2) * c; cy += (y1 + y2) * c
                if (n > 1 && (i < n - 1 || n > 2)) per += hypot(x2 - x1, y2 - y1)
            }
            val area = abs(a2) / 2
            val (clat, clon) = if (abs(a2) > 1e-9) {
                val gx = cx / (3 * a2); val gy = cy / (3 * a2)
                inverseLocal(gx, gy, lon0, v)
            } else v.sumOf { it.latitude } / n to v.sumOf { it.longitude } / n
            return FieldGeometry(if (n >= 3) area else 0.0, per, clat, clon, n in 4..3000 && selfIntersects(xy))
        }

        /** Local TM back to latitude / longitude, by interpolation over the vertices' own coordinates (sub-millimetre for fields). */
        private fun inverseLocal(x: Double, y: Double, lon0: Double, v: List<Vertex>): Pair<Double, Double> {
            var lat = v.sumOf { it.latitude } / v.size; var lon = lon0
            repeat(4) {
                val (px, py) = Grid.transverseMercator(lat, lon, lon0, 1.0, 0.0, 0.0)
                val (qx, _) = Grid.transverseMercator(lat, lon + 1e-5, lon0, 1.0, 0.0, 0.0)
                val (_, ry) = Grid.transverseMercator(lat + 1e-5, lon, lon0, 1.0, 0.0, 0.0)
                lon += (x - px) / ((qx - px) / 1e-5); lat += (y - py) / ((ry - py) / 1e-5)
            }
            return lat to lon
        }

        /** True when two non-adjacent edges of the closed ring cross. */
        fun selfIntersects(p: List<Pair<Double, Double>>): Boolean {
            val n = p.size
            fun orient(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Int {
                val v = (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
                return if (v > 1e-9) 1 else if (v < -1e-9) -1 else 0
            }
            for (i in 0 until n) {
                val a = p[i]; val b = p[(i + 1) % n]
                for (j in i + 2 until n) {
                    if (i == 0 && j == n - 1) continue
                    val c = p[j]; val d = p[(j + 1) % n]
                    val o1 = orient(a, b, c); val o2 = orient(a, b, d); val o3 = orient(c, d, a); val o4 = orient(c, d, b)
                    if (o1 != o2 && o3 != o4 && o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0) return true
                }
            }
            return false
        }
    }
}

/** Ramer–Douglas–Peucker simplification in metres (keeps the first vertex; for walked boundaries). */
fun simplify(v: List<Vertex>, toleranceM: Double): List<Vertex> {
    if (v.size < 4 || toleranceM <= 0) return v
    val lon0 = v.sumOf { it.longitude } / v.size
    val xy = v.map { Grid.transverseMercator(it.latitude, it.longitude, lon0, 1.0, 0.0, 0.0) }
    val keep = BooleanArray(v.size); keep[0] = true; keep[v.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to v.size - 1) }
    while (stack.isNotEmpty()) {
        val (s, e) = stack.removeLast()
        val (x1, y1) = xy[s]; val (x2, y2) = xy[e]
        val len = hypot(x2 - x1, y2 - y1)
        var worst = -1; var dmax = 0.0
        for (k in s + 1 until e) {
            val (x, y) = xy[k]
            val d = if (len < 1e-9) hypot(x - x1, y - y1) else abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / len
            if (d > dmax) { dmax = d; worst = k }
        }
        if (worst >= 0 && dmax > toleranceM) { keep[worst] = true; stack.add(s to worst); stack.add(worst to e) }
    }
    return v.filterIndexed { i, _ -> keep[i] }
}

/** Fields file in the app's storage: one line per field, vertices as "lat lon h" separated by '|'. */
class FieldStore(private val file: File) {
    @Synchronized fun load(): List<Field> {
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { l ->
            val f = l.split(';')
            if (f.size < 5) null else try {
                val verts = f[4].split('|').filter { it.isNotBlank() }.map { t ->
                    val c = t.trim().split(' ')
                    Vertex(c[0].toDouble(), c[1].toDouble(), c.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN)
                }
                Field(f[0], verts, f[1].toLong(), FixQuality.entries.firstOrNull { it.name == f[2] } ?: FixQuality.NONE, f[3])
            } catch (_: Exception) { null }
        }
    }

    @Synchronized fun save(list: List<Field>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("name;time_ms;quality;note;vertices\n")
            for (fl in list) w.write(listOf(clean(fl.name), fl.timeMs, fl.quality.name, clean(fl.note), fl.vertices.joinToString("|") {
                "%.10f %.10f %s".format(Locale.US, it.latitude, it.longitude, if (it.height.isNaN()) "" else "%.3f".format(Locale.US, it.height)).trim()
            }).joinToString(";") + "\n")
        }
    }

    fun add(f: Field) = save(load() + f)
    fun addAll(fs: List<Field>) = save(load() + fs)
    fun remove(f: Field) = save(load().filterNot { it.key == f.key })
    fun rename(f: Field, name: String) = save(load().map { if (it.key == f.key) it.copy(name = name) else it })

    private fun clean(s: String) = s.replace(';', ',').replace('|', '/').replace('\n', ' ')
}
