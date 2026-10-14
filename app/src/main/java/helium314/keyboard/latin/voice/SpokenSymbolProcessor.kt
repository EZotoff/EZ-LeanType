// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale

/**
 * Context-aware conversion of spoken symbol names into the actual symbols.
 * Unlike a dumb word replacement, ambiguous cases (e.g. "slash" used as a
 * verb: "we have to slash the costs") are detected via surrounding-word
 * context and left untouched.
 *
 * Regex constraint: no lookbehind — Android's ICU regex engine fails to
 * compile it on some builds (see VoiceTextProcessor note).
 */
object SpokenSymbolProcessor {

    // Spoken -> symbol for tokens that are safe to replace whenever they
    // appear as standalone words (bracket markers are handled by regex so
    // open/close pairs get proper spacing).
    private data class SymbolRule(val spoken: List<String>, val symbol: String)

    private val PAIRED_RULES = listOf(
        // English brackets / braces / parens / quotes
        SymbolRule(listOf("open bracket", "open paren", "open parenthesis"), "("),
        SymbolRule(listOf("close bracket", "close paren", "close parenthesis"), ")"),
        SymbolRule(listOf("open square bracket", "open square"), "["),
        SymbolRule(listOf("close square bracket", "close square"), "]"),
        SymbolRule(listOf("open curly bracket", "open brace", "open curly"), "{"),
        SymbolRule(listOf("close curly bracket", "close brace", "close curly"), "}"),
        SymbolRule(listOf("open quote"), "\""),
        SymbolRule(listOf("close quote", "unquote", "end quote"), "\""),
        // Russian
        SymbolRule(listOf("открой скобку", "открыть скобку", "открывающая скобка"), "("),
        SymbolRule(listOf("закрой скобку", "закрыть скобку", "закрывающая скобка"), ")")
    )

    // Simple symbols replaceable as standalone tokens. Pattern builds from
    // the spoken phrase; matched case-insensitively as a whole word group.
    private val SIMPLE_RULES = listOf(
        "asterisk" to "*",
        "underscore" to "_",
        "hyphen" to "-",
        "dash" to "-",
        "at sign" to "@",
        "at symbol" to "@",
        "percent sign" to "%",
        "ampersand" to "&",
        "and sign" to "&",
        "знак процента" to "%"
    )

    // Slash is ambiguous: also an English verb ("slash the costs").
    // Convert only when NOT in a verb-like context:
    //  - preceded by "to", "we", "they", "you", "i", "don't", "let's", "and"
    //  - followed by "the", "a", "an", "our", "my", "their", "it"
    private val SLASH_VERB_BEFORE = setOf(
        "to", "we", "they", "you", "i", "we'll", "they'll", "you'll",
        "don't", "lets", "let's", "and", "or", "then", "just", "can", "could", "will", "would"
    )
    private val SLASH_VERB_AFTER = setOf(
        "the", "a", "an", "our", "my", "their", "its", "it", "his", "her", "your", "this", "that", "all"
    )

    // Phrases where "slash" is clearly the connector meaning "or" / a separator.
    // Note: "and"/"or" intentionally NOT here — they also precede the verb form
    // ("try and slash the budget"); verb-context must be allowed to veto them.
    private val SLASH_CONNECTOR_BEFORE = setOf("here", "there", "either", "neither", "both")

    fun apply(raw: String): String {
        return try {
            var text = raw
            // Longest-first so "open square bracket" beats "open bracket".
            for (rule in PAIRED_RULES) {
                for (spoken in rule.spoken.sortedByDescending { it.length }) {
                    text = replaceStandalone(text, spoken, rule.symbol)
                }
            }
            for ((spoken, symbol) in SIMPLE_RULES.sortedByDescending { it.first.length }) {
                text = replaceStandalone(text, spoken, symbol)
            }
            text = processSlash(text)
            tidySpacing(text)
        } catch (_: Throwable) {
            raw
        }
    }

    /** Replace whole-phrase occurrences (case-insensitive) with the symbol. */
    private fun replaceStandalone(text: String, spoken: String, symbol: String): String {
        return rebuildWithSymbol(text, spoken, symbol)
    }

    /**
     * Token-based replacement: split on spaces, compare lowercase tokens
     * (multi-word spoken phrases consumed greedily), keep symbol spacing sane.
     */
    private fun rebuildWithSymbol(text: String, spoken: String, symbol: String): String {
        val pattern = Regex(Regex.escape(spoken), RegexOption.IGNORE_CASE)
        // Fast path: nothing to do.
        if (!pattern.containsMatchIn(text)) return text
        val tokens = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val spokenWords = spoken.lowercase(Locale.ROOT).split(' ')
        var i = 0
        var changed = false
        while (i <= tokens.size - spokenWords.size) {
            val window = tokens.subList(i, i + spokenWords.size)
            val matches = window.map { stripPunct(it).lowercase(Locale.ROOT) } == spokenWords
            if (matches) {
                val last = window.last()
                val lastCh = last.lastOrNull()
                val trailing = if (last.length > 1 && lastCh != null && lastCh in ",.;:!?") lastCh.toString() else ""
                tokens.subList(i, i + spokenWords.size).clear()
                tokens.add(i, symbol + trailing)
                changed = true
                i += 1
            } else {
                i += 1
            }
        }
        return if (changed) tokens.joinToString(" ") else text
    }

