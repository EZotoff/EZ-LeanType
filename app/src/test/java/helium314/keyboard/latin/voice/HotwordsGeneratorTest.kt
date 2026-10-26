// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordsGeneratorTest {

    @Test
    fun variants_hyphenAndCamelTermsSplitIntoSpokenWords() {
        assertEquals(listOf("Ez Omo Bench"), HotwordsGenerator.spokenVariants("ez-omo-bench"))
        assertEquals(listOf("Lite L L M"), HotwordsGenerator.spokenVariants("LiteLLM"))
        assertEquals(listOf("Lean Type"), HotwordsGenerator.spokenVariants("LeanType"))
        // already a spoken phrase: no extra variant needed
        assertTrue(HotwordsGenerator.spokenVariants("OC Beacon").isEmpty())
        assertTrue(HotwordsGenerator.spokenVariants("Qwen").isEmpty())
    }

    @Test
    fun hotwordsContent_containsOriginalAndVariants_deduped() {
        val content = HotwordsGenerator.hotwordsContent(
            listOf("ez-omo-bench", "ez-omo-bench", "Elan", "OC Beacon", " ")
        )
        val lines = content.trim().lines()
        assertEquals(listOf("ez-omo-bench", "Ez Omo Bench", "Elan", "OC Beacon"), lines)
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun hotwordsContent_emptyVocabularyIsEmpty() {
        assertEquals("", HotwordsGenerator.hotwordsContent(emptyList()))
    }

    @Test
    fun bpeVocab_synthesizedFromTokensTxt() {
        val tokens = "<unk> 0\n<|nospeech|> 1\n▁the 2\nM 3\nbogus-line\nx abc\n"
        val expected = "<unk> -0.5\n<|nospeech|> -0.5\n▁the -0.5\nM -0.5\n"
        assertEquals(expected, HotwordsGenerator.bpeVocabContent(tokens))
    }
}
