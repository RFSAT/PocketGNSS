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
    @Test fun ttff() { simulatorAtFix(); shot("11_tool_ttff", Tab.TOOLS, ToolId.TTFF) }
    @Test fun stream() { simulatorAtFix(); shot("12_tool_stream", Tab.TOOLS, ToolId.STREAM) }
    @Test fun log() { simulatorAtFix(); engine.markPoint("P1"); shot("13_log", Tab.LOG) }
    @Test fun live_disconnect_row() { simulatorAtFix(); shot("02b_live_top", Tab.LIVE) }
    @Test fun setup() { simulatorAtFix(); shot("14_setup", Tab.SETUP) }
    @Test fun setup_relay() { simulatorAtFix(); engine.store.update { it.copy(ntrip = com.rfsat.pocketgnss.data.CasterProfiles.presets[0].config, activeProfile = com.rfsat.pocketgnss.data.CasterProfiles.presets[0].name) }; shot("14b_setup_relay", Tab.SETUP, scrollTo = "NTRIP relay through the phone") }
    @Test fun exit() { simulatorAtFix(); shot("15_exit", Tab.HOME, exit = true) }
}
