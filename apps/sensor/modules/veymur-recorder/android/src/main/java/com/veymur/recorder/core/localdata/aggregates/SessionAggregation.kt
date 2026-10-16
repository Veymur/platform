package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*

/** Pure entry point over persisted logical observations/events; no JS, Android, clocks, or SQL. */
fun aggregateSession(
    session: Session, events: List<SessionEvent>, hr: List<HrObservation>, gps: List<GpsObservation>,
    parameters: AggregateParameters,
): SessionAggregates {
    require(session.identity.schema_version == 1 && session.source_type == SourceType.recorder)
    require(session.identity.id.isNotBlank() && session.identity.local_profile_id.isNotBlank() && session.identity.installation_id.isNotBlank())
    val all = events.map { it.envelope } + hr.map { it.envelope } + gps.map { it.envelope }
    require(all.map { it.identity.id }.toSet().size == all.size) { "Duplicate record IDs" }
    require(all.map { it.sequence }.toSet().size == all.size) { "Duplicate record sequences" }
    all.forEach {
        require(it.identity.id.isNotBlank() && it.identity.schema_version == 1 && it.sequence in 0..9_007_199_254_740_991L)
        require(it.session_id == session.identity.id && it.identity.local_profile_id == session.identity.local_profile_id &&
            it.identity.installation_id == session.identity.installation_id) { "Mixed sessions/profiles/installations" }
        require(it.normalization_version == NORMALIZATION_VERSION) { "Unknown normalization version" }
        it.receiptNs()
    }
    require(events.all { it.envelope.source_type == SourceType.recorder })
    require(hr.all { it.envelope.source_type == SourceType.polar_hr })
    require(gps.all { it.envelope.source_type == SourceType.phone_gps })
    require(session.route_requested || gps.isEmpty()) { "GPS in a route-disabled session" }
    val ranges = session.hr_ranges_snapshot?.let { snapshot ->
        require(snapshot.identity.local_profile_id == session.identity.local_profile_id) { "Ranges belong to a different profile" }
        snapshot.copy(ranges = snapshot.ranges.map { it.copy() })
    }
    validateRanges(ranges)
    val timeline = buildTimeline(session, events, all.associateBy { it.identity.id })
    val segments = timeline.segments
    val exclusions = mutableListOf<ExcludedObservation>()
    val intervals = hrIntervals(segments, hr, events, parameters, exclusions)
    // Active segments never overlap; every HR interval ends at the next sample or earlier.
    val activeMs = segments.sumOf { (it.endNs - it.startNs) / 1_000_000.0 }
    val coveredMs = intervals.sumOf { (it.endNs - it.startNs) / 1_000_000.0 }
    val weighted = intervals.sumOf { it.value_bpm * ((it.endNs - it.startNs) / 1_000_000.0) }
    val durations = ranges?.ranges.orEmpty().map { range -> RangeDuration(range.id,
        intervals.filter { range.contains(it.value_bpm) }.sumOf { (it.endNs - it.startNs) / 1_000_000.0 }) }
    val gpsResult = aggregateGps(segments, gps, events, parameters, intervals, exclusions)
    return SessionAggregates(AGGREGATE_METHOD_VERSION, DISTANCE_METHOD_VERSION, SPHERE_RADIUS_M, parameters.copy(), ranges,
        segments, timeline.interrupted_tail_unknown, activeMs, coveredMs,
        if (activeMs == 0.0) null else coveredMs / activeMs, if (coveredMs == 0.0) null else weighted / coveredMs,
        durations, (coveredMs - durations.sumOf { it.duration_ms }).coerceAtLeast(0.0), intervals, gpsResult.segments,
        gpsResult.distance_m, exclusions.sortedWith(compareBy<ExcludedObservation> { it.stream }.thenBy { it.observation_id }))
}
