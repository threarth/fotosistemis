package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.Database

/**
 * The app's own record of every photo it has seen.
 *
 * Holding this makes the database the thing the app reads from, and the
 * platform the thing it reconciles against. Counts, months and duplicates
 * become queries here rather than scans of the platform index, and a photo
 * keeps its identity through renames, moves and reassigned platform ids.
 */
class PhotoInventory(private val database: Database) {

    /** What a reconciliation changed, for reporting and for tests. */
    data class Report(
        val seen: Int,
        val added: Int,
        val rekeyed: Int,
        val missing: Int
    )

    /**
     * Reconciles [records] against the inventory and returns them carrying
     * their identity.
     *
     * Set [recordsAreComplete] only when [records] covers every photo the
     * platform holds: absence is read as a photo being gone, and a scan of
     * one folder cannot support that conclusion.
     */
    fun reconcile(
        records: List<PhotoRecord>,
        recordsAreComplete: Boolean = false
    ): Result<Pair<List<PhotoRecord>, Report>> = runCatching {
        val plan = PhotoMatcher.match(loadStored(), records, recordsAreComplete)
        val now = System.currentTimeMillis()

        database.transaction {
            val identified = plan.matches.map { match ->
                val photoId = match.photoId?.also { update(it, match.record, match, now) }
                    ?: insert(match.record, now)
                match.record.copy(photoId = photoId)
            }
            plan.missingPhotoIds.forEach { markMissing(it, now) }

            identified to Report(
                seen = records.size,
                added = plan.newCount,
                rekeyed = plan.rekeyedCount,
                missing = plan.missingPhotoIds.size
            )
        }
    }

