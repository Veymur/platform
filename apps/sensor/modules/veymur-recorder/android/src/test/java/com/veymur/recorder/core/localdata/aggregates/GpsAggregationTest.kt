package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class GpsAggregationTest {
    @Test fun knownArcAndAntimeridianUseSurfaceDistanceInMetres() {
        val a = GpsValue(0.0, 0.0, 5.0, null, null)
        assertEquals(PI * SPHERE_RADIUS_M / 180, sphericalDistanceM(a, a.copy(longitude = 1.0)), 1e-8)
        assertEquals(PI * SPHERE_RADIUS_M / 90, sphericalDistanceM(a.copy(longitude = 179.0), a.copy(longitude = -179.0)), 1e-6)
        assertEquals(0.0, sphericalDistanceM(a, a), 0.0)
    }
    @Test fun missingGpsIsUnknownButStationaryUsablePointsGiveKnownZero() {
        val absent = AggregateFixture(true).apply { start(); finish(2000) }.aggregate()
        assertNull(absent.gps_distance_m)
        val one = AggregateFixture(true).apply { start(); gps(100); finish(2000) }.aggregate()
        assertNull(one.gps_distance_m)
        val stationary = AggregateFixture(true).apply { start(); gps(100, 0.0); gps(1000, 0.0); finish(2000) }.aggregate()
        assertEquals(0.0, stationary.gps_distance_m!!, 0.0)
    }
    @Test fun gpsLossPauseAndLongGapAlwaysSplitLinesAndDistance() {
        val f = AggregateFixture(true).apply {
            start(); gps(100); gps(1000); event(1500, SessionEventPayload.GpsStateChanged(GpsState.lost, "provider_off"))
            gps(2000); gps(3000); pause(3500); resume(5000)
            gps(5100); gps(6000); gps(12000); gps(13000); finish(14000)
        }
        val r = f.aggregate()
        assertEquals(listOf(2, 2, 2, 2), r.route_segments.map { it.points.size })
        assertEquals(3.8 * PI * SPHERE_RADIUS_M / 180 * 0.00001, r.gps_distance_m!!, 1e-7)
        assertEquals(2, r.active_segments.size)
    }
    @Test fun unusableAccuracyAndMockDoNotBridgeToNextPointOrDestroyHr() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(100); gps(1000, accuracy = null); gps(2000); gps(3000, accuracy = 31.0)
            gps(4000); gps(4500, flags = listOf(QualityFlag.valid_format, QualityFlag.suspect_signal)); finish(5000)
        }
        val r = f.aggregate()
        assertEquals(1.0, r.hr_coverage!!, 0.0)
        assertNull(r.gps_distance_m)
        assertEquals(listOf(1, 1, 1), r.route_segments.map { it.points.size })
        assertEquals(3, r.exclusions.count { it.stream == "gps" })
    }
    @Test fun jumpIsExcludedAndNeverAddsDistanceThroughEitherNeighbour() {
        val f = AggregateFixture(true).apply {
            start(); gps(100, 0.0); gps(1000, 0.00001); gps(2000, 10.0); gps(3000, 0.00003); gps(4000, 0.00004); finish(5000)
        }
        val r = f.aggregate()
        assertEquals(listOf(2, 2), r.route_segments.map { it.points.size })
        assertEquals(PI * SPHERE_RADIUS_M / 180 * 0.00002, r.gps_distance_m!!, 1e-8)
        assertEquals(listOf("implausible_segment_speed"), r.exclusions.single().reasons)
        assertEquals(5, f.gps.size) // Source coordinates are untouched.
    }
    @Test fun measurementClockUsedForDistanceAndUnknownOrPreSegmentFixIsExcluded() {
        val f = AggregateFixture(true).apply {
            start(1000); gps(1100, observedNs = 500_000_000); gps(1500, observedNs = null)
            gps(2000, 0.0, receiptMs = 2500); gps(3000, 0.00001, receiptMs = 4500); finish(5000)
        }
        val r = f.aggregate()
        assertEquals(2, r.exclusions.size)
        assertEquals(listOf("2000000000", "3000000000"), r.route_segments.single().points.map { it.elapsed_ns })
        assertEquals(PI * SPHERE_RADIUS_M / 180 * 0.00001, r.gps_distance_m!!, 1e-8)
    }
    @Test fun duplicateFixTimesDoNotCreateSpeedOrDistanceAndThresholdEdgesAreInclusive() {
        val f = AggregateFixture(true).apply {
            start(); gps(100, accuracy = 30.0); gps(100, accuracy = 30.0); gps(5100, accuracy = 30.0); finish(5200)
        }
        val r = f.aggregate()
        assertNull(r.gps_distance_m) // Duplicate splits; the following point starts a new line.
        assertEquals(listOf("non_increasing_fix_time"), r.exclusions.single().reasons)
        val exact = AggregateFixture(true).apply { start(); gps(100, accuracy = 30.0); gps(5100, accuracy = 30.0); finish(5200) }.aggregate()
        assertNotNull(exact.gps_distance_m)
    }
    @Test fun mapUsesNearestHrWithinWindowAndBreaksTiesToEarlierSample() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(1500); hr(2000, 100); gps(3000); hr(4000, 120); gps(6500); finish(8000)
        }
        val r = f.aggregate(f.parameters.copy(map_hr_window_ms = 2000))
        val points = r.route_segments.flatMap { it.points }
        assertEquals(listOf(100, 100, null), points.map { it.hr_bpm })
        assertEquals(listOf(500.0, 1000.0, null), points.map { it.hr_delta_ms })
    }
    @Test fun mapDoesNotBorrowHrAcrossStalenessLossOrPauseEvenWithWideWindow() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(3500); hr(4000, 100)
            event(5000, SessionEventPayload.HrStateChanged(HrState.lost, "ble_lost")); gps(5500)
            pause(6000); resume(7000); gps(7500); hr(8000, 120); gps(8500); finish(9000)
        }
        val r = f.aggregate(f.parameters.copy(hr_stale_after_ms = 2000, map_hr_window_ms = 10000))
        assertEquals(listOf(null, null, null, 120), r.route_segments.flatMap { it.points }.map { it.hr_bpm })
    }
    @Test fun nanosecondWindowBoundaryDoesNotRoundAStaleMatchToFresh() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(1000, longitude = 0.0, observedNs = 1_000_000_000); gps(1001, longitude = 0.0, observedNs = 1_000_000_001); finish(2000)
        }
        val r = f.aggregate(f.parameters.copy(map_hr_window_ms = 1000))
        assertEquals(listOf(80, null), r.route_segments.flatMap { it.points }.map { it.hr_bpm })
    }
    @Test fun zeroLengthSignalLossStillSeparatesNearestHrCandidates() {
        val f = AggregateFixture(true).apply {
            start(); hr(0, 80); gps(900)
            event(1000, SessionEventPayload.HrStateChanged(HrState.lost, "lost"))
            hr(1000, 100); gps(1100); finish(2000)
        }
        val r = f.aggregate()
        assertEquals(listOf(80, 100), r.route_segments.flatMap { it.points }.map { it.hr_bpm })
        assertEquals(1.0, r.hr_coverage!!, 0.0)
    }

}
