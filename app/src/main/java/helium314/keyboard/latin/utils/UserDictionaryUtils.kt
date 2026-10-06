/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-only
 */

package helium314.keyboard.latin.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.UserDictionary
import java.util.Locale

object UserDictionaryUtils {
    private const val TAG = "UserDictionaryUtils"

    fun isCyrillicWord(word: CharSequence): Boolean {
        var i = 0
        while (i < word.length) {
            val cp = Character.codePointAt(word, i)
            if (ScriptUtils.isLetterPartOfScript(cp, ScriptUtils.SCRIPT_CYRILLIC)) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Finds any words in the user dictionary that contain Cyrillic characters
     * but are tagged with a non-Russian locale (e.g. en_GB, en_US from the system
     * spellchecker session), and migrates their locale to "ru".
     */
    fun migrateMisattributedCyrillicWords(resolver: ContentResolver) {
        try {
            val projection = arrayOf(
                UserDictionary.Words._ID,
                UserDictionary.Words.WORD,
                UserDictionary.Words.LOCALE
            )
            val selection = "${UserDictionary.Words.LOCALE} IS NOT NULL AND ${UserDictionary.Words.LOCALE} NOT LIKE 'ru%'"
            val cursor = resolver.query(UserDictionary.Words.CONTENT_URI, projection, selection, null, null) ?: return
            val idsToUpdate = mutableListOf<Long>()
            cursor.use {
                val idIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words._ID)
                val wordIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
                while (cursor.moveToNext()) {
                    val word = cursor.getString(wordIdx)
                    if (!word.isNullOrEmpty() && isCyrillicWord(word)) {
                        idsToUpdate.add(cursor.getLong(idIdx))
                    }
                }
            }
            for (id in idsToUpdate) {
                val values = ContentValues().apply {
                    put(UserDictionary.Words.LOCALE, "ru")
                }
                val updated = resolver.update(
                    UserDictionary.Words.CONTENT_URI,
                    values,
                    "${UserDictionary.Words._ID}=?",
                    arrayOf(id.toString())
                )
                if (updated > 0) {
                    Log.i(TAG, "Migrated misattributed Cyrillic word ID $id to Russian locale")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate misattributed Cyrillic words", e)
        }
    }

    /**
     * Collapses country-specific user dictionary locales (e.g. en_US, en_GB)
     * to language-only locales (e.g. en), so there is a single personal
     * dictionary per language. Words in different country variants of the
     * same language are merged; NULL locales and already language-only
     * locales are left untouched.
     */
    fun collapseLocalesToLanguageOnly(resolver: ContentResolver) {
        try {
            val projection = arrayOf(
                UserDictionary.Words._ID,
                UserDictionary.Words.LOCALE
            )
            val cursor = resolver.query(UserDictionary.Words.CONTENT_URI, projection, null, null, null) ?: return
            val idsToUpdate = mutableListOf<Pair<Long, String>>()
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(UserDictionary.Words._ID)
                val localeIdx = it.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
                while (it.moveToNext()) {
                    val localeStr = it.getString(localeIdx) ?: continue
                    // Match lang_COUNTRY or lang_COUNTRY_VARIANT (ISO codes only).
                    val match = Regex("^[a-z]{2,3}_[A-Za-z]{2,}(?:_.+)?$").matchEntire(localeStr) ?: continue
                    idsToUpdate.add(it.getLong(idIdx) to match.value.substringBefore("_"))
                }
            }
            for ((id, language) in idsToUpdate) {
                val values = ContentValues().apply {
                    put(UserDictionary.Words.LOCALE, language)
                }
                val updated = resolver.update(
                    UserDictionary.Words.CONTENT_URI,
                    values,
                    "${UserDictionary.Words._ID}=?",
                    arrayOf(id.toString())
                )
                if (updated > 0) {
                    Log.i(TAG, "Collapsed user dictionary locale of word ID $id to '$language'")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collapse user dictionary locales", e)
        }
    }
}