    /**
     * Folders holding photos, counted from the inventory.
     *
     * Answering this from our own tables is what removes a scan of the whole
     * platform index from every start.
     */
    fun loadFolders(): Result<List<FolderSummary>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_VOLUME_NAME} AS volume, " +
                    "${Schema.COLUMN_RELATIVE_PATH} AS path, COUNT(*) AS total " +
                    "FROM ${Schema.TABLE_PHOTOS} WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "GROUP BY volume, path"
        ).map {
            FolderSummary(
                volumeName = it.getString("volume").orEmpty(),
                relativePath = it.getString("path").orEmpty(),
                photoCount = it.getInt("total") ?: 0
            )
        }.sortedWith(compareBy({ it.isRemovable }, { it.relativePath }))
    }

    /** How many photos sit directly in [relativePath], across volumes. */
    fun countPhotosIn(relativePath: String): Result<Int> = runCatching {
        database.query(
            "SELECT COUNT(*) AS total FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_RELATIVE_PATH} = ? " +
                    "AND ${Schema.COLUMN_MISSING_SINCE} IS NULL",
            listOf(relativePath)
        ).firstOrNull()?.getInt("total") ?: 0
    }

    /** Every present photo, as the adopter needs it. */
    fun loadForAdoption(): Result<List<ClassificationAdopter.InventoryEntry>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_RELATIVE_PATH} " +
                    "FROM ${Schema.TABLE_PHOTOS} WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
            ClassificationAdopter.InventoryEntry(
                photoId,
                row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty()
            )
        }
    }

    /**
     * Records the candidates of a preview as filed, in one transaction.
     *
     * Their current folder is written as the original one: it is the first
     * place the app ever saw them, and restoring has to put them back
     * exactly there.
     */
    fun adopt(candidates: List<ClassificationAdopter.Candidate>): Result<Int> = runCatching {
        val now = System.currentTimeMillis()
        database.transaction {
            for (candidate in candidates) {
                database.execute(
                    "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                            "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                            "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}) " +
                            "VALUES (?, ?, ?, ?)",
                    listOf(
                        candidate.photoId,
                        ReviewStatus.CATEGORIZED.storedValue,
                        candidate.destination.id,
                        now
                    )
                )
                val known = database.query(
                    "SELECT 1 AS present FROM ${Schema.TABLE_PHOTO_PATHS} " +
                            "WHERE ${Schema.COLUMN_PHOTO_ID} = ? LIMIT 1",
                    listOf(candidate.photoId)
                ).isNotEmpty()
                if (!known) {
                    database.insert(
                        "INSERT INTO ${Schema.TABLE_PHOTO_PATHS} (${Schema.COLUMN_PHOTO_ID}, " +
                                "${Schema.COLUMN_PATH}, ${Schema.COLUMN_KIND}, " +
                                "${Schema.COLUMN_RECORDED_AT}) VALUES (?, ?, ?, ?)",
                        listOf(
                            candidate.photoId,
                            candidate.relativePath,
                            PhotoStateRepository.PathKind.ORIGINAL.storedValue,
                            now
                        )
                    )
                }
            }
            candidates.size
        }
    }

    /** Records the moment a volume was last reconciled in full. */
    fun rememberFullScan(volumeName: String): Result<Unit> = runCatching {
        database.execute(
            "INSERT OR REPLACE INTO ${Schema.TABLE_SYNC_STATE} " +
                    "(${Schema.COLUMN_VOLUME_NAME}, ${Schema.COLUMN_LAST_FULL_SCAN_AT}) " +
                    "VALUES (?, ?)",
            listOf(volumeName, System.currentTimeMillis())
        )
        Unit
    }

    /** The whole inventory, in the shape the matcher needs. */
    private fun loadStored(): List<PhotoMatcher.Stored> = database.query(
        "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_MEDIA_ID}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                "${Schema.COLUMN_SIZE_BYTES}, ${Schema.COLUMN_DATE_TAKEN} " +
                "FROM ${Schema.TABLE_PHOTOS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
        PhotoMatcher.Stored(
            photoId = photoId,
            mediaId = row.getLong(Schema.COLUMN_MEDIA_ID),
            displayName = row.getString(Schema.COLUMN_DISPLAY_NAME).orEmpty(),
            sizeBytes = row.getLong(Schema.COLUMN_SIZE_BYTES) ?: 0L,
            dateTakenMillis = row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L
        )
    }

    private fun insert(record: PhotoRecord, now: Long): Long = database.insert(
        "INSERT INTO ${Schema.TABLE_PHOTOS} (${Schema.COLUMN_MEDIA_ID}, " +
                "${Schema.COLUMN_VOLUME_NAME}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                "${Schema.COLUMN_RELATIVE_PATH}, ${Schema.COLUMN_SIZE_BYTES}, " +
                "${Schema.COLUMN_DATE_TAKEN}, ${Schema.COLUMN_DATE_SOURCE}, " +
                "${Schema.COLUMN_FIRST_SEEN_AT}, ${Schema.COLUMN_LAST_SEEN_AT}) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            record.platformId, record.volumeName, record.displayName, record.relativePath,
            record.sizeBytes, record.dateTakenMillis, record.dateSource.name, now, now
        )
    )

    /**
     * Refreshes what is known about a photo already in the inventory.
     *
     * Also clears missing_since: a photo that turns up again is present,
     * whatever happened while it was out of sight.
     */
    private fun update(
        photoId: Long,
        record: PhotoRecord,
        match: PhotoMatcher.Match,
        now: Long
    ) {
        database.execute(
            "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_MEDIA_ID} = ?, " +
                    "${Schema.COLUMN_VOLUME_NAME} = ?, ${Schema.COLUMN_DISPLAY_NAME} = ?, " +
                    "${Schema.COLUMN_RELATIVE_PATH} = ?, ${Schema.COLUMN_SIZE_BYTES} = ?, " +
                    "${Schema.COLUMN_DATE_TAKEN} = ?, ${Schema.COLUMN_DATE_SOURCE} = ?, " +
                    "${Schema.COLUMN_LAST_SEEN_AT} = ?, ${Schema.COLUMN_MISSING_SINCE} = NULL " +
                    "WHERE ${Schema.COLUMN_ID} = ?",
            listOf(
                record.platformId, record.volumeName, record.displayName, record.relativePath,
                record.sizeBytes, record.dateTakenMillis, record.dateSource.name, now, photoId
            )
        )
    }

    private fun markMissing(photoId: Long, now: Long) {
        database.execute(
            "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_MISSING_SINCE} = ? " +
                    "WHERE ${Schema.COLUMN_ID} = ? AND ${Schema.COLUMN_MISSING_SINCE} IS NULL",
            listOf(now, photoId)
        )
    }
}
