package com.rfsat.pocketgnss.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A dBASE attribute column: C (text) or N (number with [decimals]). Names are at most 10 characters. */
data class DbfField(val name: String, val type: Char, val length: Int, val decimals: Int = 0)

/**
 * ESRI Shapefile writer (points and polygons) for GIS and farm-management software: .shp, .shx, .dbf (UTF-8,
 * declared in .cpg) and .prj, in geographic coordinates (longitude / latitude, degrees). Written from the
 * ESRI technical description (1998); checked by reading back in the unit tests.
 */
object Shapefile {
    private const val POINT = 1
    private const val POLYGON = 5

    /** ETRS89 and WGS 84 agree to under a metre; GIS software reads this as geographic WGS 84 (EPSG:4326). */
    const val PRJ_WGS84 = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
        "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    class Files(val shp: ByteArray, val shx: ByteArray, val dbf: ByteArray)

    /** Points as (longitude, latitude). */
    fun points(xy: List<Pair<Double, Double>>, fields: List<DbfField>, rows: List<List<Any?>>): Files {
        val contents = xy.map { (x, y) -> ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(POINT).putDouble(x).putDouble(y).array() }
        return write(POINT, contents, bbox(xy), fields, rows)
    }

    /** Polygons, one ring each as (longitude, latitude); rings are closed and turned clockwise (outer ring) here. */
    fun polygons(rings: List<List<Pair<Double, Double>>>, fields: List<DbfField>, rows: List<List<Any?>>): Files {
        val contents = rings.map { r0 ->
            var r = if (r0.isNotEmpty() && r0.first() != r0.last()) r0 + r0.first() else r0
            if (signedArea(r) > 0) r = r.reversed() // counter-clockwise -> clockwise
            val b = bbox(r)
            val buf = ByteBuffer.allocate(44 + 4 + 16 * r.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(POLYGON); b.forEach { buf.putDouble(it) }
            buf.putInt(1).putInt(r.size).putInt(0)
            r.forEach { (x, y) -> buf.putDouble(x).putDouble(y) }
            buf.array()
        }
        return write(POLYGON, contents, bbox(rings.flatten()), fields, rows)
    }

    /** The four or five files in one zip, as GIS software and farm portals expect them. */
    fun zip(baseName: String, f: Files, prj: String = PRJ_WGS84): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(ext: String, b: ByteArray) { z.putNextEntry(ZipEntry("$baseName.$ext")); z.write(b); z.closeEntry() }
            put("shp", f.shp); put("shx", f.shx); put("dbf", f.dbf); put("prj", prj.toByteArray()); put("cpg", "UTF-8".toByteArray())
        }
        return out.toByteArray()
    }

    private fun signedArea(r: List<Pair<Double, Double>>): Double {
        var s = 0.0
        for (i in 0 until r.size - 1) s += r[i].first * r[i + 1].second - r[i + 1].first * r[i].second
        return s / 2
    }

    private fun bbox(p: List<Pair<Double, Double>>): DoubleArray =
        if (p.isEmpty()) DoubleArray(4) else doubleArrayOf(p.minOf { it.first }, p.minOf { it.second }, p.maxOf { it.first }, p.maxOf { it.second })

    private fun write(type: Int, contents: List<ByteArray>, box: DoubleArray, fields: List<DbfField>, rows: List<List<Any?>>): Files {
        val shpLen = 100 + contents.sumOf { 8 + it.size }
        val shxLen = 100 + 8 * contents.size
        val shp = ByteBuffer.allocate(shpLen); val shx = ByteBuffer.allocate(shxLen)
        header(shp, shpLen, type, box); header(shx, shxLen, type, box)
        var offset = 100
        contents.forEachIndexed { i, c ->
            shp.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(c.size / 2); shp.put(c)
            shx.order(ByteOrder.BIG_ENDIAN).putInt(offset / 2).putInt(c.size / 2)
            offset += 8 + c.size
        }
        return Files(shp.array(), shx.array(), dbf(fields, rows))
    }

    private fun header(b: ByteBuffer, len: Int, type: Int, box: DoubleArray) {
        b.order(ByteOrder.BIG_ENDIAN).putInt(9994); repeat(5) { b.putInt(0) }; b.putInt(len / 2)
        b.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(type)
        box.forEach { b.putDouble(it) }; repeat(4) { b.putDouble(0.0) }
    }

    /** dBASE III table. */
    fun dbf(fields: List<DbfField>, rows: List<List<Any?>>): ByteArray {
        val recLen = 1 + fields.sumOf { it.length }
        val hdrLen = 32 + 32 * fields.size + 1
        val b = ByteBuffer.allocate(hdrLen + recLen * rows.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        val c = Calendar.getInstance()
        b.put(3).put((c.get(Calendar.YEAR) - 1900).toByte()).put((c.get(Calendar.MONTH) + 1).toByte()).put(c.get(Calendar.DAY_OF_MONTH).toByte())
        b.putInt(rows.size).putShort(hdrLen.toShort()).putShort(recLen.toShort()); repeat(20) { b.put(0) }
        for (f in fields) {
            val nm = f.name.take(10).toByteArray(Charsets.US_ASCII)
            b.put(nm); repeat(11 - nm.size) { b.put(0) }
            b.put(f.type.code.toByte()); repeat(4) { b.put(0) }
            b.put(f.length.toByte()).put(f.decimals.toByte()); repeat(14) { b.put(0) }
        }
        b.put(0x0D)
        for (r in rows) {
            b.put(' '.code.toByte())
            fields.forEachIndexed { i, f ->
                val v = r.getOrNull(i)
                val bytes = when {
                    v == null || (v is Double && v.isNaN()) -> ByteArray(f.length) { ' '.code.toByte() }
                    f.type == 'N' -> {
                        val s = when (v) { is Double -> "%.${f.decimals}f".format(Locale.US, v); else -> v.toString() }.take(f.length)
                        s.padStart(f.length).toByteArray(Charsets.US_ASCII)
                    }
                    else -> fit(v.toString(), f.length)
                }
                b.put(bytes)
            }
        }
        b.put(0x1A)
        return b.array()
    }

    /** UTF-8 text cut to [len] bytes without splitting a character, padded with spaces. */
    private fun fit(s: String, len: Int): ByteArray {
        var t = s
        while (t.toByteArray(Charsets.UTF_8).size > len) t = t.dropLast(1)
        val raw = t.toByteArray(Charsets.UTF_8)
        return raw + ByteArray(len - raw.size) { ' '.code.toByte() }
    }
}
