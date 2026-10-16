package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import org.junit.Assert.*
import org.junit.Test

class SessionAggregationTest {
    @Test fun interruptionClosesAtLastCommittedReferenceAndNeverAtRecoveryUtcOrNewBase() {
        val f = AggregateFixture().apply { start(); hr(1000, 80); hr(3000, 100); interrupt(100) }
        val r = f.aggregate()
        assertEquals(3000.0, r.active_duration_ms, 0.0)
        assertEquals(2000.0, r.hr_covered_duration_ms, 0.0)
        assertEquals(80.0, r.average_hr_bpm!!, 0.0)
        assertTrue(r.interrupted_tail_unknown)
        assertEquals("process-1", r.active_segments.single().timebase_id)
        assertEquals("3000000000", r.active_segments.single().end_elapsed_ns)
    }
    @Test fun unknownRecoveryTailDoesNotInventDurationAndPausedRecoveryKeepsKnownBoundary() {
        val unknown = AggregateFixture().apply { start(); interrupt(100, null) }.aggregate()
        assertEquals(0.0, unknown.active_duration_ms, 0.0); assertTrue(unknown.interrupted_tail_unknown)
        val paused = AggregateFixture().apply { start(); hr(0, 80); pause(1000); interrupt(100) }.aggregate()
        assertEquals(1000.0, paused.active_duration_ms, 0.0); assertFalse(paused.interrupted_tail_unknown)
    }
    @Test fun mixedOwnersDuplicateIdsAndSequencesAndUnknownVersionsFailClosed() {
        val f = AggregateFixture().apply { start(); hr(0, 80); finish(1000) }
        fun rejects(sample: HrObservation) = assertThrows(IllegalArgumentException::class.java) {
            aggregateSession(f.session(), f.events, listOf(sample), emptyList(), f.parameters)
        }
        val sample = f.hr.single()
        rejects(sample.copy(envelope = sample.envelope.copy(session_id = "another")))
        rejects(sample.copy(envelope = sample.envelope.copy(identity = sample.envelope.identity.copy(local_profile_id = "another"))))
        rejects(sample.copy(envelope = sample.envelope.copy(normalization_version = "future")))
        rejects(sample.copy(envelope = sample.envelope.copy(sequence = f.events.first().envelope.sequence)))
        rejects(sample.copy(envelope = sample.envelope.copy(identity = sample.envelope.identity.copy(id = f.events.first().envelope.identity.id))))
        rejects(sample.copy(envelope = sample.envelope.copy(receipt_elapsed_realtime_ns = "not-a-time")))
        rejects(sample.copy(envelope = sample.envelope.copy(monotonic_offset_ms = 10)))
    }
    @Test fun invalidTransitionsReusedSegmentsAndCrossBaseSegmentsAreRejected() {
        val reused = AggregateFixture().apply { start(); pause(1000); resume(2000, "segment-1"); finish(3000) }
        assertThrows(IllegalArgumentException::class.java) { reused.aggregate() }
        val crossBase = AggregateFixture().apply { start(); base = "different"; finish(1000) }
        assertThrows(IllegalArgumentException::class.java) { crossBase.aggregate() }
        val backwards = AggregateFixture().apply { start(1000); finish(500) }
        assertThrows(IllegalArgumentException::class.java) { backwards.aggregate() }
        val missing = AggregateFixture().apply { start(); state = SessionState.recording }
        assertThrows(IllegalArgumentException::class.java) { missing.aggregate() }
    }
    @Test fun foreignStreamTimebaseCannotProvideHrOrDistance() {
        val f = AggregateFixture(true).apply { start(); base = "other"; hr(0, 80); gps(100); base = "process-1"; finish(1000) }
        val r = f.aggregate()
        assertEquals(0.0, r.hr_coverage!!, 0.0); assertNull(r.gps_distance_m); assertEquals(2, r.exclusions.size)
    }
    @Test fun parametersAreExplicitVersionedAndValidated() {
        val f = AggregateFixture().apply { start(); finish(1000) }
        val r = f.aggregate()
        assertEquals("local-session-aggregates-v1", r.method_version)
        assertEquals("haversine-sphere-v1", r.distance_method_version)
        assertEquals(f.parameters, r.parameters)
        assertThrows(IllegalArgumentException::class.java) { f.parameters.copy(hr_stale_after_ms = 0) }
        assertThrows(IllegalArgumentException::class.java) { f.parameters.copy(gps_max_accuracy_m = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { f.parameters.copy(gps_max_speed_m_s = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { f.parameters.copy(map_hr_window_ms = Long.MAX_VALUE) }
    }
    @Test fun longRecordingHasBoundedCoverageAndReproducibleIntervals() {
        val f = AggregateFixture().apply { start(); repeat(20000) { hr(it * 1000L, if (it % 2 == 0) 80 else 100) }; finish(20000000) }
        val r = f.aggregate()
        assertEquals(20000, r.hr_intervals.size)
        assertEquals(1.0, r.hr_coverage!!, 0.0)
        assertEquals(90.0, r.average_hr_bpm!!, 0.0)
    }
    @Test fun returningToEarlierBaseCannotDoubleCountOverlappingTime() {
        val f = AggregateFixture().apply {
            start(); pause(1000); base = "process-2"; resume(0); pause(1000)
            base = "process-1"; resume(500, "segment-3"); finish(1500)
        }
        assertThrows(IllegalArgumentException::class.java) { f.aggregate() }
    }
    @Test fun commitInterruptionKeepsKnownFinishBoundaryWithoutUnknownActiveTail() {
        val f = AggregateFixture().apply {
            start(); hr(0, 80); event(1000, SessionEventPayload.FinishRequested(segmentId)); interrupt(100)
        }
        val r = f.aggregate()
        assertEquals(1000.0, r.active_duration_ms, 0.0); assertFalse(r.interrupted_tail_unknown)
    }

}
