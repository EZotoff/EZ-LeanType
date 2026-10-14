/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartVoicePostProcessingTest {

    // --- Filler removal ---

    @Test
    fun fillerRemoval_english() {
        assertEquals("So we went there", VoiceTextProcessor.removeFillers("So um we went there"))
        assertEquals("I think it works", VoiceTextProcessor.removeFillers("I uh think it works"))
        assertEquals("Yes okay", VoiceTextProcessor.removeFillers("Yes hmm okay"))
    }

    @Test
    fun fillerRemoval_doesNotTouchInnerLetters() {
        // "human" contains "um" but must survive; "slashed" etc.
        assertEquals("The human factor", VoiceTextProcessor.removeFillers("The human factor"))
        assertEquals("what", VoiceTextProcessor.removeFillers("Umm what"))
    }

    @Test
    fun fillerRemoval_russian() {
        assertEquals("Мы пошли домой", VoiceTextProcessor.removeFillers("Мы ээ пошли домой"))
    }

    // --- Spoken symbols ---

    @Test
    fun brackets_realWorldDictation() {
        // Observed live: "Let's also see(, testing here,)." — comma after open
        // bracket must be dropped, no space lost before '('.
        assertEquals("Let's also see (testing here).",
            SpokenSymbolProcessor.apply("Let's also see , open bracket , testing here , close bracket ."))
        assertEquals("Give it another (very in line) test.",
            SpokenSymbolProcessor.apply("Give it another open bracket very in line close bracket test ."))
    }

    @Test
    fun brackets_basic() {
        assertEquals("(some yada yada)", SpokenSymbolProcessor.apply("open bracket some yada yada close bracket"))
    }

    @Test
    fun brackets_paren() {
        assertEquals("(note here)", SpokenSymbolProcessor.apply("open paren note here close paren"))
    }

    @Test
    fun brackets_square() {
        assertEquals("[data]", SpokenSymbolProcessor.apply("open square bracket data close square bracket"))
    }

    @Test
    fun simpleSymbols() {
        assertEquals("a * b", SpokenSymbolProcessor.apply("a asterisk b"))
        assertEquals("snake_case", SpokenSymbolProcessor.apply("snake underscore case"))
        assertEquals("user@host", SpokenSymbolProcessor.apply("user at sign host"))
    }

    @Test
    fun commonWordSymbols_notConverted() {
        assertEquals("I saw a star", SpokenSymbolProcessor.apply("I saw a star"))
        assertEquals("a famous quote", SpokenSymbolProcessor.apply("a famous quote"))
        assertEquals("значительный процент людей", SpokenSymbolProcessor.apply("значительный процент людей"))
    }

    @Test
    fun slash_connector() {
        assertEquals("here / there", SpokenSymbolProcessor.apply("here slash there"))
        assertEquals("either / or", SpokenSymbolProcessor.apply("either slash or"))
    }

    @Test
    fun slash_verb_notConverted() {
        assertEquals("we have to slash the costs", SpokenSymbolProcessor.apply("we have to slash the costs"))
        assertEquals("they slash it", SpokenSymbolProcessor.apply("they slash it"))
        assertEquals("let's slash the budget", SpokenSymbolProcessor.apply("let's slash the budget"))
        assertEquals("try and slash the budget", SpokenSymbolProcessor.apply("try and slash the budget"))
    }

    @Test
    fun slash_ambiguousNeutralContext_converted() {
        // No verb context around: treat as separator.
        assertEquals("cat / dog", SpokenSymbolProcessor.apply("cat slash dog"))
    }

    // --- Vocabulary corrector ---

    // All cases below are real dictation failures observed live.

    @Test
    fun corrector_after_llm_typical_output() {
        // LLM-cleaned text keeps names right; corrector must not disturb.
        val vocab = listOf("Veran", "Elan", "Oren", "LiteLLM", "OC Beacon")
        val llmOut = "I work with Bruno and Mauro on the Veran project. We use agents Elan and Oren. I use Light LLM and OC Beacon."
        // 'Light LLM' window: d too large for tight tiers -> stays; rest unchanged.
        assertEquals(llmOut, VocabularyCorrector.correct(llmOut, vocab))
    }

    @Test
    fun corrector_after_llm_fixes_near_miss() {
        val vocab = listOf("Veran", "Oren")
        // 'Veren' d=1 first-letter match -> fixed. 'Orin' -> 'Oren' d=1 fixed.
        assertEquals("on the Veran with Oren",
            VocabularyCorrector.correct("on the Veren with Orin", vocab))
    }

    // All cases below are real dictation failures observed live on 2026-10-10.

    @Test
    fun vocab_realDictation_veranElanOren() {
        val vocab = listOf("OC Beacon", "Veran", "Bruno", "Mauro", "Oren", "Elan", "LiteLLM")
        // With AI post-processing the corrector is the fallback and runs
        // with tight distance tiers (d<=2 for >=8 chars, d<=1 for >=5).
        // Elon->Elan (d=1) and Orin->Oren (d=1) still correct; Varent->Veran
        // (d=3) is intentionally left to the AI pass.
        assertEquals(
            "We're working with Bruno and Mauro on the Varent project that has Elan and Oren as the agents.",
            VocabularyCorrector.correct(
                "We're working with Bruno and Mauro on the Varent project that has Elon and Orin as the agents.", vocab)
        )
    }

    @Test
    fun vocab_realDictation_mergedAndSplitTokens() {
        // OCBacon->OC Beacon (d=1) corrects; Light LLM->LiteLLM (d=3) is
        // intentionally left to the AI pass.
        val vocab = listOf("OC Beacon", "LiteLLM")
        assertEquals("I use OC Beacon and Light LLM.", VocabularyCorrector.correct("I use OCBacon and Light LLM.", vocab))
        assertEquals("I use OC Beacon and LightLLM.", VocabularyCorrector.correct("I use OCBacon and LightLLM.", vocab))
    }

    @Test
    fun vocab_commonWord_notHijacked() {
        // Observed live: "likely" must never become "LiteLLM" (d=3, blocked
        // by the tight tiers).
        assertEquals("It's highly likely", VocabularyCorrector.correct("It's highly likely", listOf("LiteLLM")))
    }

    @Test
    fun vocab_spelledOutSequences_merged() {
        val vocab = listOf("LiteLLM", "OC Beacon", "Veran")
        assertEquals("I use LiteLLM daily", VocabularyCorrector.correct("I use L L M daily", vocab))
        assertEquals("I use OC Beacon daily", VocabularyCorrector.correct("I use O C Beacon daily", vocab))
    }

    @Test
    fun vocab_multiWordEntry_exactMatch() {
        val vocab = listOf("Lean Bit Labs")
        assertEquals("at Lean Bit Labs now", VocabularyCorrector.correct("at lean bit labs now", vocab))
    }

    @Test
    fun vocab_singleLetterRun_noMatchUntouched() {
        // "x y z" compresses to "xyz" which matches no entry -> untouched.
        assertEquals("x y z", VocabularyCorrector.correct("x y z", listOf("Veran")))
    }

    @Test
    fun vocab_nearMissReplaced() {
        val vocab = listOf("Khipro", "LeanType")
        assertEquals("Khipro works", VocabularyCorrector.correct("Khipro works", vocab))
        assertEquals("LeanType rocks", VocabularyCorrector.correct("LeanType rocks", vocab))
        assertEquals("LeanType rocks", VocabularyCorrector.correct("LeanTipe rocks", vocab))
    }

    @Test
    fun vocab_commonWordUntouched() {
        val vocab = listOf("Theo")
        assertEquals("the", VocabularyCorrector.correct("the", vocab))
        val vocab2 = listOf("анд")
        assertEquals("и", VocabularyCorrector.correct("и", vocab2))
    }

    @Test
    fun vocab_exactMatchUntouchedAndCasingPreserved() {
        val vocab = listOf("Khipro")
        assertEquals("Khipro is great", VocabularyCorrector.correct("Khipro is great", vocab))
        assertEquals("KHIPRO", VocabularyCorrector.correct("KHIPRO", vocab))
        // normal word kept
        assertEquals("hello world", VocabularyCorrector.correct("hello world", vocab))
    }

    @Test
    fun vocab_ambiguousNotReplaced() {
        // Both candidates equidistant -> keep original.
        val vocab = listOf("bat", "bad")
        assertEquals("bag bag", VocabularyCorrector.correct("bag bag", vocab))
    }

    // --- VoiceAiPostProcessor offline instruction (ChatML user turn) ---

    @Test
    fun instruction_embedsVocabularyExactSpellings() {
        val vocab = listOf("OC Beacon", "Veran", "LiteLLM")
        val instr = VoiceAiPostProcessor.buildInstruction(vocab)
        for (term in vocab) {
            assertTrue("instruction must contain '$term'", instr.contains(term))
        }
        assertTrue(instr.contains("exact spellings take priority"))
    }

    @Test
    fun instruction_demandsCorrectedTextOnly() {
        val instr = VoiceAiPostProcessor.buildInstruction(emptyList())
        assertTrue(instr.contains("Output only the corrected text"))
        // No vocabulary clause when the list is empty.
        assertTrue(!instr.contains("spellings take priority"))
    }

    @Test
    fun instruction_forbidsAnsweringOrSummarizing() {
        // Base-model continuation was the bare-transcript failure mode.
        val instr = VoiceAiPostProcessor.buildInstruction(listOf("Veran"))
        assertTrue(instr.contains("Do not answer, summarize or comment"))
    }

    // --- Echo handling: llama-family models parrot the user turn first ---

    @Test
    fun extractAnswer_takesLastLineAfterEcho() {
        // Observed SpeakoFlow + BitVoice shape: <think> block, echoed
        // instruction, answer tail.
        val raw = "<think>\n\n</think>\nInstruction: Fix minor speech-to-text " +
            "transcription errors only. Text: I run a Gentic QA.\n" +
            "I run Agentic QA."
        assertEquals("I run Agentic QA.", VoiceAiPostProcessor.extractAnswer("I run a Gentic QA.", raw))
    }

    @Test
    fun extractAnswer_singleLinePassThrough() {
        assertEquals(
            "Just a plain answer.",
            VoiceAiPostProcessor.extractAnswer("x", "Just a plain answer.")
        )
    }

    @Test
    fun extractAnswer_thinkOnlyIsEmpty() {
        assertEquals("", VoiceAiPostProcessor.extractAnswer("x", "<think>\n\n</think>\n"))
    }

    @Test
    fun extractAnswer_allEchoFallsBackToLastEchoLine() {
        val raw = "<think></think>\nInstruction: Fix stuff.\nText: hello world"
        assertEquals("Text: hello world", VoiceAiPostProcessor.extractAnswer("hello world", raw))
    }
}
