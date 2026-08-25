package com.retroid.translator.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct coverage of the catalog itself, at the module it now actually
 * lives in - complements, not duplicates,
 * [com.retroid.translator.packs.PackInventoryTest] in :app, which covers
 * [com.retroid.translator.packs.PackDescriptor.VoiceInput] construction
 * from these same entries rather than the catalog's own lookups
 * (docs/specs/engineering-systems-pitch.md system #2).
 */
class VoskModelCatalogTest {

    @Test
    fun `forLanguage finds every entry by its own mlKitCode`() {
        VoskModelCatalog.MODELS.forEach { info ->
            assertEquals(info, VoskModelCatalog.forLanguage(info.mlKitCode))
        }
    }

    @Test
    fun `an unsupported code returns null, not a crash`() {
        assertNull(VoskModelCatalog.forLanguage("xx"))
    }

    @Test
    fun `supportedCodes matches MODELS exactly, with no duplicate mlKitCode entries`() {
        val codes = VoskModelCatalog.MODELS.map { it.mlKitCode }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(codes.toSet(), VoskModelCatalog.supportedCodes())
    }

    @Test
    fun `every declared size is a real positive number of megabytes`() {
        assertTrue(VoskModelCatalog.MODELS.all { it.approxSizeMiB > 0 })
    }
}
