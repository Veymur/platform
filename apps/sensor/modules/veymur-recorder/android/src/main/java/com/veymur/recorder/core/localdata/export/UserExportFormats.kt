package com.veymur.recorder.core.localdata.export

import com.veymur.recorder.core.localdata.GpsObservation
import com.veymur.recorder.core.localdata.HrObservation
import com.veymur.recorder.core.localdata.Session
import com.veymur.recorder.core.localdata.aggregates.ActiveSegment
import com.veymur.recorder.core.localdata.aggregates.SessionAggregates
import com.veymur.recorder.core.localdata.aggregates.endNs
import com.veymur.recorder.core.localdata.aggregates.receiptNs
import com.veymur.recorder.core.localdata.aggregates.startNs
import java.util.Locale

const val USER_EXPORT_FORMAT_VERSION = "user-export-v1"
const val PULSE_CSV_HEADER =
    "session_id,segment_number,observation_id,received_at_utc,active_time_s,heart_rate_bpm,usable,usable_until_active_time_s,exclusion_reasons"

class ActiveTimeline(private val segments: List<ActiveSegment>) {
    private val indexById = segments.withIndex().associate { (index, segment) -> segment.segment_id to index }
    private val offsetsNs = segments.runningFold(0L) { total, segment -> total + (segment.endNs - segment.startNs) }
    fun segmentNumber(segmentId: String): Int? = indexById[segmentId]?.plus(1)
    fun activeSeconds(segmentId: String, elapsedNs: Long): Double? {
        val index = indexById[segmentId] ?: return null
        val segment = segments[index]
        if (elapsedNs < segment.startNs || elapsedNs > segment.endNs) return null
        return (offsetsNs[index] + (elapsedNs - segment.startNs)) / 1_000_000_000.0
    }
}

private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)
private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.7f", value)
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

fun writePulseCsv(session: Session, hr: List<HrObservation>, aggregates: SessionAggregates, out: Appendable): Int {
    val timeline = ActiveTimeline(aggregates.active_segments)
    val intervals = aggregates.hr_intervals.associateBy { it.observation_id }
    val exclusions = aggregates.exclusions.filter { it.stream == "hr" }.associate { it.observation_id to it.reasons }
    out.append(PULSE_CSV_HEADER).append('\n')
    val rows = hr.sortedBy { it.envelope.sequence }
    for (observation in rows) {
        val id = observation.envelope.identity.id
        val interval = intervals[id]
        val usableUntil = interval?.let { timeline.activeSeconds(it.segment_id, it.endNs) }
        listOf(
            session.identity.id,
            timeline.segmentNumber(observation.segment_id)?.toString().orEmpty(),
            id,
            observation.envelope.recorded_at_utc,
            timeline.activeSeconds(observation.segment_id, observation.envelope.receiptNs())?.let(::seconds).orEmpty(),
            observation.value.toString(),
            (interval != null).toString(),
            usableUntil?.let(::seconds).orEmpty(),
            exclusions[id].orEmpty().joinToString(";"),
        ).joinTo(out, ",") { csvField(it) }
        out.append('\n')
    }
    return rows.size
}

fun writeRouteGpx(session: Session, gps: List<GpsObservation>, aggregates: SessionAggregates, out: Appendable): Int {
    val observations = gps.associateBy { it.envelope.identity.id }
    val segments = aggregates.route_segments.filter { it.points.isNotEmpty() }
    if (segments.isEmpty()) return 0
    val name = "veymur session ${session.identity.id}"
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.append("<gpx version=\"1.1\" creator=\"veymur/sensor $USER_EXPORT_FORMAT_VERSION\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
    out.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
    out.append("  <metadata>\n    <name>").append(xml(name)).append("</name>\n")
    out.append("    <desc>").append(xml("Phone GPS route. Track segments break at pauses, gaps and excluded points; they must not be joined. " +
        "Heart rate is attached only when a usable reading was within ${aggregates.parameters.map_hr_window_ms} ms. " +
        "Aggregates ${aggregates.method_version}, distance ${aggregates.distance_method_version}.")).append("</desc>\n")
    out.append("    <time>").append(xml(session.created_at_utc)).append("</time>\n  </metadata>\n")
    out.append("  <trk>\n    <name>").append(xml(name)).append("</name>\n")
    var points = 0
    for (segment in segments) {
        out.append("    <trkseg>\n")
        for (point in segment.points) {
            val observation = observations.getValue(point.observation_id)
            out.append("      <trkpt lat=\"").append(coordinate(point.latitude)).append("\" lon=\"").append(coordinate(point.longitude)).append("\">")
            observation.value.altitude_m?.takeIf { it.isFinite() }?.let { out.append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
            out.append("<time>").append(xml(observation.envelope.observed_at_utc ?: observation.envelope.recorded_at_utc)).append("</time>")
            point.hr_bpm?.let {
                out.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it.toString())
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            }
            out.append("</trkpt>\n")
            points++
        }
        out.append("    </trkseg>\n")
    }
    out.append("  </trk>\n</gpx>\n")
    return points
}
