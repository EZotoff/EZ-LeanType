// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Generates the decoder-biasing inputs for sherpa-onnx hotwords (contextual
 * biasing, modified_beam_search):
 *  - hotwords.txt: one phrase per line, display case. BPE models cannot
 *    tokenize hyphenated/camel forms ("ez-omo-bench", "LiteLLM"), so each
 *    vocabulary term also contributes spoken-form variants with the words
 *    spaced out ("Ez Omo Bench", "Lite L L M") — the form the decoder's BPE
 *    pieces can actually match (verified on-device 2026-10-26).
 *  - bpe.vocab: sentencepiece-style "<piece> <score>" list synthesized from
 *    the model's tokens.txt, used by sherpa to tokenize the hotword lines.
 */
object HotwordsGenerator {

    fun hotwordsContent(vocabulary: List<String>): String {
        val lines = linkedSetOf<String>()
        for (term in vocabulary) {
            val t = term.trim()
            if (t.isEmpty()) continue
            lines.add(t)
            spokenVariants(t).forEach { lines.add(it) }
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    /** Spoken-form variants of a vocabulary term, display case, words spaced. */
    fun spokenVariants(term: String): List<String> {
        val words = mutableListOf<String>()
        for (chunk in term.split('-', ' ', '_')) {
            if (chunk.isEmpty()) continue
            words.addAll(camelSplit(chunk))
        }
        if (words.size <= 1) return emptyList() // already a plain spoken word
        val joined = words.joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercaseChar() }
        }
        return if (joined.equals(term.trim(), ignoreCase = true)) emptyList() else listOf(joined)
    }

    /** "LiteLLM" -> ["Lite", "L", "L", "M"]; "OCBeacon" -> ["OC", "Beacon"].
     *  A trailing all-uppercase run splits into single letters (LLM -> L L M,
     *  the form BPE pieces match); a run followed by more word keeps together
     *  (USBPort -> USB Port). No regex lookbehind (Android ICU). */
    private fun camelSplit(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        if (s == s.lowercase() || s == s.uppercase()) {
            return listOf(s) // no case transitions: a plain word or acronym
        }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (c.isUpperCase() && cur.isNotEmpty() && !cur.last().isUpperCase()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        // Terminal all-uppercase chunk -> single letters (LiteLLM -> Lite L L M)
        if (out.size >= 2) {
            val last = out.last()
            if (last.length > 1 && last.all { it.isUpperCase() }) {
                out.removeAt(out.size - 1)
                last.forEach { out.add(it.toString()) }
            }
        }
        return out
    }

    /** "sym id" lines from tokens.txt -> "sym -0.5" bpe.vocab lines. */
    fun bpeVocabContent(tokensTxt: String): String {
        val sb = StringBuilder()
        for (line in tokensTxt.lineSequence()) {
            val idx = line.lastIndexOf(' ')
            if (idx <= 0) continue
            val sym = line.substring(0, idx)
            val id = line.substring(idx + 1)
            if (id.toIntOrNull() == null || sym.isEmpty()) continue
            sb.append(sym).append(" -0.5\n")
        }
        return sb.toString()
    }
}
