package com.retroid.translator.wear.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Byte-for-byte port of the phone app's
 * [com.retroid.translator.engine.TranslationEngine] (only the package
 * changed). ML Kit Translate has no native/JNI component of its own - it is
 * pure Kotlin/Java calling into Google Play services - so unlike Vosk this
 * needed zero porting work and carries zero ABI risk. Confirms the "hard
 * technical question" is specifically about the native STT/TTS stack, not
 * about this app's translation engine.
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
