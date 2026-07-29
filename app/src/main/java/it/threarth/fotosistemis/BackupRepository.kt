package it.threarth.fotosistemis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes and reads a full copy of the local database.
 *
 * The database holds the only record of work that cannot be reconstructed
 * from the photos themselves: which ones have been reviewed, their tags, and
 * where each came from. Reinstalling over the app preserves it, but
 * uninstalling, clearing app data, or a change of signing key all destroy it
 * without warning, so a copy the user keeps is the only real protection.
 *
 * JSON rather than a copy of the .db file: it survives a change of schema,
 * can be read and repaired by hand, and needs no dependency, since org.json
 * ships with Android.
 */
class BackupRepository(context: Context) {

    private val helper = PhotoStateDatabase(context.applicationContext)
    private val settings = AppSettings(context)

    private companion object {

        /** Format of the backup itself, not of the database. */
        const val BACKUP_VERSION = 1

        const val KEY_VERSION = "backupVersion"
        const val KEY_EXPORTED_AT = "exportedAt"
        const val KEY_YEAR_PATTERN = "yearFolderPattern"

        /** Tables copied, in an order that reads naturally when inspected. */
        val TABLES = listOf(
            PhotoStateDatabase.TABLE_DESTINATIONS,
            PhotoStateDatabase.TABLE_PHOTO_STATE,
            PhotoStateDatabase.TABLE_TAGS,
            PhotoStateDatabase.TABLE_PHOTO_TAGS,
            PhotoStateDatabase.TABLE_PHOTO_PATHS
        )
    }

    /** Summary of what a backup contains, for reporting to the user. */
    data class Summary(val rowCount: Int, val tableCount: Int)

    /** Writes every table to [output] as JSON. */
    fun exportTo(output: OutputStream): Result<Summary> = try {
        val document = JSONObject()
        document.put(KEY_VERSION, BACKUP_VERSION)
        document.put(KEY_EXPORTED_AT, System.currentTimeMillis())
        document.put(KEY_YEAR_PATTERN, settings.yearFolderPattern)

        var rows = 0
        val database = helper.readableDatabase
        for (table in TABLES) {
            val dumped = dumpTable(database, table)
            rows += dumped.length()
            document.put(table, dumped)
        }

        output.bufferedWriter().use { it.write(document.toString(2)) }
        Result.success(Summary(rows, TABLES.size))
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Replaces the whole database with the contents of [input].
     *
     * A restore is a snapshot, not a merge: reconciling two divergent
     * histories of the same photos would need rules the user never stated,
     * and guessing them silently is worse than replacing what is there.
     * Everything happens in one transaction, so a malformed file leaves the
     * existing data untouched.
     */
    fun importFrom(input: InputStream): Result<Summary> {
        val database = helper.writableDatabase
        return try {
            val text = input.bufferedReader().use { it.readText() }
            val document = JSONObject(text)
            require(document.optInt(KEY_VERSION) == BACKUP_VERSION) {
                "Formato di backup non riconosciuto"
            }

            database.beginTransaction()
            var rows = 0
            for (table in TABLES) {
                database.delete(table, null, null)
                rows += restoreTable(database, table, document.optJSONArray(table))
            }
            database.setTransactionSuccessful()

            document.optString(KEY_YEAR_PATTERN).takeIf { it.isNotBlank() }
                ?.let { settings.yearFolderPattern = it }
            Result.success(Summary(rows, TABLES.size))
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            if (database.inTransaction()) database.endTransaction()
        }
    }

    /** Reads a whole table, using the column names the cursor reports. */
    private fun dumpTable(database: SQLiteDatabase, table: String): JSONArray {
        val rows = JSONArray()
        database.query(table, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (index in 0 until cursor.columnCount) {
                    if (!cursor.isNull(index)) {
                        row.put(cursor.getColumnName(index), cursor.getString(index))
                    }
                }
                rows.put(row)
            }
        }
        return rows
    }

    /** Inserts the rows of [values] into [table], returning how many. */
    private fun restoreTable(
        database: SQLiteDatabase,
        table: String,
        values: JSONArray?
    ): Int {
        if (values == null) return 0
        for (index in 0 until values.length()) {
            val row = values.getJSONObject(index)
            val content = ContentValues()
            for (name in row.keys()) content.put(name, row.getString(name))
            database.insertWithOnConflict(
                table, null, content, SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        return values.length()
    }
}
