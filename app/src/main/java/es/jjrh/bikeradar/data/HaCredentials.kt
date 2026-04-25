// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("DEPRECATION")

package es.jjrh.bikeradar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import es.jjrh.bikeradar.BuildConfig

class HaCredentials(context: Context) {

    private val sp: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, "") ?: ""
        set(v) { sp.edit().putString(KEY_BASE_URL, v).apply() }

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) { sp.edit().putString(KEY_TOKEN, v).apply() }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    fun save(url: String, token: String) {
        sp.edit()
            .putString(KEY_BASE_URL, url)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun clear() {
        sp.edit().remove(KEY_BASE_URL).remove(KEY_TOKEN).apply()
    }

    // On first launch, seed from BuildConfig if local.properties was populated
    // and the store is still empty. This keeps JJ's iteration loop fast; end
    // users never touch local.properties.
    fun seedFromBuildConfigIfEmpty() {
        if (!isConfigured()) {
            val url = BuildConfig.HA_BASE_URL
            val tok = BuildConfig.HA_TOKEN
            if (url.isNotBlank() && tok.isNotBlank()) {
                save(url, tok)
            }
        }
    }

    companion object {
        private const val FILE = "ha_credentials"
        private const val KEY_BASE_URL = "ha_base_url"
        private const val KEY_TOKEN = "ha_token"
    }
}
