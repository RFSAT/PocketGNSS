package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.data.CasterProfile
import com.rfsat.pocketgnss.data.CasterProfiles
import com.rfsat.pocketgnss.data.SecretCipher
import com.rfsat.pocketgnss.protocol.NtripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasterProfileTest {
    private fun mount(name: String, details: String) =
        com.rfsat.pocketgnss.protocol.Mountpoint(name, name, "RTCM 3.2", details, 2, "GPS+GAL", "", "GRC", 38.0, 23.7, false, 0, "B", false, null)

    @Test fun ephemerisStreamsAreRecognisedFromTheSourcetable() {
        assertEquals(true, CasterProfiles.carriesEphemerides(mount("MSM_VRS", "1006(10),1019(5),1046(5),1077(1),1097(1)")))
        assertEquals(false, CasterProfiles.carriesEphemerides(mount("NEAR", "1004(1),1006(10),1012(1)")))
        assertEquals(null, CasterProfiles.carriesEphemerides(mount("ATHA_4102", "")))
        assertEquals(true, CasterProfiles.carriesEphemerides(mount("BCEP00BKG0", "")))
    }

    /** Stand-in for the Keystore cipher: reversible, text-safe output (like Base64), not plain text. */
    private val fake = object : SecretCipher {
        override fun encrypt(plain: String) = "k1:" + plain.reversed().toByteArray().joinToString("") { "%02x".format(it) }
        override fun decrypt(stored: String) = String(stored.removePrefix("k1:").chunked(2).map { it.toInt(16).toByte() }.toByteArray()).reversed()
    }
    private val broken = object : SecretCipher {
        override fun encrypt(plain: String): String? = null
        override fun decrypt(stored: String): String? = null
    }
    private val profiles = listOf(
        CasterProfile("Metrica VRS", NtripConfig("caster.example", 2101, "VRS_RTCM32", "user1", "s3cret\tpw", version2 = true, tls = false, ggaIntervalSec = 10)),
        CasterProfile("EOS base", NtripConfig("192.168.1.20", 2102, "EOS1", "", "", version2 = false, tls = true, ggaIntervalSec = 0)),
    )

    @Test fun roundTripKeepsEverything() {
        val text = CasterProfiles.encode(profiles, fake)
        assertFalse("password must not be stored in plain text", text.contains("s3cret"))
        val back = CasterProfiles.decode(text, fake)
        assertEquals(2, back.size)
        assertEquals("s3cret\tpw", back[0].config.password)
        assertEquals(profiles[1], back[1])
        assertEquals(10, back[0].config.ggaIntervalSec); assertTrue(back[1].config.tls); assertFalse(back[1].config.version2)
    }

    @Test fun withoutKeystoreThePasswordIsDroppedNotStoredInClear() {
        val text = CasterProfiles.encode(profiles, broken)
        assertFalse(text.contains("s3cret"))
        val back = CasterProfiles.decode(text, fake)
        assertEquals("", back[0].config.password)
        assertEquals("user1", back[0].config.user)
    }

    @Test fun editsAreSavedToTheSelectedProfile() {
        val start = com.rfsat.pocketgnss.data.AppSettings(profiles = profiles, activeProfile = "Metrica VRS", ntrip = profiles[0].config)
        // typing a new password while the profile is selected: saved to it, in its place
        val typed = CasterProfiles.keepInSync(start, start.copy(ntrip = start.ntrip.copy(password = "new")))
        assertEquals("new", typed.profiles[0].config.password); assertEquals(listOf("Metrica VRS", "EOS base"), typed.profiles.map { it.name })
        // a preset selected and its account typed becomes a saved profile of that name
        val preset = CasterProfiles.presets.first { it.name == "EUREF-IP (ROB, Belgium)" }
        val chosen = CasterProfiles.keepInSync(typed, typed.copy(ntrip = preset.config, activeProfile = preset.name))
        assertEquals(2, chosen.profiles.size)                  // choosing saves nothing
        val withLogin = CasterProfiles.keepInSync(chosen, chosen.copy(ntrip = chosen.ntrip.copy(user = "artur", password = "pw", mountpoint = "BCEP00BKG0")))
        val rob = withLogin.profiles.single { it.name == preset.name }.config
        assertEquals("www.euref-ip.be", rob.host); assertEquals("artur", rob.user); assertEquals("pw", rob.password)
        // switching back to Metrica keeps both logins
        val back = CasterProfiles.keepInSync(withLogin, withLogin.copy(ntrip = withLogin.profiles[0].config, activeProfile = "Metrica VRS"))
        assertEquals("new", back.ntrip.password); assertEquals("pw", back.profiles.single { it.name == preset.name }.config.password)
        // without a selected profile edits stay in the connection only
        val none = start.copy(activeProfile = "")
        assertEquals(profiles, CasterProfiles.keepInSync(none, none.copy(ntrip = none.ntrip.copy(password = "x"))).profiles)
    }

    @Test fun profilesSavedWithoutPasswordTakeItFromTheSameAccount() {
        val noPw = profiles[0].copy(config = profiles[0].config.copy(password = ""))
        val other = CasterProfile("Other user", profiles[0].config.copy(user = "user2", password = ""))
        val fixed = CasterProfiles.fillPasswords(listOf(noPw, other, profiles[1]), profiles[0].config)
        assertEquals("s3cret\tpw", fixed[0].config.password)
        assertEquals("", fixed[1].config.password)
        assertEquals(profiles[1], fixed[2])
    }

    @Test fun presetsHavePublicHostsOnly() {
        assertTrue(CasterProfiles.presets.all { it.config.host.isNotBlank() && it.config.user.isEmpty() && it.config.password.isEmpty() })
    }

    @Test fun aFailingKeystoreDoesNotWipeStoredPasswords() {
        var fail = false; val failures = ArrayList<String>()
        val inner = object : com.rfsat.pocketgnss.data.SecretCipher {
            override fun encrypt(plain: String) = if (fail) null else "enc:" + plain.reversed()
            override fun decrypt(stored: String) = if (fail || !stored.startsWith("enc:")) null else stored.removePrefix("enc:").reversed()
        }
        val c = com.rfsat.pocketgnss.data.CachingCipher(inner) { failures.add(it) }
        val stored = c.encrypt("s3cret!")!!
        fail = true
        org.junit.Assert.assertEquals(stored, c.encrypt("s3cret!"))          // saved again: same ciphertext, not ""
        org.junit.Assert.assertNull(c.encrypt("new"))                         // a new password cannot be stored...
        org.junit.Assert.assertEquals(listOf("encrypt"), failures)            // ...and that is reported
        fail = false
        val c2 = com.rfsat.pocketgnss.data.CachingCipher(inner)
        org.junit.Assert.assertEquals("s3cret!", c2.decrypt(stored))        // read back after a restart
        fail = true
        org.junit.Assert.assertEquals(stored, c2.encrypt("s3cret!"))         // and kept on the next save
    }
}
