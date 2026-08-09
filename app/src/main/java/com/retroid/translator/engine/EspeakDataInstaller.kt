package com.retroid.translator.engine

import android.content.Context
import android.util.Log
import com.reecedunn.espeak.CheckVoiceData
import java.io.File

/**
 * Copies the bundled `assets/espeak-ng-data` (18MB, all ~114 espeak-ng
 * languages, shipped inside the APK itself) out to the app-private
 * filesystem location that the native eSpeak NG library expects
 * (see [CheckVoiceData.getDataPath]). Native code cannot read directly out
 * of the compressed APK, so this one-time unpack is required before the
 * synthesizer can initialize. Runs once; subsequent launches are a no-op.
 */
object EspeakDataInstaller {
    private const val TAG = "EspeakDataInstaller"
    private const val ASSET_DIR = "espeak-ng-data"

    @Volatile
    private var installed = false

    /** Blocking; call from a background thread. Idempotent. */
    fun ensureInstalled(context: Context): Boolean {
        if (installed) return true
        val destRoot = CheckVoiceData.getDataPath(context)
        val markerFile = File(destRoot, ".installed_v1")
        if (markerFile.exists()) {
            installed = true
            return true
        }
        return try {
            if (destRoot.exists()) destRoot.deleteRecursively()
            destRoot.mkdirs()
            copyAssetDirRecursive(context, ASSET_DIR, destRoot)
            markerFile.writeText("ok")
            installed = true
            Log.i(TAG, "espeak-ng-data installed to ${destRoot.path}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install espeak-ng-data", e)
            false
        }
    }

    private fun copyAssetDirRecursive(context: Context, assetPath: String, destDir: File) {
        val am = context.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // Leaf file.
            destDir.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val grandChildren = am.list(childAssetPath)
            if (grandChildren != null && grandChildren.isNotEmpty()) {
                copyAssetDirRecursive(context, childAssetPath, childDest)
            } else {
                // Could be an empty dir marker or a leaf file; try opening as a file.
                try {
                    am.open(childAssetPath).use { input ->
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    // Empty directory - create it and move on.
                    childDest.mkdirs()
                }
            }
        }
    }
}
