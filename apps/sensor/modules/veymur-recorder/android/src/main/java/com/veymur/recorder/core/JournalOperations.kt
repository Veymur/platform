package com.veymur.recorder.core

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.ZoneId
import com.veymur.recorder.core.localdata.aggregates.AggregateParameters
import com.veymur.recorder.core.localdata.export.writePulseCsv
import com.veymur.recorder.core.localdata.export.writeRouteGpx
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Invoked only by Journal's writer. No account, network, or recorder snapshot dependency. */
internal class JournalOperations(
    private val db: SQLiteDatabase, private val identity: JournalLocalIdentity,
    private val exports: File, private val calculate: (String, AggregateParameters) -> StoredAggregateResult,
    private val freeBytes: () -> Long = { exports.parentFile?.usableSpace ?: 0L }, private val now: () -> Long = System::currentTimeMillis,
) {
    private val profile get() = identity.localProfileId
    private fun invalid(): Nothing = throw RecorderError("INVALID_ARGUMENT", "Invalid local data request")
    private fun text(o: JSONObject, key: String): String = (o.opt(key) as? String)?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: invalid()
    private fun integer(o: JSONObject, key: String, min: Long = 0, max: Long = 9007199254740991L): Long {
        val v = o.opt(key) as? Number ?: invalid()
        val d = v.toDouble(); if (!d.isFinite() || d < min || d > max || d != v.toLong().toDouble()) invalid()
        return v.toLong()
    }
    private fun number(o: JSONObject, key: String): Double = (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: invalid()
    private fun flag(o: JSONObject, key: String) { if (o.opt(key) !is Boolean) invalid() }
    private fun choice(o: JSONObject, key: String, choices: Set<String>) { if (text(o,key) !in choices) invalid() }
    private fun keys(o: JSONObject, vararg names: String) { if (o.keys().asSequence().toSet() != names.toSet()) invalid() }
    private fun nullableId(o: JSONObject, key: String): String? { if (!o.has(key)) invalid(); return if (o.isNull(key)) null else text(o,key) }
    private fun transaction(action: () -> Map<String, Any?>): Map<String, Any?> {
        db.beginTransaction(); try { val result = action(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    fun execute(request: JSONObject): Map<String, Any?> = when (text(request,"operation")) {
        "list" -> list(request)
        "details" -> details(request)
        "stream" -> stream(request)
        "preferences" -> { keys(request,"operation"); preferences() }
        "saveSettings" -> save(request, "settings")
        "saveRanges" -> save(request, "hr_ranges")
        "pendingDeletions" -> {
            keys(request,"operation","after_id","limit")
            val after = nullableId(request,"after_id") ?: ""
            val limit = integer(request,"limit",1,100).toInt()
            val ids = db.rawQuery("SELECT session_id FROM local_deletion_cleanup WHERE session_id>? ORDER BY session_id LIMIT ${limit+1}",arrayOf(after)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
            mapOf("ids" to ids.take(limit), "next_id" to if(ids.size>limit) ids[limit-1] else null)
        }
        "delete" -> delete(request)
        else -> invalid()
    }
    private fun session(id: String): Map<String, Any?> = db.rawQuery("SELECT * FROM sessions WHERE id=? AND local_profile_id=?", arrayOf(id,profile)).use { c ->
        if (!c.moveToFirst()) throw RecorderError("INVALID_ARGUMENT", "Local session not found")
        fun s(name: String): String? = c.getColumnIndexOrThrow(name).let { if(c.isNull(it)) null else c.getString(it) }
        mapOf("id" to id, "local_profile_id" to profile, "state" to s("state"), "hr_state" to s("hr_state"), "gps_state" to s("gps_state"),
            "started_utc_ms" to c.getLong(c.getColumnIndexOrThrow("started")), "ended_utc_ms" to s("ended_utc")?.toLong(),
            "timezone_id" to s("timezone_id"), "device_id" to s("device"), "route_requested" to s("route_requested")?.let { it == "1" },
            "storage_origin" to s("storage_origin"), "recorded_active_ms" to c.getLong(c.getColumnIndexOrThrow("active_ms")),
            "hr_ranges_snapshot" to s("hr_ranges_snapshot")?.let { jsonMap(JSONObject(it)) },
            "hr_coverage" to null, "average_hr_bpm" to null, "gps_distance_m" to null, "metrics_unavailable_reason" to "details_calculation_required")
    }
    private fun list(r: JSONObject): Map<String, Any?> {
        keys(r,"operation","limit","before")
        val limit = integer(r,"limit",1,100).toInt()
        val before = r.opt("before")
        var where = "local_profile_id=?"; val args = mutableListOf(profile)
        if (before != JSONObject.NULL) {
            val b = before as? JSONObject ?: invalid(); keys(b,"started_utc_ms","id")
            val start = integer(b,"started_utc_ms", -9007199254740991L).toString(); val id = text(b,"id")
            where += " AND (started < ? OR (started = ? AND id < ?))"; args.addAll(listOf(start,start,id))
        }
        val ids = db.rawQuery("SELECT id FROM sessions WHERE $where ORDER BY started DESC,id DESC LIMIT ${limit+1}", args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val items = ids.take(limit).map(::session)
        return mapOf("items" to items, "next" to if(ids.size>limit) items.last().let { mapOf("started_utc_ms" to it["started_utc_ms"], "id" to it["id"]) } else null)
    }
    private fun parameters(r: JSONObject): Pair<JSONObject, AggregateParameters> {
        val p = r.opt("parameters") as? JSONObject ?: invalid()
        keys(p,"hr_stale_after_ms","gps_max_accuracy_m","gps_max_gap_ms","gps_max_speed_m_s","map_hr_window_ms")
        return p to AggregateParameters(integer(p,"hr_stale_after_ms",1,9007199254),number(p,"gps_max_accuracy_m"), integer(p,"gps_max_gap_ms",1,9007199254),number(p,"gps_max_speed_m_s"),integer(p,"map_hr_window_ms",0,9007199254))
    }
    private fun payloadSize(id: String): Pair<Int, Long> =
        db.rawQuery("SELECT count(*),coalesce(sum(length(payload)),0) FROM (SELECT payload FROM records WHERE session=? LIMIT 50001)",arrayOf(id)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
    private fun exceedsInteractiveLimit(id: String) = payloadSize(id).let { (count, bytes) -> count>50000 || bytes>16777216 }
    private fun details(r: JSONObject): Map<String, Any?> {
        keys(r,"operation","id","parameters"); val id = text(r,"id"); val saved = session(id)
        val (p, parameters) = parameters(r)
        // Bound synchronous writer work; large sessions remain readable through stream pagination.
        val tooLarge = exceedsInteractiveLimit(id)
        val result = if(tooLarge) StoredAggregateResult("journal-v2-logical-v1", "local-session-aggregates-v1", null, listOf("interactive_calculation_limit")) else calculate(id,parameters)
        val a = result.aggregates
        val summary = a?.let { mapOf("distance_method_version" to it.distance_method_version,"sphere_radius_m" to it.sphere_radius_m,"active_duration_ms" to it.active_duration_ms,"hr_covered_duration_ms" to it.hr_covered_duration_ms,
            "hr_coverage" to it.hr_coverage,"average_hr_bpm" to it.average_hr_bpm,"gps_distance_m" to it.gps_distance_m,
            "interrupted_tail_unknown" to it.interrupted_tail_unknown,"unassigned_hr_duration_ms" to it.unassigned_hr_duration_ms,
            "range_durations" to it.range_durations.map { range -> mapOf("range_id" to range.range_id,"duration_ms" to range.duration_ms) }) }
        return mapOf("session" to saved,"summary" to summary,"parameters" to jsonMap(p),"method_version" to result.method_version,"projection_version" to result.projection_version,"unavailable_reasons" to result.unavailable_reasons)
    }
    fun exportUserFile(r: JSONObject): Map<String, Any?> {
        keys(r,"id","format","parameters"); val id = text(r,"id"); val saved = session(id)
        choice(r,"format",setOf("csv","gpx")); val format = r.getString("format")
        val (_, parameters) = parameters(r)
        if (saved["state"] !in setOf("completed","interrupted")) throw RecorderError("INVALID_STATE","Finish the session before exporting it")
        val (count, bytes) = payloadSize(id)
        if (count>50000 || bytes>16777216) throw RecorderError("INVALID_STATE","This session is too long to export on the phone right now")
        if (freeBytes() < EXPORT_FREE_SPACE_RESERVE + bytes * 3) throw RecorderError("STORAGE_ERROR","Not enough free space to prepare the export")
        val result = calculate(id,parameters)
        val aggregates = result.aggregates; val inputs = result.inputs
        if (aggregates == null || inputs == null) throw RecorderError("INVALID_STATE","Export unavailable: ${result.unavailable_reasons.joinToString(",")}")
        if (format == "gpx" && aggregates.route_segments.none { it.points.isNotEmpty() }) throw RecorderError("INVALID_STATE","No usable route was recorded in this session")
        pruneExports()
        val target = exportFile(exports,id,if (format == "csv") PULSE_SUFFIX else ROUTE_SUFFIX)
        val temp = File(exports,"${target.name}.${java.util.UUID.randomUUID()}.part")
        var rows = 0
        try {
            java.io.FileOutputStream(temp).use { raw ->
                val writer = raw.bufferedWriter(Charsets.UTF_8)
                rows = if (format == "csv") writePulseCsv(inputs.session, inputs.hr, aggregates, writer) else writeRouteGpx(inputs.session, inputs.gps, aggregates, writer)
                writer.flush(); raw.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        return mapOf("path" to target.absolutePath, "mimeType" to if (format == "csv") "text/csv" else "application/gpx+xml", "rows" to rows)
    }
    private fun pruneExports() {
        if (!exports.isDirectory && !exports.mkdirs()) throw RecorderError("STORAGE_ERROR","Export folder unavailable")
        exports.listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || now() - it.lastModified() >= EXPORT_TTL_MS || it.lastModified() > now()) }?.forEach { it.delete() }
    }
    private fun stream(r: JSONObject): Map<String, Any?> {
        keys(r,"operation","id","stream","after_sequence","limit")
        val id = text(r,"id"); session(id)
        choice(r,"stream",setOf("hr","gps","events")); val stream = r.getString("stream")
        val after = integer(r,"after_sequence"); val limit = integer(r,"limit",1,200).toInt()
        val filter = if(stream=="events") "kind NOT IN ('hr','gps')" else "kind='$stream'"
        val items = mutableListOf<Map<String,Any?>>()
        var bytes = 0; var more = false
        db.rawQuery("SELECT id,sequence,kind,received_utc,elapsed_ms,CASE WHEN length(CAST(payload AS BLOB))<=262144 THEN payload ELSE NULL END,storage_origin,segment_id,length(CAST(payload AS BLOB)) FROM records WHERE session=? AND sequence>? AND $filter ORDER BY sequence LIMIT ${limit+1}",arrayOf(id,after.toString())).use { c ->
            while(c.moveToNext()) {
                if(items.size==limit) { more=true; break }
                val size = c.getLong(8)
                if(size>262144) throw RecorderError("STORAGE_ERROR","Record exceeds interactive payload limit; use diagnostic ZIP")
                if(bytes+size>524288) { more=true; break }; bytes+=size.toInt()
                val payload = c.getString(5)
                items.add(mapOf("session_id" to id,"local_profile_id" to profile,"id" to c.getString(0),"sequence" to c.getLong(1),"kind" to c.getString(2),"received_utc_ms" to c.getLong(3),
                    "elapsed_realtime_ms" to c.getLong(4),"payload_json" to payload,"storage_origin" to c.getString(6),"segment_id" to if(c.isNull(7)) null else c.getString(7)))
            }
        }
        return mapOf("items" to items,"next_sequence" to if(more) items.last()["sequence"] else null)
    }
    private fun latest(table: String): JSONObject? = db.rawQuery("SELECT payload FROM $table WHERE local_profile_id=? ORDER BY revision DESC LIMIT 1",arrayOf(profile)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun preferences() = mapOf("settings" to latest("settings")?.let(::jsonMap),"hr_ranges" to latest("hr_ranges")?.let(::jsonMap))
    private fun save(r: JSONObject, table: String): Map<String,Any?> {
        keys(r,"operation","expected_revision","value"); val expected = integer(r,"expected_revision",0,Int.MAX_VALUE.toLong()-1)
        val v = r.opt("value") as? JSONObject ?: invalid()
        if(table=="settings") {
            keys(v,"units","timezone_id","theme","route_enabled","hr_ranges_id","alerts")
            choice(v,"units",setOf("metric","imperial")); choice(v,"theme",setOf("system","light","dark")); flag(v,"route_enabled")
            try { ZoneId.of(text(v,"timezone_id")) } catch(e: java.time.DateTimeException) { invalid() }; val rangeId = nullableId(v,"hr_ranges_id")
            if(rangeId != null && latest("hr_ranges")?.getString("id") != rangeId) invalid()
            val a = v.opt("alerts") as? JSONObject ?: invalid(); keys(a,"enabled","sound","vibration","debounce_ms","hysteresis_bpm","repeat_ms")
            for(k in listOf("enabled","sound","vibration")) flag(a,k)
            integer(a,"debounce_ms"); integer(a,"hysteresis_bpm",0,Int.MAX_VALUE.toLong())
            if(!a.has("repeat_ms")) invalid(); if(!a.isNull("repeat_ms")) integer(a,"repeat_ms",1)
        } else {
            keys(v,"ranges"); val ranges = v.opt("ranges") as? JSONArray ?: invalid(); if(ranges.length()>32) invalid()
            val ids = mutableSetOf<String>(); val intervals = mutableListOf<Pair<Long,Long>>()
            for(i in 0 until ranges.length()) {
                val a = ranges.opt(i) as? JSONObject ?: invalid(); keys(a,"id","label","lower_bpm_inclusive","upper_bpm_exclusive")
                if(!ids.add(text(a,"id"))) invalid(); text(a,"label")
                if(!a.has("lower_bpm_inclusive") || !a.has("upper_bpm_exclusive")) invalid()
                val low = if(a.isNull("lower_bpm_inclusive")) 0 else integer(a,"lower_bpm_inclusive",0,Int.MAX_VALUE.toLong())
                val high = if(a.isNull("upper_bpm_exclusive")) Long.MAX_VALUE else integer(a,"upper_bpm_exclusive",1,Int.MAX_VALUE.toLong())
                if(low>=high) invalid(); intervals.add(low to high)
            }
            val sorted=intervals.sortedBy { it.first }; if(sorted.zipWithNext().any { it.first.second>it.second.first }) invalid()
        }
        return transaction {
            val previous = latest(table)
            if((previous?.getLong("revision") ?: 0L)!=expected) throw RecorderError("INVALID_STATE","Preferences changed; reload before saving")
            val id=previous?.getString("id") ?: java.util.UUID.randomUUID().toString()
            val payload=JSONObject(v.toString()).put("id",id).put("revision",expected+1).put("schema_version",1).put("local_profile_id",profile).put("installation_id",identity.installationId)
            db.execSQL("INSERT INTO $table (id,revision,schema_version,local_profile_id,installation_id,payload) VALUES (?,?,1,?,?,?)", arrayOf<Any>(id,expected+1,profile,identity.installationId,payload.toString()))
            preferences()
        }
    }
    private fun delete(r: JSONObject): Map<String,Any?> {
        keys(r,"operation","id"); val id=text(r,"id")
        var deleted=false
        transaction {
            db.rawQuery("SELECT state FROM sessions WHERE id=? AND local_profile_id=?",arrayOf(id,profile)).use { c ->
                if(c.moveToFirst()) {
                    if(c.getString(0) !in setOf("completed","interrupted")) throw RecorderError("INVALID_STATE","Finish the session before deletion")
                    db.execSQL("INSERT OR IGNORE INTO local_deletion_cleanup(session_id) VALUES (?)",arrayOf(id))
                    db.execSQL("DELETE FROM records WHERE session=?",arrayOf(id)); db.execSQL("DELETE FROM segments WHERE session=?",arrayOf(id))
                    db.execSQL("DELETE FROM sessions WHERE id=? AND local_profile_id=?",arrayOf(id,profile)); deleted=true
                }
            }
            emptyMap()
        }
        val pending=db.rawQuery("SELECT 1 FROM local_deletion_cleanup WHERE session_id=?",arrayOf(id)).use { it.moveToFirst() }
        var complete=true
        if(pending) {
            complete=try { MANAGED_SUFFIXES.map { suffix -> exportFile(exports,id,suffix) }.map { file -> !file.exists() || (file.isFile && file.delete()) }.all { it } } catch(e: Exception) { false }
            if(complete) db.execSQL("DELETE FROM local_deletion_cleanup WHERE session_id=?",arrayOf(id))
        }
        return mapOf("id" to id,"data_deleted" to deleted,"cleanup" to if(complete) "complete" else "pending", "cleanup_error" to if(complete) null else "MANAGED_FILE_DELETE_FAILED")
    }
    companion object {
        const val PULSE_SUFFIX = "-pulse.csv"
        const val ROUTE_SUFFIX = "-route.gpx"
        private const val ARCHIVE_SUFFIX = ".zip"
        private val MANAGED_SUFFIXES = listOf(ARCHIVE_SUFFIX, PULSE_SUFFIX, ROUTE_SUFFIX)
        private const val EXPORT_TTL_MS = 24L * 60 * 60 * 1000
        private const val EXPORT_FREE_SPACE_RESERVE = 64L * 1024 * 1024
        fun exportFile(directory: File,id: String,suffix: String = ARCHIVE_SUFFIX): File {
            require(suffix in MANAGED_SUFFIXES) { "Unknown export kind" }
            require(id.isNotEmpty() && id.length<=256 && id.none { it=='/' || it=='\\' || it=='\u0000' }) { "Unsafe session export name" }
            require(directory.canonicalFile == File(directory.parentFile.canonicalFile,directory.name)) { "Managed export directory is a link" }
            val f=File(directory,"veymur-$id$suffix")
            require(f.canonicalFile==File(directory.canonicalFile,f.name)) { "Export outside managed directory" }; return f
        }
        fun selectedRanges(db: SQLiteDatabase,profile: String): JSONObject? {
            val selected=db.rawQuery("SELECT payload FROM settings WHERE local_profile_id=? ORDER BY revision DESC LIMIT 1",arrayOf(profile)).use { if(it.moveToFirst()) JSONObject(it.getString(0)).opt("hr_ranges_id") as? String else null } ?: return null
            return db.rawQuery("SELECT payload FROM hr_ranges WHERE id=? AND local_profile_id=? ORDER BY revision DESC LIMIT 1",arrayOf(selected,profile)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
        }
        private fun jsonValue(v: Any?): Any? = when(v) { null,JSONObject.NULL -> null; is JSONObject -> jsonMap(v); is JSONArray -> (0 until v.length()).map { jsonValue(v.get(it)) }; else -> v }
        private fun jsonMap(v: JSONObject): Map<String,Any?> = v.keys().asSequence().associateWith { jsonValue(v.get(it)) }
    }
}
