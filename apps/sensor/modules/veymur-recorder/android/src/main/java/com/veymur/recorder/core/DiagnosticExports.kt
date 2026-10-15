package com.veymur.recorder.core

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticExportMetadata(val status: DiagnosticCaptureStatus, val configuration: String, val environment: String)

/** ZIP work never holds the diagnostic writer or a cursor while doing file I/O. No upload. */
class DiagnosticExports(
    context: Context,
    private val journal: DiagnosticJournal,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val freeBytes: () -> Long = { exportFreeBytes(context) },
) {
    companion object { const val TTL_MS = 24L * 60 * 60 * 1000 }
    private val directory = File(context.cacheDir, "exports/polar")
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "veymur-polar-export") }
    init { cleanup() }
    private fun id(value: String): String {
        if (runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) return value
        throw DiagnosticStorageError("invalid_capture_id")
    }
    private fun <T> submit(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        try { executor.execute {
            try { future.complete(action()) }
            catch (error: Exception) {
                var cause: Throwable = error
                while (cause is java.util.concurrent.ExecutionException && cause.cause != null) cause = cause.cause!!
                future.completeExceptionally(if (cause is DiagnosticStorageError) cause else DiagnosticStorageError("export_operation_failed"))
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { future.completeExceptionally(DiagnosticStorageError("export_unavailable")) }
        return future
    }
    private fun <T> await(future: CompletableFuture<T>): T = future.get()
    fun cleanup(): CompletableFuture<Unit> = submit { prune(); Unit }
    private fun prune() {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { file ->
            if ((file.name.endsWith(".part") || now() - file.lastModified() >= TTL_MS || file.lastModified() > now()) && !file.delete())
                throw DiagnosticStorageError("export_cleanup_failed")
        }
    }
    fun export(captureId: String): CompletableFuture<File> = submit {
        val capture = id(captureId)
        prune()
        val metadata = await(journal.beginExport(capture))
        val temp = File(directory, "$capture-${UUID.randomUUID()}.part")
        try {
            val reserve = journal.policy.minFreeBytes + metadata.status.savedBytes * 2 + metadata.configuration.length * 4L + metadata.environment.length * 4L + 128 * 1024
            if (freeBytes() < reserve) throw DiagnosticStorageError("export_low_storage")
            if (!directory.isDirectory && !directory.mkdirs()) throw DiagnosticStorageError("export_directory_failed")
            val target = File(directory, "polar-diagnostic-$capture.zip")
            FileOutputStream(temp).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    val events = writeRows(zip, capture, false)
                    val processing = writeRows(zip, capture, true)
                    val status = metadata.status
                    if (events.getLong("records") != status.savedEvents || processing.getLong("records") != status.savedProcessing)
                        throw DiagnosticStorageError("export_checkpoint_mismatch")
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    val manifest = manifest(metadata, events, processing).toString()
                    // Firmware observations are streamed in a second bounded pass, not accumulated in RAM.
                    zip.write("{\"firmware\":[".toByteArray(Charsets.UTF_8))
                    var first = true
                    pages(capture, false) { row ->
                        val event = JSONObject(row.json)
                        if (event.optString("event_type") == "device_info") {
                            val payload = event.getJSONObject("payload")
                            val uuid = payload.optString("uuid")
                            val key = payload.optString("key")
                            if (uuid.equals("00002a26-0000-1000-8000-00805f9b34fb", true) || key.equals("firmware", true) || key.equals("FIRMWARE_REVISION", true)) {
                                if (!first) zip.write(','.code)
                                first = false
                                val observation = JSONObject().put("device_id", event.getString("device_id"))
                                    .put("uuid", if (payload.has("uuid")) payload.get("uuid") else JSONObject.NULL)
                                    .put("key", if (payload.has("key")) payload.get("key") else JSONObject.NULL)
                                    .put("value", payload.getString("value")).put("input_sequence", row.sequence)
                                zip.write(observation.toString().toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                    zip.write("],".toByteArray(Charsets.UTF_8))
                    zip.write(manifest.substring(1).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    zip.finish(); zip.flush(); raw.fd.sync()
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally {
            temp.delete()
            await(journal.endExport(capture))
        }
    }
    private fun pages(id: String, processing: Boolean, accept: (DiagnosticRow) -> Unit) {
        var after = 0L
        while (true) {
            val page = await(journal.exportPage(id, processing, after))
            if (page.isEmpty()) return
            page.forEach(accept); after = page.last().sequence
        }
    }
    private fun writeRows(zip: ZipOutputStream, id: String, processing: Boolean): JSONObject {
        zip.putNextEntry(ZipEntry(if (processing) "processing.jsonl" else "events.jsonl"))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L; var records = 0L
        pages(id, processing) { row ->
            val line = (row.json + "\n").toByteArray(Charsets.UTF_8)
            zip.write(line); digest.update(line); bytes += line.size; records++
        }
        zip.closeEntry()
        return JSONObject().put("bytes", bytes).put("records", records)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
    }
    private fun manifest(data: DiagnosticExportMetadata, events: JSONObject, processing: JSONObject): JSONObject {
        val s = data.status; val environment = JSONObject(data.environment)
        val phone = JSONObject().put("manufacturer", environment.optString("manufacturer", "unknown"))
            .put("model", environment.optString("model", "unknown")).put("android_api", environment.optInt("android_api", 0))
            .put("android_release", environment.optString("android_release", "unknown"))
        val checkpoint = JSONObject().put("last_committed_sequence", s.lastCommittedSequence ?: JSONObject.NULL)
            .put("last_committed_processing_sequence", s.lastCommittedProcessingSequence ?: JSONObject.NULL)
            .put("received_events", s.receivedEvents ?: JSONObject.NULL).put("saved_events", s.savedEvents)
            .put("known_lost_events", s.knownLostEvents).put("unknown_tail", s.unknownTail)
            .put("received_processing", s.receivedProcessing ?: JSONObject.NULL).put("saved_processing", s.savedProcessing)
            .put("known_lost_processing", s.knownLostProcessing).put("unprocessed_inputs", s.unprocessedInputs)
            .put("checkpoint_persisted", s.checkpointPersisted).put("last_error_code", s.lastErrorCode ?: JSONObject.NULL)
            .put("last_error_class", s.lastErrorClass ?: JSONObject.NULL)
        return JSONObject().put("schema_version", 1).put("capture_id", s.captureId).put("process_run_id", s.processRunId)
            .put("app_version", environment.optString("app_version", "unknown")).put("core_version", environment.optString("core_version", "unknown"))
            .put("polar_sdk_version", "8.4.0").put("phone", phone)
            .put("enabled_features", org.json.JSONArray(listOf("FEATURE_HR", "FEATURE_BATTERY_INFO", "FEATURE_DEVICE_INFO")))
            .put("started_utc_ms", s.startedUtcMs).put("started_elapsed_ns", s.startedElapsedNs.toString())
            .put("ended_utc_ms", s.endedUtcMs ?: JSONObject.NULL).put("ended_elapsed_ns", s.endedElapsedNs?.toString() ?: JSONObject.NULL)
            .put("state", s.state).put("initial_configuration", JSONObject(data.configuration)).put("checkpoint", checkpoint)
            .put("files", JSONObject().put("events.jsonl", events).put("processing.jsonl", processing))
    }
    fun delete(captureId: String): CompletableFuture<Boolean> = submit {
        val capture = id(captureId)
        val exists = await(journal.listCaptures()).any { it.captureId == capture }
        // Validate terminal state before deleting either the DB or its temporary copies.
        if (exists) await(journal.beginExport(capture))
        try {
            directory.listFiles()?.filter { it.name == "polar-diagnostic-$capture.zip" || it.name.startsWith("$capture-") }?.forEach {
                if (!it.delete()) throw DiagnosticStorageError("export_cleanup_failed")
            }
        } finally { if (exists) await(journal.endExport(capture)) }
        if (exists) await(journal.deleteCapture(capture)) else false
    }
    fun closeAsync(): CompletableFuture<Unit> {
        val done = submit { Unit }; executor.shutdown(); return done
    }
}

@SuppressLint("UsableSpace") // Reserve real free bytes; export cannot rely on eviction of other apps' caches.
private fun exportFreeBytes(context: Context) = context.cacheDir.usableSpace
