// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import es.jjrh.bikeradar.BuildConfig

/**
 * Stores the Home Assistant base URL and long-lived bearer token at rest.
 *
 * Confidentiality is delegated to a [Cryptor]: [AndroidKeyStoreCryptor]
 * in production (AES-256/GCM with a hardware-bound key that cannot leave
 * the secure element); [es.jjrh.bikeradar.testutil.InMemoryCryptor] in
 * JVM tests via [cryptorFactory]. SharedPreferences only ever sees
 * ciphertext.
 *
 * Threat model: a passive `adb pull` of the prefs file recovers nothing
 * useful in production, because the AES key sits inside the Keystore and
 * cannot be exfiltrated. The key is not gated on user authentication,
 * so an attacker with code execution as the app user can still invoke
 * the Keystore to decrypt; lock-screen state is not in the trust path.
 */
class HaCredentials(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val cryptor: Cryptor = cryptorFactory()

    var baseUrl: String
        get() = cryptor.decrypt(sp.getString(KEY_BASE_URL, null))
        set(v) { sp.edit().putString(KEY_BASE_URL, cryptor.encrypt(v)).apply() }

    var token: String
        get() = cryptor.decrypt(sp.getString(KEY_TOKEN, null))
        set(v) { sp.edit().putString(KEY_TOKEN, cryptor.encrypt(v)).apply() }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    fun save(url: String, token: String) {
        sp.edit()
            .putString(KEY_BASE_URL, cryptor.encrypt(url))
            .putString(KEY_TOKEN, cryptor.encrypt(token))
            .apply()
    }

    fun clear() {
        sp.edit().remove(KEY_BASE_URL).remove(KEY_TOKEN).apply()
    }

    // On first launch, seed from BuildConfig if local.properties was
    // populated at build time and the store is still empty. End users
    // do not need local.properties; this only matters for development
    // builds with credentials baked in.
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
        private const val FILE = "ha_credentials_v2"
        private const val KEY_BASE_URL = "ha_base_url"
        private const val KEY_TOKEN = "ha_token"

        /**
         * Indirection for swapping cipher in JVM tests. Production never
         * mutates this. Tests assign an in-memory implementation in
         * `@Before` so [HaCredentials] can be exercised without
         * AndroidKeyStore (which Robolectric does not provide).
         */
        @VisibleForTesting
        var cryptorFactory: () -> Cryptor = { AndroidKeyStoreCryptor() }
    }
}
