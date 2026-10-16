package com.veymur.recorder.core.localdata

import java.time.Instant
import kotlin.math.abs

const val NORMALIZATION_VERSION = "native-observation-v1"
const val HR_USABILITY_VERSION = "hr-usability-v1"
// Provisional diagnostic tolerance, not a tested sensor accuracy guarantee.
const val CLOCK_DISCONTINUITY_TOLERANCE_MS = 1000L

/** Captured before the writer queue. elapsed_ns is Android boot time including deep sleep. */
data class Receipt(val utc_ms: Long, val elapsed_ns: Long, val timebase_id: String, val timezone_id: String?)
data class ObservationTime(
    val observed_at_utc: String?, val recorded_at_utc: String, val timezone_id: String?,
    val time_origin: TimeOrigin, val monotonic_offset_ms: Long, val timebase_id: String,
    val source_timestamp: SourceTimestamp?, val receipt_elapsed_realtime_ns: String,
    val source_elapsed_realtime_ns: String?, val observed_monotonic_offset_ms: Long?
)
data class SourceQuality(val quality_flags: List<QualityFlag>, val quality_reasons: List<String>)
data class NormalizedHr(val value: Int, val contact_detected: Boolean?, val time: ObservationTime, val quality: SourceQuality)
data class GpsInput(
    val latitude: Double, val longitude: Double, val accuracy_m: Double?, val altitude_m: Double?,
    val speed_m_s: Double?, val utc_ms: Long?, val elapsed_ns: Long?, val mock: Boolean
)
data class NormalizedGps(val value: GpsValue, val time: ObservationTime, val quality: SourceQuality)
class InvalidObservation(val reason: String) : IllegalArgumentException(reason)

/** Writer-owned; deterministic given receipt stamps. Does not repair raw values or estimate UTC. */
class ObservationNormalizer {
    private var anchor: Receipt? = null
    private fun time(receipt: Receipt, observedUtc: Long? = null, observedElapsed: Long? = null): Pair<ObservationTime, List<String>> {
        if (receipt.elapsed_ns < 0 || receipt.timebase_id.isBlank()) throw InvalidObservation("invalid_receipt_timebase")
        val reference = anchor?.takeIf { it.timebase_id == receipt.timebase_id } ?: receipt.also { anchor = it }
        val reasons = mutableListOf<String>()
        // Double subtraction avoids Long overflow for hostile timestamps; no duration uses wall time.
        val wallDelta = receipt.utc_ms.toDouble() - reference.utc_ms.toDouble()
        val elapsedDelta = (receipt.elapsed_ns - reference.elapsed_ns) / 1_000_000.0
        if (abs(wallDelta - elapsedDelta) > CLOCK_DISCONTINUITY_TOLERANCE_MS) reasons.add("wall_clock_discontinuity")
        if (receipt.timezone_id == null) reasons.add("timezone_unknown")
        if (observedElapsed != null && (observedElapsed < 0 || observedElapsed > receipt.elapsed_ns)) {
            reasons.add("source_monotonic_outside_receipt")
        }
        val utc = observedUtc?.takeIf { it > 0 }
        if (observedUtc != null && utc == null) reasons.add("source_utc_unavailable")
        val monotonic = observedElapsed?.takeIf { it >= 0 && it <= receipt.elapsed_ns }
        if (utc != null && monotonic != null && abs((receipt.utc_ms.toDouble() - utc.toDouble()) -
                (receipt.elapsed_ns - monotonic) / 1_000_000.0) > CLOCK_DISCONTINUITY_TOLERANCE_MS) {
            reasons.add("source_clock_disagreement")
        }
        return ObservationTime(
            utc?.let { Instant.ofEpochMilli(it).toString() }, Instant.ofEpochMilli(receipt.utc_ms).toString(),
            receipt.timezone_id, if (utc == null) TimeOrigin.receipt else TimeOrigin.measurement,
            receipt.elapsed_ns / 1_000_000, receipt.timebase_id,
            observedUtc?.let { SourceTimestamp(it.toString(), "unix_ms", if (utc == null) null else "unix-ms-to-utc-v1") },
            receipt.elapsed_ns.toString(), observedElapsed?.toString(), monotonic?.div(1_000_000)
        ) to reasons
    }
    fun hr(bpm: Int, contact: Boolean, contactSupported: Boolean, receipt: Receipt): NormalizedHr {
        if (bpm <= 0) throw InvalidObservation("invalid_hr_format")
        val (time, timeReasons) = time(receipt)
        val contactDetected = contact.takeIf { contactSupported }
        val reasons = timeReasons + if (contactDetected == false) listOf("sensor_contact_lost") else emptyList()
        val flags = listOf(QualityFlag.valid_format) +
            (if (timeReasons.isNotEmpty()) listOf(QualityFlag.clock_uncertain) else emptyList()) +
            (if (contactDetected == false) listOf(QualityFlag.suspect_signal) else emptyList())
        return NormalizedHr(bpm, contactDetected, time, SourceQuality(flags, reasons))
    }
    fun gps(input: GpsInput, receipt: Receipt): NormalizedGps {
        if (!input.latitude.isFinite() || input.latitude !in -90.0..90.0 ||
            !input.longitude.isFinite() || input.longitude !in -180.0..180.0 ||
            input.accuracy_m?.let { !it.isFinite() || it < 0 } == true ||
            input.altitude_m?.let { !it.isFinite() } == true ||
            input.speed_m_s?.let { !it.isFinite() || it < 0 } == true) throw InvalidObservation("invalid_gps_format")
        val (time, timeReasons) = time(receipt, input.utc_ms, input.elapsed_ns)
        val reasons = timeReasons + (if (input.mock) listOf("mock_location") else emptyList())
        val flags = listOf(QualityFlag.valid_format) +
            (if (timeReasons.isNotEmpty()) listOf(QualityFlag.clock_uncertain) else emptyList()) +
            (if (input.mock) listOf(QualityFlag.suspect_signal) else emptyList())
        return NormalizedGps(GpsValue(input.latitude, input.longitude, input.accuracy_m, input.altitude_m, input.speed_m_s),
            time, SourceQuality(flags, reasons))
    }
}

/** Derived assessment, separate from source quality. Caller supplies a provisional stale threshold. */
data class HrUsability(val method_version: String, val stale_after_ms: Long, val usable: Boolean, val reasons: List<String>)
fun assessHr(sample: NormalizedHr, now: Receipt, stale_after_ms: Long): HrUsability {
    require(stale_after_ms >= 0)
    val reasons = mutableListOf<String>()
    if (QualityFlag.suspect_signal in sample.quality.quality_flags) reasons.addAll(sample.quality.quality_reasons.filter { it == "sensor_contact_lost" })
    if (now.timebase_id != sample.time.timebase_id) reasons.add("different_timebase")
    else {
        val receivedNs = sample.time.receipt_elapsed_realtime_ns.toLong()
        if (now.elapsed_ns < receivedNs) reasons.add("before_receipt")
        else if ((now.elapsed_ns - receivedNs) / 1_000_000.0 > stale_after_ms) reasons.add("stale")
    }
    return HrUsability(HR_USABILITY_VERSION, stale_after_ms, reasons.isEmpty(), reasons)
}
