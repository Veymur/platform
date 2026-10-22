package com.veymur.recorder.core

import android.database.Cursor
import com.veymur.recorder.core.localdata.*
import com.veymur.recorder.core.localdata.aggregates.*
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Unavailable is explicit: old append times are never promoted to precise callback times. */
data class StoredAggregateResult(val projection_version: String, val method_version: String,
    val aggregates: SessionAggregates?, val unavailable_reasons: List<String>, val inputs: LogicalSessionInputs? = null)
data class LogicalSessionInputs(val session: Session, val hr: List<HrObservation>, val gps: List<GpsObservation>)

internal class JournalAggregateReader(private val db: SQLiteDatabase) {
    private fun unavailable(reason: String) = StoredAggregateResult("journal-v2-logical-v1", AGGREGATE_METHOD_VERSION, null, listOf(reason))
    fun read(id: String, parameters: AggregateParameters): StoredAggregateResult = try {
        readKnown(id, parameters)
    } catch (_: IllegalArgumentException) { unavailable("incomplete_or_incompatible_logical_history") }
      catch (_: org.json.JSONException) { unavailable("malformed_logical_payload") }
    private fun readKnown(id: String, parameters: AggregateParameters): StoredAggregateResult {
        val session = db.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) return unavailable("session_not_found")
            if (it.text("storage_origin") == "legacy_v1") return unavailable("legacy_envelope_not_known")
            if (it.text("state") !in listOf("completed", "interrupted")) return unavailable("session_not_terminal")
            Session(Identity(id, 1, it.text("local_profile_id")!!, it.text("installation_id")!!), SourceType.recorder,
                it.text("installation_id")!!, java.time.Instant.ofEpochMilli(it.getLong(it.getColumnIndexOrThrow("started"))).toString(),
                it.text("timezone_id")!!, SessionState.valueOf(it.text("state")!!), HrState.valueOf(it.text("hr_state")!!),
                GpsState.valueOf(it.text("gps_state")!!), it.getInt(it.getColumnIndexOrThrow("route_requested")) == 1,
                null, it.text("ended_utc")?.toLong()?.let { ms -> java.time.Instant.ofEpochMilli(ms).toString() },
                it.text("current_segment_id"), it.text("hr_ranges_snapshot")?.let(::ranges))
        }
        val events = mutableListOf<SessionEvent>(); val hr = mutableListOf<HrObservation>(); val gps = mutableListOf<GpsObservation>()
        var lastTypedRecord: String? = null
        try {
            db.rawQuery("SELECT * FROM records WHERE session=? ORDER BY sequence", arrayOf(id)).use {
                while (it.moveToNext()) {
                    val kind = it.text("kind")!!
                    if (kind !in listOf("start", "pause", "resume", "finish", "interrupted", "hr", "gps", "disconnected", "bluetooth", "gps_unavailable", "gps_error")) continue
                    val envelope = envelope(it)
                    val segment = it.text("segment_id")
                    val payload = JSONObject(it.text("payload")!!)
                    val event: SessionEventPayload? = when (kind) {
                        "start" -> SessionEventPayload.Start(requireNotNull(segment))
                        "resume" -> SessionEventPayload.Resume(requireNotNull(segment))
                        "pause" -> SessionEventPayload.Pause(segment)
                        "finish" -> SessionEventPayload.FinishRequested(segment)
                        "interrupted" -> SessionEventPayload.Interrupted(lastTypedRecord)
                        "disconnected" -> SessionEventPayload.HrStateChanged(HrState.lost, "disconnected")
                        "bluetooth" -> if (payload.opt("powered") == false) SessionEventPayload.HrStateChanged(HrState.lost, "bluetooth_off") else null
                        "gps_unavailable", "gps_error" -> SessionEventPayload.GpsStateChanged(GpsState.lost, kind)
                        else -> null
                    }
                    if (event != null) {
                        events.add(SessionEvent(envelope, event)); lastTypedRecord = envelope.identity.id
                        if (kind == "finish") {
                            val completed = envelope.copy(identity = envelope.identity.copy(id = envelope.identity.id + ":completed"), sequence = envelope.sequence + 1)
                            events.add(SessionEvent(completed, SessionEventPayload.Completed)); lastTypedRecord = completed.identity.id
                        }
                    } else if (kind == "hr") {
                        hr.add(HrObservation(envelope, requireNotNull(segment), payload.observationInt("bpm"), HrUnit.bpm,
                            if (payload.opt("contact_supported") == true) payload.opt("contact") as? Boolean else null))
                        lastTypedRecord = envelope.identity.id
                    } else if (kind == "gps") {
                        gps.add(GpsObservation(envelope, requireNotNull(segment), GpsValue(
                            requireNotNull(payload.opt("latitude") as? Number).toDouble(), requireNotNull(payload.opt("longitude") as? Number).toDouble(),
                            (payload.opt("accuracy_m") as? Number)?.toDouble(), (payload.opt("altitude_m") as? Number)?.toDouble(),
                            (payload.opt("speed_m_s") as? Number)?.toDouble()), GpsUnit.degree))
                        lastTypedRecord = envelope.identity.id
                    }
                }
            }
            return StoredAggregateResult("journal-v2-logical-v1", AGGREGATE_METHOD_VERSION,
                aggregateSession(session, events, hr, gps, parameters), emptyList(), LogicalSessionInputs(session, hr, gps))
        } catch (_: IllegalArgumentException) { return unavailable("incomplete_or_incompatible_logical_history") }
          catch (_: org.json.JSONException) { return unavailable("malformed_logical_payload") }
    }
    private fun envelope(cursor: Cursor): RecordEnvelope = with(cursor) {
        val receipt = requireNotNull(text("receipt_elapsed_ns")); val ns = receipt.toLong()
        val sequence = getLong(getColumnIndexOrThrow("sequence"))
        require(sequence in 0..4_503_599_627_370_495L)
        val sourceTimestamp = text("source_timestamp")?.let(::JSONObject)?.let {
            SourceTimestamp(it.getString("value"), it.getString("unit"), if (it.isNull("utc_conversion")) null else it.getString("utc_conversion"))
        }
        RecordEnvelope(Identity(text("id")!!, 1, text("local_profile_id")!!, requireNotNull(text("installation_id"))),
            SourceType.valueOf(requireNotNull(text("source_type"))), requireNotNull(text("source_id")), text("observed_at_utc"),
            requireNotNull(text("recorded_at_utc")), text("timezone_id"), TimeOrigin.valueOf(requireNotNull(text("time_origin"))), ns / 1_000_000,
            requireNotNull(text("timebase_id")), sourceTimestamp, receipt, text("source_elapsed_ns"), text("observed_monotonic_ms")?.toLong(),
            requireNotNull(text("normalization_version")), strings(requireNotNull(text("quality_reasons"))), requireNotNull(text("session")), sequence * 2,
            strings(requireNotNull(text("quality_flags"))).map { QualityFlag.valueOf(it) })
    }
    private fun ranges(text: String): HrRanges {
        val value = JSONObject(text)
        val identity = Identity(value.getString("id"), value.getInt("schema_version"), value.getString("local_profile_id"), value.getString("installation_id"))
        val ranges = value.getJSONArray("ranges")
        return HrRanges(identity, value.getInt("revision"), List(ranges.length()) { index ->
            val range = ranges.getJSONObject(index)
            HrRange(range.getString("id"), range.getString("label"), if (range.isNull("lower_bpm_inclusive")) null else range.getInt("lower_bpm_inclusive"),
                if (range.isNull("upper_bpm_exclusive")) null else range.getInt("upper_bpm_exclusive"))
        })
    }
    private fun strings(text: String): List<String> = JSONArray(text).let { a -> List(a.length()) { a.getString(it) } }
}
internal fun Cursor.text(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
