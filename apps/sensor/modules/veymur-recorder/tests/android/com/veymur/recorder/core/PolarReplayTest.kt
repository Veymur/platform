package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolarReplayTest {
    private fun asset(name: String) = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("polar/$name.fixture.json").bufferedReader().use { it.readText() })
    private fun expected(name: String, r: PolarReplay) {
        val e = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("polar/$name.expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("native_replay_assertions")
        assertEquals(e.getInt("processed_inputs"), r.outcomes.size)
        assertEquals(e.getString("session_state"), r.session.state)
        assertEquals(e.getInt("hr_count"), r.snapshot.hrCount)
        assertEquals(e.getInt("session_records"), r.records.size)
        assertTrue(e.isNull("current_hr")); assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        assertTrue(e.isNull("connected_device_id")); assertNull(r.snapshot.connected)
        assertEquals(e.getInt("visible_devices"), r.snapshot.devices.size)
        val warnings = e.getJSONArray("limitations_include")
        repeat(warnings.length()) { assertTrue(warnings.getString(it), warnings.getString(it) in r.limitations) }
    }
    private fun input(sequence: Long, atMs: Long, type: String, payload: JSONObject = JSONObject(), utc: Long = 1700000000000 + atMs) =
        PolarInputEvent("synthetic", "synthetic", sequence, utc, atMs * 1_000_000, "polar_sdk", type, "test", "SYNTHETIC", payload.toString())
    private fun hr(sequence: Long, at: Long, bpm: Int) = input(sequence, at, "hr", JSONObject().put("hr", bpm)
        .put("contactStatus", true).put("contactStatusSupported", true).put("ppgQuality", 0))
    private fun command(sequence: Long, at: Long, name: String) = input(sequence, at, "command_requested", JSONObject().put("command_id", "command-$sequence").put("argument", JSONObject.NULL).put("name", name).put("route", false)).copy(source = "app_command", callbackVariant = "execute")
    private fun at(replay: PolarReplay, ms: Long, input: PolarInputEvent): String {
        replay.time.advanceTo(ms * 1_000_000)
        replay.time.utcMs = input.receivedUtcMs
        return replay.process(input)
    }
    private fun runSession(): PolarReplay {
        val r = PolarReplay(ReplayTime(1700000000000, 0))
        assertEquals("rejected:NOT_CONNECTED", at(r, 0, command(1, 0, "start")))
        at(r, 100, input(2, 100, "connected"))
        at(r, 200, command(3, 200, "start"))
        at(r, 300, hr(4, 300, 80))
        at(r, 400, hr(5, 400, 80)) // Repeated samples are preserved.
        at(r, 1200, command(6, 1200, "pause"))
        at(r, 2200, hr(7, 2200, 90)) // UI input, not a recorded HR point.
        at(r, 3200, command(8, 3200, "pause")) // Idempotent.
        at(r, 9200, command(9, 9200, "resume"))
        at(r, 9300, hr(10, 9300, 0))
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        at(r, 9400, hr(11, 9400, -2))
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        at(r, 9500, hr(12, 9500, 400)) // Diagnostic recorder imposes no medical range.
        at(r, 9600, input(13, 9600, "disconnected"))
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        at(r, 9700, input(14, 9700, "connected"))
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value) // No resurrected HR.
        at(r, 9800, hr(15, 9800, 82))
        r.time.schedule(14800_000_000) { r.observe() }
        r.time.schedule(14801_000_000) { r.observe() }
        r.scheduleTicks(16000_000_000)
        r.time.advanceTo(16000_000_000)
        assertEquals("stale", r.snapshot.heartRateAt(r.time.elapsedNs).status)
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        // A backward wall-clock jump cannot alter monotonic duration.
        r.time.utcMs = 1600000000000
        r.process(command(16, 16000, "finish"))
        return r
    }
    @Test fun deterministicCommandsGapsAndIndependentExpectations() {
        val first = runSession()
        val second = runSession()
        assertEquals(first.records, second.records)
        assertEquals(first.outcomes, second.outcomes)
        assertEquals(first.observations, second.observations)
        assertEquals("completed", first.session.state)
        assertEquals(7800L, first.clock.activeMs()) // (1200-200)+(16000-9200)
        val rows = first.records.map(::JSONObject)
        val hr = rows.filter { it.getString("kind") == "hr" }.map { it.getJSONObject("payload").getInt("bpm") }
        assertEquals(listOf(80, 80, 0, -2, 400, 82), hr)
        assertEquals(1, rows.count { it.getString("kind") == "pause" })
        assertEquals(1, rows.count { it.getString("kind") == "resume" })
        val boundary = first.observations.map(::JSONObject).filter { it.getString("elapsed_ns") in setOf("14800000000", "14801000000") }
        assertEquals(listOf("fresh", "stale"), boundary.distinctBy { it.getString("elapsed_ns") }.map { it.getString("hr_status") })
        assertEquals(1600000000000L, rows.last().getLong("utc_ms"))
    }
    @Test fun realNativeCallbackFixtureUsesLiveRulesNotCapturedMockOutcomes() {
        val json = asset("native-callbacks")
        val first = PolarReplay.fixture(json)
        val second = PolarReplay.fixture(json)
        expected("native-callbacks", first)
        assertEquals(first.outcomes, second.outcomes)
        assertEquals(first.observations, second.observations)
        assertEquals(21, first.outcomes.size)
        assertEquals(10, first.outcomes.count { it.endsWith(":applied") })
        assertEquals(11, first.outcomes.count { it.endsWith(":ignored") })
        assertTrue(first.records.isEmpty()) // No start command in this fixture.
        assertEquals(0, first.snapshot.hrCount)
        assertNull(first.snapshot.hr)
        assertNull(first.snapshot.connected)
        assertEquals(0, first.snapshot.devices.size)
        assertEquals("DISCOVERY_ERROR", first.snapshot.errorCode)
        assertTrue(first.limitations.contains("source_not_replay_ready"))
        assertEquals("11:applied", first.outcomes[10])
        // Deliberately corrupt an observed processing outcome. Replay output must be unchanged.
        json.getJSONArray("processing").getJSONObject(0).put("outcome", "ignored")
        assertEquals(first.outcomes, PolarReplay.fixture(json).outcomes)
    }
    @Test fun interruptedInputIsNotInventedOrApplied() {
        val r = PolarReplay.fixture(asset("native-interrupted"))
        expected("native-interrupted", r)
        assertTrue(r.outcomes.isEmpty())
        assertTrue(r.records.isEmpty())
        assertTrue(r.limitations.contains("unprocessed_inputs_not_applied"))
        assertTrue(r.limitations.contains("source_capture_incomplete"))
        assertEquals("preparation", r.session.state)
        assertEquals("Preparing storage…", r.snapshot.status)
    }
    @Test fun truncatedAndFailedCheckpointFixturesReportTheirLimits() {
        val focused = PolarReplay.fixture(asset("native-hr-focus"))
        expected("native-hr-focus", focused)
        assertEquals(6, focused.outcomes.size)
        assertTrue(focused.limitations.contains("source_not_replay_ready"))
        val failed = PolarReplay.fixture(asset("native-checkpoint-failure"))
        expected("native-checkpoint-failure", failed)
        assertEquals(1, failed.outcomes.size)
        assertTrue(failed.limitations.contains("source_capture_incomplete"))
    }
    @Test fun invalidSchemaOrderParentsAndInitialSessionAreRejected() {
        val cases: List<(JSONObject) -> Unit> = listOf(
            { it.put("fixture_version", 2) },
            { it.getJSONArray("events").getJSONObject(0).put("schema_version", 2) },
            { it.getJSONArray("processing").getJSONObject(0).put("schema_version", 2) },
            { it.getJSONArray("processing").getJSONObject(0).put("input_sequence", 999) },
            { it.getJSONArray("processing").getJSONObject(1).put("processing_sequence", 0) },
            { it.getJSONArray("processing").getJSONObject(0).put("processed_elapsed_ns", "0") },
            { it.getJSONObject("initial_state").getJSONObject("configuration").put("session_state", "recording") },
        )
        cases.forEach { change ->
            val json = asset("native-callbacks"); change(json)
            assertThrows(IllegalArgumentException::class.java) { PolarReplay.fixture(json) }
        }
    }
    @Test fun commandJournalDrivesExecutionAndProcessingOrder() {
        val root = asset("native-callbacks")
        val events = listOf(input(1, 1100, "connected"), command(2, 1200, "start"), hr(3, 1300, 80),
            command(4, 2200, "pause"), hr(5, 2300, 90), command(6, 9200, "resume"),
            hr(7, 9300, 82), command(8, 10200, "finish"))
        root.put("events", org.json.JSONArray(events.map { it.toJson() }))
        val rows = org.json.JSONArray()
        var sequence = 0L
        events.forEach { input ->
            if (input.source == "app_command") rows.put(PolarInputProcessing(input, ++sequence, input.receivedUtcMs,
                input.receivedElapsedNs, null, "accepted", null).toJson())
            rows.put(PolarInputProcessing(input, ++sequence, input.receivedUtcMs, input.receivedElapsedNs, null,
                if (input.source == "app_command") "committed" else "applied", null).toJson())
        }
        root.put("processing", rows)
        root.getJSONObject("capture_bounds").put("ended_elapsed_ns", "11000000000")
        val r = PolarReplay.fixture(root)
        assertEquals("completed", r.session.state)
        assertEquals(2000L, r.clock.activeMs())
        assertEquals(2, r.snapshot.hrCount)
        assertEquals(8, r.outcomes.size) // Acceptance never executes a command twice.
        assertEquals(listOf(80, 82), r.records.map(::JSONObject).filter { it.getString("kind") == "hr" }
            .map { it.getJSONObject("payload").getInt("bpm") })

        // Receipt order and worker order are different. Only the processing journal is executable.
        val older = input(1, 1100, "disconnected")
        val newer = input(2, 1200, "connected")
        root.put("events", org.json.JSONArray(listOf(older.toJson(), newer.toJson())))
        root.put("processing", org.json.JSONArray(listOf(
            PolarInputProcessing(newer, 1, 10, 1300_000_000, null, "applied", null).toJson(),
            PolarInputProcessing(older, 2, 0, 1400_000_000, null, "applied", null).toJson())))
        val reordered = PolarReplay.fixture(root)
        assertEquals(listOf("2:applied", "1:applied"), reordered.outcomes)
        assertNull(reordered.snapshot.connected)
    }
    @Test fun delayedProcessingUsesReceiptTimeForStaleness() {
        val root = asset("native-callbacks")
        val connected = input(1, 1100, "connected")
        val sample = hr(2, 1200, 82)
        root.put("events", org.json.JSONArray(listOf(connected.toJson(), sample.toJson())))
        root.put("processing", org.json.JSONArray(listOf(
            PolarInputProcessing(connected, 1, 100, 1100_000_000, null, "applied", null).toJson(),
            PolarInputProcessing(sample, 2, -100, 6201_000_000, null, "applied", null).toJson())))
        root.getJSONObject("capture_bounds").put("ended_elapsed_ns", "6201000000")
        val r = PolarReplay.fixture(root)
        assertEquals("stale", r.snapshot.heartRateAt(r.time.elapsedNs).status)
        assertNull(r.snapshot.heartRateAt(r.time.elapsedNs).value)
        assertEquals(-100L, r.time.utcMs)
    }
    @Test fun virtualSchedulerOrdersEqualTimesAndRejectsTimeTravel() {
        val t = ReplayTime(100, 0)
        val order = mutableListOf<Int>()
        t.schedule(1000) { order.add(1); t.utcMs = -100 }
        t.schedule(1000) { order.add(2) }
        t.advanceTo(1000)
        assertEquals(listOf(1, 2), order)
        assertEquals(-100L, t.utcMs)
        assertThrows(IllegalArgumentException::class.java) { t.advanceTo(0) }
        assertThrows(IllegalArgumentException::class.java) { t.schedule(0) {} }
    }
}
