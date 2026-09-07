package it.threarth.fotosistemis.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A proposal is shown as what it will become, and round-trips its stored value. */
class ProposalTest {

    @Test
    fun `each action is shown as its outcome`() {
        assertEquals(ReviewStatus.CATEGORIZED, Proposal(1, Proposal.Action.FILE, 7, 0).shownStatus)
        assertEquals(ReviewStatus.TRASHED, Proposal(1, Proposal.Action.TRASH, null, 0).shownStatus)
        assertEquals(ReviewStatus.KEPT, Proposal(1, Proposal.Action.RESTORE, null, 0).shownStatus)
    }

    @Test
    fun `stored values round-trip and unknown ones are null`() {
        for (action in Proposal.Action.entries) {
            assertEquals(action, Proposal.Action.fromStoredValue(action.storedValue))
        }
        assertNull(Proposal.Action.fromStoredValue("categorized"))
        assertNull(Proposal.Action.fromStoredValue(null))
    }
}
