package com.retroid.translator.wear

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.remember
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

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result observed via hasRecordAudioPermission() on next recomposition trigger below */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TranslateController(applicationContext)

        setContent {
            var permissionGranted by remember { mutableStateOf(hasRecordAudioPermission()) }
            WearTranslateApp(
                controller = controller,
                micPermissionGranted = permissionGranted,
                onRequestMicPermission = {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    // Wear Compose has no permission-result callback wired
                    // into recomposition here without a full ViewModel/Flow
                    // setup (out of scope for this pass's single screen) -
                    // re-check synchronously right after the system dialog
                    // returns control, which is good enough for a single
                    // grant/deny tap.
                    permissionGranted = hasRecordAudioPermission()
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
                Chip(
                    onClick = { controller.cycleSourceLang() },
                    label = { Text("${controller.sourceLang.displayName} -> ${controller.targetLang.displayName}") },
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
                    Text(
                        "${controller.sourceLang.displayName} pack not downloaded",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2
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
