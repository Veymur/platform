package com.veymur.recorder.core

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.UUID

const val JOURNAL_DATABASE_VERSION = 2
class UnsupportedJournalVersion(val foundVersion: Int) : IllegalStateException("Unsupported recorder database version: $foundVersion")

/** Only schema/data changes live here. The caller opens supported stores and owns the connection. */
internal object JournalSchema {
    fun version(db: SQLiteDatabase): Int = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
    fun checkVersion(db: SQLiteDatabase) {
        val version = version(db)
        if (version !in 0..JOURNAL_DATABASE_VERSION) throw UnsupportedJournalVersion(version)
        if (version == 0) db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use {
            check(!it.moveToFirst()) { "Unversioned nonempty recorder database; refusing to initialize" }
        }
    }
    fun migrate(db: SQLiteDatabase, nowUtcMs: Long) {
        checkVersion(db)
        db.execSQL("PRAGMA temp_store=MEMORY")
        db.execSQL("PRAGMA foreign_keys=ON")
        db.rawQuery("PRAGMA foreign_keys", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
        db.execSQL("PRAGMA synchronous=FULL")
        db.beginTransaction()
        try {
            checkVersion(db) // Recheck under the writer transaction.
            while (version(db) < JOURNAL_DATABASE_VERSION) {
                when (version(db)) {
                    0 -> {
                        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, device TEXT NOT NULL, started INTEGER NOT NULL, state TEXT NOT NULL, active_ms INTEGER NOT NULL DEFAULT 0, gps INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE records (sequence INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, received_utc INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL)")
                        db.execSQL("PRAGMA user_version=1")
                    }
                    1 -> { migrateV1(db, nowUtcMs); db.execSQL("PRAGMA user_version=2") }
                    else -> error("No migration path")
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Recorder foreign key violation" } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun migrateV1(db: SQLiteDatabase, now: Long) {
        // Refuse unknown extensions rather than silently dropping a user-created trigger/index.
        db.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name IN ('sessions','records') AND type IN ('index','trigger') AND sql IS NOT NULL", null).use {
            check(!it.moveToFirst()) { "Unsupported v1 recorder schema extension" }
        }
        val highWater = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='records'", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val hasHistory = db.rawQuery("SELECT EXISTS(SELECT 1 FROM sessions)", null).use { it.moveToFirst(); it.getInt(0) != 0 }
        db.execSQL("CREATE TABLE local_profiles (id TEXT PRIMARY KEY NOT NULL, schema_version INTEGER NOT NULL CHECK(schema_version=1), created_utc INTEGER NOT NULL, installation_id TEXT NOT NULL REFERENCES installations(id), origin TEXT NOT NULL CHECK(origin IN ('created','adopted_v1')))")
        db.execSQL("CREATE TABLE installations (id TEXT PRIMARY KEY NOT NULL, created_utc INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE local_store_identity (singleton INTEGER PRIMARY KEY CHECK(singleton=1), local_profile_id TEXT NOT NULL REFERENCES local_profiles(id), installation_id TEXT NOT NULL REFERENCES installations(id))")
        val profile = UUID.randomUUID().toString(); val installation = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO installations VALUES (?,?)", arrayOf<Any>(installation, now))
        db.execSQL("INSERT INTO local_profiles VALUES (?,1,?,?,?)", arrayOf<Any>(profile, now, installation, if (hasHistory) "adopted_v1" else "created"))
        db.execSQL("INSERT INTO local_store_identity VALUES (1,?,?)", arrayOf(profile, installation))
        for (table in listOf("settings", "hr_ranges")) {
            db.execSQL("""CREATE TABLE $table (
                id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0), schema_version INTEGER NOT NULL CHECK(schema_version=1),
                local_profile_id TEXT NOT NULL REFERENCES local_profiles(id), installation_id TEXT NOT NULL REFERENCES installations(id),
                payload TEXT NOT NULL, PRIMARY KEY(id,revision), UNIQUE(id,revision,local_profile_id))""")
        }
        db.execSQL("""CREATE TABLE sessions_new (
            id TEXT PRIMARY KEY NOT NULL, device TEXT NOT NULL, started INTEGER NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('preparation','recording','paused','finishing','completed','interrupted')),
            active_ms INTEGER NOT NULL DEFAULT 0 CHECK(active_ms>=0), gps INTEGER NOT NULL CHECK(gps IN (0,1)),
            schema_version INTEGER NOT NULL CHECK(schema_version=1), local_profile_id TEXT NOT NULL REFERENCES local_profiles(id),
            installation_id TEXT REFERENCES installations(id), timezone_id TEXT, ended_utc INTEGER,
            hr_state TEXT CHECK(hr_state IS NULL OR hr_state IN ('inactive','waiting','fresh','stale','lost')),
            gps_state TEXT CHECK(gps_state IS NULL OR gps_state IN ('disabled','inactive','waiting','available','degraded','lost','permission_denied')),
            current_segment_id TEXT, hr_ranges_snapshot TEXT, route_requested INTEGER CHECK(route_requested IS NULL OR route_requested IN (0,1)), storage_origin TEXT NOT NULL CHECK(storage_origin IN ('legacy_v1','native_v2')),
            UNIQUE(id,local_profile_id))""")
        db.execSQL("""INSERT INTO sessions_new (id,device,started,state,active_ms,gps,schema_version,local_profile_id,storage_origin)
            SELECT id,device,started,state,active_ms,gps,1,?,'legacy_v1' FROM sessions""", arrayOf(profile))
        db.execSQL("""CREATE TABLE records_new (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, received_utc INTEGER NOT NULL,
            elapsed_ms INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL,
            id TEXT NOT NULL UNIQUE, schema_version INTEGER NOT NULL CHECK(schema_version=1),
            local_profile_id TEXT NOT NULL, installation_id TEXT REFERENCES installations(id), segment_id TEXT,
            source_type TEXT CHECK(source_type IS NULL OR source_type IN ('recorder','polar_hr','phone_gps','user')), source_id TEXT,
            timebase_id TEXT, receipt_elapsed_ns TEXT, observed_at_utc TEXT, recorded_at_utc TEXT, timezone_id TEXT,
            source_timestamp TEXT, source_elapsed_ns TEXT, observed_monotonic_ms INTEGER,
            time_origin TEXT CHECK(time_origin IS NULL OR time_origin IN ('measurement','receipt','user_action','recovery')),
            normalization_version TEXT, quality_flags TEXT, quality_reasons TEXT,
            storage_origin TEXT NOT NULL CHECK(storage_origin IN ('legacy_v1','native_v2')),
            FOREIGN KEY(session,local_profile_id) REFERENCES sessions_new(id,local_profile_id),
            FOREIGN KEY(segment_id,session) REFERENCES segments(id,session) DEFERRABLE INITIALLY DEFERRED, UNIQUE(id,session))""")
        db.execSQL("CREATE TABLE segments (id TEXT PRIMARY KEY NOT NULL, session TEXT NOT NULL REFERENCES sessions_new(id), timebase_id TEXT NOT NULL, start_elapsed_ns TEXT NOT NULL, end_elapsed_ns TEXT, start_record_id TEXT NOT NULL, end_record_id TEXT, closure TEXT NOT NULL CHECK(closure IN ('open','closed','interrupted_unknown')), UNIQUE(id,session), FOREIGN KEY(start_record_id,session) REFERENCES records_new(id,session) DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(end_record_id,session) REFERENCES records_new(id,session) DEFERRABLE INITIALLY DEFERRED)")
        db.rawQuery("SELECT sequence,session,received_utc,elapsed_ms,kind,payload FROM records ORDER BY sequence", null).use {
            while (it.moveToNext()) {
                val sequence = it.getLong(0); val session = it.getString(1)
                db.insertOrThrow("records_new", null, ContentValues().apply {
                    put("sequence", sequence); put("session", session); put("received_utc", it.getLong(2)); put("elapsed_ms", it.getLong(3))
                    put("kind", it.getString(4)); put("payload", it.getString(5))
                    put("id", UUID.nameUUIDFromBytes("veymur-v1-record\u0000$session\u0000$sequence".toByteArray(Charsets.UTF_8)).toString())
                    put("schema_version", 1); put("local_profile_id", profile); put("storage_origin", "legacy_v1")
                })
            }
        }
        db.execSQL("DROP TABLE records"); db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions"); db.execSQL("ALTER TABLE records_new RENAME TO records")
        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='records'", arrayOf(highWater))
        // Empty tables may have no sqlite_sequence row; retain a deleted v1 high-water mark too.
        db.execSQL("INSERT INTO sqlite_sequence(name,seq) SELECT 'records',? WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='records')", arrayOf(highWater))

        db.execSQL("CREATE INDEX sessions_owner_started ON sessions(local_profile_id,started DESC,id)")
        db.execSQL("CREATE INDEX records_session_kind_sequence ON records(session,kind,sequence)")
        db.execSQL("CREATE INDEX records_session_sequence ON records(session,sequence)")
        db.execSQL("CREATE INDEX records_segment_time ON records(segment_id,timebase_id,elapsed_ms)")
        db.execSQL("CREATE INDEX segments_session ON segments(session,start_record_id)")
        // Immediate guards complement deferred cyclic foreign keys.
        db.execSQL("""CREATE TRIGGER records_segment_owner BEFORE INSERT ON records WHEN NEW.segment_id IS NOT NULL
            BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM segments WHERE id=NEW.segment_id AND session=NEW.session)
            THEN RAISE(ABORT,'record segment/session mismatch') END; END""")
        db.execSQL("""CREATE TRIGGER sessions_segment_insert BEFORE INSERT ON sessions WHEN NEW.current_segment_id IS NOT NULL
            BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM segments WHERE id=NEW.current_segment_id AND session=NEW.id)
            THEN RAISE(ABORT,'session segment mismatch') END; END""")
        db.execSQL("""CREATE TRIGGER sessions_segment_owner BEFORE UPDATE OF current_segment_id ON sessions WHEN NEW.current_segment_id IS NOT NULL
            BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM segments WHERE id=NEW.current_segment_id AND session=NEW.id)
            THEN RAISE(ABORT,'session segment mismatch') END; END""")
    }
}
