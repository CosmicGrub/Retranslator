package com.retroid.translator.ocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.retroid.translator.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera OCR translate capture screen (docs/specs/fold5-adaptation.md
 * "Camera OCR translate" section). Single-shot capture, not a live AR
 * sweep/overlay: CameraX drives a plain live preview so the user can frame
 * the shot; tapping the capture button takes exactly one frame, runs
 * on-device ML Kit Text Recognition on it, and (on success) finishes this
 * Activity with the recognized text as a result extra -
 * [TranslateFragment][com.retroid.translator.ui.TranslateFragment] then
 * feeds that text into the exact same [com.retroid.translator.engine.TranslationEngine]
 * pipeline typed/spoken text already goes through, not a separate code
 * path.
 *
 * Reachable only from [TranslateFragment][com.retroid.translator.ui.TranslateFragment]'s
 * default layout (see that class's `bindDefault` for why this pass didn't
 * duplicate the entry button across all 8 Translate layouts) via
 * `startActivityForResult`/[androidx.activity.result.ActivityResultLauncher] -
 * this screen itself does not know or care which of those 8 layouts
 * launched it, matching the task's "layout-variant-agnostic" requirement.
 */
class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var selectedScript: OcrScript = OcrScript.LATIN
    private var recognizing = false

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var spinnerScript: android.widget.Spinner
    private lateinit var btnDownloadScript: android.widget.Button
    private lateinit var textOcrStatus: android.widget.TextView
    private lateinit var btnCapture: android.widget.ImageButton
    private lateinit var progressRecognizing: android.widget.ProgressBar

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to scan text.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        previewView = findViewById(R.id.previewView)
        spinnerScript = findViewById(R.id.spinnerScript)
        btnDownloadScript = findViewById(R.id.btnDownloadScript)
        textOcrStatus = findViewById(R.id.textOcrStatus)
        btnCapture = findViewById(R.id.btnCapture)
        progressRecognizing = findViewById(R.id.progressRecognizing)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupScriptSpinner()
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        btnCapture.setOnClickListener { captureAndRecognize() }
        btnDownloadScript.setOnClickListener { downloadSelectedScript() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // -------------------------------------------------------------------
    // Script picker - Latin (default, always ready) or Chinese (on-demand,
    // see OcrEngine's doc comment). Reuses this app's existing "status text
    // + Download button" pack UI shape (TranslateFragment's
    // textSttStatus/btnDownloadStt is the closest sibling), not a new one.
    // -------------------------------------------------------------------

    private fun setupScriptSpinner() {
        val names = OcrScript.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerScript.adapter = adapter
        spinnerScript.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedScript = OcrScript.values()[pos]
                refreshScriptReadiness()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        refreshScriptReadiness()
    }

    private fun refreshScriptReadiness() {
        val script = selectedScript
        textOcrStatus.text = "Checking ${script.displayName} pack status..."
        btnDownloadScript.visibility = View.GONE
        btnCapture.isEnabled = false
        OcrEngine.isScriptReady(this, script) { ready ->
            if (isFinishing) return@isScriptReady
            if (selectedScript != script) return@isScriptReady // user switched again while this was in flight
            if (ready) {
                textOcrStatus.text = "Point the camera at printed or on-screen text, then tap the button below."
                btnDownloadScript.visibility = View.GONE
                btnCapture.isEnabled = true
            } else {
                textOcrStatus.text = "${script.displayName} text-recognition pack not downloaded yet."
                btnDownloadScript.visibility = View.VISIBLE
                btnDownloadScript.isEnabled = true
                btnDownloadScript.text = "Download (Wi-Fi or cellular)"
                btnCapture.isEnabled = false
            }
        }
    }

    private fun downloadSelectedScript() {
        val script = selectedScript
        btnDownloadScript.isEnabled = false
        textOcrStatus.text = "Downloading ${script.displayName} pack (Wi-Fi or cellular)..."
        OcrEngine.downloadScript(
            this, script,
            onProgress = { pct ->
                if (!isFinishing && selectedScript == script) {
                    textOcrStatus.text = "Downloading ${script.displayName} pack... $pct%"
                }
            }
        ) { success, error ->
            if (isFinishing || selectedScript != script) return@downloadScript
            if (success) {
                Toast.makeText(this, "${script.displayName} pack downloaded.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Download failed: $error", Toast.LENGTH_LONG).show()
            }
            refreshScriptReadiness()
        }
    }

    // -------------------------------------------------------------------
    // CameraX preview + single-shot capture
    // -------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't obtain ProcessCameraProvider", e)
                Toast.makeText(this, "Couldn't start the camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed", e)
                Toast.makeText(this, "Couldn't start the camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndRecognize() {
        if (recognizing) return
        val capture = imageCapture ?: return
        recognizing = true
        setBusy(true, "Capturing...")
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val mediaImage = image.image
                if (mediaImage == null) {
                    image.close()
                    runOnUiThread {
                        recognizing = false
                        setBusy(false, "Capture failed - no image data, try again.")
                    }
                    return
                }
                val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                runOnUiThread { setBusy(true, "Recognizing text...") }
                OcrEngine.recognize(
                    selectedScript, inputImage,
                    onResult = { text ->
                        image.close()
                        runOnUiThread { onRecognized(text) }
                    },
                    onError = { err ->
                        image.close()
                        runOnUiThread {
                            recognizing = false
                            setBusy(false, "Text recognition failed: $err")
                            Toast.makeText(this@CameraCaptureActivity, "Text recognition failed: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    recognizing = false
                    setBusy(false, "Capture failed: ${exc.message}")
                    Toast.makeText(this@CameraCaptureActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    /** No text detected is a real, disclosed outcome, not a silent no-op: a Toast plus a status line, and the capture screen stays open so the user can reposition and try again. */
    private fun onRecognized(text: String) {
        recognizing = false
        // Metadata only, never the recognized text itself - it could be a
        // photographed document, letter, ID, or other private content, and
        // this is a shipped (non-debug-gated) log line, not a prototype one.
        // Matches ConversationsFragment's CONTINUOUS_LATENCY line, which
        // logs only timing/decision metadata and never transcript/
        // translation text for the same reason.
        Log.i(TAG, "OCR result: script=$selectedScript chars=${text.length} blank=${text.isBlank()}")
        if (text.isBlank()) {
            setBusy(false, "No text detected in that frame - reposition and try again.")
            Toast.makeText(this, "No text detected", Toast.LENGTH_SHORT).show()
            return
        }
        val data = Intent().putExtra(EXTRA_RECOGNIZED_TEXT, text)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun setBusy(busy: Boolean, status: String) {
        progressRecognizing.visibility = if (busy) View.VISIBLE else View.GONE
        btnCapture.isEnabled = !busy
        textOcrStatus.text = status
    }

    companion object {
        private const val TAG = "CameraCaptureActivity"
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
    }
}
