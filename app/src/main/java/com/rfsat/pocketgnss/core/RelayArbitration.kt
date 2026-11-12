package com.rfsat.pocketgnss.core

/**
 * Decides who corrects a receiver that has its own NTRIP client while the phone relay is on (see [RelayRole]).
 * Pure Kotlin, unit-tested: [step] is called once a second with whether the receiver is using corrections now and
 * returns the role to switch to, or null to keep the current one.
 */
class RelayArbitration {
    var role = RelayRole.DIRECT; private set
    var sinceMs = 0L; private set
    private var inUseMs = 0L
    private var feedFails = 0

    fun reset() { role = RelayRole.DIRECT; sinceMs = 0; inUseMs = 0; feedFails = 0 }

    /** [active]: relay on, receiver writable and with its own NTRIP client. */
    fun step(now: Long, active: Boolean, inUse: Boolean): RelayRole? {
        if (!active) return if (role != RelayRole.DIRECT) go(RelayRole.DIRECT, now) else null
        if (inUse) inUseMs = now
        return when (role) {
            RelayRole.DIRECT -> go(RelayRole.CHECKING, now)
            RelayRole.CHECKING -> when {
                inUse -> { feedFails = 0; go(RelayRole.RECEIVER_OWN, now) }
                now - sinceMs > CHECK_MS -> go(RelayRole.FEEDING, now)
                else -> null
            }
            RelayRole.RECEIVER_OWN -> if (now - inUseMs > OWN_LOST_MS) go(RelayRole.FEEDING, now) else null
            RelayRole.FEEDING -> {
                if (inUse) { feedFails = 0; null }
                else if (now - sinceMs > feedWaitMs() && now - inUseMs > FEED_LOST_MS) { feedFails++; go(RelayRole.CHECKING, now) }
                else null
            }
        }
    }

    /** 1 min for the relay's corrections to be used; twice as long after each failed try, at most 10 min. */
    fun feedWaitMs(): Long = (FEED_WAIT_MS shl feedFails.coerceAtMost(4)).coerceAtMost(600_000L)

    private fun go(r: RelayRole, now: Long): RelayRole { role = r; sinceMs = now; return r }

    companion object {
        const val CHECK_MS = 25_000L
        const val OWN_LOST_MS = 20_000L
        const val FEED_WAIT_MS = 60_000L
        const val FEED_LOST_MS = 30_000L
    }
}
