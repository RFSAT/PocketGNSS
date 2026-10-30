package com.rfsat.pocketgnss.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Mountpoint
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

/**
 * Keeps the ciphertext of each password once encrypted or decrypted, so saving the settings again reuses it: the
 * Keystore is not asked on every save, and a moment when it cannot encrypt (it can fail transiently) no longer replaces
 * a stored password with nothing. Failures are reported through [onFail] ("encrypt" / "decrypt"), never the password.
 */
class CachingCipher(private val inner: SecretCipher, private val onFail: (String) -> Unit = {}) : SecretCipher {
    private val byPlain = HashMap<String, String>()

    @Synchronized override fun encrypt(plain: String): String? =
        byPlain[plain] ?: inner.encrypt(plain)?.also { byPlain[plain] = it } ?: run { onFail("encrypt"); null }

    @Synchronized override fun decrypt(stored: String): String? =
        inner.decrypt(stored)?.also { byPlain[it] = stored } ?: run { onFail("decrypt"); null }
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
     * Edits of the connection (host, mountpoint, user, password...) while a caster profile is selected are saved to
     * that profile, so its login is remembered without pressing Save; selecting a public preset and typing the
     * account turns it into a saved profile of the same name. Choosing another profile (the active name changes at
     * the same time) or no profile saves nothing.
     */
    fun keepInSync(before: AppSettings, after: AppSettings): AppSettings {
        val name = after.activeProfile
        if (name.isEmpty() || name != before.activeProfile || after.ntrip == before.ntrip) return after
        if (after.profiles.firstOrNull { it.name == name }?.config == after.ntrip) return after
        return after.copy(profiles = remember(after.profiles, name, after.ntrip))
    }

    /** [profiles] with the profile [name] set to [cfg], in its place, or added at the end. */
    fun remember(profiles: List<CasterProfile>, name: String, cfg: NtripConfig): List<CasterProfile> =
        if (profiles.any { it.name == name }) profiles.map { if (it.name == name) CasterProfile(name, cfg) else it }
        else profiles + CasterProfile(name, cfg)

    /**
     * Repair for profiles saved before 1.12.1 without their password: one with the same caster and user as the
     * current connection takes the connection's password.
     */
    fun fillPasswords(profiles: List<CasterProfile>, current: NtripConfig): List<CasterProfile> = profiles.map { p ->
        val c = p.config
        if (c.password.isEmpty() && current.password.isNotEmpty() && c.user.isNotEmpty() && c.user == current.user &&
            c.host.equals(current.host, ignoreCase = true) && c.port == current.port) p.copy(config = c.copy(password = current.password)) else p
    }

    /**
     * Starting points with public hosts and ports, checked against the casters' sourcetables (October 2026). The user
     * adds the account (user, password) and can pick another mountpoint with Tools > Corrections > Get mountpoints.
     *
     * Metrica / HxGN SmartNet Greece (Leica Spider, NTRIP v2, login required): network streams VRS_Rtcm3 and MAX
     * (GPS+GLO), MSM_VRS and MSM_iMAX (GPS+GLO+GAL+BDS, formerly "MSM_i-MAX"), nearest single base NEAR and MSM_NEAR.
     * All of them need the rover's GGA. EUREF / IGS single stations need no GGA but must be within about 20-30 km for
     * RTK (in Greece: DYNG00GRC0 Dionysos near Athens; nothing close to Pella).
     */
    val presets = listOf(
        CasterProfile("Metrica SmartNet GR (VRS)", NtripConfig(host = "gr.nrtk.eu", port = 7801, mountpoint = "MSM_VRS", ggaIntervalSec = 10)),
        CasterProfile("Metrica SmartNet GR (nearest base)", NtripConfig(host = "gr.nrtk.eu", port = 7801, mountpoint = "MSM_NEAR", ggaIntervalSec = 10)),
        CasterProfile("EUREF-IP (BKG)", NtripConfig(host = "euref-ip.net", port = 2101, ggaIntervalSec = 0)),
        CasterProfile("EUREF-IP (ROB, Belgium)", NtripConfig(host = "www.euref-ip.be", port = 2101, ggaIntervalSec = 0)),
        CasterProfile("IGS-IP (BKG)", NtripConfig(host = "igs-ip.net", port = 2101, ggaIntervalSec = 0)),
        // Galileo HAS Internet Data Distribution (EUSPA, after HAS IDD registration): NTRIP 2 over TLS on port 443,
        // SSR orbit / clock / bias corrections for PPP (HAS / SSR) mode.
        CasterProfile("Galileo HAS (EUSPA)", NtripConfig(host = "ntrip.gsc-europa.eu", port = 443, mountpoint = "SSRA00EUH0",
            version2 = true, tls = true, ggaIntervalSec = 0)),
        // IGS real-time products (free registration at igs-ip.net): PPP correction streams SSRA… and the broadcast
        // ephemeris stream BCEP00BKG0. The mountpoint is chosen from the caster's sourcetable (Get mountpoints).
        CasterProfile("IGS products (PPP)", NtripConfig(host = "products.igs-ip.net", port = 2101, ggaIntervalSec = 0)),
        CasterProfile("RTK2go (community)", NtripConfig(host = "rtk2go.com", port = 2101, ggaIntervalSec = 10)),
    )

