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
    private var snapshot: RecorderSnapshot
        get() = read()
        set(value) = write(value)
    /** Shared processing entry: it consumes owned input, never an SDK callback object. */
    fun process(event: PolarInputEvent): Boolean {
        val payload = event.payload()
        when (event.eventType) {
            "bluetooth" -> {
                if (!payload.getBoolean("powered")) snapshot = snapshot.copy(status = "Bluetooth disabled", connected = null, hr = null)
                record("bluetooth", JSONObject().put("powered", payload.getBoolean("powered")))
            }
            "connecting" -> snapshot = snapshot.copy(status = "Connecting to ${event.deviceId}…")
            "connected" -> {
                snapshot = snapshot.copy(connected = event.deviceId, status = "Sensor connected")
                record("connected", JSONObject().put("device", event.deviceId))
            }
            "disconnected" -> {
                snapshot = snapshot.copy(connected = null, hr = null, status = "Connection lost. The SDK is reconnecting")
                record("disconnected", JSONObject().put("device", event.deviceId)
                    .put("stream", "hr").put("quality_flags", org.json.JSONArray(listOf("missing"))))
            }
            "battery_level" -> {
                snapshot = snapshot.copy(battery = payload.getInt("level"))
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
            "search_ended" -> {
                if (payload.getString("reason") != "timeout") return false
                snapshot = snapshot.copy(status = if (snapshot.devices.isEmpty()) "Sensor not found. Turn it on and search again" else "Select your sensor")
            }
            else -> return false // Diagnostic-only callbacks retain their input without adding recorder behavior.
        }
        return true
    }
}
