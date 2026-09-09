package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.port.Database

/**
 * Everything the app knows about each photograph, gathered in one place.
 *
 * The same content the backup writes out as JSON, read back into one record
 * per photograph instead of one array per table. A phone is not a desk with
 * a database browser on it, and the questions asked of this are always about
 * a single photograph — what became of that one, where has it been, why does
 * the app still call it missing — so the tables are joined here rather than
 * left for the reader to join by eye.
 *
 * Reads every photo, the ones no scan can find included: a photo deleted
 * from outside the app is exactly the case somebody comes here to look at,
 * and leaving it out would answer the question by hiding it.
 */
class InventoryDump(private val database: Database) {

    /** One place a photograph has been, and when it was written down. */
    data class Step(
        val kind: String,
        val path: String,
        val displayName: String?,
        val recordedAt: Long
    )

    /** One photograph, with everything recorded about it. */
    data class Entry(
        val photoId: Long,
        val mediaId: Long?,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long,
        val dateTakenMillis: Long,
        val dateSource: String?,
        val contentHash: String?,
        val imageHash: String?,
        val missingSince: Long?,
        val dateSuspect: Boolean,
        val status: String?,
        val statusAt: Long?,
        val category: String?,
        val proposedAction: String?,
        val proposedCategory: String?,
        val proposedAt: Long?,
        val stars: Int?,
        val tags: List<String>,
        val ignoredBy: List<String>,
        val history: List<Step>
    ) {

        /**
         * When anything last happened to this photograph.
         *
         * The most recent of the three things that are written down as they
         * happen: what became of it, what was asked of it, and where it went.
         * Sorting by this puts what the app touched last at the top, which is
         * the only order that answers "what did it just do?".
         */
        val lastActionAt: Long
            get() = maxOf(
                statusAt ?: 0L,
                proposedAt ?: 0L,
                history.maxOfOrNull { it.recordedAt } ?: 0L
            )

        /** Everything a search should look through, folded to one string. */
        val searchable: String
            get() = (listOf(
                displayName, relativePath, status.orEmpty(), category.orEmpty(),
                proposedAction.orEmpty(), proposedCategory.orEmpty(),
                contentHash.orEmpty(), imageHash.orEmpty(), photoId.toString(),
                mediaId?.toString().orEmpty()
            ) + tags + ignoredBy + history.map { it.path + it.displayName.orEmpty() })
                .joinToString(" ")
                .lowercase()
    }

    /** How the list is ordered. */
    enum class Order {

        /** What the app touched last, first. */
        LAST_ACTION,

        /** Newest photograph first, by the date it was taken. */
        TAKEN,

        /** By folder and then name, which is how a file manager shows them. */
        PLACE
    }

    /** Reads the whole inventory. Must not run on the main thread. */
    fun load(): Result<List<Entry>> = runCatching {
        val categories = loadCategories()
        val states = loadStates()
        val proposals = loadProposals()
        val stars = loadStars()
        val tags = loadTags()
        val ignored = loadIgnored()
        val history = loadHistory()

        readPhotos().map { photo ->
            val state = states[photo.photoId]
            val proposal = proposals[photo.photoId]
            photo.copy(
                status = state?.first,
                statusAt = state?.third,
                category = categories[state?.second],
                proposedAction = proposal?.first,
                proposedCategory = categories[proposal?.second],
                proposedAt = proposal?.third,
                stars = stars[photo.photoId],
                tags = tags[photo.photoId].orEmpty(),
                ignoredBy = ignored[photo.photoId].orEmpty(),
                history = history[photo.photoId].orEmpty()
            )
        }
    }

    private fun readPhotos(): List<Entry> = database.query(
        "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_MEDIA_ID}, " +
                "${Schema.COLUMN_DISPLAY_NAME}, ${Schema.COLUMN_RELATIVE_PATH}, " +
                "${Schema.COLUMN_SIZE_BYTES}, ${Schema.COLUMN_DATE_TAKEN}, " +
                "${Schema.COLUMN_DATE_SOURCE}, ${Schema.COLUMN_CONTENT_HASH}, " +
                "${Schema.COLUMN_IMAGE_HASH}, ${Schema.COLUMN_MISSING_SINCE}, " +
                "${Schema.COLUMN_DATE_SUSPECT} FROM ${Schema.TABLE_PHOTOS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
        Entry(
            photoId = photoId,
            mediaId = row.getLong(Schema.COLUMN_MEDIA_ID),
            displayName = row.getString(Schema.COLUMN_DISPLAY_NAME).orEmpty(),
            relativePath = row.getString(Schema.COLUMN_RELATIVE_PATH).orEmpty(),
            sizeBytes = row.getLong(Schema.COLUMN_SIZE_BYTES) ?: 0L,
            dateTakenMillis = row.getLong(Schema.COLUMN_DATE_TAKEN) ?: 0L,
            dateSource = row.getString(Schema.COLUMN_DATE_SOURCE),
            contentHash = row.getString(Schema.COLUMN_CONTENT_HASH),
            imageHash = row.getString(Schema.COLUMN_IMAGE_HASH)
                ?.takeIf { it != PhotoInventory.NO_PICTURE },
            missingSince = row.getLong(Schema.COLUMN_MISSING_SINCE),
            dateSuspect = (row.getInt(Schema.COLUMN_DATE_SUSPECT) ?: 0) != 0,
            status = null, statusAt = null, category = null,
            proposedAction = null, proposedCategory = null, proposedAt = null,
            stars = null, tags = emptyList(), ignoredBy = emptyList(),
            history = emptyList()
        )
    }

