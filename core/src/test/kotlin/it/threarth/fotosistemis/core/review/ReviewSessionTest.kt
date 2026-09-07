package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.Schema
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

    /**
     * Accepts every statement, remembering the deletions for the asserts.
     *
     * Answers a query for one photo's decision from [rows], seeded by the
     * test, so that what the repository reads back can be controlled.
     */
    private class AcceptingDatabase : Database {
        val deletedIds = ArrayList<Long>()
        val rows = HashMap<Long, Database.Row>()

        override fun query(sql: String, args: List<Any?>): List<Database.Row> =
            listOfNotNull(rows[args.singleOrNull()])

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

    /** A stored decision as the database would hand it back. */
    private fun row(state: PhotoStateRepository.StoredState) = object : Database.Row {
        private val values: Map<String, Any?> = mapOf(
            Schema.COLUMN_PHOTO_ID to state.photoId,
            Schema.COLUMN_STATUS to state.status.storedValue,
            Schema.COLUMN_DESTINATION_ID to state.destinationId,
            Schema.COLUMN_PENDING to if (state.pending) 1L else 0L,
            Schema.COLUMN_PREVIOUS_STATUS to state.previousStatus?.storedValue,
            Schema.COLUMN_PREVIOUS_DESTINATION_ID to state.previousDestinationId
        )

        override fun getString(column: String) = values[column] as String?
        override fun getLong(column: String) = values[column] as Long?
        override fun getInt(column: String) = (values[column] as Long?)?.toInt()
    }

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
    fun `keeping a refiled photo gives its category back`() {
        val refiled = PhotoStateRepository.StoredState(
            1, ReviewStatus.CATEGORIZED, 8, pending = true,
            previousStatus = ReviewStatus.CATEGORIZED, previousDestinationId = 7
        )
        val travel = family.copy(id = 8, label = "Viaggi", relativePath = "Pictures/Viaggi/")
        session.destinationsById = { id -> listOf(family, travel).firstOrNull { it.id == id } }
        database.rows[1L] = row(refiled)
        session.load(listOf(photo(1)), mapOf(1L to refiled), emptyMap(), emptyMap())
        assertEquals(1, session.pendingCount)

        assertTrue(session.keepCurrent().isSuccess)
        assertEquals(0, session.pendingCount)
        assertEquals(ReviewStatus.CATEGORIZED, session.statusOf(1))
        assertEquals(7L, session.currentDestinationId())
        assertTrue(database.deletedIds.isEmpty())
        assertFalse(session.canUndo)
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
