package com.jarvis.assistant.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.core.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place an API key is ever stored.
 *
 * Backed by a keystore-bound AES256-GCM master key, so the file is useless if it
 * is pulled off the device. Keys are never logged, never put in a data class
 * that the UI holds, and never leave except in the Authorization header of the
 * provider the user picked.
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences by lazy { createPrefs() }

    fun getApiKey(provider: ProviderId): String? =
        prefs.getString(provider.storageKey, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: ProviderId, key: String) {
        val trimmed = key.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(provider.storageKey) else putString(provider.storageKey, trimmed)
        }.apply()
    }

    fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.storageKey).apply()
    }

    fun hasApiKey(provider: ProviderId): Boolean =
        !provider.requiresApiKey || getApiKey(provider) != null

    /**
     * Shows the user their key is present without putting the secret on screen.
     */
    fun maskedApiKey(provider: ProviderId): String? {
        val key = getApiKey(provider) ?: return null
        if (key.length <= 8) return "•".repeat(key.length)
        return key.take(4) + "•".repeat(key.length - 8) + key.takeLast(4)
    }

    private fun createPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            build(masterKey)
        } catch (e: Exception) {
            // A keystore entry can be invalidated by a factory reset, a restore,
            // or a lock-screen change. The stored blob is unrecoverable at that
            // point, so drop it and start clean rather than crashing on launch.
            Log.w(TAG, "Encrypted preferences unreadable, recreating", e)
            context.deleteSharedPreferences(FILE_NAME)
            build(masterKey)
        }
    }

    private fun build(masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val FILE_NAME = "jarvis_secure_keys"
        const val TAG = "SecureKeyStore"
    }
}
