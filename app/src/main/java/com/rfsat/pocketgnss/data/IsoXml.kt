package com.rfsat.pocketgnss.data

import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.core.insideField
import com.rfsat.pocketgnss.protocol.FixQuality
import java.util.Locale

/**
 * ISO 11783-10 (ISOBUS) task data, version 4.3: how field boundaries, guidance lines and points reach tractor terminals
 * and farm management software. Each field becomes a partfield (PFD) with its boundary (PLN type 1, exterior ring
 * LSG type 1), the guidance lines that start in it (GGP / GPN: AB lines as type 1, curves as type 3, headland rings as
 * type 3 without extensions; LSG type 5 with the reference points A (PNT 6), B (PNT 7) and the curve's points (PNT 9),
 * implement width in mm) and the points inside it (PNT type 2, "other", with the name). Lines and points outside every
 * field go into one more partfield. Coordinates are WGS 84 / ETRS89 degrees as recorded; heights (PointUp) are
 * ellipsoidal, in millimetres.
 */
object IsoXml {
    const val VERSION_MAJOR = 4
    const val VERSION_MINOR = 3

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun name(s: String) = esc(s.take(32))
    private fun deg(v: Double) = "%.9f".format(Locale.US, v)

    /** GPN GNSS method of a fix quality (ISO 11783-10). */
    fun gnssMethod(q: FixQuality): Int = when (q) {
        FixQuality.RTK_FIXED -> 4
        FixQuality.RTK_FLOAT -> 5
        FixQuality.DGNSS -> 2
        FixQuality.SINGLE -> 1
        FixQuality.DEAD_RECKONING -> 6
        else -> 0
    }

    fun taskData(fields: List<Field>, lines: List<AbLine>, points: List<SurveyPoint>, version: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<ISO11783_TaskData VersionMajor=\"$VERSION_MAJOR\" VersionMinor=\"$VERSION_MINOR\" ManagementSoftwareManufacturer=\"RFSAT PocketGNSS\"")
        append(" ManagementSoftwareVersion=\"").append(esc(version.take(32))).append("\" DataTransferOrigin=\"1\">\n")
        var pfd = 0; var ggp = 0; var gpn = 0
        val usedLines = HashSet<AbLine>(); val usedPoints = HashSet<SurveyPoint>()
        fun pnt(type: Int, lat: Double, lon: Double, label: String? = null, hEll: Double? = null, hAcc: Double? = null, vAcc: Double? = null) {
            append("      <PNT A=\"$type\"")
            if (label != null) append(" B=\"").append(name(label)).append('"')
            append(" C=\"").append(deg(lat)).append("\" D=\"").append(deg(lon)).append('"')
            if (hEll != null && !hEll.isNaN()) append(" E=\"").append(Math.round(hEll * 1000)).append('"')
            if (hAcc != null && hAcc > 0) append(" H=\"%.3f\"".format(Locale.US, hAcc.coerceAtMost(65.0)))
            if (vAcc != null && vAcc > 0) append(" I=\"%.3f\"".format(Locale.US, vAcc.coerceAtMost(65.0)))
            append("/>\n")
        }
        fun guidance(ls: List<AbLine>) {
            if (ls.isEmpty()) return
            append("    <GGP A=\"GGP${++ggp}\" B=\"Guidance\">\n")
            for (l in ls) {
                val curve = l.curve
                val type = if (curve.isEmpty()) 1 else 3
                append("     <GPN A=\"GPN${++gpn}\" B=\"").append(name(l.name)).append("\" C=\"$type\" E=\"1\"")
                append(" F=\"").append(if (l.closed) 4 else 1).append('"')
                if (curve.isEmpty()) append(" G=\"%.4f\"".format(Locale.US, l.bearing))
                append(">\n")
                append("      <LSG A=\"5\" C=\"").append(Math.round(l.width * 1000)).append("\">\n")
                if (curve.isEmpty()) { pnt(6, l.aLat, l.aLon); pnt(7, l.bLat, l.bLon) }
                else curve.forEachIndexed { i, (la, lo) -> pnt(if (i == 0) 6 else if (i == curve.size - 1 && !l.closed) 7 else 9, la, lo) }
                append("      </LSG>\n     </GPN>\n")
            }
            append("    </GGP>\n")
        }
        for (f in fields.filter { it.vertices.size >= 3 }) {
            append("  <PFD A=\"PFD${++pfd}\" C=\"").append(name(f.name)).append("\" D=\"").append(Math.round(f.geometry.area)).append("\">\n")
            append("    <PLN A=\"1\" B=\"").append(name(f.name)).append("\" C=\"").append(Math.round(f.geometry.area)).append("\">\n")
            append("     <LSG A=\"1\">\n")
            for (v in f.vertices + f.vertices.first()) pnt(2, v.latitude, v.longitude, hEll = v.height.takeIf { !it.isNaN() })
            append("     </LSG>\n    </PLN>\n")
            val ls = lines.filter { it !in usedLines && insideField(f, it.aLat, it.aLon) }; usedLines += ls
            val ps = points.filter { it !in usedPoints && insideField(f, it.latitude, it.longitude) }; usedPoints += ps
            for (p in ps) pnt(2, p.latitude, p.longitude, p.name, p.heightEllipsoid, kotlin.math.hypot(p.sdEast, p.sdNorth), p.sdUp)
            guidance(ls)
            append("  </PFD>\n")
        }
        val restLines = lines.filter { it !in usedLines }; val restPoints = points.filter { it !in usedPoints }
        if (restLines.isNotEmpty() || restPoints.isNotEmpty()) {
            append("  <PFD A=\"PFD${++pfd}\" C=\"PocketGNSS\" D=\"0\">\n")
            for (p in restPoints) pnt(2, p.latitude, p.longitude, p.name, p.heightEllipsoid, kotlin.math.hypot(p.sdEast, p.sdNorth), p.sdUp)
            guidance(restLines)
            append("  </PFD>\n")
        }
        append("</ISO11783_TaskData>\n")
    }

    /** TASKDATA.zip with TASKDATA/TASKDATA.XML, the layout terminals read from a USB stick. */
    fun zip(xml: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("TASKDATA/TASKDATA.XML")); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry()
        }
        return out.toByteArray()
    }
}
