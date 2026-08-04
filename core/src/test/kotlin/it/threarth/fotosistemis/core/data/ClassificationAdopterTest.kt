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

    private fun entry(photoId: Long, path: String) =
        ClassificationAdopter.InventoryEntry(photoId, path)

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
        val proposal = ClassificationAdopter.propose(
            listOf(entry(10, "Pictures/Famiglia/2026/compleanni/")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )
        assertTrue(proposal.candidates.isEmpty())
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
}
