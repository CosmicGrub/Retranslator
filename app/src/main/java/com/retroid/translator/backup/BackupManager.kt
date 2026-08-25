package com.retroid.translator.backup

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.retroid.translator.diagnostics.Diag
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.learn.LearnProgressStore
import com.retroid.translator.packs.LanguagePackPreferences
import com.retroid.translator.settings.LayoutPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter

/**
 * The only class in this pass that touches persistence directly
 * (docs/specs/engineering-systems-pitch.md system #7). [export] reads
 * [LearnProgressStore]'s three real tables (app_state/lesson_completion/
 * srs_state - confirmed via a real read of that file; the original pitch
 * doc's own JSON scaffold only showed two of them, omitting
 * lesson_completion) plus the three real SharedPreferences files this app
 * actually uses (confirmed via a real grep of every getSharedPreferences(
 * call site - NOT the original pitch doc's "five known files" claim),
 * assembles one [JSONObject], and writes it to the [Uri] the user picked
 * via Android's Storage Access Framework
 * (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`) - zero sockets opened,
 * the same mechanism any "Save As" dialog uses. [import] reverses this,
 * first writing a JSON safety-net snapshot (reusing the exact same export
 * logic, not a raw SQLite file copy - see [writeSafetyNetSnapshot]'s doc
 * comment for why) since import is destructive-by-construction: a full
 * replace, not a merge.
 *
 * Deliberately excludes: `recordings/` (.wav files, already excluded from
 * OS backup for privacy - folding them into a JSON export would quietly
 * undo that), `vosk-models/`/`piper-voices/` (large, fully re-downloadable,
 * no reason to bloat an export the user has to store somewhere), and
 * conversation transcripts (not exportable because they are not persisted
 * anywhere today - `ConversationsFragment` holds them in a plain in-memory
 * list; giving transcripts a persistence layer at all is separate, larger,
 * more privacy-sensitive prerequisite work this pass does not resolve).
 */
object BackupManager {
    private const val TAG = "BackupManager"

    /** Distinguishes "this file isn't a valid/importable backup" (show the user why) from a genuine I/O failure. */
    class ImportRefusedException(message: String) : Exception(message)

