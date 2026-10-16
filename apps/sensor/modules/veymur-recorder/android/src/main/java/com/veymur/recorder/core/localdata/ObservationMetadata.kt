package com.veymur.recorder.core.localdata

import org.json.JSONArray
import org.json.JSONObject

/** Additive payload metadata; SQL row receipt/commit columns retain their legacy meaning. */
fun observationMetadata(time: ObservationTime, quality: SourceQuality): JSONObject = JSONObject()
    .put("normalization_version", NORMALIZATION_VERSION)
    .put("observed_at_utc", time.observed_at_utc ?: JSONObject.NULL)
    .put("recorded_at_utc", time.recorded_at_utc).put("timezone_id", time.timezone_id ?: JSONObject.NULL)
    .put("time_origin", time.time_origin.name).put("monotonic_offset_ms", time.monotonic_offset_ms)
    .put("timebase_id", time.timebase_id).put("receipt_elapsed_realtime_ns", time.receipt_elapsed_realtime_ns)
    .put("source_elapsed_realtime_ns", time.source_elapsed_realtime_ns ?: JSONObject.NULL)
    .put("observed_monotonic_offset_ms", time.observed_monotonic_offset_ms ?: JSONObject.NULL)
    .put("source_timestamp", time.source_timestamp?.let {
        JSONObject().put("value", it.value).put("unit", it.unit).put("utc_conversion", it.utc_conversion ?: JSONObject.NULL)
    } ?: JSONObject.NULL)
    .put("quality_flags", JSONArray(quality.quality_flags.map { it.name }))
    .put("quality_reasons", JSONArray(quality.quality_reasons))

/** JSONObject getInt/getBoolean coerce strings/numbers; observation ingress must not. */
fun JSONObject.observationInt(key: String): Int = (opt(key) as? Int) ?: throw InvalidObservation("invalid_$key")
fun JSONObject.observationBoolean(key: String): Boolean = (opt(key) as? Boolean) ?: throw InvalidObservation("invalid_$key")
