package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.data.Json
import com.rfsat.pocketgnss.ui.map.MapLayer
import com.rfsat.pocketgnss.ui.map.MapStyles
import com.rfsat.pocketgnss.ui.map.OwnMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineMapTest {
    @Test fun styleWithOwnMapIsValid() {
        for (l in MapLayer.entries) {
            val st = Json(MapStyles.styleFor(l, "/data/user/0/com.PocketGNSS/files/maps/own_1.mbtiles")).parse() as Map<*, *>
            val sources = st["sources"] as Map<*, *>
            assertEquals("mbtiles:///data/user/0/com.PocketGNSS/files/maps/own_1.mbtiles", (sources["own"] as Map<*, *>)["url"])
            assertEquals(listOf("base", "own"), (st["layers"] as List<*>).map { (it as Map<*, *>)["id"] })
            assertEquals(MapStyles.styleFor(l), MapStyles.styleFor(l, null))
        }
    }

    @Test fun mbtilesInfo() {
        val f = File.createTempFile("tiny", ".mbtiles")
        f.writeBytes(javaClass.getResourceAsStream("/tiny.mbtiles")!!.readBytes())
        val i = OwnMap.info(f)!!
        assertEquals("Farm ortho", i.name); assertTrue(i.raster); assertEquals(14, i.minZoom); assertEquals(18, i.maxZoom)
        assertEquals(40.8, i.bounds!![3], 1e-9)
        f.delete()
    }
}
