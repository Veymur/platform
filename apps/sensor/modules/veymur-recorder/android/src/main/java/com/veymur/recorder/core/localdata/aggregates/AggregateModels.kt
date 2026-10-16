package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.HrRanges

const val AGGREGATE_METHOD_VERSION = "local-session-aggregates-v1"
const val DISTANCE_METHOD_VERSION = "haversine-sphere-v1"
const val SPHERE_RADIUS_M = 6371008.0

/** Required parameters: no production defaults until validation on real recordings. */
data class AggregateParameters(
    val hr_stale_after_ms: Long,
    val gps_max_accuracy_m: Double,
    val gps_max_gap_ms: Long,
    val gps_max_speed_m_s: Double,
    val map_hr_window_ms: Long,
) {
    init {
        require(hr_stale_after_ms > 0 && hr_stale_after_ms <= Long.MAX_VALUE / 1_000_000)
        require(gps_max_gap_ms > 0 && gps_max_gap_ms <= Long.MAX_VALUE / 1_000_000)
        require(map_hr_window_ms >= 0 && map_hr_window_ms <= Long.MAX_VALUE / 1_000_000)
        require(gps_max_accuracy_m.isFinite() && gps_max_accuracy_m > 0)
        require(gps_max_speed_m_s.isFinite() && gps_max_speed_m_s > 0)
    }
}

data class ActiveSegment(
    val segment_id: String, val timebase_id: String,
    val start_elapsed_ns: String, val end_elapsed_ns: String,
    val start_sequence: Long, val end_sequence: Long,
)
data class HrInterval(
    val observation_id: String, val sequence: Long, val segment_id: String, val timebase_id: String,
    val start_elapsed_ns: String, val end_elapsed_ns: String, val value_bpm: Int,
)
data class ExcludedObservation(val observation_id: String, val stream: String, val reasons: List<String>)
data class RangeDuration(val range_id: String, val duration_ms: Double)
data class RoutePoint(
    val observation_id: String, val sequence: Long, val elapsed_ns: String,
    val latitude: Double, val longitude: Double,
    val hr_observation_id: String?, val hr_bpm: Int?, val hr_delta_ms: Double?,
)
data class RouteSegment(val segment_id: String, val timebase_id: String, val points: List<RoutePoint>)
data class SessionAggregates(
    val method_version: String, val distance_method_version: String, val sphere_radius_m: Double,
    val parameters: AggregateParameters, val hr_ranges_snapshot: HrRanges?,
    val active_segments: List<ActiveSegment>, val interrupted_tail_unknown: Boolean,
    val active_duration_ms: Double, val hr_covered_duration_ms: Double,
    val hr_coverage: Double?, val average_hr_bpm: Double?,
    val range_durations: List<RangeDuration>, val unassigned_hr_duration_ms: Double,
    val hr_intervals: List<HrInterval>, val route_segments: List<RouteSegment>,
    val gps_distance_m: Double?, val exclusions: List<ExcludedObservation>,
)

internal val ActiveSegment.startNs get() = start_elapsed_ns.toLong()
internal val ActiveSegment.endNs get() = end_elapsed_ns.toLong()
internal val HrInterval.startNs get() = start_elapsed_ns.toLong()
internal val HrInterval.endNs get() = end_elapsed_ns.toLong()
internal fun ActiveSegment.contains(at: Long, sequence: Long): Boolean =
    at >= startNs && at < endNs && sequence > start_sequence && sequence < end_sequence
