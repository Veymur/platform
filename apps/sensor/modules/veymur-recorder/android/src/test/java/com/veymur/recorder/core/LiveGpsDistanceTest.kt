package com.veymur.recorder.core

import com.veymur.recorder.core.localdata.GpsValue
import com.veymur.recorder.core.localdata.NormalizedGps
import com.veymur.recorder.core.localdata.ObservationTime
import com.veymur.recorder.core.localdata.QualityFlag
import com.veymur.recorder.core.localdata.SourceQuality
import com.veymur.recorder.core.localdata.TimeOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGpsDistanceTest {
    private fun sample(
        latitude: Double,
        longitude: Double = 21.01,
        accuracy: Double? = 5.0,
        flags: List<QualityFlag> = listOf(QualityFlag.valid_format),
        timebase: String = "process-1",
    ) = NormalizedGps(
        GpsValue(latitude, longitude, accuracy, null, null),
        ObservationTime(
            observed_at_utc = null,
            recorded_at_utc = "2026-10-26T10:00:00Z",
            timezone_id = "UTC",
            time_origin = TimeOrigin.receipt,
            monotonic_offset_ms = 0,
            timebase_id = timebase,
            source_timestamp = null,
            receipt_elapsed_realtime_ns = "0",
            source_elapsed_realtime_ns = null,
            observed_monotonic_offset_ms = null,
        ),
        SourceQuality(flags, emptyList()),
    )

    @Test
    fun stationaryPointsProduceZeroDistanceOnlyAfterAnEdge() {
        val live = LiveGpsDistance()
        assertNull(live.distanceM)
        live.offer(sample(52.23), 0L)
        assertNull(live.distanceM)
        live.offer(sample(52.23), 1_000_000_000L)
        assertEquals(0.0, live.distanceM!!, 0.001)
    }

    @Test
    fun plausibleEdgeAddsHaversineDistance() {
        val live = LiveGpsDistance()
        live.offer(sample(52.23), 0L)
        live.offer(sample(52.2305), 4_000_000_000L)
        assertEquals(55.6, live.distanceM!!, 2.0)
    }

    @Test
    fun accuracyGapAndSpeedFiltersBreakTheLineWithoutInventingDistance() {
        val accuracy = LiveGpsDistance()
        accuracy.offer(sample(52.23, accuracy = 31.0), 0L)
        accuracy.offer(sample(52.231), 1_000_000_000L)
        assertNull(accuracy.distanceM)

        val gap = LiveGpsDistance()
        gap.offer(sample(52.23), 0L)
        gap.offer(sample(52.231), 6_000_000_000L)
        assertNull(gap.distanceM)

        val speed = LiveGpsDistance()
        speed.offer(sample(52.23), 0L)
        speed.offer(sample(52.231), 1_000_000_000L)
        assertNull(speed.distanceM)
    }

    @Test
    fun pauseAndMockBreakTheLineButKeepAcceptedDistance() {
        val live = LiveGpsDistance()
        live.offer(sample(52.23), 0L)
        live.offer(sample(52.2305), 4_000_000_000L)
        val accepted = live.distanceM!!
        live.breakLine()
        live.offer(sample(52.231, flags = listOf(QualityFlag.suspect_signal)), 8_000_000_000L)
        assertEquals(accepted, live.distanceM!!, 0.001)
        live.offer(sample(52.2315), 12_000_000_000L)
        assertEquals(accepted, live.distanceM!!, 0.001)
        live.offer(sample(52.232), 16_000_000_000L)
        assertEquals(accepted + 55.6, live.distanceM!!, 2.0)
    }

    @Test
    fun resetClearsAccumulationForTheNextSession() {
        val live = LiveGpsDistance()
        live.offer(sample(52.23), 0L)
        live.offer(sample(52.231), 10_000_000_000L)
        live.reset()
        assertNull(live.distanceM)
    }
}
