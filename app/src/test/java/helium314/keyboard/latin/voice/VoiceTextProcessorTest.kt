/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceTextProcessorTest {

    @Test
    fun testApplySpokenPunctuation_english() {
        val input = "Hello comma how are you question mark"
        val output = VoiceTextProcessor.applySpokenPunctuation(input)
        assertEquals("Hello, how are you?", output)
    }

    @Test
    fun testApplySpokenPunctuation_russian() {
        val input = "Привет запятая как дела вопросительный знак"
        val output = VoiceTextProcessor.applySpokenPunctuation(input)
        assertEquals("Привет, как дела?", output)
    }

    @Test
    fun testApplySpokenPunctuation_russianPeriodAndExclamation() {
        val input = "Отлично точка Всё работает восклицательный знак"
        val output = VoiceTextProcessor.applySpokenPunctuation(input)
        assertEquals("Отлично. Всё работает!", output)
    }

    // --- Spoken punctuation vs ASR's own punctuation (corpus block B4) ---

    @Test
    fun punct_phrase_with_asr_punctuation_after() {
        // ASR appends its own punctuation right after the spoken phrase;
        // the phrase must still convert and the marks must deduplicate.
        assertEquals("never skip the tests.",
            VoiceTextProcessor.applySpokenPunctuation("never skip the tests period."))
        assertEquals("Can we ship on Friday?",
            VoiceTextProcessor.applySpokenPunctuation("Can we ship on Friday question mark."))
        assertEquals("Wait for it!",
            VoiceTextProcessor.applySpokenPunctuation("Wait for it, exclamation Mark."))
        assertEquals("and patience;",
            VoiceTextProcessor.applySpokenPunctuation("and patience semicolon."))
    }

    @Test
    fun punct_asr_misspells_comma_as_coma() {
        // Phonon consistently hears "comma" as "coma" (corpus B4).
        assertEquals("The first rule is simple, never",
            VoiceTextProcessor.applySpokenPunctuation("The first rule is simple, coma, never"))
        assertEquals("a charger, and patience",
            VoiceTextProcessor.applySpokenPunctuation("a charger coma and patience"))
    }

    @Test
    fun punct_period_stays_word_mid_sentence() {
        // Corpus trap: "period" as a real word must survive when a regular
        // word or non-terminal punctuation follows it.
        assertEquals("The billing period ends Friday.",
            VoiceTextProcessor.applySpokenPunctuation("The billing period ends Friday."))
        assertEquals("Grace period, then renewal.",
            VoiceTextProcessor.applySpokenPunctuation("Grace period, then renewal."))
    }

    @Test
    fun punct_period_converts_at_clause_end() {
        assertEquals("Skip the tests.",
            VoiceTextProcessor.applySpokenPunctuation("Skip the tests period"))
        assertEquals("Skip the tests.",
            VoiceTextProcessor.applySpokenPunctuation("Skip the tests period."))
    }

    @Test
    fun punct_duplicate_marks_collapse() {
        // ASR punctuation + converted spoken punctuation must not double up.
        assertEquals("Ready?", VoiceTextProcessor.applySpokenPunctuation("Ready question mark?"))
        assertEquals("Done.", VoiceTextProcessor.applySpokenPunctuation("Done period."))
        assertEquals("one, two", VoiceTextProcessor.applySpokenPunctuation("one, comma, two"))
        assertEquals("patience;", VoiceTextProcessor.applySpokenPunctuation("patience, semicolon."))
    }

    @Test
    fun testCommandsMapping() {
        assertEquals(VoiceTextProcessor.Action.NEW_LINE, VoiceTextProcessor.COMMANDS["новая строка"])
        assertEquals(VoiceTextProcessor.Action.NEW_PARAGRAPH, VoiceTextProcessor.COMMANDS["новый абзац"])
        assertEquals(VoiceTextProcessor.Action.DELETE_LAST_WORD, VoiceTextProcessor.COMMANDS["удалить слово"])
        assertEquals(VoiceTextProcessor.Action.CLEAR_ALL, VoiceTextProcessor.COMMANDS["очистить всё"])
        assertEquals(VoiceTextProcessor.Action.SEND, VoiceTextProcessor.COMMANDS["отправить"])
        assertEquals(VoiceTextProcessor.Action.NEW_LINE, VoiceTextProcessor.COMMANDS["new line"])
        assertEquals(VoiceTextProcessor.Action.SEND, VoiceTextProcessor.COMMANDS["send"])
    }
}
