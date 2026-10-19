package com.veymur.recorder.core

import org.json.JSONObject

class RecorderError(val code: String, message: String) : Exception(message)

/** Invoked exclusively by the service writer, with no React/Expo dependency. */
class RecorderSession(private val journal: RecorderSessionStore, val clock: RecordingClock) {
    @Volatile var id: String? = null; private set
    @Volatile var state = "preparation"; private set
    private var completedId: String? = null
    fun start(device: String, route: Boolean, configuration: JSONObject): String {
        if (id != null) throw RecorderError("INVALID_STATE", "A session is already active")
        // A failed new start must not acknowledge a previously saved session.
        completedId = null; state = "preparation"
        val created = journal.begin(device, route, configuration)
        clock.start(); id = created; state = "recording"
        return created
    }
    fun pause() {
        if (state == "paused") return
        if (state != "recording") throw RecorderError("INVALID_STATE", "No recording to pause")
        clock.pause()
        commit { journal.transition(id!!, "pause", "paused", clock.activeMs()) }
        state = "paused"
    }
    fun resume() {
        if (state == "recording") return
        if (state != "paused") throw RecorderError("INVALID_STATE", "No paused recording to resume")
        commit { journal.transition(id!!, "resume", "recording", clock.activeMs()) }
        clock.resume(); state = "recording"
    }
    fun finish(battery: Int): String {
        if (id == null) return completedId ?: throw RecorderError("INVALID_STATE", "No session to finish")
        clock.pause()
        val current = id!!
        // After a failed write, the uncommitted tail is unknown. A later successful
        // retry must not stretch the last segment to the time storage becomes available.
        val interrupted = state == "interrupted"
        val terminal = if (interrupted) "interrupted" else "completed"
        commit {
            journal.transition(current, if (interrupted) "interrupted" else "finish", terminal, clock.activeMs(),
                JSONObject().put("active_ms", clock.activeMs()).put("phone_battery_percent", battery)
                    .apply { if (interrupted) put("reason", "storage_error") })
        }
        id = null; completedId = current; state = terminal
        return current
    }
    private fun commit(action: () -> Unit) {
        try { action() } catch (error: Exception) { failed(); throw error }
    }
    fun failed() { clock.pause(); state = "interrupted" }
}