    // Real SharedPreferences file names this app uses, referenced from each
    // owning object's own real constant (not duplicated literals - the
    // original pitch doc's own disclosed risk).
    private val PREFS_FILE_NAMES = listOf(
        VoicePreferences.PREFS,
        LanguagePackPreferences.PREFS_NAME,
        LayoutPreferences.PREFS_NAME,
    )

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Runs real SQLite/SharedPreferences reads and Uri I/O - call off the main thread. */
    fun export(context: Context, uri: Uri) {
        val root = buildBackupJson(context)
        val resolver = context.applicationContext.contentResolver
        val stream = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open an output stream for $uri")
        stream.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(root.toString(2)) }
        }
    }

    private fun buildBackupJson(context: Context): JSONObject = JSONObject().apply {
        put(BackupBundle.KEY_SCHEMA_VERSION, BackupBundle.SCHEMA_VERSION)
        put(BackupBundle.KEY_EXPORTED_AT_EPOCH_MS, System.currentTimeMillis())
        put(BackupBundle.KEY_LEARN_PROGRESS, exportLearnProgress(context))
        put(BackupBundle.KEY_PREFERENCES, exportPreferences(context))
    }

    private fun exportLearnProgress(context: Context): JSONObject {
        val db = LearnProgressStore(context).readableDatabase
        val out = JSONObject()

        val appState = JSONObject()
        db.rawQuery("SELECT key, value FROM app_state", null).use { c ->
            while (c.moveToNext()) appState.put(c.getString(0), c.getString(1))
        }
        out.put(BackupBundle.KEY_APP_STATE, appState)

        val lessonCompletion = JSONArray()
        db.rawQuery("SELECT lesson_key, completed_at_epoch_ms, xp_earned FROM lesson_completion", null).use { c ->
            while (c.moveToNext()) {
                lessonCompletion.put(
                    JSONObject().apply {
                        put("lessonKey", c.getString(0))
                        put("completedAtEpochMs", c.getLong(1))
                        put("xpEarned", c.getInt(2))
                    }
                )
            }
        }
        out.put(BackupBundle.KEY_LESSON_COMPLETION, lessonCompletion)

        val srsState = JSONArray()
        db.rawQuery(
            "SELECT exercise_key, box, next_review_epoch_day, correct_count, incorrect_count FROM srs_state",
            null
        ).use { c ->
            while (c.moveToNext()) {
                srsState.put(
                    JSONObject().apply {
                        put("exerciseKey", c.getString(0))
                        put("box", c.getInt(1))
                        put("nextReviewEpochDay", c.getLong(2))
                        put("correctCount", c.getInt(3))
                        put("incorrectCount", c.getInt(4))
                    }
                )
            }
        }
        out.put(BackupBundle.KEY_SRS_STATE, srsState)
        return out
    }

    private fun exportPreferences(context: Context): JSONObject {
        val out = JSONObject()
        for (name in PREFS_FILE_NAMES) {
            val fileObj = JSONObject()
            context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value != null) fileObj.put(key, BackupBundle.encodePrefValue(value))
            }
            out.put(name, fileObj)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Runs real SQLite/SharedPreferences reads+writes and Uri I/O - call
     * off the main thread. Throws [ImportRefusedException] (safe to show
     * the message to the user directly) if [uri] isn't a valid, compatible
     * backup; nothing is modified in that case. Any other exception means a
     * genuine I/O failure.
     */
    fun import(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        val text = resolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() }
            ?: throw IllegalStateException("Could not open an input stream for $uri")

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ImportRefusedException("This file isn't a valid backup (not JSON): ${e.message}")
        }

        val version = root.optInt(BackupBundle.KEY_SCHEMA_VERSION, -1)
        if (version != BackupBundle.SCHEMA_VERSION) {
            throw ImportRefusedException(
                if (version > BackupBundle.SCHEMA_VERSION) {
                    "This backup was made by a newer version of the app (schema $version) and can't be safely imported here."
                } else {
                    "This file doesn't look like a RetroidTranslator backup (missing or invalid schema version)."
                }
            )
        }

        writeSafetyNetSnapshot(context)

        importLearnProgress(context, root.optJSONObject(BackupBundle.KEY_LEARN_PROGRESS) ?: JSONObject())
        importPreferences(context, root.optJSONObject(BackupBundle.KEY_PREFERENCES) ?: JSONObject())
    }

    private fun importLearnProgress(context: Context, obj: JSONObject) {
        val db = LearnProgressStore(context).writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM app_state")
            db.execSQL("DELETE FROM lesson_completion")
            db.execSQL("DELETE FROM srs_state")

            obj.optJSONObject(BackupBundle.KEY_APP_STATE)?.let { appState ->
                appState.keys().forEach { key ->
                    db.insert(
                        "app_state",
                        null,
                        ContentValues().apply {
                            put("key", key)
                            put("value", appState.getString(key))
                        }
                    )
                }
            }
            obj.optJSONArray(BackupBundle.KEY_LESSON_COMPLETION)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    db.insert(
                        "lesson_completion",
                        null,
                        ContentValues().apply {
                            put("lesson_key", row.getString("lessonKey"))
                            put("completed_at_epoch_ms", row.getLong("completedAtEpochMs"))
                            put("xp_earned", row.getInt("xpEarned"))
                        }
                    )
                }
            }
            obj.optJSONArray(BackupBundle.KEY_SRS_STATE)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    db.insert(
                        "srs_state",
                        null,
                        ContentValues().apply {
                            put("exercise_key", row.getString("exerciseKey"))
                            put("box", row.getInt("box"))
                            put("next_review_epoch_day", row.getLong("nextReviewEpochDay"))
                            put("correct_count", row.getInt("correctCount"))
                            put("incorrect_count", row.getInt("incorrectCount"))
                        }
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun importPreferences(context: Context, obj: JSONObject) {
        for (name in PREFS_FILE_NAMES) {
            val fileObj = obj.optJSONObject(name) ?: continue
            val editor = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            fileObj.keys().forEach { key ->
                editor.putDecoded(key, BackupBundle.decodePrefValue(fileObj.getJSONObject(key)))
            }
            editor.apply()
        }
    }

    private fun SharedPreferences.Editor.putDecoded(key: String, decoded: Any) {
        when (decoded) {
            is Boolean -> putBoolean(key, decoded)
            is Int -> putInt(key, decoded)
            is Long -> putLong(key, decoded)
            is Float -> putFloat(key, decoded)
            is String -> putString(key, decoded)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, decoded as Set<String>)
            }
            else -> throw IllegalStateException("Unsupported decoded preference value type: ${decoded::class.java}")
        }
    }

    // ------------------------------------------------------------------
    // Pre-import safety net
    // ------------------------------------------------------------------

    /**
     * Writes a full backup snapshot (the exact same [buildBackupJson] logic
     * [export] uses) to app-private internal storage before [import]
     * overwrites anything. Deliberately NOT a raw copy of the live
     * `learn_progress.db` file (the pitch doc's own original scaffold
     * suggested `.pre-import-<epochMs>.bak`): copying a SQLite file while
     * this same process may hold an open connection to it risks capturing
     * an inconsistent snapshot, and a raw file copy wouldn't cover the
     * SharedPreferences half of the state at all. Reusing the already-
     * correct, SQL-query-based export path avoids both problems and is a
     * real, re-importable backup if ever needed - not user-facing (no
     * "restore last snapshot" UI exists this pass, see honest gaps), but a
     * genuine safety net rather than a best-effort file copy.
     *
     * Wrapped in its own try/catch, deliberately: a failure here must never
     * block the import the user actually asked for.
     */
    private fun writeSafetyNetSnapshot(context: Context) {
        try {
            val dir = File(context.applicationContext.filesDir, SAFETY_NET_DIR)
            dir.mkdirs()
            pruneOldSnapshots(dir)
            val file = File(dir, "pre-import-${System.currentTimeMillis()}.json")
            file.writeText(buildBackupJson(context).toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Diag.w(TAG, "Pre-import safety-net snapshot failed (proceeding anyway - import itself is transactional)", e)
        }
    }

    /** Keeps at most [keep] snapshots so repeated imports can't grow this directory unbounded - same capped-growth discipline as DiagnosticsStore.MAX_EVENTS. */
    private fun pruneOldSnapshots(dir: File, keep: Int = 3) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("pre-import-") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private const val SAFETY_NET_DIR = "backup_safety_net"
}
