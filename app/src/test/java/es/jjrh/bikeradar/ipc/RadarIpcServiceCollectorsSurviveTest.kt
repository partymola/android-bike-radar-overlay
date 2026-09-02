// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ipc

import es.jjrh.bikeradar.testutil.RepoFiles
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A throw must not be able to end either cross-app collector.
 *
 * The failure is silent and permanent rather than noisy. An exception escaping
 * a `collect` body cancels that coroutine, and the SupervisorJob then keeps the
 * service alive with nothing to restart it, so either every consumer's stream
 * stops for good or revalidation stops and an app the rider revoked carries on
 * receiving frames. Nothing raises: the exception dies with the Job.
 *
 * Two halves, because neither is worth anything alone. The first drives a throw
 * through the wrapper. The second reads the source, because no Robolectric test
 * can make `broadcast` or `revalidate` actually throw, so nothing else could
 * tell whether the collectors use the wrapper at all - which is the half that
 * would silently stop being true.
 */
@RunWith(RobolectricTestRunner::class)
class RadarIpcServiceCollectorsSurviveTest {

    private fun service() = Robolectric.buildService(RadarIpcService::class.java).create().get()

    @Test
    fun aThrowingTurnDoesNotPropagate() {
        var ran = false

        service().survive("feed") {
            ran = true
            throw IllegalStateException("the package manager died")
        }

        assertTrue("the block must have run", ran)
    }

    @Test
    fun anErrorIsSwallowedToo() {
        // `Exception` is the narrowing that looks harmless and is not: an
        // `Error` from deep in the framework ends the collector just as
        // permanently, and reads to a rider exactly the same way.
        service().survive("revalidation") { throw StackOverflowError() }
    }

    @Test
    fun cancellationStillPropagates() {
        // Teardown cancels the scope, and swallowing that would leave a
        // collector running against a service that has gone.
        val s = service()

        assertThrows(CancellationException::class.java) {
            s.survive("feed") { throw CancellationException("service stopping") }
        }
    }

    @Test
    fun bothCollectorsRunTheirTurnThroughIt() {
        val text = RepoFiles.mainSource("ipc/RadarIpcService.kt").readText()

        // Comments stripped first: `survive(` appears in this file's own prose
        // about the wrapper, and a substring test over raw source would accept
        // a collector that only mentions it.
        val code = text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        // Any launch, however it is spelled - `scope.launch(Dispatchers.IO)`, a
        // bare `launch`, a nested one. A pattern that only knew the current
        // spelling would let a third collector arrive unwrapped.
        val launches = Regex("""launch[^{]*\{(.*?)\n {8}\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("the service should launch exactly the feed and the revalidation", 2, launches.size)
        launches.forEach {
            assertTrue(
                "a collector that does not wrap its turn dies on the first throw, permanently " +
                    "and silently: $it",
                it.contains("survive("),
            )
        }
    }
}
