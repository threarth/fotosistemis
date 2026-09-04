package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.model.FolderSummary

/**
 * Turns the flat list of folders holding photos into something choosable.
 *
 * The platform reports only the folders that directly contain photos, which
 * on a phone is dozens of unrelated leaves. Picking an archive means picking
 * the branch it lives on — Pictures/storage-0, not each of its eighteen event
 * folders — so the branches have to be reconstructed and counted.
 */
object FolderTree {

    private const val SEPARATOR = "/"

    /** How many children a folder needs before it is worth offering on its own. */
    private const val BRANCHING_CHILDREN = 2

    /** A folder that can be chosen, with everything beneath it counted. */
    data class Node(

        /** Path without a trailing separator, as the settings store it. */
        val relativePath: String,

        /** How deep it sits, so a list can indent it. */
        val depth: Int,

        /** Photos in this folder and in every folder below it. */
        val photoCount: Int,

        /** Photos sitting directly in it, ignoring what is below. */
        val ownPhotoCount: Int,

        /** True when other offered folders hang from this one. */
        val hasChildren: Boolean
    )

    /**
     * Folders worth offering as a source, in path order.
     *
     * A folder earns a place when it holds photos itself or when the tree
     * forks under it. The rule exists to collapse the long single-child runs
     * that app folders produce: Android/media/com.whatsapp/WhatsApp/Media
     * says nothing that its one child does not already say.
     */
    fun candidates(folders: List<FolderSummary>): List<Node> {
        val own = HashMap<String, Int>()
        val below = HashMap<String, Int>()
        val children = HashMap<String, MutableSet<String>>()

        for (folder in folders) {
            val path = folder.relativePath.trim('/')
            if (path.isEmpty()) continue
            own[path] = (own[path] ?: 0) + folder.photoCount

            for (ancestor in ancestorsOf(path)) {
                below[ancestor] = (below[ancestor] ?: 0) + folder.photoCount
                val parent = parentOf(ancestor)
                if (parent != null) children.getOrPut(parent) { HashSet() }.add(ancestor)
            }
        }

        val offerti = below.keys.filter { worthOffering(it, own, children) }.sorted()
        val genitori = offerti.mapNotNull(::parentOf).toHashSet()

        return offerti.map { path ->
            Node(
                relativePath = path,
                depth = path.count { it == '/' },
                photoCount = below[path] ?: 0,
                ownPhotoCount = own[path] ?: 0,
                hasChildren = path in genitori
            )
        }
    }

    /**
     * The offered folders visible when [expanded] are the open ones.
     *
     * A folder shows when every offered ancestor of it is open, so the list
     * starts as the top level alone and grows only where the user looks.
     */
    fun visible(nodes: List<Node>, expanded: Set<String>): List<Node> {
        val offerti = nodes.map { it.relativePath }.toHashSet()

        return nodes.filter { node ->
            ancestorsOf(node.relativePath)
                .dropLast(1)
                .filter { it in offerti }
                .all { it in expanded }
        }
    }

    /** True when [path] is inside [root], or is [root] itself. */
    fun isWithin(path: String, root: String): Boolean {
        val clean = path.trim('/')
        val anchor = root.trim('/')
        if (anchor.isEmpty()) return true

        return clean.equals(anchor, ignoreCase = true) ||
                clean.startsWith("$anchor$SEPARATOR", ignoreCase = true)
    }

    /** A folder is offered when it holds photos, or when the tree forks in it. */
    private fun worthOffering(
        path: String,
        own: Map<String, Int>,
        children: Map<String, MutableSet<String>>
    ): Boolean {
        if ((own[path] ?: 0) > 0) return true
        if (parentOf(path) == null) return true

        return (children[path]?.size ?: 0) >= BRANCHING_CHILDREN
    }

    /** Every prefix of [path], itself included, shortest first. */
    private fun ancestorsOf(path: String): List<String> {
        val segments = path.split(SEPARATOR).filter { it.isNotEmpty() }
        return segments.indices.map { segments.take(it + 1).joinToString(SEPARATOR) }
    }

    /** The folder containing [path], or null when it is already top level. */
    private fun parentOf(path: String): String? =
        path.substringBeforeLast(SEPARATOR, "").takeIf { it.isNotEmpty() }
}
