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

    @Test fun presetsHavePublicHostsOnly() {
        assertTrue(CasterProfiles.presets.all { it.config.host.isNotBlank() && it.config.user.isEmpty() && it.config.password.isEmpty() })
    }
}
