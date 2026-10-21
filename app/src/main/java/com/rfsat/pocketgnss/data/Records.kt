package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.core.GnssFix
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Grid
import com.rfsat.pocketgnss.protocol.GridSystem
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A surveyed (averaged) point. Heights in metres: ellipsoidal and MSL. Accuracies are 1-sigma, metres. */
data class SurveyPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val heightEllipsoid: Double,
    val heightMsl: Double?,
    val sdEast: Double,
    val sdNorth: Double,
    val sdUp: Double,
    val samples: Int,
    val quality: FixQuality,
    val timeMs: Long,
    val note: String = "",
    /** Attribute code / category (e.g. "tree", "drain", "sample"). */
    val code: String = "",
    /** Photo file name in the points/photos folder ("" = none). */
    val photo: String = "",
)

/** One row of a session's fixes.csv, read back for the map track and exports. */
data class TrackPoint(val timeMs: Long, val latitude: Double, val longitude: Double, val height: Double?, val quality: FixQuality)

private fun esc(s: String) = s.replace(';', ',').replace('\n', ' ')

private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
fun isoTime(ms: Long): String = synchronized(iso) { iso.format(Date(ms)) }

/** Points file: one CSV in the app's own storage (no JSON library, so it also loads in plain unit tests). */
class PointStore(private val file: File) {
    @Synchronized fun load(): List<SurveyPoint> {
        if (!file.exists()) return emptyList()
        return file.readLines().drop(1).mapNotNull { l ->
            val f = l.split(';')
            if (f.size < 11) null else try {
                SurveyPoint(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDoubleOrNull(), f[5].toDouble(), f[6].toDouble(),
                    f[7].toDouble(), f[8].toInt(), FixQuality.entries.firstOrNull { it.name == f[9] } ?: FixQuality.NONE, f[10].toLong(), f.getOrElse(11) { "" },
                    f.getOrElse(12) { "" }, f.getOrElse(13) { "" })
            } catch (_: NumberFormatException) { null }
        }
    }

    @Synchronized fun save(list: List<SurveyPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("name;lat;lon;h_ell;h_msl;sd_e;sd_n;sd_u;samples;quality;time_ms;note;code;photo\n")
            for (p in list) w.write(listOf(esc(p.name), "%.10f".format(Locale.US, p.latitude), "%.10f".format(Locale.US, p.longitude),
                "%.4f".format(Locale.US, p.heightEllipsoid), p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "",
                "%.4f".format(Locale.US, p.sdEast), "%.4f".format(Locale.US, p.sdNorth), "%.4f".format(Locale.US, p.sdUp),
                p.samples, p.quality.name, p.timeMs, esc(p.note), esc(p.code), esc(p.photo)).joinToString(";") + "\n")
        }
    }

    fun add(p: SurveyPoint) = save(load() + p)
    fun remove(p: SurveyPoint) = save(load().filterNot { it.name == p.name && it.timeMs == p.timeMs })
}

/** Exports to the formats the AgriBIT users asked for (D3.5 Table 1: KML, SHP...) plus GPX, CSV and GeoJSON. */
object Exporters {
    fun pointsKml(points: List<SurveyPoint>, title: String = "PocketGNSS points", photos: Boolean = false): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(xml(title)).append("</name>\n")
        for (p in points) {
            append("<Placemark><name>").append(xml(p.name)).append("</name><description>")
            append(xml(listOfNotNull(p.code.ifBlank { null }, p.note.ifBlank { null },
                "%s, %d samples, σE %.3f m σN %.3f m σU %.3f m, %s".format(Locale.US, p.quality.name, p.samples, p.sdEast, p.sdNorth, p.sdUp, isoTime(p.timeMs))).joinToString(" · ")))
            if (photos && p.photo.isNotBlank()) append(xml("<br/><img src=\"files/${p.photo}\" width=\"480\"/>"))
            val alt = p.heightMsl ?: p.heightEllipsoid.takeIf { !it.isNaN() }
            append("</description><ExtendedData><Data name=\"code\"><value>").append(xml(p.code)).append("</value></Data><Data name=\"note\"><value>")
                .append(xml(p.note)).append("</value></Data></ExtendedData><Point>").append(if (alt != null) "<altitudeMode>absolute</altitudeMode>" else "").append("<coordinates>")
            append("%.10f,%.10f".format(Locale.US, p.longitude, p.latitude)).append(alt?.let { ",%.4f".format(Locale.US, it) } ?: "")
            append("</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    fun pointsGeoJson(points: List<SurveyPoint>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        points.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append("%.10f,%.10f".format(Locale.US, p.longitude, p.latitude)).append(if (p.heightEllipsoid.isNaN()) "" else ",%.4f".format(Locale.US, p.heightEllipsoid))
            append("]},\"properties\":{")
            append("\"name\":\"").append(json(p.name)).append("\",\"quality\":\"").append(p.quality.name).append("\",")
            append("\"samples\":${p.samples},\"sd_e\":%.4f,\"sd_n\":%.4f,\"sd_u\":%.4f,".format(Locale.US, p.sdEast, p.sdNorth, p.sdUp))
            append("\"h_msl\":").append(p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "null").append(",\"time\":\"").append(isoTime(p.timeMs))
            append("\",\"code\":\"").append(json(p.code)).append("\",\"note\":\"").append(json(p.note)).append("\",\"photo\":\"").append(json(p.photo)).append("\"}}")
        }
        append("]}\n")
    }

