package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.RelayArbitration
import com.rfsat.pocketgnss.core.RelayRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.13.1: the phone relay and a receiver's own NTRIP client never feed the receiver at the same time. */
class RelayArbitrationTest {
    private fun run(a: RelayArbitration, from: Long, to: Long, active: Boolean = true, inUse: (Long) -> Boolean) {
        var t = from
        while (t <= to) { a.step(t, active, inUse(t)); t += 1000 }
    }

    @Test fun receiverWithWorkingOwnCorrectionsKeepsRelayOff() {
        val a = RelayArbitration()
        a.step(0, true, false); assertEquals(RelayRole.CHECKING, a.role)
        run(a, 1000, 10_000) { it >= 5000 }
        assertEquals(RelayRole.RECEIVER_OWN, a.role)
        run(a, 11_000, 600_000) { true }
        assertEquals(RelayRole.RECEIVER_OWN, a.role)
    }

    @Test fun relayFeedsWhenReceiverHasNoCorrectionsAndTakesOverWhenTheyStop() {
        val a = RelayArbitration()
        run(a, 0, 30_000) { false }
        assertEquals(RelayRole.FEEDING, a.role)                 // nothing of its own after 25 s
        val b = RelayArbitration()
        run(b, 0, 10_000) { true }
        assertEquals(RelayRole.RECEIVER_OWN, b.role)
        run(b, 11_000, 30_000) { false }                        // own corrections stopped at 11 s
        assertEquals(RelayRole.RECEIVER_OWN, b.role)            // not yet: 20 s grace
        run(b, 31_000, 33_000) { false }
        assertEquals(RelayRole.FEEDING, b.role)
    }

    @Test fun relayThatDoesNotHelpGivesWayWithGrowingWaits() {
        val a = RelayArbitration()
        run(a, 0, 26_000) { false }
        assertEquals(RelayRole.FEEDING, a.role)
        val fedAt = a.sinceMs
        run(a, 27_000, fedAt + 60_000) { false }
        assertEquals(RelayRole.FEEDING, a.role)                 // waits a full minute
        run(a, fedAt + 61_000, fedAt + 62_000) { false }
        assertEquals(RelayRole.CHECKING, a.role)                // gives the receiver's own source a clean try
        assertEquals(120_000L, a.feedWaitMs())                  // next wait doubled
    }

    @Test fun relayOffOrReceiverWithoutOwnClientIsDirect() {
        val a = RelayArbitration()
        run(a, 0, 10_000) { true }
        assertEquals(RelayRole.RECEIVER_OWN, a.role)
        a.step(11_000, false, false)
        assertEquals(RelayRole.DIRECT, a.role)
    }
}
