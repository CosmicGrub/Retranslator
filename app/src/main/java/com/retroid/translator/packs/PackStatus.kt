package com.retroid.translator.packs

import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.retroid.translator.TranslatorApp

/**
 * Downloaded/not-downloaded status for [PackDescriptor]s. ML Kit's own
 * per-model check (`RemoteModelManager.getDownloadedModels`) is async and
 * returns the FULL set of downloaded models in one call - fetched once per
 * screen refresh here (see [fetchDownloadedTranslationCodes]) rather than
 * once per translation pack (would be ~59 separate async round-trips for a
 * screen listing every language). Vosk/Piper's own engines already expose a
 * synchronous check ([com.retroid.translator.engine.VoskEngine.isModelDownloaded],
 * [com.retroid.translator.engine.PiperTtsEngine.isVoiceDownloaded]) so no
 * batching is needed for those two categories.
 */
object PackStatus {

    fun fetchDownloadedTranslationCodes(onResult: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.map { it.language }.toSet()) }
            .addOnFailureListener { onResult(emptySet()) }
    }

    fun isDownloaded(app: TranslatorApp, descriptor: PackDescriptor, downloadedTranslationCodes: Set<String>): Boolean =
        when (descriptor) {
            is PackDescriptor.Translation -> descriptor.mlKitCode in downloadedTranslationCodes
            is PackDescriptor.VoiceInput -> app.vosk.isModelDownloaded(descriptor.info.mlKitCode)
            is PackDescriptor.NaturalVoice -> app.piper.isVoiceDownloaded(descriptor.info)
        }
}
