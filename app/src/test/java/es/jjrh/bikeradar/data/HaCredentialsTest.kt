// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [HaCredentials]' backup-transferable storage contract: values
 * round-trip through app-private SharedPreferences (deliberately unencrypted
 * so an Android backup restores usable credentials on a new phone - see the
 * class KDoc), `isConfigured` tracks save/clear, and the migration from the
 * legacy Keystore-encrypted format converts an in-place upgrade's blobs
 * while leaving undecryptable ones (restored onto a device that never had
 * the key, or a transient Keystore failure) in place for retry.
 */
@RunWith(RobolectricTestRunner::class)
class HaCredentialsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun installInMemoryCryptor() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        // Each test gets a fresh prefs file via the Robolectric default
        // SharedPreferences impl, so no manual clear is needed.
        HaCredentials(app).clear()
    }

    @After
    fun restoreCryptorFactory() {
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    private fun rawPrefs() = app.applicationContext.getSharedPreferences(
        "ha_credentials_v2",
        Context.MODE_PRIVATE,
    )

    @Test
    fun isConfiguredFalseOnFreshInstall() {
        assertFalse(HaCredentials(app).isConfigured())
    }

    @Test
    fun saveRoundTripsAcrossInstances() {
        val creds = HaCredentials(app)
        creds.save("https://h.example", "tok-123")
        assertTrue(creds.isConfigured())
        // New instance simulates app restart.
        val reread = HaCredentials(app)
        assertEquals("https://h.example", reread.baseUrl)
        assertEquals("tok-123", reread.token)
    }

    @Test
    fun storedValuesAreBackupTransferable() {
        // The whole point of the storage format: what SharedPreferences
        // holds IS the value, so a platform backup restores working
        // credentials on a new phone. A regression that reintroduced a
        // device-bound cipher would restore undecryptable blobs.
        val creds = HaCredentials(app)
        creds.save("https://h.example", "tok-123")
        assertEquals("https://h.example", rawPrefs().getString("ha_base_url_v3", null))
        assertEquals("tok-123", rawPrefs().getString("ha_token_v3", null))
    }

    @Test
    fun clearMakesIsConfiguredFalse() {
        val creds = HaCredentials(app)
        creds.save("https://h.example", "tok-123")
        creds.clear()
        assertFalse(creds.isConfigured())
        assertEquals("", creds.baseUrl)
        assertEquals("", creds.token)
    }

    @Test
    fun individualPropertySettersRoundTrip() {
        // save() is the common path; the baseUrl/token property setters are a
        // separate entry point (Settings edits one field at a time).
        val creds = HaCredentials(app)
        creds.baseUrl = "https://lan.local:8123"
        creds.token = "tok-xyz"
        val reread = HaCredentials(app)
        assertEquals("https://lan.local:8123", reread.baseUrl)
        assertEquals("tok-xyz", reread.token)
        assertTrue(reread.isConfigured())
    }

    @Test
    fun changeListenerFiresOnSaveFromAnotherInstance() {
        // The service registers a listener and rebuilds its HaClient when
        // credentials change; Settings writes through a DIFFERENT
        // HaCredentials instance over the same prefs file, so the listener
        // must fire across instances. The listener reference is held by the
        // test for the registration's lifetime (SharedPreferences holds
        // listeners weakly).
        val serviceSide = HaCredentials(app)
        var fired = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> fired++ }
        serviceSide.registerOnChangeListener(listener)
        HaCredentials(app).save("https://new.example", "tok-new")
        assertTrue("listener must fire on save", fired > 0)
        val before = fired
        serviceSide.unregisterOnChangeListener(listener)
        HaCredentials(app).clear()
        assertEquals("listener must not fire after unregister", before, fired)
    }

    @Test
    fun seedFromBuildConfigIsNoOpWhenAlreadyConfigured() {
        val creds = HaCredentials(app)
        creds.save("https://existing.example", "existing-token")
        creds.seedFromBuildConfigIfEmpty()
        // The pre-existing values must not be overwritten regardless of
        // whether BuildConfig carries seed values.
        assertEquals("https://existing.example", creds.baseUrl)
        assertEquals("existing-token", creds.token)
    }

    // ── legacy-format migration ──────────────────────────────────────────

    @Test
    fun legacyCiphertextMigratesOnceOnTheSameDevice() {
        // An in-place upgrade: the legacy blobs decrypt with the device's
        // own cryptor and must be rewritten in the transferable format,
        // with the legacy keys removed.
        val cryptor = InMemoryCryptor()
        HaCredentials.cryptorFactory = { cryptor }
        rawPrefs().edit()
            .putString("ha_base_url", cryptor.encrypt("https://legacy.example"))
            .putString("ha_token", cryptor.encrypt("tok-legacy"))
            .apply()

        val creds = HaCredentials(app)

        assertEquals("https://legacy.example", creds.baseUrl)
        assertEquals("tok-legacy", creds.token)
        assertTrue(creds.isConfigured())
        assertNull("legacy blob must be removed", rawPrefs().getString("ha_base_url", null))
        assertNull("legacy blob must be removed", rawPrefs().getString("ha_token", null))
        assertEquals(
            "migrated value must be stored transferably",
            "https://legacy.example",
            rawPrefs().getString("ha_base_url_v3", null),
        )
    }

    @Test
    fun undecryptableLegacyBlobsAreKeptForRetryAndAppStaysUnconfigured() {
        // Nothing decrypts: either the restored-new-phone case (the blobs
        // arrived via backup without their device-bound key) or a transient
        // Keystore failure at this launch. The blobs must be KEPT - deleting
        // possibly-good ciphertext on a transient failure would silently
        // cost the rider their HA setup - and the app runs unconfigured.
        rawPrefs().edit()
            .putString("ha_base_url", "not-decryptable-on-this-device")
            .putString("ha_token", "also-not-decryptable")
            .apply()

        val creds = HaCredentials(app)

        assertFalse(creds.isConfigured())
        assertEquals("", creds.baseUrl)
        assertEquals(
            "undecryptable legacy blobs must be kept for retry",
            "not-decryptable-on-this-device",
            rawPrefs().getString("ha_base_url", null),
        )
    }

    @Test
    fun transientDecryptFailureRecoversOnALaterConstruction() {
        // First launch after the upgrade hits a Keystore hiccup (decrypt
        // yields nothing); the next construction decrypts fine and the
        // migration completes with nothing lost.
        val working = InMemoryCryptor()
        rawPrefs().edit()
            .putString("ha_base_url", working.encrypt("https://recovered.example"))
            .putString("ha_token", working.encrypt("tok-recovered"))
            .apply()

        HaCredentials.cryptorFactory = {
            object : Cryptor {
                override fun encrypt(plain: String) = plain
                override fun decrypt(blob: String?) = "" // hiccup: nothing decrypts
            }
        }
        assertFalse(HaCredentials(app).isConfigured())

        HaCredentials.cryptorFactory = { working }
        val creds = HaCredentials(app)

        assertEquals("https://recovered.example", creds.baseUrl)
        assertEquals("tok-recovered", creds.token)
        assertNull("migrated blobs must be removed", rawPrefs().getString("ha_base_url", null))
    }

    @Test
    fun migrationNeverOverwritesExistingTransferableValues() {
        // A restore can carry BOTH formats (v3 written on the old phone
        // after its own migration, plus a stale legacy blob from an even
        // older backup layer). The v3 pair is authoritative.
        val cryptor = InMemoryCryptor()
        HaCredentials.cryptorFactory = { cryptor }
        rawPrefs().edit()
            .putString("ha_base_url_v3", "https://current.example")
            .putString("ha_token_v3", "tok-current")
            .putString("ha_base_url", cryptor.encrypt("https://stale.example"))
            .putString("ha_token", cryptor.encrypt("tok-stale"))
            .apply()

        val creds = HaCredentials(app)

        assertEquals("https://current.example", creds.baseUrl)
        assertEquals("tok-current", creds.token)
        assertNull(rawPrefs().getString("ha_base_url", null))
    }
}
