package com.veymur.recorder.core

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object DiagnosticTestData {
    const val PROCESS = "D3-SYNTHETIC-PROCESS"
    const val SENTINEL = "D3_RAW_SENTINEL_793c46e5"
    fun context(prefix: String = "d3-${UUID.randomUUID()}-"): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
        override fun getFilesDir() = File(baseContext.filesDir, prefix).also { it.mkdirs() }
    }
    fun <T> await(future: CompletableFuture<T>): T = future.get(15, TimeUnit.SECONDS)
    fun config() = JSONObject().put("session_state", "preparation").put("session_id", JSONObject.NULL)
        .put("connected_device_id", JSONObject.NULL).put("route_enabled", false).put("automatic_reconnection", true)
        .put("hr_stale_after_ms", 5000).put("active_ms", 0).put("last_hr", JSONObject.NULL)
        .put("last_hr_received_elapsed_ns", JSONObject.NULL).toString()
    fun policy() = DiagnosticPolicy(queueRecords = 128, batchRecords = 64, commitIntervalMs = 60000, drainTimeoutMs = 100, minFreeBytes = 0)
    fun start(journal: DiagnosticJournal, process: String = PROCESS) = await(journal.startCapture(process, config(), "{}"))
    fun input(capture: DiagnosticCapture, sequence: Long, json: String = "{\"powered\":false}") = PolarInputEvent(
        capture.captureId, PROCESS, sequence, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
        "polar_sdk", "bluetooth", "blePowerStateChanged", null, json)
    fun processing(input: PolarInputEvent) = PolarInputProcessing(input, input.sequence, System.currentTimeMillis(),
        SystemClock.elapsedRealtimeNanos(), null, "applied", null)
    fun pair(capture: DiagnosticCapture, sequence: Long, json: String = "{\"powered\":false}"): PolarInputEvent = input(capture, sequence, json).also {
        capture.offerInput(it); capture.offerProcessing(processing(it))
    }
    fun waitUntil(test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (!test()) { check(SystemClock.elapsedRealtime() < until) { "Diagnostic test timeout" }; SystemClock.sleep(10) }
    }
}
