package com.veymur.recorder.core

data class HeartRateView(val value: Int?, val ageMs: Long?, val status: String)

/** Same projection for live DTO and replay observations, including time without callbacks. */
fun RecorderSnapshot.heartRateAt(elapsedNs: Long): HeartRateView {
    val age = if ((lastHrElapsedNs > 0 || hr != null) && elapsedNs >= lastHrElapsedNs)
        (elapsedNs - lastHrElapsedNs) / 1_000_000 else null
    val fresh = connected != null && hr != null && age != null && age <= 5000
    return HeartRateView(hr.takeIf { fresh }, age,
        if (fresh) "fresh" else if (hr != null && connected != null) "stale" else "missing")
}
