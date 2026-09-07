package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.Database

/**
 * What has happened to each photo, and where it has been.
 *
 * This is the truth and nothing else (v12): kept, filed under a category,
 * thrown away — or no row at all for a photo never seen. What the user has
 * asked to be done and is not done yet lives in [ProposalRepository];
 * carrying a proposal out is what writes here.
 */
class PhotoStateRepository(private val database: Database) {

    private companion object {

        /** The tables that point photos at a category: what is, and what is asked. */
        val DESTINATION_TABLES = listOf(Schema.TABLE_PHOTO_STATE, Schema.TABLE_PROPOSALS)

        /** Every column of a decision, as [stateOf] reads them. */
        val STATE_COLUMNS = listOf(
            Schema.COLUMN_PHOTO_ID, Schema.COLUMN_STATUS, Schema.COLUMN_DESTINATION_ID
        ).joinToString(", ")
    }

    /** What has happened to one photo. */
    data class StoredState(
        val photoId: Long,
        val status: ReviewStatus,
        val destinationId: Long?
    )

    /**
     * Somewhere a photo has been, folder and file name together.
     *
     * The name is nullable because rows written before v6 recorded only the
     * folder: null means the name at that moment is not known, not that the
     * photo had none.
     */
    data class Location(val relativePath: String, val displayName: String?)

    /** Why a path was recorded. */
    enum class PathKind(val storedValue: String) {

        /** Where the photo was when the app first saw it. */
        ORIGINAL("original"),

        /** Where the app put it afterwards. */
        MOVED("moved"),

        /**
         * Handed to Android's own bin, because it could not be moved.
         *
         * Recorded like a location because that is what it is: the photo
         * left, and the app needs to know it left. Without this the decision
         * looks unfinished for ever — the file is still in WhatsApp's folder
         * as far as its own path is concerned — and the photo is offered for
         * gathering again at every check.
         */
        SYSTEM_BIN("system_bin")
    }

    /**
     * Loads every decision, keyed by our own photo id.
     *
     * Read in one go because filtering means membership checks for hundreds
     * of photos: one query plus in-memory lookups beats one query per photo.
     */
    fun loadAll(): Result<Map<Long, StoredState>> = runCatching {
        database.query("SELECT $STATE_COLUMNS FROM ${Schema.TABLE_PHOTO_STATE}")
            .mapNotNull { row -> stateOf(row)?.let { it.photoId to it } }
            .toMap()
    }

    /** One row as a decision, or null when the row cannot be read as one. */
    private fun stateOf(row: Database.Row): StoredState? {
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return null
        val status = ReviewStatus.fromStoredValue(row.getString(Schema.COLUMN_STATUS))
            ?: return null
        return StoredState(photoId, status, row.getLong(Schema.COLUMN_DESTINATION_ID))
    }

