package com.veymur.recorder.core

/** User-authored intervals, lower inclusive and upper exclusive; no prescribed zones. */
data class AlertRange(val id: String, val label: String, val lower: Int?, val upper: Int?) {
    fun contains(bpm: Int) = bpm >= (lower ?: 0) && (upper == null || bpm < upper)
}
data class RangeAlertConfiguration(val ranges: List<AlertRange>, val debounceMs: Long, val hysteresisBpm: Int, val repeatMs: Long?) {
    init {
        require(debounceMs >= 0 && hysteresisBpm >= 0 && (repeatMs == null || repeatMs > 0))
        require(ranges.map { it.id }.distinct().size == ranges.size)
        ranges.forEach { require(it.id.isNotBlank() && it.label.isNotBlank() && (it.lower ?: 0) >= 0 && (it.upper == null || it.upper > (it.lower ?: 0))) }
        require(ranges.sortedBy { it.lower ?: 0 }.zipWithNext().none { (a,b) -> a.upper == null || a.upper > (b.lower ?: 0) })
    }
}

/** Pure monotonic reducer. Caller supplies only fresh HR from the active native segment. */
class RangeAlerts(private val config: RangeAlertConfiguration) {
    private var current: AlertRange? = null
    private var pending: AlertRange? = null
    private var announced: AlertRange? = null
    private var pendingSince = 0L
    private var lastEmission: Long? = null
    private var lastTime: Long? = null
    // Loss resets debounce but preserves this cooldown so flapping cannot flood alerts.
    private val minimumIntervalMs = 5000L
    fun reset() { current = null; pending = null; announced = null }
    fun sample(bpm: Int?, nowMs: Long): AlertRange? {
        require(nowMs >= 0 && (lastTime == null || nowMs >= lastTime!!))
        lastTime = nowMs
        if (bpm == null || bpm <= 0) { reset(); return null }
        val target = config.ranges.singleOrNull { it.contains(bpm) }
        val previous = current
        if (target != previous && previous != null && target != null) {
            // A transition must cross the old boundary by the user's hysteresis amount.
            val exitHigh = previous.upper?.let { bpm.toLong() >= it.toLong() + config.hysteresisBpm } ?: false
            val exitLow = previous.lower?.let { bpm.toLong() < it.toLong() - config.hysteresisBpm } ?: false
            if (!exitHigh && !exitLow) { pending = null; return null }
        }
        if (target == null) { reset(); return null }
        if (target != current) {
            if (pending != target) { pending = target; pendingSince = nowMs }
            if (nowMs - pendingSince < config.debounceMs) return null
            current = target; pending = null
            return emit(target, nowMs)
        }
        pending = null
        if (announced != target) return emit(target, nowMs)
        val last = lastEmission ?: return emit(target, nowMs)
        return if (config.repeatMs != null && nowMs - last >= config.repeatMs) emit(target, nowMs) else null
    }
    private fun emit(range: AlertRange, now: Long): AlertRange? {
        if (lastEmission?.let { now - it < minimumIntervalMs } == true) return null
        lastEmission = now; announced = range
        return range
    }
}
