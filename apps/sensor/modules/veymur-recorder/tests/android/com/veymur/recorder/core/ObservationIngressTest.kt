package com.veymur.recorder.core

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veymur.recorder.core.localdata.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservationIngressTest {
    @Test fun hrWriterUsesReceiptBeforeQueueAndPersistsSuspectAndRejectedValuesSeparately() {
        val r = PolarReplay(ReplayTime(1700000000000, 100_000_000_000))
        fun input(type: String, payload: JSONObject) = PolarInputEvent(null, "test-process", 1,
            1700000000100, 1_000_000_001, "polar_sdk", type, "test", "SYNTHETIC", payload.toString(), "Europe/Warsaw")
        r.process(input("connected", JSONObject()))
        val command = JSONObject().put("name", "start").put("command_id", "start-1").put("route", false)
        r.process(input("command_requested", command).copy(source = "app_command"))
        fun payload(bpm: Any) = JSONObject().put("hr", bpm).put("contactStatus", false)
            .put("contactStatusSupported", true).put("ppgQuality", 101)
        r.process(input("hr", payload(400)))
        val saved = r.records.map(::JSONObject).last().getJSONObject("payload")
        val metadata = saved.getJSONObject("observation_metadata")
        assertEquals(400, saved.getInt("bpm")); assertNull(r.snapshot.hr)
        assertTrue(metadata.has("observed_at_utc")); assertTrue(metadata.isNull("observed_at_utc"))
        assertEquals("2023-11-14T22:13:20.100Z", metadata.getString("recorded_at_utc"))
        assertEquals("1000000001", metadata.getString("receipt_elapsed_realtime_ns"))
        assertEquals("Europe/Warsaw", metadata.getString("timezone_id"))
        assertTrue(metadata.getJSONArray("quality_flags").toString().contains("suspect_signal"))
        assertEquals("sensor_contact_lost", metadata.getJSONArray("quality_reasons").getString(0))
        assertEquals(1, r.snapshot.hrCount)
        for (bad in listOf<Any>(0, -2, "90", 90.5, JSONObject.NULL)) r.process(input("hr", payload(bad)))
        val rejected = r.records.map(::JSONObject).filter { it.getString("kind") == "hr_rejected" }
        assertEquals(5, rejected.size); assertEquals(1, r.snapshot.hrCount)
        assertTrue(rejected.all { it.getJSONObject("payload").has("raw_payload") })
        assertEquals("recording", r.session.state)
    }
    @Test fun androidGpsOptionalFieldsStayNullAndCallbackCopyIsOwned() {
        val location = Location("gps").apply {
            latitude = 52.23; longitude = 21.01; time = 1700000000000; elapsedRealtimeNanos = 900000001
        }
        val owned = locationObservationInput(location)
        location.latitude = -10.0; location.accuracy = 0f; location.altitude = 0.0; location.speed = 0f
        val n = ObservationNormalizer()
        val receipt = Receipt(1700000000100, 1000000001, "process-1", "Europe/Warsaw")
        val sample = n.gps(owned, receipt)
        assertEquals(52.23, sample.value.latitude, 0.0)
        assertNull(sample.value.horizontal_accuracy_m); assertNull(sample.value.altitude_m); assertNull(sample.value.speed_m_s)
        val metadata = observationMetadata(sample.time, sample.quality)
        assertEquals("900000001", metadata.getString("source_elapsed_realtime_ns"))
        assertEquals("unix-ms-to-utc-v1", metadata.getJSONObject("source_timestamp").getString("utc_conversion"))
        val zero = n.gps(locationObservationInput(location), receipt)
        assertEquals(0.0, zero.value.horizontal_accuracy_m!!, 0.0)
        assertEquals(0.0, zero.value.altitude_m!!, 0.0)
        assertEquals(0.0, zero.value.speed_m_s!!, 0.0)
    }
}
