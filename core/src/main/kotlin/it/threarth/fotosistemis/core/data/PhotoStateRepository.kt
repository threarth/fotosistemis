package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.Database

/**
 * Review decisions and the location history of each photo.
 */
class PhotoStateRepository(private val database: Database) {

    /** A stored decision about one photo. */
    data class StoredState(
        val photoId: Long,
        val status: ReviewStatus,
        val destinationId: Long?,

        /** True while the decision has been taken and not yet carried out. */
        val pending: Boolean = false
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
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                    "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_PENDING} " +
                    "FROM ${Schema.TABLE_PHOTO_STATE}"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
            val status = ReviewStatus.fromStoredValue(row.getString(Schema.COLUMN_STATUS))
                ?: return@mapNotNull null
            photoId to StoredState(
                photoId,
                status,
                row.getLong(Schema.COLUMN_DESTINATION_ID),
                pending = (row.getLong(Schema.COLUMN_PENDING) ?: 0L) == 1L
            )
        }.toMap()
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
     * Records a decision, replacing any earlier one for the same photo, and
     * remembers where the photo was at that moment.
     */
    fun record(
        photo: PhotoRecord,
        status: ReviewStatus,
        destinationId: Long?,

        /**
         * False when the decision is already carried out as it is taken.
         *
         * Keeping a photo where it is moves nothing, and neither does
         * filing a folder into a category it already sits in. Those are
         * done the moment they are decided; only work that needs a file to
         * move is owed.
         */
        pending: Boolean = true
    ): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                        "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                        "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}, " +
                        "${Schema.COLUMN_PENDING}) VALUES (?, ?, ?, ?, ?)",
                listOf(
                    photo.photoId, status.storedValue, destinationId,
                    System.currentTimeMillis(), if (pending) 1 else 0
                )
            )
            // Written now, while the photo is still where it started: this
            // is what putting it back later depends on.
            rememberPathIfNew(photo.photoId, photo.relativePath, photo.displayName)
            Unit
        }
    }

    /**
     * Marks a decision as carried out, together with where the photo ended
     * up and how it got there — in one transaction.
     *
     * Three writes describing one event: the archive's idea of where the
     * photo is, the record of the move, and the fact that the work is done.
     * Apart, a death between them leaves a photo said to be filed whose
     * folder was never updated, or a move recorded twice. Together they
     * either all describe what happened or none of them does.
     */
    fun markCarriedOut(
        photoId: Long,
        relativePath: String,
        displayName: String,

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
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTO_STATE} SET ${Schema.COLUMN_PENDING} = 0 " +
                        "WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                listOf(photoId)
            )
            Unit
        }
    }



    /**
     * Throws away every decision not yet carried out, and says how many.
     *
     * Only those: what has already happened is not in the queue and is not
     * the queue's to undo. Deleting the rows returns the database to what
     * it was before those decisions were taken, which is what discarding
     * has always promised.
     */
    fun discardPending(): Result<Int> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_PHOTO_STATE} WHERE ${Schema.COLUMN_PENDING} = 1"
            )
        }
    }

    /**
     * Records the same decision about many photos, and says how many.
     *
     * One transaction for the lot: filing a folder is one act as far as the
     * user is concerned, and half a folder filed because something failed
     * partway would be worse than none. Each photo's original location is
     * remembered here too, exactly as when deciding one at a time.
     */
    fun recordAll(
        photos: List<PhotoRecord>,
        status: ReviewStatus,
        destinationId: Long?,

        /** False when the photos are already where the decision puts them. */
        pending: Boolean = true
    ): Result<Int> = runCatching {
        if (photos.isEmpty()) return@runCatching 0

        database.transaction {
            val now = System.currentTimeMillis()
            for (photo in photos) {
                database.execute(
                    "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                            "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                            "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}, " +
                            "${Schema.COLUMN_PENDING}) VALUES (?, ?, ?, ?, ?)",
                    listOf(
                        photo.photoId, status.storedValue, destinationId, now,
                        if (pending) 1 else 0
                    )
                )
                rememberPathIfNew(photo.photoId, photo.relativePath, photo.displayName)
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
     * what it can do is stop pretending the decision is still pending.
     */
    fun recordSystemBin(photoId: Long, path: String, displayName: String): Result<Unit> =
        runCatching {
            database.transaction {
                insertPath(photoId, path, displayName, PathKind.SYSTEM_BIN)
                database.execute(
                    "UPDATE ${Schema.TABLE_PHOTO_STATE} SET ${Schema.COLUMN_PENDING} = 0 " +
                            "WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                    listOf(photoId)
                )
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

    /** How many photos are filed under [destinationId]. */
    fun countFor(destinationId: Long): Result<Int> = runCatching {
        database.query(
            "SELECT COUNT(*) AS total FROM ${Schema.TABLE_PHOTO_STATE} " +
                    "WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
            listOf(destinationId)
        ).firstOrNull()?.getInt("total") ?: 0
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
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTO_STATE} SET ${Schema.COLUMN_DESTINATION_ID} = ?, " +
                        "${Schema.COLUMN_UPDATED_AT} = ? WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                listOf(toDestinationId, System.currentTimeMillis(), fromDestinationId)
            )
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
            database.execute(
                "DELETE FROM ${Schema.TABLE_PHOTO_STATE} " +
                        "WHERE ${Schema.COLUMN_DESTINATION_ID} = ?",
                listOf(destinationId)
            )
        }
    }

    /**
     * Forgets the decisions about [photoIds], making them unseen again.
     *
     * Emptying a queue has to reach the decisions themselves: they are
     * written the instant they are taken, so a queue discarded without being
     * applied would otherwise leave every one of them standing.
     */
    fun forgetAll(photoIds: List<Long>): Result<Int> = runCatching {
        database.transaction {
            var forgotten = 0
            for (photoId in photoIds) {
                forgotten += database.execute(
                    "DELETE FROM ${Schema.TABLE_PHOTO_STATE} " +
                            "WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                    listOf(photoId)
                )
            }
            forgotten
        }
    }

    /** Removes the decision for one photo, making it unseen again. */
    fun forget(photoId: Long): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_PHOTO_STATE} WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                listOf(photoId)
            )
            Unit
        }
    }

    /** Writes the original location once, on the first decision about a photo. */
    private fun rememberPathIfNew(photoId: Long, path: String, displayName: String) {
        val known = database.query(
            "SELECT 1 AS present FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_PHOTO_ID} = ? LIMIT 1",
            listOf(photoId)
        ).isNotEmpty()
        if (!known) insertPath(photoId, path, displayName, PathKind.ORIGINAL)
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
