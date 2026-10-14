package com.veymur.recorder.core

/** Monotonic time, never wall-clock subtraction. Paused intervals are excluded. */
class RecordingClock(private val now: () -> Long) {
    private var since: Long? = null
    private var accumulated = 0L
    fun start() { accumulated = 0; since = now() }
    fun pause() { since?.let { accumulated += now() - it }; since = null }
    fun resume() { if (since == null) since = now() }
    fun activeMs(): Long = accumulated + (since?.let { now() - it } ?: 0)
}
