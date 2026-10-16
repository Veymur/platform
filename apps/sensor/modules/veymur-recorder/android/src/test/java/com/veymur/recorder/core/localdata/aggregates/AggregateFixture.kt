package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*

/** Synthetic persisted logical records. Test parameters are deliberately not product defaults. */
internal class AggregateFixture(val route: Boolean = false, val ranges: HrRanges? = null) {
    val events = mutableListOf<SessionEvent>()
    val hr = mutableListOf<HrObservation>()
    val gps = mutableListOf<GpsObservation>()
    var state = SessionState.completed
    private var sequence = 0L
    var segmentId = "segment-1"
    var base = "process-1"
    val parameters = AggregateParameters(5000, 30.0, 5000, 20.0, 2000)
    fun identity(id: String) = Identity(id, 1, "guest-1", "install-1")
    fun envelope(ms: Long, source: SourceType, flags: List<QualityFlag> = listOf(QualityFlag.valid_format), ns: Long = ms * 1_000_000): RecordEnvelope {
        val n = ++sequence
        return RecordEnvelope(identity("record-$n"), source, "source", null, "2026-10-16T10:00:00Z", "Europe/Warsaw",
            TimeOrigin.receipt, ns / 1_000_000, base, null, ns.toString(), null, null, NORMALIZATION_VERSION, emptyList(), "session-1", n, flags)
    }
    fun event(ms: Long, payload: SessionEventPayload): SessionEvent = SessionEvent(envelope(ms, SourceType.recorder), payload).also { events.add(it) }
    fun start(ms: Long = 0) = event(ms, SessionEventPayload.Start(segmentId))
    fun pause(ms: Long) = event(ms, SessionEventPayload.Pause(segmentId))
    fun resume(ms: Long, id: String = "segment-2"): SessionEvent { segmentId = id; return event(ms, SessionEventPayload.Resume(id)) }
    fun finish(ms: Long) { event(ms, SessionEventPayload.FinishRequested(segmentId)); event(ms, SessionEventPayload.Completed) }
    fun finishPaused(ms: Long) { event(ms, SessionEventPayload.FinishRequested(null)); event(ms, SessionEventPayload.Completed) }
    fun hr(ms: Long, bpm: Int, flags: List<QualityFlag> = listOf(QualityFlag.valid_format), ns: Long = ms * 1_000_000): HrObservation =
        HrObservation(envelope(ms, SourceType.polar_hr, flags, ns), segmentId, bpm, HrUnit.bpm, null).also { hr.add(it) }
    fun gps(ms: Long, longitude: Double = ms / 1000.0 * 0.00001, accuracy: Double? = 5.0,
            observedNs: Long? = ms * 1_000_000, receiptMs: Long = ms, latitude: Double = 0.0,
            flags: List<QualityFlag> = listOf(QualityFlag.valid_format)): GpsObservation {
        val e = envelope(receiptMs, SourceType.phone_gps, flags).copy(source_elapsed_realtime_ns = observedNs?.toString(), observed_monotonic_offset_ms = observedNs?.div(1_000_000))
        return GpsObservation(e, segmentId, GpsValue(latitude, longitude, accuracy, null, null), GpsUnit.degree).also { gps.add(it) }
    }
    fun interrupt(recoveryMs: Long, lastId: String? = (events.map { it.envelope } + hr.map { it.envelope } + gps.map { it.envelope }).maxByOrNull { it.sequence }?.identity?.id) {
        base = "process-2"; event(recoveryMs, SessionEventPayload.Interrupted(lastId)); state = SessionState.interrupted
    }
    fun session() = Session(identity("session-1"), SourceType.recorder, "install-1", "2026-10-16T10:00:00Z", "Europe/Warsaw", state,
        HrState.inactive, if (route) GpsState.inactive else GpsState.disabled, route, "2026-10-16T10:00:00Z", null, null, ranges)
    fun aggregate(parameters: AggregateParameters = this.parameters) = aggregateSession(session(), events, hr, gps, parameters)
}
