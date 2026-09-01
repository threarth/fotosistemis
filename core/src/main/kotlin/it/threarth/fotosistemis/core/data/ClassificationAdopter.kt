package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination

/**
 * Reads the classification already present on disk.
 *
 * Photos filed into category folders before the app knew about them, by a
 * previous install or by hand, are indistinguishable from unreviewed ones:
 * the app would offer them for review again and the user would redo work
 * already done. After moving to another phone, that is the whole archive.
 *
 * Where a photo sits is itself a statement about it, and this turns that
 * statement into a recorded decision. Nothing is moved.
 */
object ClassificationAdopter {

    /**
     * Shape a folder must have to count as classified: a category holding
     * year folders, such as Pictures/Famiglia/2026-famiglia.
     *
     * Requiring the year is what keeps the reading honest. Without it any
     * folder under Pictures would look like a category, and screenshots or a
     * download folder would be adopted as though they had been sorted.
     */
    private val YEAR_IN_NAME = Regex("""(?:^|[^0-9])((?:19|20)\d{2})(?:[^0-9]|$)""")

    /**
     * Folders searched for that shape when the user has named none.
     *
     * A root may be several segments deep, such as Pictures/storage-1: what
     * matters is that categories sit directly inside it.
     */
    val DEFAULT_ROOTS = listOf("Pictures", "DCIM")

    /** One photo as the inventory holds it. */
    data class InventoryEntry(
        val photoId: Long,
        val relativePath: String,
        val displayName: String
    )

    /** A photo recognised as filed, and the category it sits in. */
    data class Candidate(
        val photoId: Long,
        val relativePath: String,
        val displayName: String,
        val categoryLabel: String,

        /** Folder it belongs to, or null when that folder is not known yet. */
        val destinationId: Long?
    )

    /** A category found on disk that the app has no destination for. */
    data class ProposedCategory(
        val label: String,
        val relativePath: String,
        val photoCount: Int
    )

    /** What adopting would record, stated before anything is written. */
    data class Proposal(
        val candidates: List<Candidate>,
        val proposedCategories: List<ProposedCategory>
    ) {
        val total: Int get() = candidates.size

        /** Photos per category, for showing what is about to happen. */
        val byCategory: Map<String, Int>
            get() = candidates.groupingBy { it.categoryLabel }.eachCount()

        /** True when accepting would also create folders. */
        val createsCategories: Boolean get() = proposedCategories.isNotEmpty()
    }

    /**
     * Works out which photos are already filed, and under what.
     *
     * Categories are read from the paths rather than taken from
     * [destinations] alone: on a new phone the folders exist on disk while
     * the app knows nothing of them, so insisting on a destination that
     * already exists would find nothing.
     *
     * A decision already recorded is never overwritten, whatever the folder
     * suggests.
     */
    fun propose(
        inventory: List<InventoryEntry>,
        destinations: List<Destination>,
        alreadyDecided: Set<Long>,
        roots: List<String> = DEFAULT_ROOTS
    ): Proposal {
        val knownByPath = destinations.associateBy { it.relativePath.trim('/').lowercase() }
        val candidates = ArrayList<Candidate>()
        val discovered = LinkedHashMap<String, ProposedCategory>()

        for (entry in inventory) {
            if (entry.photoId in alreadyDecided) continue
            val category = categoryOf(entry.relativePath, roots) ?: continue

            val known = knownByPath[category.path.lowercase()]
            candidates.add(
                Candidate(
                    entry.photoId,
                    entry.relativePath,
                    entry.displayName,
                    known?.label ?: category.label,
                    known?.id
                )
            )
            if (known == null) {
                val existing = discovered[category.path]
                discovered[category.path] = ProposedCategory(
                    category.label,
                    category.path,
                    (existing?.photoCount ?: 0) + 1
                )
            }
        }
        return Proposal(candidates, discovered.values.sortedByDescending { it.photoCount })
    }

    /** The category folder a photo sits in, when its path has that shape. */
    private data class Category(val label: String, val path: String)

    /**
     * Reads category and year folder out of a path, under one of [roots].
     *
     * Exactly two segments below the root, never more: the rule stays as
     * strict as it was, only its anchor moved. A transfer from another phone
     * can bury a whole archive one level down — Phone Clone writes
     * Pictures/storage-1/Famiglia/2026-famiglia — and a fixed root would find
     * nothing there, while accepting any depth would read every pair of
     * nested folders as a category.
     */
    private fun categoryOf(relativePath: String, roots: List<String>): Category? {
        val path = relativePath.trim('/')

        for (root in roots) {
            val anchor = root.trim('/')
            val inside = below(path, anchor) ?: continue
            val segments = inside.split('/').filter { it.isNotEmpty() }
            if (segments.size != 2) continue

            val category = segments[0]
            if (!YEAR_IN_NAME.containsMatchIn(segments[1])) continue

            // A category named after a year would mean the shape was read one
            // level off, with the real categories sitting deeper.
            if (YEAR_IN_NAME.containsMatchIn(category)) continue

            return Category(label = category, path = "$anchor/$category")
        }
        return null
    }

    /** What is left of [path] under [root], or null when it is not under it. */
    private fun below(path: String, root: String): String? {
        if (root.isEmpty()) return path
        if (!path.startsWith("$root/", ignoreCase = true)) return null

        return path.substring(root.length + 1)
    }
}
