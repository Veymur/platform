package com.veymur.recorder.core

/** Provisional policy, not a phone-validated latency or loss-window promise. */
data class DiagnosticPolicy(
    val queueRecords: Int = 512,
    val queueBytes: Long = 2L * 1024 * 1024,
    val maxRecordChars: Int = 128 * 1024,
    val batchRecords: Int = 64,
    val commitIntervalMs: Long = 250,
    val drainTimeoutMs: Long = 5000,
    val maxCaptureBytes: Long = 16L * 1024 * 1024,
    val maxDurationMs: Long = 60 * 60 * 1000,
    val maxDatabaseBytes: Long = 64L * 1024 * 1024,
    val maxCaptures: Int = 20,
    val minFreeBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(queueRecords in 1..4096 && queueBytes in 1024..(16L * 1024 * 1024))
        require(maxRecordChars in 1..(1024 * 1024) && batchRecords in 1..queueRecords)
        require(commitIntervalMs in 0..60000 && drainTimeoutMs in 1..60000)
        require(maxCaptureBytes > 0 && maxDurationMs in 1..(24L * 60 * 60 * 1000))
        require(maxDatabaseBytes >= 1024 * 1024 && maxCaptures in 1..100 && minFreeBytes >= 0)
    }
}

data class DiagnosticCaptureStatus(
    val captureId: String,
    val processRunId: String,
    val state: String,
    val startedUtcMs: Long,
    val startedElapsedNs: Long,
    val endedUtcMs: Long? = null,
    val endedElapsedNs: Long? = null,
    val receivedEvents: Long? = 0,
    val acceptedEvents: Long? = 0,
    val savedEvents: Long = 0,
    val knownLostEvents: Long = 0,
    val receivedProcessing: Long? = 0,
    val acceptedProcessing: Long? = 0,
    val savedProcessing: Long = 0,
    val knownLostProcessing: Long = 0,
    val unprocessedInputs: Long = 0,
    val savedBytes: Long = 0,
    val lastCommittedSequence: Long? = null,
    val lastCommittedProcessingSequence: Long? = null,
    val unknownTail: Boolean = false,
    val lastErrorCode: String? = null,
    val lastErrorClass: String? = null,
    val checkpointPersisted: Boolean = true,
)

class DiagnosticStorageError(val code: String) : Exception(code)

/** Native-only handle. Status and queue are owned by DiagnosticJournal; there is no disk I/O here. */
class DiagnosticCapture internal constructor(
    private val owner: DiagnosticJournal,
    override val captureId: String,
) : PolarDiagnosticSink {
    val status: DiagnosticCaptureStatus get() = owner.liveStatus(this)
    override fun offerInput(event: PolarInputEvent) = owner.offerInput(this, event)
    override fun offerProcessing(processing: PolarInputProcessing) = owner.offerProcessing(this, processing)
    fun stop() = owner.stop(this)
}
