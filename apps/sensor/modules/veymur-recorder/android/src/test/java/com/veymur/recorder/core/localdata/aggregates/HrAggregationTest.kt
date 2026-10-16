package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import org.junit.Assert.*
import org.junit.Test

class HrAggregationTest {
    @Test fun weightedMeanDoesNotDependOnCallbackCountOrInputListOrder() {
        val a = AggregateFixture().apply { start(); hr(0, 80); hr(1000, 120); finish(5000) }
        val b = AggregateFixture().apply { start(); hr(0, 80); hr(200, 80); hr(800, 80); hr(1000, 120); hr(3000, 120); finish(5000) }
        val result = a.aggregate()
        assertEquals(112.0, result.average_hr_bpm!!, 1e-10)
        assertEquals(1.0, result.hr_coverage!!, 0.0)
        assertEquals(result.average_hr_bpm, b.aggregate().average_hr_bpm)
        assertEquals(result, aggregateSession(a.session(), a.events.reversed(), a.hr.reversed(), emptyList(), a.parameters))
    }
    @Test fun staleIntervalsStopAtThresholdAndLeaveExplicitChartGap() {
        val f = AggregateFixture().apply { start(); hr(0, 80); hr(8000, 100); finish(10000) }
        val r = f.aggregate(f.parameters.copy(hr_stale_after_ms = 3000))
        assertEquals(5000.0, r.hr_covered_duration_ms, 0.0)
        assertEquals(0.5, r.hr_coverage!!, 0.0)
        assertEquals(88.0, r.average_hr_bpm!!, 1e-10)
        assertEquals(listOf("3000000000", "10000000000"), r.hr_intervals.map { it.end_elapsed_ns })
    }
    @Test fun suspectAndMalformedNextSamplesStopPrecedingHrButRemainInInput() {
        val f = AggregateFixture().apply {
            start(); hr(0, 80); hr(1000, 250, listOf(QualityFlag.valid_format, QualityFlag.suspect_signal))
            hr(2000, 0); hr(4000, 100); finish(6000)
        }
        val r = f.aggregate()
        assertEquals(3000.0, r.hr_covered_duration_ms, 0.0)
        assertEquals(4, f.hr.size); assertEquals(250, f.hr[1].value)
        assertEquals(2, r.exclusions.size)
        assertTrue(r.exclusions.any { "source_suspect_signal" in it.reasons })
        assertTrue(r.exclusions.any { "invalid_hr_format" in it.reasons })
    }
    @Test fun lossEventCutsCoverageWithoutEndingSessionOrGps() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(100); event(500, SessionEventPayload.HrStateChanged(HrState.lost, "disconnected"))
            gps(1000); hr(2000, 100); gps(2500); finish(3000)
        }
        val r = f.aggregate()
        assertEquals(3000.0, r.active_duration_ms, 0.0)
        assertEquals(1500.0, r.hr_covered_duration_ms, 0.0)
        assertEquals(1, r.route_segments.size)
        assertNull(r.route_segments.single().points[1].hr_bpm)
        assertTrue(r.gps_distance_m!! > 0)
    }
    @Test fun pausesDoNotContributeActiveTimeAndResumeDoesNotReuseOldHr() {
        val f = AggregateFixture().apply { start(); hr(0, 80); pause(1000); resume(10000); hr(11000, 100); finish(12000) }
        val r = f.aggregate()
        assertEquals(3000.0, r.active_duration_ms, 0.0)
        assertEquals(2000.0, r.hr_covered_duration_ms, 0.0)
        assertEquals(90.0, r.average_hr_bpm!!, 0.0)
        assertEquals(2, r.active_segments.size)
    }
    @Test fun noCoverageAndZeroActiveTimeHaveDifferentUnknownResults() {
        val empty = AggregateFixture().apply { start(); finish(1000) }.aggregate()
        assertEquals(0.0, empty.hr_coverage!!, 0.0); assertNull(empty.average_hr_bpm); assertNull(empty.gps_distance_m)
        val zero = AggregateFixture().apply { start(); hr(0, 80); finish(0) }.aggregate()
        assertEquals(0.0, zero.active_duration_ms, 0.0); assertNull(zero.hr_coverage); assertNull(zero.average_hr_bpm)
    }
    @Test fun sameTimestampUsesLatestSequenceAndRetainsNanosecondDurations() {
        val f = AggregateFixture().apply { start(); hr(0, 80); hr(0, 100); hr(1, 120, ns = 1_000_001); finish(2) }
        val r = f.aggregate()
        assertEquals(listOf(100, 120), r.hr_intervals.map { it.value_bpm })
        assertEquals(109.99999, r.average_hr_bpm!!, 1e-8)
        assertEquals(2.0, r.hr_covered_duration_ms, 1e-12)
    }
    @Test fun utcAndTimezoneChangesNeverAffectAggregates() {
        val f = AggregateFixture().apply { start(); hr(0, 80); hr(1000, 100); finish(3000) }
        val expected = f.aggregate()
        val changed = f.hr.map { it.copy(envelope = it.envelope.copy(recorded_at_utc = "1900-01-01T00:00:00Z", timezone_id = "America/New_York", quality_flags = listOf(QualityFlag.valid_format, QualityFlag.clock_uncertain))) }
        assertEquals(expected, aggregateSession(f.session(), f.events, changed, emptyList(), f.parameters))
    }
    @Test fun rangeEdgesAreHalfOpenAndSnapshotIsDetachedFromCurrentSettings() {
        val identity = Identity("ranges-1", 1, "guest-1", "install-1")
        val list = mutableListOf(HrRange("low", "My low", 0, 100), HrRange("middle", "My middle", 100, 150), HrRange("high", "My high", 200, null))
        val f = AggregateFixture(ranges = HrRanges(identity, 2, list)).apply { start(); hr(0, 99); hr(1000, 100); hr(2000, 150); hr(3000, 200); finish(4000) }
        val r = f.aggregate()
        assertEquals(listOf(1000.0, 1000.0, 1000.0), r.range_durations.map { it.duration_ms })
        assertEquals(1000.0, r.unassigned_hr_duration_ms, 0.0)
        list.clear()
        assertEquals(3, r.hr_ranges_snapshot!!.ranges.size)
        assertEquals(2, r.hr_ranges_snapshot.revision)
    }
    @Test fun overlappingEmptyDuplicateAndForeignRangesAreRejected() {
        val identity = Identity("ranges", 1, "guest-1", "install-1")
        val bad = listOf(listOf(HrRange("a", "a", 100, 100)),
            listOf(HrRange("a", "a", null, 150), HrRange("b", "b", 100, null)),
            listOf(HrRange("a", "a", 0, 100), HrRange("a", "b", 100, null)))
        for (ranges in bad) {
            val f = AggregateFixture(ranges = HrRanges(identity, 1, ranges)).apply { start(); finish(1000) }
            assertThrows(IllegalArgumentException::class.java) { f.aggregate() }
        }
        val f = AggregateFixture(ranges = HrRanges(identity.copy(local_profile_id = "other"), 1, emptyList())).apply { start(); finish(1000) }
        assertThrows(IllegalArgumentException::class.java) { f.aggregate() }
    }
}
