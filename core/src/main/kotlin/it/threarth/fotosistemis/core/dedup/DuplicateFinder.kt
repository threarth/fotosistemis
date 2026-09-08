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

        /**
         * Fingerprint of the picture with the header left out, when it has
         * been read.
         *
         * The stronger of the two for a JPEG: files agreeing here hold one
         * photograph even when their lengths differ, which is what happens
         * when the app copies a photo it cannot move and writes the capture
         * date into the copy. Null for a file that carries no readable
         * picture, and for one nobody has read yet.
         */
        val imageHash: String? = null,

        /** True when the user has already asked for this copy to go. */
        val discardRequested: Boolean = false,

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
         * is what makes a flat folder sort by time. Then an original beats
         * something another app made out of it. Alphabetical order settles
         * what is left, so the answer does not wander between runs.
         *
         * Sorting a list this way, rather than picking a winner, means the
         * reasons stay legible and stay in one place.
         */
        val suggested: Candidate
            get() = copies.sortedWith(
                // Never propose keeping a copy the user has already asked
                // to be rid of: that is an answer they have given.
                compareBy<Candidate> { it.discardRequested }
                    .thenByDescending { it.catalogued }
                    .thenBy { it.immovable }
                    .thenByDescending { it.stamped }
                    .thenBy { DuplicateFinder.isDerived(it.displayName) }
                    .thenBy { it.relativePath + it.displayName }
            ).first()
    }

    /**
     * True when the name says another app made this file out of another one.
     *
     * Measured on the archive, 8 September 2026: twenty-one pairs went to
     * the bin in one batch, every one of them `nome.jpg` against
     * `nome_saved.jpg` in the same folder — the trail a gallery app leaves
     * when it saves a picture back. Fifty to a hundred and twenty bytes
     * apart on two and a half megabytes, all of it metadata.
     *
     * Before this the choice fell through to the alphabet, which put
     * `..._01.jpg` before `..._01_saved.jpg` and so happened to be right;
     * a marker sorting the other way would have been just as happily
     * wrong. The point is not the outcome but that nothing was deciding
     * it. The camera's file is the one to keep: the other carries no
     * pixel the first has not got, and lost its metadata on the way.
     *
     * This only ever separates copies already known to hold **the same
     * picture** — a genuine edit has different pixels and never reaches
     * this comparison — so no version of a photograph is at stake here,
     * only which of two identical files survives.
     *
     * [MARKERS] holds what has been seen on the real archive and nothing
     * else. Guessing at `_edit`, `_copy` or `(1)` would mean choosing
     * between somebody's files on the strength of a hunch about a naming
     * habit nobody has observed.
     */
    fun isDerived(displayName: String): Boolean {
        val base = displayName.substringBeforeLast('.', displayName)

        return MARKERS.any { base.endsWith(it, ignoreCase = true) }
    }

    /** Endings another app appends to the name of a file it rewrote. */
    private val MARKERS = listOf("_saved")

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
        .mapNotNull { candidate -> sameness(candidate)?.let { it to candidate } }
        .groupBy({ it.first }, { it.second })
        .values
        .filter { it.size > 1 }
        .map { copies -> Group(copies.sortedBy { it.relativePath + it.displayName }) }
        .sortedByDescending { it.extra }

    /**
     * What makes two files the same photograph, or null when there is no
     * saying yet.
     *
     * Two answers, and the stronger is preferred where it exists. The
     * picture's fingerprint settles it on its own: files agreeing there
     * hold the same photograph whatever their lengths, and the length must
     * not be consulted — differing lengths are exactly the case it was
     * added for. Failing that, the older pairing stands: the head of the
     * file and its length together.
     *
     * The two keys are kept apart by a prefix so they can never collide,
     * and a file with neither is left out of the search: not knowing is not
     * the same as knowing two files differ, and a group is a proposal to
     * delete something.
     */
    private fun sameness(candidate: Candidate): String? = when {
        candidate.imageHash != null -> "$PICTURE_KEY${candidate.imageHash}"
        candidate.contentHash != null ->
            "$FILE_KEY${candidate.sizeBytes}:${candidate.contentHash}"

        else -> null
    }

    /** Marks a key made from the picture alone. */
    private const val PICTURE_KEY = "picture:"

    /** Marks a key made from the file's head and length. */
    private const val FILE_KEY = "file:"
}
