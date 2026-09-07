package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.data.PhotoStateRepository.StoredState
import it.threarth.fotosistemis.core.model.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a new pending decision keeps of the one it writes over.
 *
 * The rule decides what discarding gives back, so each case is a story:
 * a photo in the bin, restored and then not; a filed photo refiled twice
 * and then neither; a photo never decided about at all.
 */
class StoredStateTest {

    private companion object {
        const val PHOTO = 4L
        const val FAMILY = 7L
        const val TRAVEL = 8L
    }

    @Test
    fun `una decisione eseguita viene conservata`() {
        val filed = StoredState(PHOTO, ReviewStatus.CATEGORIZED, FAMILY, pending = false)

        assertEquals(filed, filed.decisionToKeep())
    }

    @Test
    fun `una decisione in sospeso tramanda quella che aveva sostituito`() {
        val refiled = StoredState(
            PHOTO, ReviewStatus.CATEGORIZED, TRAVEL, pending = true,
            previousStatus = ReviewStatus.TRASHED, previousDestinationId = null
        )

        assertEquals(
            StoredState(PHOTO, ReviewStatus.TRASHED, null),
            refiled.decisionToKeep()
        )
    }

    @Test
    fun `una decisione in sospeso su una foto mai vista non conserva nulla`() {
        val trashed = StoredState(PHOTO, ReviewStatus.TRASHED, null, pending = true)

        assertNull(trashed.decisionToKeep())
    }
}
