package it.threarth.fotosistemis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_DISPLAY_NAME
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_MEDIA_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_SIZE_BYTES
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_STATUS
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_TAG
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_UPDATED_AT
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_PHOTO_STATE

/**
 * Reads and writes review decisions.
 *
 * Every write runs inside an explicit transaction so that a failure midway
 * through a batch leaves no partial record of decisions.
 */
class PhotoStateRepository(context: Context) {

    private val helper = PhotoStateDatabase(context.applicationContext)

    /** A stored decision about one photo. */
    data class StoredState(
        val mediaId: Long,
        val status: ReviewStatus,
        val tag: String?
    )

    /**
     * Loads every decision, keyed by MediaStore id.
     *
     * The whole table is read at once because filtering photos means checking
     * membership for hundreds of ids: one query plus in-memory lookups beats
     * one query per photo. The table holds one short row per reviewed photo,
     * so it stays small enough to keep in memory.
     */
    fun loadAll(): Result<Map<Long, StoredState>> = try {
        val states = HashMap<Long, StoredState>()
        helper.readableDatabase.query(
            TABLE_PHOTO_STATE,
            arrayOf(COLUMN_MEDIA_ID, COLUMN_STATUS, COLUMN_TAG),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(0)
                val status = ReviewStatus.fromStoredValue(cursor.getString(1))
                if (status != null) {
                    states[mediaId] = StoredState(mediaId, status, cursor.getString(2))
                }
            }
        }
        Result.success(states)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Records one decision, replacing any earlier decision for the same photo.
     * A photo reviewed twice keeps only its latest status.
     */
    fun record(
        photo: MediaStoreRepository.Photo,
        status: ReviewStatus,
        tag: String?
    ): Result<Unit> = writeInTransaction { db ->
        db.insertWithOnConflict(
            TABLE_PHOTO_STATE,
            null,
            buildValues(photo, status, tag),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Records a whole batch of decisions atomically. */
    fun recordAll(decisions: List<Triple<MediaStoreRepository.Photo, ReviewStatus, String?>>):
            Result<Unit> = writeInTransaction { db ->
        for ((photo, status, tag) in decisions) {
            db.insertWithOnConflict(
                TABLE_PHOTO_STATE,
                null,
                buildValues(photo, status, tag),
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /** Removes the decision for one photo, making it unseen again. */
    fun forget(mediaId: Long): Result<Unit> = writeInTransaction { db ->
        db.delete(TABLE_PHOTO_STATE, "$COLUMN_MEDIA_ID = ?", arrayOf(mediaId.toString()))
    }

    /** Row contents for one decision. */
    private fun buildValues(
        photo: MediaStoreRepository.Photo,
        status: ReviewStatus,
        tag: String?
    ) = ContentValues().apply {
        put(COLUMN_MEDIA_ID, photo.mediaId)
        put(COLUMN_DISPLAY_NAME, photo.displayName)
        put(COLUMN_SIZE_BYTES, photo.sizeBytes)
        put(COLUMN_STATUS, status.storedValue)
        put(COLUMN_TAG, tag)
        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
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
