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

        /**
         * How deep it sits among the folders offered, so a list can indent
         * it. Not the depth of the path: five segments collapsed into one
         * step must indent by one, or the tree looks broken.
         */
        val depth: Int,

        /**
         * What to call it: the part of the path below the folder above it.
         *
         * A collapsed run would otherwise vanish silently — Android showing
         * a child called "WhatsApp Images" says nothing about the four
         * levels in between, and the user cannot tell where it came from.
         */
        val label: String,

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
        val insieme = offerti.toHashSet()
        val genitori = offerti.mapNotNull { offertoSopra(it, insieme) }.toHashSet()

        return offerti.map { path ->
            val sopra = offertoSopra(path, insieme)
            Node(
                relativePath = path,
                depth = if (sopra == null) 0 else path.removePrefix("$sopra/").let {
                    depthOf(sopra, insieme) + 1
                },
                label = if (sopra == null) path else path.removePrefix("$sopra/"),
                photoCount = below[path] ?: 0,
                ownPhotoCount = own[path] ?: 0,
                hasChildren = path in genitori
            )
        }
    }

    /** The nearest folder above [path] that is itself offered. */
    private fun offertoSopra(path: String, offerti: Set<String>): String? {
        var parent = parentOf(path)
        while (parent != null) {
            if (parent in offerti) return parent
            parent = parentOf(parent)
        }
        return null
    }

    /** How many offered folders stand between [path] and the top. */
    private fun depthOf(path: String, offerti: Set<String>): Int {
        var depth = 0
        var sopra = offertoSopra(path, offerti)
        while (sopra != null) {
            depth++
            sopra = offertoSopra(sopra, offerti)
        }
        return depth
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
