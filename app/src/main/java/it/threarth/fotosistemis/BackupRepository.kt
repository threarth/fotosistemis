package it.threarth.fotosistemis

import it.threarth.fotosistemis.core.data.Schema
import it.threarth.fotosistemis.core.port.Database
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
 * JSON rather than a copy of the database file: it survives a change of
 * schema, can be read and repaired by hand, and needs no dependency, since
 * org.json ships with Android.
 *
 * Still in the Android module for that last reason: a desktop build would
 * need its own JSON writer, and inventing one before there is a desktop
 * build would be guesswork.
 */
class BackupRepository(
    private val database: Database,
    private val settings: AppSettings
) {

    private companion object {

        /**
         * Format of the backup itself, not of the database.
         *
         * Raised to 2 with the photos inventory: a v1 file keys decisions by
         * platform id, which no longer identifies anything, so restoring one
         * would silently attach them to the wrong photographs.
         */
        const val BACKUP_VERSION = 2

        const val KEY_VERSION = "backupVersion"
        const val KEY_EXPORTED_AT = "exportedAt"
        const val KEY_YEAR_PATTERN = "yearFolderPattern"

        /** Tables copied, in an order that reads naturally when inspected. */
        val TABLES = listOf(
            Schema.TABLE_PHOTOS,
            Schema.TABLE_DESTINATIONS,
            Schema.TABLE_PHOTO_STATE,
            Schema.TABLE_TAGS,
            Schema.TABLE_PHOTO_TAGS,
            Schema.TABLE_PHOTO_PATHS
        )

        /** Columns to read back, per table, in the order they are inserted. */
        val COLUMNS = mapOf(
            Schema.TABLE_PHOTOS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_MEDIA_ID, Schema.COLUMN_VOLUME_NAME,
                Schema.COLUMN_DISPLAY_NAME, Schema.COLUMN_RELATIVE_PATH,
                Schema.COLUMN_SIZE_BYTES, Schema.COLUMN_DATE_TAKEN, Schema.COLUMN_DATE_SOURCE,
                Schema.COLUMN_MEDIA_TYPE, Schema.COLUMN_WIDTH, Schema.COLUMN_HEIGHT,
                Schema.COLUMN_DURATION_MILLIS, Schema.COLUMN_CONTENT_HASH,
                Schema.COLUMN_FIRST_SEEN_AT, Schema.COLUMN_LAST_SEEN_AT,
                Schema.COLUMN_MISSING_SINCE
            ),
            Schema.TABLE_DESTINATIONS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_LABEL, Schema.COLUMN_RELATIVE_PATH,
                Schema.COLUMN_YEAR_SUBFOLDER, Schema.COLUMN_SORT_ORDER
            ),
            Schema.TABLE_PHOTO_STATE to listOf(
                Schema.COLUMN_PHOTO_ID, Schema.COLUMN_STATUS,
                Schema.COLUMN_DESTINATION_ID, Schema.COLUMN_UPDATED_AT
            ),
            Schema.TABLE_TAGS to listOf(Schema.COLUMN_ID, Schema.COLUMN_NAME),
            Schema.TABLE_PHOTO_TAGS to listOf(Schema.COLUMN_PHOTO_ID, Schema.COLUMN_TAG_ID),
            Schema.TABLE_PHOTO_PATHS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_PHOTO_ID, Schema.COLUMN_PATH,
                Schema.COLUMN_KIND, Schema.COLUMN_RECORDED_AT
            )
        )
    }

    /** Summary of what a backup contains, for reporting to the user. */
    data class Summary(val rowCount: Int, val tableCount: Int)

    /** Writes every table to [output] as JSON. */
    fun exportTo(output: OutputStream): Result<Summary> = runCatching {
        val document = JSONObject()
        document.put(KEY_VERSION, BACKUP_VERSION)
        document.put(KEY_EXPORTED_AT, System.currentTimeMillis())
        document.put(KEY_YEAR_PATTERN, settings.yearFolderPattern)

        var rows = 0
        for (table in TABLES) {
            val dumped = dumpTable(table)
            rows += dumped.length()
            document.put(table, dumped)
        }

        output.bufferedWriter().use { it.write(document.toString(2)) }
        Summary(rows, TABLES.size)
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
    fun importFrom(input: InputStream): Result<Summary> = runCatching {
        val text = input.bufferedReader().use { it.readText() }
        val document = JSONObject(text)
        val version = document.optInt(KEY_VERSION)
        require(version == BACKUP_VERSION) {
            if (version == 1) {
                "Backup della versione precedente: le decisioni vi sono legate " +
                        "all'identificatore di sistema e non sono piu' riconducibili " +
                        "alle foto giuste."
            } else {
                "Formato di backup non riconosciuto"
            }
        }

        val rows = database.transaction {
            var restored = 0
            for (table in TABLES) {
                database.execute("DELETE FROM $table")
                restored += restoreTable(table, document.optJSONArray(table))
            }
            restored
        }

        document.optString(KEY_YEAR_PATTERN).takeIf { it.isNotBlank() }
            ?.let { settings.yearFolderPattern = it }
        Summary(rows, TABLES.size)
    }

    /** Reads a whole table into JSON objects keyed by column name. */
    private fun dumpTable(table: String): JSONArray {
        val columns = COLUMNS.getValue(table)
        val rows = JSONArray()
        database.query("SELECT ${columns.joinToString(", ")} FROM $table").forEach { row ->
            val entry = JSONObject()
            for (column in columns) row.getString(column)?.let { entry.put(column, it) }
            rows.put(entry)
        }
        return rows
    }

    /** Inserts the rows of [values] into [table], returning how many. */
    private fun restoreTable(table: String, values: JSONArray?): Int {
        if (values == null) return 0
        val columns = COLUMNS.getValue(table)
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT OR REPLACE INTO $table (${columns.joinToString(", ")}) " +
                "VALUES ($placeholders)"

        for (index in 0 until values.length()) {
            val row = values.getJSONObject(index)
            database.execute(sql, columns.map { column ->
                if (row.has(column)) row.getString(column) else null
            })
        }
        return values.length()
    }
}
