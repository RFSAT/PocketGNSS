package com.rfsat.pocketgnss.data

/**
 * "Recommended settings" for phone RTK (Setup > Receiver connection > Phone): what worked best in the October 2026
 * tests with a Galaxy S24, applied with the caster accounts the user has saved (accounts themselves are not changed).
 *
 * - RTK with a base station, not PPP: at Korfos under open sky RTK ended 0.43 m from an EOS Arrow Gold fix, while
 *   Galileo HAS PPP on the phone did not converge on a roof or a dashboard.
 * - Moving (kinematic) mode and tuning to this phone (PhoneTuning).
 * - Main stream: the saved Metrica SmartNet account on MSM_VRS, a virtual base next to the rover (needs the GGA).
 *   Without a Metrica account the current RTK profile is kept.
 * - Ephemeris stream: BCEP00BKG0 with a saved BKG account (igs-ip.net, products.igs-ip.net or euref-ip.net), never the
 *   main stream's account: Metrica's MSM_NEAR in Athens sent no ephemerides at all (30 October 2026).
 */
object RecommendedSetup {
    const val METRICA_HOST = "gr.nrtk.eu"
    const val METRICA_MOUNT = "MSM_VRS"
    const val EPH_MOUNT = "BCEP00BKG0"
    /** Casters of BKG that serve BCEP00BKG0, in order of preference. */
    val EPH_HOSTS = listOf("igs-ip.net", "products.igs-ip.net", "euref-ip.net")

    enum class Note { NO_RTK_PROFILE, NO_EPH_PROFILE }

    /** The new settings, the main and ephemeris profiles chosen (null = none), and what could not be set. */
    data class Result(val settings: AppSettings, val mainProfile: String?, val ephProfile: String?, val notes: List<Note>)

    private fun host(c: com.rfsat.pocketgnss.protocol.NtripConfig) = c.host.trim().lowercase()

    /** A profile for RTK base data: not a PPP (SSR) or ephemeris-only stream, not Galileo HAS. */
    private fun isRtk(p: CasterProfile): Boolean {
        val m = p.config.mountpoint.uppercase()
        return p.config.host.isNotBlank() && !m.startsWith("SSR") && !m.startsWith("BCEP") && host(p.config) != "ntrip.gsc-europa.eu" &&
            host(p.config) != "products.igs-ip.net"
    }

    fun apply(s: AppSettings): Result {
        val notes = ArrayList<Note>()
        var out = s.copy(phoneRtk = true, phonePpp = false, phoneRtkStatic = false, phoneRtkAuto = true)

        // Main stream: Metrica with an account on MSM_VRS, else the current RTK profile, else the first RTK profile with an account.
        val metrica = s.profiles.firstOrNull { host(it.config) == METRICA_HOST && it.config.user.isNotBlank() }
        val main = when {
            metrica != null -> CasterProfile(metrica.name, metrica.config.copy(mountpoint = METRICA_MOUNT, ggaIntervalSec = 10))
            else -> s.profiles.firstOrNull { it.name == s.activeProfile && isRtk(it) && it.config.mountpoint.isNotBlank() }
                ?: s.profiles.firstOrNull { isRtk(it) && it.config.user.isNotBlank() && it.config.mountpoint.isNotBlank() }
        }
        if (main != null) out = out.copy(ntrip = main.config, activeProfile = main.name,
            profiles = CasterProfiles.remember(out.profiles, main.name, main.config))
        else notes += Note.NO_RTK_PROFILE

        // Ephemeris stream: a BKG account, never the account of the main stream (one login per account).
        val mainCfg = out.ntrip
        val eph = EPH_HOSTS.firstNotNullOfOrNull { h ->
            s.profiles.firstOrNull { host(it.config) == h && it.config.user.isNotBlank() &&
                !(host(it.config) == host(mainCfg) && it.config.user == mainCfg.user && it.config.port == mainCfg.port) }
        }
        if (eph != null) out = out.copy(ephStream = true, ephProfile = eph.name, ephMount = EPH_MOUNT)
        else notes += Note.NO_EPH_PROFILE
        return Result(out, main?.name, eph?.name, notes)
    }
}
