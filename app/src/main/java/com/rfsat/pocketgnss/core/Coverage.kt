package com.rfsat.pocketgnss.core

import com.rfsat.pocketgnss.protocol.Geo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Worked-area map for guidance. The implement's swath (the path driven, [width] wide across the direction of travel)
 * is painted on square cells of [cell] metres in the local east / north plane at [originLat] / [originLon], kept in
 * tiles of [T] x [T] cells only where work was done. A cell is covered when its centre lies in the swath. Painted
 * again more than [samePassSec] after it was last painted, it counts once more: ground worked twice (an overlap).
 * Repainting within that time (the joins of one pass, the inside of a turn) does not count.
 *
 * With a field boundary ([setField]) the map also gives how much of the field is covered; the rest are the gaps.
 */
class CoverageMap(val originLat: Double, val originLon: Double, val cell: Double, private val samePassSec: Double = 8.0) {
    private class Tile { val count = ByteArray(T * T); val time = FloatArray(T * T) { Float.NEGATIVE_INFINITY } }
    private val tiles = HashMap<Long, Tile>()
    /** Tiles changed since the last [shapes]. */
    private val dirty = HashSet<Long>()

    /** Cells covered at least once, and at least twice. */
    var coveredCells = 0L; private set
    var overlapCells = 0L; private set
    /** Covered cells whose centre is inside the field boundary. */
    var coveredInField = 0L; private set
    /** Changes with every paint: map layers redraw when it changes. */
    var version = 0L; private set

    private var boundary: DoubleArray? = null
    private var timeBase: Double? = null     // boundary ring in local east, north (x0, y0, x1, y1, ...)
    var fieldArea = 0.0; private set

    val coveredArea: Double get() = coveredCells * cell * cell
    val overlapArea: Double get() = overlapCells * cell * cell
    /** Share of the field covered (0..1), or null without a field. */
    val fieldShare: Double?
        get() = if (boundary == null || fieldArea <= 0) null else (coveredInField * cell * cell / fieldArea).coerceAtMost(1.0)
    /** Field area not covered (m²), or null without a field. */
    val gapArea: Double?
        get() = if (boundary == null) null else (fieldArea - coveredInField * cell * cell).coerceAtLeast(0.0)

