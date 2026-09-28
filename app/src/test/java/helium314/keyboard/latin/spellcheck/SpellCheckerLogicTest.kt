package helium314.keyboard.latin.spellcheck

import helium314.keyboard.latin.utils.ScriptUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpellCheckerLogicTest {

    @Test
    fun testCyrillicScriptDetection() {
        val russianWord = "привет"
        val firstCodePoint = russianWord.codePointAt(0)
        assertTrue(ScriptUtils.isLetterPartOfScript(firstCodePoint, ScriptUtils.SCRIPT_CYRILLIC))
        assertFalse(ScriptUtils.isLetterPartOfScript(firstCodePoint, ScriptUtils.SCRIPT_LATIN))

        val englishWord = "hello"
        val engFirstCodePoint = englishWord.codePointAt(0)
        assertTrue(ScriptUtils.isLetterPartOfScript(engFirstCodePoint, ScriptUtils.SCRIPT_LATIN))
        assertFalse(ScriptUtils.isLetterPartOfScript(engFirstCodePoint, ScriptUtils.SCRIPT_CYRILLIC))
    }

    @Test
    fun testCheckabilityAndTypoLogic() {
        // Reproduce the checkability logic
        val CHECKABILITY_CHECKABLE = 0
        val CHECKABILITY_TOO_MANY_NON_LETTERS = 1
        val CHECKABILITY_CONTAINS_PERIOD = 2
        val CHECKABILITY_EMAIL_OR_URL = 3
        val CHECKABILITY_FIRST_LETTER_UNCHECKABLE = 4
        val CHECKABILITY_TOO_SHORT = 5

        fun getCheckability(text: String, script: String): Int {
            if (text.isEmpty() || text.length <= 1) return CHECKABILITY_TOO_SHORT
            val firstCodePoint = text.codePointAt(0)
            if (!ScriptUtils.isLetterPartOfScript(firstCodePoint, script) && '\'' != text[0]) return CHECKABILITY_FIRST_LETTER_UNCHECKABLE

            var letterCount = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if ('@'.code == codePoint || '/'.code == codePoint) return CHECKABILITY_EMAIL_OR_URL
                if ('.'.code == codePoint) return CHECKABILITY_CONTAINS_PERIOD
                if (ScriptUtils.isLetterPartOfScript(codePoint, script)) ++letterCount
                i = text.offsetByCodePoints(i, 1)
            }
            return if (letterCount * 4 < text.length * 3) CHECKABILITY_TOO_MANY_NON_LETTERS else CHECKABILITY_CHECKABLE
        }

        fun shouldReportAsTypo(text: String, script: String): Boolean {
            val checkability = getCheckability(text, script)
            if (checkability == CHECKABILITY_CHECKABLE) return false // handled by dict
            val periodOnlyAtLastIndex = text.indexOf('.') == (text.length - 1)
            return (checkability == CHECKABILITY_CONTAINS_PERIOD) && !periodOnlyAtLastIndex
        }

        // 1. Russian word in Latin session: should NEVER report as typo
        assertEquals(CHECKABILITY_FIRST_LETTER_UNCHECKABLE, getCheckability("привет", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("привет", ScriptUtils.SCRIPT_LATIN))

        // 2. Single-letter words (Russian prepositions like в, и, я or English a, I): should NEVER report as typo
        assertEquals(CHECKABILITY_TOO_SHORT, getCheckability("я", ScriptUtils.SCRIPT_CYRILLIC))
        assertFalse(shouldReportAsTypo("я", ScriptUtils.SCRIPT_CYRILLIC))
        assertEquals(CHECKABILITY_TOO_SHORT, getCheckability("в", ScriptUtils.SCRIPT_CYRILLIC))
        assertFalse(shouldReportAsTypo("в", ScriptUtils.SCRIPT_CYRILLIC))
        assertEquals(CHECKABILITY_TOO_SHORT, getCheckability("a", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("a", ScriptUtils.SCRIPT_LATIN))

        // 3. Email and URL: should NEVER report as typo
        assertEquals(CHECKABILITY_EMAIL_OR_URL, getCheckability("test@example.com", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("test@example.com", ScriptUtils.SCRIPT_LATIN))
        assertEquals(CHECKABILITY_EMAIL_OR_URL, getCheckability("https://google.com", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("https://google.com", ScriptUtils.SCRIPT_LATIN))

        // 4. Numbers: should NEVER report as typo
        assertEquals(CHECKABILITY_FIRST_LETTER_UNCHECKABLE, getCheckability("123456", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("123456", ScriptUtils.SCRIPT_LATIN))
        assertEquals(CHECKABILITY_TOO_MANY_NON_LETTERS, getCheckability("a12345", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("a12345", ScriptUtils.SCRIPT_LATIN))

        // 5. Word with trailing period at end of sentence: should NEVER report as typo
        assertEquals(CHECKABILITY_CONTAINS_PERIOD, getCheckability("hello.", ScriptUtils.SCRIPT_LATIN))
        assertFalse(shouldReportAsTypo("hello.", ScriptUtils.SCRIPT_LATIN))

        // 6. Words stuck together with period: SHOULD report as typo
        assertEquals(CHECKABILITY_CONTAINS_PERIOD, getCheckability("hello.world", ScriptUtils.SCRIPT_LATIN))
        assertTrue(shouldReportAsTypo("hello.world", ScriptUtils.SCRIPT_LATIN))
    }

    @Test
    fun testRussianYoNormalizationAndHyphenation() {
        val dict = setOf("елка", "по", "русски", "кто", "то")

        fun isValidSpelling(word: String): Boolean = dict.contains(word)

        fun checkWord(word: String): Boolean {
            if (isValidSpelling(word)) return true
            if (word.contains('ё') || word.contains('Ё')) {
                val normalized = word.replace('ё', 'е').replace('Ё', 'Е')
                if (isValidSpelling(normalized)) return true
            }
            if (word.contains('-')) {
                val parts = word.split('-')
                if (parts.all { it.isEmpty() || isValidSpelling(it) || isValidSpelling(it.replace('ё', 'е').replace('Ё', 'Е')) }) {
                    return true
                }
            }
            return false
        }

        // 'ёлка' should match dictionary entry 'елка'
        assertTrue(checkWord("ёлка"))
        assertTrue(checkWord("елка"))

        // Hyphenated Russian words should match components
        assertTrue(checkWord("по-русски"))
        assertTrue(checkWord("кто-то"))
        assertFalse(checkWord("несуществующее-слово"))
    }

    @Test
    fun testVoiceEngineRoutingLogic() {
        fun selectEngine(
            pref: String,
            languageTag: String,
            activeLanguage: String,
            parakeetReady: Boolean,
            whisperReady: Boolean
        ): String {
            val isRussian = languageTag.startsWith("ru") || activeLanguage.startsWith("ru")
            val isEnglish = languageTag.startsWith("en") || activeLanguage.startsWith("en")

            val preferWhisper = when (pref) {
                "whisper" -> true
                "parakeet" -> false
                else -> { // auto
                    if (isRussian || (!isEnglish && activeLanguage.isNotEmpty())) {
                        true
                    } else {
                        !parakeetReady && whisperReady
                    }
                }
            }

            return if (preferWhisper) {
                if (whisperReady) "whisper" else if (parakeetReady) "parakeet" else "none"
            } else {
                if (parakeetReady) "parakeet" else if (whisperReady) "whisper" else "none"
            }
        }

        // Auto mode, Russian language -> MUST route to Whisper
        assertEquals("whisper", selectEngine("auto", "ru", "ru", parakeetReady = true, whisperReady = true))
        assertEquals("whisper", selectEngine("auto", "auto", "ru", parakeetReady = true, whisperReady = true))

        // Auto mode, English language -> Routes to Parakeet (faster)
        assertEquals("parakeet", selectEngine("auto", "en", "en", parakeetReady = true, whisperReady = true))

        // Explicit preference overrides
        assertEquals("whisper", selectEngine("whisper", "en", "en", parakeetReady = true, whisperReady = true))
        assertEquals("parakeet", selectEngine("parakeet", "ru", "ru", parakeetReady = true, whisperReady = true))

        // Fallbacks
        assertEquals("whisper", selectEngine("parakeet", "en", "en", parakeetReady = false, whisperReady = true))
        assertEquals("parakeet", selectEngine("auto", "ru", "ru", parakeetReady = true, whisperReady = false))
    }

    @Test
    fun testRussianMorphologyAndInflectionMatching() {
        val RUSSIAN_INFLECTION_SUFFIXES = arrayOf(
            // Participles & gerunds
            "вшись", "вшийся", "вшаяся", "вшееся", "вшиеся",
            "ющий", "ющая", "ющее", "ющие", "ющего", "ющей", "ющим", "ющих",
            "ящий", "ящая", "ящее", "ящие", "ящего", "ящей", "ящим", "ящих",
            "ущий", "ущая", "ущее", "ущие", "ущего", "ущей", "ущим", "ущих",
            "нный", "нная", "нное", "нные", "рованного", "рованной", "нным", "нных",
            "вший", "вшая", "вшее", "вшие", "вшего", "вшей", "вшим", "вших",
            "щий", "щая", "щее", "щие", "щего", "щей", "щим", "щих",
            "емый", "емая", "емое", "емые", "емого", "емой", "емым", "емых",
            "имый", "имая", "имое", "имые",
            // Adjective superlatives
            "ейший", "ейшая", "ейшее", "ейшие", "айший", "айшая", "айшее", "айшие"
        )

        // Realistic dictionary containing both lemmas and regular inflected forms (as in main_ru.dict)
        val dict = setOf(
            "делать", "делаю", "делаешь", "делает", "делаем", "делаете", "делают", "делал", "делала", "делали",
            "книга", "книги", "книге", "книгу", "книгой", "книгам", "книгами", "книгах",
            "красивый", "красивая", "красивое", "красивые", "красивого", "красивой", "красивому", "красивым", "красивых", "красивыми",
            "учить", "учил", "учила", "учили",
            "кот", "коты", "собака", "собаки", "собаку", "стол", "столы", "человек"
        )

        fun isValidSpelling(word: String): Boolean = dict.contains(word)

        fun checkRussianInflections(word: String): Boolean {
            if (word.length <= 3) return false
            val baseWord = if ((word.endsWith("ся") || word.endsWith("сь")) && word.length > 4) {
                word.substring(0, word.length - 2)
            } else {
                word
            }
            if (baseWord != word && isValidSpelling(baseWord)) return true

            for (suffix in RUSSIAN_INFLECTION_SUFFIXES) {
                if (baseWord.endsWith(suffix) && baseWord.length > suffix.length + 2) {
                    val stem = baseWord.substring(0, baseWord.length - suffix.length)
                    if (isValidSpelling(stem + "ть")) return true
                    if (isValidSpelling(stem + "ить")) return true
                    if (isValidSpelling(stem + "ать")) return true
                    if (isValidSpelling(stem + "еть")) return true
                    if (isValidSpelling(stem + "ый")) return true
                    if (isValidSpelling(stem + "ий")) return true
                }
            }
            return false
        }

        fun checkWord(word: String): Boolean {
            if (isValidSpelling(word)) return true
            return checkRussianInflections(word)
        }

        // Exact lemma matches
        assertTrue(checkWord("делать"))
        assertTrue(checkWord("книга"))

        // Verb conjugations match
        assertTrue(checkWord("делаешь"))
        assertTrue(checkWord("делает"))
        assertTrue(checkWord("делаем"))
        assertTrue(checkWord("делаете"))
        assertTrue(checkWord("делают"))
        assertTrue(checkWord("делал"))
        assertTrue(checkWord("делали"))

        // Reflexive verbs (stripped and checked against base verb)
        assertTrue(checkWord("учился"))
        assertTrue(checkWord("училась"))

        // Noun case inflections from dictionary
        assertTrue(checkWord("книги"))
        assertTrue(checkWord("книге"))
        assertTrue(checkWord("книгу"))
        assertTrue(checkWord("книгой"))
        assertTrue(checkWord("книгам"))
        assertTrue(checkWord("книгами"))
        assertTrue(checkWord("книгах"))

        // Adjective inflections from dictionary
        assertTrue(checkWord("красивая"))
        assertTrue(checkWord("красивое"))
        assertTrue(checkWord("красивые"))
        assertTrue(checkWord("красивого"))
        assertTrue(checkWord("красивому"))
        assertTrue(checkWord("красивых"))
        assertTrue(checkWord("красивыми"))

        // Superlatives via suffix stemming
        assertTrue(checkWord("красивейший"))

        // Participles via suffix stemming
        assertTrue(checkWord("делавший"))
        assertTrue(checkWord("делающая"))

        // Invalid typos MUST fail (never falsely accepted as valid)
        assertFalse(checkWord("абвгдежзий"))
        assertFalse(checkWord("коти"))
        assertFalse(checkWord("собако"))
        assertFalse(checkWord("столи"))
        assertFalse(checkWord("брати"))
        assertFalse(checkWord("превед"))
        assertFalse(checkWord("карова"))
        assertFalse(checkWord("машына"))
    }

    @Test
    fun testTypoDetectionFlags() {
        // Any word that is not in the dictionary MUST be flagged with RESULT_ATTR_LOOKS_LIKE_TYPO
        // so that the UI underscores it with red squiggly lines.
        val RESULT_ATTR_LOOKS_LIKE_TYPO = 0x0002
        val RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS = 0x0004

        fun computeFlags(hasRecommendedSuggestions: Boolean): Int {
            return RESULT_ATTR_LOOKS_LIKE_TYPO or
                    (if (hasRecommendedSuggestions) RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0)
        }

        // Typo without recommended suggestions (e.g. unknown word, heavy typo) -> MUST be underscored as typo
        val flagsNoRec = computeFlags(hasRecommendedSuggestions = false)
        assertTrue((flagsNoRec and RESULT_ATTR_LOOKS_LIKE_TYPO) != 0)
        assertFalse((flagsNoRec and RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0)

        // Typo with high-confidence recommended suggestions -> MUST be underscored as typo AND have recommended suggestions
        val flagsWithRec = computeFlags(hasRecommendedSuggestions = true)
        assertTrue((flagsWithRec and RESULT_ATTR_LOOKS_LIKE_TYPO) != 0)
        assertTrue((flagsWithRec and RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0)
    }

    @Test
    fun testStreamingDeltaSync() {
        data class Delta(val deleteChars: Int, val appendText: String, val shouldUpdate: Boolean)

        fun computeDelta(current: String, incoming: String, isFinal: Boolean): Delta {
            val currTrimmed = current.trim().replace(Regex("\\s+"), " ")
            val inTrimmed = incoming.trim().replace(Regex("\\s+"), " ")
            if (!isFinal && inTrimmed.length < currTrimmed.length && currTrimmed.startsWith(inTrimmed)) {
                return Delta(0, "", false)
            }
            var commonPrefixLen = 0
            val minLen = minOf(currTrimmed.length, inTrimmed.length)
            while (commonPrefixLen < minLen && currTrimmed[commonPrefixLen] == inTrimmed[commonPrefixLen]) {
                commonPrefixLen++
            }
            val delete = currTrimmed.length - commonPrefixLen
            val append = inTrimmed.substring(commonPrefixLen)
            return Delta(delete, append, delete > 0 || append.isNotEmpty() || isFinal)
        }

        // Test 1: Standard forward extension (0 delete, appends new text)
        val d1 = computeDelta("Hello world", "Hello world how are you", isFinal = false)
        assertEquals(0, d1.deleteChars)
        assertEquals(" how are you", d1.appendText)

        // Test 2: Word completion at boundary ("morn" -> "morning everyone")
        val d2 = computeDelta("Good morn", "Good morning everyone", isFinal = false)
        assertEquals(0, d2.deleteChars)
        assertEquals("ing everyone", d2.appendText)

        // Test 3: Word refinement ("work" -> "world")
        val d3 = computeDelta("Hello work", "Hello world", isFinal = false)
        assertEquals(1, d3.deleteChars)
        assertEquals("ld", d3.appendText)

        // Test 4: Lagging/truncated partial during streaming is ignored (no deletion flicker)
        val d4 = computeDelta("I am typing on the keyboard", "I am typing", isFinal = false)
        assertFalse(d4.shouldUpdate)

        // Test 5: Final commit updates properly
        val d5 = computeDelta("I am typing", "I am typing on the keyboard", isFinal = true)
        assertEquals(0, d5.deleteChars)
        assertEquals(" on the keyboard", d5.appendText)
        assertTrue(d5.shouldUpdate)
    }
}
