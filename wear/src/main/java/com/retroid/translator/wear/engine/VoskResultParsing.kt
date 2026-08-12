package com.retroid.translator.wear.engine

import org.json.JSONObject

/**
 * Trimmed port of the phone app's
 * [com.retroid.translator.engine.VoskResultParsing] - only the plain
 * text/partial-text extraction is needed here. The dual-recognizer
 * avgWordConf/pickLanguage machinery was NOT ported: this pass's translate
 * flow uses one explicitly-user-selected source language at a time (a
 * language picker, not Conversations' auto-detect-which-of-two-languages
 * problem), so there is nothing to pick between. If a future pass adds a
 * Conversations-equivalent mode to the watch, port the rest of that file
 * verbatim at that point rather than guessing at it now.
 */
object VoskResultParsing {
    fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "")
    } catch (e: Exception) {
        ""
    }

    fun extractPartialText(json: String): String = try {
        JSONObject(json).optString("partial", "")
    } catch (e: Exception) {
        ""
    }
}
