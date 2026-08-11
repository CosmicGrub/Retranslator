package com.retroid.translator.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Thin shared wrapper around ML Kit Translate so the Translate screen and
 * the Conversations screen use exactly the same download/translate logic
 * instead of two copies of it.
 */
object TranslationEngine {

    fun isModelDownloaded(code: String, onResult: (Boolean) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.any { it.language == code }) }
            .addOnFailureListener { onResult(false) }
    }

    fun downloadModel(code: String, requireWifi: Boolean, onDone: (Boolean, String?) -> Unit) {
        val conditions = if (requireWifi) DownloadConditions.Builder().requireWifi().build() else DownloadConditions.Builder().build()
        val model = TranslateRemoteModel.Builder(code).build()
        RemoteModelManager.getInstance().download(model, conditions)
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { e -> onDone(false, e.message) }
    }

    /**
     * Additive - used by the "Manage language packs" screen
     * (docs/specs/galaxy-tab-s9fe-adaptation.md) to let a downloaded pack be
     * deleted to reclaim space and re-downloaded later. Doesn't change any
     * existing download/translate call path.
     */
    fun deleteModel(code: String, onDone: (Boolean, String?) -> Unit) {
        val model = TranslateRemoteModel.Builder(code).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model)
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { e -> onDone(false, e.message) }
    }

    fun translate(
        sourceCode: String,
        targetCode: String,
        text: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator: Translator = Translation.getClient(options)
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { result ->
                        onResult(result)
                        translator.close()
                    }
                    .addOnFailureListener { e ->
                        onError(e.message ?: "Translation failed")
                        translator.close()
                    }
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Model download failed")
                translator.close()
            }
    }
}
