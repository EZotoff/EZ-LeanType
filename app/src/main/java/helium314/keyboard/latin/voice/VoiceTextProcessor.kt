// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale
import java.util.StringTokenizer

object VoiceTextProcessor {

    enum class Action {
        NEW_LINE,
        NEW_PARAGRAPH,
        DELETE_LAST_WORD,
        CLEAR_ALL,
        SEND
    }

    sealed class Result {
        data class Command(val action: Action, val commandText: String) : Result()
        data class Text(val value: String, val isTerminal: Boolean) : Result()
    }

    // Pre-compile regex patterns at the object level to avoid main-thread allocations.
    // NOTE: no lookbehind here — Android's ICU regex engine (com.android.icu.util.regex)
    // fails to compile lookbehind on some builds and throws PatternSyntaxException during
    // <clinit>, which surfaces as ExceptionInInitializerError and kills the app (4.2.7 crash).
    // Use an optional leading space in the pattern instead, and re-emit it in the replacement.
    // Two-tier spoken punctuation (corpus voice_test B4):
    // - AGGRESSIVE phrases convert wherever they stand: what follows may be a
    //   space, ASR's own punctuation right after the phrase ("question Mark.",
    //   "tests period.") or end of input; duplicates collapse afterwards.
    //   "coma" is Phonon's consistent misspelling of "comma" and is mapped
    //   deliberately (a literal medical "coma" in dictation is rarer than
    //   the misspelling; corpus pins this).
    // - PERIOD-class phrases are ordinary English words ("billing period",
    //   "grace period"): they convert ONLY at clause end, i.e. immediately
    //   before terminal punctuation or end of input. A following word or
    //   non-terminal mark means the word sense wins. Russian "точка" stays
    //   aggressive (existing behavior, pinned by tests).
    private val PUNCT_REGEX = Regex(
        """( |^)(question mark|exclamation mark|exclamation point|semicolon|colon|comma|coma|вопросительный знак|восклицательный знак|точка с запятой|точка|запятая|двоеточие)(?=[ .,;:!?]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val PERIOD_AT_END_REGEX = Regex(
        """( |^)(period|full stop)(?=[.!?]|$)""",
        RegexOption.IGNORE_CASE
    )
    // ASR often emits its own punctuation where the user also spoke it;
    // collapse the duplicates: "??"/".." -> one mark, a comma yields to a
    // spoken stronger mark (",;" -> ";"), and a trailing period after a
    // spoken terminal mark is redundant ("?." -> "?", ";." -> ";").
    private val PUNCT_RUN_RE = Regex("""([,;:?.!])\1+""")
    private val COMMA_BEFORE_STRONGER_RE = Regex(""",([;:!?])""")
    private val REDUNDANT_TRAILING_DOT_RE = Regex("""([,;:!?])\.""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,;.!?])""")
    private val SPACE_AFTER_PUNCT = Regex("""([,;.!?])(?=\w)""")

    // Standalone filler words to drop (English + Russian). Must be whole words,
    // never inside real words. No lookbehind (Android ICU regex constraint, see above).
    // "короче" intentionally NOT here: it is a real adjective ("сделай короче").
    private val FILLER_RE = Regex(
        """(^|\s)(uh|um|uhh|umm|ah|er|erm|hmm+|mm+|mhm|э+м+|эм|ээ)(?=[\s.,!?;:]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val DOUBLE_SPACE_RE = Regex(""" {2,}""")
    private val ORPHAN_PUNCT_RE = Regex("""(^\s*[.,;:]\s*)|(\s+[.,;:](?=\s|$))""")

    /** Drop standalone filler words and tidy spacing around them. */
    fun removeFillers(raw: String): String {
        return try {
            var text = raw
            // Repeatedly strip orphans left behind after filler removal.
            repeat(2) {
                text = ORPHAN_PUNCT_RE.replace(text, "")
                text = FILLER_RE.replace(text) { it.groupValues[1] }
            }
            DOUBLE_SPACE_RE.replace(text, " ").trim(' ')
        } catch (_: Throwable) {
            raw
        }
    }

    val COMMANDS = mapOf(
        "new line" to Action.NEW_LINE,
        "next line" to Action.NEW_LINE,
        "new paragraph" to Action.NEW_PARAGRAPH,
        "delete last word" to Action.DELETE_LAST_WORD,
        "delete word" to Action.DELETE_LAST_WORD,
        "clear all" to Action.CLEAR_ALL,
        "clear text" to Action.CLEAR_ALL,
        "send" to Action.SEND,
        "send it" to Action.SEND,
        "новая строка" to Action.NEW_LINE,
        "новый абзац" to Action.NEW_PARAGRAPH,
        "удалить слово" to Action.DELETE_LAST_WORD,
        "стереть всё" to Action.CLEAR_ALL,
        "очистить всё" to Action.CLEAR_ALL,
        "отправить" to Action.SEND
    )

    private val PUNCT_MAP = mapOf(
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "full stop" to ".",
        "period" to ".",
        "comma" to ",",
        "coma" to ",",
        "semicolon" to ";",
        "colon" to ":",
        "вопросительный знак" to "?",
        "восклицательный знак" to "!",
        "точка" to ".",
        "запятая" to ",",
        "двоеточие" to ":",
        "точка с запятой" to ";"
    )

    fun applySpokenPunctuation(raw: String): String {
        return try {
            var text = raw.trim()
            val replacePhrase: (MatchResult) -> CharSequence = { match ->
                val separator = if (match.groupValues[1] == " ") " " else ""
                separator + (PUNCT_MAP[match.groupValues[2].lowercase(Locale.ROOT)] ?: match.groupValues[2])
            }
            text = PUNCT_REGEX.replace(text, replacePhrase)
            text = PERIOD_AT_END_REGEX.replace(text, replacePhrase)
            text.replace(SPACE_BEFORE_PUNCT, "$1")
                .replace(PUNCT_RUN_RE, "$1")
                .replace(COMMA_BEFORE_STRONGER_RE, "$1")
                .replace(REDUNDANT_TRAILING_DOT_RE, "$1")
                .replace(SPACE_AFTER_PUNCT, "$1 ")
        } catch (_: Throwable) {
            raw
        }
    }

    fun process(
        raw: String,
        commandsEnabled: Boolean,
        smartPunctuationEnabled: Boolean,
        needsCapital: Boolean
    ): List<Result> {
        val results = mutableListOf<Result>()
        if (raw.isBlank()) return results

        // 1. Global spoken punctuation replacement
        var text = raw.trim()
        if (smartPunctuationEnabled) {
            try {
                text = applySpokenPunctuation(text)
            } catch (_: Throwable) {}
        }

        // 2. Linear tokenization using StringTokenizer
        val st = StringTokenizer(text, ",;.!?\n", true)
        val currentClause = StringBuilder()
        var currentCapital = needsCapital

        while (st.hasMoreTokens()) {
            val token = st.nextToken()
            val isDelimiter = (token.length == 1 && ",;.!?".contains(token)) || token == "\n"

            if (isDelimiter) {
                val clauseStr = currentClause.toString().trim()
                if (clauseStr.isNotBlank()) {
                    val norm = clauseStr.lowercase(Locale.ROOT)
                    val cmdAction = if (commandsEnabled) COMMANDS[norm] else null
                    if (cmdAction != null) {
                        results.add(Result.Command(cmdAction, norm))
                        if (token in ".!?" || token == "\n") currentCapital = true
                    } else {
                        var finalText = clauseStr
                        if (currentCapital) {
                            finalText = finalText.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                            }
                        }
                        if (token != "\n") {
                            finalText += token
                        }
                        val isTerminal = token in ".!?"
                        results.add(Result.Text(finalText, isTerminal))
                        currentCapital = isTerminal || token == "\n"
                    }
                } else if (token == "\n") {
                    currentCapital = true
                }
                currentClause.clear()
            } else {
                currentClause.append(token)
            }
        }

        val remainingClause = currentClause.toString().trim()
        if (remainingClause.isNotBlank()) {
            val norm = remainingClause.lowercase(Locale.ROOT)
            val cmdAction = if (commandsEnabled) COMMANDS[norm] else null
            if (cmdAction != null) {
                results.add(Result.Command(cmdAction, norm))
            } else {
                var finalText = remainingClause
                if (currentCapital) {
                    finalText = finalText.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    }
                }
                val lastChar = finalText.trimEnd().lastOrNull()
                val isTerminal = lastChar != null && lastChar in ".!?"
                results.add(Result.Text(finalText, isTerminal))
            }
        }

        return results
    }
}
