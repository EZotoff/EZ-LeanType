// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale

/**
 * Post-hoc vocabulary bias for engines without native prompting
 * (Phonon, GigaAM). One unified matcher:
 *
 *  Every vocabulary entry is compressed to its letters/digits only
 *  ("OC Beacon" -> "ocbeacon", "LiteLLM" -> "litellm"). Sliding windows
 *  of 1-3 consecutive tokens in the final text are compressed the same
 *  way ("O C Beacon" -> "ocbeacon", "OCBacon" -> "ocbacon", "Light LLM"
 *  -> "lightllm") and fuzzy-matched (bounded Levenshtein) against the
 *  compressed entries. This uniformly handles merged tokens, split
 *  tokens, spelled-out letters, and near-miss mishearings.
 *
 *  Guards: windows containing common words never match (except an exact
 *  whole-window match), windows shorter than 4 characters never
 *  fuzzy-match, ambiguous candidates leave the text untouched.
 */
object VocabularyCorrector {

    // Very common words that must never be part of a fuzzy match window.
    private val COMMON_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on", "at", "is",
        "it", "as", "be", "by", "for", "with", "this", "that", "not", "are", "was",
        "you", "i", "he", "she", "we", "they", "will", "can", "do", "did", "have",
        "has", "had", "from", "what", "when", "where", "who", "how", "all", "any",
        "one", "two", "no", "so", "up", "out", "about", "into", "over", "than",
        "then", "them", "there", "here", "just", "like", "make", "many", "more",
        "only", "some", "time", "very", "want", "well", "also", "now", "get", "go",
        "my", "me", "us", "him", "her", "its", "our", "your",
        "open", "opens", "opened", "use", "used", "uses", "then", "than", "them",
        "и", "в", "не", "на", "я", "что", "ты", "он", "она", "мы", "они", "это",
        "как", "а", "то", "за", "с", "да", "но", "вы", "у", "же", "если", "бы"
    )

    private const val MAX_WINDOW_TOKENS = 3
    private const val MIN_FUZZY_LEN = 4

    /**
     * Post-LLM vocabulary pass: letter-overlap near-match. A token (or token
     * window) matches a vocabulary entry when >= 75% of the entry's letters
     * appear in order in the candidate (both lowercased, letters only).
     * Catches ASR mangles the LLM cannot know about:
     * "LightLM" -> "LiteLLM" (7/8), "OCBCon" -> "OC Beacon" (6/8).
     * Common words never match (protects "open", "use", ...).
     */
    fun nearMatchVocab(text: String, vocabulary: List<String>): String {
        if (vocabulary.isEmpty() || text.isBlank()) return text
        return try {
            val entries = vocabulary.mapNotNull { e ->
                val c = e.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
                if (c.length >= 4) c to e else null
            }
            if (entries.isEmpty()) return text
            val tokens = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
            val out = mutableListOf<String>()
            var i = 0
            outer@ while (i < tokens.size) {
                // try windows of 2 then 1 tokens (multi-word entries)
                for (size in intArrayOf(2, 1)) {
                    if (i + size > tokens.size) continue
                    val window = tokens.subList(i, i + size)
                    val bare = window.map { stripToken(it) }
                    if (bare.any { it.lowercase(Locale.ROOT) in COMMON_WORDS }) continue
                    val joined = bare.joinToString("").filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
                    if (joined.length < 4) continue
                    val hits = entries.filter { (entry, _) ->
                        val matched = overlapOrdered(joined, entry)
                        // window must not be much longer than the entry
                        // (prevents swallowing trailing words like 'works')
                        matched >= entry.length * 0.70 &&
                            (entry.length - matched) <= 2 &&
                            joined.length <= entry.length + 2
                    }
                    if (hits.size == 1) {
                        val trailing = window.last().takeLastWhile { !it.isLetterOrDigit() }
                        out.add(hits[0].second + trailing)
                        i += size
                        continue@outer
                    }
                }
                out.add(tokens[i])
                i++
            }
            out.joinToString(" ")
        } catch (_: Throwable) {
            text
        }
    }

    /**
     * Count of entry letters matched in candidate in order, allowing skips
     * of entry letters (ASR drops syllables: "OCBCon" vs "OC Beacon" drops
     * 'ea' but keeps o,c,b,c,o,n). Returns matched count; caller checks the
     * ratio AND limits how many letters were dropped (skipped = entry len - matched).
     */
    private fun overlapOrdered(candidate: String, entry: String): Int {
        var ci = 0
        var count = 0
        for (ch in entry) {
            if (ci >= candidate.length) break
            var found = -1
            for (k in ci until candidate.length) {
                if (candidate[k] == ch) { found = k; break }
            }
            if (found >= 0) { ci = found + 1; count++ }
        }
        return count
    }

    fun correct(text: String, vocabulary: List<String>): String {
        if (vocabulary.isEmpty() || text.isBlank()) return text
        return try {
            // Compressed entry -> original casing. Duplicate compressed forms
            // are dropped (ambiguous).
            val compressed = vocabulary.mapNotNull { e ->
                val c = compress(e)
                if (c.length >= 2) c to e else null
            }.let { pairs ->
                val map = HashMap<String, String>()
                for ((c, e) in pairs) {
                    if (c in map && map[c] != e) map.remove(c) else map[c] = e
                }
                map
            }
            if (compressed.isEmpty()) return text

            val tokens = text.split(' ').filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            val isTerm = mutableListOf<Boolean>()
            var i = 0
            while (i < tokens.size) {
                var matched = false
                // Prefer the longest window.
                for (size in minOf(MAX_WINDOW_TOKENS, tokens.size - i) downTo 1) {
                    val window = tokens.subList(i, i + size)
                    val result = matchWindow(window, compressed)
                    if (result != null) {
                        // Extend across a following single-letter run that
                        // belongs to the same term ("Lite L L" + "M"): the
                        // longest-window-first order can stop one short.
                        val resultC = compress(result)
                        var ext = size
                        while (i + ext < tokens.size) {
                            val next = stripToken(tokens[i + ext])
                            val nextC = compress(next)
                            if (next.length != 1 || nextC.isEmpty()) break
                            val windowC = compress(tokens.subList(i, i + ext).joinToString(""))
                            if (!resultC.contains(windowC + nextC)) break
                            ext++
                        }
                        val finalWindow = tokens.subList(i, i + ext)
                        val finalResult = if (ext > size) matchWindow(finalWindow, compressed) ?: result else result
                        out.add(finalResult)
                        isTerm.add(true)
                        i += ext
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    out.add(tokens[i])
                    isTerm.add(false)
                    i++
                }
            }
            dropDuplicatePrefixes(out, isTerm, compressed)
            out.joinToString(" ")
        } catch (_: Throwable) {
            text
        }
    }

    /**
     * Try to match one token window against the compressed vocabulary.
     * Returns the replacement string (with trailing punctuation preserved)
     * or null if no (unambiguous) match.
     */
    private fun matchWindow(window: List<String>, compressed: Map<String, String>): String? {
        val stripped = window.map { stripToken(it) }
        val joined = compress(stripped.joinToString(""))
        if (joined.isEmpty()) return null

        // Exact compressed match always wins (spelled-out, merged, split forms).
        compressed[joined]?.let { return withTrailing(window, it) }

        // Spelled-out runs ("L L M") may match as suffix/prefix of an entry
        // ("LiteLLM") — the engine heard only part of the name. Checked before
        // the MIN_FUZZY_LEN gate: spelled runs are short by nature.
        val spelledOut = window.size >= 2 && stripped.all { it.length == 1 && it[0].isLetter() }
        if (spelledOut && joined.length >= 2) {
            val hits = compressed.keys.filter { it.length > joined.length && (it.endsWith(joined) || it.startsWith(joined)) }
            if (hits.size == 1) return withTrailing(window, compressed[hits[0]]!!)
        }

        // Fuzzy match, only for reasonably long windows and never through
        // common words.
        if (joined.length < MIN_FUZZY_LEN) return null
        if (stripped.any { it.lowercase(Locale.ROOT) in COMMON_WORDS }) return null

        // Distance tier: require the first letter to match — recognition
        // near-misses rarely mangle the onset — and keep distances tight;
        // loose tiers caused real false positives ("likely" -> "LiteLLM").
        // The >=10 tier catches decoders that prefer a common word over a
        // boosted short first word ("Easy Omo Bench" vs ez-omo-bench, d=3).
        val maxDist = when {
            joined.length >= 10 -> 3
            joined.length >= 8 -> 2
            joined.length >= 4 -> 1
            else -> return null
        }
        var bestKey: String? = null
        var bestDist = Int.MAX_VALUE
        for (key in compressed.keys) {
            if (key.first() != joined.first()) continue
            if (kotlin.math.abs(key.length - joined.length) > maxDist) continue
            val d = boundedLevenshtein(joined, key, maxDist)
            if (d in 1..maxDist) {
                if (bestKey != null && d == bestDist) return null // ambiguous
                if (bestKey == null || d < bestDist) { bestKey = key; bestDist = d }
            }
        }
        return bestKey?.let { withTrailing(window, compressed[it]!!) }
    }

    /** Attach the window's trailing sentence punctuation to the replacement. */
    private fun withTrailing(window: List<String>, replacement: String): String {
        val trailing = window.last().takeLastWhile { !it.isLetterOrDigit() }
        // Preserve shout-case only for single-token windows ("KHIPRO" stays
        // all caps); multi-token windows like "Lite LLM" should not shout.
        if (window.size == 1) {
            val core = window.last().dropLast(trailing.length)
            if (core.length > 1 && core.all { it.isUpperCase() }) {
                return replacement.uppercase(Locale.ROOT) + trailing
            }
        }
        return replacement + trailing
    }

    private fun compress(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    /**
     * Drop a token that immediately precedes a matched vocabulary term and
     * is just the term's spoken first word lingering before the boosted
     * form ("Light LiteLLM" -> "LiteLLM"): >=60% of the token's letters must
     * appear in order within the head of the term. Never drops common words
     * or tokens that are themselves vocabulary terms.
     */
    private fun dropDuplicatePrefixes(out: MutableList<String>, isTerm: MutableList<Boolean>, compressed: Map<String, String>) {
        if (out.size < 2) return
        val drop = BooleanArray(out.size)
        for (i in 0 until out.size - 1) {
            if (isTerm[i] || !isTerm[i + 1]) continue
            val tokenC = compress(out[i])
            if (tokenC.length < 3 || tokenC.length > 8) continue
            if (tokenC in compressed.values.map { compress(it) }) continue
            if (out[i].lowercase(Locale.ROOT) in COMMON_WORDS) continue
            val termC = compress(out[i + 1])
            // ordered match: token letters found in order in the head of the
            // term (unmatched token letters are skipped, e.g. "light" ->
            // l,i,*,*,t against "litellm" = 3/5)
            var ti = 0
            var matched = 0
            for (c in tokenC) {
                val limit = minOf(termC.length, tokenC.length + 2)
                var k = ti
                while (k < limit) {
                    if (termC[k] == c) { matched++; ti = k + 1; break }
                    k++
                }
            }
            if (matched >= tokenC.length * 0.6) drop[i] = true
        }
        var w = 0
        for (i in out.indices) {
            if (!drop[i]) {
                out[w] = out[i]
                isTerm[w] = isTerm[i]
                w++
            }
        }
        while (out.size > w) { out.removeAt(out.size - 1); isTerm.removeAt(isTerm.size - 1) }
    }

    private fun stripToken(token: String): String =
        token.trim { !it.isLetterOrDigit() && it != '\'' }

    /** Levenshtein distance with early exit once it exceeds [max]. */
    private fun boundedLevenshtein(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > max) return max + 1
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[m]
    }
}
