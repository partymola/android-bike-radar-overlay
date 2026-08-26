// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bench scenario has to describe traffic the decider can actually read.
 *
 * It is the only instrument for auditioning alert behaviour without a ride, so
 * a scenario that contradicts itself does not fail loudly - it just quietly
 * demonstrates the wrong thing, and every gate stays green while it does.
 *
 * The check derives its expectation from the motion the script writes, not
 * from the speeds it declares, so it cannot be satisfied by agreeing with
 * itself.
 */
@RunWith(RobolectricTestRunner::class)
class SyntheticScenarioServiceScriptTest {

    private val svc = SyntheticScenarioService()

    /** Every 100 ms of the 60 s timeline, as (id -> vehicle) frames. */
    private fun timeline(): List<Map<Int, Vehicle>> = (0..600).map { step ->
        svc.scriptAt(step * 100L).associateBy { it.id }
    }

    @Test
    fun aVehicleThatIsClosingDeclaresANegativeSpeed() {
        // Vehicle.speedMs: negative = approaching, positive = receding. A
        // vehicle whose distance counts down while its speed reads positive
        // is closing and receding at once, and every closing gate reads the
        // receding half.
        val frames = timeline()
        var checked = 0
        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            for ((id, now) in frames[i]) {
                val before = prev[id] ?: continue
                // Only where the distance actually moved: the script clamps
                // several vehicles at a floor (coerceAtLeast), and a clamped
                // tail holds distance flat while the declared speed stays
                // non-zero, which is not a contradiction.
                if (now.distanceM == before.distanceM) continue
                checked++
                val closing = now.distanceM < before.distanceM
                assertTrue(
                    "id=$id at frame $i moved ${before.distanceM}->${now.distanceM} m " +
                        "but declares speedMs=${now.speedMs}; " +
                        "negative means approaching",
                    if (closing) now.speedMs < 0f else now.speedMs > 0f,
                )
            }
        }
        // Anti-vacuity. A script that emitted nothing, or one whose vehicles
        // never moved, would satisfy every assertion above without testing
        // anything. The timeline scripts fourteen vehicles over 60 s, so the
        // real count is in the thousands; this only has to be far above zero.
        assertTrue("no moving vehicle was checked - the script emitted nothing", checked > 500)
    }

    @Test
    fun aStationaryVehicleDeclaresZeroSpeed() {
        // The two parked/braked cases are what the alongside-stationary dock
        // and the renderer's edge-dock exist to catch, and both gates test
        // |speedMs| against a floor near zero. A parked car carrying a
        // non-zero speed would silently stop exercising either.
        val frames = timeline()
        var checked = 0
        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            for ((id, now) in frames[i]) {
                val before = prev[id] ?: continue
                // A vehicle held at exactly the same distance for a full
                // second is parked relative to the rider, not clamped mid-
                // approach: the clamped tails all sit at the coerce floor
                // and are reached from above within a frame or two.
                val heldFor = (1..10).count { back ->
                    frames.getOrNull(i - back)?.get(id)?.distanceM == now.distanceM
                }
                if (before.distanceM != now.distanceM || heldFor < 10) continue
                checked++
                assertTrue(
                    "id=$id held at ${now.distanceM} m but declares speedMs=${now.speedMs}",
                    now.speedMs == 0f,
                )
            }
        }
        assertTrue("no held vehicle was checked", checked > 0)
    }
}
