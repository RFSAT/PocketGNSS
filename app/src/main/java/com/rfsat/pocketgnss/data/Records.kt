package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.core.GnssFix
import com.rfsat.pocketgnss.protocol.FixQuality
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
                    f[7].toDouble(), f[8].toInt(), FixQuality.entries.firstOrNull { it.name == f[9] } ?: FixQuality.NONE, f[10].toLong(), f.getOrElse(11) { "" })
            } catch (_: NumberFormatException) { null }
        }
    }

    @Synchronized fun save(list: List<SurveyPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("name;lat;lon;h_ell;h_msl;sd_e;sd_n;sd_u;samples;quality;time_ms;note\n")
            for (p in list) w.write(listOf(esc(p.name), "%.10f".format(Locale.US, p.latitude), "%.10f".format(Locale.US, p.longitude),
                "%.4f".format(Locale.US, p.heightEllipsoid), p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "",
                "%.4f".format(Locale.US, p.sdEast), "%.4f".format(Locale.US, p.sdNorth), "%.4f".format(Locale.US, p.sdUp),
                p.samples, p.quality.name, p.timeMs, esc(p.note)).joinToString(";") + "\n")
        }
    }

    fun add(p: SurveyPoint) = save(load() + p)
    fun remove(p: SurveyPoint) = save(load().filterNot { it.name == p.name && it.timeMs == p.timeMs })
}

/** Exports to the formats the AgriBIT users asked for (D3.5 Table 1: KML, SHP...) plus GPX, CSV and GeoJSON. */
object Exporters {
    fun pointsKml(points: List<SurveyPoint>, title: String = "PocketGNSS points"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(xml(title)).append("</name>\n")
        for (p in points) {
            append("<Placemark><name>").append(xml(p.name)).append("</name><description>")
            append(xml("%s, %d samples, σE %.3f m σN %.3f m σU %.3f m, %s".format(Locale.US, p.quality.name, p.samples, p.sdEast, p.sdNorth, p.sdUp, isoTime(p.timeMs))))
            append("</description><Point><altitudeMode>absolute</altitudeMode><coordinates>")
            append("%.10f,%.10f,%.4f".format(Locale.US, p.longitude, p.latitude, p.heightMsl ?: p.heightEllipsoid))
            append("</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }

    fun pointsGeoJson(points: List<SurveyPoint>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        points.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append("%.10f,%.10f,%.4f".format(Locale.US, p.longitude, p.latitude, p.heightEllipsoid)).append("]},\"properties\":{")
            append("\"name\":\"").append(json(p.name)).append("\",\"quality\":\"").append(p.quality.name).append("\",")
            append("\"samples\":${p.samples},\"sd_e\":%.4f,\"sd_n\":%.4f,\"sd_u\":%.4f,".format(Locale.US, p.sdEast, p.sdNorth, p.sdUp))
            append("\"h_msl\":").append(p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "null").append(",\"time\":\"").append(isoTime(p.timeMs)).append("\"}}")
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

    fun pointsCsv(points: List<SurveyPoint>): String = buildString {
        append("name,latitude,longitude,h_ellipsoid_m,h_msl_m,sd_east_m,sd_north_m,sd_up_m,samples,quality,time_utc\n")
        for (p in points) append("\"%s\",%.10f,%.10f,%.4f,%s,%.4f,%.4f,%.4f,%d,%s,%s\n".format(Locale.US, p.name.replace("\"", "'"),
            p.latitude, p.longitude, p.heightEllipsoid, p.heightMsl?.let { "%.4f".format(Locale.US, it) } ?: "", p.sdEast, p.sdNorth, p.sdUp,
            p.samples, p.quality.name, isoTime(p.timeMs)))
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
