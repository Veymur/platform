package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.*
import com.polar.sdk.api.*
import com.polar.sdk.api.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PolarInputBoundaryTest {
    class Sink(override val captureId: String) : PolarDiagnosticSink {
        val inputs = CopyOnWriteArrayList<PolarInputEvent>()
        val processing = CopyOnWriteArrayList<PolarInputProcessing>()
        override fun offerInput(event: PolarInputEvent) { inputs.add(event) }
        override fun offerProcessing(processing: PolarInputProcessing) { this.processing.add(processing) }
    }
    private val info = PolarDeviceInfo("SYNTHETIC", "00:00:00:00:00:00", -80, "Other device", false, true, false, true)
    private fun hr(rrs: List<Int> = emptyList(), rrsMs: List<Int> = emptyList()) =
        PolarHrData.PolarHrSample(0, -2, 101, rrs, rrsMs, false, false, true)

    @Test fun everySdkCallbackAndSearchSignalUsesOwnedLosslessPayloadBeforeWorker() {
        val deliveries = mutableListOf<PolarInputDelivery>()
        var utc = 1000L; var elapsed = 1_000_000_001L
        val input = PolarInputBoundary({ utc }, { elapsed }) { deliveries.add(it) }
        val sink = Sink("synthetic-all-callbacks"); input.setSink(sink)
        val callbacks = PolarSdkCallbacks(input)
        val search = PolarSearchInput(input)
        val rrs = mutableListOf(1024, 0, 1024); val rrsMs = mutableListOf(1000, 0, 1000)
        callbacks.blePowerStateChanged(false)
        callbacks.deviceConnecting(info); callbacks.deviceConnected(info)
        callbacks.deviceDisconnected(info, PolarBleDisconnectInfo(PolarBleDisconnectReason.UNKNOWN, PolarBleRecoveryAction.NONE))
        @Suppress("DEPRECATION")
        callbacks.deviceDisconnected(info)
        callbacks.hrNotificationReceived("SYNTHETIC", hr(rrs, rrsMs))
        callbacks.batteryLevelReceived("SYNTHETIC", 0)
        callbacks.batteryChargingStatusReceived("SYNTHETIC", ChargeState.UNKNOWN)
        callbacks.powerSourcesStateReceived("SYNTHETIC", PowerSourcesState(BatteryPresentState.UNKNOWN,
            PowerSourceState.RESERVED_FOR_FUTURE_USE, PowerSourceState.NOT_CONNECTED))
        callbacks.disInformationReceived("SYNTHETIC", UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"), "")
        callbacks.disInformationReceived("SYNTHETIC", DisInfo("firmware", "synthetic"))
        callbacks.bleSdkFeatureReady("SYNTHETIC", PolarBleApi.PolarBleSdkFeature.FEATURE_HR)
        val ready = mutableListOf(PolarBleApi.PolarBleSdkFeature.FEATURE_HR, PolarBleApi.PolarBleSdkFeature.FEATURE_HR)
        callbacks.bleSdkFeaturesReadiness("SYNTHETIC", ready, emptyList())
        callbacks.htsNotificationReceived("SYNTHETIC", PolarHealthThermometerData(java.lang.Float.intBitsToFloat(0x7fc01234), -0.0f))
        callbacks.polarCompanionDeviceAppeared("SYNTHETIC"); callbacks.polarCompanionDeviceDisappeared("SYNTHETIC")
        search.result("search-1", info); search.result("search-1", info)
        search.completed("search-1"); search.error("search-2", IllegalStateException())
        search.cancelled("search-3", "cancelled", java.util.concurrent.CancellationException("synthetic cancellation"))
        assertEquals(21, sink.inputs.size); assertEquals(21, deliveries.size); assertTrue(sink.processing.isEmpty())
        rrs.clear(); rrsMs[0] = -1; ready.clear()
        val sample = sink.inputs.single { it.eventType == "hr" }
        val payload = sample.payload()
        assertEquals(0, payload.getInt("hr")); assertEquals(-2, payload.getInt("correctedHr"))
        assertEquals(101, payload.getInt("ppgQuality")); assertFalse(payload.getBoolean("rrAvailable"))
        assertFalse(payload.getBoolean("contactStatus")); assertTrue(payload.getBoolean("contactStatusSupported"))
        assertEquals("[1024,0,1024]", payload.getJSONArray("rrs").toString())
        assertEquals("[1000,0,1000]", payload.getJSONArray("rrsMs").toString())
        payload.put("hr", 999); assertEquals(0, sample.payload().getInt("hr"))
        val device = sink.inputs.first { it.eventType == "connecting" }.payload().getJSONObject("info")
        assertEquals(8, device.length()); assertEquals(-80, device.getInt("rssi"))
        assertFalse(device.getBoolean("isConnectable")); assertTrue(device.getBoolean("hasSAGRFCFileSystem"))
        assertEquals(2, sink.inputs.single { it.eventType == "features_readiness" }.payload().getJSONArray("ready").length())
        val typed = sink.inputs.single { it.callbackVariant == "deviceDisconnected.diagnostics" }.payload().getJSONObject("diagnostics")
        assertEquals(4, typed.length()); assertTrue(typed.has("gattStatus")); assertTrue(typed.isNull("gattStatus"))
        assertTrue(typed.has("deviceCommand")); assertTrue(typed.isNull("deviceCommand"))
        assertFalse(sink.inputs.single { it.callbackVariant == "deviceDisconnected.legacy" }.payload().has("diagnostics"))
        val thermometer = sink.inputs.single { it.eventType == "thermometer" }.payload()
        assertEquals("7fc01234", thermometer.getJSONObject("celsius").getString("ieee754_hex"))
        assertEquals("80000000", thermometer.getJSONObject("fahrenheit").getString("ieee754_hex"))
        assertTrue(sink.inputs.first().toJson().isNull("device_id"))
        assertEquals("", sink.inputs.single { it.callbackVariant == "disInformationReceived.uuid" }.payload().getString("value"))
        assertTrue(sink.inputs.single { it.eventType == "search_error" }.payload().getJSONObject("error").getJSONArray("nodes").getJSONObject(0).isNull("message"))
        assertEquals(2, sink.inputs.count { it.eventType == "search_result" })
        utc = 0 // A backwards phone clock does not change receipt/processing identity.
        elapsed += 10_000_000_000
        deliveries.forEach { delivery -> input.process(delivery, { null }) { true } }
        assertEquals(21, sink.processing.size)
        assertEquals(sample.sequence, sink.processing.single { it.input === sample }.input.sequence)
        assertEquals(1000L, sample.receivedUtcMs)
        assertEquals(0L, sink.processing.first().processedUtcMs)
        assertEquals(10_000_000_000, sink.processing.first().processedElapsedNs - sample.receivedElapsedNs)
        assertTrue(sink.processing.zipWithNext().all { (a, b) -> a.processingSequence < b.processingSequence })
        // Synthetic output only, for independent host-side JSON Schema validation; never an SDK/hardware claim.
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        File(host.getExternalFilesDir(null), "polar-d2-synthetic-contract.json").writeText(JSONObject()
            .put("events", JSONArray(sink.inputs.map { it.toJson() }))
            .put("processing", JSONArray(sink.processing.map { it.toJson() })).toString())
    }

    @Test fun concurrentInputsAreUniqueAndOrderedAndSinkReplacementCannotStealPendingProcessing() {
        val deliveries = CopyOnWriteArrayList<PolarInputDelivery>()
        val elapsed = AtomicLong(1_000_000_000)
        val input = PolarInputBoundary({ 1000 }, { elapsed.getAndIncrement() }) { deliveries.add(it) }
        val first = Sink("first"); input.setSink(first)
        val start = CountDownLatch(1); val done = CountDownLatch(8)
        val errors = CopyOnWriteArrayList<Throwable>()
        repeat(8) {
            Thread {
                try { start.await(); repeat(100) { PolarSdkCallbacks(input).hrNotificationReceived("SYNTHETIC", hr()) } }
                catch (error: Throwable) { errors.add(error) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(800, deliveries.size); assertEquals(800, first.inputs.size)
        assertEquals(800, first.inputs.map { it.sequence }.toSet().size)
        assertTrue(first.inputs.zipWithNext().all { (a, b) -> a.sequence < b.sequence && a.receivedElapsedNs < b.receivedElapsedNs })
        val second = Sink("second"); input.setSink(second)
        // Deliberately reverse execution order: processing_sequence records worker order, not input order.
        deliveries.reversed().forEach { delivery -> input.process(delivery, { null }) { false } }
        assertEquals(800, first.processing.size); assertTrue(second.processing.isEmpty())
        assertEquals(first.inputs.last().sequence, first.processing.first().input.sequence)
        assertTrue(first.processing.all { it.outcome == "ignored" })
        PolarSdkCallbacks(input).batteryLevelReceived("SYNTHETIC", -1)
        assertEquals(1, second.inputs.size); assertEquals("second", second.inputs.first().captureId)
        input.setSink(null); PolarSdkCallbacks(input).batteryLevelReceived("SYNTHETIC", -1)
        assertEquals(1, second.inputs.size); assertNull(deliveries.last().event.captureId)
        assertThrows(IllegalArgumentException::class.java) { deliveries.last().event.toJson() }
    }

    @Test fun diagnosticSinkFailureDoesNotStopProcessingAndProcessorFailureIsReportedAndRethrown() {
        val deliveries = mutableListOf<PolarInputDelivery>()
        val input = PolarInputBoundary({ 1000 }, { 1000000000 }) { deliveries.add(it) }
        input.setSink(object : PolarDiagnosticSink {
            override val captureId = "failing-sink"
            override fun offerInput(event: PolarInputEvent) { throw IllegalStateException("synthetic sink failure") }
            override fun offerProcessing(processing: PolarInputProcessing) { throw IllegalStateException("synthetic sink failure") }
        })
        PolarSdkCallbacks(input).hrNotificationReceived("SYNTHETIC", hr())
        var processed = false
        input.process(deliveries.single(), { null }) { processed = true; true }
        assertTrue(processed); assertEquals(2L, input.sinkFailures.get())
        val sink = Sink("processor-error"); input.setSink(sink)
        PolarSdkCallbacks(input).hrNotificationReceived("SYNTHETIC", hr())
        val failure = IllegalStateException("synthetic processor failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            input.process(deliveries.last(), { "synthetic-session" }) { throw failure }
        })
        assertEquals("failed", sink.processing.single().outcome)
        assertEquals("synthetic-session", sink.processing.single().sessionId)
        assertEquals("java.lang.IllegalStateException", JSONObject(sink.processing.single().errorJson!!).getJSONArray("nodes").getJSONObject(0).getString("class_name"))
    }
    @Test fun throwableGraphCopiesStackCausesSuppressedAndCyclesWithoutToString() {
        val cause = IllegalArgumentException("synthetic cause")
        val root = IllegalStateException(null as String?)
        root.initCause(cause); cause.initCause(root); root.addSuppressed(cause)
        root.stackTrace = arrayOf(StackTraceElement("Synthetic", "method", null, -2))
        val original = PolarInputPayload.error(root).toString()
        root.stackTrace = emptyArray()
        val graph = JSONObject(original)
        assertEquals(0, graph.getInt("root_id")); assertEquals(2, graph.getJSONArray("nodes").length())
        val first = graph.getJSONArray("nodes").getJSONObject(0)
        assertTrue(first.isNull("message")); assertTrue(first.isNull("localized_message"))
        assertEquals(1, first.getInt("cause_id")); assertEquals(1, first.getJSONArray("suppressed_ids").getInt(0))
        val frame = first.getJSONArray("stack_trace").getJSONObject(0)
        assertEquals("Synthetic", frame.getString("declaring_class")); assertEquals("method", frame.getString("method_name"))
        assertTrue(frame.isNull("file_name")); assertEquals(-2, frame.getInt("line_number")); assertTrue(frame.getBoolean("native_method"))
        assertEquals(0, graph.getJSONArray("nodes").getJSONObject(1).getInt("cause_id"))
    }

    @Test fun pinnedSdkPublicFieldsAndCallbackSignaturesMatchInventory() {
        fun fields(type: Class<*>) = type.declaredMethods.filter {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                (it.name.startsWith("get") || it.name.startsWith("is"))
        }.map { if (it.name.startsWith("get")) it.name.removePrefix("get").replaceFirstChar { char -> char.lowercaseChar() } else it.name }.toSet()
        assertEquals(setOf("hr", "correctedHr", "ppgQuality", "rrs", "rrsMs", "rrAvailable", "contactStatus", "contactStatusSupported"), fields(PolarHrData.PolarHrSample::class.java))
        assertEquals(setOf("deviceId", "address", "rssi", "name", "isConnectable", "hasHeartRateService", "hasFileSystemService", "hasSAGRFCFileSystem"), fields(PolarDeviceInfo::class.java))
        assertEquals(setOf("reason", "recoveryAction", "gattStatus", "deviceCommand"), fields(PolarBleDisconnectInfo::class.java))
        assertEquals(setOf("key", "value"), fields(DisInfo::class.java))
        assertEquals(setOf("celsius", "fahrenheit"), fields(PolarHealthThermometerData::class.java))
        assertEquals(setOf("batteryPresent", "wiredExternalPowerConnected", "wirelessExternalPowerConnected"), fields(PowerSourcesState::class.java))
        val signatures = PolarBleApiCallbackProvider::class.java.declaredMethods.filter {
            !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.map { it.name + "(" + it.parameterTypes.joinToString(",") { type -> type.simpleName } + ")" }.toSet()
        assertEquals(setOf(
            "blePowerStateChanged(boolean)", "deviceConnected(PolarDeviceInfo)", "deviceConnecting(PolarDeviceInfo)",
            "deviceDisconnected(PolarDeviceInfo)", "deviceDisconnected(PolarDeviceInfo,PolarBleDisconnectInfo)",
            "bleSdkFeatureReady(String,PolarBleSdkFeature)", "bleSdkFeaturesReadiness(String,List,List)",
            "disInformationReceived(String,UUID,String)", "disInformationReceived(String,DisInfo)",
            "batteryLevelReceived(String,int)", "batteryChargingStatusReceived(String,ChargeState)",
            "powerSourcesStateReceived(String,PowerSourcesState)", "hrNotificationReceived(String,PolarHrSample)",
            "htsNotificationReceived(String,PolarHealthThermometerData)",
            "polarCompanionDeviceAppeared(String)", "polarCompanionDeviceDisappeared(String)"), signatures)
    }

}
