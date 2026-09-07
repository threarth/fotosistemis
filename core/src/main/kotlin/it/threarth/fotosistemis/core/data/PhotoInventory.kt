package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.FolderPath
import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.review.ReviewSession
import it.threarth.fotosistemis.core.reorg.Reorganizer
import it.threarth.fotosistemis.core.port.Database
import it.threarth.fotosistemis.core.port.PhotoSource

/**
 * The app's own record of every photo it has seen.
 *
 * Holding this makes the database the thing the app reads from, and the
 * platform the thing it reconciles against. Counts, months and duplicates
 * become queries here rather than scans of the platform index, and a photo
 * keeps its identity through renames, moves and reassigned platform ids.
 */
class PhotoInventory(private val database: Database) {

    companion object {

        /**
         * Written in place of a picture's fingerprint when the file holds
         * none this can read.
         *
         * Stored rather than left empty so the reading pass knows it has
         * been here and does not offer the same file for ever. Never handed
         * out as a fingerprint: records carry null instead, or every PNG on
         * the phone would look like a copy of every other.
         */
        const val NO_PICTURE = "-"
    }


    /** What a reconciliation changed, for reporting and for tests. */
    data class Report(
        val seen: Int,
        val added: Int,
        val rekeyed: Int,
        /**
         * Rows no scan can find any more, of all time.
         *
         * A photo already given up is given up again by every later scan, so
         * this only ever grows: it is a backlog, not news, and reading it as
         * news makes every reconciliation look like a disaster.
         */
        val missing: Int,

        /** Of those, the ones this scan is the first to miss. */
        val newlyMissing: Int
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
                missing = plan.missingPhotoIds.size,
                newlyMissing = plan.newlyMissingPhotoIds.size
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
    fun loadForReorganization(
        stagingPath: String = ReviewSession.DELETION_STAGING_PATH
    ): Result<List<Reorganizer.Entry>> = runCatching {
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
                    "AND s.${Schema.COLUMN_DESTINATION_ID} IS NOT NULL " +
                    // Said outright rather than left to follow from a photo
                    // in the bin having no category. Reorganising is what
                    // stamps names, and nothing in the bin may be renamed:
                    // the name it arrived with is the name it goes back
                    // with. A guarantee that rests on a coincidence is not
                    // a guarantee.
                    "AND p.${Schema.COLUMN_RELATIVE_PATH} <> ? " +
                    // A photo that could not be moved was copied into its
                    // category and the original stayed put, so its own path
                    // says "wrong folder" for ever and reorganising would
                    // copy it again. Excluded — but only those. Every other
                    // photo has to stay visible here even when it is already
                    // in place, because this screen is also where a category
                    // is given a different folder, and a photo left out of
                    // the list can no longer be sent anywhere.
                    "AND NOT (p.${Schema.COLUMN_RELATIVE_PATH} LIKE ? " +
                    "AND EXISTS (SELECT 1 FROM ${Schema.TABLE_PHOTO_PATHS} pp " +
                    "JOIN ${Schema.TABLE_DESTINATIONS} d ON d.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_DESTINATION_ID} " +
                    "WHERE pp.${Schema.COLUMN_PHOTO_ID} = p.${Schema.COLUMN_ID} " +
                    "AND pp.${Schema.COLUMN_KIND} = ? " +
                    "AND pp.${Schema.COLUMN_PATH} LIKE " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/%'))",
            listOf(
                ReviewStatus.CATEGORIZED.storedValue,
                stagingPath,
                PhotoSource.APP_MEDIA_ROOT + "%",
                PhotoStateRepository.PathKind.MOVED.storedValue
            )
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
     * Photos decided for the bin whose file has not moved yet.
     *
     * A view of the owed work rather than a query of its own: two ways of
     * asking the same question are two answers waiting to disagree, and
     * this one used to be deduced from where the photo happened to be —
     * which for a photo that cannot be moved is never an answer at all.
     */
    fun loadPendingTrash(): Result<List<PhotoRecord>> = runCatching {
        loadProposed().getOrThrow()
            .filter { it.proposal.action == Proposal.Action.TRASH }
            .map { it.photo }
    }

    /**
     * Photos a decision has been made about that carry no fingerprint yet.
     *
     * Only those: a fingerprint protects work already done, and computing
     * one for every photo on the device would read every byte on it to
     * protect nothing.
     */
    fun loadNeedingHash(limit: Int): Result<List<PhotoRecord>> = runCatching {
        val ids = database.query(
            "SELECT s.${Schema.COLUMN_PHOTO_ID} AS pid FROM ${Schema.TABLE_PHOTO_STATE} s " +
                    "JOIN ${Schema.TABLE_PHOTOS} p ON p.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_PHOTO_ID} " +
                    "WHERE p.${Schema.COLUMN_CONTENT_HASH} IS NULL " +
                    "AND p.${Schema.COLUMN_MISSING_SINCE} IS NULL LIMIT ?",
            listOf(limit)
        ).mapNotNull { it.getLong("pid") }

        if (ids.isEmpty()) emptyList() else loadRecords(ids).getOrThrow()
    }

    /**
     * Photos with no fingerprint of their picture yet, and how many are
     * left.
     *
     * Unlike [loadNeedingHash] this does not stop at the photos a decision
     * has been made about. It cannot: the whole point is to pair a copy
     * nobody has decided anything about with one that is already filed, and
     * a fingerprint only one of the two carries pairs nothing. The reading
     * is therefore offered as a pass the user starts, and the screen says
     * how much of it is still owed.
     */
    fun loadNeedingImageHash(limit: Int): Result<List<PhotoRecord>> = runCatching {
        recordsWhere("AND ${Schema.COLUMN_IMAGE_HASH} IS NULL", limit = limit)
    }

    /** How many photos are on the phone, for saying how far a pass has got. */
    fun countPresent(): Result<Int> = runCatching {
        database.query(
            "SELECT COUNT(*) AS total FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).firstOrNull()?.getInt("total") ?: 0
    }

    /** How many present photos still carry no fingerprint of their picture. */
    fun countNeedingImageHash(): Result<Int> = runCatching {
        database.query(
            "SELECT COUNT(*) AS total FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_IMAGE_HASH} IS NULL " +
                    "AND ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).firstOrNull()?.getInt("total") ?: 0
    }

    /**
     * Stores the fingerprint of a photo's picture.
     *
     * A file with no readable picture is written down as read all the same,
     * with the mark below: without it the pass would offer the same
     * unreadable files for ever and never finish.
     */
    fun recordImageHash(photoId: Long, imageHash: String?): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_IMAGE_HASH} = ? " +
                        "WHERE ${Schema.COLUMN_ID} = ?",
                listOf(imageHash ?: NO_PICTURE, photoId)
            )
            Unit
        }
    }

