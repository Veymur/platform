package com.veymur.recorder.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Independent SQLCipher owner. SDK/recorder offers never open a DB, serialize an envelope, or wait for commit. */
class DiagnosticJournal(
    private val context: Context,
    val policy: DiagnosticPolicy = DiagnosticPolicy(),
    private val utcNow: () -> Long = { System.currentTimeMillis() },
    private val elapsedNowNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val freeBytes: () -> Long = { diagnosticFreeBytes(context) },
) {
    companion object { private val openPaths = mutableSetOf<String>() }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // Timed wait/notify for the single writer.
    private val gate = Object()
    private val controls = ArrayDeque<() -> Unit>()
    private val handles = mutableMapOf<String, Capture>()
    private var active: Capture? = null
    private var closing = false
    private var closed = false
    private var writerFailed = false
    private var initializationFailed = false
    private val closeResult = CompletableFuture<Unit>()
    val ready = CompletableFuture<Int>()
    @Volatile var metrics = DiagnosticWriterMetrics(); private set
    private var storeOnWriter: DiagnosticStore? = null
    private val thread = Thread(::runWriter, "veymur-polar-diagnostic").apply { start() }

    private class Pending(val input: PolarInputEvent?, val processing: PolarInputProcessing?, val cost: Long, val offeredNs: Long)
    private class Capture(val handle: DiagnosticCapture, var status: DiagnosticCaptureStatus) {
        val queue = ArrayDeque<Pending>()
        val expectedProcessing = mutableSetOf<Long>()
        var bufferedRecords = 0
        var bufferedBytes = 0L
        var acceptInputs = true
        var acceptProcessing = true
        var stopDeadlineNs: Long? = null
        val stopped = CompletableFuture<DiagnosticCaptureStatus>()
    }

    fun startCapture(processRunId: String, configurationJson: String, environmentJson: String): CompletableFuture<DiagnosticCapture> = submit { store ->
        synchronized(gate) {
            if (closing) throw DiagnosticStorageError("journal_unavailable")
            if (active != null) throw DiagnosticStorageError("capture_active")
            if (writerFailed) throw DiagnosticStorageError("writer_failed")
        }
        if (store.list().size >= policy.maxCaptures) throw DiagnosticStorageError("retention_limit")
        if (configurationJson.length + environmentJson.length > 16384 || processRunId.isBlank() || processRunId.length > 128) throw DiagnosticStorageError("invalid_metadata")
        try { JSONObject(configurationJson); JSONObject(environmentJson) }
        catch (_: Exception) { throw DiagnosticStorageError("invalid_metadata") }
        if (freeBytes() < policy.minFreeBytes) throw DiagnosticStorageError("low_storage")
        val handle = DiagnosticCapture(this, UUID.randomUUID().toString())
        val status = DiagnosticCaptureStatus(handle.captureId, processRunId, "active", utcNow(), elapsedNowNs())
        store.create(status, configurationJson, environmentJson, policyJson())
        synchronized(gate) { Capture(handle, status).also { handles[handle.captureId] = it; active = it } }
        handle
    }
    internal fun liveStatus(handle: DiagnosticCapture): DiagnosticCaptureStatus = synchronized(gate) {
        val a = capture(handle)
        if (a.status.state != "active" && a.status.state != "stopping") a.status
        else a.status.copy(unprocessedInputs = a.expectedProcessing.size.toLong())
    }
    internal fun offerInput(handle: DiagnosticCapture, event: PolarInputEvent) {
        synchronized(gate) {
            val a = capture(handle)
            if (!a.acceptInputs) return
            a.status = a.status.copy(receivedEvents = a.status.receivedEvents!! + 1)
            if (durationExpired(a)) {
                a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + 1)
                fault(a, "duration_limit"); return
            }
            if (event.captureId != handle.captureId || event.processRunId != a.status.processRunId || event.sequence !in 1..9007199254740991L) {
                a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + 1); fault(a, "serialization_failed"); return
            }
            val chars = textChars(event)
            val cost = 512L + chars * 2
            if (chars > policy.maxRecordChars) {
                a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + 1); fault(a, "size_limit"); return
            }
            if (a.expectedProcessing.size >= policy.queueRecords || !room(a, cost)) {
                a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + 1); fault(a, "queue_overflow"); return
            }
            if (!a.expectedProcessing.add(event.sequence)) {
                a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + 1); fault(a, "serialization_failed"); return
            }
            a.status = a.status.copy(acceptedEvents = a.status.acceptedEvents!! + 1)
            enqueue(a, Pending(event, null, cost, elapsedNowNs()))
        }
    }
    internal fun offerProcessing(handle: DiagnosticCapture, processing: PolarInputProcessing) {
        synchronized(gate) {
            val a = capture(handle)
            if (!a.acceptProcessing) return
            // A rejected input is not persisted; its later processing must not create an orphan row.
            if (!a.expectedProcessing.remove(processing.input.sequence)) return
            a.status = a.status.copy(receivedProcessing = a.status.receivedProcessing!! + 1)
            if (processing.input.captureId != handle.captureId || processing.input.processRunId != a.status.processRunId) {
                a.status = a.status.copy(knownLostProcessing = a.status.knownLostProcessing + 1); fault(a, "serialization_failed"); return
            }
            val length = (processing.errorJson?.length ?: 0).toLong() + (processing.sessionId?.length ?: 0) + processing.outcome.length
            val cost = 512L + (textChars(processing.input) + length) * 2
            if (length > policy.maxRecordChars || !room(a, cost)) {
                a.status = a.status.copy(knownLostProcessing = a.status.knownLostProcessing + 1)
                fault(a, if (length > policy.maxRecordChars) "size_limit" else "queue_overflow"); return
            }
            a.status = a.status.copy(acceptedProcessing = a.status.acceptedProcessing!! + 1)
            enqueue(a, Pending(null, processing, cost, elapsedNowNs()))
        }
    }
    private fun textChars(e: PolarInputEvent): Long = e.payloadJson.length.toLong() + e.processRunId.length +
        (e.captureId?.length ?: 0) + e.source.length + e.eventType.length + e.callbackVariant.length + (e.deviceId?.length ?: 0)
    private fun room(a: Capture, cost: Long) = a.bufferedRecords < policy.queueRecords && a.bufferedBytes + cost <= policy.queueBytes
    private fun enqueue(a: Capture, value: Pending) {
        a.queue.addLast(value); a.bufferedRecords++; a.bufferedBytes += value.cost; gate.notifyAll()
    }
    private fun capture(handle: DiagnosticCapture) = handles[handle.captureId]?.takeIf { it.handle === handle }
        ?: throw DiagnosticStorageError("unknown_capture")
    private fun durationExpired(a: Capture) = elapsedNowNs() - a.status.startedElapsedNs >= policy.maxDurationMs * 1_000_000
    private fun stopInputs(a: Capture) {
        if (a.acceptInputs) {
            a.acceptInputs = false
            a.status = a.status.copy(state = "stopping", endedUtcMs = utcNow(), endedElapsedNs = elapsedNowNs())
            a.stopDeadlineNs = elapsedNowNs() + policy.drainTimeoutMs * 1_000_000
        }
        gate.notifyAll()
    }
    private fun fault(a: Capture, code: String, error: Exception? = null) {
        if (a.status.lastErrorCode == null) a.status = a.status.copy(lastErrorCode = code, lastErrorClass = error?.javaClass?.simpleName)
        stopInputs(a)
    }
    internal fun stop(handle: DiagnosticCapture): CompletableFuture<DiagnosticCaptureStatus> = synchronized(gate) {
        capture(handle).also { stopInputs(it) }.stopped
    }
    /** Native barrier for an available prefix; it does not stop input or promise to include later offers. */
    fun flush(handle: DiagnosticCapture): CompletableFuture<DiagnosticCaptureStatus> = submit { store ->
        val a = synchronized(gate) { capture(handle) }
        val count = synchronized(gate) { a.queue.size }
        var left = count
        while (left > 0) { val size = minOf(left, policy.batchRecords); drain(store, a, size); left -= size }
        if (writerFailed) throw DiagnosticStorageError("write_failed")
        synchronized(gate) { a.status }
    }
    fun listCaptures(): CompletableFuture<List<DiagnosticCaptureStatus>> = submit { store ->
        val stored = store.list()
        synchronized(gate) { stored.map { saved -> handles[saved.captureId]?.let { liveStatus(it.handle) } ?: saved } }
    }
    fun readInput(captureId: String, sequence: Long): CompletableFuture<String?> = submit { it.readInput(captureId, sequence) }
    fun deleteCapture(captureId: String): CompletableFuture<Boolean> = submit { store ->
        synchronized(gate) { if (active?.handle?.captureId == captureId) throw DiagnosticStorageError("capture_active") }
        val deleted = store.delete(captureId)
        synchronized(gate) { handles.remove(captureId) }
        deleted
    }
    fun closeAsync(): CompletableFuture<Unit> = synchronized(gate) {
        closing = true; active?.let(::stopInputs); gate.notifyAll(); closeResult
    }
    private fun <T> submit(action: (DiagnosticStore) -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        synchronized(gate) {
            if (closing || closed || initializationFailed) result.completeExceptionally(DiagnosticStorageError("journal_unavailable"))
            else if (controls.size >= 16) result.completeExceptionally(DiagnosticStorageError("control_queue_full"))
            else {
                controls.addLast {
                    try {
                        if (initializationFailed) throw DiagnosticStorageError("journal_unavailable")
                        result.complete(action(requireNotNull(storeOnWriter)))
                    }
                    catch (error: Exception) { result.completeExceptionally(if (error is DiagnosticStorageError) error else DiagnosticStorageError("storage_operation_failed")) }
                }
                gate.notifyAll()
            }
        }
        return result
    }
    private fun runWriter() {
        var path: String? = null
        var claimed = false
        try {
            path = context.getDatabasePath("polar-diagnostic.db").canonicalPath
            synchronized(openPaths) {
                if (!openPaths.add(path)) throw DiagnosticStorageError("already_open")
                claimed = true
            }
            val store = DiagnosticStore(context, policy); storeOnWriter = store
            ready.complete(store.recoverInterrupted())
            while (true) {
                val control = synchronized(gate) { if (controls.isEmpty()) null else controls.removeFirst() }
                control?.invoke()
                val a = synchronized(gate) { active }
                if (a != null) pump(store, a)
                synchronized(gate) {
                    if (closing && active == null && controls.isEmpty()) break
                    if (controls.isEmpty()) gate.wait(waitMs())
                }
            }
            store.close(); storeOnWriter = null
            synchronized(gate) { closed = true }
            synchronized(openPaths) { openPaths.remove(path); claimed = false }
            if (writerFailed) closeResult.completeExceptionally(DiagnosticStorageError("write_failed")) else closeResult.complete(Unit)
        } catch (error: Exception) {
            val sanitized = if (error is DiagnosticStorageError) error else DiagnosticStorageError("journal_open_or_writer_failed")
            synchronized(gate) {
                initializationFailed = true; closed = true
                active?.let { a ->
                    a.acceptInputs = false; a.acceptProcessing = false
                    a.status = a.status.copy(state = "incomplete", unknownTail = true, checkpointPersisted = false,
                        lastErrorCode = "writer_failed", lastErrorClass = error.javaClass.simpleName)
                    a.stopped.completeExceptionally(sanitized)
                }
            }
            ready.completeExceptionally(sanitized)
            // Release pending command futures without ever exposing SQL text or payload in exceptions.
            while (true) {
                val task = synchronized(gate) { if (controls.isEmpty()) null else controls.removeFirst() } ?: break
                task.invoke()
            }
            try { storeOnWriter?.close() } catch (_: Exception) { }
            storeOnWriter = null
            if (claimed) synchronized(openPaths) { openPaths.remove(path); claimed = false }
            closeResult.completeExceptionally(sanitized)
        } finally { if (claimed) synchronized(openPaths) { openPaths.remove(path) } }
    }
    private fun waitMs(): Long {
        val a = active ?: return 1000
        val now = elapsedNowNs()
        if (a.queue.size >= policy.batchRecords) return 1
        val commit = a.queue.peekFirst()?.let { (it.offeredNs - now) / 1_000_000 + policy.commitIntervalMs } ?: 1000
        val end = a.stopDeadlineNs?.let { (it - now) / 1_000_000 }
            ?: (a.status.startedElapsedNs - now) / 1_000_000 + policy.maxDurationMs
        return minOf(commit, end, 1000).coerceAtLeast(1)
    }
    private fun pump(store: DiagnosticStore, a: Capture) {
        val count = synchronized(gate) {
            if (a.acceptInputs && durationExpired(a)) fault(a, "duration_limit")
            if (!a.acceptInputs && elapsedNowNs() >= (a.stopDeadlineNs ?: Long.MAX_VALUE) && a.expectedProcessing.isNotEmpty()) {
                fault(a, "processing_timeout"); a.expectedProcessing.clear(); a.acceptProcessing = false
            }
            val first = a.queue.peekFirst()
            if (first != null && (!a.acceptInputs || a.queue.size >= policy.batchRecords ||
                    elapsedNowNs() - first.offeredNs >= policy.commitIntervalMs * 1_000_000)) minOf(a.queue.size, policy.batchRecords) else 0
        }
        if (count > 0) drain(store, a, count)
        val finish = synchronized(gate) { !a.acceptInputs && a.queue.isEmpty() && a.expectedProcessing.isEmpty() }
        if (finish) finish(store, a)
    }
    private fun drain(store: DiagnosticStore, a: Capture, count: Int) {
        val batch = synchronized(gate) {
            val items = mutableListOf<Pending>()
            repeat(minOf(count, a.queue.size)) { items.add(a.queue.removeFirst()) }
            items
        }
        if (batch.isEmpty()) return
        val rows = try {
            batch.map { item ->
                if (item.input != null) DiagnosticRow(item.input.sequence, null, item.input.toJson().toString())
                else requireNotNull(item.processing).let { DiagnosticRow(it.processingSequence, it.input.sequence, it.toJson().toString()) }
            }
        } catch (error: Exception) { abort(a, batch, "serialization_failed", error); return }
        val bytes = rows.sumOf { it.bytes }
        if (synchronized(gate) { a.status.savedBytes + bytes > policy.maxCaptureBytes }) { abort(a, batch, "size_limit"); return }
        if (freeBytes() < policy.minFreeBytes) { abort(a, batch, "low_storage"); return }
        val candidate = synchronized(gate) {
            a.status.copy(savedEvents = a.status.savedEvents + rows.count { it.inputSequence == null },
                savedProcessing = a.status.savedProcessing + rows.count { it.inputSequence != null }, savedBytes = a.status.savedBytes + bytes,
                lastCommittedSequence = maxNullable(a.status.lastCommittedSequence, rows.filter { it.inputSequence == null }.maxOfOrNull { it.sequence }),
                lastCommittedProcessingSequence = maxNullable(a.status.lastCommittedProcessingSequence, rows.filter { it.inputSequence != null }.maxOfOrNull { it.sequence }))
        }
        val before = elapsedNowNs()
        try { store.commit(candidate, rows) }
        catch (error: Exception) { writerFailed = true; abort(a, batch, "write_failed", error); return }
        val after = elapsedNowNs()
        metrics = metrics.copy(commits = metrics.commits + 1, totalCommitNs = metrics.totalCommitNs + (after - before).coerceAtLeast(0),
            maxCommitNs = maxOf(metrics.maxCommitNs, (after - before).coerceAtLeast(0)),
            maxOfferToCommitNs = maxOf(metrics.maxOfferToCommitNs, (after - batch.minOf { it.offeredNs }).coerceAtLeast(0)))
        synchronized(gate) {
            a.status = a.status.copy(savedEvents = candidate.savedEvents, savedProcessing = candidate.savedProcessing, savedBytes = candidate.savedBytes,
                lastCommittedSequence = candidate.lastCommittedSequence, lastCommittedProcessingSequence = candidate.lastCommittedProcessingSequence)
            batch.forEach { a.bufferedRecords--; a.bufferedBytes -= it.cost }
        }
    }
    private fun abort(a: Capture, batch: List<Pending>, code: String, error: Exception? = null) = synchronized(gate) {
        val lost = batch + a.queue.toList(); a.queue.clear()
        a.status = a.status.copy(knownLostEvents = a.status.knownLostEvents + lost.count { it.input != null },
            knownLostProcessing = a.status.knownLostProcessing + lost.count { it.processing != null })
        a.bufferedBytes = 0; a.bufferedRecords = 0; a.expectedProcessing.clear(); a.acceptProcessing = false
        fault(a, code, error)
    }
    private fun finish(store: DiagnosticStore, a: Capture) {
        val candidate = synchronized(gate) {
            a.acceptProcessing = false
            a.status.copy(state = if (a.status.lastErrorCode == null) "complete" else "incomplete",
                unprocessedInputs = (a.status.savedEvents - a.status.savedProcessing).coerceAtLeast(0))
        }
        try {
            store.commit(candidate, emptyList())
            synchronized(gate) { a.status = candidate; active = null }
            if (writerFailed) a.stopped.completeExceptionally(DiagnosticStorageError("write_failed")) else a.stopped.complete(candidate)
        } catch (error: Exception) {
            writerFailed = true
            synchronized(gate) {
                a.status = candidate.copy(state = "incomplete", unknownTail = true, checkpointPersisted = false,
                    lastErrorCode = "write_failed", lastErrorClass = error.javaClass.simpleName)
                active = null
            }
            a.stopped.completeExceptionally(DiagnosticStorageError("write_failed"))
        }
    }
    private fun maxNullable(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)
    private fun policyJson() = JSONObject().put("queue_records", policy.queueRecords).put("queue_bytes", policy.queueBytes)
        .put("max_record_chars", policy.maxRecordChars).put("batch_records", policy.batchRecords)
        .put("commit_interval_ms", policy.commitIntervalMs).put("drain_timeout_ms", policy.drainTimeoutMs)
        .put("max_capture_bytes", policy.maxCaptureBytes).put("max_duration_ms", policy.maxDurationMs)
        .put("max_database_bytes", policy.maxDatabaseBytes).put("max_captures", policy.maxCaptures)
        .put("min_free_bytes", policy.minFreeBytes).toString()
}

data class DiagnosticWriterMetrics(val commits: Long = 0, val totalCommitNs: Long = 0, val maxCommitNs: Long = 0, val maxOfferToCommitNs: Long = 0)

@SuppressLint("UsableSpace") // Reserve actual free space; do not budget cache eviction by other apps.
private fun diagnosticFreeBytes(context: Context) = context.filesDir.usableSpace
