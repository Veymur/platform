package com.veymur.recorder.core

import com.veymur.recorder.core.localdata.GpsValue
import com.veymur.recorder.core.localdata.NormalizedGps
import com.veymur.recorder.core.localdata.QualityFlag
import com.veymur.recorder.core.localdata.aggregates.sphericalDistanceM

/**
 * Display-only incremental distance for S04. It applies the same provisional GPS
 * filters as the stored-session aggregator, but it is not the source of truth for
 * the summary: the summary is recalculated from committed records on finish.
 */
class LiveGpsDistance(
    private val maxAccuracyM: Double = 30.0,
    private val maxGapMs: Long = 5_000,
    private val maxSpeedMps: Double = 20.0,
) {
    private var previous: GpsValue? = null
    private var previousFixNs: Long? = null
    private var previousTimebase: String? = null
    private var accumulatedM = 0.0
    private var hasEdge = false

    val distanceM: Double? get() = if (hasEdge) accumulatedM else null

    fun reset() {
        previous = null
        previousFixNs = null
        previousTimebase = null
        accumulatedM = 0.0
        hasEdge = false
    }

    /** Break the route at a pause, GPS loss, provider change or session boundary. */
    fun breakLine() {
        previous = null
        previousFixNs = null
        previousTimebase = null
    }

    fun offer(sample: NormalizedGps, fixNs: Long?) {
        if (fixNs == null || fixNs < 0 || !usable(sample)) {
            breakLine()
            return
        }
        val current = sample.value
        val timebase = sample.time.timebase_id
        val previousValue = previous
        val previousNs = previousFixNs
        if (previousValue == null || previousNs == null || previousTimebase != timebase) {
            setCurrent(current, fixNs, timebase)
            return
        }
        val deltaNs = fixNs - previousNs
        if (deltaNs <= 0) {
            // The aggregator rejects a non-increasing fix time and starts a new line
            // only at the next usable point.
            breakLine()
            return
        }
        if (deltaNs > maxGapMs * 1_000_000L) {
            // A gap breaks the line; the current point starts the next line.
            setCurrent(current, fixNs, timebase)
            return
        }
        val metres = sphericalDistanceM(previousValue, current)
        if (metres / (deltaNs / 1_000_000_000.0) > maxSpeedMps) {
            // The current point is implausible; do not use it as the next anchor.
            breakLine()
            return
        }
        accumulatedM += metres
        hasEdge = true
        setCurrent(current, fixNs, timebase)
    }

    private fun setCurrent(value: GpsValue, fixNs: Long, timebase: String) {
        previous = value
        previousFixNs = fixNs
        previousTimebase = timebase
    }

    private fun usable(sample: NormalizedGps): Boolean {
        val flags = sample.quality.quality_flags
        val value = sample.value
        return QualityFlag.valid_format in flags &&
            QualityFlag.suspect_signal !in flags &&
            QualityFlag.missing !in flags &&
            QualityFlag.stale !in flags &&
            QualityFlag.estimated !in flags &&
            value.latitude.isFinite() && value.latitude in -90.0..90.0 &&
            value.longitude.isFinite() && value.longitude in -180.0..180.0 &&
            value.horizontal_accuracy_m?.let {
                it.isFinite() && it >= 0.0 && it <= maxAccuracyM
            } == true
    }
}
