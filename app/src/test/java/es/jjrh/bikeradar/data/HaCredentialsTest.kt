// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [HaCredentials] honours the [Cryptor] contract: stored values
 * are not the plaintext, round-trip restores plaintext, and `isConfigured`
 * tracks save/clear correctly. The seam is what makes the rest of the
 * Activity / Service smoke tests possible - a regression here breaks
 * everything downstream.
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

    @Test
    fun isConfiguredFalseOnFreshInstall() {
        assertFalse(HaCredentials(app).isConfigured())
    }

    @Test
    fun saveRoundTripsThroughCryptor() {
        val creds = HaCredentials(app)
        creds.save("https://h.example", "tok-123")
        assertTrue(creds.isConfigured())
        // New instance simulates app restart: the encrypted blob in
        // SharedPreferences must be decrypted via the same cryptor and
        // yield the original plaintext.
        val reread = HaCredentials(app)
        assertEquals("https://h.example", reread.baseUrl)
        assertEquals("tok-123", reread.token)
    }

    @Test
    fun storedBlobIsNotPlaintext() {
        val creds = HaCredentials(app)
        creds.save("https://h.example", "tok-123")
        // Probe SharedPreferences directly: a regression that bypassed the
        // cryptor would leave plaintext on disk, defeating the threat model.
        val sp = app.applicationContext.getSharedPreferences(
            "ha_credentials_v2",
            android.content.Context.MODE_PRIVATE,
        )
        val storedUrl = sp.getString("ha_base_url", null)
        assertNotEquals("https://h.example", storedUrl)
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
    fun individualPropertySettersRoundTripThroughCryptor() {
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
}
