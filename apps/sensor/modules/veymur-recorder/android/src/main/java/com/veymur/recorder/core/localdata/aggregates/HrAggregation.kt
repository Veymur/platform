package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import kotlin.math.abs

internal fun sourceExclusions(envelope: RecordEnvelope): List<String> = buildList {
    if (QualityFlag.valid_format !in envelope.quality_flags) add("format_not_confirmed")
    for (flag in listOf(QualityFlag.suspect_signal, QualityFlag.missing, QualityFlag.stale, QualityFlag.estimated)) {
        if (flag in envelope.quality_flags) add("source_${flag.name}")
    }
}

internal fun hrIntervals(
    segments: List<ActiveSegment>, hr: List<HrObservation>, events: List<SessionEvent>,
    parameters: AggregateParameters, exclusions: MutableList<ExcludedObservation>,
): List<HrInterval> {
    val grouped = hr.groupBy { it.segment_id }
    val known = segments.associateBy { it.segment_id }
    hr.filter { it.segment_id !in known }.forEach { exclusions.add(ExcludedObservation(it.envelope.identity.id, "hr", listOf("unknown_segment"))) }
    val result = mutableListOf<HrInterval>()
    for (segment in segments) {
        val sameBase = grouped[segment.segment_id].orEmpty().filter {
            if (it.envelope.timebase_id == segment.timebase_id) true else {
                exclusions.add(ExcludedObservation(it.envelope.identity.id, "hr", listOf("outside_active_segment"))); false
            }
        }
        val samples = sameBase.sortedWith(compareBy<HrObservation> { it.envelope.receiptNs() }.thenBy { it.envelope.sequence })
        val breaks = events.filter { event ->
            val payload = event.event
            payload is SessionEventPayload.HrStateChanged && payload.state != HrState.fresh &&
                event.envelope.timebase_id == segment.timebase_id && event.envelope.sequence > segment.start_sequence &&
                event.envelope.sequence < segment.end_sequence
        }.map { it.envelope }.sortedWith(compareBy<RecordEnvelope> { it.receiptNs() }.thenBy { it.sequence })
        for ((index, sample) in samples.withIndex()) {
            val envelope = sample.envelope
            val at = envelope.receiptNs()
            val reasons = sourceExclusions(envelope).toMutableList()
            if (sample.value <= 0) reasons.add("invalid_hr_format")
            if (sample.contact_detected == false) reasons.add("sensor_contact_lost")
            if (envelope.timebase_id != segment.timebase_id || !segment.contains(at, envelope.sequence)) reasons.add("outside_active_segment")
            if (reasons.isNotEmpty()) {
                exclusions.add(ExcludedObservation(envelope.identity.id, "hr", reasons)); continue
            }
            val next = samples.getOrNull(index + 1)?.envelope?.receiptNs()
            var lo = 0; var hi = breaks.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val boundary = breaks[mid]
                if (boundary.receiptNs() < at || (boundary.receiptNs() == at && boundary.sequence <= envelope.sequence)) lo = mid + 1 else hi = mid
            }
            val nextBreak = breaks.getOrNull(lo)?.receiptNs()
            val staleNs = parameters.hr_stale_after_ms * 1_000_000
            val untilStale = if (Long.MAX_VALUE - at < staleNs) Long.MAX_VALUE else at + staleNs
            // Every next sample (including suspect data) stops the preceding value.
            val end = minOf(segment.endNs, next ?: segment.endNs, untilStale, nextBreak ?: segment.endNs)
            if (end > at) result.add(HrInterval(envelope.identity.id, envelope.sequence, segment.segment_id,
                segment.timebase_id, at.toString(), end.toString(), sample.value))
        }
    }
    return result.toList()
}

internal fun validateRanges(snapshot: HrRanges?) {
    if (snapshot == null) return
    require(snapshot.identity.schema_version == 1 && snapshot.revision > 0)
    require(snapshot.ranges.map { it.id }.toSet().size == snapshot.ranges.size) { "Duplicate range IDs" }
    snapshot.ranges.forEach {
        require(it.id.isNotBlank())
        require(it.lower_bpm_inclusive == null || it.lower_bpm_inclusive >= 0)
        require(it.upper_bpm_exclusive == null || it.upper_bpm_exclusive > 0)
        require(it.lower_bpm_inclusive == null || it.upper_bpm_exclusive == null || it.lower_bpm_inclusive < it.upper_bpm_exclusive) { "Empty range" }
    }
    val ordered = snapshot.ranges.sortedBy { it.lower_bpm_inclusive ?: Int.MIN_VALUE }
    ordered.zipWithNext().forEach { (left, right) ->
        require(left.upper_bpm_exclusive != null && right.lower_bpm_inclusive != null && left.upper_bpm_exclusive <= right.lower_bpm_inclusive) { "Overlapping ranges" }
    }
}

internal fun HrRange.contains(bpm: Int) = (lower_bpm_inclusive == null || bpm >= lower_bpm_inclusive) &&
    (upper_bpm_exclusive == null || bpm < upper_bpm_exclusive)

/** One shared HR projection for chart intervals, summary and route colouring. */
internal class HrMatcher(intervals: List<HrInterval>, private val windowMs: Long, events: List<SessionEvent>) {
    private val breaks = events.filter { it.event is SessionEventPayload.HrStateChanged && it.event.state != HrState.fresh }
        .groupBy { it.envelope.timebase_id }.mapValues { (_, events) -> events.map { it.envelope.sequence }.sorted() }
    private fun hasBreak(previous: HrInterval, next: HrInterval): Boolean {
        val sequences = breaks[previous.timebase_id] ?: return false
        val index = sequences.binarySearch(previous.sequence).let { if (it >= 0) it + 1 else -it - 1 }
        return index < sequences.size && sequences[index] <= next.sequence
    }
    private val blocksBySegment = buildList {
        var current = mutableListOf<HrInterval>()
        for (interval in intervals) {
            val previous = current.lastOrNull()
            if (previous != null && (previous.segment_id != interval.segment_id || previous.timebase_id != interval.timebase_id || previous.endNs != interval.startNs || hasBreak(previous, interval))) {
                add(current.toList()); current = mutableListOf()
            }
            current.add(interval)
        }
        if (current.isNotEmpty()) add(current.toList())
    }.groupBy { it.first().segment_id }
    fun match(segment: ActiveSegment, at: Long): HrInterval? {
        val blocks = blocksBySegment[segment.segment_id] ?: return null
        var lo = 0; var hi = blocks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].last().endNs <= at) lo = mid + 1 else hi = mid
        }
        val block = blocks.getOrNull(lo)?.takeIf { at >= it.first().startNs && it.first().timebase_id == segment.timebase_id } ?: return null
        lo = 0; hi = block.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (block[mid].startNs < at) lo = mid + 1 else hi = mid
        }
        return listOfNotNull(block.getOrNull(lo - 1), block.getOrNull(lo))
            .minWithOrNull(compareBy<HrInterval> { abs(it.startNs - at) }.thenBy { it.startNs }.thenBy { it.sequence })
            ?.takeIf { abs(it.startNs - at) <= windowMs * 1_000_000 }
    }
}
