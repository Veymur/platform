package com.veymur.recorder.core

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from recorder keys. Never recreate a missing key over an existing diagnostic database. */
internal object DiagnosticDatabaseKey {
    @SuppressLint("UseKtx") // Check the synchronous commit result before trusting the wrapped key.
    fun password(context: Context, existingDatabase: Boolean): ByteArray {
        val preferences = context.getSharedPreferences("polar-diagnostic-wrapped-key", Context.MODE_PRIVATE)
        val wrapped = preferences.getString("ciphertext", null)
        val iv = preferences.getString("iv", null)
        check((wrapped == null) == (iv == null)) { "Incomplete diagnostic key" }
        check(wrapped != null || !existingDatabase) { "Missing diagnostic key" }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "veymur-polar-diagnostic-database"
        if (!keys.containsAlias(alias)) {
            check(wrapped == null) { "Missing diagnostic wrapping key" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build())
                generateKey()
            }
        }
        val key = keys.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (wrapped != null) {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(password), Base64.NO_WRAP)).commit())
        return password
    }
}
