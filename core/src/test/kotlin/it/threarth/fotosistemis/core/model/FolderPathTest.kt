package it.threarth.fotosistemis.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the one rule every folder comparison goes through: spelling and
 * trailing slashes do not make a folder different from itself.
 */
class FolderPathTest {

    @Test
    fun `case and trailing slashes do not tell folders apart`() {
        assertTrue(FolderPath.sameFolder("Pictures/Famiglia/", "pictures/famiglia"))
        assertTrue(FolderPath.sameFolder("/DCIM/Camera", "DCIM/Camera/"))
        assertEquals(FolderPath.key("Pictures/Famiglia/"), FolderPath.key("PICTURES/FAMIGLIA"))
    }

    @Test
    fun `different folders stay different`() {
        assertFalse(FolderPath.sameFolder("Pictures/Famiglia", "Pictures/Famiglia/2024"))
        assertFalse(FolderPath.sameFolder("Pictures", "Picture"))
    }
}
