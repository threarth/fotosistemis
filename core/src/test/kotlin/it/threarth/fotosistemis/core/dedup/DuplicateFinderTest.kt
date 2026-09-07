package it.threarth.fotosistemis.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files hold one photograph, and which copy is worth proposing.
 *
 * The case that matters most: a photo the platform will not let us move is
 * copied into its category and the capture date is written into the copy,
 * so the two files differ in length and in every byte of header. Paired by
 * the file they are strangers; paired by the picture they are one
 * photograph.
 */
class DuplicateFinderTest {

    private companion object {
        const val WHATSAPP = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        const val CATEGORY = "Pictures/Famiglia/"
        const val PICTURE = "stessa-fotografia"
    }

    private fun copy(
        id: Long,
        folder: String,
        name: String,
        size: Long,
        contentHash: String? = null,
        imageHash: String? = null,
        catalogued: Boolean = false,
        immovable: Boolean = false,
        stamped: Boolean = false,
        discardRequested: Boolean = false
    ) = DuplicateFinder.Candidate(
        photoId = id, relativePath = folder, displayName = name, sizeBytes = size,
        contentHash = contentHash, imageHash = imageHash, catalogued = catalogued,
        immovable = immovable, stamped = stamped, discardRequested = discardRequested
    )

    @Test
    fun `l'originale e la copia archiviata sono la stessa fotografia`() {
        val original = copy(1, WHATSAPP, "IMG-WA0011.jpg", 115331, imageHash = PICTURE)
        val filed = copy(2, CATEGORY, "__20260803__IMG-WA0011.jpg", 115555, imageHash = PICTURE)

        val groups = DuplicateFinder.groups(listOf(original, filed))

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().copies.size)
    }

    @Test
    fun `le lunghezze diverse non impediscono piu' l'accoppiamento`() {
        val one = copy(1, WHATSAPP, "a.jpg", 100, imageHash = PICTURE)
        val other = copy(2, CATEGORY, "b.jpg", 999_999, imageHash = PICTURE)

        assertEquals(1, DuplicateFinder.groups(listOf(one, other)).size)
    }

    @Test
    fun `senza impronta dell'immagine vale ancora quella del file`() {
        val one = copy(1, "DCIM/Camera/", "a.jpg", 500, contentHash = "identica")
        val other = copy(2, CATEGORY, "b.jpg", 500, contentHash = "identica")

        assertEquals(1, DuplicateFinder.groups(listOf(one, other)).size)
    }

    @Test
    fun `le due impronte non si confondono fra loro`() {
        val byPicture = copy(1, WHATSAPP, "a.jpg", 500, imageHash = "x")
        val byFile = copy(2, CATEGORY, "b.jpg", 500, contentHash = "x")

        assertTrue(DuplicateFinder.groups(listOf(byPicture, byFile)).isEmpty())
    }

    @Test
    fun `un file di cui non si sa niente non e' doppione di nessuno`() {
        val one = copy(1, "DCIM/Camera/", "a.jpg", 500)
        val other = copy(2, CATEGORY, "b.jpg", 500)

        assertTrue(DuplicateFinder.groups(listOf(one, other)).isEmpty())
    }

    @Test
    fun `non propone mai di tenere una copia gia' segnata da eliminare`() {
        // Catalogata e con il timbro: vincerebbe su ogni altro criterio.
        val discarded = copy(
            1, CATEGORY, "__20260803__a.jpg", 500, imageHash = PICTURE,
            catalogued = true, stamped = true, discardRequested = true
        )
        val plain = copy(2, "DCIM/Camera/", "b.jpg", 500, imageHash = PICTURE)

        assertEquals(plain.photoId, DuplicateFinder.groups(listOf(discarded, plain))
            .single().suggested.photoId)
    }

    @Test
    fun `fra due copie tiene quella catalogata e non quella immobile`() {
        val original = copy(1, WHATSAPP, "a.jpg", 500, imageHash = PICTURE, immovable = true)
        val filed = copy(2, CATEGORY, "b.jpg", 520, imageHash = PICTURE, catalogued = true)

        assertEquals(filed.photoId, DuplicateFinder.groups(listOf(original, filed))
            .single().suggested.photoId)
    }
}
