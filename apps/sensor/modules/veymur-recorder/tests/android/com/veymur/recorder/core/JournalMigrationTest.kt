package com.veymur.recorder.core

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veymur.recorder.core.localdata.*
import com.veymur.recorder.core.localdata.aggregates.*
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.zip.ZipFile

/** All fixture paths/preferences are isolated. Never opens or upgrades the user's stage0.db. */
@RunWith(AndroidJUnit4::class)
class JournalMigrationTest {
    private val host get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(val context: Context, val password: ByteArray) {
        fun raw(readonly: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(context.getDatabasePath("stage0.db").absolutePath,
            password, null, if (readonly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE, null)
    }
    private fun context(): Context {
        val prefix = "local-model-${UUID.randomUUID()}-"
        return object : ContextWrapper(host) {
            override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
            override fun getCacheDir() = File(baseContext.cacheDir, "exports/$prefix").also { it.mkdirs() }
        }
    }
    private fun legacy(): Fixture {
        val ctx = context(); System.loadLibrary("sqlcipher")
        // Independent v1 key wrapping and SQL fixture, without invoking the migration implementation.
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "veymur-stage0-database"
        if (!keys.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build()); generateKey()
        }
        val password = ByteArray(32) { (it + 17).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keys.getKey(alias, null) as SecretKey) }
        check(ctx.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(password), Base64.NO_WRAP)).commit())
        val file = ctx.getDatabasePath("stage0.db").also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, password, null, null, null).use { db ->
            val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("local-data/journal-v1.sql").bufferedReader().use { it.readText() }
            sql.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it.trim()) }
        }
        return Fixture(ctx, password)
    }
    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getLong(0) }
    private fun snapshot(db: SQLiteDatabase, table: String, columns: String, order: String): List<List<String?>> =
        db.rawQuery("SELECT $columns FROM $table ORDER BY $order", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    private fun db(journal: Journal) = Journal::class.java.getDeclaredField("db").apply { isAccessible = true }.get(journal) as SQLiteDatabase
    private val parameters = AggregateParameters(5000, 30.0, 5000, 20.0, 2000)

    @Test fun migratesV1WithoutChangingRawRowsTimesSequenceOrDeletedHighWater() {
        val f = legacy()
        val sessions: List<List<String?>>; val records: List<List<String?>>
        f.raw().use {
            assertEquals(1, scalar(it, "PRAGMA user_version"))
            sessions = snapshot(it, "sessions", "id,device,started,state,active_ms,gps", "id")
            records = snapshot(it, "records", "sequence,session,received_utc,elapsed_ms,kind,payload", "sequence")
        }
        val identity: JournalLocalIdentity; val assigned: List<List<String?>>
        Journal(f.context).use { journal ->
            val db = db(journal); identity = journal.localIdentity
            assertEquals(2, scalar(db, "PRAGMA user_version")); assertEquals(1, scalar(db, "PRAGMA foreign_keys"))
            assertEquals(sessions, snapshot(db, "sessions", "id,device,started,state,active_ms,gps", "id"))
            assertEquals(records, snapshot(db, "records", "sequence,session,received_utc,elapsed_ms,kind,payload", "sequence"))
            assigned = snapshot(db, "records", "sequence,id,local_profile_id", "sequence")
            assertEquals(1, scalar(db, "SELECT count(DISTINCT local_profile_id) FROM sessions"))
            assertEquals(0, scalar(db, "SELECT count(*) FROM sessions WHERE installation_id IS NOT NULL OR timezone_id IS NOT NULL OR hr_state IS NOT NULL"))
            assertEquals(0, scalar(db, "SELECT count(*) FROM records WHERE timebase_id IS NOT NULL OR receipt_elapsed_ns IS NOT NULL OR quality_flags IS NOT NULL"))
            assertEquals(1, scalar(db, "SELECT count(*) FROM local_profiles WHERE origin='adopted_v1'"))
            assertEquals(0, scalar(db, "SELECT count(*) FROM settings")); assertEquals(0, scalar(db, "SELECT count(*) FROM hr_ranges"))
            val unavailable = journal.recalculate("legacy-completed", parameters)
            assertNull(unavailable.aggregates); assertEquals(listOf("legacy_envelope_not_known"), unavailable.unavailable_reasons)
            val next = journal.begin("SYNTHETIC-NEW", false)
            assertTrue(scalar(db, "SELECT MIN(sequence) FROM records WHERE session='$next'") > 500)
            ZipFile(journal.export("legacy-completed")).use { zip ->
                val metadata = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
                assertEquals("legacy-completed", metadata.getString("session_id")); assertEquals(3000, metadata.getLong("active_ms"))
                assertTrue(metadata.isNull("installation_id"))
            }
        }
        Journal(f.context).use { journal ->
            assertEquals(identity, journal.localIdentity)
            assertEquals(assigned, snapshot(db(journal), "records", "sequence,id,local_profile_id", "sequence").take(assigned.size))
            assertEquals(1, scalar(db(journal), "SELECT count(*) FROM local_profiles"))
            assertEquals(1, scalar(db(journal), "SELECT count(*) FROM installations"))
        }
        assertFalse(f.context.getDatabasePath("stage0.db").readBytes().copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
    }

    @Test fun failedMigrationRollsBackSchemaVersionRowsAndCanRetryWithoutReset() {
        val f = legacy()
        f.raw().use { it.execSQL("UPDATE sessions SET state='invalid_v1_state' WHERE id='legacy-active'") }
        val before = f.raw().use { snapshot(it, "records", "sequence,session,received_utc,elapsed_ms,kind,payload", "sequence") }
        assertThrows(Exception::class.java) { Journal(f.context) }
        f.raw().use {
            assertEquals(1, scalar(it, "PRAGMA user_version"))
            assertEquals(0, scalar(it, "SELECT count(*) FROM sqlite_master WHERE name IN ('local_profiles','sessions_new','records_new','segments')"))
            assertEquals(500, scalar(it, "SELECT seq FROM sqlite_sequence WHERE name='records'"))
            assertEquals(before, snapshot(it, "records", "sequence,session,received_utc,elapsed_ms,kind,payload", "sequence"))
            it.execSQL("UPDATE sessions SET state='recording' WHERE id='legacy-active'")
        }
        Journal(f.context).use { assertEquals(before, snapshot(db(it), "records", "sequence,session,received_utc,elapsed_ms,kind,payload", "sequence")) }
    }

    @Test fun orphanV1RecordCausesRollbackRatherThanDroppingTheRecord() {
        val f = legacy()
        f.raw().use { it.execSQL("INSERT INTO records(session,received_utc,elapsed_ms,kind,payload) VALUES ('missing',1,1,'hr','{}')") }
        assertThrows(Exception::class.java) { Journal(f.context) }
        f.raw().use { assertEquals(1, scalar(it, "PRAGMA user_version")); assertEquals(1, scalar(it, "SELECT count(*) FROM records WHERE session='missing'")) }
    }

    @Test fun futureVersionIsRejectedWithoutChangingFileVersionOrKeyPreferences() {
        val f = legacy()
        f.raw().use { it.execSQL("PRAGMA user_version=99") }
        val before = f.context.getDatabasePath("stage0.db").readBytes()
        val preferences = f.context.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE).all.toMap()
        assertEquals(99, assertThrows(UnsupportedJournalVersion::class.java) { Journal(f.context) }.foundVersion)
        assertArrayEquals(before, f.context.getDatabasePath("stage0.db").readBytes())
        assertEquals(preferences, f.context.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE).all)
    }

    @Test fun missingWrappedKeyAndCorruptionPreserveExistingFiles() {
        val missing = legacy(); val before = missing.context.getDatabasePath("stage0.db").readBytes()
        missing.context.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE).edit().clear().commit()
        assertThrows(IllegalStateException::class.java) { Journal(missing.context) }
        assertArrayEquals(before, missing.context.getDatabasePath("stage0.db").readBytes())
        assertTrue(missing.context.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE).all.isEmpty())
        val corrupt = legacy(); val bytes = ByteArray(8192) { 7 }; corrupt.context.getDatabasePath("stage0.db").writeBytes(bytes)
        assertThrows(Exception::class.java) { Journal(corrupt.context) }
        assertArrayEquals(bytes, corrupt.context.getDatabasePath("stage0.db").readBytes())
    }

    @Test fun unknownVersionZeroSchemaIsNotInitializedOrOverwritten() {
        val f = legacy()
        f.raw().use { it.execSQL("PRAGMA user_version=0") }
        assertThrows(IllegalStateException::class.java) { Journal(f.context) }
        f.raw().use { assertEquals(0, scalar(it, "PRAGMA user_version")); assertEquals(9, scalar(it, "SELECT count(*) FROM records")) }
    }

    @Test fun migratedRecoveryKeepsOldRowsAndIsIdempotent() {
        val f = legacy()
        Journal(f.context).use { journal ->
            assertEquals(1, journal.recoverInterrupted()); assertEquals(0, journal.recoverInterrupted())
            assertEquals(1, scalar(db(journal), "SELECT count(*) FROM records WHERE session='legacy-active' AND kind='interrupted'"))
            assertEquals(9, scalar(db(journal), "SELECT count(*) FROM records WHERE storage_origin='legacy_v1'"))
            assertNull(journal.recalculate("legacy-active", parameters).aggregates)
        }
    }

    @Test fun nativeWritesAfterMigrationReopenAndReproduceWeightedAggregates() {
        val f = legacy(); var nowMs = 1000L
        fun stamp() = JournalStamp(1700000000000 + nowMs, nowMs * 1_000_000, "native-process", "Europe/Warsaw")
        val id: String; val expected: SessionAggregates
        Journal(f.context, ::stamp).use { journal ->
            id = journal.begin("SYNTHETIC", false)
            val snapshot = JSONObject().put("id", "user-ranges").put("schema_version", 1).put("revision", 7)
                .put("local_profile_id", journal.localIdentity.localProfileId).put("installation_id", journal.localIdentity.installationId)
                .put("ranges", org.json.JSONArray(listOf(
                    JSONObject().put("id", "low").put("label", "My low").put("lower_bpm_inclusive", 0).put("upper_bpm_exclusive", 90),
                    JSONObject().put("id", "middle").put("label", "My middle").put("lower_bpm_inclusive", 90).put("upper_bpm_exclusive", 110),
                    JSONObject().put("id", "high").put("label", "My high").put("lower_bpm_inclusive", 110).put("upper_bpm_exclusive", JSONObject.NULL))))
            val db = db(journal)
            db.execSQL("INSERT INTO hr_ranges VALUES (?,7,1,?,?,?)", arrayOf("user-ranges", journal.localIdentity.localProfileId, journal.localIdentity.installationId, snapshot.toString()))
            db.execSQL("UPDATE sessions SET hr_ranges_snapshot=? WHERE id=?", arrayOf(snapshot.toString(), id))
            val normalizer = ObservationNormalizer()
            fun hr(bpm: Int) {
                val time = stamp(); val sample = normalizer.hr(bpm, true, true, Receipt(time.utcMs, time.elapsedNs, time.timebaseId, time.timezoneId))
                journal.append(id, "hr", JSONObject().put("bpm", bpm).put("contact_supported", true).put("contact", true)
                    .put("observation_metadata", observationMetadata(sample.time, sample.quality)))
            }
            hr(80); nowMs = 2000; hr(100); nowMs = 4000
            journal.transition(id, "pause", "paused", 3000)
            nowMs = 10000; journal.transition(id, "resume", "recording", 3000)
            nowMs = 11000; hr(120); nowMs = 12000; journal.transition(id, "finish", "completed", 5000)
            val result = journal.recalculate(id, parameters)
            assertEquals(emptyList<String>(), result.unavailable_reasons); expected = result.aggregates!!
            assertEquals(5000.0, expected.active_duration_ms, 0.0)
            assertEquals(4000.0, expected.hr_covered_duration_ms, 0.0)
            assertEquals(100.0, expected.average_hr_bpm!!, 0.0)
            assertEquals(listOf(1000.0, 2000.0, 1000.0), expected.range_durations.map { it.duration_ms })
            snapshot.put("revision", 8).getJSONArray("ranges").getJSONObject(0).put("upper_bpm_exclusive", 85)
            db.execSQL("INSERT INTO hr_ranges VALUES (?,8,1,?,?,?)", arrayOf("user-ranges", journal.localIdentity.localProfileId, journal.localIdentity.installationId, snapshot.toString()))
            assertEquals(expected, journal.recalculate(id, parameters).aggregates)
        }
        Journal(f.context, ::stamp).use {
            assertEquals(expected, it.recalculate(id, parameters).aggregates)
            assertEquals(2, scalar(db(it), "SELECT count(*) FROM segments WHERE session='$id' AND closure='closed'"))
        }
    }

    @Test fun nativeRecoveryDoesNotExtendTimeIntoNewProcess() {
        val ctx = context(); var nowMs = 1000L; var process = "before"
        fun stamp() = JournalStamp(1700000000000 + nowMs, nowMs * 1_000_000, process, "Europe/Warsaw")
        val id: String
        Journal(ctx, ::stamp).use { journal ->
            id = journal.begin("SYNTHETIC", false)
            val sample = ObservationNormalizer().hr(80, true, true, Receipt(stamp().utcMs, stamp().elapsedNs, process, "Europe/Warsaw"))
            journal.append(id, "hr", JSONObject().put("bpm", 80).put("observation_metadata", observationMetadata(sample.time, sample.quality)))
            nowMs = 3000
            val next = ObservationNormalizer().hr(100, true, true, Receipt(stamp().utcMs, stamp().elapsedNs, process, "Europe/Warsaw"))
            journal.append(id, "hr", JSONObject().put("bpm", 100).put("observation_metadata", observationMetadata(next.time, next.quality)))
        }
        nowMs = 10; process = "after"
        Journal(ctx, ::stamp).use { journal ->
            assertEquals(1, journal.recoverInterrupted()); assertEquals(0, journal.recoverInterrupted())
            val result = journal.recalculate(id, parameters).aggregates!!
            assertEquals(2000.0, result.active_duration_ms, 0.0)
            assertEquals(80.0, result.average_hr_bpm!!, 0.0); assertTrue(result.interrupted_tail_unknown)
            assertEquals(1, scalar(db(journal), "SELECT count(*) FROM segments WHERE closure='interrupted_unknown' AND end_elapsed_ns IS NULL"))
        }
    }

    @Test fun writerOwnershipAndForeignKeysRejectInvalidMutations() {
        Journal(context()).use { journal ->
            var failure: Throwable? = null
            val other = Thread { try { journal.begin("SYNTHETIC", false) } catch (e: Throwable) { failure = e } }
            other.start(); other.join(); assertTrue(failure is IllegalStateException)
            val id = journal.begin("SYNTHETIC", false); val db = db(journal)
            db.execSQL("INSERT INTO local_profiles VALUES ('other-owner',1,1,?,'created')", arrayOf(journal.localIdentity.installationId))
            assertThrows(Exception::class.java) { db.execSQL("UPDATE sessions SET current_segment_id='missing' WHERE id=?", arrayOf(id)) }
            assertThrows(Exception::class.java) { db.execSQL("UPDATE records SET local_profile_id='other-owner' WHERE session=?", arrayOf(id)) }
            assertThrows(Exception::class.java) { db.execSQL("DELETE FROM local_profiles") }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