    /**
     * The folder each photo was in when the app first saw it, keyed by
     * photo id. This is what restoring puts a photo back into.
     */
    fun loadOriginalPaths(): Result<Map<Long, Location>> = runCatching {
        val paths = HashMap<Long, Location>()
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_PATH}, " +
                    "${Schema.COLUMN_DISPLAY_NAME} FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_KIND} = ? " +
                    "ORDER BY ${Schema.COLUMN_RECORDED_AT} ASC",
            listOf(PathKind.ORIGINAL.storedValue)
        ).forEach { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@forEach
            val path = row.getString(Schema.COLUMN_PATH) ?: return@forEach
            // Oldest wins: the first location recorded is the original one.
            paths.putIfAbsent(
                photoId,
                Location(path, row.getString(Schema.COLUMN_DISPLAY_NAME))
            )
        }
        paths
    }

    /**
     * Records what is true of a photo without any file having moved, and
     * remembers where the photo is at that moment.
     *
     * For decisions done the moment they are taken: keeping a photo where
     * it is, or filing a folder into a category it already sits in. Work
     * that needs a file to move is not recorded here but proposed, and
     * arrives here through [markCarriedOut] once the file has gone.
     */
    fun record(photo: PhotoRecord, status: ReviewStatus, destinationId: Long?): Result<Unit> =
        runCatching {
            database.transaction {
                writeTruth(photo.photoId, status, destinationId, System.currentTimeMillis())
                rememberOrigin(photo)
                Unit
            }
        }

    /** Writes what is true of one photo over whatever was there. */
    private fun writeTruth(photoId: Long, status: ReviewStatus, destinationId: Long?, now: Long) {
        database.execute(
            "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                    "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                    "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}) " +
                    "VALUES (?, ?, ?, ?)",
            listOf(photoId, status.storedValue, destinationId, now)
        )
    }

    /**
     * Records that a proposal has been carried out: where the photo ended
     * up, how it got there, and what is now true of it — in one
     * transaction, with the proposal gone.
     *
     * Four writes describing one event. Apart, a death between them leaves
     * a photo said to be filed whose folder was never updated, a move
     * recorded twice, or a proposal still asking for what has been done.
     * Together they either all describe what happened or none of them
     * does.
     */
    fun markCarriedOut(
        photoId: Long,
        relativePath: String,
        displayName: String,

        /** What the photo is now that the file has moved. */
        outcome: ReviewStatus,
        destinationId: Long?,

        /**
         * False when the photo itself did not move.
         *
         * A file the platform refuses to move is copied instead, and the
         * original stays exactly where it was. The work is done — the
         * photograph is in its category — but saying the original went
         * there would be a plain untruth, and every screen reading the
         * inventory would repeat it.
         */
        relocated: Boolean = true
    ): Result<Unit> = runCatching {
        database.transaction {
            insertPath(photoId, relativePath, displayName, PathKind.MOVED)
            if (relocated) database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_RELATIVE_PATH} = ?, " +
                        "${Schema.COLUMN_DISPLAY_NAME} = ? WHERE ${Schema.COLUMN_ID} = ?",
                listOf(relativePath, displayName, photoId)
            )
            writeTruth(photoId, outcome, destinationId, System.currentTimeMillis())
            deleteProposal(photoId)
            Unit
        }
    }

    /** Drops the proposal about [photoId], now that it is done. */
    private fun deleteProposal(photoId: Long) {
        database.execute(
            "DELETE FROM ${Schema.TABLE_PROPOSALS} WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
            listOf(photoId)
        )
    }

    /**
     * Records the same truth about many photos, and says how many.
     *
     * One transaction for the lot: filing a folder is one act as far as the
     * user is concerned, and half a folder filed because something failed
     * partway would be worse than none. Each photo's original location is
     * remembered here too, exactly as when deciding one at a time.
     */
    fun recordAll(
        photos: List<PhotoRecord>,
        status: ReviewStatus,
        destinationId: Long?
    ): Result<Int> = runCatching {
        if (photos.isEmpty()) return@runCatching 0

        database.transaction {
            val now = System.currentTimeMillis()
            for (photo in photos) {
                writeTruth(photo.photoId, status, destinationId, now)
                rememberOrigin(photo)
            }
            photos.size
        }
    }

    /**
     * The folders each photo has already been put into, keyed by photo id.
     *
     * For a photo the platform will not let us move, where it is cannot
     * answer whether the work was done: it was copied, and the original
     * stayed. Only this record says so, and without it the same copy is
     * made again at every pass.
     */
    fun destinationsReached(): Result<Map<Long, Set<String>>> = runCatching {
        val reached = HashMap<Long, MutableSet<String>>()
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID} AS pid, ${Schema.COLUMN_PATH} AS path " +
                    "FROM ${Schema.TABLE_PHOTO_PATHS} WHERE ${Schema.COLUMN_KIND} = ?",
            listOf(PathKind.MOVED.storedValue)
        ).forEach { row ->
            val photoId = row.getLong("pid") ?: return@forEach
            val path = row.getString("path") ?: return@forEach
            reached.getOrPut(photoId) { HashSet() }.add(path.trim('/'))
        }
        reached
    }

    /**
     * Records that a photo was handed to Android's bin.
     *
     * The app cannot follow it there and does not govern what happens next:
     * what it can do is write down that the file left, and stop asking.
     *
     * Bugfix: what became of the *photograph* is a separate question, and
     * the answer depends on why the file was given up. Two paths end here.
     * A photo decided against goes to Android's bin because it cannot go
     * to ours, and it is thrown away. A photo the platform will not let us
     * move is copied into its category first, and only the original — the
     * second copy on the phone — is handed over: that photograph is filed,
     * and saying it was thrown away buries a picture the user asked to
     * keep. This used to write "thrown away" for both, over the truth
     * [markCarriedOut] had just written seconds earlier.
     */
    fun recordSystemBin(
        photoId: Long,
        path: String,
        displayName: String,

        /** True only when the photo itself was decided against. */
        thrownAway: Boolean
    ): Result<Unit> =
        runCatching {
            database.transaction {
                insertPath(photoId, path, displayName, PathKind.SYSTEM_BIN)
                // Left alone otherwise: the copy is filed, and its category
                // is what is true of this photograph now.
                if (thrownAway) {
                    writeTruth(photoId, ReviewStatus.TRASHED, null, System.currentTimeMillis())
                }
                deleteProposal(photoId)
                Unit
            }
        }

    /**
     * Photos already handed to Android's bin.
     *
     * They are still in the archive as far as the platform is concerned — a
     * trashed file is hidden, not gone, for thirty days — so anything asking
     * "what is on this phone?" finds them and offers work already done.
     */
    fun handedToSystemBin(): Result<Set<Long>> = runCatching {
        database.query(
            "SELECT DISTINCT ${Schema.COLUMN_PHOTO_ID} AS pid FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_KIND} = ?",
            listOf(PathKind.SYSTEM_BIN.storedValue)
        ).mapNotNull { it.getLong("pid") }.toSet()
    }

    /** Every location the photo has occupied, oldest first. */
    fun loadPathHistory(photoId: Long): Result<List<Pair<Location, PathKind>>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_PATH}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                    "${Schema.COLUMN_KIND} FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_PHOTO_ID} = ? ORDER BY ${Schema.COLUMN_RECORDED_AT} ASC",
            listOf(photoId)
        ).mapNotNull { row ->
            val path = row.getString(Schema.COLUMN_PATH) ?: return@mapNotNull null
            val name = row.getString(Schema.COLUMN_DISPLAY_NAME)
            val kind = PathKind.entries
                .firstOrNull { it.storedValue == row.getString(Schema.COLUMN_KIND) }
                ?: return@mapNotNull null
            Location(path, name) to kind
        }
    }

    /**
     * How many photos are filed under [destinationId], or asked to be.
     *
     * Both tables, because a category is deleted or merged as a whole:
     * a proposal left pointing at a category that is gone could never be
     * carried out, and would sit in the queue as a request nobody can
     * grant.
     */
    fun countFor(destinationId: Long): Result<Int> = runCatching {
        DESTINATION_TABLES.sumOf { table ->
            database.query(
                "SELECT COUNT(*) AS total FROM $table " +
                        "WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                listOf(destinationId)
            ).firstOrNull()?.getInt("total") ?: 0
        }
    }

    /**
     * Files everything from one category under another, and returns how many.
     *
     * Deleting a category leaves its photos marked as filed under a category
     * that no longer exists: the decision survives its own destination and
     * points at nothing. Moving them first is what keeps that from happening,
     * and it is also what undoes a split — a phone transfer can produce two
     * Famiglia, and they were always one.
     */
    fun reassign(fromDestinationId: Long, toDestinationId: Long): Result<Int> = runCatching {
        database.transaction {
            val moved = database.execute(
                "UPDATE ${Schema.TABLE_PHOTO_STATE} SET ${Schema.COLUMN_DESTINATION_ID} = ?, " +
                        "${Schema.COLUMN_UPDATED_AT} = ? WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                listOf(toDestinationId, System.currentTimeMillis(), fromDestinationId)
            )
            // The requests too: a filing asked into the old category is
            // now a filing into the new one, still waiting to be done.
            val asked = database.execute(
                "UPDATE ${Schema.TABLE_PROPOSALS} SET ${Schema.COLUMN_DESTINATION_ID} = ? " +
                        "WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                listOf(toDestinationId, fromDestinationId)
            )
            moved + asked
        }
    }

    /**
     * Forgets every decision that pointed at [destinationId], and says how
     * many.
     *
     * Deleting a category used to leave its photos marked as filed under
     * something that no longer existed: worse than unfiled, because they were
     * not offered for review either. Those photos become unseen again, which
     * is what they are once the category holding them is gone.
     */
    fun forgetDestination(destinationId: Long): Result<Int> = runCatching {
        database.transaction {
            DESTINATION_TABLES.sumOf { table ->
                database.execute(
                    "DELETE FROM $table WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                    listOf(destinationId)
                )
            }
        }
    }

    /**
     * Forgets what is recorded about [photoIds], and says how many rows
     * went.
     *
     * The photos are never seen again, as far as the archive knows. Only
     * the truth goes: a proposal about one of them is the caller's to
     * withdraw, and nothing here decides that for it.
     */
    fun forgetAll(photoIds: List<Long>): Result<Int> = runCatching {
        database.transaction {
            photoIds.sumOf { photoId ->
                database.execute(
                    "DELETE FROM ${Schema.TABLE_PHOTO_STATE} WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                    listOf(photoId)
                )
            }
        }
    }

    /** Forgets what is recorded about one photo; see [forgetAll]. */
    fun forget(photoId: Long): Result<Int> = forgetAll(listOf(photoId))

    /**
     * Writes where a photo is now as its original location, once, the
     * first time anything is decided about it.
     *
     * Written while the photo is still where it started: this is what
     * putting it back later depends on. Proposing calls it too, since a
     * proposal is the first decision about most photos.
     */
    fun rememberOrigin(photo: PhotoRecord) {
        val known = database.query(
            "SELECT 1 AS present FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_PHOTO_ID} = ? LIMIT 1",
            listOf(photo.photoId)
        ).isNotEmpty()
        if (!known) insertPath(photo.photoId, photo.relativePath, photo.displayName, PathKind.ORIGINAL)
    }

    private fun insertPath(
        photoId: Long,
        path: String,
        displayName: String,
        kind: PathKind
    ) {
        database.insert(
            "INSERT INTO ${Schema.TABLE_PHOTO_PATHS} (${Schema.COLUMN_PHOTO_ID}, " +
                    "${Schema.COLUMN_PATH}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                    "${Schema.COLUMN_KIND}, ${Schema.COLUMN_RECORDED_AT}) " +
                    "VALUES (?, ?, ?, ?, ?)",
            listOf(photoId, path, displayName, kind.storedValue, System.currentTimeMillis())
        )
    }
}