    private fun key(tx: Int, ty: Int) = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)

    /** East / north (m) of a position in this map's plane. */
    fun local(lat: Double, lon: Double): DoubleArray = Geo.enu(originLat, originLon, 0.0, lat, lon, 0.0)

    /** The field boundary (latitude, longitude pairs) for coverage share and gaps; null = none. Recounts the covered cells in it. */
    @Synchronized fun setField(ring: List<Pair<Double, Double>>?) {
        if (ring == null || ring.size < 3) { boundary = null; fieldArea = 0.0; coveredInField = 0; return }
        val f = DoubleArray(ring.size * 2)
        ring.forEachIndexed { i, (la, lo) -> val p = local(la, lo); f[2 * i] = p[0]; f[2 * i + 1] = p[1] }
        boundary = f
        var a = 0.0
        for (i in ring.indices) { val j = (i + 1) % ring.size; a += f[2 * i] * f[2 * j + 1] - f[2 * j] * f[2 * i + 1] }
        fieldArea = abs(a) / 2
        var n = 0L
        for ((k, t) in tiles) {
            val tx = (k shr 32).toInt(); val ty = k.toInt()
            for (i in 0 until T * T) if (t.count[i] > 0 && inField(tx * T + i % T, ty * T + i / T)) n++
        }
        coveredInField = n
    }

    private fun inField(cx: Int, cy: Int): Boolean {
        val f = boundary ?: return false
        val x = (cx + 0.5) * cell; val y = (cy + 0.5) * cell
        var inside = false; val n = f.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = f[2 * i]; val yi = f[2 * i + 1]; val xj = f[2 * j]; val yj = f[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Paints the swath of one step from (e0, n0) to (e1, n1) (m, this map's plane), [width] wide, at time [tSec] (s):
     * the cells whose centre lies in the rectangle swept by the implement, from the start of the step up to (not
     * including) its end, so that straight steps join exactly; on a turn the wedges between steps are a fraction of a
     * cell at the usual 5–20 positions a second.
     */
    @Synchronized fun paint(e0: Double, n0: Double, e1: Double, n1: Double, width: Double, tSec: Double) {
        val len = hypot(e1 - e0, n1 - n0)
        if (len < 1e-6) return
        val ue = (e1 - e0) / len; val un = (n1 - n0) / len
        val half = width / 2; val ext = cell
        // corners of the rectangle
        val xs = doubleArrayOf(e0 - ue * ext + un * half, e0 - ue * ext - un * half, e1 + ue * ext + un * half, e1 + ue * ext - un * half)
        val ys = doubleArrayOf(n0 - un * ext - ue * half, n0 - un * ext + ue * half, n1 + un * ext - ue * half, n1 + un * ext + ue * half)
        val cx0 = floor(xs.min() / cell).toInt(); val cx1 = ceil(xs.max() / cell).toInt()
        val cy0 = floor(ys.min() / cell).toInt(); val cy1 = ceil(ys.max() / cell).toInt()
        // cell times are kept as floats relative to the first paint, so they stay exact to milliseconds for days
        val t0 = timeBase ?: tSec.also { timeBase = it }
        val t = (tSec - t0).toFloat()
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val x = (cx + 0.5) * cell - e0; val y = (cy + 0.5) * cell - n0
            val along = x * ue + y * un; val across = x * un - y * ue
            if (along < 0 || along >= len || abs(across) > half) continue
            val tx = Math.floorDiv(cx, T); val ty = Math.floorDiv(cy, T)
            val k = key(tx, ty)
            val tile = tiles.getOrPut(k) { Tile() }
            val i = (cy - ty * T) * T + (cx - tx * T)
            val c = tile.count[i].toInt() and 0xff
            if (c == 0) {
                tile.count[i] = 1; coveredCells++
                if (boundary != null && inField(cx, cy)) coveredInField++
                dirty.add(k)
            } else if (t - tile.time[i] > samePassSec && c < 255) {
                tile.count[i] = (c + 1).toByte()
                if (c == 1) { overlapCells++; dirty.add(k) }
            }
            tile.time[i] = t
        }
        version++
    }

    /** Count of a cell (0 = not worked). */
    @Synchronized fun count(cx: Int, cy: Int): Int {
        val tx = Math.floorDiv(cx, T); val ty = Math.floorDiv(cy, T)
        val t = tiles[key(tx, ty)] ?: return 0
        return t.count[(cy - ty * T) * T + (cx - tx * T)].toInt() and 0xff
    }

    /**
     * The covered and the overlapping area as rectangles (each a ring of latitude, longitude), for the map. Cells are
     * merged along rows and down columns; when that would give more than [maxRects], blocks of 2 x 2, 4 x 4 ... cells
     * are drawn instead (a block counts when at least half its cells do).
     */
    @Synchronized fun shapes(maxRects: Int = 3000): CoverageShapes {
        dirty.clear()
        var f = 1
        while (true) {
            val cov = rects(f, 1); val ovl = rects(f, 2)
            if (cov.size + ovl.size <= maxRects || f >= 64) return CoverageShapes(version, cov.map { ring(it, f) }, ovl.map { ring(it, f) })
            f *= 2
        }
    }

    /** Rectangles (x0, y0, x1, y1 in blocks of [f] cells) of the blocks with at least half the cells counted [min] times or more. */
    private fun rects(f: Int, min: Int): List<IntArray> {
        // blocks with work, by row
        val rows = java.util.TreeMap<Int, java.util.TreeSet<Int>>()
        val need = (f * f + 1) / 2
        val blocks = HashMap<Long, Int>()
        for ((k, t) in tiles) {
            val tx = (k shr 32).toInt(); val ty = k.toInt()
            for (i in 0 until T * T) if ((t.count[i].toInt() and 0xff) >= min) {
                val cx = tx * T + i % T; val cy = ty * T + i / T
                val bk = key(Math.floorDiv(cx, f), Math.floorDiv(cy, f))
                blocks[bk] = (blocks[bk] ?: 0) + 1
            }
        }
        for ((bk, n) in blocks) if (n >= need) rows.getOrPut(bk.toInt()) { java.util.TreeSet() }.add((bk shr 32).toInt())
        // runs per row, merged with the identical run of the row below when there is one
        val open = HashMap<Long, IntArray>()   // (x0, x1) -> rect being extended
        val out = ArrayList<IntArray>()
        var lastRow = Int.MIN_VALUE
        for ((y, xs) in rows) {
            if (y != lastRow + 1) { out.addAll(open.values); open.clear() }
            val runs = ArrayList<IntArray>()
            var start = Int.MIN_VALUE; var prev = Int.MIN_VALUE
            for (x in xs) { if (x != prev + 1) { if (start != Int.MIN_VALUE) runs.add(intArrayOf(start, prev)); start = x }; prev = x }
            if (start != Int.MIN_VALUE) runs.add(intArrayOf(start, prev))
            val next = HashMap<Long, IntArray>()
            for (r in runs) {
                val k = key(r[0], r[1])
                val o = open.remove(k)
                if (o != null) { o[3] = y; next[k] = o } else next[k] = intArrayOf(r[0], y, r[1], y)
            }
            out.addAll(open.values); open.clear(); open.putAll(next)
            lastRow = y
        }
        out.addAll(open.values)
        return out
    }

    private fun ring(r: IntArray, f: Int): List<Pair<Double, Double>> {
        val s = f * cell
        val x0 = r[0] * s; val y0 = r[1] * s; val x1 = (r[2] + 1) * s; val y1 = (r[3] + 1) * s
        return listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1, x0 to y0).map { (e, n) -> Geo.fromEnu(originLat, originLon, e, n) }
    }

    @Synchronized fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC); o.writeDouble(originLat); o.writeDouble(originLon); o.writeDouble(cell); o.writeInt(tiles.size)
            for ((k, t) in tiles) { o.writeLong(k); o.write(t.count) }
        }
        tmp.renameTo(file)
    }

    companion object {
        const val T = 64
        private const val MAGIC = 0x50474356   // "PGCV"

        /** Cell size for an implement: an eighth of the working width, 0.25 to 2 m. */
        fun cellFor(width: Double): Double = (width / 8).coerceIn(0.25, 2.0)

        /** A saved map; times are not kept, so after a restart any repaint counts as an overlap (a later pass). */
        fun load(file: File): CoverageMap? = try {
            DataInputStream(file.inputStream().buffered()).use { i ->
                if (i.readInt() != MAGIC) return null
                val m = CoverageMap(i.readDouble(), i.readDouble(), i.readDouble())
                repeat(i.readInt()) {
                    val k = i.readLong(); val t = Tile(); i.readFully(t.count)
                    m.tiles[k] = t
                    for (b in t.count) { val c = b.toInt() and 0xff; if (c >= 1) m.coveredCells++; if (c >= 2) m.overlapCells++ }
                }
                m.version = 1; m
            }
        } catch (_: Exception) { null }
    }
}

/** Coverage for the map: rectangles (rings of latitude, longitude) covered once or more and twice or more. */
data class CoverageShapes(val version: Long, val covered: List<List<Pair<Double, Double>>>, val overlap: List<List<Pair<Double, Double>>>)
