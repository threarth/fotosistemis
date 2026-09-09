package it.threarth.fotosistemis.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding one photograph among tens of thousands, and putting first what
 * the app touched last.
 *
 * The screen exists to answer questions about a single photograph, so the
 * search has to reach every field that might be the one remembered — the
 * folder, the category, a fingerprint, an id — and not only the name.
 */
class InventoryDumpTest {

    private companion object {
        const val LONG_AGO = 1_000L
        const val EARLIER = 2_000L
        const val RECENTLY = 3_000L
    }

    private fun entry(
        id: Long,
        name: String = "IMG_$id.jpg",
        folder: String = "DCIM/Camera/",
        status: String? = null,
        statusAt: Long? = null,
        category: String? = null,
        imageHash: String? = null,
        takenAt: Long = 0L,
        history: List<InventoryDump.Step> = emptyList()
    ) = InventoryDump.Entry(
        photoId = id, mediaId = id * 10, displayName = name, relativePath = folder,
        sizeBytes = 100, dateTakenMillis = takenAt, dateSource = "EXIF",
        contentHash = null, imageHash = imageHash, missingSince = null,
        dateSuspect = false, status = status, statusAt = statusAt, category = category,
        proposedAction = null, proposedCategory = null, proposedAt = null,
        stars = null, tags = emptyList(), ignoredBy = emptyList(), history = history
    )

    private fun step(at: Long, path: String = "Pictures/Famiglia/") =
        InventoryDump.Step("moved", path, "x.jpg", at)

    @Test
    fun `la ricerca guarda anche la cartella e la categoria`() {
        val photos = listOf(
            entry(1, name = "a.jpg", folder = "Pictures/Famiglia/"),
            entry(2, name = "b.jpg", category = "Famiglia"),
            entry(3, name = "c.jpg")
        )

        val found = InventoryDump.search(photos, "famiglia", InventoryDump.Order.PLACE)

        assertEquals(listOf(1L, 2L), found.map { it.photoId }.sorted())
    }

    @Test
    fun `ogni parola deve trovare qualcosa, non importa dove`() {
        val photos = listOf(
            entry(1, name = "vacanza.jpg", category = "Amici"),
            entry(2, name = "vacanza.jpg", category = "Famiglia")
        )

        val found = InventoryDump.search(photos, "vacanza amici", InventoryDump.Order.PLACE)

        assertEquals(listOf(1L), found.map { it.photoId })
    }

    @Test
    fun `si cerca anche per impronta e per numero`() {
        val photos = listOf(entry(7, imageHash = "abcdef123456"), entry(8))

        assertEquals(1, InventoryDump.search(photos, "abcdef", InventoryDump.Order.PLACE).size)
        assertEquals(1, InventoryDump.search(photos, "70", InventoryDump.Order.PLACE).size)
    }

    @Test
    fun `una ricerca vuota mostra tutto`() {
        val photos = listOf(entry(1), entry(2))

        assertEquals(2, InventoryDump.search(photos, "   ", InventoryDump.Order.PLACE).size)
    }

    @Test
    fun `l'ultima operazione e' la piu' recente fra decisione e percorsi`() {
        val decided = entry(1, statusAt = RECENTLY, status = "kept")
        val moved = entry(2, statusAt = LONG_AGO, status = "kept", history = listOf(step(EARLIER)))

        assertEquals(RECENTLY, decided.lastActionAt)
        assertEquals(EARLIER, moved.lastActionAt)
    }

    @Test
    fun `ordinando per ultima operazione viene prima cio' che e' appena successo`() {
        val old = entry(1, statusAt = LONG_AGO, status = "kept")
        val fresh = entry(2, statusAt = LONG_AGO, status = "kept", history = listOf(step(RECENTLY)))

        val found = InventoryDump.search(listOf(old, fresh), "", InventoryDump.Order.LAST_ACTION)

        assertEquals(listOf(2L, 1L), found.map { it.photoId })
    }

    @Test
    fun `una foto senza storia ne' decisione non ha ultima operazione`() {
        assertEquals(0L, entry(1).lastActionAt)
    }

    @Test
    fun `ordinando per cartella si segue il percorso intero`() {
        val photos = listOf(
            entry(1, name = "b.jpg", folder = "Pictures/Amici/"),
            entry(2, name = "a.jpg", folder = "Pictures/Famiglia/"),
            entry(3, name = "a.jpg", folder = "Pictures/Amici/")
        )

        val found = InventoryDump.search(photos, "", InventoryDump.Order.PLACE)

        assertEquals(listOf(3L, 1L, 2L), found.map { it.photoId })
    }

    @Test
    fun `l'impronta di un file senza immagine leggibile non e' un'impronta`() {
        // La sentinella non deve mai valere come valore da cercare.
        assertTrue(PhotoInventory.NO_PICTURE.isNotEmpty())
    }
}
