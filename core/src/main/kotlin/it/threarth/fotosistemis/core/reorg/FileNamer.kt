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
 * The stamp is written on every photo, not only on the ones whose name lacks
 * a date. A rule with exceptions cannot be checked at a glance, and the
 * redundancy on a name that already carried its date costs less than being
 * unsure which files were touched.
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
        val source: CaptureDateResolver.Source
    )

    /** The name a photo should carry, and whether that is a change. */
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
     * Any stamp already present is removed before the new one is written,
     * which is what makes the operation repeatable. Running it twice cannot
     * stack prefixes, an interrupted run converges when repeated, and a date
     * corrected later replaces the stamp instead of adding a second one.
     */
    fun nameAll(requests: List<Request>): List<Naming> {
        val byCandidate = requests.groupBy { candidateName(it, counter = null) }
        val named = ArrayList<Naming>(requests.size)

        for (group in byCandidate.values) {
            // Ordering by id rather than by position keeps the counters
            // stable: a photo discovered later cannot renumber the others.
            val ordered = group.sortedBy { it.photoId }
            for ((index, request) in ordered.withIndex()) {
                val counter = if (index == 0) null else index + FIRST_COUNTER - 1
                named.add(
                    Naming(
                        photoId = request.photoId,
                        displayName = candidateName(request, counter),
                        uncertain = isUncertain(request.source)
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

    /** The full name a request would take with [counter]. */
    private fun candidateName(request: Request, counter: Int?): String {
        val stamp = CaptureDateResolver.formatStamp(
            request.captureMillis,
            isUncertain(request.source),
            counter
        )
        val bare = CaptureDateResolver.stripStamp(request.displayName)
        return fit(stamp, bare)
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
