package com.bobcar.vehicle.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The refresh token, encrypted with an AES key that never leaves the Android Keystore (CLAUDE.md rule 6-1).
 * Only the encrypted bytes are in app storage (and app data is not backed up: allowBackup=false).
 * A token that can no longer be decrypted is dropped; the app then signs up again.
 */
class KeystoreTokenStore(context: Context) : RefreshTokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "stored token unreadable, dropped (${e.javaClass.simpleName})")
            clear()
            null
        }
    }

    override fun save(token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_DATA, Base64.encodeToString(data, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            // Not stored: the next app run signs up again
            Log.w(TAG, "token not stored (${e.javaClass.simpleName})")
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_DATA).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "BOBCar.Auth"
        const val PREFS = "bobcar_auth"
        const val KEY_IV = "refresh_iv"
        const val KEY_DATA = "refresh_data"
        const val ALIAS = "bobcar_refresh_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
