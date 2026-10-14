// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import com.leanbitlab.leantype.voice.VoiceConstants
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray

/** Reads and parses voice-input preferences. */
object VoiceInputPrefs {

    /** Custom vocabulary words, parsed from the JSON-array preference (with legacy comma/newline fallback). */
    fun customVocabulary(context: Context): List<String> {
        val raw = context.prefs().getString(VoiceConstants.PREF_VOICE_CUSTOM_VOCABULARY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            if (raw.trimStart().startsWith("[")) {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.optString(it) }
            } else {
                raw.split(',', '\n', ';')
            }.map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** Vocabulary words merged into the Whisper prompt, e.g. "Terms: word1, word2." */
    fun whisperPromptWithVocabulary(context: Context): String? {
        val base = context.prefs().getString(VoiceConstants.PREF_VOICE_CUSTOM_PROMPT, "")?.trim()?.takeIf { it.isNotEmpty() }
        val vocab = customVocabulary(context)
        if (vocab.isEmpty()) return base
        val vocabPart = "Vocabulary: " + vocab.joinToString(", ") + "."
        return if (base != null) "$base $vocabPart" else vocabPart
    }
}
