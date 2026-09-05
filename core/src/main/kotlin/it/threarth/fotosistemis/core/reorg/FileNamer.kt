package it.threarth.fotosistemis.core.reorg

import it.threarth.fotosistemis.core.model.CaptureDateResolver

/**
 * Works out the name each photo should carry once its folder is flattened.
 *
 * Sorted by name, the names cameras produce group by device rather than by
 * time: DSC_, IMG_, PXL_ and Screenshot_ each form their own run, so a photo
 * from 2024 lands before one from 2018. Putting the capture date at the front
 * of every name restores the chronological order that the year folders used
 * to provide.
 *
 * The stamp goes on every photo, including those whose name already opens
 * with a date. Mixing stamped and unstamped names would split a folder into
 * two runs that never interleave, since the delimiter sorts after the digits;
 * one rule applied everywhere is also the only kind that can be checked at a
 * glance.
 */
object FileNamer {

    /**
     * Longest name a file may carry. The limit is per name, not per path,
     * and is counted in bytes because that is what the file system counts.
     */
    private const val MAX_NAME_BYTES = 255

    private const val EXTENSION_SEPARATOR = "."

    /** The first photo of a colliding group keeps no counter; the next is 2. */
    private const val FIRST_COUNTER = 2

    /** One photo, as the namer needs it. */
    data class Request(
        val photoId: Long,
        val displayName: String,
        val captureMillis: Long,
        val source: CaptureDateResolver.Source,

        /**
         * Where the photo came in from, recorded in the stamp when known.
         *
         * A photo that arrived through a chat app has been recompressed and
         * may be a second copy of one already here; marking it is what lets
         * those copies be found later, when a byte comparison cannot.
         */
        val origin: String? = null
    )

    /** The name a photo should carry, and how much its date is worth. */
    data class Naming(
        val photoId: Long,
        val displayName: String,
        val uncertain: Boolean
    ) {
        /** True when the file has to be renamed for this to hold. */
        fun changes(from: String): Boolean = displayName != from
    }

    /**
     * Names every photo that will end up sharing one folder.
     *
     * Collisions are only possible inside a single folder, so this must be
     * called once per destination folder: two photos with the same name in
     * different folders are not a conflict.
     *
     * A stamp already written is left as it is. It is only rewritten when
     * what the app knows now comes from a better source than what wrote it,
     * which is how a date recovered from EXIF later replaces one guessed
     * from a file name instead of piling up beside it.
     */
    fun nameAll(requests: List<Request>): List<Naming> {
        val dates = requests.associate { it.photoId to dateFor(it) }
        val byCandidate = requests.groupBy {
            candidateName(it, dates.getValue(it.photoId), counter = null)
        }
        val named = ArrayList<Naming>(requests.size)

        for (group in byCandidate.values) {
            // Ordering by id rather than by position keeps the counters
            // stable: a photo discovered later cannot renumber the others.
            for ((index, request) in group.sortedBy { it.photoId }.withIndex()) {
                val dated = dates.getValue(request.photoId)
                val counter = if (index == 0) null else index + FIRST_COUNTER - 1
                named.add(
                    Naming(
                        photoId = request.photoId,
                        displayName = candidateName(request, dated, counter),
                        uncertain = dated.uncertain
                    )
                )
            }
        }
        return named.sortedBy { it.photoId }
    }

    /**
     * True when the date is a fallback rather than a capture time.
     *
     * [CaptureDateResolver.Source.FILE_TIMESTAMP] describes when the file was
     * written on this device, which any copy resets, and ESTIMATED is a date
     * already judged to be exactly that.
     */
    fun isUncertain(source: CaptureDateResolver.Source): Boolean =
        source == CaptureDateResolver.Source.FILE_TIMESTAMP ||
                source == CaptureDateResolver.Source.ESTIMATED

    /** The date a photo's stamp should carry, and whether it is a guess. */
    private data class Dated(val millis: Long, val uncertain: Boolean, val origin: String?)

    /**
     * Decides between the stamp already on the file and what is known now.
     *
     * Ranking the two sources rather than always preferring the newer reading
     * is the same rule that keeps a capture date from drifting: a date is
     * never replaced by one from a source with less to say.
     */
    private fun dateFor(request: Request): Dated {
        // The origin is a fact about the file, not a reading of it: what is
        // known now wins over what an older stamp recorded.
        val origin = request.origin ?: CaptureDateResolver.readStamp(request.displayName)?.origin
        val existing = CaptureDateResolver.readStamp(request.displayName)
            ?: return Dated(request.captureMillis, isUncertain(request.source), origin)

        if (request.source.outranks(existing.source)) {
            return Dated(request.captureMillis, isUncertain(request.source), origin)
        }
        return Dated(existing.millis, isUncertain(existing.source), origin)
    }

    /** The full name a request would take with [counter]. */
    private fun candidateName(request: Request, dated: Dated, counter: Int?): String {
        val stamp = CaptureDateResolver.formatStamp(
            dated.millis, dated.uncertain, counter, dated.origin
        )
        return fit(stamp, CaptureDateResolver.stripStamp(request.displayName))
    }

    /**
     * Joins stamp and name, shortening the name when the two together would
     * not fit. The stamp is never shortened: a truncated date would be a
     * wrong date, while a truncated name is still recognisable, and the one
     * it started with is kept in the database.
     */
    private fun fit(stamp: String, bare: String): String {
        val extension = bare.substringAfterLast(EXTENSION_SEPARATOR, "")
        val suffix = if (extension.isEmpty()) "" else EXTENSION_SEPARATOR + extension
        var stem = bare.substringBeforeLast(EXTENSION_SEPARATOR, bare)

        while (stem.isNotEmpty() && byteLength(stamp + stem + suffix) > MAX_NAME_BYTES) {
            stem = stem.dropLast(1)
        }
        return stamp + stem + suffix
    }

    /** Length as the file system counts it. */
    private fun byteLength(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
