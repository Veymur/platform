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
        .put("session_id", sessionId ?: JSONObject.NULL).put("command_id", JSONObject.NULL)
        .put("outcome", outcome).put("error", errorJson?.let(::JSONObject) ?: JSONObject.NULL)
}

/** D2 test seam. Offers must be non-blocking; durable, bounded storage belongs to D3. */
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
        private val processRunId = UUID.randomUUID().toString()
        private val inputSequence = AtomicLong()
        private val processingSequence = AtomicLong()
    }
    private val entrance = Any()
    private var sink: PolarDiagnosticSink? = null
    val sinkFailures = AtomicLong()

    /** Native/test seam only; no UI command or implicit recording of diagnostic data in D2. */
    fun setSink(target: PolarDiagnosticSink?) = synchronized(entrance) { sink = target }

    fun receive(eventType: String, variant: String, deviceId: String?, source: String = "polar_sdk", payload: () -> JSONObject) {
        synchronized(entrance) {
            // Time and sequence are sampled before copying and before the recorder worker queue.
            val utc = utcNow()
            val elapsed = elapsedNowNs()
            val sequence = inputSequence.incrementAndGet()
            val target = sink
            val event = PolarInputEvent(target?.captureId, processRunId, sequence, utc, elapsed,
                source, eventType, variant, deviceId, payload().toString())
            offer { target?.offerInput(event) }
            dispatch(PolarInputDelivery(event, target))
        }
    }

    /** Called on the recorder worker. Live and future replay feed the same processor here. */
    fun process(delivery: PolarInputDelivery, sessionId: () -> String?, processor: (PolarInputEvent) -> Boolean) {
        val sequence = processingSequence.incrementAndGet()
        val utc = utcNow()
        val elapsed = elapsedNowNs()
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
