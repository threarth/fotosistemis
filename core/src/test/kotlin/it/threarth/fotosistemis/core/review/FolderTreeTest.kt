package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.model.FolderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that the branches of a real phone come back out of its flat list of
 * folders, counted and free of the runs that say nothing.
 */
class FolderTreeTest {

    private fun folder(path: String, count: Int) =
        FolderSummary(volumeName = "external_primary", relativePath = path, photoCount = count)

    private fun candidate(nodes: List<FolderTree.Node>, path: String) =
        nodes.firstOrNull { it.relativePath == path }

    @Test
    fun `a branch is counted with everything below it`() {
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-0/Vetralla", 72),
                folder("Pictures/storage-0/Marina", 3),
                folder("Pictures/Screenshots", 32)
            )
        )

        assertEquals(75, candidate(nodes, "Pictures/storage-0")?.photoCount)
        assertEquals(107, candidate(nodes, "Pictures")?.photoCount)
        assertEquals(0, candidate(nodes, "Pictures/storage-0")?.ownPhotoCount)
    }

    @Test
    fun `a run of single children collapses to what it leads to`() {
        // What an app folder looks like: five levels that all say the same
        // thing, and only the last one is worth choosing.
        val nodes = FolderTree.candidates(
            listOf(folder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 15719))
        ).map { it.relativePath }

        assertTrue(
            "La foglia si offre",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" in nodes
        )
        assertTrue("Il primo livello resta sempre", "Android" in nodes)
        assertTrue(
            "I passaggi intermedi spariscono",
            nodes.none { it == "Android/media" || it == "Android/media/com.whatsapp" }
        )
    }

    @Test
    fun `a fork is offered even when it holds no photo itself`() {
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-1/Famiglia/2026-famiglia", 268),
                folder("Pictures/storage-1/Casa/2026-casa", 48)
            )
        ).map { it.relativePath }

        assertTrue("Qui l'albero si biforca", "Pictures/storage-1" in nodes)
        assertTrue("Sotto no", "Pictures/storage-1/Famiglia" !in nodes)
    }

    @Test
    fun `a folder holding photos is always offered`() {
        val nodes = FolderTree.candidates(listOf(folder("DCIM/Camera", 5032)))
            .map { it.relativePath }

        assertTrue("DCIM" in nodes)
        assertTrue("DCIM/Camera" in nodes)
    }

    @Test
    fun `depth says how far to indent`() {
        // Two leaves, so the branch between them survives the collapsing.
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-0/Vetralla", 72),
                folder("Pictures/storage-0/Marina", 3)
            )
        )

        assertEquals(0, candidate(nodes, "Pictures")?.depth)
        assertEquals(1, candidate(nodes, "Pictures/storage-0")?.depth)
        assertEquals(2, candidate(nodes, "Pictures/storage-0/Vetralla")?.depth)
    }

    @Test
    fun `a lone leaf collapses the branch above it`() {
        val nodes = FolderTree.candidates(listOf(folder("Pictures/storage-0/Vetralla", 72)))
            .map { it.relativePath }

        assertTrue("La foglia c'e'", "Pictures/storage-0/Vetralla" in nodes)
        assertTrue("Il primo livello resta", "Pictures" in nodes)
        assertTrue("Il passaggio in mezzo no", "Pictures/storage-0" !in nodes)
    }

    @Test
    fun `being inside a root is decided by whole segments`() {
        assertTrue(FolderTree.isWithin("Pictures/storage-0/Vetralla", "Pictures/storage-0"))
        assertTrue("Una radice contiene se stessa", FolderTree.isWithin("DCIM", "DCIM"))
        assertTrue("Maiuscole indifferenti", FolderTree.isWithin("pictures/x", "Pictures"))
        assertTrue("Radice vuota vuol dire tutto", FolderTree.isWithin("qualsiasi/cosa", ""))
        assertTrue(
            "storage-1 non sta dentro storage-0",
            !FolderTree.isWithin("Pictures/storage-1", "Pictures/storage-0")
        )
    }

    @Test
    fun `the whole phone comes back in path order`() {
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-0/Vetralla", 72),
                folder("DCIM/Camera", 5032),
                folder("Download", 99)
            )
        ).map { it.relativePath }

        assertEquals(nodes.sorted(), nodes)
    }

    @Test
    fun `a node knows whether anything hangs from it`() {
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-0/Vetralla", 72),
                folder("Pictures/storage-0/Marina", 3)
            )
        )

        assertTrue(candidate(nodes, "Pictures")?.hasChildren == true)
        assertTrue(candidate(nodes, "Pictures/storage-0")?.hasChildren == true)
        assertTrue(candidate(nodes, "Pictures/storage-0/Vetralla")?.hasChildren == false)
    }

    @Test
    fun `closed folders hide what is under them`() {
        val nodes = FolderTree.candidates(
            listOf(
                folder("Pictures/storage-0/Vetralla", 72),
                folder("Pictures/storage-0/Marina", 3),
                folder("DCIM/Camera", 5032)
            )
        )

        val chiuso = FolderTree.visible(nodes, emptySet()).map { it.relativePath }
        assertEquals("Solo il primo livello", listOf("DCIM", "Pictures"), chiuso)

        val aperto = FolderTree.visible(nodes, setOf("Pictures")).map { it.relativePath }
        assertEquals(
            listOf("DCIM", "Pictures", "Pictures/storage-0"),
            aperto
        )

        val tutto = FolderTree.visible(nodes, setOf("Pictures", "Pictures/storage-0"))
        assertEquals(5, tutto.size)
    }

    @Test
    fun `a collapsed run is named by everything it collapsed`() {
        val nodes = FolderTree.candidates(
            listOf(folder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 15719))
        )
        val foglia = candidate(nodes, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images")

        assertEquals(
            "Il salto si vede, invece di sparire",
            "media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            foglia?.label
        )
        assertEquals("Un passo sotto Android, non cinque", 1, foglia?.depth)
        assertEquals("Android", candidate(nodes, "Android")?.label)
    }
}
