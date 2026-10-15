package com.veymur.recorder.core

import com.polar.sdk.api.model.PolarDeviceInfo
import org.json.JSONObject

/** Capture every result before recorder name/connectable filtering or UI deduplication. */
class PolarSearchInput(private val input: PolarInputBoundary) {
    fun result(subscriptionId: String, info: PolarDeviceInfo) =
        input.receive("search_result", "searchForDevice.collect", info.deviceId) {
            JSONObject().put("subscription_id", subscriptionId).put("info", PolarInputPayload.device(info))
        }
    fun completed(subscriptionId: String) = input.receive("search_completed", "searchForDevice.complete", null) {
        JSONObject().put("subscription_id", subscriptionId)
    }
    fun error(subscriptionId: String, error: Throwable) = input.receive("search_error", "searchForDevice.error", null) {
        JSONObject().put("subscription_id", subscriptionId).put("error", PolarInputPayload.error(error))
    }
    fun cancelled(subscriptionId: String, reason: String, error: Throwable) =
        input.receive("search_ended", "searchForDevice.cancel", null, source = "diagnostic") {
            JSONObject().put("subscription_id", subscriptionId).put("reason", reason).put("error", PolarInputPayload.error(error))
        }
}
