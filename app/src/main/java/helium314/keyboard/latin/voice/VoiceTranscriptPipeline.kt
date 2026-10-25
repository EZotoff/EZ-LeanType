// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale

/**
 * Pure text stage of the dictation pipeline, extracted verbatim from
 * VoiceInputManager.syncRecognizedText so the exact same transforms run in the
 * IME and in the offline corpus harness (debug builds). No Android
 * dependencies: strings in, strings out.
 *
 * Final chain: spoken punctuation -> filler removal -> spoken symbols ->
 * vocabulary corrector (finals only, no-AI-provider fallback) -> [AI pass,
 * wired by the caller] -> echo guard -> command detection ->
 * capitalization + question-mark logic.
 */
object VoiceTranscriptPipeline {

    const val ECHO_GUARD_WINDOW_MS = 8000L

    data class StageFlags(
        val smartPunctuation: Boolean,
        val removeFillers: Boolean,
        val aiPostProcessing: Boolean,
        val aiUsable: Boolean,
        val customVocab: List<String>
    )

    /** Spoken-form interpretation + fallback vocabulary correction. */
    fun preTransform(rawText: String, isFinal: Boolean, flags: StageFlags): String {
        var processedRaw = if (flags.smartPunctuation) {
            VoiceTextProcessor.applySpokenPunctuation(rawText)
        } else {
            rawText.replace(Regex("[,.?!;:]"), "")
        }
        if (flags.removeFillers) {
            processedRaw = VoiceTextProcessor.removeFillers(processedRaw)
        }
        // Symbol conversion and vocabulary correction share the smart
        // punctuation toggle: they are spoken-form interpretation, and users
        // who dictate literal words ("open bracket") need an off switch.
        // Vocabulary correction runs on finals only: it is O(words x vocab)
        // and must not run on every streaming partial.
        if (flags.smartPunctuation) {
            processedRaw = SpokenSymbolProcessor.apply(processedRaw)
        }
        // Vocabulary strategy: with a working AI provider the corrector is
        // OFF (double-processing hijacks common words: observed 'open' ->
        // 'Oren').
        val runCorrector = flags.customVocab.isNotEmpty() && !flags.aiUsable
        if (isFinal && runCorrector) {
            processedRaw = VocabularyCorrector.correct(processedRaw, flags.customVocab)
        }
        return processedRaw
    }

    fun normalize(processedRaw: String): String =
        processedRaw.trim().replace(Regex("\\s+"), " ")

    /**
     * Echo guard check: continuous-mode engines sometimes re-decode the tail of
     * the previous segment and emit it as a new final (observed: 'LM' after
     * '...Light L L M.'). True if [trimmed] is a short (<=16 chars) suffix of
     * one of the [recentFinals] (callers pass both the AI-refined and the
     * pre-AI text: the refine rewrites the tail, so the pre-AI form is what
     * the echo actually resembles).
     */
    fun isTailEcho(trimmed: String, recentFinals: List<String>): Boolean {
        if (trimmed.isEmpty() || recentFinals.isEmpty()) return false
        if (trimmed.length > 16) return false
        val echoWords = trimmed.lowercase(Locale.ROOT).split(' ').size
        return recentFinals.any { recent ->
            val tailWords = recent.lowercase(Locale.ROOT).split(' ', '.', ',', '!', '?')
                .filter { it.isNotEmpty() }
            tailWords.takeLast(echoWords).joinToString(" ") == trimmed.lowercase(Locale.ROOT)
        }
    }

    /** Command action if a final segment is exactly a spoken command, else null. */
    fun detectCommand(trimmed: String): VoiceTextProcessor.Action? =
        VoiceTextProcessor.COMMANDS[trimmed.lowercase(Locale.ROOT)]

    /** Display form of a FINAL segment: capitalization + question-mark logic. */
    fun finalDisplayText(trimmed: String, needsCapitalStart: Boolean, lastPartialText: String?): String {
        var displayText = if (needsCapitalStart && trimmed.isNotEmpty()) {
            trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            trimmed
        }
        if (displayText.endsWith(".")) {
            if (lastPartialText?.trimEnd()?.endsWith("?") == true) {
                displayText = displayText.dropLast(1) + "?"
            } else {
                val lower = displayText.lowercase(Locale.ROOT)
                if (QUESTION_PREFIXES.any { lower.startsWith(it) }) {
                    displayText = displayText.dropLast(1) + "?"
                }
            }
        }
        return displayText
    }

    /** Display form of a streaming PARTIAL: capitalization + question flip. */
    fun partialDisplayText(trimmed: String, needsCapitalStart: Boolean): String {
        var displayText = if (needsCapitalStart && trimmed.isNotEmpty()) {
            trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            trimmed
        }
        if (displayText.endsWith(".")) {
            val lower = displayText.lowercase(Locale.ROOT)
            if (QUESTION_PREFIXES.any { lower.startsWith(it) }) {
                displayText = displayText.dropLast(1) + "?"
            }
        }
        return displayText
    }

    fun needsCapitalAfter(text: String): Boolean {
        val lastChar = text.lastOrNull()
        return lastChar != null && lastChar in ".!?"
    }

    val QUESTION_PREFIXES = arrayOf(
        // English question words / auxiliaries
        "what ", "why ", "how ", "when ", "where ", "who ", "which ", "whose ", "whom ",
        "is ", "are ", "am ", "was ", "were ", "do ", "does ", "did ",
        "can ", "could ", "should ", "would ", "will ", "won't ", "shall ",
        "has ", "have ", "had ", "isn't ", "aren't ", "wasn't ", "weren't ",
        "don't ", "doesn't ", "didn't ", "can't ", "couldn't ", "shouldn't ", "wouldn't ",
        // Russian question words / interrogatives
        "почему ", "зачем ", "как ", "где ", "куда ", "откуда ", "когда ",
        "кто ", "что ", "чей ", "чья ", "чье ", "чьё ", "чьи ",
        "какой ", "какая ", "какое ", "какие ", "какого ", "какому ", "каким ",
        "сколько ", "насколько ",
        "правда ли ", "неужели ", "разве ",
        "а почему ", "а зачем ", "а как ", "а где ", "а куда ", "а откуда ", "а когда ",
        "а кто ", "а что ", "а разве ", "а правда "
    )
}
