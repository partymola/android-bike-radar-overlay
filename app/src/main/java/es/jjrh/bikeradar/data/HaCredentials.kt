// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import es.jjrh.bikeradar.BuildConfig
import es.jjrh.bikeradar.HaHealthBus

/**
 * Stores the Home Assistant base URL and long-lived bearer token at rest.
 *
 * Values live in the app's private SharedPreferences, readable by no other
 * app, and are deliberately included in Android's device backup and
 * phone-to-phone transfer so the rider's setup moves to a new phone without
 * re-entering the token. Backups are encrypted by the platform with a key
 * derived from the lockscreen secret, so the cloud copy is not readable by
 * the backup provider.
 *
 * Earlier versions encrypted these values with a device-bound Android
 * Keystore key. That key can never leave the device, which made every
 * backup of the ciphertext useless on a new phone - the transferability
 * the backup exists for. The Keystore layer was therefore dropped in
 * favour of the platform's backup encryption; [migrateLegacyCiphertext]
 * converts an in-place upgrade's ciphertext, while ciphertext restored
 * onto a NEW device (whose Keystore never had the key) never decrypts and
 * the user simply re-enters credentials, exactly as before.
 *
 * Threat model: the app sandbox is the at-rest boundary - an attacker with
 * code execution as the app user could always decrypt (the old Keystore key
 * was not auth-gated), so the practical protection is unchanged; what moved
 * is that a backup now carries usable credentials, by explicit choice, under
 * the platform's end-to-end backup encryption.
 */
class HaCredentials(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        migrateLegacyCiphertext()
    }

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL_V3, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_BASE_URL_V3, v).apply()
        }

    var token: String
        get() = sp.getString(KEY_TOKEN_V3, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_TOKEN_V3, v).apply()
        }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    /**
     * Store the rider's credentials, and forget what the old ones proved.
     *
     * The reset lives HERE, not at the four call sites, because a caller that
     * forgets it leaves the status surfaces asserting an outcome the current
     * credentials never earned: fix a mistyped host and the row keeps reading
     * UNREACHABLE, break a working one and it keeps reading READY, in both
     * cases until something happens to publish.
     *
     * Only on an actual change. Re-saving the same values - which the
     * Settings screen does whenever the rider presses save without editing -
     * is not new information about them, and wiping a good verdict for it
     * would put the row back to "nothing observed yet" for no reason.
     */
    fun save(url: String, token: String) {
        val changed = url != baseUrl || token != this.token
        sp.edit()
            .putString(KEY_BASE_URL_V3, url)
            .putString(KEY_TOKEN_V3, token)
            .apply()
        if (changed) HaHealthBus.reset()
    }

    /**
     * Remove the rider's stored credentials, both formats.
     *
     * The legacy blobs go too, and that is not tidiness. Ciphertext this
     * device cannot currently read is still the rider's credentials in
     * storage, and the Privacy screen promises they are gone. Worse, leaving
     * them is not inert: [migrateLegacyCiphertext] writes v3 whenever v3 is
     * blank, and a clear is precisely what blanks it, so a blob that
     * outlived a clear would be migrated back in on a later construction and
     * quietly resume publishing to a Home Assistant the rider disconnected.
     */
    fun clear() {
        HaHealthBus.reset()
        sp.edit()
            .remove(KEY_BASE_URL_V3)
            .remove(KEY_TOKEN_V3)
            .remove(LEGACY_KEY_BASE_URL)
            .remove(LEGACY_KEY_TOKEN)
            .apply()
    }

    /** Notify [listener] when the stored credentials change (any writer:
     *  Settings, onboarding, clear). Lets the service rebuild its
     *  long-lived HaClient mid-session instead of publishing with stale
     *  credentials until restart. SharedPreferences holds listeners
     *  weakly - the caller must keep a strong reference and pair this
     *  with [unregisterOnChangeListener]. */
    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Seed from BuildConfig on a development build whose local.properties
     * carried credentials. End users never need local.properties.
     *
     * Debug-gated, and that gate is load-bearing rather than tidiness: this
     * writes whenever the store is empty, and [clear] is exactly what empties
     * it. Ungated, a rider who cleared their credentials would have them
     * restored on the next launch and publishing would resume - the same
     * shape as the legacy-blob path [clear] already had to close. The release
     * BuildConfig fields are empty today, so the blank guard below would also
     * stop it, but that is a gradle default rather than a rule; this gate is
     * the rule. The no-op-when-configured half is pinned by
     * [HaCredentialsTest]; this gate itself is not, since the suite runs only
     * the debug variant.
     */
    fun seedFromBuildConfigIfEmpty() {
        if (!BuildConfig.DEBUG) return
        if (!isConfigured()) {
            val url = BuildConfig.HA_BASE_URL
            val tok = BuildConfig.HA_TOKEN
            if (url.isNotBlank() && tok.isNotBlank()) {
                save(url, tok)
            }
        }
    }

    /**
     * Upgrade of the pre-backup storage format. Legacy installs hold
     * Keystore-encrypted blobs under the old keys: decrypt them with the
     * device's own key and rewrite as backup-transferable values.
     *
     * The legacy blobs are removed only once SOMETHING decrypted. When
     * nothing does, they are kept and the next construction retries: a
     * transient Keystore failure at the first post-upgrade launch must not
     * permanently destroy still-decryptable credentials, and the retry is
     * nearly free. Blobs restored onto a NEW phone (whose Keystore never
     * had the key) therefore just idle here undecryptable - the app runs
     * unconfigured and the user re-enters credentials, exactly as they
     * always had to before this change. Existing v3 values are never
     * overwritten (a restore that carries both formats keeps the
     * authoritative v3 pair).
     */
    private fun migrateLegacyCiphertext() {
        val legacyUrl = sp.getString(LEGACY_KEY_BASE_URL, null)
        val legacyToken = sp.getString(LEGACY_KEY_TOKEN, null)
        if (legacyUrl == null && legacyToken == null) return
        val cryptor = cryptorFactory()
        val url = cryptor.decrypt(legacyUrl)
        val tok = cryptor.decrypt(legacyToken)
        if (url.isBlank() && tok.isBlank()) return
        val e = sp.edit().remove(LEGACY_KEY_BASE_URL).remove(LEGACY_KEY_TOKEN)
        if (url.isNotBlank() && sp.getString(KEY_BASE_URL_V3, null).isNullOrBlank()) {
            e.putString(KEY_BASE_URL_V3, url)
        }
        if (tok.isNotBlank() && sp.getString(KEY_TOKEN_V3, null).isNullOrBlank()) {
            e.putString(KEY_TOKEN_V3, tok)
        }
        e.apply()
    }

    companion object {
        private const val FILE = "ha_credentials_v2"
        private const val KEY_BASE_URL_V3 = "ha_base_url_v3"
        private const val KEY_TOKEN_V3 = "ha_token_v3"

        // Pre-backup format: Keystore-encrypted blobs. Read once by the
        // migration, never written.
        private const val LEGACY_KEY_BASE_URL = "ha_base_url"
        private const val LEGACY_KEY_TOKEN = "ha_token"

        /**
         * Indirection for the LEGACY-migration cipher in JVM tests.
         * Production never mutates this. Tests assign an in-memory
         * implementation in `@Before` so the migration path can be
         * exercised without AndroidKeyStore (which Robolectric does not
         * provide).
         */
        @VisibleForTesting
        var cryptorFactory: () -> Cryptor = { AndroidKeyStoreCryptor() }
    }
}
