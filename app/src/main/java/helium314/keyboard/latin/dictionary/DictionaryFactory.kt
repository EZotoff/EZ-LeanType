/*
* Copyright (C) 2011 The Android Open Source Project
* modified
* SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
*/
package helium314.keyboard.latin.dictionary

import android.content.Context
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.LinkedList
import java.util.Locale

object DictionaryFactory {
    /**
     * Initializes a main dictionary collection for a locale.
     * Uses all dictionaries in cache folder for locale, and adds built-in
     * dictionaries of matching locales if type is not already in cache folder.
     *
     * @return an initialized instance of DictionaryCollection
     */
    // todo:
    //  expose the weight so users can adjust dictionary "importance" (useful for addons like emoji dict)
    //  allow users to block certain dictionaries (not sure how this should work exactly)
    fun createMainDictionaryCollection(context: Context, locale: Locale, useEmojiDict: Boolean): DictionaryCollection {
        val dictList = LinkedList<Dictionary>()
        val (extracted, nonExtracted) = getAvailableDictsForLocale(locale, context, useEmojiDict)
        val loadedFileNames = mutableSetOf<String>()

        // 1. Process extracted files (both user-imported dicts and previously extracted asset dicts)
        extracted.forEach { file ->
            checkAndAddDictionaryToList(file, dictList, locale, context, loadedFileNames)
        }

        // 2. Extract and load any asset dictionaries not yet extracted
        nonExtracted.forEach { filename ->
            val extractedFile = DictionaryInfoUtils.extractAssetsDictionary(filename, locale, context) ?: return@forEach
            checkAndAddDictionaryToList(extractedFile, dictList, locale, context, loadedFileNames)
        }
        return DictionaryCollection(Dictionary.TYPE_MAIN, locale, dictList, FloatArray(dictList.size) { 1f })
    }

    fun getAvailableDictsForLocale(locale: Locale, context: Context, useEmojiDict: Boolean): Pair<Array<out File>, List<String>> {
        var cachedDicts = DictionaryInfoUtils.getCachedDictsForLocale(locale, context)
        if (!useEmojiDict) cachedDicts = cachedDicts.filter { it.name.substringBefore("_") != Dictionary.TYPE_EMOJI }.toTypedArray()

        val nonExtractedDicts = mutableListOf<String>()
        DictionaryInfoUtils.getAssetsDictionaryList(context)
            // file name is <type>_<language tag>.dict
            ?.groupBy { it.substringBefore("_") }
            ?.forEach { (dictType, dicts) ->
                if (!useEmojiDict && dictType == Dictionary.TYPE_EMOJI) return@forEach
                val hasValidCached = cachedDicts.any { 
                    it.name == "$dictType.dict" && it.length() > 1000 && DictionaryInfoUtils.getDictionaryFileHeaderOrNull(it) != null 
                }
                if (hasValidCached)
                    return@forEach // dictionary is already extracted and valid
                val bestMatch = LocaleUtils.getBestMatch(locale, dicts) {
                    DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it)
                } ?: return@forEach
                nonExtractedDicts.add(bestMatch)
            }
        return cachedDicts to nonExtractedDicts
    }

    /**
     * add dictionary created from [file] to [dicts]
     * if [file] cannot be loaded it is deleted
     * For main dictionaries, allows both user dictionaries and internal dictionaries.
     * For non-main dictionaries (e.g. emoji), only allows one of the same type.
     */
    private fun checkAndAddDictionaryToList(
        file: File,
        dicts: MutableList<Dictionary>,
        locale: Locale,
        context: Context,
        loadedFileNames: MutableSet<String>
    ) {
        if (!loadedFileNames.add(file.name)) {
            // Already loaded this exact file
            return
        }
        val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file)
        if (header != null) {
            val prefs = context.prefs()
            val dictType = header.mIdString.split(":").first()
            if (dictType == Dictionary.TYPE_MAIN) {
                val localeTag = locale.toLanguageTag().lowercase().replace("-", "_")
                val langTag = locale.language.lowercase()
                val isExplicitlyDisabled = !prefs.getBoolean("pref_dict_enabled_main:$localeTag", true) ||
                        !prefs.getBoolean("pref_dict_enabled_main:$langTag", true) ||
                        !prefs.getBoolean("pref_dict_enabled_${header.mIdString}", true)
                if (isExplicitlyDisabled) {
                    Log.i("DictionaryFactory", "skipping disabled main dictionary ${file.name} for locale $locale")
                    return
                }
            } else {
                if (!prefs.getBoolean("pref_dict_enabled_${header.mIdString}", true)) {
                    Log.i("DictionaryFactory", "skipping disabled addon dictionary ${header.mIdString}")
                    return
                }
                // For non-main addon dictionaries (e.g. emoji), only keep one of each type
                if (dicts.any { it.mDictType == dictType }) {
                    return
                }
            }
        }
        val dictionary = getDictionary(file, locale) ?: return
        if (dictionary.mDictType != Dictionary.TYPE_MAIN && dicts.any { it.mDictType == dictionary.mDictType }) {
            dictionary.close()
            return
        }
        dicts.add(dictionary)
    }

    fun getDictionary(
        file: File,
        locale: Locale
    ): Dictionary? {
        if (!file.isFile) return null
        if (file.length() < 1024) {
            killDictionary(file)
            return null
        }
        val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file)
        if (header == null) {
            killDictionary(file)
            return null
        }
        val dictType = header.mIdString.split(":").first()
        val dictLocale = header.mLocaleString.constructLocale()
        val readOnlyBinaryDictionary = ReadOnlyBinaryDictionary(
            file.absolutePath, 0, file.length(), false, dictLocale, dictType
        )

        if (readOnlyBinaryDictionary.isValidDictionary) {
            if (locale.language == "ko") {
                // Use KoreanDictionary for Korean locale
                return KoreanDictionary(readOnlyBinaryDictionary)
            }
            return readOnlyBinaryDictionary
        }
        readOnlyBinaryDictionary.close()
        killDictionary(file)
        return null
    }

    private fun killDictionary(file: File) {
        Log.e("DictionaryFactory", "could not load dictionary ${file.parentFile?.name}/${file.name}, deleting")
        file.delete()
    }
}
