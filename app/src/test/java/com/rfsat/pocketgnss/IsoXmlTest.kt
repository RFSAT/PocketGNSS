package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.data.Field
import com.rfsat.pocketgnss.data.IsoXml
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ISO 11783-10 task data. The structure is checked here; the file is also written to build/isoxml/TASKDATA.XML,
 * which was validated against the ISO 11783-10 TaskFile schema (V4) with xmlschema (see the README).
 */
class IsoXmlTest {
    private val lat0 = 40.76; private val lon0 = 22.52
    private fun at(e: Double, n: Double) = Geo.fromEnu(lat0, lon0, e, n)

    private fun sample(): String {
        val ring = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 150.0, 0.0 to 150.0).map { (e, n) -> at(e, n).let { Vertex(it.first, it.second, 80.0) } }
        val field = Field("North & \"big\" <plot>", ring, 1_760_000_000_000L, FixQuality.RTK_FIXED)
        val far = Field("Far", listOf(1000.0 to 0.0, 1100.0 to 0.0, 1100.0 to 80.0).map { (e, n) -> at(e, n).let { Vertex(it.first, it.second) } }, 1L)
        val a = at(10.0, 10.0); val b = at(190.0, 10.0)
        val ab = AbLine("Wheat 2026", a.first, a.second, b.first, b.second, 6.0, 0.0, 2L)
        val curve = AbLine.curve("Contour", (0..20).map { at(20.0 + it * 8.0, 40.0 + 10 * kotlin.math.sin(it / 3.0)) }, 4.5, timeMs = 3L)
        val headland = AbLine.headland("North headland", ring.map { it.latitude to it.longitude }, 6.0, 4L)
        val outside = AbLine.fromBearing("Road", at(-500.0, 0.0).first, at(-500.0, 0.0).second, 45.0, 3.0, 5L)
        val p1 = at(50.0, 50.0); val p2 = at(-300.0, -300.0)
        val pts = listOf(SurveyPoint("Tree 1", p1.first, p1.second, 81.234, 43.0, 0.01, 0.012, 0.02, 30, FixQuality.RTK_FIXED, 0L),
            SurveyPoint("Gate", p2.first, p2.second, Double.NaN, null, 0.0, 0.0, 0.0, 1, FixQuality.SINGLE, 0L))
        return IsoXml.taskData(listOf(field, far), listOf(ab, curve, headland, outside), pts, "1.12.0")
    }

    @Test fun structure() {
        val xml = sample()
        File("build/isoxml").mkdirs(); File("build/isoxml/TASKDATA.XML").writeText(xml)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val root = doc.documentElement
        assertEquals("ISO11783_TaskData", root.tagName)
        assertEquals("4", root.getAttribute("VersionMajor")); assertEquals("1", root.getAttribute("DataTransferOrigin"))
        fun kids(e: Element, tag: String) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
        val pfds = kids(root, "PFD")
        assertEquals(3, pfds.size)                             // two fields and one for what lies outside them
        val north = pfds[0]
        assertEquals("North & \"big\" <plot>", north.getAttribute("C"))
        assertEquals(30000.0, north.getAttribute("D").toDouble(), 1.0)
        val pln = kids(north, "PLN").single(); assertEquals("1", pln.getAttribute("A"))
        val ext = kids(pln, "LSG").single(); assertEquals("1", ext.getAttribute("A"))
        assertEquals(5, kids(ext, "PNT").size)                 // closed ring
        assertEquals("80000", kids(ext, "PNT")[0].getAttribute("E"))
        // the point in the field, with its height in mm and accuracies
        val tree = kids(north, "PNT").single()
        assertEquals("Tree 1", tree.getAttribute("B")); assertEquals("81234", tree.getAttribute("E"))
        // AB line, curve and headland in the field's guidance group
        val gpns = kids(kids(north, "GGP").single(), "GPN")
        assertEquals(listOf("1", "3", "3"), gpns.map { it.getAttribute("C") })
        val abLsg = kids(gpns[0], "LSG").single()
        assertEquals("5", abLsg.getAttribute("A")); assertEquals("6000", abLsg.getAttribute("C"))
        assertEquals(listOf("6", "7"), kids(abLsg, "PNT").map { it.getAttribute("A") })
        assertEquals(90.0, gpns[0].getAttribute("G").toDouble(), 0.01)
        val curvePts = kids(kids(gpns[1], "LSG").single(), "PNT")
        assertEquals(21, curvePts.size); assertEquals("6", curvePts.first().getAttribute("A")); assertEquals("7", curvePts.last().getAttribute("A"))
        assertEquals("4", gpns[2].getAttribute("F"))            // headland ring: no extensions
        // a field with nothing else in it; then the rest: the road line and the gate
        assertTrue(kids(pfds[1], "GGP").isEmpty())
        assertEquals("PocketGNSS", pfds[2].getAttribute("C"))
        assertEquals("Gate", kids(pfds[2], "PNT").single().getAttribute("B"))
        assertEquals("Road", kids(kids(pfds[2], "GGP").single(), "GPN").single().getAttribute("B"))
        // IDs unique and well formed
        val ids = listOf("PFD", "GGP", "GPN").flatMap { t -> (0 until doc.getElementsByTagName(t).length).map { (doc.getElementsByTagName(t).item(it) as Element).getAttribute("A") } }
        assertEquals(ids.size, ids.toSet().size); assertTrue(ids.all { Regex("(PFD|GGP|GPN)[0-9]+").matches(it) })
        // designators are cut to 32 characters
        assertTrue((0 until doc.getElementsByTagName("PFD").length).all { (doc.getElementsByTagName("PFD").item(it) as Element).getAttribute("C").length <= 32 })
    }

    @Test fun zipLayout() {
        val z = java.util.zip.ZipInputStream(IsoXml.zip(sample()).inputStream())
        val e = z.nextEntry!!
        assertEquals("TASKDATA/TASKDATA.XML", e.name)
        assertTrue(String(z.readBytes()).startsWith("<?xml"))
    }
}
