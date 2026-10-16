package com.veymur.recorder.core

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Owned JSON text: no SDK object, mutable list, JSONObject, or JS reference crosses the boundary. */
data class PolarInputEvent(
    val captureId: String?,
    val processRunId: String,
    val sequence: Long,
    val receivedUtcMs: Long,
    val receivedElapsedNs: Long,
    val source: String,
    val eventType: String,
    val callbackVariant: String,
    val deviceId: String?,
    val payloadJson: String,
    // Live-only receipt context. Legacy diagnostic v1 captures did not persist timezone.
    val receivedTimezoneId: String? = null,
) {
    fun payload() = JSONObject(payloadJson)
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1).put("capture_id", requireNotNull(captureId) { "Input was not captured" })
        .put("process_run_id", processRunId).put("sequence", sequence)
        .put("received_utc_ms", receivedUtcMs).put("received_elapsed_ns", receivedElapsedNs.toString())
        .put("source", source).put("event_type", eventType).put("callback_variant", callbackVariant)
        .put("device_id", deviceId ?: JSONObject.NULL).put("payload", payload())
}

data class PolarInputProcessing(
    val input: PolarInputEvent,
    val processingSequence: Long,
    val processedUtcMs: Long,
    val processedElapsedNs: Long,
    val sessionId: String?,
    val outcome: String,
    val errorJson: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1).put("capture_id", requireNotNull(input.captureId))
        .put("process_run_id", input.processRunId).put("processing_sequence", processingSequence)
        .put("input_sequence", input.sequence).put("processed_utc_ms", processedUtcMs)
        .put("processed_elapsed_ns", processedElapsedNs.toString())
        .put("session_id", sessionId ?: JSONObject.NULL).put("command_id",
            if (input.source == "app_command") input.payload().getString("command_id") else JSONObject.NULL)
        .put("outcome", outcome).put("error", errorJson?.let(::JSONObject) ?: JSONObject.NULL)
}

/** Non-blocking native sink. D3 DiagnosticCapture supplies bounded asynchronous storage. */
interface PolarDiagnosticSink {
    val captureId: String
    fun offerInput(event: PolarInputEvent)
    fun offerProcessing(processing: PolarInputProcessing)
}

/** Keeps the sink selected at receipt, even if a later capture replaces it before processing. */
class PolarInputDelivery(val event: PolarInputEvent, internal val sink: PolarDiagnosticSink?)

class PolarInputBoundary(
    private val utcNow: () -> Long,
    private val elapsedNowNs: () -> Long,
    private val dispatch: (PolarInputDelivery) -> Unit,
) {
    companion object {
        val processRunId = UUID.randomUUID().toString()
        private val inputSequence = AtomicLong()
        private val processingSequence = AtomicLong()
    }
    private val entrance = Any()
    private val processingEntrance = Any()
    private data class Stamp(val sequence: Long, val utc: Long, val elapsed: Long)
    // Command acceptance can run on main while callback/commit processing runs on the writer.
    private fun processingStamp() = synchronized(processingEntrance) {
        Stamp(processingSequence.incrementAndGet(), utcNow(), elapsedNowNs())
    }
    private var sink: PolarDiagnosticSink? = null
    val sinkFailures = AtomicLong()

    /** Native/test seam only; no UI command or implicit recording of diagnostic data in D2. */
    fun setSink(target: PolarDiagnosticSink?) = synchronized(entrance) { sink = target }

    fun receive(eventType: String, variant: String, deviceId: String?, source: String = "polar_sdk", payload: () -> JSONObject) {
        synchronized(entrance) {
            // Time and sequence are sampled before copying and before the recorder worker queue.
            val utc = utcNow()
            val elapsed = elapsedNowNs()
            val timezone = java.time.ZoneId.systemDefault().id
            val sequence = inputSequence.incrementAndGet()
            val target = sink
            val event = PolarInputEvent(target?.captureId, processRunId, sequence, utc, elapsed,
                source, eventType, variant, deviceId, payload().toString(), timezone)
            offer { target?.offerInput(event) }
            dispatch(PolarInputDelivery(event, target))
        }
    }

    /** Session command receipt shares the callback sequence/sink selection lock. */
    fun command(name: String, route: Boolean): PolarInputDelivery = synchronized(entrance) {
        val target = sink
        val event = PolarInputEvent(target?.captureId, processRunId, inputSequence.incrementAndGet(), utcNow(), elapsedNowNs(),
            "app_command", "command_requested", "execute", null, JSONObject().put("command_id", UUID.randomUUID().toString())
                .put("name", name).put("argument", JSONObject.NULL).put("route", route).toString())
        offer { target?.offerInput(event) }
        PolarInputDelivery(event, target)
    }
    fun commandResult(delivery: PolarInputDelivery?, sessionId: String?, outcome: String, error: Exception? = null) {
        if (delivery == null) return
        val stamp = processingStamp()
        offer { delivery.sink?.offerProcessing(PolarInputProcessing(delivery.event, stamp.sequence,
            stamp.utc, stamp.elapsed, sessionId, outcome, error?.let { PolarInputPayload.error(it).toString() })) }
    }
    /** Terminal timestamp is worker execution time, not a delayed main-thread acknowledgement. */
    fun <T> commandProcess(delivery: PolarInputDelivery?, sessionId: () -> String?, action: () -> T): T {
        if (delivery == null) return action()
        val stamp = processingStamp()
        val sequence = stamp.sequence; val utc = stamp.utc; val elapsed = stamp.elapsed
        try {
            val result = action()
            offer { delivery.sink?.offerProcessing(PolarInputProcessing(delivery.event, sequence, utc, elapsed, sessionId(), "committed", null)) }
            return result
        } catch (error: Exception) {
            offer { delivery.sink?.offerProcessing(PolarInputProcessing(delivery.event, sequence, utc, elapsed, sessionId(),
                if (error is RecorderError) "rejected" else "failed", PolarInputPayload.error(error).toString())) }
            throw error
        }
    }

    /** Called on the recorder worker. Live and future replay feed the same processor here. */
    fun process(delivery: PolarInputDelivery, sessionId: () -> String?, processor: (PolarInputEvent) -> Boolean) {
        val stamp = processingStamp()
        val sequence = stamp.sequence; val utc = stamp.utc; val elapsed = stamp.elapsed
        try {
            val applied = processor(delivery.event)
            offer { delivery.sink?.offerProcessing(PolarInputProcessing(delivery.event, sequence, utc, elapsed,
                sessionId(), if (applied) "applied" else "ignored", null)) }
        } catch (error: Exception) {
            offer { delivery.sink?.offerProcessing(PolarInputProcessing(delivery.event, sequence, utc, elapsed,
                sessionId(), "failed", PolarInputPayload.error(error).toString())) }
            throw error
        }
    }

    private inline fun offer(action: () -> Unit) {
        try { action() } catch (_: Exception) { sinkFailures.incrementAndGet() }
    }
}
