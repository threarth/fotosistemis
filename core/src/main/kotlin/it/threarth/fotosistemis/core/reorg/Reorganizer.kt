package it.threarth.fotosistemis.core.reorg

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import java.util.Calendar
import java.util.Locale

/**
 * Works out what has to be written on disk to give each category the shape
 * the user asked for.
 *
 * The layout is a projection of the inventory, not a state to be guarded:
 * holding paths, names and dates in the database means the arrangement can be
 * recomputed whenever the user changes their mind. Nothing here touches the
 * platform; the plan is produced whole so it can be shown before it happens.
 */
object Reorganizer {

    /**
     * How many photos sharing one day of file timestamps stop looking like a
     * day of shooting and start looking like an import.
     */
    private const val MIN_CLUSTER_SIZE = 10

    /** Year first here too, so the preview lists days in order. */
    private const val DAY_LABEL_FORMAT = "%04d-%02d-%02d"

    /** One photo already filed, as the planner needs it. */
    data class Entry(
        val photoId: Long,
        val destinationId: Long,
        val volumeName: String,
        val relativePath: String,
        val displayName: String,
        val captureMillis: Long,
        val source: CaptureDateResolver.Source
    )

    /**
     * What one category should become.
     *
     * [destination] carries the wanted state, renamed folder included, so the
     * path is built by the same code that builds it during a review.
     */
    data class Choice(val destination: Destination, val stampNames: Boolean)

    /** One photo that has to be moved, renamed, or both. */
    data class PlannedMove(
        val photoId: Long,
        val destinationId: Long,
        val categoryLabel: String,
        val fromRelativePath: String,
        val fromDisplayName: String,
        val toRelativePath: String,
        val toDisplayName: String,
        val uncertainDate: Boolean
    )

    /**
     * Two photos that would end up sharing a name.
     *
     * Only possible with the stamp turned off: flattening brings together
     * folders that were free to repeat a name while the years kept them
     * apart. Reported rather than resolved, because the fix is a decision —
     * turn the stamp on — and not something to make silently.
     */
    data class Conflict(
        val relativePath: String,
        val displayName: String,
        val photoIds: List<Long>
    )

    /** A day that holds far more file timestamps than a day should. */
    data class Cluster(val dayLabel: String, val photoCount: Int)

    /** What the reorganisation would do, stated before anything is written. */
    data class Plan(
        val moves: List<PlannedMove>,
        val conflicts: List<Conflict>,
        val unchanged: Int
    ) {
        val total: Int get() = moves.size

        /** Photos per category, for showing what is about to happen. */
        val byCategory: Map<String, Int>
            get() = moves.groupingBy { it.categoryLabel }.eachCount()

        /** How many would carry a date the app cannot vouch for. */
        val uncertainCount: Int get() = moves.count { it.uncertainDate }

        /** True when nothing can be applied until the user decides. */
        val isBlocked: Boolean get() = conflicts.isNotEmpty()

        /** Categories that still have photos to move. */
        val categoriesWithWork: Set<String>
            get() = moves.map { it.categoryLabel }.toSet()
    }

    /**
     * Plans the moves for every category named in [choices].
     *
     * Entries whose category is not mentioned are left exactly as they are:
     * reorganising one folder is not a reason to touch another.
     */
    fun plan(
        entries: List<Entry>,
        choices: List<Choice>,
        yearFolderPattern: String
    ): Plan {
        val byDestination = choices.associateBy { it.destination.id }
        val moves = ArrayList<PlannedMove>()
        val conflicts = ArrayList<Conflict>()
        var unchanged = 0

        for ((target, group) in groupByTargetFolder(entries, byDestination, yearFolderPattern)) {
            val choice = byDestination.getValue(group.first().destinationId)
            val names = nameGroup(group, choice.stampNames)

            conflicts.addAll(conflictsIn(target.relativePath, group, names))
            for (entry in group) {
                val name = names.getValue(entry.photoId)
                val move = moveFor(entry, choice, target.relativePath, name)
                if (move == null) unchanged++ else moves.add(move)
            }
        }
        return Plan(moves.sortedBy { it.photoId }, conflicts, unchanged)
    }

    /**
     * Days where file timestamps pile up.
     *
     * Four hundred photos sharing a date are not a day of shooting, they are
     * the day an archive was copied onto this phone. Worth seeing before that
     * date is written into four hundred file names.
     */
    fun clustersOf(entries: List<Entry>): List<Cluster> = entries
        .filter { it.source == CaptureDateResolver.Source.FILE_TIMESTAMP }
        .groupingBy { dayLabelOf(it.captureMillis) }
        .eachCount()
        .filter { it.value >= MIN_CLUSTER_SIZE }
        .map { Cluster(it.key, it.value) }
        .sortedByDescending { it.photoCount }

    /** A folder photos are heading for. Names only collide inside one. */
    private data class TargetFolder(val volumeName: String, val relativePath: String)

    /** Splits entries into the folders they are heading for. */
    private fun groupByTargetFolder(
        entries: List<Entry>,
        choices: Map<Long, Choice>,
        yearFolderPattern: String
    ): Map<TargetFolder, List<Entry>> = entries
        .filter { it.destinationId in choices }
        .groupBy { entry ->
            val destination = choices.getValue(entry.destinationId).destination
            TargetFolder(
                entry.volumeName,
                destination.pathFor(entry.captureMillis, yearFolderPattern)
            )
        }

    /**
     * The name each photo of one folder should take.
     *
     * With the stamp off the stamp is removed rather than left behind:
     * turning the option off has to undo what turning it on did, or the
     * choice would only work in one direction.
     */
    private fun nameGroup(group: List<Entry>, stampNames: Boolean): Map<Long, String> =
        if (!stampNames) {
            group.associate { it.photoId to CaptureDateResolver.stripStamp(it.displayName) }
        } else {
            FileNamer.nameAll(
                group.map {
                    FileNamer.Request(it.photoId, it.displayName, it.captureMillis, it.source)
                }
            ).associate { it.photoId to it.displayName }
        }

    /** Names claimed by more than one photo in the same folder. */
    private fun conflictsIn(
        relativePath: String,
        group: List<Entry>,
        names: Map<Long, String>
    ): List<Conflict> = group
        .groupBy { names.getValue(it.photoId) }
        .filterValues { it.size > 1 }
        .map { (name, sharing) -> Conflict(relativePath, name, sharing.map { it.photoId }) }

    /** The move for one photo, or null when it is already where it belongs. */
    private fun moveFor(
        entry: Entry,
        choice: Choice,
        toRelativePath: String,
        toDisplayName: String
    ): PlannedMove? {
        if (entry.relativePath == toRelativePath && entry.displayName == toDisplayName) return null

        return PlannedMove(
            photoId = entry.photoId,
            destinationId = choice.destination.id,
            categoryLabel = choice.destination.label,
            fromRelativePath = entry.relativePath,
            fromDisplayName = entry.displayName,
            toRelativePath = toRelativePath,
            toDisplayName = toDisplayName,
            uncertainDate = FileNamer.isUncertain(entry.source)
        )
    }

    /** The day a timestamp falls on, as the preview shows it. */
    private fun dayLabelOf(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.ROOT,
            DAY_LABEL_FORMAT,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }
}
