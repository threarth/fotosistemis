package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that every decision plans the same move whichever screen asks:
 * the bin keeps names, a category stamps them, home means folder and name.
 */
class MovePlannerTest {

    private companion object {
        const val YEAR_PATTERN = "{anno}"
        const val STAMPED_NAME = "__20240103_120000__IMG_0001.jpg"
    }

    private val family = Destination(
        id = 7, label = "Famiglia", relativePath = "Pictures/Famiglia/",
        yearSubfolder = true, sortOrder = 0
    )

    private fun millisAt(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private fun photo(
        name: String = "IMG_0001.jpg",
        folder: String = "DCIM/Camera/"
    ) = PhotoRecord(
        photoId = 1, platformId = 100, volumeName = "external_primary",
        displayName = name, relativePath = folder, sizeBytes = 10,
        dateTakenMillis = millisAt(2024, 1, 3),
        dateSource = CaptureDateResolver.Source.EXIF
    )

    @Test
    fun `the bin keeps the name the photo arrived with`() {
        val move = MovePlanner.toBin(photo())

        assertEquals(ReviewSession.DELETION_STAGING_PATH, move.destinationRelativePath)
        assertEquals(Proposal.Action.TRASH, move.action)
        assertNull(move.newDisplayName)
    }

    @Test
    fun `a category stamps the name and adds the year folder`() {
        val move = MovePlanner.toCategory(photo(), family, YEAR_PATTERN)

        assertEquals("Pictures/Famiglia/2024/", move.destinationRelativePath)
        assertEquals(family.id, move.destinationId)
        assertEquals(STAMPED_NAME, move.newDisplayName)
    }

    @Test
    fun `going home restores folder and original name`() {
        val origin = PhotoStateRepository.Location("DCIM/Camera/", "IMG_0001.jpg")
        val move = MovePlanner.backHome(photo(name = STAMPED_NAME, folder = "Pictures/Famiglia/2024/"), origin)

        assertEquals("DCIM/Camera/", move.destinationRelativePath)
        assertEquals("IMG_0001.jpg", move.newDisplayName)
        assertEquals(Proposal.Action.RESTORE, move.action)
    }

    @Test
    fun `home means the folder and the name both match`() {
        val origin = PhotoStateRepository.Location("DCIM/Camera/", "IMG_0001.jpg")

        assertTrue(MovePlanner.isHome(photo(), origin))
        assertFalse(MovePlanner.isHome(photo(name = STAMPED_NAME), origin))
        assertFalse(MovePlanner.isHome(photo(folder = "Pictures/Famiglia/2024/"), origin))
    }

    @Test
    fun `an origin recorded without a name compares the folder alone`() {
        val origin = PhotoStateRepository.Location("DCIM/Camera/", null)

        assertTrue(MovePlanner.isHome(photo(name = STAMPED_NAME), origin))
    }

    private fun proposal(action: Proposal.Action, destinationId: Long? = null) =
        Proposal(1, action, destinationId, proposedAt = 0)

    @Test
    fun `a proposal plans the same move the screen would`() {
        val filed = MovePlanner.plan(
            photo(), proposal(Proposal.Action.FILE, family.id), family, YEAR_PATTERN, null
        )
        assertEquals(MovePlanner.toCategory(photo(), family, YEAR_PATTERN), filed)

        val binned = MovePlanner.plan(photo(), proposal(Proposal.Action.TRASH), null, YEAR_PATTERN, null)
        assertEquals(MovePlanner.toBin(photo()), binned)
    }

    @Test
    fun `a proposal that cannot be planned is null rather than wrong`() {
        assertNull(
            "No category, no move",
            MovePlanner.plan(photo(), proposal(Proposal.Action.FILE, 99), null, YEAR_PATTERN, null)
        )
        assertNull(
            "No origin, no restore",
            MovePlanner.plan(photo(), proposal(Proposal.Action.RESTORE), null, YEAR_PATTERN, null)
        )
        assertNull(
            "Already home, nothing to restore",
            MovePlanner.plan(
                photo(), proposal(Proposal.Action.RESTORE), null, YEAR_PATTERN,
                PhotoStateRepository.Location("DCIM/Camera/", "IMG_0001.jpg")
            )
        )
    }

    @Test
    fun `the obstacle names what keeps a proposal from being planned`() {
        val home = PhotoStateRepository.Location("DCIM/Camera/", "IMG_0001.jpg")
        assertEquals(
            MovePlanner.Obstacle.NO_CATEGORY,
            MovePlanner.obstacle(photo(), proposal(Proposal.Action.FILE, 99), null, null)
        )
        assertEquals(
            MovePlanner.Obstacle.NO_ORIGIN,
            MovePlanner.obstacle(photo(), proposal(Proposal.Action.RESTORE), null, null)
        )
        assertEquals(
            MovePlanner.Obstacle.ALREADY_HOME,
            MovePlanner.obstacle(photo(), proposal(Proposal.Action.RESTORE), null, home)
        )
        assertNull(MovePlanner.obstacle(photo(), proposal(Proposal.Action.TRASH), null, null))
        assertNull(
            MovePlanner.obstacle(photo(folder = "Pictures/x/"), proposal(Proposal.Action.RESTORE), null, home)
        )
        assertNull(
            MovePlanner.obstacle(photo(), proposal(Proposal.Action.FILE, family.id), family, null)
        )
    }
}
