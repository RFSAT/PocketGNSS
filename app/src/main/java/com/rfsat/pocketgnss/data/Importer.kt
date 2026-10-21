package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Grid
import com.rfsat.pocketgnss.protocol.GridRef
import com.rfsat.pocketgnss.protocol.GridSystem
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

enum class ImportIssue {
    /** Grid (E/N) coordinates but no grid chosen in Setup and none named in the file. */
    NO_GRID,
    /** Not CSV, GeoJSON, KML, GPX or a zipped shapefile. */
    UNKNOWN_FORMAT,
    /** Readable, but no points or boundaries in it. */
    NOTHING_FOUND,
    /** Some rows / features could not be read. */
    SKIPPED,
}

data class ImportResult(val points: List<SurveyPoint>, val fields: List<Field>, val skipped: Int = 0, val issue: ImportIssue? = null)

/**
 * Reads points and boundaries prepared elsewhere (GIS, farm-management software, paying-agency parcel downloads)
 * for stake-out and the field list. Geographic coordinates are taken as ETRS89 / WGS 84; grid coordinates use the
 * grid the file names (shapefile .prj, GeoJSON "crs") or else [grid], the one chosen in Setup. Pure Kotlin
 * (javax.xml for KML / GPX), unit-tested.
 */
class Importer(private val grid: GridRef?, private val now: Long, private val source: String) {
    private val note = "imported: $source"
    private var skipped = 0

    fun read(bytes: ByteArray): ImportResult {
        val r = try {
            when {
                bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> shapefileZip(bytes)
                else -> {
                    val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                    val head = text.trimStart().take(400).lowercase(Locale.US)
                    when {
                        head.startsWith("{") -> geoJson(text)
                        head.contains("<kml") -> kml(bytes)
                        head.contains("<gpx") -> gpx(bytes)
                        head.startsWith("<") -> ImportResult(emptyList(), emptyList(), issue = ImportIssue.UNKNOWN_FORMAT)
                        else -> csv(text)
                    }
                }
            }
        } catch (_: NoGrid) {
            ImportResult(emptyList(), emptyList(), issue = ImportIssue.NO_GRID)
        } catch (_: Exception) {
            ImportResult(emptyList(), emptyList(), issue = ImportIssue.UNKNOWN_FORMAT)
        }
        if (r.issue != null) return r
        val issue = when { r.points.isEmpty() && r.fields.isEmpty() -> ImportIssue.NOTHING_FOUND; skipped > 0 -> ImportIssue.SKIPPED; else -> null }
        return r.copy(skipped = skipped, issue = issue)
    }

    private class NoGrid : Exception()

    private fun point(name: String, lat: Double, lon: Double, hEll: Double?, hMsl: Double?, code: String = "", text: String = "") =
        SurveyPoint(name, lat, lon, hEll ?: Double.NaN, hMsl, 0.0, 0.0, 0.0, 0, FixQuality.NONE, now, text.ifBlank { note }, code)

    private fun field(name: String, ring: List<Pair<Double, Double>>): Field? {
        var r = ring
        if (r.size >= 2 && r.first() == r.last()) r = r.dropLast(1)
        if (r.size < 3) { skipped++; return null }
        return Field(name, r.map { Vertex(it.first, it.second) }, now, FixQuality.NONE, note)
    }

    /** Grid to latitude / longitude with [ref]; tries both axis orders (some countries write northing first). */
    private fun fromGrid(ref: GridRef, a: Double, b: Double): Pair<Double, Double>? {
        fun ok(p: Pair<Double, Double>?) = p != null && (ref.system.area?.let { ar ->
            if (ref.system == GridSystem.UTM) abs(p.second - (ref.utmZone * 6 - 183)) < 9 else ar.contains(p.first, p.second)
        } ?: false)
        val en = Grid.inverse(ref.system, a, b, ref.utmZone, ref.south)
        if (ok(en)) return en
        val ne = Grid.inverse(ref.system, b, a, ref.utmZone, ref.south)
        return if (ok(ne)) ne else en
    }

    private fun gridOrThrow(ref: GridRef?): GridRef {
        val g = ref ?: grid
        if (g == null || g.system == GridSystem.NONE || (g.system == GridSystem.UTM && g.utmZone !in 1..60)) throw NoGrid()
        return g
    }

