package com.veymur.recorder.core

import org.json.JSONObject

/** Worker-side session commands. Android permissions/resources remain in the service adapter. */
class RecorderControl(
    private val session: () -> RecorderSession,
    private val read: () -> RecorderSnapshot,
    private val write: (RecorderSnapshot) -> Unit,
    private val utcNow: () -> Long,
) {
    fun start(routeRequested: Boolean, routeEnabled: Boolean, configuration: JSONObject): String {
        val before = read()
        val device = before.connected ?: throw RecorderError("NOT_CONNECTED", "Connect a sensor first")
        if (before.recording || session().id != null) throw RecorderError("INVALID_STATE", "A session is already active")
        val id = session().start(device, routeEnabled, configuration)
        write(before.copy(recording = true, paused = false, errorCode = null, hrCount = 0, gpsCount = 0, activeMs = 0,
            lastCommit = utcNow(), status = "Recording on this phone",
            gpsStatus = if (routeEnabled) "Waiting for coordinates" else if (routeRequested) "GPS unavailable; recording heart rate only" else "Route disabled"))
        return id
    }
    fun pauseOrResume(name: String) {
        require(name == "pause" || name == "resume")
        val engine = session()
        val previous = engine.state
        if (name == "pause") engine.pause() else engine.resume()
        val before = read()
        write(before.copy(paused = engine.state == "paused", activeMs = engine.clock.activeMs(),
            lastCommit = if (previous != engine.state) utcNow() else before.lastCommit, errorCode = null,
            status = if (engine.state == "paused") "Paused" else "Recording on this phone"))
    }
    fun finish(battery: Int, message: String): String {
        val engine = session()
        val hadSession = engine.id != null
        val id = engine.finish(battery)
        val before = read()
        write(before.copy(recording = false, paused = false, errorCode = null, activeMs = engine.clock.activeMs(),
            lastCommit = if (hadSession) utcNow() else before.lastCommit, status = message))
        return id
    }
}
