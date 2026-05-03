// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [HaCredentials] honours the [Cryptor] contract: stored values
 * are not the plaintext, round-trip restores plaintext, and `isConfigured`
 * tracks save/clear correctly. The seam is what makes the rest of the
 * Activity / Service smoke tests possible — a regression here breaks
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
