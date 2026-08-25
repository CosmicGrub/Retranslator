package com.retroid.translator.packs

import com.retroid.translator.engine.PiperVoiceCatalog
import com.retroid.translator.engine.VoskModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deliberately does NOT call [PackInventory.all] or exercise
 * [PackCategory.TRANSLATION]: [PackInventory.all]'s first step is
 * `LanguageCatalog.codes`, which calls ML Kit's
 * `TranslateLanguage.getAllLanguages()` - the same real-ML-Kit-runtime
 * dependency [com.retroid.translator.engine.LanguageCatalogTest] already
 * documents avoiding for `.codes`, unavailable on a plain JVM unit test.
 * Voice-input and natural-voice packs have no such dependency - both
 * catalogs are pure Kotlin data - so they're tested directly here instead.
 * (docs/specs/engineering-systems-pitch.md's CI test-gate system, Part B.)
 */
class PackInventoryTest {

    @Test
    fun `every Vosk model produces a VOICE_INPUT descriptor with matching id and size`() {
        val descriptors = VoskModelCatalog.MODELS.map { PackDescriptor.VoiceInput(it) }
        assertEquals(VoskModelCatalog.MODELS.size, descriptors.size)
        descriptors.forEachIndexed { i, d ->
            val info = VoskModelCatalog.MODELS[i]
            assertEquals(PackCategory.VOICE_INPUT, d.category)
            assertEquals(info.mlKitCode, d.id)
            assertEquals(info.displayName, d.displayName)
            assertEquals(info.approxSizeMiB, d.approxSizeMiB)
        }
    }

    @Test
    fun `every Piper voice produces a NATURAL_VOICE descriptor with gender in its display name`() {
        val descriptors = PiperVoiceCatalog.VOICES.map { PackDescriptor.NaturalVoice(it) }
        descriptors.forEach {
            assertEquals(PackCategory.NATURAL_VOICE, it.category)
            assertTrue(
                "displayName should end with a gender tag: ${it.displayName}",
                it.displayName.endsWith("(Male)") || it.displayName.endsWith("(Female)")
            )
        }
    }

    @Test
    fun `NaturalVoice id is the voice's own storage key, not its display name`() {
        val info = PiperVoiceCatalog.VOICES.first()
        val descriptor = PackDescriptor.NaturalVoice(info)
        assertEquals(info.voiceId, descriptor.id)
    }

    @Test
    fun `VoiceInput and NaturalVoice ids stay unique within their own category`() {
        val voiceInputIds = VoskModelCatalog.MODELS.map { it.mlKitCode }
        assertEquals(voiceInputIds.size, voiceInputIds.toSet().size)

        val naturalVoiceIds = PiperVoiceCatalog.VOICES.map { it.voiceId }
        assertEquals(naturalVoiceIds.size, naturalVoiceIds.toSet().size)
    }
}
