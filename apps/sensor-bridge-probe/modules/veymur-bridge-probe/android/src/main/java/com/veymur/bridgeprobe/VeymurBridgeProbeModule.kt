package com.veymur.bridgeprobe

import android.os.Build
import com.polar.sdk.api.PolarBleApiDefaultImpl
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

class VeymurBridgeProbeModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("VeymurBridgeProbe")
        AsyncFunction("probe") {
            val context = requireNotNull(appContext.reactContext) { "Android context unavailable" }
            System.loadLibrary("sqlcipher")
            val file = File.createTempFile("bridge-probe-", ".db", context.cacheDir)
            val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                val cipherVersion = SQLiteDatabase.openOrCreateDatabase(file, password, null, null, null).use { db ->
                    db.execSQL("CREATE TABLE probe (value INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO probe VALUES (42)")
                    db.rawQuery("SELECT value FROM probe", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 42)
                    }
                    db.rawQuery("PRAGMA cipher_version", null).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0)
                    }
                }
                val header = file.inputStream().use { it.readNBytes(16) }
                check(!String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))
                mapOf(
                    "module" to "VeymurBridgeProbe",
                    "polarSdkVersion" to PolarBleApiDefaultImpl.versionInfo(),
                    "sqlCipherVersion" to cipherVersion,
                    "encryptedRoundTrip" to true,
                    "abi" to Build.SUPPORTED_ABIS.first(),
                    "androidApi" to Build.VERSION.SDK_INT,
                )
            } finally {
                password.fill(0)
                SQLiteDatabase.deleteDatabase(file)
            }
        }
    }
}
