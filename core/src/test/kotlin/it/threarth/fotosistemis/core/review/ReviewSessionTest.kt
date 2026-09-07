package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.ProposalRepository
import it.threarth.fotosistemis.core.data.Schema
import it.threarth.fotosistemis.core.data.TagRepository
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the queue against the database it is a view of: the proposals
 * in scope planned into moves, rebuilt at every load, scoped wider than
 * the screen, and undone only for what this session decided — with the
 * truth about each photo never overwritten by a request about it.
 *
 * The database is a stand-in that accepts every write and answers no
 * query: what matters here is what the session queues, not what SQLite
 * stores, and the repositories are exercised against real SQLite on the
 * device.
 */
class ReviewSessionTest {

    private companion object {
        const val YEAR_PATTERN = "{anno}"
        const val WITHDRAWN = "DELETE FROM ${Schema.TABLE_PROPOSALS}"
        const val FORGOTTEN = "DELETE FROM ${Schema.TABLE_PHOTO_STATE}"
    }

    /** Accepts every statement, remembering the deletions for the asserts. */
    private class AcceptingDatabase : Database {
        val withdrawnIds = ArrayList<Long>()
        val forgottenIds = ArrayList<Long>()

        override fun query(sql: String, args: List<Any?>): List<Database.Row> = emptyList()

        override fun execute(sql: String, args: List<Any?>): Int {
            if (sql.startsWith(WITHDRAWN)) withdrawnIds.add(args.single() as Long)
            if (sql.startsWith(FORGOTTEN)) forgottenIds.add(args.single() as Long)
            return 1
        }

        override fun insert(sql: String, args: List<Any?>): Long = 1
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val family = Destination(
        id = 7, label = "Famiglia", relativePath = "Pictures/Famiglia/",
        yearSubfolder = false, sortOrder = 0
    )
    private val travel = family.copy(id = 8, label = "Viaggi", relativePath = "Pictures/Viaggi/")

    private val database = AcceptingDatabase()
    private val states = PhotoStateRepository(database)
    private val session = ReviewSession(
        states, ProposalRepository(database, states), TagRepository(database)
    ) { YEAR_PATTERN }.also {
        it.destinationsById = { id -> listOf(family, travel).firstOrNull { d -> d.id == id } }
    }

    private fun photo(id: Long, folder: String = "DCIM/Camera/", name: String = "IMG_$id.jpg") =
        PhotoRecord(
            photoId = id, platformId = id * 100, volumeName = "external_primary",
            displayName = name, relativePath = folder, sizeBytes = 10,
            dateTakenMillis = 0, dateSource = CaptureDateResolver.Source.EXIF
        )

    private fun asked(id: Long, action: Proposal.Action, destinationId: Long? = null) =
        id to Proposal(id, action, destinationId, proposedAt = 0)

    private fun truth(id: Long, status: ReviewStatus, destinationId: Long? = null) =
        id to PhotoStateRepository.StoredState(id, status, destinationId)

    @Test
    fun `the queue is the proposals in scope, planned`() {
        val photos = listOf(photo(1), photo(2), photo(3))
        session.load(
            photos,
            mapOf(truth(2, ReviewStatus.KEPT)),
            mapOf(asked(1, Proposal.Action.TRASH), asked(3, Proposal.Action.FILE, 7)),
            emptyMap(), emptyMap()
        )

        assertEquals(listOf(1L, 3L), session.queuedMoves.map { it.photo.photoId })
        assertEquals(
            "Planned with the same name the screen would give",
            MovePlanner.toCategory(photo(3), family, YEAR_PATTERN),
            session.queuedMoves.last()
        )
    }

    @Test
    fun `a load replaces the queue instead of adding to it`() {
        session.load(listOf(photo(1)), emptyMap(), mapOf(asked(1, Proposal.Action.TRASH)), emptyMap(), emptyMap())
        // Carried out elsewhere in the meantime: no longer asked.
        session.load(listOf(photo(1)), mapOf(truth(1, ReviewStatus.TRASHED)), emptyMap(), emptyMap(), emptyMap())

        assertEquals(0, session.pendingCount)
    }

    @Test
    fun `the scope decides what is queued, the screen what is shown`() {
        val hidden = photo(2)
        session.load(
            listOf(photo(1)),
            emptyMap(),
            mapOf(asked(2, Proposal.Action.TRASH), asked(9, Proposal.Action.TRASH)),
            emptyMap(), emptyMap(),
            scope = listOf(photo(1), hidden)
        )

        assertEquals(1, session.size)
        assertEquals(listOf(2L), session.queuedMoves.map { it.photo.photoId })
    }