    // -------------------------------------------------------------------------------------------- CSV

    private val nameCols = listOf("name", "id", "point", "pt", "label", "nazwa", "nom", "nome", "nombre", "naam", "punkt", "code")
    private val latCols = listOf("lat", "latitude", "lat_deg", "phi", "breite")
    private val lonCols = listOf("lon", "lng", "long", "longitude", "lon_deg", "lambda", "laenge", "länge")
    private val eCols = listOf("e", "easting", "easting_m", "east", "rechtswert")
    private val nCols = listOf("n", "northing", "northing_m", "north", "hochwert")
    private val codeCols = listOf("code", "category", "type", "kod", "attribute", "class")
    private val noteCols = listOf("note", "description", "desc", "comment", "remark", "notes")
    private val xCols = listOf("x"); private val yCols = listOf("y")
    private val hEllCols = listOf("h_ellipsoid_m", "h_ell", "hell", "h_ellipsoid", "ellipsoidal_height", "ell_h")
    private val hMslCols = listOf("h_msl_m", "h_msl", "msl", "elevation", "elev", "ele", "alt", "altitude", "height", "h", "z", "orthometric_height")

    private fun splitCsv(line: String, d: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == d && !q -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun num(s: String?, decimalComma: Boolean): Double? {
        if (s.isNullOrBlank()) return null
        val t = if (decimalComma) s.replace(',', '.') else s
        return t.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun csv(text: String): ImportResult {
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty()) return ImportResult(emptyList(), emptyList())
        val first = lines[0]
        val d = when { first.contains('\t') -> '\t'; first.contains(';') -> ';'; else -> ',' }
        val dc = d != ','
        val head = splitCsv(first, d).map { it.lowercase(Locale.US).trim().removeSurrounding("\"") }
        fun col(names: List<String>) = head.indexOfFirst { it in names }
        val known = listOf(nameCols, latCols, lonCols, eCols, nCols, xCols, yCols, hEllCols, hMslCols).count { col(it) >= 0 }
        val hasHeader = known >= 2
        val rows = (if (hasHeader) lines.drop(1) else lines).map { splitCsv(it, d) }
        val pts = ArrayList<SurveyPoint>()
        var k = 0
        for (r in rows) {
            k++
            try {
                val name: String; val a: Double?; val b: Double?; var hEll: Double? = null; var hMsl: Double? = null
                var geographic: Boolean? = null
                if (hasHeader) {
                    val ni = col(nameCols)
                    name = r.getOrNull(ni)?.takeIf { ni >= 0 && it.isNotBlank() } ?: "I$k"
                    val la = col(latCols); val lo = col(lonCols)
                    if (la >= 0 && lo >= 0) { a = num(r.getOrNull(la), dc); b = num(r.getOrNull(lo), dc); geographic = true }
                    else {
                        val e = col(eCols).takeIf { it >= 0 } ?: col(xCols); val n = col(nCols).takeIf { it >= 0 } ?: col(yCols)
                        a = num(r.getOrNull(e), dc); b = num(r.getOrNull(n), dc)
                        // x / y in degrees are longitude / latitude
                        if (a != null && b != null && abs(a) <= 180 && abs(b) <= 90 && col(xCols) == e) {
                            hEll = col(hEllCols).let { num(r.getOrNull(it), dc) }; hMsl = col(hMslCols).let { num(r.getOrNull(it), dc) }
                            pts.add(point(name, b, a, hEll, hMsl)); continue
                        }
                        geographic = false
                    }
                    hEll = col(hEllCols).takeIf { it >= 0 }?.let { num(r.getOrNull(it), dc) }
                    hMsl = col(hMslCols).takeIf { it >= 0 }?.let { num(r.getOrNull(it), dc) }
                } else {
                    val nums = r.indices.filter { num(r[it], dc) != null }
                    if (nums.size < 2) { skipped++; continue }
                    name = r.indices.firstOrNull { it !in nums && r[it].isNotBlank() }?.let { r[it] } ?: "I$k"
                    a = num(r[nums[0]], dc); b = num(r[nums[1]], dc)
                    hMsl = nums.getOrNull(2)?.let { num(r[it], dc) }
                }
                if (a == null || b == null) { skipped++; continue }
                val isGeo = geographic ?: (abs(a) <= 90 && abs(b) <= 180)
                val code = if (hasHeader) col(codeCols).takeIf { it >= 0 }?.let { r.getOrNull(it) } ?: "" else ""
                val text = if (hasHeader) col(noteCols).takeIf { it >= 0 }?.let { r.getOrNull(it) } ?: "" else ""
                if (isGeo) pts.add(point(name, a, b, hEll, hMsl, code, text))
                else {
                    val ll = fromGrid(gridOrThrow(null), a, b) ?: run { skipped++; null } ?: continue
                    pts.add(point(name, ll.first, ll.second, hEll, hMsl, code, text))
                }
            } catch (e: NoGrid) { throw e } catch (_: Exception) { skipped++ }
        }
        return ImportResult(pts, emptyList())
    }

    // -------------------------------------------------------------------------------------------- GeoJSON

    private fun geoJson(text: String): ImportResult {
        val root = Json(text).parse() as? Map<*, *> ?: return ImportResult(emptyList(), emptyList(), issue = ImportIssue.UNKNOWN_FORMAT)
        val crsName = ((root["crs"] as? Map<*, *>)?.get("properties") as? Map<*, *>)?.get("name") as? String
        val ref = crsName?.let { Regex("""EPSG:*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { Grid.fromEpsg(it) }
        val projected = ref != null && ref.system != GridSystem.NONE
        fun ll(c: List<*>): Pair<Double, Double>? {
            val x = (c.getOrNull(0) as? Number)?.toDouble() ?: return null
            val y = (c.getOrNull(1) as? Number)?.toDouble() ?: return null
            return if (projected) fromGrid(ref, x, y) else y to x
        }
        val features: List<Map<*, *>> = when (root["type"]) {
            "FeatureCollection" -> (root["features"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
            "Feature" -> listOf(root)
            else -> listOf(mapOf("type" to "Feature", "geometry" to root, "properties" to emptyMap<String, Any>()))
        }
        val pts = ArrayList<SurveyPoint>(); val fls = ArrayList<Field>()
        features.forEachIndexed { i, f ->
            val props = f["properties"] as? Map<*, *> ?: emptyMap<String, Any>()
            val name = props.entries.firstOrNull { (k, v) -> (k as? String)?.lowercase(Locale.US) in nameCols && v != null && v.toString().isNotBlank() }?.value?.toString()
                ?: "I${i + 1}"
            fun geom(g: Map<*, *>?, suffix: String) {
                if (g == null) { skipped++; return }
                val c = g["coordinates"] as? List<*>
                when (g["type"]) {
                    "Point" -> {
                        val p = c?.let { ll(it) } ?: run { skipped++; return }
                        val z = (c.getOrNull(2) as? Number)?.toDouble()
                        fun prop(names: List<String>) = props.entries.firstOrNull { (k, v) -> (k as? String)?.lowercase(Locale.US) in names && v != null }?.value?.toString() ?: ""
                        pts.add(point(name + suffix, p.first, p.second, if (projected) null else z, (props["h_msl"] as? Number)?.toDouble(), prop(codeCols), prop(noteCols)))
                    }
                    "MultiPoint" -> c?.filterIsInstance<List<*>>()?.forEachIndexed { k, q -> ll(q)?.let { pts.add(point("$name-${k + 1}", it.first, it.second, null, null)) } }
                    "Polygon" -> c?.firstOrNull()?.let { ring -> field(name + suffix, (ring as List<*>).filterIsInstance<List<*>>().mapNotNull { ll(it) })?.let { fls.add(it) } }
                    "MultiPolygon" -> c?.filterIsInstance<List<*>>()?.forEachIndexed { k, poly ->
                        (poly.firstOrNull() as? List<*>)?.let { ring -> field(if (c.size > 1) "$name-${k + 1}" else name, ring.filterIsInstance<List<*>>().mapNotNull { ll(it) })?.let { fls.add(it) } }
                    }
                    "GeometryCollection" -> (g["geometries"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEachIndexed { k, gg -> geom(gg, "-${k + 1}") }
                    else -> skipped++
                }
            }
            geom(f["geometry"] as? Map<*, *>, "")
        }
        return ImportResult(pts, fls)
    }

    // -------------------------------------------------------------------------------------------- KML, GPX

    private fun xmlDoc(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun Element.children(tag: String): List<Element> {
        val nl = getElementsByTagNameNS("*", tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun kmlCoords(s: String): List<Triple<Double, Double, Double?>> = s.trim().split(Regex("\\s+")).mapNotNull { t ->
        val c = t.split(',')
        val lon = c.getOrNull(0)?.toDoubleOrNull(); val lat = c.getOrNull(1)?.toDoubleOrNull()
        if (lon == null || lat == null) null else Triple(lat, lon, c.getOrNull(2)?.toDoubleOrNull())
    }

    private fun kml(bytes: ByteArray): ImportResult {
        val doc = xmlDoc(bytes)
        val pts = ArrayList<SurveyPoint>(); val fls = ArrayList<Field>()
        doc.documentElement.children("Placemark").forEachIndexed { i, pm ->
            val name = pm.children("name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: "I${i + 1}"
            val points = pm.children("Point"); val polys = pm.children("Polygon")
            points.forEachIndexed { k, p ->
                val c = kmlCoords(p.children("coordinates").firstOrNull()?.textContent ?: "").firstOrNull() ?: run { skipped++; return@forEachIndexed }
                pts.add(point(if (points.size > 1) "$name-${k + 1}" else name, c.first, c.second, null, c.third))
            }
            polys.forEachIndexed { k, p ->
                val outer = p.children("outerBoundaryIs").firstOrNull()?.children("coordinates")?.firstOrNull()?.textContent ?: run { skipped++; return@forEachIndexed }
                field(if (polys.size > 1) "$name-${k + 1}" else name, kmlCoords(outer).map { it.first to it.second })?.let { fls.add(it) }
            }
            if (points.isEmpty() && polys.isEmpty()) {
                // A closed line drawn as a boundary
                val ring = pm.children("LineString").firstOrNull()?.children("coordinates")?.firstOrNull()?.textContent?.let { kmlCoords(it) }
                if (ring != null && ring.size >= 4 && ring.first().first == ring.last().first && ring.first().second == ring.last().second)
                    field(name, ring.map { it.first to it.second })?.let { fls.add(it) }
                else skipped++
            }
        }
        return ImportResult(pts, fls)
    }

    private fun gpx(bytes: ByteArray): ImportResult {
        val doc = xmlDoc(bytes)
        val pts = doc.documentElement.children("wpt").mapIndexedNotNull { i, w ->
            val lat = w.getAttribute("lat").toDoubleOrNull(); val lon = w.getAttribute("lon").toDoubleOrNull()
            if (lat == null || lon == null) { skipped++; null } else point(w.children("name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: "I${i + 1}",
                lat, lon, null, w.children("ele").firstOrNull()?.textContent?.trim()?.toDoubleOrNull())
        }
        return ImportResult(pts, emptyList())
    }

    // -------------------------------------------------------------------------------------------- Shapefile

    private fun shapefileZip(bytes: ByteArray): ImportResult {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name.substringAfterLast('/').lowercase(Locale.US)] = z.readBytes()
            }
        }
        val shpName = files.keys.firstOrNull { it.endsWith(".shp") } ?: return ImportResult(emptyList(), emptyList(), issue = ImportIssue.UNKNOWN_FORMAT)
        val base = shpName.removeSuffix(".shp")
        val shp = files.getValue(shpName)
        val names = files["$base.dbf"]?.let { dbfNames(it) } ?: emptyList()
        val prj = files["$base.prj"]?.let { String(it, Charsets.UTF_8) }
        val ref = prj?.let { Grid.fromWkt(it) }
        val b = ByteBuffer.wrap(shp)
        val box = DoubleArray(4) { b.order(ByteOrder.LITTLE_ENDIAN).getDouble(36 + 8 * it) }
        val degrees = box.all { abs(it) <= 180 } && abs(box[1]) <= 90 && abs(box[3]) <= 90
        val geo = when { ref != null -> ref.system == GridSystem.NONE; prj == null -> degrees; else -> false }
        val proj = if (geo) null else gridOrThrow(ref?.takeIf { it.system != GridSystem.NONE })
        fun ll(x: Double, y: Double): Pair<Double, Double>? = if (proj == null) y to x else fromGrid(proj, x, y)
        val pts = ArrayList<SurveyPoint>(); val fls = ArrayList<Field>()
        var pos = 100; var rec = 0
        while (pos + 8 <= shp.size) {
            val len = b.order(ByteOrder.BIG_ENDIAN).getInt(pos + 4) * 2
            val c = pos + 8
            if (c + len > shp.size || len < 4) break
            b.order(ByteOrder.LITTLE_ENDIAN)
            val type = b.getInt(c)
            val name = names.getOrNull(rec)?.takeIf { it.isNotBlank() } ?: "I${rec + 1}"
            when (type) {
                1, 11, 21 -> ll(b.getDouble(c + 4), b.getDouble(c + 12))?.let {
                    pts.add(point(name, it.first, it.second, if (type == 11 && proj == null) b.getDouble(c + 20) else null, null))
                } ?: skipped++
                5, 15, 25 -> {
                    val nParts = b.getInt(c + 36); val nPts = b.getInt(c + 40)
                    val parts = IntArray(nParts) { b.getInt(c + 44 + 4 * it) }
                    val p0 = c + 44 + 4 * nParts
                    val outer = ArrayList<List<Pair<Double, Double>>>()
                    for (k in 0 until nParts) {
                        val s = parts[k]; val e = if (k + 1 < nParts) parts[k + 1] else nPts
                        val xy = (s until e).map { b.getDouble(p0 + 16 * it) to b.getDouble(p0 + 16 * it + 8) }
                        // Outer rings are clockwise in shapefiles; holes (counter-clockwise) are left out.
                        var a = 0.0
                        for (i in 0 until xy.size - 1) a += xy[i].first * xy[i + 1].second - xy[i + 1].first * xy[i].second
                        if (a <= 0) outer.add(xy)
                    }
                    outer.forEachIndexed { k, ring ->
                        field(if (outer.size > 1) "$name-${k + 1}" else name, ring.mapNotNull { (x, y) -> ll(x, y) })?.let { fls.add(it) }
                    }
                }
                0 -> {}
                else -> skipped++
            }
            pos = c + len; rec++
        }
        return ImportResult(pts, fls)
    }

    /** The name-like column of a dBASE table (else the first text column), one value per record. */
    private fun dbfNames(d: ByteArray): List<String> {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(4); val hdr = b.getShort(8).toInt(); val recLen = b.getShort(10).toInt()
        data class F(val name: String, val type: Char, val off: Int, val len: Int)
        val fs = ArrayList<F>(); var off = 1; var p = 32
        while (p + 32 <= hdr && d[p] != 0x0D.toByte()) {
            val nm = String(d, p, 11, Charsets.US_ASCII).trimEnd('\u0000').trim()
            val len = d[p + 16].toInt() and 0xFF
            fs.add(F(nm, d[p + 11].toInt().toChar(), off, len)); off += len; p += 32
        }
        val f = fs.firstOrNull { it.name.lowercase(Locale.US) in nameCols } ?: fs.firstOrNull { it.type == 'C' } ?: return emptyList()
        return (0 until n).map { r ->
            val s = hdr + r * recLen + f.off
            if (s + f.len > d.size) "" else String(d, s, f.len, Charsets.UTF_8).trim()
        }
    }
}

/** A minimal JSON reader (objects, arrays, strings, numbers, true / false / null) for GeoJSON import. */
class Json(private val s: String) {
    private var i = 0
    fun parse(): Any? { val v = value(); ws(); return v }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("JSON at $i")
        }
    }
    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); i++ // ':'
            m[k] = value(); ws()
            if (s[i] == ',') { i++; continue }
            i++; return m
        }
    }
    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>(); i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value()); ws()
            if (s[i] == ',') { i++; continue }
            i++; return l
        }
    }
    private fun str(): String {
        val sb = StringBuilder(); i++
        while (s[i] != '"') {
            val c = s[i++]
            if (c == '\\') {
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(c)
        }
        i++
        return sb.toString()
    }
    private fun number(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(st, i).toDouble()
    }
}
