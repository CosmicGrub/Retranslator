package com.retroid.translator.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * :wear's single screen for this pass - Wear Compose
 * (androidx.wear.compose.material), not the phone app's XML+ViewBinding (see
 * spec's "Design decisions already made"). Deliberately one Activity, one
 * screen, no navigation graph yet: this pass's scope is proving the
 * standalone translate flow works end to end on real hardware, not building
 * out the full curated-language-picker/settings/download-management UI the
 * phone app has (ManagePacksFragment etc.) - that is real, scoped follow-up
 * work, not an oversight.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: TranslateController

    /**
     * Real bug found via on-device testing (docs/specs/watch6-classic-adaptation.md
     * "hard technical question" pass): this MUST be a Compose state read
     * directly by the callback below, not re-checked synchronously right
     * after calling `.launch()` in the click handler. `launch()` only
     * *starts* the system permission Activity - it returns immediately,
     * long before the user has answered the dialog - so a same-stack-frame
     * `hasRecordAudioPermission()` call after it always observes the
     * pre-grant state. Confirmed on the real Watch6 Classic: tapping
     * "While using app" on the real system dialog genuinely granted the
     * permission (`adb shell dumpsys package` showed
     * `RECORD_AUDIO: granted=true`) but the UI kept showing "Grant mic"
     * until this fix, because the Composable never got told. The permission
     * *system* worked correctly the whole time; this was purely a
     * state-plumbing bug in this new code.
     */
    private var micPermissionGranted by mutableStateOf(false)

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micPermissionGranted = granted }

    /**
     * Wake-lock/foreground-service fix (see ContinuousListeningService's
     * class doc): requested eagerly here, not gated behind a visible UI
     * button the way mic permission is, because a denial isn't fatal to
     * continuous listening itself - it only suppresses the persistent
     * "Listening…" notification's visibility (Android doesn't gate
     * starting a foreground service on this permission), so there's no
     * app state that needs to react to the result the way
     * [micPermissionGranted] does. No-op callback is deliberate, not an
     * oversight.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op - see doc above */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TranslateController(applicationContext)
        micPermissionGranted = hasRecordAudioPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            WearTranslateApp(
                controller = controller,
                micPermissionGranted = micPermissionGranted,
                onRequestMicPermission = {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
    }
}

@Composable
fun WearTranslateApp(
    controller: TranslateController,
    micPermissionGranted: Boolean,
    onRequestMicPermission: () -> Unit
) {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PaddingValues(horizontal = 12.dp, vertical = 28.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Two separate compact controls, not one combined chip: the
                // combined "X -> Y" label was tap-to-cycle-source only, and
                // with no way to move target off its default (Spanish),
                // source could never reach Spanish either - cycleSourceLang's
                // own skip-if-equals-target logic permanently locked it out.
                // Real gap, not a display nit - any language pair selection
                // needs both directions changeable.
                Chip(
                    onClick = { controller.cycleSourceLang() },
                    label = { Text(controller.sourceLang.displayName) },
                    colors = ChipDefaults.primaryChipColors()
                )
                Chip(
                    onClick = { controller.cycleTargetLang() },
                    label = { Text("-> ${controller.targetLang.displayName}") },
                    colors = ChipDefaults.secondaryChipColors()
                )

                Text(
                    text = controller.statusMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2
                )

                if (!micPermissionGranted) {
                    Button(onClick = onRequestMicPermission, colors = ButtonDefaults.primaryButtonColors()) {
                        Text("Grant mic")
                    }
                } else if (!controller.isSourceModelDownloaded()) {
                    // Chip, not Button: Wear Compose's Button is circular/
                    // compact and wraps longer labels badly mid-word on a
                    // round screen (confirmed on real hardware) - Chip is
                    // the wider pill shape already used for the language
                    // selectors above, and fits this label properly.
                    Chip(
                        onClick = { controller.downloadSourceModel() },
                        label = {
                            Text(
                                if (controller.state == ListenState.LOADING_MODEL)
                                    "Downloading..."
                                else
                                    "Download ${controller.sourceLang.displayName}"
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        enabled = controller.state != ListenState.LOADING_MODEL
                    )
                } else {
                    Button(
                        onClick = { controller.toggleListening() },
                        colors = if (controller.state == ListenState.IDLE)
                            ButtonDefaults.primaryButtonColors()
                        else
                            ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text(if (controller.state == ListenState.IDLE) "Listen" else "Stop")
                    }
                }

                if (controller.transcript.isNotBlank()) {
                    Text(
                        text = "\"${controller.transcript}\"",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption3
                    )
                }
                if (controller.translated.isNotBlank()) {
                    Text(
                        text = controller.translated,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2
                    )
                }
            }
        }
    }
}