    /** Stores the fingerprint taken from a photo's bytes. */
    fun recordHash(photoId: Long, contentHash: String): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_CONTENT_HASH} = ? " +
                        "WHERE ${Schema.COLUMN_ID} = ?",
                listOf(contentHash, photoId)
            )
            Unit
        }
    }

    /**
     * Records, or withdraws, the user's judgement that a date is wrong.
     *
     * Nothing is corrected here and no file is touched: the photo keeps the
     * date it has, and keeps being filed by it. What is written is that the
     * date is not to be trusted, so the photograph can be found again when
     * there is a way to establish the right one.
     */
    fun markDateSuspect(photoId: Long, suspect: Boolean): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "UPDATE ${Schema.TABLE_PHOTOS} SET ${Schema.COLUMN_DATE_SUSPECT} = ? " +
                        "WHERE ${Schema.COLUMN_ID} = ?",
                listOf(if (suspect) 1 else 0, photoId)
            )
            Unit
        }
    }

    /**
     * Photos inside a category folder that nobody ever filed there.
     *
     * A category folder is supposed to hold decided photographs and nothing
     * else. Something else can get in: a copy the app made and then lost
     * track of, a file dropped in by hand, a restore from another phone.
     * Left alone it is invisible — it is not offered for review either,
     * because it no longer looks like an unsorted photo.
     *
     * Finding them is cheap, since it asks only about the category folders.
     *
     * A photo something is asked of is not a stranger either: the request
     * says what it is about to be, and filing it here on top would put a
     * truth under a proposal that will overwrite it.
     */
    fun loadStrangersInDestinations(): Result<List<Long>> = runCatching {
        database.query(
            "SELECT p.${Schema.COLUMN_ID} AS pid FROM ${Schema.TABLE_PHOTOS} p " +
                    "WHERE p.${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM ${Schema.TABLE_PHOTO_STATE} s " +
                    "WHERE s.${Schema.COLUMN_PHOTO_ID} = p.${Schema.COLUMN_ID}) " +
                    "AND NOT EXISTS (SELECT 1 FROM ${Schema.TABLE_PROPOSALS} r " +
                    "WHERE r.${Schema.COLUMN_PHOTO_ID} = p.${Schema.COLUMN_ID}) " +
                    "AND EXISTS (SELECT 1 FROM ${Schema.TABLE_DESTINATIONS} d " +
                    "WHERE p.${Schema.COLUMN_RELATIVE_PATH} = " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/' " +
                    "OR p.${Schema.COLUMN_RELATIVE_PATH} LIKE " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/%')"
        ).mapNotNull { it.getLong("pid") }
    }

    /** What the inventory knows about a photo, found by its platform id. */
    data class Known(val photoId: Long, val dateTakenMillis: Long)

    /**
     * Looks photos up by the id the platform gave them.
     *
     * The way in when a photo comes from MediaStore rather than from here:
     * such a record carries no inventory id at all, and asking by one would
     * quietly read the wrong row.
     */
    fun byMediaId(mediaIds: List<Long>): Result<Map<Long, Known>> = runCatching {
        if (mediaIds.isEmpty()) return@runCatching emptyMap()

        val wanted = mediaIds.toSet()
        database.query(
            "SELECT ${Schema.COLUMN_ID} AS pid, ${Schema.COLUMN_MEDIA_ID} AS mid, " +
                    "${Schema.COLUMN_DATE_TAKEN} AS taken FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).mapNotNull { row ->
            val mediaId = row.getLong("mid") ?: return@mapNotNull null
            if (mediaId !in wanted) return@mapNotNull null
            val photoId = row.getLong("pid") ?: return@mapNotNull null
            mediaId to Known(photoId, row.getLong("taken") ?: 0L)
        }.toMap()
    }

    /** One proposal still owed, with the photo it is about. */
    data class Proposed(val photo: PhotoRecord, val proposal: Proposal)

    /** A decided photo that no longer sits where the app first found it. */
    data class Departed(val photoId: Long, val originPath: String, val dateTakenMillis: Long)

    /**
     * Every decided photo that has left the folder it was first seen in,
     * whether moved elsewhere or gone from the phone.
     *
     * New feature. The periods are counted from the photos present in the
     * chosen folders, so a month whose photos have all been filed away, or
     * handed to the bin, vanished from the list the moment it was finished
     * — the opposite of what finishing should look like. These are the
     * photos that make such a month whole again: they count under the
     * folder they came from, as work done.
     */
    fun loadDeparted(): Result<List<Departed>> = runCatching {
        val departed = ArrayList<Departed>()
        val judged = HashSet<Long>()
        database.query(
            "SELECT p.${Schema.COLUMN_ID}, p.${Schema.COLUMN_RELATIVE_PATH}, " +
                    "p.${Schema.COLUMN_DATE_TAKEN}, p.${Schema.COLUMN_MISSING_SINCE}, " +
                    "o.${Schema.COLUMN_PATH} AS origin " +
                    "FROM ${Schema.TABLE_PHOTOS} p " +
                    "JOIN ${Schema.TABLE_PHOTO_STATE} s ON s.${Schema.COLUMN_PHOTO_ID} = " +
                    "p.${Schema.COLUMN_ID} " +
                    "JOIN ${Schema.TABLE_PHOTO_PATHS} o ON o.${Schema.COLUMN_PHOTO_ID} = " +
                    "p.${Schema.COLUMN_ID} AND o.${Schema.COLUMN_KIND} = ? " +
                    "ORDER BY o.${Schema.COLUMN_RECORDED_AT} ASC",
            listOf(PhotoStateRepository.PathKind.ORIGINAL.storedValue)
        ).forEach { row ->
            val photoId = row.getLong(Schema.COLUMN_ID) ?: return@forEach
            // Oldest wins, as in loadOriginalPaths: the first location
            // recorded is the original one, and the only one judged.
            if (!judged.add(photoId)) return@forEach
            val origin = row.getString("origin") ?: return@forEach
            val here = row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty()
            val gone = row.getLong(Schema.COLUMN_MISSING_SINCE) != null
            if (!gone && FolderPath.sameFolder(here, origin)) return@forEach
            departed.add(Departed(photoId, origin, row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L))
        }
        departed
    }

    /**
     * Every proposal, with its photo, for photos still on the phone.
     *
     * The one place that answers "what is still owed", of every kind at
     * once. Deriving it per kind — deletions from one query, filings from
     * another — is how the queue came to show half its contents and the
     * user came to distrust the count.
     */
    fun loadProposed(): Result<List<Proposed>> = runCatching {
        val proposals = database.query(
            "SELECT r.${Schema.COLUMN_PHOTO_ID}, r.${Schema.COLUMN_ACTION}, " +
                    "r.${Schema.COLUMN_DESTINATION_ID}, r.${Schema.COLUMN_PROPOSED_AT} " +
                    "FROM ${Schema.TABLE_PROPOSALS} r " +
                    "JOIN ${Schema.TABLE_PHOTOS} p ON p.${Schema.COLUMN_ID} = " +
                    "r.${Schema.COLUMN_PHOTO_ID} " +
                    "WHERE p.${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
            val action = Proposal.Action.fromStoredValue(row.getString(Schema.COLUMN_ACTION))
                ?: return@mapNotNull null
            Proposal(
                photoId, action, row.getLong(Schema.COLUMN_DESTINATION_ID),
                row.getLong(Schema.COLUMN_PROPOSED_AT) ?: 0L
            )
        }
        if (proposals.isEmpty()) return@runCatching emptyList()

        val byId = loadRecords(proposals.map { it.photoId }).getOrThrow()
            .associateBy { it.photoId }
        proposals.mapNotNull { proposal -> byId[proposal.photoId]?.let { Proposed(it, proposal) } }
    }

    /** Ids of every photo whose date the user has contradicted. */
    fun loadDateSuspect(): Result<Set<Long>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_ID} AS pid FROM ${Schema.TABLE_PHOTOS} " +
                    "WHERE ${Schema.COLUMN_DATE_SUSPECT} = 1 " +
                    "AND ${Schema.COLUMN_MISSING_SINCE} IS NULL"
        ).mapNotNull { it.getLong("pid") }.toSet()
    }

    /** Those same photos in full, newest first, for showing them. */
    fun loadDateSuspectRecords(): Result<List<PhotoRecord>> = runCatching {
        val ids = loadDateSuspect().getOrThrow()
        if (ids.isEmpty()) return@runCatching emptyList()

        loadRecords(ids.toList()).getOrThrow().sortedByDescending { it.dateTakenMillis }
    }

    /**
     * Filed photos that are not in the folder their category names.
     *
     * The decision was taken and the move was not: they are the other half
     * of the waiting work, beside the ones marked for deletion that never
     * reached the bin.
     */
    fun loadMisplaced(): Result<List<Long>> = runCatching {
        database.query(
            "SELECT s.${Schema.COLUMN_PHOTO_ID} AS pid FROM ${Schema.TABLE_PHOTO_STATE} s " +
                    "JOIN ${Schema.TABLE_PHOTOS} p ON p.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_PHOTO_ID} " +
                    "JOIN ${Schema.TABLE_DESTINATIONS} d ON d.${Schema.COLUMN_ID} = " +
                    "s.${Schema.COLUMN_DESTINATION_ID} " +
                    "WHERE p.${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                    // Only what the truth says is filed: a proposal still
                    // owed is work to do, not a photo in the wrong place,
                    // and it is in the other table.
                    "AND p.${Schema.COLUMN_RELATIVE_PATH} NOT LIKE " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/%' " +
                    // A photo the platform will not let us move was copied
                    // into its category instead, and the original stayed put
                    // for ever. Its own path therefore says "wrong folder"
                    // permanently, and only the record of the copy says
                    // otherwise. The check screen already knew this; the
                    // startup warning did not, and the two disagreed about
                    // the same photographs.
                    "AND NOT EXISTS (SELECT 1 FROM ${Schema.TABLE_PHOTO_PATHS} pp " +
                    "WHERE pp.${Schema.COLUMN_PHOTO_ID} = p.${Schema.COLUMN_ID} " +
                    "AND pp.${Schema.COLUMN_KIND} = ? " +
                    "AND pp.${Schema.COLUMN_PATH} LIKE " +
                    "d.${Schema.COLUMN_RELATIVE_PATH} || '/%')",
            listOf(PhotoStateRepository.PathKind.MOVED.storedValue)
        ).mapNotNull { it.getLong("pid") }
    }

    /**
     * Records a copy as a photo in its own right, already carrying the
     * decision taken about the original, and says which id it got.
     *
     * The old way repointed the original's row at the copy, which reads as
     * the same photograph having moved. It is not: the original is still
     * there until someone agrees to delete it, and a reconciliation running
     * in that window finds it, matches the row back to it, and leaves the
     * copy a stranger — offered again as if nothing had been decided.
     *
     * Two rows, both decided, written in one transaction: whichever of the
     * two files survives, neither comes back to be sorted a second time.
     */
    fun recordCopy(
        originalPhotoId: Long,
        newMediaId: Long,
        relativePath: String,
        displayName: String,

        /** What is true of the copy: it was made to be exactly this. */
        status: ReviewStatus,
        destinationId: Long?
    ): Result<Long> = runCatching {
        database.transaction {
            val now = System.currentTimeMillis()
            val copyId = database.insert(
                "INSERT INTO ${Schema.TABLE_PHOTOS} (${Schema.COLUMN_MEDIA_ID}, " +
                        "${Schema.COLUMN_VOLUME_NAME}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                        "${Schema.COLUMN_RELATIVE_PATH}, ${Schema.COLUMN_SIZE_BYTES}, " +
                        "${Schema.COLUMN_DATE_TAKEN}, ${Schema.COLUMN_DATE_SOURCE}, " +
                        "${Schema.COLUMN_MEDIA_TYPE}, ${Schema.COLUMN_WIDTH}, " +
                        "${Schema.COLUMN_HEIGHT}, ${Schema.COLUMN_DURATION_MILLIS}, " +
                        "${Schema.COLUMN_CONTENT_HASH}, ${Schema.COLUMN_DATE_SUSPECT}, " +
                        "${Schema.COLUMN_FIRST_SEEN_AT}, ${Schema.COLUMN_LAST_SEEN_AT}) " +
                        "SELECT ?, ${Schema.COLUMN_VOLUME_NAME}, ?, ?, " +
                        "${Schema.COLUMN_SIZE_BYTES}, ${Schema.COLUMN_DATE_TAKEN}, " +
                        "${Schema.COLUMN_DATE_SOURCE}, ${Schema.COLUMN_MEDIA_TYPE}, " +
                        "${Schema.COLUMN_WIDTH}, ${Schema.COLUMN_HEIGHT}, " +
                        "${Schema.COLUMN_DURATION_MILLIS}, ${Schema.COLUMN_CONTENT_HASH}, " +
                        "${Schema.COLUMN_DATE_SUSPECT}, ?, ? " +
                        "FROM ${Schema.TABLE_PHOTOS} WHERE ${Schema.COLUMN_ID} = ?",
                listOf(
                    newMediaId, displayName, relativePath, now, now, originalPhotoId
                )
            )

            // The copy is that photograph, already where the proposal about
            // the original asked it to be: written as truth from the start,
            // since deciding it again is work the user has already done.
            database.execute(
                "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE} " +
                        "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                        "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT}) " +
                        "VALUES (?, ?, ?, ?)",
                listOf(copyId, status.storedValue, destinationId, now)
            )
            copyId
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
    /**
     * Every photo the archive currently holds.
     *
     * For the questions that are about the archive as a whole rather than
     * about a list of photos — finding what is held twice, above all, where
     * leaving anything out would mean missing exactly the pair being looked
     * for.
     */
    fun loadAllPresent(): Result<List<PhotoRecord>> = runCatching {
        presentRecords().toList()
    }

    fun loadRecords(photoIds: List<Long>): Result<List<PhotoRecord>> = runCatching {
        if (photoIds.isEmpty()) return@runCatching emptyList()
        val wanted = photoIds.toHashSet()

        presentRecords().filter { it.photoId in wanted }
    }

    /** Every present photo as a record, newest first. */
    private fun presentRecords(): List<PhotoRecord> = recordsWhere()

    /**
     * Present photos as records, narrowed by [condition] and [limit].
     *
     * Bugfix: callers that wanted a handful used to read the whole
     * inventory and filter it in memory. Fetching two hundred photos then
     * cost twenty-four thousand rows, and the pass that reads every
     * picture paid it once per batch — the archive walked a hundred times
     * over to walk it once.
     */
    private fun recordsWhere(
        condition: String = "",
        args: List<Any?> = emptyList(),
        limit: Int? = null
    ): List<PhotoRecord> = database.query(
        "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_MEDIA_ID}, " +
                "${Schema.COLUMN_VOLUME_NAME}, ${Schema.COLUMN_DISPLAY_NAME}, " +
                "${Schema.COLUMN_RELATIVE_PATH}, ${Schema.COLUMN_SIZE_BYTES}, " +
                "${Schema.COLUMN_DATE_TAKEN}, ${Schema.COLUMN_DATE_SOURCE}, " +
                "${Schema.COLUMN_CONTENT_HASH}, ${Schema.COLUMN_IMAGE_HASH} " +
                "FROM ${Schema.TABLE_PHOTOS} WHERE ${Schema.COLUMN_MISSING_SINCE} IS NULL " +
                condition + " ORDER BY ${Schema.COLUMN_DATE_TAKEN} DESC" +
                (limit?.let { " LIMIT $it" } ?: ""),
        args
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
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
                ?: CaptureDateResolver.Source.FILE_TIMESTAMP,
            contentHash = row.getString(Schema.COLUMN_CONTENT_HASH),
            imageHash = row.getString(Schema.COLUMN_IMAGE_HASH)?.takeIf { it != NO_PICTURE }
        )
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

    /** Forgets the last scan, so the next one runs however recent it was. */
    fun forgetFullScan(volumeName: String): Result<Unit> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_SYNC_STATE} WHERE ${Schema.COLUMN_VOLUME_NAME} = ?",
                listOf(volumeName)
            )
            Unit
        }
    }

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
                "${Schema.COLUMN_SIZE_BYTES}, ${Schema.COLUMN_DATE_TAKEN}, " +
                "${Schema.COLUMN_CONTENT_HASH}, ${Schema.COLUMN_MISSING_SINCE} " +
                "FROM ${Schema.TABLE_PHOTOS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
        PhotoMatcher.Stored(
            photoId = photoId,
            mediaId = row.getLong(Schema.COLUMN_MEDIA_ID),
            displayName = row.getString(Schema.COLUMN_DISPLAY_NAME).orEmpty(),
            sizeBytes = row.getLong(Schema.COLUMN_SIZE_BYTES) ?: 0L,
            dateTakenMillis = row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L,
            contentHash = row.getString(Schema.COLUMN_CONTENT_HASH),
            alreadyMissing = row.getLong(Schema.COLUMN_MISSING_SINCE) != null
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
                    "${Schema.COLUMN_LAST_SEEN_AT} = ?, ${Schema.COLUMN_MISSING_SINCE} = NULL, " +
                    // A file whose length changed is not the file the
                    // fingerprints describe. Clearing them costs one
                    // re-reading; keeping them would answer "same picture"
                    // about bytes nobody has looked at.
                    "${Schema.COLUMN_CONTENT_HASH} = CASE WHEN ${Schema.COLUMN_SIZE_BYTES} = ? " +
                    "THEN ${Schema.COLUMN_CONTENT_HASH} END, " +
                    "${Schema.COLUMN_IMAGE_HASH} = CASE WHEN ${Schema.COLUMN_SIZE_BYTES} = ? " +
                    "THEN ${Schema.COLUMN_IMAGE_HASH} END " +
                    "WHERE ${Schema.COLUMN_ID} = ?",
            listOf(
                record.platformId, record.volumeName, record.displayName, record.relativePath,
                record.sizeBytes, dateMillis, dateSource.name, now,
                record.sizeBytes, record.sizeBytes, photoId
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
