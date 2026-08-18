package com.retroid.translator.ocr

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Thin shared wrapper around ML Kit Text Recognition v2, mirroring
 * [com.retroid.translator.engine.TranslationEngine]'s shape
 * (isXDownloaded/downloadX/doTheThing) so the capture screen's status/download
 * UI can look and behave like every other "pack" in this app.
 *
 * One real API difference from every other on-demand pack in this app, worth
 * being explicit about rather than papering over: ML Kit Text Recognition has
 * no `RemoteModelManager` path at all (unlike Translate/Language-ID, which
 * this app's original camera-OCR design assumed Text Recognition would also
 * use). It has exactly two real install shapes:
 *  - **Bundled** (`com.google.mlkit:text-recognition[-<script>]`): the model
 *    ships inside the APK. Nothing to download, nothing to check - always
 *    ready. Used here for [OcrScript.LATIN].
 *  - **Unbundled** (`com.google.android.gms:play-services-mlkit-text-recognition-<script>`):
 *    the model downloads dynamically via Google Play services'
 *    `ModuleInstallClient` (`com.google.android.gms.common.moduleinstall`,
 *    NOT `com.google.mlkit.common.model.RemoteModelManager`) - a genuinely
 *    different download mechanism, not a re-skin of the Translate one. Used
 *    here for [OcrScript.CHINESE] and, added in a later pass once real
 *    verification text was available (docs/specs/engines-upgrade-plan.md's
 *    Tier 2 "Add Japanese/Korean/Devanagari OCR script packs"),
 *    [OcrScript.JAPANESE]/[OcrScript.KOREAN]/[OcrScript.DEVANAGARI] - all
 *    four scripts share the identical `isScriptReady`/`downloadScript` code
 *    below with zero script-specific logic, exactly as that plan predicted.
 */
object OcrEngine {

    private fun clientFor(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /** [OcrScript.LATIN] is bundled - always true, no I/O. Every other script asks ModuleInstallClient whether its module is already present on this device. */
    fun isScriptReady(context: Context, script: OcrScript, onResult: (Boolean) -> Unit) {
        if (script == OcrScript.LATIN) {
            onResult(true)
            return
        }
        val client = clientFor(script)
        ModuleInstall.getClient(context).areModulesAvailable(client)
            .addOnSuccessListener { response -> onResult(response.areModulesAvailable()) }
            .addOnFailureListener { onResult(false) }
    }

    /**
     * Triggers the Play-services module download for [script]. No-op success
     * for [OcrScript.LATIN] (nothing to download). [onProgress] receives
     * best-effort percentages from [ModuleInstallStatusUpdate]'s progress
     * info when Play services reports byte counts; some devices/module
     * states report no progress info at all, in which case [onProgress] is
     * simply never called and the caller should rely on [onDone] alone
     * (mirrors [com.retroid.translator.engine.DownloadManager]'s own
     * "progress is best-effort" contract).
     */
    fun downloadScript(context: Context, script: OcrScript, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        if (script == OcrScript.LATIN) {
            onDone(true, null)
            return
        }
        val client = clientFor(script)
        val moduleInstallClient = ModuleInstall.getClient(context)
        val progressListener = InstallStatusListener { update ->
            val info = update.progressInfo
            if (info != null && info.totalBytesToDownload > 0) {
                onProgress(((info.bytesDownloaded * 100) / info.totalBytesToDownload).toInt())
            }
        }
        val request = ModuleInstallRequest.newBuilder()
            .addApi(client)
            .setListener(progressListener)
            .build()
        moduleInstallClient.installModules(request)
            .addOnSuccessListener {
                moduleInstallClient.unregisterListener(progressListener)
                onDone(true, null)
            }
            .addOnFailureListener { e ->
                moduleInstallClient.unregisterListener(progressListener)
                onDone(false, e.message)
            }
    }

    /** Runs [script]'s recognizer on [image] once. Closes the recognizer itself when done - callers don't hold onto a recognizer between calls (single-shot capture, not a live stream, per this feature's design). */
    fun recognize(script: OcrScript, image: InputImage, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val recognizer = clientFor(script)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText.text)
                recognizer.close()
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Text recognition failed")
                recognizer.close()
            }
    }
}
