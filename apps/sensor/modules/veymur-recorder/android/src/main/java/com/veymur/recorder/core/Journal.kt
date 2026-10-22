package com.veymur.recorder.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.veymur.recorder.core.localdata.*
import com.veymur.recorder.core.localdata.aggregates.*
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class JournalStamp(val utcMs: Long, val elapsedNs: Long, val timebaseId: String, val timezoneId: String)
data class JournalLocalIdentity(val localProfileId: String, val installationId: String)

/** Native writer owns all mutations; JS never owns a connection or key. */
class Journal(private val context: Context, private val stamp: () -> JournalStamp = {
    JournalStamp(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), PolarInputBoundary.processRunId, ZoneId.systemDefault().id)
}) : AutoCloseable, RecorderSessionStore {
    private val writer = Thread.currentThread()
    private val db: SQLiteDatabase
    val localIdentity: JournalLocalIdentity
    init {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath("stage0.db").also { it.parentFile!!.mkdirs() }
        val exists = file.exists() && file.length() > 0
        val password = JournalDatabaseKey.password(context, exists)
        // SQLCipher's default corruption handler may delete files. This store must preserve them.
        val preserve = DatabaseErrorHandler { throw IllegalStateException("Recorder database corruption; original file preserved") }
        db = try {
            if (exists) SQLiteDatabase.openDatabase(file.absolutePath, password, null, SQLiteDatabase.OPEN_READONLY, preserve, null).use {
                JournalSchema.checkVersion(it)
            }
            SQLiteDatabase.openOrCreateDatabase(file, password, null, preserve, null)
        } finally { password.fill(0) }
        try {
            JournalSchema.migrate(db, stamp().utcMs)
            localIdentity = db.rawQuery("SELECT local_profile_id,installation_id FROM local_store_identity WHERE singleton=1", null).use {
                check(it.moveToFirst()) { "Missing local recorder identity" }; JournalLocalIdentity(it.getString(0), it.getString(1))
            }
        } catch (error: Exception) { db.close(); throw error }
    }
    private fun owner() { check(Thread.currentThread() === writer) { "Recorder writer ownership" } }
    private fun <T> transaction(action: () -> T): T {
        owner(); db.beginTransaction()
        try { val value = action(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }
    fun localData(request: JSONObject): Map<String, Any?> {
        owner()
        return JournalOperations(db, localIdentity, File(context.cacheDir, "exports"), ::recalculate).execute(request)
    }
    fun exportUserFile(request: JSONObject): Map<String, Any?> {
        owner()
        return JournalOperations(db, localIdentity, File(context.cacheDir, "exports"), ::recalculate).exportUserFile(request)
    }
    fun recoverInterrupted(): Int = transaction {
        val ids = mutableListOf<String>()
        db.rawQuery("SELECT id FROM sessions WHERE state IN ('recording','paused','finishing')", null).use {
            while (it.moveToNext()) ids.add(it.getString(0))
        }
        for (id in ids) {
            append(id, "interrupted", JSONObject().put("reason", "process_ended"))
            db.execSQL("UPDATE sessions SET state='interrupted',hr_state='inactive',gps_state=CASE WHEN gps=0 THEN 'disabled' ELSE 'inactive' END WHERE id=?", arrayOf(id))
        }
        ids.size
    }
    override fun begin(device: String, gps: Boolean, configuration: JSONObject?): String = transaction {
        val id = UUID.randomUUID().toString(); val now = stamp()
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("device", device); put("started", now.utcMs); put("state", "recording"); put("gps", if (gps) 1 else 0)
            put("route_requested", if ((configuration?.opt("route_requested") as? Boolean) ?: gps) 1 else 0)
            put("schema_version", 1); put("local_profile_id", localIdentity.localProfileId); put("installation_id", localIdentity.installationId)
            JournalOperations.selectedRanges(db, localIdentity.localProfileId)?.let { put("hr_ranges_snapshot", it.toString()) }
            put("timezone_id", now.timezoneId); put("hr_state", "waiting"); put("gps_state", if (gps) "waiting" else "disabled"); put("storage_origin", "native_v2")
        })
        append(id, "start", JSONObject()); configuration?.let { append(id, "configuration", it) }
        id
    }
    override fun transition(id: String, event: String, state: String, activeMs: Long, payload: JSONObject) = transaction {
        append(id, event, payload); updateState(id, state, activeMs)
    }
    override fun append(id: String, kind: String, payload: JSONObject) = transaction {
        val now = stamp(); val recordId = UUID.randomUUID().toString()
        val session = db.rawQuery("SELECT local_profile_id,device,current_segment_id,storage_origin,gps FROM sessions WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Unknown recorder session" }
            StoredWriterSession(it.getString(0), it.getString(1), if (it.isNull(2)) null else it.getString(2), it.getString(3), it.getInt(4) != 0)
        }
        var segment = session.segment
        if (kind == "start" || kind == "resume") {
            check(segment == null) { "Segment already active" }
            segment = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO segments(id,session,timebase_id,start_elapsed_ns,start_record_id,closure) VALUES (?,?,?,?,?,'open')",
                arrayOf(segment, id, now.timebaseId, now.elapsedNs.toString(), recordId))
            db.execSQL("UPDATE sessions SET current_segment_id=? WHERE id=?", arrayOf(segment, id))
        }
        val source = when (kind) { "hr" -> "polar_hr"; "gps" -> "phone_gps"; else -> "recorder" }
        val values = ContentValues().apply {
            put("session", id); put("received_utc", now.utcMs); put("elapsed_ms", now.elapsedNs / 1_000_000)
            put("kind", kind); put("payload", payload.toString()); put("id", recordId); put("schema_version", 1)
            put("local_profile_id", session.profile); put("installation_id", localIdentity.installationId); put("segment_id", segment)
            put("source_type", source); put("source_id", if (source == "polar_hr") session.device else localIdentity.installationId)
            put("storage_origin", "native_v2")
        }
        val metadata = payload.optJSONObject("observation_metadata")
        if (metadata != null) putMetadata(values, metadata)
        else if (kind != "hr" && kind != "gps") {
            putMetadata(values, JSONObject().put("normalization_version", NORMALIZATION_VERSION)
                .put("observed_at_utc", JSONObject.NULL).put("recorded_at_utc", Instant.ofEpochMilli(now.utcMs).toString())
                .put("timezone_id", now.timezoneId).put("time_origin", if (kind == "interrupted") "recovery" else if (kind in listOf("start","pause","resume","finish")) "user_action" else "receipt")
                .put("timebase_id", now.timebaseId).put("receipt_elapsed_realtime_ns", now.elapsedNs.toString())
                .put("quality_flags", JSONArray(listOf("valid_format"))).put("quality_reasons", JSONArray()))
        }
        db.insertOrThrow("records", null, values)
        if (kind in listOf("pause", "finish", "interrupted")) {
            if (segment != null) {
                if (kind == "interrupted") {
                    // Recovery timestamp is never used as the end of an older process's segment.
                    db.execSQL("UPDATE segments SET end_record_id=?,closure='interrupted_unknown' WHERE id=?", arrayOf(recordId, segment))
                } else {
                    val base = db.rawQuery("SELECT timebase_id FROM segments WHERE id=?", arrayOf(segment)).use { check(it.moveToFirst()); it.getString(0) }
                    check(base == now.timebaseId) { "Closing segment in a different timebase" }
                    db.execSQL("UPDATE segments SET end_elapsed_ns=?,end_record_id=?,closure='closed' WHERE id=?", arrayOf(now.elapsedNs.toString(), recordId, segment))
                }
                db.execSQL("UPDATE sessions SET current_segment_id=NULL WHERE id=?", arrayOf(id))
            }
        }
        when (kind) {
            "hr" -> db.execSQL("UPDATE sessions SET hr_state=? WHERE id=?", arrayOf(if (payload.optBoolean("contact_supported") && !payload.optBoolean("contact")) "lost" else "fresh", id))
            "disconnected" -> db.execSQL("UPDATE sessions SET hr_state='lost' WHERE id=?", arrayOf(id))
            "gps" -> db.execSQL("UPDATE sessions SET gps_state='available' WHERE id=?", arrayOf(id))
            "gps_unavailable", "gps_error" -> db.execSQL("UPDATE sessions SET gps_state='lost' WHERE id=?", arrayOf(id))
        }
    }
    fun updateState(id: String, state: String, activeMs: Long) = transaction {
        db.execSQL("UPDATE sessions SET state=?,active_ms=? WHERE id=?", arrayOf<Any>(state, activeMs, id))
        db.rawQuery("SELECT changes()", null).use { check(it.moveToFirst() && it.getInt(0) == 1) { "Unknown recorder session" } }
        if (state in listOf("paused", "finishing", "completed", "interrupted")) {
            db.execSQL("UPDATE sessions SET hr_state='inactive',gps_state=CASE WHEN gps=0 THEN 'disabled' ELSE 'inactive' END WHERE id=?", arrayOf(id))
        }
        if (state == "completed") db.execSQL("UPDATE sessions SET ended_utc=? WHERE id=?", arrayOf<Any>(stamp().utcMs, id))
    }
    /** Native aggregate reader shared by verification and the local details API. */
    fun recalculate(id: String, parameters: AggregateParameters): StoredAggregateResult { owner(); return JournalAggregateReader(db).read(id, parameters) }

    fun latest(): String? {
        owner()
        return db.rawQuery("SELECT id FROM sessions ORDER BY started DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    fun export(id: String): File {
        owner()
        val metadata = JSONObject().put("schema_version", 1).put("app_version", "0.0.1")
            .put("sdk_version", "8.4.0").put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("exported_at_utc_ms", System.currentTimeMillis())
            .put("hr_timestamp_source", "phone_receipt_time_not_sensor_measurement_time")
            .put("database_version", JOURNAL_DATABASE_VERSION)
        db.rawQuery("SELECT device, started, state, active_ms, gps, local_profile_id, installation_id, storage_origin FROM sessions WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            metadata.put("session_id", id).put("device_id", it.getString(0)).put("started_utc_ms", it.getLong(1))
                .put("state", it.getString(2)).put("active_ms", it.getLong(3)).put("gps_requested", it.getInt(4) == 1)
                .put("local_profile_id", it.getString(5)).put("installation_id", if (it.isNull(6)) JSONObject.NULL else it.getString(6))
                .put("storage_origin", it.getString(7))
        }
        val directory = File(context.cacheDir, "exports").also { it.mkdirs() }
        val output = JournalOperations.exportFile(directory, id)
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json")); zip.write(metadata.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("records.csv"))
            zip.write("sequence,received_utc_ms,elapsed_realtime_ms,kind,payload_json\n".toByteArray())
            db.rawQuery("SELECT sequence, received_utc, elapsed_ms, kind, payload FROM records WHERE session=? ORDER BY sequence", arrayOf(id)).use {
                while (it.moveToNext()) {
                    val payload = it.getString(4).replace("\"", "\"\"")
                    zip.write("${it.getLong(0)},${it.getLong(1)},${it.getLong(2)},${it.getString(3)},\"$payload\"\n".toByteArray())
                }
            }
            zip.closeEntry()
        }
        return output
    }
    override fun close() { owner(); db.close() }
}