    private fun stripPunct(token: String): String = token.trim { !it.isLetterOrDigit() && it != '\'' }

    /** Context-aware "slash" handling. */
    private fun processSlash(text: String): String {
        if (!text.contains("slash", ignoreCase = true)) return text
        val tokens = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        var i = 0
        while (i < tokens.size) {
            val bare = stripPunct(tokens[i]).lowercase(Locale.ROOT)
            if (bare != "slash") { i += 1; continue }
            val prev = if (i > 0) stripPunct(tokens[i - 1]).lowercase(Locale.ROOT) else null
            val next = if (i < tokens.size - 1) stripPunct(tokens[i + 1]).lowercase(Locale.ROOT) else null

            val verbBefore = prev != null && (prev in SLASH_VERB_BEFORE || prev.endsWith("'ll") || prev.endsWith("'d"))
            val verbAfter = next != null && next in SLASH_VERB_AFTER
            val connector = (prev != null && prev in SLASH_CONNECTOR_BEFORE) ||
                (next != null && next in SLASH_CONNECTOR_BEFORE)

            val convert = !verbBefore && !verbAfter || (connector && !verbAfter)
            if (convert) {
                // Attach slash to neighbors: "here / there" style is more
                // readable; keep spaces around it.
                val raw = tokens[i]
                val rawCh = raw.lastOrNull()
                val trailing = if (raw.length > 5 && rawCh != null && rawCh in ",.;:!?") rawCh.toString() else ""
                tokens[i] = "/" + trailing
            }
            i += 1
        }
        return tokens.joinToString(" ")
    }

    /** Normalize spacing around inserted symbols: no space after '(' etc., no double spaces. */
    private fun tidySpacing(text: String): String {
        var t = text
        // Bracket contents glued to the bracket; opener keeps a separating
        // space before it: "another (test)", not "another(test)".
        t = Regex("""\s+\(""").replace(t, " (")
        t = Regex("""\(\s+""").replace(t, "(")
        t = Regex("""\s+\[""").replace(t, " [")
        t = Regex("""\[\s+""").replace(t, "[")
        t = Regex("""\s+\{""").replace(t, " {")
        t = Regex("""\{\s+""").replace(t, "{")
        t = Regex("""\s+\)""").replace(t, ")")
        t = Regex("""\s+\]""").replace(t, "]")
        t = Regex("""\s+\}""").replace(t, "}")
        // Junk punctuation hugging inserted brackets: "(, text ,)" -> "(text)".
        t = Regex("""\(\s*[,;:]+\s*""").replace(t, "(")
        t = Regex("""\s*[,;:]+\s*\)""").replace(t, ")")
        t = Regex("""\[\s*[,;:]+\s*""").replace(t, "[")
        t = Regex("""\s*[,;:]+\s*\]""").replace(t, "]")
        t = Regex("""\{\s*[,;:]+\s*""").replace(t, "{")
        t = Regex("""\s*[,;:]+\s*\}""").replace(t, "}")
        // Comma/semicolon/colon directly before an opener from spoken-punct
        // leftovers ("see , (testing") loses its purpose: drop it.
        t = Regex("""[,;:]\s*\(""").replace(t, " (")
        t = Regex("""[,;:]\s*\[""").replace(t, " [")
        t = Regex("""[,;:]\s*\{""").replace(t, " {")
        // Sentence punctuation after a closer glues: ") ." -> ")."
        t = Regex("""([)\]}])\s+([.,;:!?])""").replace(t, "$1$2")
        // After a closer that ends the segment (end or followed by another
        // token): ensure a separating space before the next word, but never
        // before sentence punctuation. "another(test) word" stays glued.
        t = Regex("""(\)|\]|\})(?=[\w"(])""").replace(t) { "${it.groupValues[1]} " }
        // Glue symbols that bind tight: underscore, at, percent, ampersand.
        t = Regex("""([\w])\s+_""").replace(t, "$1_")
        t = Regex("""_\s+([\w])""").replace(t, "_$1")
        t = Regex("""([\w])\s+@""").replace(t, "$1@")
        t = Regex("""@\s+([\w])""").replace(t, "@$1")
        t = Regex("""([\d\w])\s+%""").replace(t, "$1%")
        t = Regex("""([\w])\s+&""").replace(t, "$1&")
        t = Regex("""&\s+([\w])""").replace(t, "&$1")
        // Space before sentence-final punctuation (leftover "word .") glues.
        t = Regex("""(\S)\s+([.,;:!?])(?=\s|$)""").replace(t, "$1$2")
        t = Regex(""" {2,}""").replace(t, " ")
        return t.trim(' ')
    }
}
