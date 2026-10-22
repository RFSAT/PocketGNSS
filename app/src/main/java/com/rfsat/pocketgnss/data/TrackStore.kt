package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.protocol.FixQuality
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/**
 * The map trace, kept between app runs until the user clears it. Binary: "PGTR", version, then per point time (ms),
 * latitude, longitude (degrees, double), ellipsoid height (float, NaN = none) and GGA quality (byte): 29 bytes, so the
 * 7200 points the map holds take about 200 kB. Written to a temporary file and renamed, so a crash never leaves half
 * a trace; an unreadable file is ignored.
 */
class TrackStore(private val file: File) {
    fun load(): List<TrackPoint> {
        if (!file.isFile) return emptyList()
        return try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val magic = ByteArray(4).also { inp.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC || inp.readByte().toInt() != VERSION) return emptyList()
                val out = ArrayList<TrackPoint>()
                try {
                    while (true) {
                        val t = inp.readLong(); val la = inp.readDouble(); val lo = inp.readDouble()
                        val h = inp.readFloat(); val q = inp.readByte().toInt()
                        out.add(TrackPoint(t, la, lo, h.takeIf { !it.isNaN() }?.toDouble(), FixQuality.of(q)))
                    }
                } catch (_: EOFException) {}
                out
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(points: List<TrackPoint>) {
        try {
            file.parentFile?.mkdirs()
            val bytes = ByteArrayOutputStream(5 + points.size * 29)
            DataOutputStream(bytes).use { o ->
                o.write(MAGIC.toByteArray(Charsets.US_ASCII)); o.writeByte(VERSION)
                for (p in points) {
                    o.writeLong(p.timeMs); o.writeDouble(p.latitude); o.writeDouble(p.longitude)
                    o.writeFloat(p.height?.toFloat() ?: Float.NaN); o.writeByte(p.quality.code)
                }
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes.toByteArray())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {}
    }

    fun clear() { file.delete(); File(file.path + ".tmp").delete() }

    companion object {
        private const val MAGIC = "PGTR"
        private const val VERSION = 1
    }
}
