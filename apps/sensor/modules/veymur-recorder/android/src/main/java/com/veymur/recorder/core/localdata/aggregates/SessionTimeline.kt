package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*

internal fun RecordEnvelope.receiptNs(): Long {
    val ns = receipt_elapsed_realtime_ns.toLongOrNull()
    require(ns != null && ns >= 0 && ns / 1_000_000 == monotonic_offset_ms) { "Invalid receipt monotonic timestamp" }
    require(timebase_id.isNotBlank()) { "Missing timebase" }
    return ns
}

internal data class SessionTimeline(val segments: List<ActiveSegment>, val interrupted_tail_unknown: Boolean)

/** Committed events define active intervals. Recovery time never closes the previous process. */
internal fun buildTimeline(
    session: Session, events: List<SessionEvent>, records: Map<String, RecordEnvelope>,
): SessionTimeline {
    require(session.state == SessionState.completed || session.state == SessionState.interrupted) { "A terminal session is required" }
    require(session.active_segment_id == null) { "Terminal session has an active segment" }
    var tailUnknown = false
    var state = SessionState.preparation
    var open: SessionEvent? = null
    var openId: String? = null
    val ids = mutableSetOf<String>()
    val result = mutableListOf<ActiveSegment>()
    fun close(boundary: RecordEnvelope, endSequence: Long, expectedId: String?) {
        val start = requireNotNull(open) { "No active segment to close" }
        require(expectedId == openId) { "Closing a different segment" }
        require(boundary.timebase_id == start.envelope.timebase_id) { "Active segment crosses timebases" }
        val from = start.envelope.receiptNs(); val until = boundary.receiptNs()
        require(until >= from) { "Segment ends before its start" }
        result.add(ActiveSegment(openId!!, boundary.timebase_id, from.toString(), until.toString(), start.envelope.sequence, endSequence))
        open = null; openId = null
    }
    for (event in events.sortedBy { it.envelope.sequence }) {
        val envelope = event.envelope
        when (val payload = event.event) {
            is SessionEventPayload.Start, is SessionEventPayload.Resume -> {
                require(if (payload is SessionEventPayload.Start) state == SessionState.preparation else state == SessionState.paused) { "Invalid start/resume" }
                val id = when (payload) { is SessionEventPayload.Start -> payload.segment_id; is SessionEventPayload.Resume -> payload.segment_id }
                require(id.isNotBlank() && ids.add(id)) { "Segment ID reused" }
                // Adjacent segments in one base must not overlap, regardless of UTC clock changes.
                result.lastOrNull()?.takeIf { it.timebase_id == envelope.timebase_id }?.let {
                    require(envelope.receiptNs() >= it.endNs) { "Overlapping segments" }
                }
                open = event; openId = id; state = SessionState.recording
            }
            is SessionEventPayload.Pause -> {
                require(state == SessionState.recording) { "Pause outside recording" }
                close(envelope, envelope.sequence, payload.segment_id); state = SessionState.paused
            }
            is SessionEventPayload.FinishRequested -> {
                require(state == SessionState.recording || state == SessionState.paused) { "Invalid finish request" }
                if (open != null) close(envelope, envelope.sequence, payload.segment_id) else require(payload.segment_id == null)
                state = SessionState.finishing
            }
            SessionEventPayload.Completed -> {
                require(state == SessionState.finishing) { "Completion without finish/commit" }; state = SessionState.completed
            }
            is SessionEventPayload.Interrupted -> {
                require(state in listOf(SessionState.recording, SessionState.paused, SessionState.finishing)) { "Invalid interruption" }
                val last = payload.last_persisted_record_id?.let { requireNotNull(records[it]) { "Recovery reference is missing" } }
                require(last == null || last.sequence < envelope.sequence) { "Recovery reference must precede recovery" }
                if (open != null) {
                    tailUnknown = true
                    val start = open!!.envelope
                    // With no known tail, retain a zero-length boundary at the last start/resume.
                    val boundary = last ?: start
                    require(boundary.sequence >= start.sequence) { "Recovery boundary precedes active segment" }
                    close(boundary, envelope.sequence, openId)
                }
                state = SessionState.interrupted
            }
            is SessionEventPayload.HrStateChanged, is SessionEventPayload.GpsStateChanged -> {
                require(state != SessionState.completed && state != SessionState.interrupted) { "Stream event after terminal state" }
            }
        }
    }
    result.groupBy { it.timebase_id }.values.forEach { withinBase ->
        withinBase.zipWithNext().forEach { (previous, next) ->
            require(previous.endNs <= next.startNs) { "Overlapping active segments in one timebase" }
        }
    }
    require(open == null && state == session.state) { "Incomplete or inconsistent terminal event history" }
    return SessionTimeline(result.toList(), tailUnknown)
}
