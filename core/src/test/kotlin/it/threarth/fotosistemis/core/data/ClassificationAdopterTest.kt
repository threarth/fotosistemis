package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks which photos count as already filed, and which categories are read
 * out of the folder layout.
 */
class ClassificationAdopterTest {

    private fun destination(id: Long, label: String, path: String) =
        Destination(id, label, path, yearSubfolder = true, sortOrder = 0)

    private fun entry(photoId: Long, path: String, name: String = "IMG001.jpg") =
        ClassificationAdopter.InventoryEntry(photoId, path, name)

    private val famiglia = destination(1, "Famiglia", "Pictures/Famiglia")

    @Test
    fun `reads the year folder whatever it is called`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Famiglia/2026-famiglia/"),
                // The naming used before the pattern became configurable.
                entry(11, "Pictures/Famiglia/2025/"),
                entry(12, "Pictures/Famiglia/famiglia_2024/")
            ),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )
        assertEquals(3, proposal.total)
    }

    @Test
    fun `discovers a category the app does not know yet`() {
        // What a new phone looks like: folders on disk, nothing in the app.
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Vacanze/2026-vacanze/"),
                entry(11, "Pictures/Vacanze/2025-vacanze/")
            ),
            destinations = emptyList(),
            alreadyDecided = emptySet()
        )

        val proposed = proposal.proposedCategories.single()
        assertEquals("Vacanze", proposed.label)
        assertEquals("Pictures/Vacanze", proposed.relativePath)
        assertEquals(2, proposed.photoCount)
        assertTrue(proposal.createsCategories)
        assertNull("La cartella non esiste ancora", proposal.candidates.first().destinationId)
    }

    @Test
    fun `uses the existing destination when there is one`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/Famiglia/2026-famiglia/")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )

        assertEquals(1L, proposal.candidates.single().destinationId)
        assertTrue("Nulla da creare", proposal.proposedCategories.isEmpty())
    }

    @Test
    fun `ignores folders with no year`() {
        // Without the year requirement these would all look like categories.
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Screenshots/"),
                entry(11, "Pictures/WhatsApp/Immagini/"),
                entry(12, "DCIM/Camera/")
            ),
            destinations = emptyList(),
            alreadyDecided = emptySet()
        )
        assertTrue(proposal.candidates.isEmpty())
    }

    @Test
    fun `ignores structures deeper than category and year`() {
        // No destination declared: the shape is all there is to go on, and
        // two folders below a root are not a category and a year.
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/Famiglia/2026/compleanni/")),
            destinations = emptyList(),
            alreadyDecided = emptySet()
        )
        assertTrue(proposal.candidates.isEmpty())
    }

    /**
     * The same path, once the user has said that folder is a category.
     *
     * Shape is a guess about what a folder means; a declared destination is
     * an answer, and it holds however deep the photo sits inside it.
     */
    @Test
    fun `sotto una destinazione dichiarata anche il profondo appartiene`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/Famiglia/2026/compleanni/")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )

        assertEquals(1L, proposal.candidates.single().destinationId)
    }

    @Test
    fun `a category named after a year is not a category`() {
        // Reading Pictures/2026/gita as category 2026 would be one level off.
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/2026/2026-gita/")),
            destinations = emptyList(),
            alreadyDecided = emptySet()
        )
        assertTrue(proposal.candidates.isEmpty())
    }

    @Test
    fun `never overrides a decision already taken`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Famiglia/2026-famiglia/"),
                entry(11, "Pictures/Famiglia/2026-famiglia/")
            ),
            listOf(famiglia),
            alreadyDecided = setOf(10)
        )

        assertEquals(1, proposal.total)
        assertEquals(11L, proposal.candidates.single().photoId)
    }

    @Test
    fun `counts photos per category for the preview`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Famiglia/2026-famiglia/"),
                entry(11, "Pictures/Famiglia/2025-famiglia/"),
                entry(12, "Pictures/Lavoro/2026-lavoro/")
            ),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )
        assertEquals(mapOf("Famiglia" to 2, "Lavoro" to 1), proposal.byCategory)
    }

    @Test
    fun `finds an archive a phone transfer buried one level down`() {
        // Phone Clone copies the old device's volumes into subfolders, which
        // is where this user's whole sorted archive ended up.
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/storage-1/Famiglia/2026-famiglia/"),
                entry(11, "Pictures/storage-1/Casa/2026-casa/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = listOf("Pictures/storage-1")
        )

        assertEquals(2, proposal.total)
        assertEquals(setOf("Famiglia", "Casa"), proposal.byCategory.keys)
        assertEquals(
            "Pictures/storage-1/Famiglia",
            proposal.proposedCategories.first { it.label == "Famiglia" }.relativePath
        )
    }

    @Test
    fun `the root still has to be the root`() {
        // Two segments below it, no more: with the root at Pictures, the
        // same archive is one level too deep and stays untouched.
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/storage-1/Famiglia/2026-famiglia/")),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = listOf("Pictures")
        )

        assertTrue(proposal.candidates.isEmpty())
    }

    @Test
    fun `looks under every root it is given`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/storage-1/Famiglia/2026-famiglia/"),
                entry(11, "DCIM/Lavoro/2025-lavoro/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = listOf("Pictures/storage-1", "DCIM")
        )

        assertEquals(2, proposal.total)
    }

    @Test
    fun `the same category under two roots is one category`() {
        // Phone Clone splits an archive across storage-0 and storage-1:
        // creating two Famiglia would make the split permanent.
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/storage-0/Famiglia/2020-famiglia/"),
                entry(11, "Pictures/storage-1/Famiglia/2026-famiglia/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = listOf("Pictures/storage-0", "Pictures/storage-1")
        )

        assertEquals(2, proposal.total)
        assertEquals(1, proposal.proposedCategories.size)
        assertEquals("Famiglia", proposal.proposedCategories.single().label)
        assertEquals(2, proposal.proposedCategories.single().photoCount)
    }

    @Test
    fun `a category already known by name is reused, wherever it lives`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/storage-1/Famiglia/2026-famiglia/")),
            listOf(famiglia),
            alreadyDecided = emptySet(),
            roots = listOf("Pictures/storage-1")
        )

        assertEquals(
            "Riagganciata alla Famiglia che esiste gia'",
            1L,
            proposal.candidates.single().destinationId
        )
        assertTrue("Niente da creare", proposal.proposedCategories.isEmpty())
    }

    @Test
    fun `searching everywhere finds the whole of what is sorted`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/storage-0/Famiglia/2020-famiglia/"),
                entry(11, "Pictures/storage-1/Famiglia/2026-famiglia/"),
                entry(12, "Pictures/Casa/2026-casa/"),
                entry(13, "DCIM/Lavoro/2025-lavoro/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = ClassificationAdopter.ANYWHERE
        )

        assertEquals(4, proposal.total)
        assertEquals(
            "Una Famiglia sola, per quanti rami la contengano",
            setOf("Famiglia", "Casa", "Lavoro"),
            proposal.proposedCategories.map { it.label }.toSet()
        )
        assertEquals(2, proposal.byCategory["Famiglia"])
    }

    @Test
    fun `searching everywhere still needs the year folder`() {
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/Screenshots/"),
                entry(11, "Download/Foto classe elementari/"),
                entry(12, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
                entry(13, "Pictures/storage-0/Festa Tommy 2021/Da Andrea/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = ClassificationAdopter.ANYWHERE
        )

        assertTrue(
            "Nessuna di queste ha la forma categoria/anno",
            proposal.candidates.isEmpty()
        )
    }

    @Test
    fun `what the shape finds can be pruned`() {
        // Searching everywhere also finds folders that merely look the part.
        val proposal = ClassificationAdopter.propose(
            listOf(
                entry(10, "Pictures/storage-0/Famiglia/2020-famiglia/"),
                entry(11, "DCIM/2023 Recita di Natale/"),
                entry(12, "Pictures/storage-0/Festa Tommy 2021/")
            ),
            emptyList(),
            alreadyDecided = emptySet(),
            roots = ClassificationAdopter.ANYWHERE
        )

        assertEquals(
            "La forma da sola non sa distinguerle",
            setOf("Famiglia", "DCIM", "storage-0"),
            proposal.categories.map { it.label }.toSet()
        )
        assertTrue("Sono tutte da creare", proposal.categories.all { it.isNew })

        val tenute = proposal.restrictedTo(setOf("Famiglia"))
        assertEquals(1, tenute.total)
        assertEquals(1, tenute.proposedCategories.size)
        assertEquals("Famiglia", tenute.proposedCategories.single().label)
    }

    @Test
    fun `a category already known is offered as known`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/storage-1/Famiglia/2026-famiglia/")),
            listOf(famiglia),
            alreadyDecided = emptySet(),
            roots = ClassificationAdopter.ANYWHERE
        )

        assertTrue("Esiste gia'", proposal.categories.none { it.isNew })
        assertEquals(1, proposal.restrictedTo(setOf("Famiglia")).total)
        assertEquals(0, proposal.restrictedTo(emptySet()).total)
    }

    /**
     * A copy the app made lands flat in the category folder, with no year
     * folder to give it away. Before, nothing recognised it and the photo
     * came back to be sorted a second time.
     */
    @Test
    fun `una foto dentro una cartella di destinazione appartiene a quella`() {
        val famiglia = destination(7, "Famiglia", "Pictures/Famiglia")
        val proposal = ClassificationAdopter.propose(
            listOf(entry(1, "Pictures/Famiglia/", "IMG-20260826-WA0007.jpg")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )

        assertEquals(1, proposal.total)
        assertEquals("Famiglia", proposal.candidates.first().categoryLabel)
        assertEquals(7L, proposal.candidates.first().destinationId)
        assertTrue(proposal.proposedCategories.isEmpty())
    }

    /** A year folder under a known destination belongs to it just the same. */
    @Test
    fun `anche la sottocartella dell'anno appartiene alla destinazione`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(1, "Pictures/Famiglia/2026-famiglia/", "foto.jpg")),
            listOf(destination(7, "Famiglia", "Pictures/Famiglia")),
            alreadyDecided = emptySet()
        )

        assertEquals(7L, proposal.candidates.first().destinationId)
    }

    /** The nearer folder wins, or a category inside another would lose its own. */
    @Test
    fun `fra due destinazioni annidate vince la piu' vicina`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(1, "Pictures/Famiglia/Sport/", "foto.jpg")),
            listOf(
                destination(7, "Famiglia", "Pictures/Famiglia"),
                destination(8, "Sport", "Pictures/Famiglia/Sport")
            ),
            alreadyDecided = emptySet()
        )

        assertEquals(8L, proposal.candidates.first().destinationId)
    }

    /** A decision already taken is never revisited, wherever the photo sits. */
    @Test
    fun `una foto gia' decisa non viene riproposta`() {
        val proposal = ClassificationAdopter.propose(
            listOf(entry(1, "Pictures/Famiglia/", "foto.jpg")),
            listOf(destination(7, "Famiglia", "Pictures/Famiglia")),
            alreadyDecided = setOf(1L)
        )

        assertEquals(0, proposal.total)
    }
}
