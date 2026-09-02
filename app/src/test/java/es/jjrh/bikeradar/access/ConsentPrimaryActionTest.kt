// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.access

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the confirm button offers for each combination the rider can leave the
 * toggles in. The screen renders this; the rules are here.
 */
class ConsentPrimaryActionTest {

    @Test
    fun eitherToggleOnIsSomethingToAllow() {
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = false, read = true, control = false))
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = false, read = false, control = true))
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = false, read = true, control = true))
    }

    @Test
    fun bothOffOverAnExistingGrantIsARevoke() {
        // The rider wanting an app to stop is in that app, not in these
        // Settings, so this has to stay reachable from here.
        assertEquals(ConsentPrimaryAction.REVOKE, consentPrimaryAction(hasGrant = true, read = false, control = false))
    }

    @Test
    fun bothOffWithNothingGrantedHasNothingToStore() {
        // The defect this closes: the button read "Allow", stored nothing, and
        // told the asking app its answer had been recorded.
        assertEquals(ConsentPrimaryAction.NOTHING, consentPrimaryAction(hasGrant = false, read = false, control = false))
    }

    @Test
    fun anExistingGrantDoesNotTurnAnAllowIntoARevoke() {
        // All three, so the table across both files is visibly total: eight
        // inputs, eight assertions, nothing left for a reader to work out.
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = true, read = true, control = false))
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = true, read = false, control = true))
        assertEquals(ConsentPrimaryAction.ALLOW, consentPrimaryAction(hasGrant = true, read = true, control = true))
    }
}
