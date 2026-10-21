package com.rfsat.pocketgnss

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.ui.AppRoot
import com.rfsat.pocketgnss.ui.RtkTheme
import com.rfsat.pocketgnss.ui.StoreShots
import com.rfsat.pocketgnss.ui.Tab
import com.rfsat.pocketgnss.ui.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens, fed by the simulated receiver, at Galaxy S24 size.
 * Run with:  ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
 * Output:    app/build/screenshots/ (and play/ for the store set, without version)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = ScreenshotTest.PHONE)
class ScreenshotTest {
    companion object { const val PHONE = "w411dp-h891dp-xxhdpi"; const val STORE = "w360dp-h640dp-xxhdpi" }

    @get:Rule val compose = createComposeRule()
    private val engine: GnssEngine get() = (RuntimeEnvironment.getApplication() as GnssApp).engine

    /** A phone with a GNSS chip, as nearly all are (Robolectric reports none by default). */
    @org.junit.Before fun phoneWithGnss() = gnss(true)

    private fun gnss(present: Boolean) = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager)
        .setSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION_GPS, present)

    private fun simulatorAtFix(fixed: Boolean = true, survey: Boolean = false, variant: com.rfsat.pocketgnss.link.ReceiverVariant = com.rfsat.pocketgnss.link.ReceiverVariant.LTE) {
        engine.store.update { it.copy(source = LinkKind.SIMULATOR, receiverVariant = variant) }
        engine.connectSimulator()
        val t0 = System.currentTimeMillis()
        while (engine.connection.value !is Connection.Ready && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        engine.simulator!!.receiver.skip(if (fixed) 100.0 else 30.0)
        if (survey) engine.startSurvey(FixQuality.RTK_FIXED, null)
        val want = if (fixed) FixQuality.RTK_FIXED else FixQuality.SINGLE
        while (engine.fix.value?.quality != want && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(50)
        Thread.sleep(if (survey) 6000 else 1500) // satellites (GSV once a second) and survey samples
    }

    private fun shot(name: String, tab: Tab, tool: ToolId? = null, store: Boolean = false, exit: Boolean = false, scrollTo: String? = null) {
        StoreShots.hideVersion = store
        compose.setContent { RtkTheme { AppRoot(engine, {}, initialTab = tab.ordinal, initialTool = tool, showExitDialog = exit) } }
        compose.waitForIdle()
        if (scrollTo != null) { compose.onNodeWithText(scrollTo).performScrollTo(); compose.waitForIdle() }
        val path = "build/screenshots/${if (store) "play/" else ""}$name.png"
        compose.onRoot().captureRoboImage(path)
        // Google Play wants 24-bit PNG without alpha.
        if (store) {
            val f = java.io.File(path)
            val src = javax.imageio.ImageIO.read(f)
            val rgb = java.awt.image.BufferedImage(src.width, src.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply { drawImage(src, 0, 0, java.awt.Color.WHITE, null); dispose() }
            javax.imageio.ImageIO.write(rgb, "png", f)
        }
    }

    @Test fun home_phone_without_gnss() { gnss(false); engine.store.update { it.copy(source = LinkKind.USB) }; engine.disconnect(); shot("00b_home_phone_without_gnss", Tab.HOME) }
    @Test fun setup_phone_rtk() { simulatorAtFix(); engine.store.update { it.copy(source = LinkKind.PHONE) }; shot("14c_setup_phone_rtk", Tab.SETUP) }
    @Test fun home_no_receiver() { engine.store.update { it.copy(source = LinkKind.USB) }; engine.disconnect(); shot("00_home_no_receiver", Tab.HOME) }
    @Test fun home() { simulatorAtFix(); shot("01_home", Tab.HOME) }
    @Test fun live_fixed() { simulatorAtFix(); shot("02_live_rtk_fixed", Tab.LIVE) }
    @Config(qualifiers = STORE) @Test fun store_live() { simulatorAtFix(); shot("1_live", Tab.LIVE, store = true) }
    @Config(qualifiers = STORE) @Test fun store_home() { simulatorAtFix(); shot("3_home", Tab.HOME, store = true) }
    @Config(qualifiers = STORE) @Test fun store_nmea() { simulatorAtFix(); shot("4_nmea_output", Tab.TOOLS, ToolId.NMEA_CONFIG, store = true) }
    @Test fun live_single() { simulatorAtFix(fixed = false); shot("03_live_autonomous", Tab.LIVE) }
    // The Map tab is not rendered here: MapLibre's native library does not load under Robolectric. Check it on the phone.
    @Test fun tools() { simulatorAtFix(); shot("05_tools", Tab.TOOLS) }
    @Test fun survey() { simulatorAtFix(survey = true); shot("06_tool_survey", Tab.TOOLS, ToolId.SURVEY) }
    @Config(qualifiers = STORE) @Test fun store_survey() { simulatorAtFix(survey = true); shot("2_survey", Tab.TOOLS, ToolId.SURVEY, store = true) }
    @Test fun stakeout() { simulatorAtFix(); engine.markPoint("Tree 12"); shot("07_tool_stakeout", Tab.TOOLS, ToolId.STAKEOUT) }
    @Test fun nmea() { simulatorAtFix(); shot("08_tool_nmea_output", Tab.TOOLS, ToolId.NMEA_CONFIG) }
    @Test fun corrections() { simulatorAtFix(); shot("09_tool_corrections", Tab.TOOLS, ToolId.CORRECTIONS) }
    @Test fun web() { simulatorAtFix(); shot("10_tool_receiver_web_lte", Tab.TOOLS, ToolId.RECEIVER_WEB) }
    @Test fun web_wifi() { simulatorAtFix(variant = com.rfsat.pocketgnss.link.ReceiverVariant.WIFI); shot("10b_tool_receiver_web_wifi", Tab.TOOLS, ToolId.RECEIVER_WEB) }
    @Test fun home_unset() { simulatorAtFix(variant = com.rfsat.pocketgnss.link.ReceiverVariant.UNSET); shot("01b_home_version_not_set", Tab.HOME) }
    @Test fun corrections_wifi() { simulatorAtFix(variant = com.rfsat.pocketgnss.link.ReceiverVariant.WIFI); shot("09b_tool_corrections_wifi", Tab.TOOLS, ToolId.CORRECTIONS) }
    @Test fun raw() { simulatorAtFix(); shot("09c_tool_phone_raw", Tab.TOOLS, ToolId.RAW) }
    @Test fun history() { simulatorAtFix(); Thread.sleep(4000); shot("09d_tool_history", Tab.TOOLS, ToolId.HISTORY, scrollTo = "Reported accuracy against actual scatter") }
    @Test fun setup_bt_share() { simulatorAtFix(); shot("14d_setup_bt_share", Tab.SETUP, scrollTo = "Share the position over Bluetooth") }
    @Test fun home_bt_card() { simulatorAtFix(); shot("01c_home_bt_card", Tab.HOME, scrollTo = "Phone as GNSS receiver (Bluetooth)") }
    @Test fun setup_alerts() { simulatorAtFix(); shot("14e_setup_alerts", Tab.SETUP, scrollTo = "Signal") }
    @Test fun ttff() { simulatorAtFix(); shot("11_tool_ttff", Tab.TOOLS, ToolId.TTFF) }
    @Test fun stream() { simulatorAtFix(); shot("12_tool_stream", Tab.TOOLS, ToolId.STREAM) }
    @Test fun log() { simulatorAtFix(); engine.markPoint("P1"); shot("13_log", Tab.LOG) }
    @Test fun live_grid() { simulatorAtFix(); engine.store.update { it.copy(gridSystem = com.rfsat.pocketgnss.protocol.GridSystem.UTM) }; shot("02c_live_grid", Tab.LIVE) }
    @Test fun setup_grid() { simulatorAtFix(); engine.store.update { it.copy(gridSystem = com.rfsat.pocketgnss.protocol.GridSystem.UTM) }; shot("14f_setup_grid", Tab.SETUP, scrollTo = "Keep the screen on while connected") }
    private fun demoFields() {
        val f = engine.fix.value!!
        val dLat = 1 / 111_000.0; val dLon = 1 / (111_320.0 * Math.cos(Math.toRadians(f.latitude)))
        fun v(n: Double, e: Double) = com.rfsat.pocketgnss.data.Vertex(f.latitude + n * dLat, f.longitude + e * dLon)
        val ring = listOf(v(-40.0, -60.0), v(-45.0, 150.0), v(120.0, 170.0), v(140.0, -50.0))
        engine.importFile(com.rfsat.pocketgnss.data.Exporters.fieldsGeoJson(listOf(com.rfsat.pocketgnss.data.Field("North plot", ring, 0))).toByteArray(), "plots.geojson")
        engine.fieldCapture.start(com.rfsat.pocketgnss.core.CaptureMode.CORNERS, FixQuality.RTK_FIXED, 1.0, 0)
        for ((k, p) in listOf(v(-10.0, -10.0), v(-12.0, 60.0), v(30.0, 65.0)).withIndex()) {
            engine.fieldCapture.onFix(p.latitude, p.longitude, 100.0, FixQuality.RTK_FIXED, 1000L * k); engine.fieldCapture.addCorner(1000L * k + 10)
        }
    }
    @Test fun tool_field() { simulatorAtFix(); demoFields(); shot("07_tool_field", Tab.TOOLS, ToolId.FIELD) }
    @Test fun tool_field_list() { simulatorAtFix(); demoFields(); engine.fieldCapture.cancel(); shot("07b_tool_field_list", Tab.TOOLS, ToolId.FIELD) }
    @Test fun tool_guidance() {
        simulatorAtFix()
        val f = engine.fix.value!!
        val a = com.rfsat.pocketgnss.protocol.Geo.offset(f.latitude, f.longitude, -40.0, 18.12)
        val l = com.rfsat.pocketgnss.core.AbLine.fromBearing("Wheat 2026", a.first, a.second, 90.0, 6.0, 1)
        engine.guidance.add(l); engine.guidance.activate(l)
        Thread.sleep(1500)
        shot("08_tool_guidance", Tab.TOOLS, ToolId.GUIDANCE)
    }
    @Test fun tool_guidance_setup() { simulatorAtFix(); shot("08b_tool_guidance_setup", Tab.TOOLS, ToolId.GUIDANCE) }
    @Test fun tool_guidance_coverage() {
        simulatorAtFix(); demoFields(); engine.fieldCapture.cancel()
        val f = engine.fix.value!!
        val a = com.rfsat.pocketgnss.protocol.Geo.fromEnu(f.latitude, f.longitude, -40.0, -30.0)
        val l = com.rfsat.pocketgnss.core.AbLine.fromBearing("Wheat 2026", a.first, a.second, 90.0, 6.0, 1)
        engine.guidance.resetWorked(); engine.guidance.add(l); engine.guidance.activate(l)
        // four passes up and down the field, the third drifting 1 m into the second; the simulator is stopped meanwhile
        // so that only these positions reach the guidance, then reconnected for the live view
        engine.disconnect(); Thread.sleep(300)
        var ms = 1_000_000L
        for (k in 0 until 4) {
            val n = -30.0 + 6.0 * k - (if (k == 2) 1.0 else 0.0)
            for (i in 0..180) {
                val e = if (k % 2 == 0) -50.0 + i else 130.0 - i
                val p = com.rfsat.pocketgnss.protocol.Geo.fromEnu(f.latitude, f.longitude, e, n)
                engine.guidance.onFix(p.first, p.second, if (k % 2 == 0) 90.0 else 270.0, ms); ms += 200
            }
            ms += 30_000
        }
        simulatorAtFix()
        shot("08c_tool_guidance_coverage", Tab.TOOLS, ToolId.GUIDANCE, scrollTo = "Ground worked")
    }
    @Test fun tool_guidance_headland() {
        simulatorAtFix(); demoFields(); engine.fieldCapture.cancel()
        val f = engine.fields.value.first()
        val l = com.rfsat.pocketgnss.core.AbLine.headland(f.name + " headland", f.vertices.map { it.latitude to it.longitude }, 6.0, 1)
        engine.guidance.add(l); engine.guidance.activate(l)
        Thread.sleep(1500)
        shot("08d_tool_guidance_headland", Tab.TOOLS, ToolId.GUIDANCE)
    }
    @Test fun tool_guidance_curve_setup() {
        simulatorAtFix(); demoFields(); engine.fieldCapture.cancel()
        engine.guidance.startCurve()
        val f = engine.fix.value!!
        for (i in 0..120) { val p = com.rfsat.pocketgnss.protocol.Geo.fromEnu(f.latitude, f.longitude, i * 0.6, 5 * kotlin.math.sin(i / 20.0)); engine.guidance.recordCurve(p.first, p.second) }
        shot("08e_tool_guidance_curve", Tab.TOOLS, ToolId.GUIDANCE, scrollTo = "Start headland guidance")
        engine.guidance.cancelCurve()
    }
    /** EGM96 from the test resources installed as the chosen height model. */
    private fun heightModel() {
        val dir = java.io.File(RuntimeEnvironment.getApplication().filesDir, "geoid").apply { mkdirs() }
        java.io.File(dir, "us_nga_egm96_15.tif").writeBytes(javaClass.getResourceAsStream("/geoid/us_nga_egm96_15.tif")!!.readBytes())
        engine.store.update { it.copy(heightRef = com.rfsat.pocketgnss.data.HeightRef.MODEL, heightModel = "EGM96") }
    }
    @Test fun live_height() { simulatorAtFix(); heightModel(); shot("02d_live_height", Tab.LIVE) }
    @Test fun setup_height() { simulatorAtFix(); heightModel(); shot("14g_setup_height", Tab.SETUP, scrollTo = "Keep the screen on while connected") }
    @Test fun setup_ppp() { simulatorAtFix(); engine.store.update { it.copy(source = LinkKind.PHONE, phonePpp = true) }; shot("14h_setup_ppp", Tab.SETUP) }
    /** Live during a replay of real Galileo HAS corrections (PppTest data) through phone PPP. */
    @Test fun live_ppp() {
        org.junit.Assume.assumeTrue(com.rfsat.pocketgnss.rtk.RtkNative.available)
        val dir = java.io.File(engine.sessionsRoot, "raw_20230817_020000").apply { mkdirs() }
        fun res(n: String) = java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/ppp/$n")!!).readBytes()
        java.io.File(dir, "raw.obs").writeBytes(res("obe4_2023229c_5s.obs.gz"))
        java.io.File(dir, "base.rtcm3").writeBytes(res("idd2023229c.rtc.gz"))
        engine.store.update { it.copy(phonePpp = true, phoneRtk = true, heightRef = com.rfsat.pocketgnss.data.HeightRef.ELLIPSOID) }
        engine.replay(dir, 0.0)
        val t0 = System.currentTimeMillis()
        while (com.rfsat.pocketgnss.link.Replay.state.value.done < 560 && System.currentTimeMillis() - t0 < 120_000) Thread.sleep(20)
        com.rfsat.pocketgnss.link.Replay.setPaused(true)
        Thread.sleep(500)
        shot("02e_live_ppp", Tab.LIVE)
        com.rfsat.pocketgnss.link.Replay.setPaused(false)
        engine.disconnect()
    }
    @Config(qualifiers = "el-" + PHONE) @Test fun live_greek() { simulatorAtFix(); shot("20_live_el", Tab.LIVE) }
    @Config(qualifiers = "de-" + PHONE) @Test fun live_german() { simulatorAtFix(); shot("21_live_de", Tab.LIVE) }
    @Config(qualifiers = "pl-" + PHONE) @Test fun field_polish() { simulatorAtFix(); demoFields(); engine.fieldCapture.cancel(); shot("22_field_pl", Tab.TOOLS, ToolId.FIELD) }
    @Config(qualifiers = "fr-" + PHONE) @Test fun guide_french() { simulatorAtFix(); shot("23_guide_fr", Tab.TOOLS, ToolId.GUIDANCE) }
    @Test fun live_disconnect_row() { simulatorAtFix(); shot("02b_live_top", Tab.LIVE) }
    @Test fun setup() { simulatorAtFix(); shot("14_setup", Tab.SETUP) }
    @Test fun setup_relay() { simulatorAtFix(); engine.store.update { it.copy(ntrip = com.rfsat.pocketgnss.data.CasterProfiles.presets[0].config, activeProfile = com.rfsat.pocketgnss.data.CasterProfiles.presets[0].name) }; shot("14b_setup_relay", Tab.SETUP, scrollTo = "NTRIP relay through the phone") }
    @Test fun exit() { simulatorAtFix(); shot("15_exit", Tab.HOME, exit = true) }
}
