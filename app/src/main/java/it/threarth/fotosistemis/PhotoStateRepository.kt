package it.threarth.fotosistemis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_DESTINATION_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_DISPLAY_NAME
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_KIND
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_MEDIA_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_PATH
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_RECORDED_AT
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_SIZE_BYTES
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_STATUS
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_UPDATED_AT
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_PHOTO_PATHS
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_PHOTO_STATE

/**
 * Review decisions and the location history of each photo.
 *
 * Every write runs inside an explicit transaction, so a failure midway
 * through a batch leaves no partial record.
 */
class PhotoStateRepository(context: Context) {

    private val helper = PhotoStateDatabase(context.applicationContext)

    /** A stored decision about one photo. */
    data class StoredState(
        val mediaId: Long,
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
     * Loads every decision, keyed by MediaStore id.
     *
     * Read in one go because filtering means membership checks for hundreds
     * of ids: one query plus in-memory lookups beats one query per photo.
     */
    fun loadAll(): Result<Map<Long, StoredState>> = try {
        val states = HashMap<Long, StoredState>()
        helper.readableDatabase.query(
            TABLE_PHOTO_STATE,
            arrayOf(COLUMN_MEDIA_ID, COLUMN_STATUS, COLUMN_DESTINATION_ID),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val status = ReviewStatus.fromStoredValue(cursor.getString(1)) ?: continue
                val mediaId = cursor.getLong(0)
                val destinationId = if (cursor.isNull(2)) null else cursor.getLong(2)
                states[mediaId] = StoredState(mediaId, status, destinationId)
            }
        }
        Result.success(states)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Records a decision, replacing any earlier one for the same photo, and
     * remembers where the photo was at that moment.
     */
    fun record(
        photo: MediaStoreRepository.Photo,
        status: ReviewStatus,
        destinationId: Long?
    ): Result<Unit> = writeInTransaction { db ->
        db.insertWithOnConflict(
            TABLE_PHOTO_STATE,
            null,
            ContentValues().apply {
                put(COLUMN_MEDIA_ID, photo.mediaId)
                put(COLUMN_DISPLAY_NAME, photo.displayName)
                put(COLUMN_SIZE_BYTES, photo.sizeBytes)
                put(COLUMN_STATUS, status.storedValue)
                put(COLUMN_DESTINATION_ID, destinationId)
                put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        rememberPathIfNew(db, photo.mediaId, photo.relativePath, PathKind.ORIGINAL)
    }

    /** Appends the location a photo was moved to. */
    fun recordMovedPath(mediaId: Long, path: String): Result<Unit> = writeInTransaction { db ->
        insertPath(db, mediaId, path, PathKind.MOVED)
    }

    /** Every location the photo has occupied, oldest first. */
    fun loadPathHistory(mediaId: Long): Result<List<Pair<String, PathKind>>> = try {
        val history = ArrayList<Pair<String, PathKind>>()
        helper.readableDatabase.query(
            TABLE_PHOTO_PATHS,
            arrayOf(COLUMN_PATH, COLUMN_KIND),
            "$COLUMN_MEDIA_ID = ?",
            arrayOf(mediaId.toString()),
            null, null, "$COLUMN_RECORDED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = PathKind.entries.firstOrNull { it.storedValue == cursor.getString(1) }
                if (kind != null) history.add(cursor.getString(0) to kind)
            }
        }
        Result.success(history)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * The folder each photo was in when the app first saw it, keyed by
     * MediaStore id. This is what "restore" puts a photo back into.
     */
    fun loadOriginalPaths(): Result<Map<Long, String>> = try {
        val paths = HashMap<Long, String>()
        helper.readableDatabase.query(
            TABLE_PHOTO_PATHS,
            arrayOf(COLUMN_MEDIA_ID, COLUMN_PATH),
            "$COLUMN_KIND = ?",
            arrayOf(PathKind.ORIGINAL.storedValue),
            null, null, "$COLUMN_RECORDED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                // Oldest wins: putIfAbsent keeps the first location recorded.
                paths.putIfAbsent(cursor.getLong(0), cursor.getString(1))
            }
        }
        Result.success(paths)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** Removes the decision for one photo, making it unseen again. */
    fun forget(mediaId: Long): Result<Unit> = writeInTransaction { db ->
        db.delete(TABLE_PHOTO_STATE, "$COLUMN_MEDIA_ID = ?", arrayOf(mediaId.toString()))
    }

    /** Writes the original location once, on the first decision about a photo. */
    private fun rememberPathIfNew(db: SQLiteDatabase, mediaId: Long, path: String, kind: PathKind) {
        val alreadyKnown = db.query(
            TABLE_PHOTO_PATHS,
            arrayOf(COLUMN_MEDIA_ID),
            "$COLUMN_MEDIA_ID = ?",
            arrayOf(mediaId.toString()),
            null, null, null, "1"
        ).use { it.moveToFirst() }
        if (!alreadyKnown) insertPath(db, mediaId, path, kind)
    }

    private fun insertPath(db: SQLiteDatabase, mediaId: Long, path: String, kind: PathKind) {
        db.insertOrThrow(
            TABLE_PHOTO_PATHS,
            null,
            ContentValues().apply {
                put(COLUMN_MEDIA_ID, mediaId)
                put(COLUMN_PATH, path)
                put(COLUMN_KIND, kind.storedValue)
                put(COLUMN_RECORDED_AT, System.currentTimeMillis())
            }
        )
    }

    /**
     * Runs [block] inside a transaction, rolling back on any failure.
     * Errors are returned rather than thrown: callers must handle them.
     */
    private fun writeInTransaction(block: (SQLiteDatabase) -> Unit): Result<Unit> {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            block(db)
            db.setTransactionSuccessful()
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            db.endTransaction()
        }
    }
}
