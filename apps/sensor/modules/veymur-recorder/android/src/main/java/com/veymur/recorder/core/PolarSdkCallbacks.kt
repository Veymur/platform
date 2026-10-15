package com.veymur.recorder.core

import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.ChargeState
import com.polar.androidcommunications.api.ble.model.gatt.client.PowerSourcesState
import com.polar.sdk.api.*
import com.polar.sdk.api.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Every base API callback in the pinned SDK, including currently inactive HTS/companion callbacks. */
class PolarSdkCallbacks(private val input: PolarInputBoundary) : PolarBleApiCallback() {
    override fun blePowerStateChanged(powered: Boolean) = input.receive("bluetooth", "blePowerStateChanged", null) {
        JSONObject().put("powered", powered)
    }
    override fun deviceConnecting(info: PolarDeviceInfo) = input.receive("connecting", "deviceConnecting", info.deviceId) {
        JSONObject().put("info", PolarInputPayload.device(info))
    }
    override fun deviceConnected(info: PolarDeviceInfo) = input.receive("connected", "deviceConnected", info.deviceId) {
        JSONObject().put("info", PolarInputPayload.device(info))
    }
    override fun deviceDisconnected(info: PolarDeviceInfo, diagnostics: PolarBleDisconnectInfo) =
        input.receive("disconnected", "deviceDisconnected.diagnostics", info.deviceId) {
            JSONObject().put("info", PolarInputPayload.device(info)).put("diagnostics", PolarInputPayload.disconnect(diagnostics))
        }
    @Suppress("DEPRECATION")
    override fun deviceDisconnected(info: PolarDeviceInfo) = input.receive("disconnected", "deviceDisconnected.legacy", info.deviceId) {
        JSONObject().put("info", PolarInputPayload.device(info))
    }
    override fun hrNotificationReceived(identifier: String, data: PolarHrData.PolarHrSample) =
        input.receive("hr", "hrNotificationReceived", identifier) { PolarInputPayload.hr(data) }
    override fun batteryLevelReceived(identifier: String, level: Int) = input.receive("battery_level", "batteryLevelReceived", identifier) {
        JSONObject().put("level", level)
    }
    override fun batteryChargingStatusReceived(identifier: String, chargingStatus: ChargeState) =
        input.receive("battery_charging", "batteryChargingStatusReceived", identifier) { JSONObject().put("chargingStatus", chargingStatus.name) }
    override fun powerSourcesStateReceived(identifier: String, powerSourcesState: PowerSourcesState) =
        input.receive("power_sources", "powerSourcesStateReceived", identifier) { PolarInputPayload.power(powerSourcesState) }
    override fun disInformationReceived(identifier: String, uuid: UUID, value: String) =
        input.receive("device_info", "disInformationReceived.uuid", identifier) { PolarInputPayload.dis(uuid, value) }
    override fun disInformationReceived(identifier: String, disInfo: DisInfo) =
        input.receive("device_info", "disInformationReceived.key", identifier) { PolarInputPayload.dis(disInfo) }
    override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) =
        input.receive("thermometer", "htsNotificationReceived", identifier) { PolarInputPayload.thermometer(data) }
    override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) =
        input.receive("feature_ready", "bleSdkFeatureReady", identifier) { JSONObject().put("feature", feature.name) }
    override fun bleSdkFeaturesReadiness(identifier: String, ready: List<PolarBleApi.PolarBleSdkFeature>, unavailable: List<PolarBleApi.PolarBleSdkFeature>) =
        input.receive("features_readiness", "bleSdkFeaturesReadiness", identifier) {
            JSONObject().put("ready", JSONArray(ready.map { it.name })).put("unavailable", JSONArray(unavailable.map { it.name }))
        }
    override fun polarCompanionDeviceAppeared(identifier: String) = input.receive("companion_appeared", "polarCompanionDeviceAppeared", identifier) { JSONObject() }
    override fun polarCompanionDeviceDisappeared(identifier: String) = input.receive("companion_disappeared", "polarCompanionDeviceDisappeared", identifier) { JSONObject() }
}
