package com.retroid.translator.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * The versioned JSON shape a Local Data Vault export/import round-trips
 * through (docs/specs/engineering-systems-pitch.md system #7). Pure
 * Kotlin - no Context, no Android-framework dependency beyond org.json
 * (already this codebase's established JSON library, see
 * [com.retroid.translator.engine.VoskResultParsing]) - so every function
 * here is directly unit-testable without Robolectric.
 *
 * All real persistence I/O (SQLite, SharedPreferences, the SAF Uri itself)
 * lives in [com.retroid.translator.backup.BackupManager], deliberately kept
 * out of this file so the type-tagging logic below - the one genuinely
 * fiddly piece of this system - can be tested in plain JVM unit tests.
 */
object BackupBundle {
    /** Bumped only if this shape changes incompatibly. [BackupManager.import] refuses anything else. */
    const val SCHEMA_VERSION = 1

    // Top-level keys.
    const val KEY_SCHEMA_VERSION = "schemaVersion"
    const val KEY_EXPORTED_AT_EPOCH_MS = "exportedAtEpochMs"
    const val KEY_LEARN_PROGRESS = "learnProgress"
    const val KEY_PREFERENCES = "preferences"

    // learnProgress sub-keys - one entry per real LearnProgressStore table
    // (app_state/lesson_completion/srs_state - all three, confirmed via a
    // real read of that file; the original pitch doc's own JSON scaffold
    // only showed two of them, omitting lesson_completion entirely).
    const val KEY_APP_STATE = "appState"
    const val KEY_LESSON_COMPLETION = "lessonCompletion"
    const val KEY_SRS_STATE = "srsState"

    // ------------------------------------------------------------------
    // SharedPreferences value type-tagging
    // ------------------------------------------------------------------
    //
    // SharedPreferences.Editor supports exactly 6 real value types
    // (Boolean/Int/Long/Float/String/Set<String>), but JSON has no native
    // Int-vs-Long distinction and no Set type. A plain JSONObject.put(k, v)
    // would round-trip every real value this app currently stores
    // (confirmed via a real grep of every getSharedPreferences( call site
    // this app has: only String/Boolean/Long are actually in use today -
    // NOT the original pitch doc's "five known files" claim, corrected to
    // the real three in BackupManager) but would silently misbehave the
    // moment a future preference introduces an Int or a Set<String> - this
    // tags every value's real type explicitly instead of relying on JSON's
    // own type inference, a real (if not yet triggered) gotcha, not a
    // hypothetical one.

    private const val TAG_TYPE = "type"
    private const val TAG_VALUE = "value"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "stringSet"

    /** Encodes one real SharedPreferences value ([android.content.SharedPreferences.getAll]'s per-entry value) as a type-tagged JSON object. */
    fun encodePrefValue(value: Any): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> { put(TAG_TYPE, TYPE_BOOLEAN); put(TAG_VALUE, value) }
            is Int -> { put(TAG_TYPE, TYPE_INT); put(TAG_VALUE, value) }
            is Long -> { put(TAG_TYPE, TYPE_LONG); put(TAG_VALUE, value) }
            // JSON has no distinct float literal type either - stored as a
            // JSON number, decoded back to Float explicitly by the "float"
            // tag below, never inferred from the number's own shape.
            is Float -> { put(TAG_TYPE, TYPE_FLOAT); put(TAG_VALUE, value.toDouble()) }
            is String -> { put(TAG_TYPE, TYPE_STRING); put(TAG_VALUE, value) }
            is Set<*> -> {
                put(TAG_TYPE, TYPE_STRING_SET)
                val arr = JSONArray()
                value.forEach { arr.put(it as? String ?: it.toString()) }
                put(TAG_VALUE, arr)
            }
            else -> throw IllegalArgumentException("Unsupported SharedPreferences value type: ${value::class.java}")
        }
    }

    /**
     * Reverses [encodePrefValue] into a plain Kotlin value of the real
     * original type (Boolean/Int/Long/Float/String/Set<String>) - NOT
     * applied to a [android.content.SharedPreferences.Editor] here, since
     * that would make this Android-framework-coupled; [BackupManager]
     * dispatches the returned value onto the right `Editor.put*` call.
     */
    fun decodePrefValue(tagged: JSONObject): Any {
        val type = tagged.getString(TAG_TYPE)
        return when (type) {
            TYPE_BOOLEAN -> tagged.getBoolean(TAG_VALUE)
            TYPE_INT -> tagged.getInt(TAG_VALUE)
            TYPE_LONG -> tagged.getLong(TAG_VALUE)
            TYPE_FLOAT -> tagged.getDouble(TAG_VALUE).toFloat()
            TYPE_STRING -> tagged.getString(TAG_VALUE)
            TYPE_STRING_SET -> {
                val arr = tagged.getJSONArray(TAG_VALUE)
                (0 until arr.length()).mapTo(LinkedHashSet()) { arr.getString(it) }
            }
            else -> throw IllegalArgumentException("Unknown tagged preference type: $type")
        }
    }
}
