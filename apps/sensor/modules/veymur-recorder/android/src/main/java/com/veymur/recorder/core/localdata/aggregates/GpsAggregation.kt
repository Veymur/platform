package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import kotlin.math.*

/** Spherical surface distance; altitude is deliberately not added to horizontal distance. */
internal fun sphericalDistanceM(a: GpsValue, b: GpsValue): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = (sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)
    return 2 * SPHERE_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

internal data class GpsAggregation(val segments: List<RouteSegment>, val distance_m: Double?)
internal fun aggregateGps(
    segments: List<ActiveSegment>, gps: List<GpsObservation>, events: List<SessionEvent>,
    parameters: AggregateParameters, hr: List<HrInterval>, exclusions: MutableList<ExcludedObservation>,
): GpsAggregation {
    val matcher = HrMatcher(hr, parameters.map_hr_window_ms, events)
    val grouped = gps.groupBy { it.segment_id }
    val known = segments.associateBy { it.segment_id }
    gps.filter { it.segment_id !in known }.forEach { exclusions.add(ExcludedObservation(it.envelope.identity.id, "gps", listOf("unknown_segment"))) }
    val output = mutableListOf<RouteSegment>()
    var distance = 0.0; var edges = 0
    for (segment in segments) {
        val breaks = events.filter {
            val payload = it.event
            payload is SessionEventPayload.GpsStateChanged && payload.state != GpsState.available &&
                it.envelope.timebase_id == segment.timebase_id && it.envelope.sequence > segment.start_sequence &&
                it.envelope.sequence < segment.end_sequence
        }.map { it.envelope.receiptNs() }.sorted()
        fun fixNs(sample: GpsObservation): Long? = sample.envelope.source_elapsed_realtime_ns?.toLongOrNull()?.takeIf {
            it >= 0 && it / 1_000_000 == sample.envelope.observed_monotonic_offset_ms && it <= sample.envelope.receiptNs()
        }
        val samples = grouped[segment.segment_id].orEmpty().sortedWith(compareBy<GpsObservation> { fixNs(it) ?: it.envelope.receiptNs() }.thenBy { it.envelope.sequence })
        var line = mutableListOf<RoutePoint>()
        var previous: GpsObservation? = null
        fun flush() {
            if (line.isNotEmpty()) output.add(RouteSegment(segment.segment_id, segment.timebase_id, line.toList()))
            line = mutableListOf(); previous = null
        }
        for (sample in samples) {
            val envelope = sample.envelope; val value = sample.value; val at = fixNs(sample)
            val reasons = sourceExclusions(envelope).toMutableList()
            if (!value.latitude.isFinite() || value.latitude !in -90.0..90.0 || !value.longitude.isFinite() || value.longitude !in -180.0..180.0) reasons.add("invalid_coordinates")
            if (value.horizontal_accuracy_m == null) reasons.add("accuracy_unknown")
            else if (!value.horizontal_accuracy_m.isFinite() || value.horizontal_accuracy_m < 0 || value.horizontal_accuracy_m > parameters.gps_max_accuracy_m) reasons.add("accuracy_unusable")
            if (at == null) reasons.add("measurement_monotonic_unknown")
            if (envelope.timebase_id != segment.timebase_id || at == null || !segment.contains(at, envelope.sequence) ||
                !segment.contains(envelope.receiptNs(), envelope.sequence)) reasons.add("outside_active_segment")
            if (reasons.isNotEmpty()) {
                exclusions.add(ExcludedObservation(envelope.identity.id, "gps", reasons)); flush(); continue
            }
            val currentNs = at!!
            val prev = previous
            if (prev != null) {
                val previousNs = fixNs(prev)!!
                val dt = currentNs - previousNs
                val breakIndex = breaks.binarySearch(previousNs).let { if (it >= 0) it else -it - 1 }
                val hasBreak = breakIndex < breaks.size && breaks[breakIndex] <= currentNs
                if (dt > parameters.gps_max_gap_ms * 1_000_000 || hasBreak) flush()
                else {
                    val metres = sphericalDistanceM(prev.value, value)
                    if (dt <= 0) {
                        exclusions.add(ExcludedObservation(envelope.identity.id, "gps", listOf("non_increasing_fix_time"))); flush(); continue
                    }
                    if (metres / (dt / 1_000_000_000.0) > parameters.gps_max_speed_m_s) {
                        exclusions.add(ExcludedObservation(envelope.identity.id, "gps", listOf("implausible_segment_speed"))); flush(); continue
                    }
                    distance += metres; edges++
                }
            }
            val match = matcher.match(segment, currentNs)
            line.add(RoutePoint(envelope.identity.id, envelope.sequence, currentNs.toString(), value.latitude, value.longitude,
                match?.observation_id, match?.value_bpm, match?.let { abs(it.startNs - currentNs) / 1_000_000.0 }))
            previous = sample
        }
        flush()
    }
    return GpsAggregation(output.toList(), if (edges == 0) null else distance)
}
