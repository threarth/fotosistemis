package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.port.Database

/**
 * Create, read, update and delete the folders photos can be filed into.
 */
class DestinationRepository(private val database: Database) {

    private companion object {
        const val COLUMNS = "${Schema.COLUMN_ID}, ${Schema.COLUMN_LABEL}, " +
                "${Schema.COLUMN_RELATIVE_PATH}, ${Schema.COLUMN_YEAR_SUBFOLDER}, " +
                Schema.COLUMN_SORT_ORDER
        const val ORDER_BY = "${Schema.COLUMN_SORT_ORDER} ASC, ${Schema.COLUMN_LABEL} ASC"
    }

    /** Every destination, in display order. */
    fun loadAll(): Result<List<Destination>> = runCatching {
        database.query("SELECT $COLUMNS FROM ${Schema.TABLE_DESTINATIONS} ORDER BY $ORDER_BY")
            .map { row ->
                Destination(
                    id = row.getLong(Schema.COLUMN_ID) ?: 0L,
                    label = row.getString(Schema.COLUMN_LABEL).orEmpty(),
                    relativePath = row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty(),
                    yearSubfolder = (row.getInt(Schema.COLUMN_YEAR_SUBFOLDER) ?: 1) != 0,
                    sortOrder = row.getInt(Schema.COLUMN_SORT_ORDER) ?: 0
                )
            }
    }

    /** Adds a destination and returns its new id. */
    fun insert(label: String, relativePath: String, yearSubfolder: Boolean): Result<Long> =
        runCatching {
            database.transaction {
                database.insert(
                    "INSERT INTO ${Schema.TABLE_DESTINATIONS} " +
                            "(${Schema.COLUMN_LABEL}, ${Schema.COLUMN_RELATIVE_PATH}, " +
                            "${Schema.COLUMN_YEAR_SUBFOLDER}) VALUES (?, ?, ?)",
                    listOf(label.trim(), cleanPath(relativePath), if (yearSubfolder) 1 else 0)
                )
            }
        }

    /** Updates an existing destination in place. */
    fun update(
        id: Long,
        label: String,
        relativePath: String,
        yearSubfolder: Boolean
    ): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_DESTINATIONS} SET ${Schema.COLUMN_LABEL} = ?, " +
                        "${Schema.COLUMN_RELATIVE_PATH} = ?, ${Schema.COLUMN_YEAR_SUBFOLDER} = ? " +
                        "WHERE ${Schema.COLUMN_ID} = ?",
                listOf(label.trim(), cleanPath(relativePath), if (yearSubfolder) 1 else 0, id)
            )
            Unit
        }
    }

    /**
     * Removes a destination. Photos already filed there keep their files:
     * only the shortcut disappears, never the photos.
     */
    fun delete(id: Long): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_DESTINATIONS} WHERE ${Schema.COLUMN_ID} = ?",
                listOf(id)
            )
            Unit
        }
    }

    private fun cleanPath(relativePath: String) = relativePath.trim().trim('/')
}
