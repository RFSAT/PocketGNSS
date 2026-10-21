package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AlertEvent
import com.rfsat.pocketgnss.core.FixAlertLogic
import com.rfsat.pocketgnss.protocol.FixQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTest {
    /** Feeds one update per second from [from] for [secs] seconds; returns all events. */
    private fun FixAlertLogic.run(from: Long, secs: Int, q: FixQuality, age: Double? = 1.0): List<AlertEvent> =
        (0 until secs).flatMap { update((from + it) * 1000, q, age) }

    @Test fun noAlarmBeforeTheLevelWasReached() {
        val a = FixAlertLogic()
        assertTrue(a.run(0, 60, FixQuality.RTK_FLOAT).isEmpty()) // never fixed: nothing to lose
    }

    @Test fun lostOnceThenRegained() {
        val a = FixAlertLogic()
        assertTrue(a.run(0, 10, FixQuality.RTK_FIXED).isEmpty())
        assertTrue(a.run(10, 2, FixQuality.RTK_FLOAT).isEmpty())           // 2 s dip: debounced
        assertTrue(a.run(12, 5, FixQuality.RTK_FIXED).isEmpty())
        assertEquals(listOf(AlertEvent.FIX_LOST), a.run(17, 30, FixQuality.RTK_FLOAT)) // once, not every second
        assertTrue(a.fixLost)
        assertEquals(listOf(AlertEvent.FIX_REGAINED), a.run(47, 10, FixQuality.RTK_FIXED))
    }

    @Test fun floatThresholdAndNoFix() {
        val a = FixAlertLogic(threshold = FixQuality.RTK_FLOAT)
        a.run(0, 10, FixQuality.RTK_FLOAT)
        assertTrue(a.run(10, 10, FixQuality.RTK_FIXED).isEmpty())          // better is fine
        assertEquals(listOf(AlertEvent.FIX_LOST), a.run(20, 10, FixQuality.NONE)) // no position at all
    }

    @Test fun staleCorrections() {
        val a = FixAlertLogic(staleSec = 10.0)
        assertTrue(a.run(0, 10, FixQuality.SINGLE, null).isEmpty())         // never had corrections: no alarm
        a.run(10, 5, FixQuality.RTK_FIXED, 1.0)
        assertEquals(listOf(AlertEvent.CORRECTIONS_STALE), a.run(15, 10, FixQuality.RTK_FIXED, 25.0))
        assertEquals(listOf(AlertEvent.CORRECTIONS_BACK), a.run(25, 10, FixQuality.RTK_FIXED, 1.0))
    }
}
