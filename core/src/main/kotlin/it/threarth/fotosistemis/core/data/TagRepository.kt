package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.port.Database
import java.util.Locale

/**
 * Free-text tags, many per photo.
 *
 * Tags carry the meaning folders cannot: a photo lives in one folder but can
 * be about several things at once. They stay in the database rather than in
 * file names, so adding or removing one is an update instead of a rename
 * that would disturb backup services.
 */
class TagRepository(private val database: Database) {

    private companion object {

        /** Longest tag accepted, after normalisation. */
        const val MAX_TAG_LENGTH = 60
    }

    /** All tags currently in use, alphabetically. */
    fun loadAllNames(): Result<List<String>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_NAME} FROM ${Schema.TABLE_TAGS} " +
                    "ORDER BY ${Schema.COLUMN_NAME} ASC"
        ).mapNotNull { it.getString(Schema.COLUMN_NAME) }
    }

    /** Tags assigned to every photo, keyed by platform id. */
    fun loadAssignments(): Result<Map<Long, List<String>>> = runCatching {
        val assignments = HashMap<Long, MutableList<String>>()
        database.query(
            "SELECT pt.${Schema.COLUMN_MEDIA_ID} AS media, t.${Schema.COLUMN_NAME} AS name " +
                    "FROM ${Schema.TABLE_PHOTO_TAGS} pt " +
                    "JOIN ${Schema.TABLE_TAGS} t ON t.${Schema.COLUMN_ID} = pt.${Schema.COLUMN_TAG_ID} " +
                    "ORDER BY t.${Schema.COLUMN_NAME} ASC"
        ).forEach { row ->
            val mediaId = row.getLong("media") ?: return@forEach
            val name = row.getString("name") ?: return@forEach
            assignments.getOrPut(mediaId) { ArrayList() }.add(name)
        }
        assignments
    }

    /**
     * Attaches [rawName] to [mediaId], creating the tag if it is new.
     * Assigning the same tag twice is a no-op rather than an error.
     */
    fun assign(mediaId: Long, rawName: String): Result<String> = runCatching {
        val name = normalise(rawName)
            ?: throw IllegalArgumentException("Tag non valido: $rawName")
        database.transaction {
            val tagId = findOrCreateTag(name)
            database.execute(
                "INSERT OR IGNORE INTO ${Schema.TABLE_PHOTO_TAGS} " +
                        "(${Schema.COLUMN_MEDIA_ID}, ${Schema.COLUMN_TAG_ID}) VALUES (?, ?)",
                listOf(mediaId, tagId)
            )
            name
        }
    }

    /** Detaches a tag from one photo. The tag itself survives. */
    fun unassign(mediaId: Long, name: String): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_PHOTO_TAGS} " +
                        "WHERE ${Schema.COLUMN_MEDIA_ID} = ? AND ${Schema.COLUMN_TAG_ID} = " +
                        "(SELECT ${Schema.COLUMN_ID} FROM ${Schema.TABLE_TAGS} " +
                        "WHERE ${Schema.COLUMN_NAME} = ?)",
                listOf(mediaId, name)
            )
            Unit
        }
    }

    /** Returns the id of [name], inserting the tag when it does not exist. */
    private fun findOrCreateTag(name: String): Long {
        val existing = database.query(
            "SELECT ${Schema.COLUMN_ID} FROM ${Schema.TABLE_TAGS} WHERE ${Schema.COLUMN_NAME} = ?",
            listOf(name)
        ).firstOrNull()?.getLong(Schema.COLUMN_ID)
        if (existing != null) return existing

        return database.insert(
            "INSERT INTO ${Schema.TABLE_TAGS} (${Schema.COLUMN_NAME}) VALUES (?)",
            listOf(name)
        )
    }

    /**
     * Trims and lowercases, so that "Festa Tommy" and "festa tommy" are the
     * same tag. Returns null when nothing usable is left.
     */
    private fun normalise(rawName: String): String? =
        rawName.trim().lowercase(Locale.ITALY).take(MAX_TAG_LENGTH).ifEmpty { null }
}
