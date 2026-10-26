package com.veymur.recorder.core

import com.polar.sdk.api.model.PolarDeviceInfo
import com.veymur.recorder.core.localdata.*
import org.json.JSONObject

/** Single Polar reducer used by the service and deterministic replay. Effects are injected. */
class PolarRecorderProcessor(
    private val read: () -> RecorderSnapshot,
    private val write: (RecorderSnapshot) -> Unit,
    private val clock: RecordingClock,
    private val deviceDetails: MutableMap<String, String>,
    private val normalizer: ObservationNormalizer = ObservationNormalizer(),
    private val record: (String, JSONObject) -> Unit,
) {
    private companion object {
        // Polar SDK 8.4.0 emits the same notification twice, 1-2 ms apart. A real HR
        // notification has a ~1 s period, so a short window only removes that duplicate
        // pair. The raw input remains in the diagnostic capture; only the user journal
        // and its counters ignore the second copy.
        const val DUPLICATE_HR_WINDOW_NS = 20_000_000L
    }

    private data class HrFingerprint(
        val deviceId: String?,
        val payload: String,
        val elapsedNs: Long,
    )

    private var lastHr: HrFingerprint? = null

    private var snapshot: RecorderSnapshot
        get() = read()
        set(value) = write(value)

    /** Shared processing entry: it consumes owned input, never an SDK callback object. */
    fun process(event: PolarInputEvent): Boolean {
        val payload = event.payload()
        if (event.eventType == "hr" && duplicateHr(event, payload)) return false
        when (event.eventType) {
            "bluetooth" -> {
                lastHr = null
                if (!payload.getBoolean("powered")) snapshot = snapshot.copy(status = "Bluetooth disabled", connected = null, hr = null)
                record("bluetooth", JSONObject().put("powered", payload.getBoolean("powered")))
            }
            "connecting" -> snapshot = snapshot.copy(status = "Connecting to ${event.deviceId}…")
            "connected" -> {
                lastHr = null
                snapshot = snapshot.copy(connected = event.deviceId, hr = null, battery = null, status = "Sensor connected; waiting for fresh heart rate")
                record("connected", JSONObject().put("device", event.deviceId))
            }
            "disconnected" -> {
                // A second disconnect for an already-lost sensor is SDK noise, not a
                // new session event. Raw diagnostic input still contains both copies.
                if (snapshot.connected == null) return false
                lastHr = null
                snapshot = snapshot.copy(connected = null, hr = null, status = "Connection lost. Keep the sensor nearby or reconnect the selected sensor")
                record("disconnected", JSONObject().put("device", event.deviceId)
                    .put("stream", "hr").put("quality_flags", org.json.JSONArray(listOf("missing"))))
            }
            "battery_level" -> {
                snapshot = snapshot.copy(battery = payload.getInt("level").takeIf { it in 0..100 })
                record("sensor_battery", JSONObject().put("percent", payload.getInt("level")))
            }
            "device_info" -> when (event.callbackVariant) {
                "disInformationReceived.uuid" -> {
                    deviceDetails[payload.getString("uuid")] = payload.getString("value")
                    record("device_info", payload)
                }
                "disInformationReceived.key" -> {
                    // Preserve the existing session export's display field; diagnostic input is structured.
                    val details = "DisInfo(key=${payload.getString("key")}, value=${payload.getString("value")})"
                    deviceDetails["dis"] = details
                    record("device_info", JSONObject().put("details", details))
                }
                else -> return false
            }
            "hr" -> {
                val sample = try {
                    payload.observationInt("ppgQuality")
                    normalizer.hr(payload.observationInt("hr"), payload.observationBoolean("contactStatus"),
                        payload.observationBoolean("contactStatusSupported"), Receipt(event.receivedUtcMs,
                            event.receivedElapsedNs, event.processRunId, event.receivedTimezoneId))
                } catch (error: InvalidObservation) {
                    // A corrupt sample is not a storage failure and must not refresh current HR.
                    if (snapshot.recording && !snapshot.paused) record("hr_rejected", JSONObject()
                        .put("reason", error.reason).put("raw_payload", payload)
                        .put("received_utc_ms", event.receivedUtcMs)
                        .put("received_elapsed_ns", event.receivedElapsedNs.toString()).put("timebase_id", event.processRunId))
                    return true
                }
                snapshot = snapshot.copy(hr = sample.value.takeIf { sample.contact_detected != false },
                    lastHrElapsed = event.receivedElapsedNs / 1_000_000, lastHrElapsedNs = event.receivedElapsedNs)
                if (snapshot.recording && !snapshot.paused) {
                    record("hr", JSONObject().put("bpm", sample.value).put("contact", payload.getBoolean("contactStatus"))
                        .put("contact_supported", payload.getBoolean("contactStatusSupported")).put("ppg_quality", payload.getInt("ppgQuality"))
                        .put("active_ms", clock.activeMs()).put("timestamp_source", "phone_receipt")
                        .put("observation_metadata", observationMetadata(sample.time, sample.quality)))
                    snapshot = snapshot.copy(hrCount = snapshot.hrCount + 1)
                }
            }
            "search_result" -> {
                val info = payload.getJSONObject("info")
                if (!info.getString("name").contains("Sense", ignoreCase = true) || !info.getBoolean("isConnectable")) return false
                val device = PolarDeviceInfo(info.getString("deviceId"), info.getString("address"), info.getInt("rssi"),
                    info.getString("name"), info.getBoolean("isConnectable"), info.getBoolean("hasHeartRateService"),
                    info.getBoolean("hasFileSystemService"), info.getBoolean("hasSAGRFCFileSystem"))
                snapshot = snapshot.copy(devices = (snapshot.devices + device).distinctBy { it.deviceId })
            }
            "search_error" -> snapshot = snapshot.copy(errorCode = "DISCOVERY_ERROR", status = "Sensor discovery failed; retry")
            "search_completed", "search_ended" -> {
                if (event.eventType == "search_ended" && payload.getString("reason") != "timeout") return false
                snapshot = snapshot.copy(status = if (snapshot.devices.isEmpty()) "Sensor not found. Turn it on and search again" else "Select your sensor")
            }
            else -> return false // Diagnostic-only callbacks retain their input without adding recorder behavior.
        }
        return true
    }

    private fun duplicateHr(event: PolarInputEvent, payload: JSONObject): Boolean {
        val current = HrFingerprint(event.deviceId, hrFingerprint(payload), event.receivedElapsedNs)
        val previous = lastHr
        lastHr = current
        return previous != null &&
            previous.deviceId == current.deviceId &&
            previous.payload == current.payload &&
            current.elapsedNs >= previous.elapsedNs &&
            current.elapsedNs - previous.elapsedNs <= DUPLICATE_HR_WINDOW_NS
    }

    private fun hrFingerprint(payload: JSONObject): String = listOf(
        "hr", "correctedHr", "ppgQuality", "contactStatus", "contactStatusSupported",
        "rrAvailable", "rrs", "rrsMs",
    ).joinToString("\u0000") { key -> payload.opt(key)?.toString() ?: "<null>" }
}
