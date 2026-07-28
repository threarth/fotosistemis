package it.threarth.fotosistemis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_LABEL
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_RELATIVE_PATH
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_SORT_ORDER
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_YEAR_SUBFOLDER
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_DESTINATIONS
import java.util.Calendar

/**
 * CRUD over the folders a photo can be filed into.
 *
 * A destination is an arbitrary relative path, not necessarily a child of
 * wherever the photo currently lives: on Android the app can file a photo
 * anywhere on the volume, which the web version could not do.
 */
class DestinationRepository(context: Context) {

    private val helper = PhotoStateDatabase(context.applicationContext)

    /**
     * One filing target.
     *
     * [yearSubfolder] appends the capture year, so a 2024 photo filed under
     * Pictures/Famiglia lands in Pictures/Famiglia/2024. The year is the one
     * dimension that is never ambiguous, which is why it is a folder level
     * and everything finer is a tag.
     */
    data class Destination(
        val id: Long,
        val label: String,
        val relativePath: String,
        val yearSubfolder: Boolean,
        val sortOrder: Int
    ) {

        /** Full RELATIVE_PATH for a photo taken at [captureMillis]. */
        fun pathFor(captureMillis: Long): String {
            val trimmed = relativePath.trim('/')
            if (!yearSubfolder) return "$trimmed/"
            val year = Calendar.getInstance().apply { timeInMillis = captureMillis }
                .get(Calendar.YEAR)
            return "$trimmed/$year/"
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            COLUMN_ID, COLUMN_LABEL, COLUMN_RELATIVE_PATH,
            COLUMN_YEAR_SUBFOLDER, COLUMN_SORT_ORDER
        )
        const val ORDER_BY = "$COLUMN_SORT_ORDER ASC, $COLUMN_LABEL ASC"
    }

    /** Every destination, in display order. */
    fun loadAll(): Result<List<Destination>> = try {
        val destinations = ArrayList<Destination>()
        helper.readableDatabase
            .query(TABLE_DESTINATIONS, PROJECTION, null, null, null, null, ORDER_BY)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    destinations.add(
                        Destination(
                            id = cursor.getLong(0),
                            label = cursor.getString(1),
                            relativePath = cursor.getString(2),
                            yearSubfolder = cursor.getInt(3) != 0,
                            sortOrder = cursor.getInt(4)
                        )
                    )
                }
            }
        Result.success(destinations)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** Adds a destination and returns its new id. */
    fun insert(label: String, relativePath: String, yearSubfolder: Boolean): Result<Long> =
        inTransaction { db ->
            db.insertOrThrow(
                TABLE_DESTINATIONS,
                null,
                valuesOf(label, relativePath, yearSubfolder)
            )
        }

    /** Updates an existing destination in place. */
    fun update(
        id: Long,
        label: String,
        relativePath: String,
        yearSubfolder: Boolean
    ): Result<Long> = inTransaction { db ->
        db.update(
            TABLE_DESTINATIONS,
            valuesOf(label, relativePath, yearSubfolder),
            "$COLUMN_ID = ?",
            arrayOf(id.toString())
        ).toLong()
    }

    /**
     * Removes a destination. Photos already filed there keep their files:
     * only the shortcut disappears, never the photos.
     */
    fun delete(id: Long): Result<Long> = inTransaction { db ->
        db.delete(TABLE_DESTINATIONS, "$COLUMN_ID = ?", arrayOf(id.toString())).toLong()
    }

    private fun valuesOf(label: String, relativePath: String, yearSubfolder: Boolean) =
        ContentValues().apply {
            put(COLUMN_LABEL, label.trim())
            put(COLUMN_RELATIVE_PATH, relativePath.trim().trim('/'))
            put(COLUMN_YEAR_SUBFOLDER, if (yearSubfolder) 1 else 0)
        }

    /** Runs [block] in a transaction, rolling back on failure. */
    private fun inTransaction(block: (SQLiteDatabase) -> Long): Result<Long> {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val outcome = block(db)
            db.setTransactionSuccessful()
            Result.success(outcome)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            db.endTransaction()
        }
    }
}
