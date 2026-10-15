package com.veymur.recorder.core

import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.PowerSourcesState
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.IdentityHashMap

/** Explicit SDK 8.4.0 field mappings. Never serialize an SDK model with toString(). */
object PolarInputPayload {
    fun device(info: PolarDeviceInfo) = JSONObject()
        .put("deviceId", info.deviceId).put("address", info.address).put("rssi", info.rssi)
        .put("name", info.name).put("isConnectable", info.isConnectable)
        .put("hasHeartRateService", info.hasHeartRateService).put("hasFileSystemService", info.hasFileSystemService)
        .put("hasSAGRFCFileSystem", info.hasSAGRFCFileSystem)

    fun hr(data: PolarHrData.PolarHrSample) = JSONObject()
        .put("hr", data.hr).put("correctedHr", data.correctedHr).put("ppgQuality", data.ppgQuality)
        .put("rrs", JSONArray(data.rrs.toList())).put("rrsMs", JSONArray(data.rrsMs.toList()))
        .put("rrAvailable", data.rrAvailable).put("contactStatus", data.contactStatus)
        .put("contactStatusSupported", data.contactStatusSupported)

    fun disconnect(info: PolarBleDisconnectInfo) = JSONObject()
        .put("reason", info.reason.name).put("recoveryAction", info.recoveryAction.name)
        .put("gattStatus", info.gattStatus ?: JSONObject.NULL)
        .put("deviceCommand", info.deviceCommand?.name ?: JSONObject.NULL)

    fun dis(uuid: UUID, value: String) = JSONObject().put("uuid", uuid.toString()).put("value", value)
    fun dis(info: DisInfo) = JSONObject().put("key", info.key).put("value", info.value)
    fun power(state: PowerSourcesState) = JSONObject().put("batteryPresent", state.batteryPresent.name)
        .put("wiredExternalPowerConnected", state.wiredExternalPowerConnected.name)
        .put("wirelessExternalPowerConnected", state.wirelessExternalPowerConnected.name)

    fun thermometer(data: PolarHealthThermometerData) = JSONObject()
        .put("celsius", floatBits(data.celsius)).put("fahrenheit", floatBits(data.fahrenheit))
    private fun floatBits(value: Float) = JSONObject().put("ieee754_hex",
        Integer.toHexString(java.lang.Float.floatToRawIntBits(value)).padStart(8, '0'))

    /** Graph references preserve shared/cyclic causes and suppressed exceptions without recursion. */
    fun error(error: Throwable): JSONObject {
        val ids = IdentityHashMap<Throwable, Int>()
        val pending = mutableListOf<Throwable>()
        fun identify(value: Throwable): Int = ids[value] ?: pending.size.also { ids[value] = it; pending.add(value) }
        identify(error)
        val nodes = JSONArray()
        var index = 0
        while (index < pending.size) {
            val value = pending[index]
            val causeId = value.cause?.let(::identify)
            val suppressedIds = value.suppressed.map(::identify)
            val frames = value.stackTrace.map { frame -> JSONObject()
                .put("declaring_class", frame.className).put("method_name", frame.methodName)
                .put("file_name", frame.fileName ?: JSONObject.NULL).put("line_number", frame.lineNumber)
                .put("native_method", frame.isNativeMethod) }
            nodes.put(JSONObject().put("id", index).put("class_name", value.javaClass.name)
                .put("message", value.message ?: JSONObject.NULL).put("localized_message", value.localizedMessage ?: JSONObject.NULL)
                .put("stack_trace", JSONArray(frames)).put("cause_id", causeId ?: JSONObject.NULL)
                .put("suppressed_ids", JSONArray(suppressedIds)))
            index++
        }
        return JSONObject().put("root_id", 0).put("nodes", nodes)
    }
}
