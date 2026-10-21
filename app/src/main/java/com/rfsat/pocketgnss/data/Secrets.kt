package com.rfsat.pocketgnss.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rfsat.pocketgnss.protocol.NtripConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts / decrypts short secrets (NTRIP passwords). */
interface SecretCipher {
    /** Ciphertext as text, or null when it cannot be encrypted (then nothing is stored). */
    fun encrypt(plain: String): String?
    fun decrypt(stored: String): String?
}

/**
 * AES-256-GCM with a key held in the Android Keystore: the key never leaves the phone's secure hardware and is not
 * backed up, so stored caster passwords are unreadable outside this app on this phone. Format "k1:<iv>:<ciphertext>".
 * If the Keystore is unavailable, the password is not stored at all (never in plain text).
 */
object KeystoreCipher : SecretCipher {
    private const val ALIAS = "pocketgnss.ntrip.credentials"
    private const val PROVIDER = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        "k1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (_: Exception) { null }

    override fun decrypt(stored: String): String? = try {
        val p = stored.split(':')
        if (p.size != 3 || p[0] != "k1") null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[1], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(p[2], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (_: Exception) { null }
}

/** A saved NTRIP caster account: Metrica VRS, EUREF-IP, BKG, our own EOS Arrow Gold base... */
data class CasterProfile(val name: String, val config: NtripConfig)

/**
 * Text codec for caster profiles: one profile per line, tab-separated, the password field encrypted with [cipher].
 * Pure apart from the cipher, so it is unit-tested with a fake one.
 */
object CasterProfiles {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun encode(list: List<CasterProfile>, cipher: SecretCipher): String = list.joinToString("\n") { p ->
        val c = p.config
        listOf(clean(p.name), clean(c.host), c.port.toString(), clean(c.mountpoint), clean(c.user),
            if (c.password.isEmpty()) "" else (cipher.encrypt(c.password) ?: ""),
            if (c.version2) "2" else "1", if (c.tls) "tls" else "", c.ggaIntervalSec.toString()).joinToString("\t")
    }

    fun decode(text: String?, cipher: SecretCipher): List<CasterProfile> = text.orEmpty().lineSequence().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 9 || f[0].isBlank()) null else CasterProfile(f[0], NtripConfig(
            host = f[1], port = f[2].toIntOrNull() ?: 2101, mountpoint = f[3], user = f[4],
            password = if (f[5].isEmpty()) "" else cipher.decrypt(f[5]) ?: "",
            version2 = f[6] != "1", tls = f[7] == "tls", ggaIntervalSec = f[8].toIntOrNull() ?: 10))
    }.toList()

    /**
     * Starting points with public hosts. Metrica (HxGN SmartNet Greece, VRS) and our own EOS Arrow Gold base are added
     * by the user with the details from the service contract / base set-up.
     */
    val presets = listOf(
        CasterProfile("EUREF-IP (BKG)", NtripConfig(host = "euref-ip.net", port = 2101, ggaIntervalSec = 0)),
        CasterProfile("IGS-IP (BKG)", NtripConfig(host = "igs-ip.net", port = 2101, ggaIntervalSec = 0)),
        CasterProfile("RTK2go (community)", NtripConfig(host = "rtk2go.com", port = 2101, ggaIntervalSec = 10)),
    )
}
