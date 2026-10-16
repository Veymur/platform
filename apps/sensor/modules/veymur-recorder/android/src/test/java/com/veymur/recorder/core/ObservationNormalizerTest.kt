package com.veymur.recorder.core

import com.veymur.recorder.core.localdata.*
import org.junit.Assert.*
import org.junit.Test

class ObservationNormalizerTest {
    private fun receipt(utc: Long = 1700000000000, elapsed: Long = 1_000_000_001, base: String = "process-1", zone: String? = "Europe/Warsaw") = Receipt(utc, elapsed, base, zone)
    private fun gps(utc: Long? = 1699999999900, elapsed: Long? = 900_000_001) = GpsInput(52.23, 21.01, null, null, null, utc, elapsed, false)

    @Test fun hrHasReceiptTimeButNeverInventsMeasurementTimestampOrContact() {
        val sample = ObservationNormalizer().hr(400, false, false, receipt())
        assertEquals(400, sample.value) // No physiological outlier filter.
        assertNull(sample.contact_detected)
        assertNull(sample.time.observed_at_utc)
        assertNull(sample.time.source_timestamp)
        assertNull(sample.time.observed_monotonic_offset_ms)
        assertEquals("2023-11-14T22:13:20Z", sample.time.recorded_at_utc)
        assertEquals(TimeOrigin.receipt, sample.time.time_origin)
        assertEquals("1000000001", sample.time.receipt_elapsed_realtime_ns)
        assertEquals(listOf(QualityFlag.valid_format), sample.quality.quality_flags)
    }
    @Test fun wallClockJumpsDoNotChangeActiveTimeOrHrAgeAndAreFlagged() {
        val n = ObservationNormalizer()
        n.hr(80, true, true, receipt())
        var elapsedMs = 1000L
        val clock = RecordingClock { elapsedMs }; clock.start()
        val sample = n.hr(90, true, true, receipt(1000, 2_000_000_001))
        elapsedMs = 2000; clock.pause(); elapsedMs = 9000; clock.resume(); elapsedMs = 10000
        assertEquals(2000L, clock.activeMs())
        assertTrue(QualityFlag.clock_uncertain in sample.quality.quality_flags)
        assertEquals(listOf("wall_clock_discontinuity"), sample.quality.quality_reasons)
        assertTrue(assessHr(sample, receipt(Long.MAX_VALUE, 3_000_000_001), 1000).usable)
        assertEquals(listOf("stale"), assessHr(sample, receipt(0, 3_000_000_002), 1000).reasons)
        assertFalse(QualityFlag.stale in sample.quality.quality_flags)
    }
    @Test fun gpsPreservesSourceUtcMonotonicNanosAndAbsentOptionalValues() {
        val sample = ObservationNormalizer().gps(gps(), receipt())
        assertEquals("2023-11-14T22:13:19.900Z", sample.time.observed_at_utc)
        assertEquals(TimeOrigin.measurement, sample.time.time_origin)
        assertEquals("1699999999900", sample.time.source_timestamp!!.value)
        assertEquals("unix-ms-to-utc-v1", sample.time.source_timestamp.utc_conversion)
        assertEquals("900000001", sample.time.source_elapsed_realtime_ns)
        assertEquals(900L, sample.time.observed_monotonic_offset_ms)
        assertNull(sample.value.horizontal_accuracy_m); assertNull(sample.value.altitude_m); assertNull(sample.value.speed_m_s)
        val zero = ObservationNormalizer().gps(gps().copy(accuracy_m = 0.0, altitude_m = 0.0, speed_m_s = 0.0), receipt())
        assertEquals(0.0, zero.value.horizontal_accuracy_m!!, 0.0)
    }
    @Test fun unknownGpsTimeAndInvalidSourceTimeAreNeverReplacedByReceiptTime() {
        val n = ObservationNormalizer()
        val absent = n.gps(gps(null, null), receipt())
        assertNull(absent.time.observed_at_utc); assertNull(absent.time.source_timestamp)
        val invalid = n.gps(gps(0, 2_000_000_000), receipt())
        assertNull(invalid.time.observed_at_utc); assertNull(invalid.time.observed_monotonic_offset_ms)
        assertEquals("0", invalid.time.source_timestamp!!.value)
        assertEquals("2000000000", invalid.time.source_elapsed_realtime_ns)
        assertTrue(QualityFlag.clock_uncertain in invalid.quality.quality_flags)
    }
    @Test fun malformedFormatIsRejectedButSuspectObservationsArePreservedWithReasons() {
        val n = ObservationNormalizer()
        listOf(0, -1, Int.MIN_VALUE).forEach { bpm -> assertThrows(InvalidObservation::class.java) { n.hr(bpm, true, true, receipt()) } }
        val suspect = n.hr(250, false, true, receipt())
        assertEquals(250, suspect.value)
        assertTrue(QualityFlag.suspect_signal in suspect.quality.quality_flags)
        val assessment = assessHr(suspect, receipt(), 5000)
        assertEquals("hr-usability-v1", assessment.method_version)
        assertEquals(listOf("sensor_contact_lost"), assessment.reasons); assertFalse(assessment.usable)
        listOf(gps().copy(latitude = Double.NaN), gps().copy(longitude = 181.0), gps().copy(accuracy_m = -1.0),
            gps().copy(altitude_m = Double.POSITIVE_INFINITY), gps().copy(speed_m_s = -1.0)).forEach {
            assertThrows(InvalidObservation::class.java) { n.gps(it, receipt()) }
        }
        val mock = n.gps(gps().copy(mock = true), receipt())
        assertEquals(listOf("mock_location"), mock.quality.quality_reasons)
        assertTrue(QualityFlag.suspect_signal in mock.quality.quality_flags)
    }
    @Test fun forwardClockJumpAndTimezoneChangePreserveRawGpsAndMonotonicTime() {
        val n = ObservationNormalizer()
        n.hr(80, true, true, receipt())
        val changed = receipt(1700003601000, 2_000_000_001, zone = "America/New_York")
        val sample = n.gps(gps(1700000001000, 2_000_000_000), changed)
        assertTrue("wall_clock_discontinuity" in sample.quality.quality_reasons)
        assertTrue("source_clock_disagreement" in sample.quality.quality_reasons)
        assertEquals("America/New_York", sample.time.timezone_id)
        assertEquals("1700000001000", sample.time.source_timestamp!!.value)
        assertEquals(2000L, sample.time.observed_monotonic_offset_ms)
        val onlyZone = ObservationNormalizer()
        onlyZone.hr(80, true, true, receipt())
        val next = onlyZone.hr(82, true, true, receipt(1700000001000, 2_000_000_001, zone = "America/New_York"))
        assertEquals(listOf(QualityFlag.valid_format), next.quality.quality_flags)
    }
    @Test fun restartAndRebootAreSeparateTimebasesAndLegacyTimezoneStaysUnknown() {
        val n = ObservationNormalizer()
        val before = n.hr(80, true, true, receipt())
        val after = n.hr(82, true, true, receipt(100, 1000, "process-2", null))
        assertNull(after.time.timezone_id)
        assertEquals(listOf("timezone_unknown"), after.quality.quality_reasons)
        assertEquals(listOf("different_timebase"), assessHr(before, receipt(0, 1000, "process-2"), 5000).reasons)
        assertEquals(listOf("before_receipt"), assessHr(before, receipt(0, 1), 5000).reasons)
        assertThrows(InvalidObservation::class.java) { n.hr(80, true, true, receipt(elapsed = -1)) }
    }
}
