package com.rfsat.pocketgnss

import com.rfsat.pocketgnss.core.AccuracyCompare
import com.rfsat.pocketgnss.core.GnssModel
import com.rfsat.pocketgnss.protocol.Nmea
import com.rfsat.pocketgnss.protocol.Quectel
import com.rfsat.pocketgnss.protocol.QuectelEpe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.13.3: GST and $PQTMEPE recorded side by side at each GGA epoch. */
class AccuracyCompareTest {
    private val fix = GnssModel().accept(Nmea.parse(Quectel.command("GNGGA,120000.00,3758.0000,N,02345.0000,E,2,30,0.8,100.0,M,30.0,M,2.0,0123"))!!)!!

    private fun row(gstLat: Double?, gstLon: Double?, epe2d: Double?, src: AccuracyCompare.Source = AccuracyCompare.Source.GST) =
        AccuracyCompare.Row(1000, fix, gstLat?.let { 200L }, 0.9, gstLat, gstLon, 0.8, epe2d?.let { 100L },
            epe2d?.let { QuectelEpe(0.3, 0.3, 0.5, it, 0.6) }, null, 0.65, src)

    @Test fun rowDifferenceRatioAndCsv() {
        val r = row(0.30, 0.40, 0.40)
        assertEquals(0.5, r.gstH!!, 1e-12)
        assertEquals(0.1, r.diff!!, 1e-12)
        assertEquals(1.25, r.ratio!!, 1e-12)
        assertEquals(AccuracyCompare.HEADER.split(',').size, r.csv().split(',').size)
        assertNull(row(0.3, 0.4, null).diff)
    }

    @Test fun summaryOverEpochs() {
        val s = AccuracyCompare.Stats()
        s.add(row(0.30, 0.40, 0.40))                 // +0.10
        s.add(row(0.60, 0.80, 0.70))                 // +0.30
        s.add(row(0.06, 0.08, 0.08))                 // +0.02
        s.add(row(0.3, 0.4, null, AccuracyCompare.Source.ESTIMATE)) // no EPE: counted, not paired
        val m = s.summary()
        assertEquals(4, m.epochs); assertEquals(3, m.paired)
        assertEquals(0.10, m.medianDiff!!, 1e-12)
        assertEquals(0.02, m.minDiff!!, 1e-12); assertEquals(0.30, m.maxDiff!!, 1e-12)
        assertEquals(3, m.appFromGst); assertEquals(1, m.appEstimated)
        assertEquals(AccuracyCompare.Source.ESTIMATE, m.lastSource)
    }
}
