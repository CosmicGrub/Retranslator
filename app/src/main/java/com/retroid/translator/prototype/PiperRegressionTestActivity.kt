package com.retroid.translator.prototype

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.retroid.translator.engine.PiperTtsEngine
import com.retroid.translator.engine.PiperVoiceCatalog
import com.retroid.translator.engine.PiperVoiceInfo

/**
 * Throwaway, NON-shipped debug entry point for the sherpa-onnx v1.13.4 ->
 * v1.13.5 re-vendor's required full 4-language regression pass
 * (docs/specs/engines-upgrade-plan.md's Tier 2 item: the release includes a
 * real correctness fix in the Piper VITS phoneme pipeline this app drives
 * directly, so a smoke test on one language isn't enough). Same "prototype"
 * pattern as [WifiGateTestActivity]/[DualRecognizerProtoActivity] already in
 * this package - drives the real, unmodified [PiperTtsEngine] (the exact
 * production class every shipped speak call-site uses), no mocks. Added
 * purely because real on-device UI navigation for this pass was repeatedly
 * disrupted by a different concurrently-installed build sharing this same
 * physical device's foreground/package slot (see
 * docs/evidence/fold5-edition/sherpa-onnx-1.13.5-regression.md for the full
 * account) - this harness reaches the identical synth code path in one
 * `adb shell am start`, with no UI tap sequence to be interrupted mid-flow.
 *
 * Trigger:
 *   adb shell am start -n com.retroid.translator.fold5/com.retroid.translator.prototype.PiperRegressionTestActivity
 *   adb logcat -s PiperRegressionTest PiperTtsEngine
 *
 * For each of this app's 4 currently-supported Piper languages (en/de/es/fr),
 * this downloads the voice if not already present (real network call, same
 * [PiperTtsEngine.downloadVoice] every "Download natural voice" button in the
 * app calls), loads it (real [PiperTtsEngine.loadVoiceAsync]), and
 * synthesizes a real, language-appropriate test phrase (real
 * [PiperTtsEngine.speak], real sherpa-onnx `OfflineTts.generate` call, real
 * `AudioTrack` playback - not a stub), logging the same real
 * synthMs/audioMs/rtf line [PiperTtsEngine] already logs on every synth call,
 * plus a PASS/FAIL per language. Not linked from any nav flow, off the
 * launcher. Debug-build-only (declared in app/src/debug/AndroidManifest.xml)
 * - this activity does not exist at all in a release build.
 */
class PiperRegressionTestActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()
    private lateinit var engine: PiperTtsEngine

    // One voice per language - the exact 4 languages this app's Piper catalog
    // covers today. Picked the specific voiceId per language (not "any voice
    // for this language") so results are reproducible and directly citable.
    private val voicesToTest: List<Pair<PiperVoiceInfo, String>> = listOfNotNull(
        PiperVoiceCatalog.VOICES.find { it.voiceId == "en_US-ljspeech-medium" }
            ?.let { it to "The quick brown fox jumps over the lazy dog." },
        PiperVoiceCatalog.VOICES.find { it.voiceId == "de_DE-thorsten-medium" }
            ?.let { it to "Guten Tag, wie geht es Ihnen heute?" },
        PiperVoiceCatalog.VOICES.find { it.voiceId == "es_MX-claude-high" }
            ?.let { it to "Buenos dias, como estas hoy?" },
        PiperVoiceCatalog.VOICES.find { it.voiceId == "fr_FR-siwis-medium" }
            ?.let { it to "Bonjour, comment allez-vous aujourd'hui?" },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = "Starting Piper sherpa-onnx 4-language regression pass...\n"
        }
        setContentView(ScrollView(this).apply { addView(logView) })
        engine = PiperTtsEngine(this)
        runNext(0)
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            logBuilder.append(line).append('\n')
            logView.text = logBuilder.toString()
        }
    }

    private fun runNext(index: Int) {
        if (index >= voicesToTest.size) {
            appendLog("ALL DONE.")
            return
        }
        val (info, phrase) = voicesToTest[index]
        appendLog("--- [${index + 1}/${voicesToTest.size}] ${info.mlKitCode} (${info.voiceId}) ---")
        if (engine.isVoiceDownloaded(info)) {
            appendLog("Already downloaded: ${info.voiceId}")
            loadAndSpeak(info, phrase, index)
        } else {
            appendLog("Downloading ${info.voiceId} (~${info.approxSizeMiB}MB)...")
            engine.downloadVoice(this, info, onProgress = { pct ->
                if (pct % 25 == 0) appendLog("  download progress: $pct%")
            }) { success, error ->
                if (!success) {
                    appendLog("FAIL [${info.mlKitCode}]: download failed: $error")
                    runNext(index + 1)
                    return@downloadVoice
                }
                appendLog("Download complete: ${info.voiceId}")
                loadAndSpeak(info, phrase, index)
            }
        }
    }

    private fun loadAndSpeak(info: PiperVoiceInfo, phrase: String, index: Int) {
        engine.loadVoiceAsync(info) { loaded, loadErr ->
            if (!loaded) {
                appendLog("FAIL [${info.mlKitCode}]: load failed: $loadErr")
                runNext(index + 1)
                return@loadVoiceAsync
            }
            appendLog("Loaded ${info.voiceId}. Synthesizing: \"$phrase\"")
            engine.speak(
                text = phrase,
                info = info,
                onDone = {
                    appendLog("PASS [${info.mlKitCode}]: synth + playback completed for ${info.voiceId}.")
                    runNext(index + 1)
                },
                onError = { err ->
                    appendLog("FAIL [${info.mlKitCode}]: synth/playback error: $err")
                    runNext(index + 1)
                },
                onAudioStart = {
                    appendLog("  audio playback started for ${info.voiceId}")
                }
            )
        }
    }

    companion object {
        private const val TAG = "PiperRegressionTest"
    }
}
