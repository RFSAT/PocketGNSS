package com.rfsat.pocketgnss.ui.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

/** What an MBTiles file holds (its metadata table). */
data class MbTilesInfo(val name: String, val format: String, val minZoom: Int, val maxZoom: Int,
                       /** west, south, east, north (degrees), or null */ val bounds: DoubleArray?) {
    val raster: Boolean get() = format.lowercase() in setOf("png", "jpg", "jpeg", "webp")
}

/**
 * The user's own offline map: a raster MBTiles file (a drone orthophoto, a national orthophoto or topographic map
 * exported from QGIS / gdal, ...) copied into the app and drawn over the base map by MapLibre's MBTiles source.
 * Bulk downloading the online base maps (OpenStreetMap, Esri, OpenTopoMap) for offline use is not allowed by their
 * terms, so offline areas come from the user's own map plus the tiles already viewed (kept in the tile cache).
 */
object OwnMap {
    fun dir(context: Context) = File(context.filesDir, "maps").apply { mkdirs() }

    fun info(file: File): MbTilesInfo? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val m = HashMap<String, String>()
            db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) m[c.getString(0).lowercase()] = c.getString(1) ?: "" }
            val zooms = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }
            MbTilesInfo(m["name"] ?: file.nameWithoutExtension, m["format"] ?: "png", m["minzoom"]?.toIntOrNull() ?: zooms.first,
                m["maxzoom"]?.toIntOrNull() ?: zooms.second,
                m["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray())
        }
    } catch (_: Exception) { null }

    /** Copies a chosen file into the app; returns its path, or null when it is not a raster MBTiles. */
    fun import(context: Context, uri: Uri): String? {
        val tmp = File(dir(context), "import.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } } ?: return null
            val inf = info(tmp)
            if (inf == null || !inf.raster) { tmp.delete(); return null }
            dir(context).listFiles()?.filter { it.name.endsWith(".mbtiles") }?.forEach { it.delete() }
            val dst = File(dir(context), "own_${System.currentTimeMillis()}.mbtiles")
            return if (tmp.renameTo(dst)) dst.path else null
        } catch (_: Exception) { tmp.delete(); return null }
    }

    fun remove(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }
}
