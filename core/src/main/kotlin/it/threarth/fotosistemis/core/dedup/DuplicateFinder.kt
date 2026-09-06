package it.threarth.fotosistemis.core.dedup

/**
 * Finds photographs the archive holds more than once.
 *
 * Two files of the same length are not the same photograph, and treating
 * them as such would offer to delete somebody's picture on the strength of a
 * coincidence. Only the bytes settle it. But hashing an archive of tens of
 * thousands of files to find a handful of duplicates costs more than the
 * answer is worth, so the work is done in two passes: the length narrows the
 * field for nothing, and the hash decides among what is left.
 *
 * Nothing here chooses what to delete. It says which files are the same
 * photograph; which copy to keep is a judgement, and belongs to the user.
 */
object DuplicateFinder {

    /** One file as the search needs to see it. */
    data class Candidate(
        val photoId: Long,
        val relativePath: String,
        val displayName: String,
        val sizeBytes: Long,

        /** Filled only for files whose length is shared with another. */
        val contentHash: String? = null,

        /** True when a decision has been recorded about this copy. */
        val catalogued: Boolean = false,

        /** True when the platform will not let this copy be moved. */
        val immovable: Boolean = false,

        /** True when the name carries the app's date stamp. */
        val stamped: Boolean = false
    )

    /** One photograph, and every file holding it. */
    data class Group(val copies: List<Candidate>) {

        /** How many files could go without the photograph being lost. */
        val extra: Int get() = copies.size - 1

        /**
         * The copy worth proposing to keep — a proposal, never a decision.
         *
         * Filed beats unfiled: work has been done on that copy, and throwing
         * it away would throw the work away with it. Then movable beats
         * immovable, because a copy inside another app's folder cannot be
         * organised, dated or renamed ever again, and discarding it costs
         * only Android's bin. Then stamped beats unstamped, since the stamp
         * is what makes a flat folder sort by time. Alphabetical order
         * settles what is left, so the answer does not wander between runs.
         *
         * Sorting a list this way, rather than picking a winner, means the
         * reasons stay legible and stay in one place.
         */
        val suggested: Candidate
            get() = copies.sortedWith(
                compareByDescending<Candidate> { it.catalogued }
                    .thenBy { it.immovable }
                    .thenByDescending { it.stamped }
                    .thenBy { it.relativePath + it.displayName }
            ).first()
    }

    /**
     * Files worth hashing: those sharing a length with at least one other.
     *
     * A length nobody else has cannot be a duplicate of anything, and asking
     * the disk about it would be work spent to learn nothing.
     */
    fun needingHash(candidates: List<Candidate>): List<Candidate> {
        val counts = candidates.groupingBy { it.sizeBytes }.eachCount()

        return candidates.filter { (counts[it.sizeBytes] ?: 0) > 1 }
    }

    /**
     * Groups the files that hold the same photograph.
     *
     * Both length and hash must agree. The hash alone would be enough in
     * principle, but it is taken over the head of the file rather than all
     * of it, and pairing it with the length costs nothing and closes the
     * one case where a head could repeat.
     *
     * A file whose hash was never taken is left out: not knowing is not the
     * same as knowing they differ, and a group is a proposal to delete
     * something.
     */
    fun groups(candidates: List<Candidate>): List<Group> = candidates
        .filter { it.contentHash != null }
        .groupBy { it.sizeBytes to it.contentHash }
        .values
        .filter { it.size > 1 }
        .map { copies -> Group(copies.sortedBy { it.relativePath + it.displayName }) }
        .sortedByDescending { it.extra }
}
