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
        val destinationId: Long?
    )

    /** Why a path was recorded. */
    enum class PathKind(val storedValue: String) {

        /** Where the photo was when the app first saw it. */
        ORIGINAL("original"),

        /** Where the app put it afterwards. */
        MOVED("moved")
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
                    "${Schema.COLUMN_DESTINATION_ID} FROM ${Schema.TABLE_PHOTO_STATE}"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
            val status = ReviewStatus.fromStoredValue(row.getString(Schema.COLUMN_STATUS))
                ?: return@mapNotNull null
            photoId to StoredState(photoId, status, row.getLong(Schema.COLUMN_DESTINATION_ID))
        }.toMap()
    }

    /**
     * The folder each photo was in when the app first saw it, keyed by
     * photo id. This is what restoring puts a photo back into.
     */
    fun loadOriginalPaths(): Result<Map<Long, String>> = runCatching {
        val paths = HashMap<Long, String>()
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_PATH} " +
                    "FROM ${Schema.TABLE_PHOTO_PATHS} WHERE ${Schema.COLUMN_KIND} = ? " +
                    "ORDER BY ${Schema.COLUMN_RECORDED_AT} ASC",
            listOf(PathKind.ORIGINAL.storedValue)
        ).forEach { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@forEach
            val path = row.getString(Schema.COLUMN_PATH) ?: return@forEach
            // Oldest wins: the first location recorded is the original one.
            paths.putIfAbsent(photoId, path)
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
        destinationId: Long?
    ): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                        "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                        "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}) " +
                        "VALUES (?, ?, ?, ?)",
                listOf(
                    photo.photoId, status.storedValue, destinationId,
                    System.currentTimeMillis()
                )
            )
            rememberPathIfNew(photo.photoId, photo.relativePath)
            Unit
        }
    }

    /** Appends the location a photo was moved to. */
    fun recordMovedPath(photoId: Long, path: String): Result<Unit> = runCatching {
        database.transaction {
            insertPath(photoId, path, PathKind.MOVED)
            Unit
        }
    }

    /** Every location the photo has occupied, oldest first. */
    fun loadPathHistory(photoId: Long): Result<List<Pair<String, PathKind>>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_PATH}, ${Schema.COLUMN_KIND} FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_PHOTO_ID} = ? ORDER BY ${Schema.COLUMN_RECORDED_AT} ASC",
            listOf(photoId)
        ).mapNotNull { row ->
            val path = row.getString(Schema.COLUMN_PATH) ?: return@mapNotNull null
            val kind = PathKind.entries
                .firstOrNull { it.storedValue == row.getString(Schema.COLUMN_KIND) }
                ?: return@mapNotNull null
            path to kind
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
    private fun rememberPathIfNew(photoId: Long, path: String) {
        val known = database.query(
            "SELECT 1 AS present FROM ${Schema.TABLE_PHOTO_PATHS} " +
                    "WHERE ${Schema.COLUMN_PHOTO_ID} = ? LIMIT 1",
            listOf(photoId)
        ).isNotEmpty()
        if (!known) insertPath(photoId, path, PathKind.ORIGINAL)
    }

    private fun insertPath(photoId: Long, path: String, kind: PathKind) {
        database.insert(
            "INSERT INTO ${Schema.TABLE_PHOTO_PATHS} (${Schema.COLUMN_PHOTO_ID}, " +
                    "${Schema.COLUMN_PATH}, ${Schema.COLUMN_KIND}, ${Schema.COLUMN_RECORDED_AT}) " +
                    "VALUES (?, ?, ?, ?)",
            listOf(photoId, path, kind.storedValue, System.currentTimeMillis())
        )
    }
}
