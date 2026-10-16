package com.veymur.recorder.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalOperationsTest {
    private fun context(): Context {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "local-operations-${UUID.randomUUID()}-"
        return object : ContextWrapper(host) {
            override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix+name)
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix+name,mode)
            override fun getCacheDir() = File(baseContext.cacheDir,prefix).also { it.mkdirs() }
        }
    }
    private fun db(j: Journal) = Journal::class.java.getDeclaredField("db").apply { isAccessible=true }.get(j) as SQLiteDatabase
    private fun op(j: Journal, operation: String, vararg fields: Pair<String,Any?>): Map<String,Any?> = j.localData(JSONObject().put("operation",operation).apply { fields.forEach { put(it.first,it.second ?: JSONObject.NULL) } })
    @Suppress("UNCHECKED_CAST") private fun items(page: Map<String,Any?>) = page["items"] as List<Map<String,Any?>>
    private fun terminal(j: Journal): String = j.begin("synthetic",false).also { j.transition(it,"finish","completed",100) }
    private fun count(j: Journal, sql: String) = db(j).rawQuery(sql,null).use { it.moveToFirst(); it.getLong(0) }
    private fun settings(rangeId: Any?=null) = JSONObject("""{"units":"metric","timezone_id":"Europe/Warsaw","theme":"system","route_enabled":false,"hr_ranges_id":null,"alerts":{"enabled":false,"sound":false,"vibration":false,"debounce_ms":0,"hysteresis_bpm":0,"repeat_ms":null}}""").put("hr_ranges_id",rangeId ?: JSONObject.NULL)
    private fun ranges(label: String) = JSONObject("""{"ranges":[{"id":"user-range","label":"$label","lower_bpm_inclusive":70,"upper_bpm_exclusive":100}]}""")
    private fun params() = JSONObject("""{"hr_stale_after_ms":5000,"gps_max_accuracy_m":30,"gps_max_gap_ms":10000,"gps_max_speed_m_s":12,"map_hr_window_ms":5000}""")
    @Test fun keysetHistoryAndBoundedIndependentStreamsSurviveReopen() {
        val ctx=context(); val ids=mutableListOf<String>()
        Journal(ctx, { JournalStamp(1700000000000,1000000000,"test","UTC") }).use { j ->
            repeat(3) { ids.add(terminal(j)) }
            j.append(ids[0],"hr",JSONObject().put("bpm",81)); j.append(ids[0],"gps",JSONObject().put("latitude",52))
            j.append(ids[0],"hr",JSONObject().put("bpm",83))
        }
        Journal(ctx).use { j ->
            val first=op(j,"list","limit" to 2,"before" to null); val next=JSONObject(first["next"] as Map<*,*>)
            val second=op(j,"list","limit" to 2,"before" to next)
            assertEquals(ids.toSet(),(items(first)+items(second)).map { it["id"] }.toSet()); assertNull(second["next"])
            assertEquals("completed",items(first)[0]["state"]); assertNull(items(first)[0]["hr_coverage"])
            val hr=op(j,"stream","id" to ids[0],"stream" to "hr","after_sequence" to 0,"limit" to 1)
            val remaining=op(j,"stream","id" to ids[0],"stream" to "hr","after_sequence" to hr["next_sequence"],"limit" to 1)
            assertEquals(81,JSONObject(items(hr)[0]["payload_json"] as String).getInt("bpm")); assertEquals(83,JSONObject(items(remaining)[0]["payload_json"] as String).getInt("bpm"))
            assertNull(remaining["next_sequence"])
            assertEquals(1,items(op(j,"stream","id" to ids[0],"stream" to "gps","after_sequence" to 0,"limit" to 200)).size)
            val details=op(j,"details","id" to ids[1],"parameters" to params()); assertNotNull(details["summary"])
            val summary=details["summary"] as Map<*,*>; assertNull(summary["hr_coverage"]); assertNull(summary["average_hr_bpm"])
        }
    }
    @Test fun revisionsAreOwnedAndSnapshotsStayStableAfterRestart() {
        val ctx=context(); var old=""; var rangeId=""
        Journal(ctx).use { j ->
            assertNull(op(j,"preferences")["settings"])
            val saved=op(j,"saveRanges","expected_revision" to 0,"value" to ranges("original"))["hr_ranges"] as Map<*,*>
            rangeId=saved["id"] as String; assertEquals(j.localIdentity.localProfileId,saved["local_profile_id"])
            op(j,"saveSettings","expected_revision" to 0,"value" to settings(rangeId)); old=terminal(j)
            op(j,"saveRanges","expected_revision" to 1,"value" to ranges("updated"))
            val conflict=assertThrows(RecorderError::class.java) { op(j,"saveRanges","expected_revision" to 1,"value" to ranges("lost update")) }
            assertEquals("INVALID_STATE",conflict.code)
        }
        Journal(ctx).use { j ->
            val prefs=op(j,"preferences"); assertEquals(2L,((prefs["hr_ranges"] as Map<*,*>)["revision"] as Number).toLong())
            val saved=(op(j,"details","id" to old,"parameters" to params())["session"] as Map<*,*>)["hr_ranges_snapshot"] as Map<*,*>
            assertEquals(1L,(saved["revision"] as Number).toLong()); assertEquals(rangeId,saved["id"])
            val newer=terminal(j); val snapshot=(op(j,"details","id" to newer,"parameters" to params())["session"] as Map<*,*>)["hr_ranges_snapshot"] as Map<*,*>
            assertEquals(2L,(snapshot["revision"] as Number).toLong())
        }
    }
    @Test fun activeStatesRejectDeletionAndTerminalDeletionKeepsOtherSessions() {
        Journal(context()).use { j ->
            val active=j.begin("test",false); val other=terminal(j)
            for(state in listOf("recording","paused","finishing")) {
                db(j).execSQL("UPDATE sessions SET state=? WHERE id=?",arrayOf(state,active))
                assertEquals("INVALID_STATE",assertThrows(RecorderError::class.java) { op(j,"delete","id" to active) }.code)
            }
            j.recoverInterrupted(); val export=j.export(active); assertTrue(export.exists())
            assertEquals("complete",op(j,"delete","id" to active)["cleanup"]); assertFalse(export.exists())
            assertEquals(0,count(j,"SELECT count(*) FROM records WHERE session='$active'")); assertEquals(0,count(j,"SELECT count(*) FROM segments WHERE session='$active'"))
            assertEquals(other,items(op(j,"list","limit" to 10,"before" to null)).single()["id"])
            assertEquals(false,op(j,"delete","id" to active)["data_deleted"])
        }
    }
    @Test fun failedTransactionKeepsRowsAndExportThenRetrySucceeds() {
        Journal(context()).use { j ->
            val id=terminal(j); val file=j.export(id)
            db(j).execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT,'test failure'); END")
            assertThrows(Exception::class.java) { op(j,"delete","id" to id) }
            assertTrue(file.exists()); assertEquals(1,count(j,"SELECT count(*) FROM sessions")); assertEquals(2,count(j,"SELECT count(*) FROM records")); assertEquals(1,count(j,"SELECT count(*) FROM segments")); assertEquals(0,count(j,"SELECT count(*) FROM local_deletion_cleanup"))
            db(j).execSQL("DROP TRIGGER reject_delete"); assertEquals(true,op(j,"delete","id" to id)["data_deleted"])
        }
    }
    @Test fun failedManagedFileCleanupIsDiscoverableAfterRestartAndRetryable() {
        val ctx=context(); var id=""; lateinit var blocker: File
        Journal(ctx).use { j ->
            id=terminal(j); blocker=File(ctx.cacheDir,"exports/veymur-$id.zip").also { it.mkdirs() }; File(blocker,"blocker").writeText("not an expected export file")
            val result=op(j,"delete","id" to id); assertEquals(true,result["data_deleted"]); assertEquals("pending",result["cleanup"]); assertEquals("MANAGED_FILE_DELETE_FAILED",result["cleanup_error"])
            assertEquals(0,count(j,"SELECT count(*) FROM sessions"))
        }
        Journal(ctx).use { j ->
            assertEquals(listOf(id),op(j,"pendingDeletions","after_id" to null,"limit" to 10)["ids"])
            assertEquals("pending",op(j,"delete","id" to id)["cleanup"])
            blocker.deleteRecursively(); assertEquals("complete",op(j,"delete","id" to id)["cleanup"])
            assertEquals(emptyList<String>(),op(j,"pendingDeletions","after_id" to null,"limit" to 10)["ids"])
        }
    }
    @Test fun invalidRequestsDoNotChangePreferencesOrStreams() {
        Journal(context()).use { j ->
            val id=terminal(j)
            for(limit in listOf(0,101,1.5)) assertThrows(RecorderError::class.java) { op(j,"list","limit" to limit,"before" to null) }
            assertThrows(RecorderError::class.java) { op(j,"saveSettings","expected_revision" to 0,"value" to settings().put("local_profile_id","injected")) }
            assertThrows(RecorderError::class.java) { op(j,"saveSettings","expected_revision" to 0,"value" to settings().put("timezone_id","invalid/timezone")) }
            assertThrows(RecorderError::class.java) { op(j,"saveRanges","expected_revision" to 0,"value" to JSONObject("""{"ranges":[{"id":"a","label":"A","lower_bpm_inclusive":null,"upper_bpm_exclusive":100},{"id":"b","label":"B","lower_bpm_inclusive":90,"upper_bpm_exclusive":null}]}""")) }
            assertNull(op(j,"preferences")["settings"]); assertNull(op(j,"preferences")["hr_ranges"])
            assertEquals(2,items(op(j,"stream","id" to id,"stream" to "events","after_sequence" to 0,"limit" to 200)).size)
        }
    }
    @Test fun existingV2UpgradesWithoutChangingIdentityOrRows() {
        val ctx=context(); var id=""; var owner=""
        Journal(ctx).use { j -> id=terminal(j); owner=j.localIdentity.localProfileId; db(j).execSQL("DROP TABLE local_deletion_cleanup"); db(j).execSQL("PRAGMA user_version=2") }
        Journal(ctx).use { j -> assertEquals(owner,j.localIdentity.localProfileId); assertEquals(id,items(op(j,"list","limit" to 10,"before" to null)).single()["id"]); assertEquals(JOURNAL_DATABASE_VERSION.toLong(),count(j,"PRAGMA user_version")) }
    }
    @Test fun streamHasByteBudgetAndOversizedRecordFailsExplicitly() {
        Journal(context()).use { j ->
            val id=terminal(j); repeat(4) { j.append(id,"hr",JSONObject().put("extra","x".repeat(200000))) }
            val first=op(j,"stream","id" to id,"stream" to "hr","after_sequence" to 0,"limit" to 200)
            assertEquals(2,items(first).size); assertNotNull(first["next_sequence"])
            val second=op(j,"stream","id" to id,"stream" to "hr","after_sequence" to first["next_sequence"],"limit" to 200)
            assertEquals(2,items(second).size); assertNull(second["next_sequence"])
            j.append(id,"gps",JSONObject().put("extra","x".repeat(262145)))
            assertEquals("STORAGE_ERROR",assertThrows(RecorderError::class.java) { op(j,"stream","id" to id,"stream" to "gps","after_sequence" to 0,"limit" to 1) }.code)
        }
    }
    @Test fun failedV2MigrationRollsBackAndCanBeRetriedWithoutReset() {
        val ctx=context(); var id=""
        Journal(ctx).use { j ->
            id=terminal(j); db(j).execSQL("DROP TABLE local_deletion_cleanup")
            db(j).execSQL("CREATE TABLE local_deletion_cleanup (unexpected_extension TEXT)")
            db(j).execSQL("PRAGMA user_version=2")
        }
        assertThrows(Exception::class.java) { Journal(ctx).close() }
        val prefs=ctx.getSharedPreferences("wrapped-key",Context.MODE_PRIVATE)
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,keys.getKey("veymur-stage0-database",null),
            javax.crypto.spec.GCMParameterSpec(128,android.util.Base64.decode(prefs.getString("iv",null),android.util.Base64.NO_WRAP)))
        val key=cipher.doFinal(android.util.Base64.decode(prefs.getString("ciphertext",null),android.util.Base64.NO_WRAP))
        try {
            SQLiteDatabase.openDatabase(ctx.getDatabasePath("stage0.db").absolutePath,key,null,SQLiteDatabase.OPEN_READWRITE,null,null).use { database ->
                database.rawQuery("PRAGMA user_version",null).use { assertTrue(it.moveToFirst()); assertEquals(2,it.getInt(0)) }
                database.rawQuery("SELECT id FROM sessions",null).use { assertTrue(it.moveToFirst()); assertEquals(id,it.getString(0)) }
                database.execSQL("DROP TABLE local_deletion_cleanup")
            }
        } finally { key.fill(0) }
        Journal(ctx).use { j -> assertEquals(id,items(op(j,"list","limit" to 1,"before" to null)).single()["id"]) }
    }
    @Test fun cleanupDoesNotFollowManagedDirectorySymlink() {
        val ctx=context(); var id=""
        Journal(ctx).use { j ->
            id=terminal(j)
            val outside=File(ctx.cacheDir,"other-files").also { it.mkdirs() }
            val protected=File(outside,"veymur-$id.zip").also { it.writeText("keep") }
            android.system.Os.symlink(outside.absolutePath,File(ctx.cacheDir,"exports").absolutePath)
            protected.setLastModified(1)
            assertThrows(IllegalArgumentException::class.java) { j.export(id) }; assertTrue(protected.exists())
            assertEquals("pending",op(j,"delete","id" to id)["cleanup"]); assertTrue(protected.exists())
            File(ctx.cacheDir,"exports").delete()
            assertEquals("complete",op(j,"delete","id" to id)["cleanup"]); assertTrue(protected.exists())
        }
    }
}
