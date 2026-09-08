package it.threarth.fotosistemis

import it.threarth.fotosistemis.core.data.Schema
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus
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
         *
         * Not raised since, though tables and columns have been added: a
         * file missing them still restores, with the table left empty and
         * the column at its schema default, which is what the data meant
         * before the column existed.
         */
        const val BACKUP_VERSION = 2

        const val KEY_VERSION = "backupVersion"
        const val KEY_EXPORTED_AT = "exportedAt"
        const val KEY_YEAR_PATTERN = "yearFolderPattern"

        /**
         * Columns a v10/v11 backup carried on each decision, before the
         * pending ones were lifted into proposals (schema v12). Read here
         * only to convert such a file; the schema no longer has them.
         */
        const val OBSOLETE_PENDING = "pending"
        const val OBSOLETE_PREVIOUS_STATUS = "previous_status"
        const val OBSOLETE_PREVIOUS_DESTINATION_ID = "previous_destination_id"
        const val PENDING_TRUE = "1"

        /**
         * Tables copied, in an order that reads naturally when inspected.
         *
         * Everything the user has told the app, and nothing the app can
         * find out again by reading the phone: the sync bookkeeping is
         * left out because the next start rebuilds it.
         */
        val TABLES = listOf(
            Schema.TABLE_PHOTOS,
            Schema.TABLE_DESTINATIONS,
            Schema.TABLE_PHOTO_STATE,
            Schema.TABLE_PROPOSALS,
            Schema.TABLE_TAGS,
            Schema.TABLE_PHOTO_TAGS,
            Schema.TABLE_PHOTO_PATHS,
            Schema.TABLE_PHOTO_RATINGS,
            Schema.TABLE_IGNORED
        )

        /** Columns to read back, per table, in the order they are inserted. */
        val COLUMNS = mapOf(
            Schema.TABLE_PHOTOS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_MEDIA_ID, Schema.COLUMN_VOLUME_NAME,
                Schema.COLUMN_DISPLAY_NAME, Schema.COLUMN_RELATIVE_PATH,
                Schema.COLUMN_SIZE_BYTES, Schema.COLUMN_DATE_TAKEN, Schema.COLUMN_DATE_SOURCE,
                Schema.COLUMN_MEDIA_TYPE, Schema.COLUMN_WIDTH, Schema.COLUMN_HEIGHT,
                Schema.COLUMN_DURATION_MILLIS, Schema.COLUMN_CONTENT_HASH,
                // Left out until 8 September 2026, and a restore lost every
                // one of the 23.703 fingerprints read from the archive: the
                // duplicate search fell back to length plus head-of-file
                // until each photo had been opened and read again. It is
                // not something the phone gives back for free, which is the
                // whole test for what belongs in a backup.
                Schema.COLUMN_IMAGE_HASH,
                Schema.COLUMN_FIRST_SEEN_AT, Schema.COLUMN_LAST_SEEN_AT,
                Schema.COLUMN_MISSING_SINCE, Schema.COLUMN_DATE_SUSPECT
            ),
            Schema.TABLE_DESTINATIONS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_LABEL, Schema.COLUMN_RELATIVE_PATH,
                Schema.COLUMN_YEAR_SUBFOLDER, Schema.COLUMN_SORT_ORDER
            ),
            // The truth about each photo, and apart from it what is still
            // asked: a backup that folded the two together would restore
            // work still owed as though it had been done, and the files
            // would never move.
            Schema.TABLE_PHOTO_STATE to listOf(
                Schema.COLUMN_PHOTO_ID, Schema.COLUMN_STATUS,
                Schema.COLUMN_DESTINATION_ID, Schema.COLUMN_UPDATED_AT
            ),
            Schema.TABLE_PROPOSALS to listOf(
                Schema.COLUMN_PHOTO_ID, Schema.COLUMN_ACTION,
                Schema.COLUMN_DESTINATION_ID, Schema.COLUMN_PROPOSED_AT
            ),
            Schema.TABLE_TAGS to listOf(Schema.COLUMN_ID, Schema.COLUMN_NAME),
            Schema.TABLE_PHOTO_TAGS to listOf(Schema.COLUMN_PHOTO_ID, Schema.COLUMN_TAG_ID),
            // With the name each path was recorded under: without it a
            // restore from the bin would put the folder back and leave the
            // stamped name, and the photo would count as still away.
            Schema.TABLE_PHOTO_PATHS to listOf(
                Schema.COLUMN_ID, Schema.COLUMN_PHOTO_ID, Schema.COLUMN_PATH,
                Schema.COLUMN_DISPLAY_NAME, Schema.COLUMN_KIND, Schema.COLUMN_RECORDED_AT
            ),
            Schema.TABLE_PHOTO_RATINGS to listOf(
                Schema.COLUMN_PHOTO_ID, Schema.COLUMN_STARS, Schema.COLUMN_UPDATED_AT
            ),
            Schema.TABLE_IGNORED to listOf(
                Schema.COLUMN_PHOTO_ID, Schema.COLUMN_CHECK, Schema.COLUMN_RECORDED_AT
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
            // Older files keep the work still owed in other shapes, or not
            // at all; the same answers the migrations gave are given here.
            when {
                document.has(Schema.TABLE_PROPOSALS) -> Unit
                carriesPending(document) ->
                    liftPendingDecisions(document.getJSONArray(Schema.TABLE_PHOTO_STATE))
                else -> Schema.proposeUnfinishedWork(database)
            }
            restored
        }

        document.optString(KEY_YEAR_PATTERN).takeIf { it.isNotBlank() }
            ?.let { settings.yearFolderPattern = it }
        Summary(rows, TABLES.size)
    }

    /**
     * True when the decisions in [document] were written by schema v10 or
     * v11, which marked each one as done or still pending.
     */
    private fun carriesPending(document: JSONObject): Boolean {
        val states = document.optJSONArray(Schema.TABLE_PHOTO_STATE) ?: return false
        if (states.length() == 0) return false
        return states.getJSONObject(0).has(OBSOLETE_PENDING)
    }

    /**
     * Turns the pending decisions of a v10/v11 file into proposals, the
     * way the v12 migration did on the phone.
     *
     * The truth-only insert above kept the pending rows as if they were
     * done; each is put back as what it was: a request, over the decision
     * it replaced when the file remembers one, over nothing otherwise.
     */
    private fun liftPendingDecisions(states: JSONArray) {
        for (index in 0 until states.length()) {
            val row = states.getJSONObject(index)
            if (row.optString(OBSOLETE_PENDING) != PENDING_TRUE) continue
            val photoId = row.getString(Schema.COLUMN_PHOTO_ID)
            val outcome = ReviewStatus.fromStoredValue(row.optString(Schema.COLUMN_STATUS))
            val action = Proposal.Action.entries.firstOrNull { it.outcome == outcome } ?: continue

            database.execute(
                "INSERT OR REPLACE INTO ${Schema.TABLE_PROPOSALS} " +
                        "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_ACTION}, " +
                        "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_PROPOSED_AT}) " +
                        "VALUES (?, ?, ?, ?)",
                listOf(
                    photoId, action.storedValue,
                    row.nullableString(Schema.COLUMN_DESTINATION_ID),
                    row.nullableString(Schema.COLUMN_UPDATED_AT)
                )
            )
            restorePreviousTruth(row, photoId)
        }
    }

    /** The decision a pending one replaced becomes the truth again, or none does. */
    private fun restorePreviousTruth(row: JSONObject, photoId: String) {
        val previous = row.nullableString(OBSOLETE_PREVIOUS_STATUS)
        if (previous == null) {
            database.execute(
                "DELETE FROM ${Schema.TABLE_PHOTO_STATE} WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                listOf(photoId)
            )
            return
        }
        database.execute(
            "UPDATE ${Schema.TABLE_PHOTO_STATE} SET ${Schema.COLUMN_STATUS} = ?, " +
                    "${Schema.COLUMN_DESTINATION_ID} = ? WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
            listOf(previous, row.nullableString(OBSOLETE_PREVIOUS_DESTINATION_ID), photoId)
        )
    }

    /** The value under [name], or null when the row lacks it or holds JSON null. */
    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

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

    /**
     * Inserts the rows of [values] into [table], returning how many.
     *
     * Only the columns a row carries, and the schema still has, are named
     * in its insert. A column the file predates is then filled by the
     * schema's own default rather than by NULL, which the column may well
     * refuse; a column the schema has since dropped is read separately,
     * where its meaning is converted, and not inserted at all.
     */
    private fun restoreTable(table: String, values: JSONArray?): Int {
        if (values == null) return 0
        val known = COLUMNS.getValue(table)

        for (index in 0 until values.length()) {
            val row = values.getJSONObject(index)
            val present = known.filter { row.has(it) }
            if (present.isEmpty()) continue
            val placeholders = present.joinToString(", ") { "?" }
            database.execute(
                "INSERT OR REPLACE INTO $table (${present.joinToString(", ")}) " +
                        "VALUES ($placeholders)",
                present.map { row.getString(it) }
            )
        }
        return values.length()
    }
}
