// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.UserDictionaryUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PersonalDictionaryLogicTest {

    @Test
    fun testIsCyrillicWord() {
        assertTrue(UserDictionaryUtils.isCyrillicWord("борщ"))
        assertTrue(UserDictionaryUtils.isCyrillicWord("Привет"))
        assertTrue(UserDictionaryUtils.isCyrillicWord("ёлка"))
        assertTrue(UserDictionaryUtils.isCyrillicWord("по-русски"))

        assertFalse(UserDictionaryUtils.isCyrillicWord("hello"))
        assertFalse(UserDictionaryUtils.isCyrillicWord("world"))
        assertFalse(UserDictionaryUtils.isCyrillicWord("12345"))
        assertFalse(UserDictionaryUtils.isCyrillicWord("!@#$%"))
        assertFalse(UserDictionaryUtils.isCyrillicWord(""))
    }

    @Test
    fun testRussianLocaleCollapsing() {
        val sortedLocales = sortedSetOf<Locale>(compareBy { it.toLanguageTag().lowercase() })
        // Subtype adds "ru"
        sortedLocales.add(Locale("ru"))
        // Subtype adds "en_US"
        sortedLocales.add(Locale("en", "US"))

        // System locales list contains "ru_RU" and "en_US"
        val systemLocales = listOf(Locale("ru", "RU"), Locale("en", "US"), Locale("fr", "FR"))

        for (sysLocale in systemLocales) {
            val alreadyCovered = sortedLocales.any { existing ->
                existing.language == sysLocale.language && (
                    existing.language == "ru" ||
                    existing.country.isEmpty() ||
                    existing.country.equals(sysLocale.country, ignoreCase = true)
                )
            }
            if (!alreadyCovered) {
                sortedLocales.add(sysLocale)
            }
        }

        // Collapse Russian country variants
        if (sortedLocales.any { it.language == "ru" }) {
            sortedLocales.removeAll { it.language == "ru" && it.country.isNotEmpty() }
            sortedLocales.add(Locale("ru"))
        }

        // Verify "ru_RU" is NOT present, only "ru"
        val localeTags = sortedLocales.map { it.toLanguageTag() }
        assertTrue(localeTags.contains("ru"))
        assertFalse(localeTags.contains("ru-RU"))
        assertEquals(3, sortedLocales.size) // ru, en-US, fr-FR
    }

    @Test
    fun testCyrillicWordRoutingByScript() {
        val russianWord = "тестирование"
        val englishWord = "testing"

        val firstCpRu = russianWord.codePointAt(0)
        assertTrue(ScriptUtils.isLetterPartOfScript(firstCpRu, ScriptUtils.SCRIPT_CYRILLIC))
        assertFalse(ScriptUtils.isLetterPartOfScript(firstCpRu, ScriptUtils.SCRIPT_LATIN))

        val firstCpEn = englishWord.codePointAt(0)
        assertTrue(ScriptUtils.isLetterPartOfScript(firstCpEn, ScriptUtils.SCRIPT_LATIN))
        assertFalse(ScriptUtils.isLetterPartOfScript(firstCpEn, ScriptUtils.SCRIPT_CYRILLIC))
    }

    @Test
    fun testPersonalDictionarySelectionQueryForRussian() {
        fun buildQuery(locale: Locale): Pair<String, Array<String>> {
            return if (locale.language == "ru" || locale.country.isEmpty()) {
                ("locale=? OR locale LIKE ?" to arrayOf(locale.toString(), "${locale.language}_%"))
            } else {
                ("locale=?" to arrayOf(locale.toString()))
            }
        }

        val (ruQuery, ruArgs) = buildQuery(Locale("ru"))
        assertEquals("locale=? OR locale LIKE ?", ruQuery)
        assertEquals(2, ruArgs.size)
        assertEquals("ru", ruArgs[0])
        assertEquals("ru_%", ruArgs[1])

        val (enQuery, enArgs) = buildQuery(Locale("en", "US"))
        assertEquals("locale=?", enQuery)
        assertEquals(1, enArgs.size)
        assertEquals("en_US", enArgs[0])
    }
}
