package com.anubhav.app

import com.anubhav.app.ui.customer.RangeBarScale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider keeps the normal band in the middle half of the track, and only stretches a
 * side when the result would otherwise fall off it.
 */
class RangeBarScaleTest {
    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test fun bandSitsInTheMiddleHalf() {
        val s = RangeBarScale.of(12.0, 16.0, 14.0)
        assertNear(0.25, s.fraction(12.0))
        assertNear(0.75, s.fraction(16.0))
        assertNear(0.5, s.fraction(14.0))
    }

    @Test fun slightlyOutOfRangeLeavesTheScaleAlone() {
        // HGB 11.1 against 11.5 - 16.0 still fits in the default yellow margin.
        val s = RangeBarScale.of(11.5, 16.0, 11.1)
        assertNear(0.25, s.fraction(11.5))
        assertNear((11.1 - 9.25) / 9.0, s.fraction(11.1))
    }

    @Test fun farOutResultStretchesItsSideToATenth() {
        // MCV 64.1 against 81 - 99: the low side stretches, the high side keeps its half band.
        val low = RangeBarScale.of(81.0, 99.0, 64.1)
        assertNear(0.1, low.fraction(64.1))
        assertNear(108.0, low.spanHigh)
        val high = RangeBarScale.of(4.0, 11.0, 19.0)
        assertNear(0.9, high.fraction(19.0))
        assertNear(0.5, high.spanLow)
    }

    @Test fun extremeResultRestsOnTheEndWithTheBandStillReadable() {
        val s = RangeBarScale.of(0.0, 40.0, 1000.0)
        assertNear(1.0, s.fraction(1000.0))
        assertNear(0.15, s.fraction(40.0) - s.fraction(0.0))
    }

    @Test fun flatOrInvertedRangeIsNormalized() {
        val flat = RangeBarScale.of(5.0, 5.0, null)
        assertNear(5.0, flat.low); assertNear(6.0, flat.high)
        val inverted = RangeBarScale.of(9.0, 3.0, null)
        assertNear(3.0, inverted.low); assertNear(9.0, inverted.high)
    }

    @Test fun ticksPrintLikeTheReport() {
        assertEquals("4", RangeBarScale.tick(4.0))
        assertEquals("3.7", RangeBarScale.tick(3.70))
        assertEquals("0.35", RangeBarScale.tick(0.35))
        assertEquals("150000", RangeBarScale.tick(150000.0))
        assertEquals("0", RangeBarScale.tick(0.0))
    }
}
