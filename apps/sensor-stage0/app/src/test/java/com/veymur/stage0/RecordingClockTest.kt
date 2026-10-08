package com.veymur.stage0

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {
    @Test fun pauseExcludesTimeAndRepeatedPauseDoesNotDoubleCount() {
        var time = 0L
        val clock = RecordingClock { time }
        clock.start(); time = 1000; clock.pause(); time = 9000; clock.pause()
        assertEquals(1000, clock.activeMs())
        clock.resume(); clock.resume(); time = 10000
        assertEquals(2000, clock.activeMs())
    }
    @Test fun newSessionResetsPreviousDuration() {
        var time = 0L
        val clock = RecordingClock { time }
        clock.start(); time = 4000; clock.pause(); clock.start(); time = 4500
        assertEquals(500, clock.activeMs())
    }
}
