package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.TagRepository
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the queue against the database it is a view of: rebuilt from
 * what is owed at every load, scoped wider than the screen, and undone
 * only for what this session decided.
 *
 * The database is a stand-in that accepts every write and answers no
 * query: what matters here is what the session queues, not what SQLite
 * stores, and the repositories are exercised against real SQLite on the
 * device.
 */
class ReviewSessionTest {

    private companion object {
        const val YEAR_PATTERN = "{anno}"
        const val DELETED_ROWS = "DELETE FROM photo_state"
    }

    /** Accepts every statement, remembering the deletions for the asserts. */
    private class AcceptingDatabase : Database {
        val deletedIds = ArrayList<Long>()

        override fun query(sql: String, args: List<Any?>): List<Database.Row> = emptyList()

        override fun execute(sql: String, args: List<Any?>): Int {
            if (sql.startsWith(DELETED_ROWS)) deletedIds.add(args.single() as Long)
            return 1
        }

        override fun insert(sql: String, args: List<Any?>): Long = 1
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val family = Destination(
        id = 7, label = "Famiglia", relativePath = "Pictures/Famiglia/",
        yearSubfolder = false, sortOrder = 0
    )

    private val database = AcceptingDatabase()
    private val session = ReviewSession(
        PhotoStateRepository(database), TagRepository(database)
    ) { YEAR_PATTERN }.also { it.destinationsById = { id -> family.takeIf { id == 7L } } }

    private fun photo(id: Long, folder: String = "DCIM/Camera/", name: String = "IMG_$id.jpg") =
        PhotoRecord(
            photoId = id, platformId = id * 100, volumeName = "external_primary",
            displayName = name, relativePath = folder, sizeBytes = 10,
            dateTakenMillis = 0, dateSource = CaptureDateResolver.Source.EXIF
        )

    private fun owed(id: Long, status: ReviewStatus, destinationId: Long? = null) =
        id to PhotoStateRepository.StoredState(id, status, destinationId, pending = true)

    private fun done(id: Long, status: ReviewStatus) =
        id to PhotoStateRepository.StoredState(id, status, null, pending = false)

    @Test
    fun `the queue is read back from what is owed`() {
        val photos = listOf(photo(1), photo(2), photo(3))
        session.load(
            photos,
            mapOf(owed(1, ReviewStatus.TRASHED), done(2, ReviewStatus.KEPT), owed(3, ReviewStatus.CATEGORIZED, 7)),
            emptyMap(), emptyMap()
        )

        assertEquals(listOf(1L, 3L), session.queuedMoves.map { it.photo.photoId })
        assertEquals(
            "Queued from the spool with the same name the screen would give",
            MovePlanner.toCategory(photo(3), family, YEAR_PATTERN),
            session.queuedMoves.last()
        )
    }

    @Test
    fun `a load replaces the queue instead of adding to it`() {
        session.load(listOf(photo(1)), mapOf(owed(1, ReviewStatus.TRASHED)), emptyMap(), emptyMap())
        // Carried out elsewhere in the meantime: no longer owed.
        session.load(listOf(photo(1)), mapOf(done(1, ReviewStatus.TRASHED)), emptyMap(), emptyMap())

        assertEquals(0, session.pendingCount)
    }

    @Test
    fun `the scope decides what is queued, the screen what is shown`() {
        val hidden = photo(2)
        session.load(
            listOf(photo(1)),
            mapOf(owed(2, ReviewStatus.TRASHED)),
            emptyMap(), emptyMap(),
            scope = listOf(photo(1), hidden)
        )

        assertEquals(1, session.size)
        assertEquals(listOf(2L), session.queuedMoves.map { it.photo.photoId })
    }

    @Test
    fun `a restore still owed is queued from the origin`() {
        val inBin = photo(1, folder = ReviewSession.DELETION_STAGING_PATH)
        val origin = PhotoStateRepository.Location("DCIM/Camera/", "IMG_1.jpg")
        session.load(listOf(inBin), mapOf(owed(1, ReviewStatus.KEPT)), emptyMap(), mapOf(1L to origin))

        assertEquals(listOf(MovePlanner.backHome(inBin, origin)), session.queuedMoves)
    }

    @Test
    fun `undo reaches only what this session decided`() {
        session.load(listOf(photo(1), photo(2)), mapOf(owed(1, ReviewStatus.TRASHED)), emptyMap(), emptyMap())
        assertFalse("Nothing decided here yet", session.canUndo)

        // Loading resumes at the first undecided photo, which is the second.
        assertEquals(2L, session.current()?.photoId)
        assertTrue(session.trashCurrent().isSuccess)
        assertTrue(session.canUndo)
        assertEquals(2, session.pendingCount)

        assertTrue(session.undoLastMove().isSuccess)
        assertEquals(listOf(2L), database.deletedIds)
        assertEquals(listOf(1L), session.queuedMoves.map { it.photo.photoId })
        assertFalse("The older decision is not this session's to take back", session.canUndo)
        assertTrue(session.undoLastMove().isFailure)
    }

    @Test
    fun `deciding again replaces the queued move`() {
        session.load(listOf(photo(1)), emptyMap(), emptyMap(), emptyMap())
        session.trashCurrent()
        session.fileCurrent(family)

        assertEquals(1, session.pendingCount)
        assertEquals(ReviewStatus.CATEGORIZED, session.queuedMoves.single().status)
    }

    @Test
    fun `keeping revokes the queued move and is done on the spot`() {
        session.load(listOf(photo(1)), emptyMap(), emptyMap(), emptyMap())
        session.trashCurrent()
        session.keepCurrent()

        assertEquals(0, session.pendingCount)
        assertEquals(ReviewStatus.KEPT, session.statusOf(1))
    }

    @Test
    fun `discarding forgets exactly the scoped queue`() {
        session.load(
            listOf(photo(1)),
            mapOf(owed(1, ReviewStatus.TRASHED), owed(2, ReviewStatus.TRASHED), owed(9, ReviewStatus.TRASHED)),
            emptyMap(), emptyMap(),
            scope = listOf(photo(1), photo(2))
        )

        assertTrue(session.discardQueue().isSuccess)
        assertEquals(listOf(1L, 2L), database.deletedIds)
        assertEquals(0, session.pendingCount)
        assertFalse(session.canUndo)
    }
}
