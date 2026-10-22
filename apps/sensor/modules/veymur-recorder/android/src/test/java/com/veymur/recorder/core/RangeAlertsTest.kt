package com.veymur.recorder.core

import org.junit.Assert.*
import org.junit.Test

class RangeAlertsTest {
    private val low = AlertRange("a", "A", null, 100)
    private val high = AlertRange("b", "B", 100, null)
    private fun alerts(debounce: Long = 2000, hysteresis: Int = 3, repeat: Long? = null) =
        RangeAlerts(RangeAlertConfiguration(listOf(low, high), debounce, hysteresis, repeat))
    @Test fun debounceRequiresContinuousFreshDataAndLossCannotTriggerAnAlert() {
        val a = alerts()
        assertNull(a.sample(80, 0)); assertNull(a.sample(80, 1000)); assertNull(a.sample(null, 2000))
        assertNull(a.sample(80, 3000)); assertNull(a.sample(80, 4000)); assertEquals(low, a.sample(80, 5000))
        assertNull(a.sample(80, 100000)); assertNull(a.sample(null, 110000))
    }
    @Test fun hysteresisAndDebounceRejectBoundaryOscillation() {
        val a = alerts()
        a.sample(90, 0); assertEquals(low, a.sample(90, 2000))
        assertNull(a.sample(100, 10000)); assertNull(a.sample(102, 11000))
        assertNull(a.sample(103, 12000)); assertNull(a.sample(99, 13000))
        assertNull(a.sample(103, 14000)); assertEquals(high, a.sample(103, 16000))
        assertNull(a.sample(98, 20000)); assertNull(a.sample(97, 21000))
        assertNull(a.sample(96, 22000)); assertEquals(low, a.sample(96, 24000))
    }
    @Test fun lossAndRangeFlappingPreserveCooldownEvenWithZeroDebounce() {
        val a = alerts(debounce = 0, hysteresis = 0)
        assertEquals(low, a.sample(80, 0))
        for (t in 1L..4999L) {
            assertNull(a.sample(null, t)); assertNull(a.sample(80, t))
        }
        assertEquals(low, a.sample(80, 5000))
        assertNull(a.sample(120, 5001)); assertNull(a.sample(120, 9999))
        assertEquals(high, a.sample(120, 10000))
    }
    @Test fun repeatRespectsConfigurationAndGlobalRateLimit() {
        val a = alerts(debounce = 0, repeat = 2000)
        assertEquals(low, a.sample(80, 0)); assertNull(a.sample(80, 2000))
        assertEquals(low, a.sample(80, 5000)); assertNull(a.sample(null, 10000))
        assertNull(a.sample(null, 15000)); assertEquals(low, a.sample(80, 20000))
    }
    @Test fun uncoveredIntervalsAndResetRequireNewDebounce() {
        val a = RangeAlerts(RangeAlertConfiguration(listOf(AlertRange("custom", "Custom", 90, 110)), 2000, 0, null))
        assertNull(a.sample(100, 0)); assertNull(a.sample(120, 2000))
        assertNull(a.sample(100, 3000)); a.reset()
        assertNull(a.sample(100, 6000)); assertNotNull(a.sample(100, 8000))
    }
    @Test fun invalidRangesAndClockRegressionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RangeAlertConfiguration(listOf(low, AlertRange("b", "B", 99, null)), 0, 0, null) }
        assertThrows(IllegalArgumentException::class.java) { RangeAlertConfiguration(listOf(low, low), 0, 0, null) }
        assertThrows(IllegalArgumentException::class.java) { RangeAlertConfiguration(listOf(low), -1, 0, null) }
        val a = alerts(); a.sample(80, 10)
        assertThrows(IllegalArgumentException::class.java) { a.sample(80, 9) }
    }
}
