// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import es.jjrh.bikeradar.HaFailure
import es.jjrh.bikeradar.HaFamily
import es.jjrh.bikeradar.HaHealth
import es.jjrh.bikeradar.HaHealthBus
import es.jjrh.bikeradar.testutil.InMemoryCryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A publish outcome is evidence about the credentials that produced it, so it
 * must not survive them.
 *
 * The reset lives in [HaCredentials] rather than at the four call sites that
 * save credentials, which is what these tests are really pinning: put it in a
 * caller and the next caller added forgets it. Both directions of the stale
 * verdict matter - a rider who FIXES a mistyped host goes on reading
 * UNREACHABLE, and one who breaks a working host goes on reading READY.
 */
@RunWith(RobolectricTestRunner::class)
class HaCredentialsResetTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var creds: HaCredentials

    @Before
    fun setUp() {
        HaCredentials.cryptorFactory = { InMemoryCryptor() }
        creds = HaCredentials(app)
        creds.clear()
        HaHealthBus.reset()
    }

    @After
    fun tearDown() {
        HaHealthBus.reset()
        // Restored, matching every sibling: the factory is a static and a
        // test leaving an in-memory cryptor installed changes what the next
        // class in this worker encrypts with.
        HaCredentials.cryptorFactory = { AndroidKeyStoreCryptor() }
    }

    @Test
    fun `saving different credentials forgets what the old ones proved`() {
        creds.save("https://ha.example:8123", "token-one")
        HaHealthBus.reportOk(HaFamily.BATTERY)

        creds.save("https://other.example:8123", "token-two")

        assertEquals(
            "a verdict earned by the previous credentials must not survive them",
            HaHealth.Unknown,
            HaHealthBus.state.value,
        )
    }

    @Test
    fun `a fixed host does not keep reading unreachable`() {
        // The direction a rider actually hits: the host was wrong, they
        // corrected it, and nothing publishes again until the next ride edge.
        creds.save("https://typo.example:8123", "token")
        HaHealthBus.reportError(HaFamily.RIDE_EDGE, "failed", HaFailure.HOST_UNREACHABLE)

        creds.save("https://correct.example:8123", "token")

        assertEquals(HaHealth.Unknown, HaHealthBus.state.value)
    }

    @Test
    fun `re-saving the same credentials keeps the verdict`() {
        // The Settings screen writes on every press of save, edited or not.
        // Wiping a good verdict for a no-op write would put the row back to
        // "nothing observed yet" for no new reason.
        creds.save("https://ha.example:8123", "token-one")
        HaHealthBus.reportOk(HaFamily.BATTERY)

        creds.save("https://ha.example:8123", "token-one")

        assertEquals(HaHealth.Ok, HaHealthBus.state.value)
    }

    @Test
    fun `clearing credentials forgets the verdict too`() {
        creds.save("https://ha.example:8123", "token-one")
        HaHealthBus.reportOk(HaFamily.BATTERY)

        creds.clear()

        assertEquals(HaHealth.Unknown, HaHealthBus.state.value)
        assertTrue("clear must also unconfigure", !creds.isConfigured())
    }
}