    private fun loadCategories(): Map<Long?, String> = database.query(
        "SELECT ${Schema.COLUMN_ID}, ${Schema.COLUMN_LABEL} FROM ${Schema.TABLE_DESTINATIONS}"
    ).mapNotNull { row ->
        val id = row.getLong(Schema.COLUMN_ID) ?: return@mapNotNull null
        id as Long? to row.getString(Schema.COLUMN_LABEL).orEmpty()
    }.toMap()

    /** Status, category and when it was written. */
    private fun loadStates(): Map<Long, Triple<String, Long?, Long>> = database.query(
        "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STATUS}, " +
                "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_UPDATED_AT} " +
                "FROM ${Schema.TABLE_PHOTO_STATE}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
        photoId to Triple(
            row.getString(Schema.COLUMN_STATUS).orEmpty(),
            row.getLong(Schema.COLUMN_DESTINATION_ID),
            row.getLong(Schema.COLUMN_UPDATED_AT) ?: 0L
        )
    }.toMap()

    /** Action, category and when it was asked. */
    private fun loadProposals(): Map<Long, Triple<String, Long?, Long>> = database.query(
        "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_ACTION}, " +
                "${Schema.COLUMN_DESTINATION_ID}, ${Schema.COLUMN_PROPOSED_AT} " +
                "FROM ${Schema.TABLE_PROPOSALS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
        photoId to Triple(
            row.getString(Schema.COLUMN_ACTION).orEmpty(),
            row.getLong(Schema.COLUMN_DESTINATION_ID),
            row.getLong(Schema.COLUMN_PROPOSED_AT) ?: 0L
        )
    }.toMap()

    private fun loadStars(): Map<Long, Int> = database.query(
        "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STARS} " +
                "FROM ${Schema.TABLE_PHOTO_RATINGS}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
        photoId to (row.getInt(Schema.COLUMN_STARS) ?: 0)
    }.toMap()

    private fun loadTags(): Map<Long, List<String>> = database.query(
        "SELECT pt.${Schema.COLUMN_PHOTO_ID} AS pid, t.${Schema.COLUMN_NAME} AS name " +
                "FROM ${Schema.TABLE_PHOTO_TAGS} pt JOIN ${Schema.TABLE_TAGS} t " +
                "ON t.${Schema.COLUMN_ID} = pt.${Schema.COLUMN_TAG_ID}"
    ).mapNotNull { row ->
        val photoId = row.getLong("pid") ?: return@mapNotNull null
        photoId to row.getString("name").orEmpty()
    }.groupBy({ it.first }, { it.second })

    private fun loadIgnored(): Map<Long, List<String>> = database.query(
        "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_CHECK} FROM ${Schema.TABLE_IGNORED}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
        photoId to row.getString(Schema.COLUMN_CHECK).orEmpty()
    }.groupBy({ it.first }, { it.second })

    /** Every place each photograph has been, oldest first. */
    private fun loadHistory(): Map<Long, List<Step>> = database.query(
        "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_PATH}, " +
                "${Schema.COLUMN_DISPLAY_NAME}, ${Schema.COLUMN_KIND}, " +
                "${Schema.COLUMN_RECORDED_AT} FROM ${Schema.TABLE_PHOTO_PATHS} " +
                "ORDER BY ${Schema.COLUMN_RECORDED_AT}"
    ).mapNotNull { row ->
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
        photoId to Step(
            kind = row.getString(Schema.COLUMN_KIND).orEmpty(),
            path = row.getString(Schema.COLUMN_PATH).orEmpty(),
            displayName = row.getString(Schema.COLUMN_DISPLAY_NAME),
            recordedAt = row.getLong(Schema.COLUMN_RECORDED_AT) ?: 0L
        )
    }.groupBy({ it.first }, { it.second })

    companion object {

        /**
         * The entries a search finds, in the chosen order.
         *
         * Every word has to match, and any of the recorded fields may be the
         * one that matches it: looking for "famiglia agosto" should find what
         * both words describe, whichever field each was found in. An empty
         * search matches everything, which is what an empty search means.
         */
        fun search(entries: List<Entry>, words: String, order: Order): List<Entry> {
            val wanted = words.lowercase().split(' ').filter { it.isNotBlank() }
            val found = entries.filter { entry ->
                wanted.all { word -> entry.searchable.contains(word) }
            }
            return when (order) {
                Order.LAST_ACTION -> found.sortedByDescending { it.lastActionAt }
                Order.TAKEN -> found.sortedByDescending { it.dateTakenMillis }
                Order.PLACE -> found.sortedBy { it.relativePath + it.displayName }
            }
        }
    }
}