    /** Plain-language notes for well-known mountpoints, shown next to the sourcetable entry. */
    fun mountNote(name: String): String? = when (name) {
        "MSM_VRS", "VRS_GNSS" -> "Virtual reference station · GPS+GLO+GAL+BDS"
        "VRS_Rtcm3" -> "Virtual reference station · GPS+GLO"
        "MSM_iMAX", "MSM_i-MAX", "i-MAXGNSS" -> "Master-auxiliary (i-MAX) · GPS+GLO+GAL+BDS"
        "MAX" -> "Master-auxiliary (MAX) · GPS+GLO"
        "MSM_NEAR", "NEAR_GNSS" -> "Nearest single base · GPS+GLO+GAL+BDS"
        "NEAR" -> "Nearest single base · GPS+GLO"
        else -> when {
            // IGS real-time naming: TTTT XX AAA F (product type, scheme, analysis centre, 0 = RTCM SSR / 1 = IGS SSR)
            name.startsWith("SSRA") -> "PPP corrections (SSR, antenna phase centre): for PPP (HAS / SSR) mode"
            name.startsWith("SSRC") -> "SSR to the centre of mass: not usable here, choose an SSRA… stream"
            name.startsWith("BCEP") -> "Broadcast ephemerides only: for the ephemeris stream"
            name.startsWith("IONO") -> "Ionosphere model only"
            else -> null
        }
    }

    /**
     * Order for the mountpoint list: usable RTCM 3 streams first; within them network / nearest-base streams (they
     * follow the rover, so their nominal coordinates are meaningless) and then single stations nearest first.
     */
    fun sortMountpoints(list: List<Mountpoint>, lat: Double?, lon: Double?): List<Mountpoint> = list.sortedWith(
        compareBy<Mountpoint>({ !isRtcm3(it.format) }, { !it.needsGga }, { m ->
            if (lat != null && lon != null && m.latitude != null && m.longitude != null) Geo.distance(lat, lon, m.latitude, m.longitude) else 1e12
        }, { it.name }))

    /**
     * Whether the sourcetable says the stream sends broadcast ephemerides (RTCM 1019 GPS, 1020 GLONASS, 1042 BeiDou,
     * 1044 QZSS, 1045 / 1046 Galileo in its format details, e.g. "1004(1),1019(60)"); null when it gives no details.
     */
    fun carriesEphemerides(m: Mountpoint): Boolean? {
        val types = Regex("\\b(\\d{4})\\b").findAll(m.formatDetails).map { it.groupValues[1].toInt() }.toList()
        if (types.isEmpty()) return if (m.name.startsWith("BCEP")) true else null
        return types.any { it in EPHEMERIS_TYPES }
    }
    private val EPHEMERIS_TYPES = setOf(1019, 1020, 1042, 1044, 1045, 1046)

    /** True when the stream is RTCM 3 (the only correction format u-blox F9 and RTKLIB phone RTK accept here). */
    fun isRtcm3(format: String) = format.trim().uppercase().replace(" ", "").startsWith("RTCM3")
}
