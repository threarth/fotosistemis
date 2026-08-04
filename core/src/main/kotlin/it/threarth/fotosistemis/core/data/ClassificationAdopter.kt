package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination

/**
 * Reads the classification already present on disk.
 *
 * Photos filed into destination folders before the app knew about them, by a
 * previous install or by hand, are indistinguishable from unreviewed ones:
 * the app would offer them for review again and the user would redo work
 * already done. Where a photo sits is itself a statement about it, and this
 * turns that statement into a recorded decision.
 *
 * Only photos with nothing recorded are considered. A decision already taken
 * is never overwritten, whatever the folder suggests.
 */
object ClassificationAdopter {

    /** A photo sitting in a destination folder, and where. */
    data class Candidate(
        val photoId: Long,
        val relativePath: String,
        val destination: Destination
    )

    /** What adopting would record, per destination. */
    data class Preview(val candidates: List<Candidate>) {

        val total: Int get() = candidates.size

        /** Counts per destination label, for showing before anything changes. */
        val byDestination: Map<String, Int>
            get() = candidates.groupingBy { it.destination.label }.eachCount()
    }

    /** One photo as the inventory holds it, for the purposes of adopting. */
    data class InventoryEntry(val photoId: Long, val relativePath: String)

    /**
     * Finds photos that sit inside a destination folder and carry no
     * decision yet.
     *
     * Matching is by path prefix rather than by rebuilding the expected year
     * folder name: that name is configurable and may have changed, and older
     * installs used a different one. Anything under the destination counts,
     * whatever the year folders happen to be called.
     */
    fun preview(
        inventory: List<InventoryEntry>,
        destinations: List<Destination>,
        alreadyDecided: Set<Long>
    ): Preview {
        // Longest path first, so a destination nested inside another wins
        // over the one containing it.
        val ordered = destinations.sortedByDescending { it.relativePath.trim('/').length }

        val candidates = inventory.mapNotNull { entry ->
            if (entry.photoId in alreadyDecided) return@mapNotNull null
            val destination = ordered.firstOrNull { contains(it, entry.relativePath) }
                ?: return@mapNotNull null
            Candidate(entry.photoId, entry.relativePath, destination)
        }
        return Preview(candidates)
    }

    /**
     * True when [photoPath] lies inside the folder of [destination].
     *
     * The comparison stops at a path separator, so Pictures/Famiglia does
     * not swallow Pictures/FamigliaAllargata.
     */
    private fun contains(destination: Destination, photoPath: String): Boolean {
        val root = destination.relativePath.trim('/')
        if (root.isEmpty()) return false
        val path = photoPath.trim('/')
        return path == root || path.startsWith("$root/")
    }
}
