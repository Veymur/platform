package com.veymur.recorder.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Called exclusively on the recorder worker. Every append is a committed transaction. */
class Journal(private val context: Context) {
    private val db: SQLiteDatabase
    init {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath("stage0.db").also { it.parentFile!!.mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(file, databasePassword(), null, null, null)
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, device TEXT NOT NULL, started INTEGER NOT NULL, state TEXT NOT NULL, active_ms INTEGER NOT NULL DEFAULT 0, gps INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS records (sequence INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, received_utc INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("PRAGMA user_version=1")
    }
    private fun databasePassword(): ByteArray {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "veymur-stage0-database"
        if (!keys.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build())
                generateKey()
            }
        }
        val key = keys.getKey(alias, null) as SecretKey
        val preferences = context.getSharedPreferences("wrapped-key", Context.MODE_PRIVATE)
        val wrapped = preferences.getString("ciphertext", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (wrapped != null) {
            val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(password), Base64.NO_WRAP)).commit())
        return password
    }
    fun recoverInterrupted(): Int {
        val ids = mutableListOf<String>()
        db.rawQuery("SELECT id FROM sessions WHERE state IN ('recording','paused')", null).use {
            while (it.moveToNext()) ids.add(it.getString(0))
        }
        ids.forEach { id ->
            append(id, "interrupted", JSONObject().put("reason", "process_ended"))
            db.execSQL("UPDATE sessions SET state='interrupted' WHERE id=?", arrayOf(id))
        }
        return ids.size
    }
    fun begin(device: String, gps: Boolean): String {
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("device", device); put("started", System.currentTimeMillis())
            put("state", "recording"); put("gps", if (gps) 1 else 0)
        })
        append(id, "start", JSONObject())
        return id
    }
    fun append(id: String, kind: String, payload: JSONObject) {
        db.beginTransaction()
        try {
            db.insertOrThrow("records", null, ContentValues().apply {
                put("session", id); put("received_utc", System.currentTimeMillis())
                put("elapsed_ms", SystemClock.elapsedRealtime()); put("kind", kind); put("payload", payload.toString())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun updateState(id: String, state: String, activeMs: Long) {
        db.execSQL("UPDATE sessions SET state=?, active_ms=? WHERE id=?", arrayOf<Any>(state, activeMs, id))
    }
    fun latest(): String? = db.rawQuery("SELECT id FROM sessions ORDER BY started DESC LIMIT 1", null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun export(id: String): File {
        val metadata = JSONObject().put("schema_version", 1).put("app_version", "0.0.1")
            .put("sdk_version", "8.4.0").put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("exported_at_utc_ms", System.currentTimeMillis())
            .put("hr_timestamp_source", "phone_receipt_time_not_sensor_measurement_time")
        db.rawQuery("SELECT device, started, state, active_ms, gps FROM sessions WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            metadata.put("session_id", id).put("device_id", it.getString(0)).put("started_utc_ms", it.getLong(1))
                .put("state", it.getString(2)).put("active_ms", it.getLong(3)).put("gps_requested", it.getInt(4) == 1)
        }
        val directory = File(context.cacheDir, "exports").also { it.mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
        val output = File(directory, "veymur-$id.zip")
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
    fun close() = db.close()
}
