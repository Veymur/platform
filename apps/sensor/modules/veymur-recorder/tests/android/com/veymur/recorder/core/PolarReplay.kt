package com.veymur.recorder.core

import org.json.JSONObject
import java.util.PriorityQueue

/** Test-only virtual scheduler. UTC may move backwards; monotonic time may not. */
class ReplayTime(var utcMs: Long, elapsedNs: Long) {
    var elapsedNs: Long = elapsedNs; private set
    private data class Task(val at: Long, val ordinal: Long, val action: () -> Unit)
    private val tasks = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.ordinal })
    private var ordinal = 0L
    fun schedule(at: Long, action: () -> Unit) {
        require(at >= elapsedNs) { "Replay cannot schedule into the past" }
        tasks.add(Task(at, ordinal++, action))
    }
    fun advanceTo(target: Long) {
        require(target >= elapsedNs) { "Non-monotonic replay time" }
        while (tasks.peek()?.at?.let { it <= target } == true) {
            val task = tasks.remove()
            utcMs += (task.at / 1_000_000 - elapsedNs / 1_000_000)
            elapsedNs = task.at
            task.action()
        }
        utcMs += (target / 1_000_000 - elapsedNs / 1_000_000)
        elapsedNs = target
    }
}

/** No PolarBleApi, Android service, radio, real clock, sleeps, or disk writer is created. */
class PolarReplay(val time: ReplayTime) {
    var snapshot = RecorderSnapshot(ready = true); private set
    val records = mutableListOf<String>()
    val outcomes = mutableListOf<String>()
    val observations = mutableListOf<String>()
    val limitations = linkedSetOf<String>()
    private var nextId = 0
    private val store = object : RecorderSessionStore {
        override fun begin(device: String, gps: Boolean, configuration: JSONObject?): String {
            require(!gps) { "Phone GPS is outside Polar replay" }
            val id = "replay-session-${++nextId}"
            append(id, "start", JSONObject())
            configuration?.let { append(id, "configuration", it) }
            return id
        }
        override fun transition(id: String, event: String, state: String, activeMs: Long, payload: JSONObject) {
            append(id, event, payload)
        }
        override fun append(id: String, kind: String, payload: JSONObject) {
            records.add(JSONObject().put("session", id).put("kind", kind).put("payload", payload)
                .put("utc_ms", time.utcMs).put("elapsed_ns", time.elapsedNs.toString()).toString())
        }
    }
    val clock = RecordingClock { time.elapsedNs / 1_000_000 }
    val session = RecorderSession(store, clock)
    private val control = RecorderControl({ session }, { snapshot }, { snapshot = it }, { time.utcMs })
    private val processor = PolarRecorderProcessor({ snapshot }, { snapshot = it }, clock, mutableMapOf()) { kind, payload ->
        session.id?.let {
            store.append(it, kind, payload)
            snapshot = snapshot.copy(lastCommit = time.utcMs)
        }
    }
    fun process(input: PolarInputEvent): String {
        val outcome = try {
            if (input.source == "app_command") {
                val p = input.payload()
                require(!p.getBoolean("route")) { "Phone GPS is outside Polar replay" }
                when (val name = p.getString("name")) {
                    "start" -> control.start(false, false, JSONObject().put("route_requested", false).put("route_enabled", false))
                    "pause", "resume" -> control.pauseOrResume(name)
                    "finish" -> control.finish(-1, "Recording saved. Ready to export")
                    else -> throw IllegalArgumentException("Unsupported replay command: $name; external effect input required")
                }
                "committed"
            } else if (processor.process(input)) "applied" else "ignored"
        } catch (error: RecorderError) { "rejected:${error.code}" }
        outcomes.add("${input.sequence}:$outcome")
        observe()
        return outcome
    }
    fun observe(): String {
        val hr = snapshot.heartRateAt(time.elapsedNs)
        return JSONObject().put("elapsed_ns", time.elapsedNs.toString()).put("utc_ms", time.utcMs)
            .put("state", session.state).put("active_ms", clock.activeMs())
            .put("connected", snapshot.connected ?: JSONObject.NULL)
            .put("hr", hr.value ?: JSONObject.NULL).put("hr_status", hr.status)
            .put("hr_count", snapshot.hrCount).toString().also { observations.add(it) }
    }
    /** Periodic publication models silence/staleness; it never manufactures an HR sample. */
    fun scheduleTicks(untilNs: Long) {
        var at = time.elapsedNs + 1_000_000_000
        while (at <= untilNs) {
            time.schedule(at) { observe() }
            at += 1_000_000_000
        }
    }
    fun restorePreparation(configuration: JSONObject) {
        require(configuration.getString("session_state") == "preparation" && configuration.isNull("session_id") && configuration.getLong("active_ms") == 0L) {
            "Active initial session requires transaction history; replay refuses to invent it"
        }
        require(!configuration.getBoolean("route_enabled") && configuration.getLong("hr_stale_after_ms") == 5000L)
        val received = if (configuration.isNull("last_hr_received_elapsed_ns")) 0L else configuration.getString("last_hr_received_elapsed_ns").toLong()
        snapshot = snapshot.copy(connected = configuration.nullableString("connected_device_id"),
            hr = if (configuration.isNull("last_hr")) null else configuration.getInt("last_hr").takeIf { it > 0 },
            lastHrElapsed = received / 1_000_000, lastHrElapsedNs = received)
    }
    companion object {
        /** Call only after independent fixture/schema validation. Runtime also checks replay invariants. */
        fun fixture(root: JSONObject): PolarReplay {
            require(root.getInt("fixture_version") == 1) { "Unsupported fixture schema" }
            val bounds = root.getJSONObject("capture_bounds")
            val initial = root.getJSONObject("initial_state")
            val start = initial.getString("as_of_elapsed_ns").toLong()
            require(start >= 0)
            val replay = PolarReplay(ReplayTime(bounds.getLong("started_utc_ms"), start))
            replay.restorePreparation(initial.getJSONObject("configuration"))
            val warnings = root.getJSONArray("limitations")
            repeat(warnings.length()) { replay.limitations.add(warnings.getString(it)) }
            if (!root.getBoolean("replay_ready")) replay.limitations.add("source_not_replay_ready")
            val events = root.getJSONArray("events")
            require(events.length() <= 256) { "Fixture input limit" }
            val byId = linkedMapOf<Long, PolarInputEvent>()
            var previousInput = -1L
            var previousReceipt = start
            repeat(events.length()) { i ->
                val json = events.getJSONObject(i)
                require(json.getInt("schema_version") == 1) { "Unsupported input schema" }
                val event = json.input()
                require(event.sequence > previousInput && event.receivedElapsedNs >= previousReceipt)
                previousInput = event.sequence; previousReceipt = event.receivedElapsedNs
                byId[event.sequence] = event
            }
            val processed = mutableSetOf<Long>()
            val processing = root.getJSONArray("processing")
            require(processing.length() <= 512) { "Fixture processing limit" }
            val accepted = mutableSetOf<Long>()
            var previousSequence = -1L
            var previousTime = start
            repeat(processing.length()) { i ->
                val row = processing.getJSONObject(i)
                require(row.getInt("schema_version") == 1) { "Unsupported processing schema" }
                val sequence = row.getLong("processing_sequence")
                val at = row.getString("processed_elapsed_ns").toLong()
                require(sequence > previousSequence && at >= previousTime && at - start <= 3_605_000_000_000L)
                previousSequence = sequence; previousTime = at
                val input = requireNotNull(byId[row.getLong("input_sequence")]) { "Missing processing parent" }
                require(at >= input.receivedElapsedNs)
                if (input.source == "app_command" && row.getString("outcome") == "accepted") {
                    require(input.sequence !in processed && accepted.add(input.sequence)) { "Duplicate/out-of-order acceptance" }
                    return@repeat
                }
                require(row.getString("outcome") != "failed") { "Recorded failure requires an external effect model" }
                require(processed.add(input.sequence)) { "Duplicate terminal processing" }
                // Outcome/error/snapshot in the source are deliberately not a correctness oracle.
                replay.time.schedule(at) {
                    replay.time.utcMs = row.getLong("processed_utc_ms")
                    replay.process(input)
                }
            }
            if (processed.size != byId.size) replay.limitations.add("unprocessed_inputs_not_applied")
            val end = if (bounds.isNull("ended_elapsed_ns")) maxOf(previousTime, previousReceipt) else maxOf(previousTime, previousReceipt, bounds.getString("ended_elapsed_ns").toLong())
            require(end >= start && end - start <= 3_605_000_000_000L) { "Fixture duration limit" }
            replay.scheduleTicks(end)
            replay.time.advanceTo(end)
            replay.observe()
            return replay
        }
    }
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.input() = PolarInputEvent(nullableString("capture_id"), getString("process_run_id"), getLong("sequence"),
    getLong("received_utc_ms"), getString("received_elapsed_ns").toLong(), getString("source"), getString("event_type"),
    getString("callback_variant"), nullableString("device_id"), getJSONObject("payload").toString())
