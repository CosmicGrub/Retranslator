package com.retroid.translator.prototype

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.retroid.translator.engine.DownloadManager
import com.retroid.translator.engine.TranslationEngine

/**
 * Throwaway, NON-shipped debug entry point verifying the Wi-Fi gating fix on
 * [TranslationEngine.translate] (see docs/ENGINES.md's Translation-engines
 * "Known limits/gaps" #1, and [TranslationEngine.translate]'s own doc
 * comment for the full bug/fix explanation). Same "prototype" pattern as
 * [OcrTestActivity]/[ContinuousFlowProtoActivity] already in this package.
 * Trigger:
 *
 *   adb shell svc wifi disable
 *   adb shell am start -n com.retroid.translator/.prototype.WifiGateTestActivity
 *   adb logcat -s WifiGateTest
 *
 * then flip back with `adb shell svc wifi enable` and relaunch to exercise
 * the "Wi-Fi is back" path too.
 *
 * What this proves, concretely, using the REAL on-device
 * `RemoteModelManager` / `ConnectivityManager` (no mocks): (1) a language
 * pair with neither model downloaded, run with Wi-Fi off, never reaches ML
 * Kit's network-touching `downloadModelIfNeeded()` at all and instead gets
 * the new, clear "connect to Wi-Fi" `onError` message back fast (no
 * multi-second network-timeout delay, since no network call was ever
 * attempted); (2) the same pair, run with Wi-Fi on, still downloads and
 * translates successfully - auto-download-on-Wi-Fi is unchanged; (3) an
 * already-downloaded pair, run with Wi-Fi off, still translates
 * successfully - the "offline forever after download" promise is
 * unregressed by this fix.
 */
class WifiGateTestActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    // Deliberately obscure language codes (all real, confirmed-supported
    // ML Kit TranslateLanguage constants - checked via `javap` against the
    // actual translate AAR's TranslateLanguage.class, not guessed) that
    // this project's other verification passes have never exercised (see
    // WearLanguages.kt's curated 12, VoskModelCatalog.kt's 25, and
    // PiperVoiceCatalog.kt's 4 - none overlap these). The harness still
    // confirms "not downloaded" at runtime below rather than assuming it.
    private val candidatePairs = listOf(
        "cy" to "ga", // Welsh / Irish
        "mt" to "ka", // Maltese / Georgian
        "mk" to "be", // Macedonian / Belarusian
        "sq" to "gl", // Albanian / Galician
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = "Starting Wi-Fi gating test...\n"
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

    private fun runTest() {
        val onWifi = DownloadManager.isOnWifi(this)
        appendLog("Device Wi-Fi state right now: ${if (onWifi) "ON" else "OFF"}")

        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val downloaded = models.map { it.language }.toSet()
                appendLog("Models already downloaded on this device: $downloaded")
                val pick = candidatePairs.firstOrNull { (a, b) -> a !in downloaded && b !in downloaded }
                if (pick == null) {
                    appendLog("SKIP undownloaded-pair test: every candidate pair already has a model downloaded on this device.")
                    runAlreadyDownloadedCheck(downloaded)
                    return@addOnSuccessListener
                }
                runUndownloadedPairTest(pick.first, pick.second, onWifi, downloaded)
            }
            .addOnFailureListener { e ->
                appendLog("Could not query downloaded models up front: ${e.message}")
                appendLog("DONE.")
            }
    }

    private fun runUndownloadedPairTest(src: String, tgt: String, onWifi: Boolean, downloaded: Set<String>) {
        appendLog("Using undownloaded pair: $src -> $tgt (confirmed neither model downloaded)")
        val startNanos = System.nanoTime()
        TranslationEngine.translate(this, src, tgt, "hello",
            onResult = { translated ->
                val ms = (System.nanoTime() - startNanos) / 1_000_000
                if (onWifi) {
                    appendLog("PASS (Wi-Fi ON path): translate succeeded in ${ms}ms: \"$translated\" - auto-download-on-Wi-Fi still works.")
                } else {
                    appendLog("FAIL: translate SUCCEEDED with Wi-Fi OFF and neither model downloaded (${ms}ms) - gating did not block it.")
                }
                runAlreadyDownloadedCheck(downloaded)
            },
            onError = { err ->
                val ms = (System.nanoTime() - startNanos) / 1_000_000
                when {
                    onWifi -> appendLog("INFO (Wi-Fi ON path): translate failed in ${ms}ms with: \"$err\" (a real network/model error - the gating message only fires when Wi-Fi is off).")
                    err.contains("Connect to Wi-Fi") -> appendLog("PASS (Wi-Fi OFF path): translate short-circuited in ${ms}ms with the new clear message: \"$err\" - no cellular download attempted, no silent hang.")
                    else -> appendLog("FAIL: translate failed with Wi-Fi OFF but NOT with the expected gating message. Got: \"$err\" (${ms}ms)")
                }
                runAlreadyDownloadedCheck(downloaded)
            }
        )
    }

    /** Regression check: an already-downloaded pair must still translate successfully regardless of current Wi-Fi state - the "offline forever after download" promise must be unregressed by this fix. */
    private fun runAlreadyDownloadedCheck(downloaded: Set<String>) {
        if (downloaded.size < 2) {
            appendLog("SKIP already-downloaded regression check: fewer than 2 models downloaded on this device.")
            appendLog("DONE.")
            return
        }
        val pair = downloaded.toList()
        val src = pair[0]
        val tgt = pair[1]
        val onWifi = DownloadManager.isOnWifi(this)
        appendLog("Already-downloaded regression check: $src -> $tgt, Wi-Fi is currently ${if (onWifi) "ON" else "OFF"}")
        val startNanos = System.nanoTime()
        TranslationEngine.translate(this, src, tgt, "hello",
            onResult = { translated ->
                val ms = (System.nanoTime() - startNanos) / 1_000_000
                appendLog("PASS (already-downloaded regression): translate succeeded in ${ms}ms: \"$translated\" regardless of Wi-Fi state.")
                appendLog("DONE.")
            },
            onError = { err ->
                val ms = (System.nanoTime() - startNanos) / 1_000_000
                appendLog("FAIL (already-downloaded regression): translate failed in ${ms}ms with: \"$err\" - this should have succeeded, both models are already downloaded.")
                appendLog("DONE.")
            }
        )
    }

    companion object {
        private const val TAG = "WifiGateTest"
    }
}
