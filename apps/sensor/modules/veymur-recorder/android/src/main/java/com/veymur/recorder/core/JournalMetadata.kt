package com.veymur.recorder.core

import android.content.ContentValues
import org.json.JSONObject

internal data class StoredWriterSession(val profile: String, val device: String, val segment: String?, val origin: String, val gps: Boolean)
internal fun putMetadata(values: ContentValues, metadata: JSONObject) {
    val fields = mapOf("normalization_version" to "normalization_version", "observed_at_utc" to "observed_at_utc",
        "recorded_at_utc" to "recorded_at_utc", "timezone_id" to "timezone_id", "time_origin" to "time_origin",
        "timebase_id" to "timebase_id", "receipt_elapsed_realtime_ns" to "receipt_elapsed_ns",
        "source_elapsed_realtime_ns" to "source_elapsed_ns", "source_timestamp" to "source_timestamp",
        "quality_flags" to "quality_flags", "quality_reasons" to "quality_reasons")
    for ((key, column) in fields) {
        if (!metadata.has(key) || metadata.isNull(key)) values.putNull(column) else values.put(column, metadata.get(key).toString())
    }
    if (metadata.has("observed_monotonic_offset_ms") && !metadata.isNull("observed_monotonic_offset_ms")) values.put("observed_monotonic_ms", metadata.getLong("observed_monotonic_offset_ms"))
}
