package com.retroid.translator.voiceclone

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * On-disk storage for the user's own trained voice profile: exactly one
 * reference WAV clip (the "read this sentence aloud" recording chosen during
 * onboarding - [com.retroid.translator.settings.VoiceCloneOnboardingFragment])
 * plus the exact text spoken in it. Zero-shot voice cloning
 * ([com.retroid.translator.engine.VoiceCloneEngine], ZipVoice via
 * sherpa-onnx) conditions on both together every time it synthesizes - see
 * that class's doc comment for why the reference text must match the
 * reference audio exactly.
 *
 * Deliberately ONE active profile, not a list - this app clones the device
 * owner's own voice, not a roster of speakers. "Re-record my voice"
 * ([VoiceCloneOnboardingFragment] in update mode) replaces it outright via
 * [save], the same "unload-then-overwrite" shape
 * [com.retroid.translator.engine.PiperTtsEngine.deleteVoice]/[com.retroid.translator.engine.VoskEngine.deleteModel]
 * already use for their own on-disk packs.
 */
class VoiceProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir: File = File(appContext.filesDir, "voice-clone/profile").apply { mkdirs() }
    private val audioFile = File(dir, "reference.wav")
    private val metaFile = File(dir, "profile.json")

    data class Profile(val audioFile: File, val referenceText: String, val createdAtMs: Long)

    fun exists(): Boolean = audioFile.isFile && audioFile.length() > 0 && metaFile.isFile

    fun load(): Profile? {
        if (!exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            Profile(
                audioFile = audioFile,
                referenceText = json.optString("referenceText", ""),
                createdAtMs = json.optLong("createdAtMs", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Copies [sourceWav] in as the new active reference clip and records [referenceText] - overwrites any existing profile. */
    fun save(sourceWav: File, referenceText: String): Profile {
        sourceWav.copyTo(audioFile, overwrite = true)
        val createdAt = System.currentTimeMillis()
        val json = JSONObject()
            .put("referenceText", referenceText)
            .put("createdAtMs", createdAt)
        metaFile.writeText(json.toString())
        return Profile(audioFile, referenceText, createdAt)
    }

    fun delete() {
        audioFile.delete()
        metaFile.delete()
    }

    /** Scratch directory for onboarding's in-progress takes (candidate recordings not yet promoted to the active profile) - separate from the committed profile above so an abandoned onboarding flow never partially overwrites a working profile. */
    fun scratchDir(): File = File(appContext.filesDir, "voice-clone/scratch").apply { mkdirs() }

    fun clearScratch() {
        scratchDir().listFiles()?.forEach { it.delete() }
    }
}