    fun trackGpx(points: List<TrackPoint>, name: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"PocketGNSS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("<trk><name>").append(xml(name)).append("</name><trkseg>\n")
        for (p in points) {
            append("<trkpt lat=\"%.9f\" lon=\"%.9f\">".format(Locale.US, p.latitude, p.longitude))
            p.height?.let { append("<ele>%.3f</ele>".format(Locale.US, it)) }
            append("<time>").append(isoTime(p.timeMs)).append("</time><fix>").append(when (p.quality) {
                FixQuality.RTK_FIXED, FixQuality.RTK_FLOAT, FixQuality.DGNSS -> "dgps"
                FixQuality.NONE -> "none"
                else -> "3d"
            }).append("</fix></trkpt>\n")
        }
        append("</trkseg></trk></gpx>\n")
    }

    fun trackKml(points: List<TrackPoint>, name: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(xml(name)).append("</name>\n")
        append("<Style id=\"t\"><LineStyle><color>ffe5881e</color><width>3</width></LineStyle></Style>\n")
        append("<Placemark><name>").append(xml(name)).append("</name><styleUrl>#t</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
        for (p in points) append("%.9f,%.9f,%.3f\n".format(Locale.US, p.longitude, p.latitude, p.height ?: 0.0))
        append("</coordinates></LineString></Placemark></Document></kml>\n")
    }

    /** Points as CSV; with a [grid], three more columns: the grid's name, easting and northing (m). */
    fun pointsCsv(points: List<SurveyPoint>, grid: GridSystem = GridSystem.NONE, utmZone: Int = 0): String = buildString {
        append("name,latitude,longitude,h_ellipsoid_m,h_msl_m,sd_east_m,sd_north_m,sd_up_m,samples,quality,time_utc,code,note,photo")
        append(if (grid != GridSystem.NONE) ",grid,easting_m,northing_m\n" else "\n")
        for (p in points) {
            append("\"%s\",%.10f,%.10f,%s,%s,%.4f,%.4f,%.4f,%d,%s,%s".format(Locale.US, p.name.replace("\"", "'"),
                p.latitude, p.longitude, opt4(p.heightEllipsoid), p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "", p.sdEast, p.sdNorth, p.sdUp,
                p.samples, p.quality.name, isoTime(p.timeMs)))
            append(",\"%s\",\"%s\",\"%s\"".format(p.code.replace("\"", "'"), p.note.replace("\"", "'"), p.photo))
            val g = Grid.of(grid, p.latitude, p.longitude, p.heightEllipsoid.takeIf { !it.isNaN() }, utmZone)
            if (g != null) append(",\"%s\",%.4f,%.4f".format(Locale.US, g.label, g.easting, g.northing))
            else if (grid != GridSystem.NONE) append(",,,")
            append("\n")
        }
    }

    private fun opt4(v: Double) = if (v.isNaN()) "" else "%.4f".format(Locale.US, v)

    // ------------------------------------------------------------------------------------------ fields

    private fun ha(f: Field) = "%.4f".format(Locale.US, f.geometry.hectares)

    fun fieldsKml(fields: List<Field>, title: String = "PocketGNSS fields"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(xml(title)).append("</name>\n")
        append("<Style id=\"field\"><LineStyle><color>ff00d7ff</color><width>2</width></LineStyle><PolyStyle><color>4000d7ff</color></PolyStyle></Style>\n")
        for (f in fields) {
            val ring = f.vertices + f.vertices.take(1)
            append("<Placemark><name>").append(xml(f.name)).append("</name><description>")
            append(xml("%s ha, perimeter %.2f m, %d vertices, %s, %s%s".format(Locale.US, ha(f), f.geometry.perimeter, f.vertices.size, f.quality.name,
                isoTime(f.timeMs), if (f.note.isNotBlank()) ", " + f.note else "")))
            append("</description><styleUrl>#field</styleUrl><ExtendedData>")
            append("<Data name=\"area_ha\"><value>").append(ha(f)).append("</value></Data>")
            append("<Data name=\"perimeter_m\"><value>%.3f</value></Data>".format(Locale.US, f.geometry.perimeter))
            append("</ExtendedData><Polygon><tessellate>1</tessellate><outerBoundaryIs><LinearRing><coordinates>")
            append(ring.joinToString(" ") { "%.10f,%.10f".format(Locale.US, it.longitude, it.latitude) })
            append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    /** GeoJSON (RFC 7946): closed rings, counter-clockwise. */
    fun fieldsGeoJson(fields: List<Field>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        fields.forEachIndexed { i, f ->
            if (i > 0) append(',')
            var ring = f.vertices + f.vertices.take(1)
            if (ringSignedArea(ring) < 0) ring = ring.reversed()
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
            append(ring.joinToString(",") { "[%.10f,%.10f]".format(Locale.US, it.longitude, it.latitude) })
            append("]]},\"properties\":{\"name\":\"").append(json(f.name)).append("\",\"area_ha\":").append(ha(f))
            append(",\"perimeter_m\":%.3f,\"vertices\":%d,\"quality\":\"%s\",\"time\":\"%s\",\"note\":\"%s\"}}".format(Locale.US,
                f.geometry.perimeter, f.vertices.size, f.quality.name, isoTime(f.timeMs), json(f.note)))
        }
        append("]}\n")
    }

    /** Vertices as CSV, with grid coordinates when a [grid] is chosen. */
    fun fieldsCsv(fields: List<Field>, grid: GridSystem = GridSystem.NONE, utmZone: Int = 0): String = buildString {
        append("field,vertex,latitude,longitude,h_ellipsoid_m,area_ha,perimeter_m")
        append(if (grid != GridSystem.NONE) ",grid,easting_m,northing_m\n" else "\n")
        for (f in fields) f.vertices.forEachIndexed { k, v ->
            append("\"%s\",%d,%.10f,%.10f,%s,%s,%.3f".format(Locale.US, f.name.replace("\"", "'"), k + 1, v.latitude, v.longitude, opt4(v.height),
                ha(f), f.geometry.perimeter))
            val g = Grid.of(grid, v.latitude, v.longitude, v.height.takeIf { !it.isNaN() }, utmZone)
            if (g != null) append(",\"%s\",%.4f,%.4f".format(Locale.US, g.label, g.easting, g.northing)) else if (grid != GridSystem.NONE) append(",,,")
            append("\n")
        }
    }

    fun fieldsShapefile(fields: List<Field>, base: String = "fields"): ByteArray {
        val cols = listOf(DbfField("NAME", 'C', 80), DbfField("AREA_HA", 'N', 14, 4), DbfField("PERIM_M", 'N', 14, 3), DbfField("VERTICES", 'N', 8),
            DbfField("QUALITY", 'C', 10), DbfField("DATE", 'C', 24), DbfField("NOTE", 'C', 120))
        val rows = fields.map { listOf(it.name, it.geometry.hectares, it.geometry.perimeter, it.vertices.size, it.quality.name, isoTime(it.timeMs), it.note) }
        return Shapefile.zip(base, Shapefile.polygons(fields.map { f -> f.vertices.map { it.longitude to it.latitude } }, cols, rows))
    }

    fun pointsShapefile(points: List<SurveyPoint>, grid: GridSystem = GridSystem.NONE, utmZone: Int = 0, base: String = "points"): ByteArray {
        val cols = mutableListOf(DbfField("NAME", 'C', 80), DbfField("H_ELL", 'N', 12, 4), DbfField("H_MSL", 'N', 12, 4), DbfField("SD_E", 'N', 10, 4),
            DbfField("SD_N", 'N', 10, 4), DbfField("SD_U", 'N', 10, 4), DbfField("SAMPLES", 'N', 8), DbfField("QUALITY", 'C', 10), DbfField("DATE", 'C', 24),
            DbfField("NOTE", 'C', 120), DbfField("CODE", 'C', 40), DbfField("PHOTO", 'C', 60))
        if (grid != GridSystem.NONE) cols += listOf(DbfField("GRID", 'C', 40), DbfField("EASTING", 'N', 15, 4), DbfField("NORTHING", 'N', 15, 4))
        val rows = points.map { p ->
            val base0 = listOf<Any?>(p.name, p.heightEllipsoid, p.heightMsl, p.sdEast, p.sdNorth, p.sdUp, p.samples, p.quality.name, isoTime(p.timeMs), p.note, p.code, p.photo)
            if (grid == GridSystem.NONE) base0 else {
                val g = Grid.of(grid, p.latitude, p.longitude, p.heightEllipsoid.takeIf { !it.isNaN() }, utmZone)
                base0 + listOf(g?.label, g?.easting, g?.northing)
            }
        }
        return Shapefile.zip(base, Shapefile.points(points.map { it.longitude to it.latitude }, cols, rows))
    }

    /** KMZ: the points' KML with their photos inside (files/...), shown in the placemark balloons. */
    fun pointsKmz(points: List<SurveyPoint>, photoDir: java.io.File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("doc.kml")); z.write(pointsKml(points, photos = true).toByteArray()); z.closeEntry()
            for (p in points.filter { it.photo.isNotBlank() }.distinctBy { it.photo }) {
                val f = java.io.File(photoDir, p.photo)
                if (f.isFile) { z.putNextEntry(java.util.zip.ZipEntry("files/${p.photo}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
        }
        return out.toByteArray()
    }

    private fun ringSignedArea(r: List<Vertex>): Double {
        var s = 0.0
        for (i in 0 until r.size - 1) s += r[i].longitude * r[i + 1].latitude - r[i + 1].longitude * r[i].latitude
        return s / 2
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** CSV columns of a session's fixes.csv. */
object FixCsv {
    const val HEADER = "time_utc,epoch_ms,latitude,longitude,h_msl_m,h_ellipsoid_m,quality,fix,satellites,hdop,sd_lat_m,sd_lon_m,sd_alt_m,speed_ms,course_deg,diff_age_s,diff_station"

    fun row(f: GnssFix): String {
        fun d(v: Double?, p: Int) = v?.let { "%.${p}f".format(Locale.US, it) } ?: ""
        return listOf(isoTime(f.receivedAtMs), f.receivedAtMs.toString(), "%.10f".format(Locale.US, f.latitude), "%.10f".format(Locale.US, f.longitude),
            d(f.altitudeMsl, 4), d(f.ellipsoidHeight, 4), f.quality.code.toString(), f.quality.name, f.satellitesUsed?.toString() ?: "",
            d(f.hdop, 2), d(f.sdLatitude, 4), d(f.sdLongitude, 4), d(f.sdAltitude, 4), d(f.speedMs, 3), d(f.courseDeg, 2), d(f.diffAgeSec, 1),
            f.diffStation ?: "").joinToString(",")
    }

    fun read(file: File, maxPoints: Int = 20_000): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        val rows = file.readLines().drop(1)
        val step = maxOf(1, rows.size / maxPoints)
        return rows.filterIndexed { i, _ -> i % step == 0 }.mapNotNull { l ->
            val f = l.split(',')
            try {
                TrackPoint(f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f[5].toDoubleOrNull() ?: f[4].toDoubleOrNull(), FixQuality.of(f[6].toIntOrNull()))
            } catch (_: Exception) { null }
        }
    }
}

/** A recording session: raw.nmea (everything the receiver sent) and fixes.csv (one row per logged epoch). */
class SessionRecorder(val dir: File) {
    private val raw: BufferedWriter
    private val csv: BufferedWriter
    var fixes = 0; private set
    var lines = 0L; private set
    val startedMs = System.currentTimeMillis()

    init {
        dir.mkdirs()
        raw = File(dir, "raw.nmea").bufferedWriter()
        csv = File(dir, "fixes.csv").bufferedWriter().also { it.write(FixCsv.HEADER + "\n") }
    }

    @Synchronized fun line(s: String) { raw.write(s); raw.write("\r\n"); lines++ }
    @Synchronized fun fix(f: GnssFix) { csv.write(FixCsv.row(f)); csv.write("\n"); fixes++; if (fixes % 20 == 0) { csv.flush(); raw.flush() } }
    @Synchronized fun close() { try { raw.close() } catch (_: Exception) {}; try { csv.close() } catch (_: Exception) {} }

    companion object {
        private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fun newDir(root: File): File = File(root, "session_" + synchronized(stamp) { stamp.format(Date()) })
    }
}
