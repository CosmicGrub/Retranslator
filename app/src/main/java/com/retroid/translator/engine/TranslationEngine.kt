package com.retroid.translator.engine

import android.content.Context
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

    /**
     * Wi-Fi gating for the RUNTIME translate path (see docs/ENGINES.md's
     * Translation-engines "Known limits/gaps" #1 for the finding this
     * closes). This used to call the bare, no-argument
     * `translator.downloadModelIfNeeded()`, which applies ML Kit's
     * unrestricted default `DownloadConditions` - meaning a user who typed
     * text and tapped Translate for the very first time (before ever
     * pressing this screen's own explicit, Wi-Fi-gated "Download" button)
     * could silently trigger a cellular download, contradicting the app's
     * own "tap Download once on Wi-Fi, then it's offline" promise.
     *
     * `Translator.downloadModelIfNeeded(DownloadConditions)` is a real
     * overload on ML Kit Translate's public API - confirmed directly via
     * `javap` against the actual `com.google.mlkit:translate` AAR's
     * `Translator.class`, which declares both
     * `downloadModelIfNeeded()` and
     * `downloadModelIfNeeded(DownloadConditions)`. Passing `requireWifi()`
     * conditions here stops any *new* cellular download, matching the
     * explicit Download buttons (`TranslateFragment.downloadTranslateModels`,
     * `BulkDownloadCoordinator`), which already build their conditions the
     * same way.
     *
     * That alone isn't the whole fix though: the actual enforcement of an
     * unmet `DownloadConditions` constraint is implemented in the
     * closed-source Play services layer, not in this client jar, so
     * whether an unmet condition fails the returned `Task` quickly or
     * leaves it pending indefinitely can't be confirmed by static
     * inspection - and a silent hang with no user feedback would be worse
     * than the original silent-cellular-download bug. So this method
     * doesn't rely on that behavior at all: it pre-flights. It checks both
     * language codes against `getDownloadedModels()` (the same
     * one-round-trip srcOk/tgtOk pattern
     * `TranslateFragment.refreshModelStatus` already uses) and the
     * device's current Wi-Fi state (`DownloadManager.isOnWifi`, the same
     * helper the explicit pack downloads already use). If both models are
     * already downloaded, translate proceeds immediately regardless of
     * network state - that's the "offline forever after" case the app
     * already promises and already relies on. If either model is missing
     * AND the device isn't on Wi-Fi right now, this never touches the
     * network at all - it calls `onError` with a clear, actionable message
     * instead, so every call site's existing error handling (all of which
     * already surfaces `onError`'s message to the user) shows it verbatim.
     */
    fun translate(
        context: Context,
        sourceCode: String,
        targetCode: String,
        text: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val downloaded = models.map { it.language }.toSet()
                val bothReady = downloaded.contains(sourceCode) && downloaded.contains(targetCode)
                attemptTranslate(context, sourceCode, targetCode, text, bothReady, onResult, onError)
            }
            .addOnFailureListener {
                // Couldn't confirm download status - treat as "not confirmed
                // ready" so the Wi-Fi gate below still applies instead of
                // risking a cellular download on an unverified assumption.
                attemptTranslate(context, sourceCode, targetCode, text, bothReady = false, onResult, onError)
            }
    }

    private fun attemptTranslate(
        context: Context,
        sourceCode: String,
        targetCode: String,
        text: String,
        bothReady: Boolean,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!bothReady && !DownloadManager.isOnWifi(context)) {
            onError(
                "Translation pack not downloaded yet. Connect to Wi-Fi, then translate " +
                    "again to download it - after that this language pair works fully offline."
            )
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator: Translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().requireWifi().build()
        translator.downloadModelIfNeeded(conditions)
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
