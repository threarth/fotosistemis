package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.PhotoRecord

/**
 * Works out which stored photo each record coming from the platform is.
 *
 * Pure on purpose: this is the one piece where being wrong loses a user's
 * work, and keeping it free of the database makes it testable without a
 * device.
 *
 * The platform identifier is only a shortcut. It changes when a card is
 * remounted or the media index is rebuilt, and a photo keyed by it alone
 * would come back as never seen, with its tags and decisions still in the
 * database but unreachable. So a record that fails to match on it is looked
 * up again by what actually belongs to the photograph.
 */
object PhotoMatcher {

    /** What the inventory already holds about one photo. */
    data class Stored(
        val photoId: Long,
        val mediaId: Long?,
        val displayName: String,
        val sizeBytes: Long,
        val dateTakenMillis: Long,

        /**
         * Taken from the bytes, when it has been taken at all.
         *
         * Every other criterion describes the photo from outside — what it is
         * called, how big it is, when it was taken — and all three can change
         * without the photograph changing. This one cannot: it is the only
         * thing here that a rename, a move and a rescan all leave alone.
         *
         * Held only for photos a decision has been made about: computing it
         * for an entire device would read every byte on it to protect work
         * that has not been done yet.
         */
        val contentHash: String? = null,

        /** True when a previous scan had already given this photo up. */
        val alreadyMissing: Boolean = false
    )

    /** How a record was recognised, worst case last. */
    enum class MatchKind {

        /** Recognised by its own bytes. */
        CONTENT_HASH,

        /** The platform identifier still points at the right photo. */
        PLATFORM_ID,

        /** Same size, date and name: the identifier had gone stale. */
        FINGERPRINT,

        /** Same size and date, renamed since. Only when unambiguous. */
        SIZE_AND_DATE,

        /** Nothing in the inventory matches: a photo we have not met. */
        NEW
    }

    data class Match(
        val record: PhotoRecord,
        val photoId: Long?,
        val kind: MatchKind
    ) {

        /**
         * True when the stored platform identifier has to be rewritten.
         * Recognising a photo the hard way is also the moment to make the
         * easy way work again, so the anomaly repairs itself.
         */
        val needsRekey: Boolean
            get() = kind == MatchKind.FINGERPRINT || kind == MatchKind.SIZE_AND_DATE
    }

    /** Outcome of comparing the inventory against what the platform reports. */
    data class Plan(
        val matches: List<Match>,

        /** Photos in the inventory that the platform no longer reports. */
        val missingPhotoIds: List<Long>,

        /**
         * Of those, the ones this scan is the first to miss.
         *
         * A row already known to be gone is missed again by every scan for
         * ever, so the plain total is the backlog of all time and never
         * changes for the better. What a reader needs is what happened now.
         */
        val newlyMissingPhotoIds: List<Long> = emptyList()
    ) {
        val newCount: Int get() = matches.count { it.kind == MatchKind.NEW }
        val rekeyedCount: Int get() = matches.count { it.needsRekey }
    }

    /**
     * Matches [records] against [stored].
     *
     * [stored] must cover the whole inventory, not just the folder being
     * looked at: a photo recognised by fingerprint may have been filed
     * somewhere else entirely since it was last seen.
     *
     * Missing photos are only reported when [recordsAreComplete]: a scan of
     * one folder cannot tell whether a photo absent from it is gone or
     * merely elsewhere.
     */
    fun match(
        stored: Collection<Stored>,
        records: List<PhotoRecord>,
        recordsAreComplete: Boolean = false
    ): Plan {
        val byMediaId = HashMap<Long, Stored>(stored.size)
        val byFingerprint = HashMap<String, Stored>(stored.size)
        val bySizeAndDate = HashMap<String, MutableList<Stored>>()
        val byHash = HashMap<String, Stored>()

        for (entry in stored) {
            entry.contentHash?.let { byHash[it] = entry }
            entry.mediaId?.let { byMediaId[it] = entry }
            byFingerprint[fingerprintOf(entry)] = entry
            bySizeAndDate.getOrPut(sizeAndDateOf(entry)) { ArrayList() }.add(entry)
        }

        val matches = ArrayList<Match>(records.size)
        val claimed = HashSet<Long>(records.size)

        for (record in records) {
            val found = findMatch(
                record, byMediaId, byFingerprint, bySizeAndDate, byHash, claimed
            )
            found.photoId?.let { claimed.add(it) }
            matches.add(found)
        }

        val missing = if (!recordsAreComplete) emptyList()
        else stored.map { it.photoId }.filterNot { claimed.contains(it) }
        val alreadyGone = stored.filter { it.alreadyMissing }.map { it.photoId }.toHashSet()

        return Plan(matches, missing, missing.filterNot { it in alreadyGone })
    }

    /** Runs the cascade for one record, skipping photos already claimed. */
    private fun findMatch(
        record: PhotoRecord,
        byMediaId: Map<Long, Stored>,
        byFingerprint: Map<String, Stored>,
        bySizeAndDate: Map<String, List<Stored>>,
        byHash: Map<String, Stored>,
        claimed: Set<Long>
    ): Match {
        // First, because it is the only criterion that cannot be wrong: a
        // photo renamed, moved and re-indexed still hashes to itself.
        record.contentHash?.let { hash ->
            byHash[hash]
                ?.takeIf { it.photoId !in claimed }
                ?.let { return Match(record, it.photoId, MatchKind.CONTENT_HASH) }
        }

        byMediaId[record.platformId]
            ?.takeIf { it.photoId !in claimed }
            ?.let { return Match(record, it.photoId, MatchKind.PLATFORM_ID) }

        byFingerprint[fingerprintOf(record)]
            ?.takeIf { it.photoId !in claimed }
            ?.let { return Match(record, it.photoId, MatchKind.FINGERPRINT) }

        // Only when a single photo has that size and date. Several would mean
        // guessing which one, and a wrong guess moves someone's tags onto
        // someone else's photograph.
        val candidates = bySizeAndDate[sizeAndDateOf(record)]
            ?.filter { it.photoId !in claimed }
            .orEmpty()
        if (candidates.size == 1) {
            return Match(record, candidates.first().photoId, MatchKind.SIZE_AND_DATE)
        }

        return Match(record, null, MatchKind.NEW)
    }

    private fun fingerprintOf(entry: Stored) =
        "${entry.sizeBytes}|${entry.dateTakenMillis}|${entry.displayName}"

    private fun fingerprintOf(record: PhotoRecord) =
        "${record.sizeBytes}|${record.dateTakenMillis}|${record.displayName}"

    private fun sizeAndDateOf(entry: Stored) = "${entry.sizeBytes}|${entry.dateTakenMillis}"

    private fun sizeAndDateOf(record: PhotoRecord) = "${record.sizeBytes}|${record.dateTakenMillis}"
}
