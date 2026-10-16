package com.veymur.recorder.core.localdata

/** Local data contract v1; independent of diagnostic bridge and SQL versions. */
const val LOCAL_DATA_SCHEMA_VERSION = 1
enum class SessionState { preparation, recording, paused, finishing, completed, interrupted }
enum class HrState { inactive, waiting, fresh, stale, lost }
enum class GpsState { disabled, inactive, waiting, available, degraded, lost, permission_denied }
enum class QualityFlag { valid_format, suspect_signal, missing, stale, clock_uncertain, estimated }
enum class SourceType { polar_hr, phone_gps, recorder, user }
enum class TimeOrigin { measurement, receipt, user_action, recovery }
enum class HrUnit { bpm }
enum class GpsUnit { degree }
enum class Units { metric, imperial }
enum class Theme { system, light, dark }
data class Identity(val id: String, val schema_version: Int, val local_profile_id: String, val installation_id: String)
data class SourceTimestamp(val value: String, val unit: String, val utc_conversion: String?)
data class RecordEnvelope(
    val identity: Identity, val source_type: SourceType, val source_id: String,
    val observed_at_utc: String?, val recorded_at_utc: String, val timezone_id: String?,
    val time_origin: TimeOrigin, val monotonic_offset_ms: Long, val timebase_id: String,
    val source_timestamp: SourceTimestamp?, val receipt_elapsed_realtime_ns: String,
    val source_elapsed_realtime_ns: String?, val observed_monotonic_offset_ms: Long?,
    val normalization_version: String, val quality_reasons: List<String>, val session_id: String, val sequence: Long,
    val quality_flags: List<QualityFlag>
)
data class HrObservation(val envelope: RecordEnvelope, val segment_id: String, val value: Int, val unit: HrUnit, val contact_detected: Boolean?)
data class GpsValue(val latitude: Double, val longitude: Double, val horizontal_accuracy_m: Double?, val altitude_m: Double?, val speed_m_s: Double?)
data class GpsObservation(val envelope: RecordEnvelope, val segment_id: String, val value: GpsValue, val unit: GpsUnit)
sealed interface SessionEventPayload {
    data class Start(val segment_id: String) : SessionEventPayload
    data class Resume(val segment_id: String) : SessionEventPayload
    data class Pause(val segment_id: String?) : SessionEventPayload
    data class FinishRequested(val segment_id: String?) : SessionEventPayload
    data object Completed : SessionEventPayload
    data class Interrupted(val last_persisted_record_id: String?) : SessionEventPayload
    data class HrStateChanged(val state: HrState, val reason: String) : SessionEventPayload
    data class GpsStateChanged(val state: GpsState, val reason: String) : SessionEventPayload
}
data class SessionEvent(val envelope: RecordEnvelope, val event: SessionEventPayload)
data class Session(
    val identity: Identity, val source_type: SourceType, val source_id: String,
    val created_at_utc: String, val timezone_id: String, val state: SessionState,
    val hr_state: HrState, val gps_state: GpsState, val route_requested: Boolean,
    val started_at_utc: String?, val ended_at_utc: String?, val active_segment_id: String?,
    val hr_ranges_snapshot: HrRanges?
)
data class LocalProfile(val identity: Identity, val created_at_utc: String)
data class HrRange(val id: String, val label: String, val lower_bpm_inclusive: Int?, val upper_bpm_exclusive: Int?)
data class HrRanges(val identity: Identity, val revision: Int, val ranges: List<HrRange>)
data class Alerts(val enabled: Boolean, val sound: Boolean, val vibration: Boolean, val debounce_ms: Long, val hysteresis_bpm: Int, val repeat_ms: Long?)
data class Settings(val identity: Identity, val revision: Int, val units: Units, val timezone_id: String, val theme: Theme, val route_enabled: Boolean, val hr_ranges_id: String?, val alerts: Alerts)
