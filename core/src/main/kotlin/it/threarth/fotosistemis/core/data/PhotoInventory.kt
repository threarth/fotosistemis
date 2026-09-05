package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.reorg.Reorganizer
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
        val now = System.currentTimeMillis()

        database.transaction {
            // Read inside the transaction, not before it. Two scans can start
            // together — the full one and a folder being opened — and reading
            // first let both conclude the inventory was empty, so both
            // inserted every photo and the next pass marked half of them
            // missing. Reading here makes the two serialise.
            val plan = PhotoMatcher.match(loadStored(), records, recordsAreComplete)
            val storedDates = loadStoredDates()

            val identified = plan.matches.map { match ->
                val photoId = match.photoId?.also {
                    update(it, match.record, storedDates[it], now)
                }
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
            "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_RELATIVE_PATH}, " +
                    "${Schema.COLUMN_DISPLAY_NAME} FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
            ClassificationAdopter.InventoryEntry(
                photoId,
                row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty(),
                row.getString(Schema.COLUMN_DISPLAY_NAME).orEmpty()
            )
        }
    }

    /** How a category is used, and how much of it sits somewhere else. */
    data class DestinationUse(
        val destinationId: Long,
        val photoCount: Int,

        /** Filed here, but living outside the folder this category names. */
        val elsewhereCount: Int
    )

    /**
     * Photos per category, and how many are not in its folder.
     *
     * Merging two categories is a decision about meaning, not about disk:
     * the photos stay where they were until a reorganisation moves them.
     * Counting what is out of place is what keeps that from being invisible,
     * because a category can be whole in the database and scattered on disk.
     */
    fun countByDestination(): Result<List<DestinationUse>> = runCatching {
        database.query(
            "SELECT s.${Schema.COLUMN_DESTINATION_ID} AS did, COUNT(*) AS total, " +
                    "SUM(CASE WHEN p.${Schema.COLUMN_RELATIVE_PATH} LIKE " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/%' THEN 0 ELSE 1 END) AS elsewhere " +
                    "FROM ${Schema.TABLE_PHOTO_STATE} s " +
                    "JOIN ${Schema.TABLE_PHOTOS} p ON p.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_PHOTO_ID} " +
                    "JOIN ${Schema.TABLE_DESTINATIONS} d ON d.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_DESTINATION_ID} " +
                    "WHERE p.${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "GROUP BY s.${Schema.COLUMN_DESTINATION_ID}"
        ).mapNotNull { row ->
            val id = row.getLong("did") ?: return@mapNotNull null
            DestinationUse(id, row.getInt("total") ?: 0, row.getInt("elsewhere") ?: 0)
        }
    }

    /**
     * Every filed photo, as the reorganiser needs it.
     *
     * Only photos that carry a destination: the reorganisation arranges
     * categories, and a photo with no category has no shape to be given.
     */
    fun loadForReorganization(): Result<List<Reorganizer.Entry>> = runCatching {
        database.query(
            "SELECT p.${Schema.COLUMN_ID} AS photo_id, " +
                    "s.${Schema.COLUMN_DESTINATION_ID} AS destination_id, " +
                    "p.${Schema.COLUMN_VOLUME_NAME} AS volume, " +
                    "p.${Schema.COLUMN_RELATIVE_PATH} AS path, " +
                    "p.${Schema.COLUMN_DISPLAY_NAME} AS name, " +
                    "p.${Schema.COLUMN_DATE_TAKEN} AS taken, " +
                    "p.${Schema.COLUMN_DATE_SOURCE} AS source " +
                    "FROM ${Schema.TABLE_PHOTOS} p " +
                    "JOIN ${Schema.TABLE_PHOTO_STATE} s " +
                    "ON s.${Schema.COLUMN_PHOTO_ID} = p.${Schema.COLUMN_ID} " +
                    "WHERE p.${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "AND s.${Schema.COLUMN_STATUS} = ? " +
                    "AND s.${Schema.COLUMN_DESTINATION_ID} IS NOT NULL",
            listOf(ReviewStatus.CATEGORIZED.storedValue)
        ).mapNotNull { row ->
            val photoId = row.getLong("photo_id") ?: return@mapNotNull null
            val destinationId = row.getLong("destination_id") ?: return@mapNotNull null

            Reorganizer.Entry(
                photoId = photoId,
                destinationId = destinationId,
                volumeName = row.getString("volume").orEmpty(),
                relativePath = row.getString("path").orEmpty(),
                displayName = row.getString("name").orEmpty(),
                captureMillis = row.getLong("taken") ?: 0L,
                source = CaptureDateResolver.Source.entries
                    .firstOrNull { it.name == row.getString("source") }
                    ?: CaptureDateResolver.Source.FILE_TIMESTAMP
            )
        }
    }

    /**
     * Photos decided for deletion that never reached the bin.
     *
     * A decision is written the moment it is made, while the move can be
     * refused, postponed, or lost when the queue dies with the session. The
     * two drift apart silently, and these are the photos the app believes it
     * has gathered and has not.
     */
    fun loadPendingTrash(stagingPath: String): Result<List<PhotoRecord>> = runCatching {
        val ids = database.query(
            "SELECT s.${Schema.COLUMN_PHOTO_ID} AS pid FROM ${Schema.TABLE_PHOTO_STATE} s " +
                    "JOIN ${Schema.TABLE_PHOTOS} p ON p.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_PHOTO_ID} " +
                    "WHERE s.${Schema.COLUMN_STATUS} = ? " +
                    "AND p.${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "AND p.${Schema.COLUMN_RELATIVE_PATH} <> ?",
            listOf(ReviewStatus.TRASHED.storedValue, stagingPath)
        ).mapNotNull { it.getLong("pid") }

        if (ids.isEmpty()) emptyList() else loadRecords(ids).getOrThrow()
    }

    /**
     * Points a photo at the copy that now stands for it.
     *
     * The copy is a different file to the platform, with a new id, but the
     * same photograph to us — and everything decided about it, filed under
     * our own id, has to follow it rather than be orphaned when the original
     * goes. This is what "the media id is not an identity" was for.
     */
    fun rekeyToCopy(
        photoId: Long,
        newMediaId: Long,
        relativePath: String,
        displayName: String
    ): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_MEDIA_ID} = ?, " +
                        "${Schema.COLUMN_RELATIVE_PATH} = ?, ${Schema.COLUMN_DISPLAY_NAME} = ? " +
                        "WHERE ${Schema.COLUMN_ID} = ?",
                listOf(newMediaId, relativePath, displayName, photoId)
            )
            Unit
        }
    }

    /**
     * Writes where a photo ended up, once the platform has actually moved it.
     *
     * Reconciliation would find this out on its own at the next start, but
     * leaving the inventory stale until then would show the user the old
     * folder for every photo they just watched move.
     */
    fun recordRelocation(
        photoId: Long,
        relativePath: String,
        displayName: String
    ): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_RELATIVE_PATH} = ?, " +
                        "${Schema.COLUMN_DISPLAY_NAME} = ? WHERE ${Schema.COLUMN_ID} = ?",
                listOf(relativePath, displayName, photoId)
            )
            Unit
        }
    }

    /**
     * Records a proposal as filed, creating the categories it found.
     *
     * One transaction: creating folders and recording the photos that
     * belong to them is a single act, and half of it would leave photos
     * pointing at a folder that does not exist.
     *
     * Each photo's current folder is written as its original one: it is the
     * first place the app ever saw it, and restoring has to put it back
     * exactly there.
     */
    fun adopt(
        proposal: ClassificationAdopter.Proposal,
        destinationRepository: DestinationRepository
    ): Result<Int> = runCatching {
        val now = System.currentTimeMillis()
        database.transaction {
            val createdIds = HashMap<String, Long>()
            for (category in proposal.proposedCategories) {
                createdIds[category.label] = destinationRepository
                    .insert(category.label, category.relativePath, yearSubfolder = true)
                    .getOrThrow()
            }

            for (candidate in proposal.candidates) {
                val destinationId = candidate.destinationId
                    ?: createdIds[candidate.categoryLabel]
                    ?: continue
                database.execute(
                    "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                            "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                            "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}) " +
                            "VALUES (?, ?, ?, ?)",
                    listOf(
                        candidate.photoId,
                        ReviewStatus.CATEGORIZED.storedValue,
                        destinationId,
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
                                "${Schema.COLUMN_PATH}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                                "${Schema.COLUMN_KIND}, ${Schema.COLUMN_RECORDED_AT}) " +
                                "VALUES (?, ?, ?, ?, ?)",
                        listOf(
                            candidate.photoId,
                            candidate.relativePath,
                            candidate.displayName,
                            PhotoStateRepository.PathKind.ORIGINAL.storedValue,
                            now
                        )
                    )
                }
            }
            proposal.total
        }
    }

    /**
     * Rebuilds records for [photoIds], enough to show the photos.
     *
     * Reading them back from the inventory rather than the platform means a
     * preview can be assembled from what the app already knows, without a
     * second pass over the media index.
     */
    fun loadRecords(photoIds: List<Long>): Result<List<PhotoRecord>> = runCatching {
        if (photoIds.isEmpty()) return@runCatching emptyList()
        val wanted = photoIds.toHashSet()

        database.query(
            "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_MEDIA_ID}, " +
                    "${Schema.COLUMN_VOLUME_NAME}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                    "${Schema.COLUMN_RELATIVE_PATH}, ${Schema.COLUMN_SIZE_BYTES}, " +
                    "${Schema.COLUMN_DATE_TAKEN}, ${Schema.COLUMN_DATE_SOURCE} " +
                    "FROM ${Schema.TABLE_PHOTOS} WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "ORDER BY ${Schema.COLUMN_DATE_TAKEN} DESC"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
            if (photoId !in wanted) return@mapNotNull null
            PhotoRecord(
                photoId = photoId,
                platformId = row.getLong(Schema.COLUMN_MEDIA_ID) ?: 0L,
                volumeName = row.getString(Schema.COLUMN_VOLUME_NAME).orEmpty(),
                displayName = row.getString(Schema.COLUMN_DISPLAY_NAME).orEmpty(),
                relativePath = row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty(),
                sizeBytes = row.getLong(Schema.COLUMN_SIZE_BYTES) ?: 0L,
                dateTakenMillis = row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L,
                dateSource = CaptureDateResolver.Source.entries
                    .firstOrNull { it.name == row.getString(Schema.COLUMN_DATE_SOURCE) }
                    ?: CaptureDateResolver.Source.FILE_TIMESTAMP
            )
        }
    }

    /** Records the moment a volume was last reconciled in full. */
    /**
     * When the last full scan of [volumeName] finished, or zero.
     *
     * A full reconciliation reads every photo on the device: worth doing, but
     * not each time the app comes back from standby, which on a large archive
     * turns every glance into a wait.
     */
    fun lastFullScanAt(volumeName: String): Long = database.query(
        "SELECT ${Schema.COLUMN_LAST_FULL_SCAN_AT} AS at FROM ${Schema.TABLE_SYNC_STATE} " +
                "WHERE ${Schema.COLUMN_VOLUME_NAME} = ?",
        listOf(volumeName)
    ).firstOrNull()?.getLong("at") ?: 0L

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

    /** A capture date as the inventory already holds it. */
    private data class StoredDate(
        val millis: Long,
        val source: CaptureDateResolver.Source
    )

    /**
     * Refreshes what is known about a photo already in the inventory.
     *
     * The capture date is the exception: it is replaced only by one from a
     * source at least as trustworthy. DATE_MODIFIED changes whenever the file
     * is written and moving a photo is a write, so without this rule a photo
     * dated from the file timestamp would take the date of its own last move,
     * be filed under the wrong year, and be dated again by that move. The
     * tool would end up sorting photos by the effect of its own sorting.
     *
     * Also clears missing_since: a photo that turns up again is present,
     * whatever happened while it was out of sight.
     */
    private fun update(
        photoId: Long,
        record: PhotoRecord,
        stored: StoredDate?,
        now: Long
    ) {
        val keepStored = stored != null && stored.source.outranks(record.dateSource)
        val dateMillis = if (keepStored) stored!!.millis else record.dateTakenMillis
        val dateSource = if (keepStored) stored!!.source else record.dateSource

        database.execute(
            "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_MEDIA_ID} = ?, " +
                    "${Schema.COLUMN_VOLUME_NAME} = ?, ${Schema.COLUMN_DISPLAY_NAME} = ?, " +
                    "${Schema.COLUMN_RELATIVE_PATH} = ?, ${Schema.COLUMN_SIZE_BYTES} = ?, " +
                    "${Schema.COLUMN_DATE_TAKEN} = ?, ${Schema.COLUMN_DATE_SOURCE} = ?, " +
                    "${Schema.COLUMN_LAST_SEEN_AT} = ?, ${Schema.COLUMN_MISSING_SINCE} = NULL " +
                    "WHERE ${Schema.COLUMN_ID} = ?",
            listOf(
                record.platformId, record.volumeName, record.displayName, record.relativePath,
                record.sizeBytes, dateMillis, dateSource.name, now, photoId
            )
        )
    }

    /**
     * Capture dates already recorded, keyed by photo.
     *
     * Read separately rather than through PhotoMatcher.Stored: matching
     * decides which photo a file is, and has no business knowing how much
     * its date is worth.
     */
    private fun loadStoredDates(): Map<Long, StoredDate> = database.query(
        "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_DATE_TAKEN}, " +
                "${Schema.COLUMN_DATE_SOURCE} FROM ${Schema.TABLE_PHOTOS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
        val source = CaptureDateResolver.Source.entries
            .firstOrNull { it.name == row.getString(Schema.COLUMN_DATE_SOURCE) }
            ?: return@mapNotNull null

        photoId to StoredDate(row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L, source)
    }.toMap()

    private fun markMissing(photoId: Long, now: Long) {
        database.execute(
            "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_MISSING_SINCE} = ? " +
                    "WHERE ${Schema.COLUMN_ID} = ? AND ${Schema.COLUMN_MISSING_SINCE} IS NULL",
            listOf(now, photoId)
        )
    }
}
