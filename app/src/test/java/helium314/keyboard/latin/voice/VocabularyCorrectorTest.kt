// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fix #2b: vocabulary forms in the transcript after decoder hotwords.
 * Boosted/spoken variants ("Ez Omo Bench", "Lite L L M") must join into the
 * vocabulary display form; near-variants through one common word ("Easy Omo
 * Bench") and leftover duplicate prefixes ("Light LiteLLM") are cleaned too.
 */
class VocabularyCorrectorTest {

    private val vocab = listOf(
        "OC Beacon", "Veran", "Bruno", "Mauro", "Oren", "Elan",
        "ez-omo-bench", "ez-omo-config", "LiteLLM", "Qwen", "Nestor",
        "agentic", "Fabro", "Comfy UI", "Vercel"
    )

    @Test
    fun exactVariantWordSequencesJoinToDisplayForm() {
        assertEquals(
            "ticket for ez-omo-bench because",
            VocabularyCorrector.correct("ticket for Ez Omo Bench because", vocab)
        )
        assertEquals(
            "runs on ez-omo-config now",
            VocabularyCorrector.correct("runs on Ez Omo Config now", vocab)
        )
    }

    @Test
    fun spelledOutRunsJoinToDisplayForm() {
        assertEquals("I use LiteLLM now", VocabularyCorrector.correct("I use Lite L L M now", vocab))
        assertEquals("the OC Beacon cluster", VocabularyCorrector.correct("the o c beacon cluster", vocab))
    }

    @Test
    fun nearVariantThroughOneCommonWordJoins() {
        // Decoder prefers the common word "Easy" over the "Ez" boost.
        assertEquals(
            "ticket for ez-omo-bench because",
            VocabularyCorrector.correct("ticket for Easy Omo Bench because", vocab)
        )
    }

    @Test
    fun duplicatePrefixBeforeJoinedTermIsDropped() {
        // Hotword boost fired on the tail; the spoken first word lingers.
        assertEquals("I use LiteLLM daily", VocabularyCorrector.correct("I use Light LiteLLM daily", vocab))
    }

    @Test
    fun ordinarySpeechIsUntouched() {
        assertEquals("the bench is easy", VocabularyCorrector.correct("the bench is easy", vocab))
        assertEquals(
            "easy config lost its theme",
            VocabularyCorrector.correct("easy config lost its theme", vocab)
        )
        assertEquals("we open PRs", VocabularyCorrector.correct("we open PRs", vocab))
    }
}
