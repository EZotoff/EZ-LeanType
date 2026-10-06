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
    private val PUNCT_REGEX = Regex(
        """( |^)(question mark|exclamation mark|exclamation point|full stop|period|comma|semicolon|colon|вопросительный знак|восклицательный знак|точка с запятой|точка|запятая|двоеточие)(?= |$)""",
        RegexOption.IGNORE_CASE
    )
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,;.!?])""")
    private val SPACE_AFTER_PUNCT = Regex("""([,;.!?])(?=\w)""")

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
            text = PUNCT_REGEX.replace(text) { match ->
                val separator = if (match.groupValues[1] == " ") " " else ""
                separator + (PUNCT_MAP[match.groupValues[2].lowercase(Locale.ROOT)] ?: match.groupValues[2])
            }
            text.replace(SPACE_BEFORE_PUNCT, "$1").replace(SPACE_AFTER_PUNCT, "$1 ")
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
                text = PUNCT_REGEX.replace(text) { match ->
                    PUNCT_MAP[match.value.lowercase(Locale.ROOT)] ?: match.value
                }
                text = text.replace(SPACE_BEFORE_PUNCT, "$1").replace(SPACE_AFTER_PUNCT, "$1 ")
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
