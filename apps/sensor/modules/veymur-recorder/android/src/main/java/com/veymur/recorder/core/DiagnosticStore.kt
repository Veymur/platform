package com.veymur.recorder.core

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

/** Low-level storage: create, read, write and close exclusively on its creating native writer thread. */
class DiagnosticStore(context: Context, policy: DiagnosticPolicy) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val db: SQLiteDatabase
    init {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath("polar-diagnostic.db").also { it.parentFile!!.mkdirs() }
        val password = DiagnosticDatabaseKey.password(context, file.exists() && file.length() > 0)
        db = try { SQLiteDatabase.openOrCreateDatabase(file, password, null, null, null) }
        finally { password.fill(0) }
        try {
            db.execSQL("PRAGMA temp_store=MEMORY")
            db.execSQL("PRAGMA foreign_keys=ON")
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true)) }
            db.execSQL("PRAGMA synchronous=FULL")
            check(scalar("PRAGMA foreign_keys") == 1L && scalar("PRAGMA synchronous") == 2L && scalar("PRAGMA temp_store") == 2L)
            val pageSize = scalar("PRAGMA page_size")
            val maxPages = policy.maxDatabaseBytes / pageSize
            check(scalar("PRAGMA page_count") <= maxPages) { "Diagnostic database limit" }
            check(scalar("PRAGMA max_page_count=$maxPages") == maxPages)
            val version = scalar("PRAGMA user_version")
            check(version == 0L || version == 1L) { "Unsupported diagnostic schema" }
            transaction {
                db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_captures (id TEXT PRIMARY KEY, process_run_id TEXT NOT NULL, state TEXT NOT NULL, configuration_json TEXT NOT NULL, environment_json TEXT NOT NULL, policy_json TEXT NOT NULL, checkpoint_json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_events (capture_id TEXT NOT NULL, sequence INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(capture_id,sequence), FOREIGN KEY(capture_id) REFERENCES diagnostic_captures(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_processing (capture_id TEXT NOT NULL, sequence INTEGER NOT NULL, input_sequence INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(capture_id,sequence), FOREIGN KEY(capture_id,input_sequence) REFERENCES diagnostic_events(capture_id,sequence) ON DELETE CASCADE)")
                db.execSQL("PRAGMA user_version=1")
            }
        } catch (error: Exception) { db.close(); throw error }
    }
    private fun owner() { check(Thread.currentThread() === owner) { "Diagnostic writer ownership" } }
    private fun scalar(sql: String, args: Array<String>? = null): Long {
        owner()
        return db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
    }
    private fun <T> transaction(action: () -> T): T {
        owner(); db.beginTransaction()
        try { val result = action(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    fun create(status: DiagnosticCaptureStatus, configuration: String, environment: String, policy: String) = transaction {
        db.execSQL("INSERT INTO diagnostic_captures VALUES (?,?,?,?,?,?,?)", arrayOf(status.captureId, status.processRunId,
            status.state, configuration, environment, policy, statusJson(status).toString()))
    }
    fun commit(status: DiagnosticCaptureStatus, rows: List<DiagnosticRow>) = transaction {
        for (row in rows) {
            if (row.inputSequence == null) db.execSQL("INSERT INTO diagnostic_events VALUES (?,?,?)", arrayOf<Any>(status.captureId, row.sequence, row.json))
            else db.execSQL("INSERT INTO diagnostic_processing VALUES (?,?,?,?)", arrayOf<Any>(status.captureId, row.sequence, row.inputSequence, row.json))
        }
        db.execSQL("UPDATE diagnostic_captures SET state=?, checkpoint_json=? WHERE id=?", arrayOf(status.state, statusJson(status).toString(), status.captureId))
        check(scalar("SELECT changes()") == 1L)
    }
    fun get(id: String): DiagnosticCaptureStatus? {
        owner()
        return db.rawQuery("SELECT checkpoint_json FROM diagnostic_captures WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) parseStatus(JSONObject(it.getString(0))) else null
        }
    }
    fun list(): List<DiagnosticCaptureStatus> {
        owner()
        return db.rawQuery("SELECT checkpoint_json FROM diagnostic_captures ORDER BY rowid", null).use {
            val result = mutableListOf<DiagnosticCaptureStatus>()
            while (it.moveToNext()) result.add(parseStatus(JSONObject(it.getString(0))))
            result
        }
    }
    fun recoverInterrupted(): Int {
        owner()
        val unfinished = list().filter { it.state == "active" || it.state == "stopping" }
        transaction {
            for (status in unfinished) {
                val unmatched = scalar("SELECT COUNT(*) FROM diagnostic_events e WHERE e.capture_id=? AND NOT EXISTS (SELECT 1 FROM diagnostic_processing p WHERE p.capture_id=e.capture_id AND p.input_sequence=e.sequence)", arrayOf(status.captureId))
                val recovered = status.copy(state = "interrupted", endedUtcMs = null, endedElapsedNs = null,
                    receivedEvents = null, acceptedEvents = null, receivedProcessing = null, acceptedProcessing = null,
                    unprocessedInputs = unmatched, unknownTail = true, lastErrorCode = status.lastErrorCode ?: "unclean_shutdown")
                db.execSQL("UPDATE diagnostic_captures SET state=?, checkpoint_json=? WHERE id=?",
                    arrayOf(recovered.state, statusJson(recovered).toString(), status.captureId))
            }
        }
        return unfinished.size
    }
    fun delete(id: String): Boolean = transaction {
        val status = get(id) ?: return@transaction false
        check(status.state !in setOf("active", "stopping")) { "Active capture cannot be deleted" }
        db.execSQL("DELETE FROM diagnostic_captures WHERE id=?", arrayOf(id))
        true
    }
    fun readInput(id: String, sequence: Long): String? {
        owner()
        return db.rawQuery("SELECT json FROM diagnostic_events WHERE capture_id=? AND sequence=?", arrayOf(id, sequence.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    override fun close() { owner(); db.close() }

    companion object {
        private fun statusJson(s: DiagnosticCaptureStatus) = JSONObject()
            .put("capture_id", s.captureId).put("process_run_id", s.processRunId).put("state", s.state)
            .put("started_utc_ms", s.startedUtcMs).put("started_elapsed_ns", s.startedElapsedNs.toString())
            .put("ended_utc_ms", s.endedUtcMs ?: JSONObject.NULL).put("ended_elapsed_ns", s.endedElapsedNs?.toString() ?: JSONObject.NULL)
            .put("received_events", s.receivedEvents ?: JSONObject.NULL).put("accepted_events", s.acceptedEvents ?: JSONObject.NULL)
            .put("saved_events", s.savedEvents).put("known_lost_events", s.knownLostEvents)
            .put("received_processing", s.receivedProcessing ?: JSONObject.NULL).put("accepted_processing", s.acceptedProcessing ?: JSONObject.NULL)
            .put("saved_processing", s.savedProcessing).put("known_lost_processing", s.knownLostProcessing)
            .put("unprocessed_inputs", s.unprocessedInputs).put("saved_bytes", s.savedBytes)
            .put("last_committed_sequence", s.lastCommittedSequence ?: JSONObject.NULL)
            .put("last_committed_processing_sequence", s.lastCommittedProcessingSequence ?: JSONObject.NULL)
            .put("unknown_tail", s.unknownTail).put("last_error_code", s.lastErrorCode ?: JSONObject.NULL)
            .put("last_error_class", s.lastErrorClass ?: JSONObject.NULL)
        private fun parseStatus(j: JSONObject): DiagnosticCaptureStatus {
            fun long(key: String): Long? = if (j.isNull(key)) null else j.getLong(key)
            fun string(key: String): String? = if (j.isNull(key)) null else j.getString(key)
            return DiagnosticCaptureStatus(j.getString("capture_id"), j.getString("process_run_id"), j.getString("state"),
                j.getLong("started_utc_ms"), j.getString("started_elapsed_ns").toLong(), long("ended_utc_ms"), string("ended_elapsed_ns")?.toLong(),
                long("received_events"), long("accepted_events"), j.getLong("saved_events"), j.getLong("known_lost_events"),
                long("received_processing"), long("accepted_processing"), j.getLong("saved_processing"), j.getLong("known_lost_processing"),
                j.getLong("unprocessed_inputs"), j.getLong("saved_bytes"), long("last_committed_sequence"), long("last_committed_processing_sequence"),
                j.getBoolean("unknown_tail"), string("last_error_code"), string("last_error_class"))
        }
    }
}

/** Serialized on the writer only, never constructed by SDK callbacks. */
data class DiagnosticRow(val sequence: Long, val inputSequence: Long?, val json: String) {
    val bytes: Long get() = json.toByteArray(Charsets.UTF_8).size.toLong() + 1
}
