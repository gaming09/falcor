package com.falcor.viewer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists Frigate connection credentials in EncryptedSharedPreferences.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback for emulators / devices where StrongBox/keystore fails in CI.
        context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
    }

    data class Credentials(
        val baseUrl: String,
        val username: String?,
        val password: String?,
        val token: String?
    ) {
        val isConfigured: Boolean get() = baseUrl.isNotBlank()
    }

    fun load(): Credentials? {
        val url = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        if (url.isEmpty()) return null
        return Credentials(
            baseUrl = url.trimEnd('/'),
            username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() },
            password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() },
            token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        )
    }

    fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, credentials.baseUrl.trimEnd('/'))
            .putString(KEY_USERNAME, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .putString(KEY_TOKEN, credentials.token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "falcor_secure_prefs"
        private const val PREFS_NAME_FALLBACK = "falcor_secure_prefs_fallback"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
    }
}
