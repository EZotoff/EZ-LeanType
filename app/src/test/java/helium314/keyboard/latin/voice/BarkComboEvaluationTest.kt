/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evaluation, not contract: feeds Bark 3.0 base/plus/clean transcripts
 * (captured by the 2026-10-25 on-device spike, voice_test/results/bark-spike.md)
 * through the real text stage with the user's vocabulary, so the engine
 * choice reflects only what post-processing cannot repair. Output lines
 * ("BARKCOMBO <variant> <block>: ...") are scored by .harness scripts.
 */
class BarkComboEvaluationTest {

    private val vocab = listOf(
        "OC Beacon", "Veran", "Bruno", "Mauro", "Oren", "Elan",
        "ez-omo-bench", "ez-omo-config", "LiteLLM", "Qwen", "Nestor",
        "agentic", "Fabro", "Comfy UI", "Vercel"
    )

    private fun flags() = VoiceTranscriptPipeline.StageFlags(
        smartPunctuation = true, removeFillers = true,
        aiPostProcessing = false, aiUsable = false, customVocab = vocab
    )

    private fun transcripts(variant: String): Map<String, String> {
        val res = javaClass.classLoader!!.getResourceAsStream("bark-$variant-transcripts.json")
            ?: error("bark-$variant-transcripts.json missing from test resources")
        val text = res.bufferedReader().readText()
        val map = linkedMapOf<String, String>()
        Regex("\"(B\\d)\": \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2]
                .replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return map
    }

    /** Sentence casing approximation of the device chain's per-segment casing. */
    private fun applySentenceCasing(text: String): String {
        val sb = StringBuilder(text)
        var capitalizeNext = true
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (capitalizeNext && c.isLetter()) {
                sb.setCharAt(i, c.uppercaseChar())
                capitalizeNext = false
            } else if (c in ".!?") {
                capitalizeNext = true
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun barkVariantsThroughPipeline() {
        for (variant in listOf("base", "plus", "clean")) {
            transcripts(variant).forEach { (id, raw) ->
                val pre = VoiceTranscriptPipeline.preTransform(raw, isFinal = true, flags = flags())
                // Exact-match command (parity with the device chain; trailing
                // commands are fix #4, unimplemented on both sides).
                val command = VoiceTranscriptPipeline.detectCommand(pre.trim())
                val body = if (command != null) "" else pre
                println("BARKFULL $variant $id: ${applySentenceCasing(body)}")
                assertTrue("$variant/$id blank", pre.isNotBlank())
            }
        }
        // Fix #1 must convert spoken punctuation words for every variant.
        for (variant in listOf("base", "plus", "clean")) {
            val b4 = VoiceTranscriptPipeline.preTransform(
                transcripts(variant).getValue("B4"), isFinal = true, flags = flags()
            )
            assertTrue("$variant B4 simple,", b4.contains("simple,"))
            assertTrue("$variant B4 tests.", b4.contains("tests."))
        }
    }
}
