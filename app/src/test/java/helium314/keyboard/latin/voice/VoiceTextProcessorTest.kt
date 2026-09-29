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
