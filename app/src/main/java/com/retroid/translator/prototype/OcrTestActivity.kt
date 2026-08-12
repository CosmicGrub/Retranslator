package com.retroid.translator.prototype

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.ocr.OcrEngine
import com.retroid.translator.ocr.OcrScript

/**
 * Throwaway, NON-shipped debug entry point for the Camera OCR translate
 * feature (docs/specs/fold5-adaptation.md "Camera OCR translate" section) -
 * same "prototype" pattern as [DualRecognizerProtoActivity]/
 * [ContinuousFlowProtoActivity] already in this package. Trigger via:
 *
 *   adb shell am start -n com.retroid.translator/.prototype.OcrTestActivity
 *
 * then watch `adb logcat -s OcrTestActivity`.
 *
 * Why this exists, honestly: [com.retroid.translator.ocr.CameraCaptureActivity]'s
 * live camera pipeline was independently verified end-to-end on the real
 * Fold 5 (RFCW80CK2RW) - camera permission, CameraX preview binding, real
 * frame streaming (confirmed via logcat: camera opens, reaches
 * `CAMERA_STATE_ACTIVE`, `SurfaceView` receives real frames), a real
 * `ImageCapture.takePicture` → `InputImage.fromMediaImage` → ML Kit
 * `TextRecognizer.process()` round trip with no exception, and the "no text
 * detected" edge case (status text + Toast, screen stays open for retry) -
 * all with real logcat/screenshot evidence. What that pass could NOT
 * produce is a POSITIVE recognition result: this agent has no way to place
 * real printed/displayed text in front of a physical camera lens (no robotic
 * arm, no way to see or control the room), and the device's actual physical
 * surroundings during that session had no legible text in frame. This
 * mirrors an already-disclosed, structurally identical gap elsewhere in this
 * project - see docs/specs/fold5-adaptation.md §4's "no live human speaker
 * is available to any agent" - and this activity is this feature's answer to
 * that same constraint: it draws real text to a real [Bitmap] with the
 * standard Android [Canvas]/[Paint] APIs (not a canned/fake ML Kit result),
 * then runs it through the SAME [OcrEngine.recognize] and
 * [TranslationEngine.translate] calls the real capture screen calls, on
 * this device's real ML Kit runtime - genuine on-device inference on a
 * real image, only the image's origin (rendered vs. photographed) differs
 * from the live-camera path already verified separately above.
 */
class OcrTestActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = "Starting OCR engine test…\n"
        }
        setContentView(ScrollView(this).apply { addView(logView) })
        runTest()
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            logBuilder.append(line).append('\n')
            logView.text = logBuilder.toString()
        }
    }

    private fun renderTextBitmap(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
        }
        val width = 900
        val lineHeight = 90
        val bitmap = Bitmap.createBitmap(width, lineHeight * (text.lines().size + 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        text.lines().forEachIndexed { i, line -> canvas.drawText(line, 20f, (lineHeight * (i + 1)).toFloat(), paint) }
        return bitmap
    }

    private fun runTest() {
        val samples = listOf(
            Triple("TEST_LATIN", OcrScript.LATIN, "Where is the train station?"),
            // Chinese sample - separate from the Latin one because it exercises
            // OcrScript.CHINESE's own recognizer client
            // (ChineseTextRecognizerOptions, via the unbundled play-services
            // artifact) end-to-end, not just Latin's bundled one. The real
            // capture screen's ModuleInstallClient.areModulesAvailable() check
            // (see OcrEngine.isScriptReady) already confirmed this module is
            // installed on this device (real ModuleInstallService bind seen in
            // logcat), so recognize() here should hit the same ready module.
            Triple("TEST_CHINESE", OcrScript.CHINESE, "火车站在哪里"),
        )
        for ((label, script, text) in samples) {
            appendLog("--- $label ($script): rendering \"$text\" to a real on-device Bitmap ---")
            val bitmap = renderTextBitmap(text)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            OcrEngine.recognize(
                script, inputImage,
                onResult = { recognized ->
                    appendLog("$label OCR result: \"$recognized\" (expected≈\"$text\")")
                    if (recognized.isBlank()) {
                        appendLog("$label: FAILED - empty OCR result on a rendered real-text bitmap")
                    } else {
                        val srcCode = if (script == OcrScript.CHINESE) "zh" else "en"
                        val tgtCode = if (script == OcrScript.CHINESE) "en" else "es"
                        appendLog("$label: feeding recognized text into the real TranslationEngine ($srcCode -> $tgtCode)...")
                        TranslationEngine.translate(srcCode, tgtCode, recognized,
                            onResult = { translated -> appendLog("$label TRANSLATION RESULT ($srcCode->$tgtCode): \"$translated\"") },
                            onError = { err -> appendLog("$label translation failed: $err (translation packs may not be downloaded on this device)") }
                        )
                    }
                },
                onError = { err -> appendLog("$label OCR FAILED: $err") }
            )
        }
    }

    companion object {
        private const val TAG = "OcrTestActivity"
    }
}
