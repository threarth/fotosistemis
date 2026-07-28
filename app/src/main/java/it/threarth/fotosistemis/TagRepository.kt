package it.threarth.fotosistemis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_MEDIA_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_NAME
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.COLUMN_TAG_ID
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_PHOTO_TAGS
import it.threarth.fotosistemis.PhotoStateDatabase.Companion.TABLE_TAGS
import java.util.Locale

/**
 * Free-text tags, many per photo.
 *
 * Tags carry the meaning that folders cannot: a photo lives in one folder but
 * can be about several things at once. They stay in the database rather than
 * in file names, so adding or removing one is an UPDATE instead of a file
 * rename that would disturb backup services.
 */
class TagRepository(context: Context) {

    private val helper = PhotoStateDatabase(context.applicationContext)

    private companion object {

        /** Longest tag accepted, after normalisation. */
        const val MAX_TAG_LENGTH = 60
    }

    /** All tags currently in use, alphabetically. */
    fun loadAllNames(): Result<List<String>> = try {
        val names = ArrayList<String>()
        helper.readableDatabase
            .query(TABLE_TAGS, arrayOf(COLUMN_NAME), null, null, null, null, "$COLUMN_NAME ASC")
            .use { cursor -> while (cursor.moveToNext()) names.add(cursor.getString(0)) }
        Result.success(names)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** Tags assigned to every photo, keyed by MediaStore id. */
    fun loadAssignments(): Result<Map<Long, List<String>>> = try {
        val assignments = HashMap<Long, MutableList<String>>()
        val sql = "SELECT pt.$COLUMN_MEDIA_ID, t.$COLUMN_NAME " +
                "FROM $TABLE_PHOTO_TAGS pt " +
                "JOIN $TABLE_TAGS t ON t.$COLUMN_ID = pt.$COLUMN_TAG_ID " +
                "ORDER BY t.$COLUMN_NAME ASC"
        helper.readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                assignments.getOrPut(cursor.getLong(0)) { ArrayList() }.add(cursor.getString(1))
            }
        }
        Result.success(assignments)
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Attaches [rawName] to [mediaId], creating the tag if it is new.
     * Assigning the same tag twice is a no-op rather than an error.
     */
    fun assign(mediaId: Long, rawName: String): Result<String> {
        val name = normalise(rawName)
            ?: return Result.failure(IllegalArgumentException("Tag non valido: $rawName"))

        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val tagId = findOrCreateTag(db, name)
            db.insertWithOnConflict(
                TABLE_PHOTO_TAGS,
                null,
                ContentValues().apply {
                    put(COLUMN_MEDIA_ID, mediaId)
                    put(COLUMN_TAG_ID, tagId)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            db.setTransactionSuccessful()
            Result.success(name)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            db.endTransaction()
        }
    }

    /** Detaches a tag from one photo. The tag itself survives. */
    fun unassign(mediaId: Long, name: String): Result<Unit> {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val sql = "DELETE FROM $TABLE_PHOTO_TAGS " +
                    "WHERE $COLUMN_MEDIA_ID = ? AND $COLUMN_TAG_ID = " +
                    "(SELECT $COLUMN_ID FROM $TABLE_TAGS WHERE $COLUMN_NAME = ?)"
            db.execSQL(sql, arrayOf(mediaId.toString(), name))
            db.setTransactionSuccessful()
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            db.endTransaction()
        }
    }

    /** Returns the id of [name], inserting the tag when it does not exist. */
    private fun findOrCreateTag(db: SQLiteDatabase, name: String): Long {
        db.query(TABLE_TAGS, arrayOf(COLUMN_ID), "$COLUMN_NAME = ?", arrayOf(name), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return db.insertOrThrow(TABLE_TAGS, null, ContentValues().apply { put(COLUMN_NAME, name) })
    }

    /**
     * Trims and lowercases, so that "Festa Tommy" and "festa tommy" are the
     * same tag. Returns null when nothing usable is left.
     */
    private fun normalise(rawName: String): String? {
        val name = rawName.trim().lowercase(Locale.ITALY).take(MAX_TAG_LENGTH)
        return name.ifEmpty { null }
    }
}
