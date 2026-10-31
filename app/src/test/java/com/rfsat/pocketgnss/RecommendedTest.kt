package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.data.AppSettings
import com.rfsat.pocketgnss.data.CasterProfile
import com.rfsat.pocketgnss.data.RecommendedSetup
import com.rfsat.pocketgnss.protocol.NtripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedTest {
    private val metrica = CasterProfile("Metrica", NtripConfig(host = "gr.nrtk.eu", port = 7801, mountpoint = "MSM_NEAR", user = "u1", password = "p1"))
    private val bkg = CasterProfile("BKG", NtripConfig(host = "igs-ip.net", port = 2101, mountpoint = "BCEP00BKG0", user = "u2", password = "p2", ggaIntervalSec = 0))
    private val has = CasterProfile("HAS", NtripConfig(host = "ntrip.gsc-europa.eu", port = 443, mountpoint = "SSRA00EUH0", user = "u3", tls = true))

    @Test fun metricaOnVrsWithTheBkgEphemerisStream() {
        val s = AppSettings(phonePpp = true, phoneRtkStatic = true, profiles = listOf(has, metrica, bkg), activeProfile = "HAS", ntrip = has.config,
            ephStream = true, ephProfile = "HAS", ephMount = "SSRA00EUH0")
        val r = RecommendedSetup.apply(s)
        assertFalse(r.settings.phonePpp); assertFalse(r.settings.phoneRtkStatic); assertTrue(r.settings.phoneRtk)
        assertEquals("Metrica", r.settings.activeProfile)
        assertEquals("MSM_VRS", r.settings.ntrip.mountpoint)
        assertEquals(10, r.settings.ntrip.ggaIntervalSec)
        assertEquals("p1", r.settings.ntrip.password)
        assertEquals("MSM_VRS", r.settings.profiles.first { it.name == "Metrica" }.config.mountpoint)
        assertTrue(r.settings.ephStream); assertEquals("BKG", r.settings.ephProfile); assertEquals("BCEP00BKG0", r.settings.ephMount)
        assertTrue(r.notes.isEmpty())
    }

    @Test fun missingAccountsAreReported() {
        val r = RecommendedSetup.apply(AppSettings(profiles = listOf(has)))
        assertEquals(listOf(RecommendedSetup.Note.NO_RTK_PROFILE, RecommendedSetup.Note.NO_EPH_PROFILE), r.notes)
        assertEquals(null, r.mainProfile)
    }

    @Test fun theEphemerisStreamNeverUsesTheMainAccount() {
        // only a BKG account, used for an EUREF station as the main stream: no second login with it
        val station = CasterProfile("EUREF", NtripConfig(host = "igs-ip.net", port = 2101, mountpoint = "DYNG00GRC0", user = "u2", ggaIntervalSec = 0))
        val r = RecommendedSetup.apply(AppSettings(profiles = listOf(station), activeProfile = "EUREF", ntrip = station.config))
        assertEquals("EUREF", r.mainProfile)
        assertEquals(null, r.ephProfile)
        assertEquals(listOf(RecommendedSetup.Note.NO_EPH_PROFILE), r.notes)
    }
}