    @Test
    fun `a restore still asked is planned from the origin`() {
        val inBin = photo(1, folder = ReviewSession.DELETION_STAGING_PATH)
        val origin = PhotoStateRepository.Location("DCIM/Camera/", "IMG_1.jpg")
        session.load(
            listOf(inBin), mapOf(truth(1, ReviewStatus.TRASHED)),
            mapOf(asked(1, Proposal.Action.RESTORE)), emptyMap(), mapOf(1L to origin)
        )

        assertEquals(listOf(MovePlanner.backHome(inBin, origin)), session.queuedMoves)
        assertEquals("Shown as what is asked, not as what it is", ReviewStatus.KEPT, session.shownStatus(1))
    }

    @Test
    fun `undo reaches only what this session decided`() {
        session.load(listOf(photo(1), photo(2)), emptyMap(), mapOf(asked(1, Proposal.Action.TRASH)), emptyMap(), emptyMap())
        assertFalse("Nothing decided here yet", session.canUndo)

        // Loading resumes at the first undecided photo, which is the second.
        assertEquals(2L, session.current()?.photoId)
        assertTrue(session.trashCurrent().isSuccess)
        assertTrue(session.canUndo)
        assertEquals(2, session.pendingCount)

        assertTrue(session.undoLastMove().isSuccess)
        assertEquals(listOf(2L), database.withdrawnIds)
        assertEquals(listOf(1L), session.queuedMoves.map { it.photo.photoId })
        assertFalse("The older decision is not this session's to take back", session.canUndo)
        assertTrue(session.undoLastMove().isFailure)
    }

    @Test
    fun `deciding again replaces the queued move`() {
        session.load(listOf(photo(1)), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        session.trashCurrent()
        session.fileCurrent(family)

        assertEquals(1, session.pendingCount)
        assertEquals(Proposal.Action.FILE, session.queuedMoves.single().action)
        assertEquals(7L, session.currentDestinationId())
    }

    @Test
    fun `keeping a photo nothing is known about writes kept, and undo takes it back`() {
        session.load(listOf(photo(1)), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        session.trashCurrent()
        assertTrue(session.keepCurrent().isSuccess)

        assertEquals(0, session.pendingCount)
        assertEquals(listOf(1L), database.withdrawnIds)
        assertEquals(ReviewStatus.KEPT, session.shownStatus(1))
        assertTrue(session.canUndo)

        assertTrue(session.undoLastMove().isSuccess)
        assertEquals(listOf(1L), database.forgottenIds)
        assertNull(session.shownStatus(1))
    }

    @Test
    fun `keeping a refiled photo withdraws the request and leaves its category`() {
        session.load(
            listOf(photo(1)), mapOf(truth(1, ReviewStatus.CATEGORIZED, 7)),
            mapOf(asked(1, Proposal.Action.FILE, 8)), emptyMap(), emptyMap()
        )
        assertEquals(1, session.pendingCount)
        assertEquals(8L, session.currentDestinationId())

        assertTrue(session.keepCurrent().isSuccess)
        assertEquals(0, session.pendingCount)
        assertEquals(ReviewStatus.CATEGORIZED, session.shownStatus(1))
        assertEquals(listOf(1L), database.withdrawnIds)
        assertTrue("The truth is never overwritten", database.forgottenIds.isEmpty())
        assertFalse("Nothing was written, so nothing is undoable", session.canUndo)
    }

    @Test
    fun `discarding withdraws exactly the scoped proposals`() {
        session.load(
            listOf(photo(1)),
            mapOf(truth(2, ReviewStatus.CATEGORIZED, 7)),
            mapOf(asked(1, Proposal.Action.TRASH), asked(2, Proposal.Action.TRASH), asked(9, Proposal.Action.TRASH)),
            emptyMap(), emptyMap(),
            scope = listOf(photo(1), photo(2))
        )

        assertTrue(session.discardQueue().isSuccess)
        assertEquals(listOf(1L, 2L), database.withdrawnIds)
        assertTrue(database.forgottenIds.isEmpty())
        assertEquals(0, session.pendingCount)
        assertEquals("Back to what the truth says", ReviewStatus.CATEGORIZED, session.shownStatus(2))
        assertFalse(session.canUndo)
    }
}
