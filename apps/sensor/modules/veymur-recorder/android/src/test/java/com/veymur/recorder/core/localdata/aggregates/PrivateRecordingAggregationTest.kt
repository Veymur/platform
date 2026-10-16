package com.veymur.recorder.core.localdata.aggregates

import com.veymur.recorder.core.localdata.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local evidence. No private HR/coordinates or artifacts are committed to test resources. */
class PrivateRecordingAggregationTest {
    @Test fun legacyProjectionsAgreeWithIndependentReferenceAndReportParameterSensitivity() {
        val directory = System.getenv("VEYMUR_AGGREGATE_FIXTURES")?.let(::File)
        assumeTrue("Set VEYMUR_AGGREGATE_FIXTURES to the prepared private TSV directory", directory?.isDirectory == true)
        val files = directory!!.listFiles()!!.filter { it.name.matches(Regex("[0-9a-f]{16}\\.tsv")) }.sortedBy { it.name }
        assertTrue("No prepared recordings", files.isNotEmpty())
        val cases = files.map { file ->
            val rows = file.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            val header = rows.first()
            val f = AggregateFixture(header[2].toBooleanStrict())
            f.state = SessionState.valueOf(header[1])
            var recording = false
            for (row in rows.drop(1)) {
                val kind = row[0]; val sequence = row[1].toLong(); val ns = row[2].toLong(); val segment = row[3]
                if (kind == "interrupted") f.base = "legacy-recovery"
                val source = when (kind) { "hr" -> SourceType.polar_hr; "gps" -> SourceType.phone_gps; else -> SourceType.recorder }
                val flags = listOf(QualityFlag.valid_format, QualityFlag.clock_uncertain) +
                    if (row.last() == "suspect") listOf(QualityFlag.suspect_signal) else emptyList()
                val envelope = f.envelope(ns / 1_000_000, source, flags, ns).copy(sequence = sequence,
                    identity = f.identity("record-$sequence"), recorded_at_utc = java.time.Instant.ofEpochMilli(row[4].toLong()).toString(),
                    timezone_id = null, quality_reasons = listOf("legacy_append_time_proxy", "timezone_unknown"))
                when (kind) {
                    "hr" -> f.hr.add(HrObservation(envelope, segment, row[5].toInt(), HrUnit.bpm, null))
                    "gps" -> {
                        val observedNs = row[8].takeIf { it != "-" }?.toLong()
                        f.gps.add(GpsObservation(envelope.copy(source_elapsed_realtime_ns = observedNs?.toString(),
                            observed_monotonic_offset_ms = observedNs?.div(1_000_000)), segment,
                            GpsValue(row[5].toDouble(), row[6].toDouble(), row[7].takeIf { it != "-" }?.toDouble(), null, null), GpsUnit.degree))
                    }
                    else -> {
                        val payload = when (kind) {
                            "start" -> SessionEventPayload.Start(segment).also { recording = true }
                            "resume" -> SessionEventPayload.Resume(segment).also { recording = true }
                            "pause" -> SessionEventPayload.Pause(segment).also { recording = false }
                            "finish" -> SessionEventPayload.FinishRequested(segment.takeIf { recording }).also { recording = false }
                            "completed" -> SessionEventPayload.Completed
                            "interrupted" -> SessionEventPayload.Interrupted(row[5].takeIf { it != "-" })
                            "hr_lost" -> SessionEventPayload.HrStateChanged(HrState.lost, "legacy_disconnect")
                            "gps_lost" -> SessionEventPayload.GpsStateChanged(GpsState.lost, "legacy_provider_off")
                            else -> error("Unknown prepared row kind")
                        }
                        f.events.add(SessionEvent(envelope, payload))
                    }
                }
            }
            val result = f.aggregate()
            assertEquals("Active time differs: ${file.name}", header[3].toDouble(), result.active_duration_ms, 1e-6)
            assertEquals("HR coverage differs: ${file.name}", header[4].toDouble(), result.hr_covered_duration_ms, 1e-6)
            if (header[5] == "-") assertNull(result.average_hr_bpm)
            else assertEquals("HR mean differs: ${file.name}", header[5].toDouble(), result.average_hr_bpm!!, 1e-8)
            assertEquals(result, aggregateSession(f.session(), f.events.reversed(), f.hr.reversed(), f.gps.reversed(), f.parameters))
            f
        }
        val report = mutableListOf("kind\tparameter\tvalue\tcovered_ms\troute_points\troute_lines\thr_matched_points")
        fun assess(kind: String, value: Number, parameter: String, change: (AggregateParameters) -> AggregateParameters) {
            val results = cases.map { it.aggregate(change(it.parameters)) }
            val points = results.flatMap { it.route_segments }.flatMap { it.points }
            report.add(listOf(kind, parameter, value, results.sumOf { it.hr_covered_duration_ms }, points.size,
                results.sumOf { it.route_segments.size }, points.count { it.hr_bpm != null }).joinToString("\t"))
        }
        for (value in listOf(1000L, 3000L, 5000L, 10000L)) assess("hr", value, "stale_after_ms") { it.copy(hr_stale_after_ms = value) }
        for (value in listOf(10.0, 30.0, 50.0, 100.0)) assess("gps", value, "max_accuracy_m") { it.copy(gps_max_accuracy_m = value) }
        for (value in listOf(2000L, 5000L, 10000L)) assess("gps", value, "max_gap_ms") { it.copy(gps_max_gap_ms = value) }
        for (value in listOf(3.0, 10.0, 20.0, 50.0)) assess("gps", value, "max_speed_m_s") { it.copy(gps_max_speed_m_s = value) }
        for (value in listOf(500L, 1000L, 2000L, 5000L)) assess("map", value, "hr_window_ms") { it.copy(map_hr_window_ms = value) }
        File(directory, "parameter-sensitivity.tsv").writeText(report.joinToString("\n") + "\n")
    }
}
