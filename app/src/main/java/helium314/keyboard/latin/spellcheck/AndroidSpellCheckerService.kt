/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.spellcheck

import android.content.Intent
import android.content.SharedPreferences
import android.service.textservice.SpellCheckerService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import android.view.textservice.SuggestionsInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.DictionaryFacilitatorLruCache
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.SuggestionResults
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * Service for spell checking, using LatinIME's dictionaries and mechanisms.
 */
class AndroidSpellCheckerService : SpellCheckerService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val MAX_NUM_OF_THREADS_READ_DICTIONARY = 2
    private val mSemaphore = Semaphore(MAX_NUM_OF_THREADS_READ_DICTIONARY, true)
    private val mSessionIdPool = ConcurrentLinkedQueue<Int>()

    private val mDictionaryFacilitatorCache = DictionaryFacilitatorLruCache(this, DICTIONARY_NAME_PREFIX)
    private val mKeyboardCache = ConcurrentHashMap<Locale, Keyboard>()

    private var mRecommendedThreshold: Float = 0f
    private var mSettingsValuesForSuggestion: SettingsValuesForSuggestion? = null

    init {
        for (i in 0 until MAX_NUM_OF_THREADS_READ_DICTIONARY) {
            mSessionIdPool.add(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        mRecommendedThreshold = getString(R.string.spellchecker_recommended_threshold_value).toFloat()
        val prefs = prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        onSharedPreferenceChanged(prefs, Settings.PREF_USE_APPS)
        val blockOffensive = prefs.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        mSettingsValuesForSuggestion = SettingsValuesForSuggestion(blockOffensive, false, "fallback")
    }

    override fun onDestroy() {
        prefs().unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    val recommendedThreshold: Float
        get() = mRecommendedThreshold

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key != null) {
            when (key) {
                Settings.PREF_USE_APPS -> {
                    val useAppsDictionary = prefs.getBoolean(Settings.PREF_USE_APPS, Defaults.PREF_USE_APPS)
                    mDictionaryFacilitatorCache.setUseAppsDictionary(useAppsDictionary)
                }
                Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE -> {
                    val blockOffensive = prefs.getBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
                    mSettingsValuesForSuggestion = SettingsValuesForSuggestion(blockOffensive, false, "fallback")
                }
            }
        }
    }

    override fun createSession(): Session {
        return AndroidSpellCheckerSessionFactory.newInstance(this)
    }

    fun releaseMemory() {
        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        try {
            mDictionaryFacilitatorCache.closeDictionaries()
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        }
        mKeyboardCache.clear()
    }

    private val RUSSIAN_INFLECTION_SUFFIXES = arrayOf(
        // Participles & gerunds
        "вшись", "вшийся", "вшаяся", "вшееся", "вшиеся",
        "ющий", "ющая", "ющее", "ющие", "ющего", "ющей", "ющим", "ющих",
        "ящий", "ящая", "ящее", "ящие", "ящего", "ящей", "ящим", "ящих",
        "ущий", "ущая", "ущее", "ущие", "ущего", "ущей", "ущим", "ущих",
        "нный", "нная", "нное", "нные", "нного", "нной", "нным", "нных",
        "вший", "вшая", "вшее", "вшие", "вшего", "вшей", "вшим", "вших",
        "щий", "щая", "щее", "щие", "щего", "щей", "щим", "щих",
        "емый", "емая", "емое", "емые", "емого", "емой", "емым", "емых",
        "имый", "имая", "имое", "имые",
        // Adjective superlatives
        "ейший", "ейшая", "ейшее", "ейшие", "айший", "айшая", "айшее", "айшие"
    )

    private fun checkRussianInflections(facilitator: DictionaryFacilitator, word: String): Boolean {
        if (word.length <= 3) return false
        val baseWord = if ((word.endsWith("ся") || word.endsWith("сь")) && word.length > 4) {
            word.substring(0, word.length - 2)
        } else {
            word
        }
        if (baseWord != word && facilitator.isValidSpellingWord(baseWord)) return true

        for (suffix in RUSSIAN_INFLECTION_SUFFIXES) {
            if (baseWord.endsWith(suffix) && baseWord.length > suffix.length + 2) {
                val stem = baseWord.substring(0, baseWord.length - suffix.length)
                if (facilitator.isValidSpellingWord(stem + "ть")) return true
                if (facilitator.isValidSpellingWord(stem + "ить")) return true
                if (facilitator.isValidSpellingWord(stem + "ать")) return true
                if (facilitator.isValidSpellingWord(stem + "еть")) return true
                if (facilitator.isValidSpellingWord(stem + "ый")) return true
                if (facilitator.isValidSpellingWord(stem + "ий")) return true
            }
        }
        return false
    }

    private fun checkWordInFacilitator(facilitator: DictionaryFacilitator, word: String): Boolean {
        if (facilitator.isValidSpellingWord(word)) return true

        // Check ё <-> е substitution for Russian words
        val hasYo = word.contains('ё') || word.contains('Ё')
        if (hasYo) {
            val normalized = word.replace('ё', 'е').replace('Ё', 'Е')
            if (facilitator.isValidSpellingWord(normalized)) return true
            if (checkRussianInflections(facilitator, normalized)) return true
        }

        // Check Russian morphology and inflections
        val firstCp = if (word.isNotEmpty()) word.codePointAt(0) else 0
        if (ScriptUtils.isLetterPartOfScript(firstCp, ScriptUtils.SCRIPT_CYRILLIC)) {
            if (checkRussianInflections(facilitator, word)) return true
        }

        // Check hyphenated words (e.g. по-русски, кто-то, из-за)
        if (word.contains('-')) {
            val parts = word.split('-')
            if (parts.all { it.isEmpty() || checkWordInFacilitator(facilitator, it) }) {
                return true
            }
        }
        return false
    }

    fun isValidWord(locale: Locale, word: String): Boolean {
        val prefs = prefs()
        if (!prefs.getBoolean(Settings.PREF_ENABLE_SPELL_CHECKER_SERVICE, Defaults.PREF_ENABLE_SPELL_CHECKER_SERVICE)) {
            return true
        }
        if (word.isEmpty()) return true
        mSemaphore.acquireUninterruptibly()
        try {
            val dictionaryFacilitatorForLocale = mDictionaryFacilitatorCache.get(locale)
            if (checkWordInFacilitator(dictionaryFacilitatorForLocale, word)) return true

            // Multilingual: if word has Cyrillic characters, check Russian dictionary
            val firstCp = word.codePointAt(0)
            if (ScriptUtils.isLetterPartOfScript(firstCp, ScriptUtils.SCRIPT_CYRILLIC)) {
                if (locale.language != "ru") {
                    val ruFacilitator = mDictionaryFacilitatorCache.get(Locale("ru"))
                    if (checkWordInFacilitator(ruFacilitator, word)) return true
                }
            } else if (ScriptUtils.isLetterPartOfScript(firstCp, ScriptUtils.SCRIPT_LATIN)) {
                if (locale.language != "en") {
                    val enFacilitator = mDictionaryFacilitatorCache.get(Locale.ENGLISH)
                    if (checkWordInFacilitator(enFacilitator, word)) return true
                }
            }

            // Also check other enabled subtypes (for bilingual setups)
            for (enabledSubtype in SubtypeSettings.getEnabledSubtypes(true)) {
                val subtypeLocale = enabledSubtype.locale()
                if (subtypeLocale.language != locale.language) {
                    val facilitator = mDictionaryFacilitatorCache.get(subtypeLocale)
                    if (checkWordInFacilitator(facilitator, word)) return true
                }
            }
            return false
        } finally {
            mSemaphore.release()
        }
    }

    fun getSuggestionResults(
        locale: Locale,
        composedData: ComposedData,
        ngramContext: NgramContext,
        keyboard: Keyboard
    ): SuggestionResults? {
        var sessionId: Int? = null
        mSemaphore.acquireUninterruptibly()
        try {
            sessionId = mSessionIdPool.poll()
            val dictionaryFacilitatorForLocale = mDictionaryFacilitatorCache.get(locale)
            val settingsValues = mSettingsValuesForSuggestion ?: return null
            return dictionaryFacilitatorForLocale.getSuggestionResults(
                composedData, ngramContext,
                keyboard, settingsValues,
                sessionId, SuggestedWords.INPUT_STYLE_TYPING
            )
        } finally {
            if (sessionId != null) {
                mSessionIdPool.add(sessionId)
            }
            mSemaphore.release()
        }
    }

    fun hasMainDictionaryForLocale(locale: Locale): Boolean {
        mSemaphore.acquireUninterruptibly()
        try {
            val dictionaryFacilitator = mDictionaryFacilitatorCache.get(locale)
            if (dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()) return true
            if (locale.language == "ru") {
                val ruFacilitator = mDictionaryFacilitatorCache.get(Locale("ru"))
                return ruFacilitator.hasAtLeastOneInitializedMainDictionary()
            }
            return false
        } finally {
            mSemaphore.release()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        try {
            mDictionaryFacilitatorCache.closeDictionaries()
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY)
        }
        mKeyboardCache.clear()
        return false
    }

    fun getKeyboardForLocale(locale: Locale): Keyboard {
        var keyboard = mKeyboardCache[locale]
        if (keyboard == null) {
            keyboard = createKeyboardForLocale(locale)
            mKeyboardCache[locale] = keyboard
        }
        return keyboard
    }

    private fun createKeyboardForLocale(locale: Locale): Keyboard {
        if (Settings.getValues() == null) {
            val editorInfo = EditorInfo()
            editorInfo.inputType = InputType.TYPE_CLASS_TEXT
            Settings.getInstance().loadSettings(
                this, locale, InputAttributes(editorInfo, false, packageName), ScriptUtils.SCRIPT_UNKNOWN
            )
        }
        var mainLayoutName: String? = null
        for (enabledSubtype in SubtypeSettings.getEnabledSubtypes(true)) {
            if (enabledSubtype.locale == locale.toString() ||
                enabledSubtype.locale().language == locale.language
            ) {
                mainLayoutName = enabledSubtype.mainLayoutName()
                break
            }
        }
        if (mainLayoutName == null) {
            mainLayoutName = SubtypeSettings.getMatchingMainLayoutNameForLocale(locale)
        }
        val subtype = SubtypeUtilsAdditional.createDummyAdditionalSubtype(locale, mainLayoutName)
        val keyboardLayoutSet = createKeyboardSetForSpellChecker(subtype)
        return keyboardLayoutSet.getKeyboard(KeyboardId.ELEMENT_ALPHABET)
    }

    private fun createKeyboardSetForSpellChecker(subtype: InputMethodSubtype): KeyboardLayoutSet {
        val editorInfo = EditorInfo()
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
        val builder = KeyboardLayoutSet.Builder(this, editorInfo)
        return builder
            .setKeyboardGeometry(SPELLCHECKER_DUMMY_KEYBOARD_WIDTH, SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT)
            .setSubtype(RichInputMethodSubtype.get(subtype))
            .setIsSpellChecker(true)
            .disableTouchPositionCorrectionData()
            .build()
    }

    companion object {
        const val SPELLCHECKER_DUMMY_KEYBOARD_WIDTH = 480
        const val SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT = 301

        private const val DICTIONARY_NAME_PREFIX = "spellcheck_"
        private val EMPTY_STRING_ARRAY = arrayOf<String>()

        const val SINGLE_QUOTE = "'"
        const val APOSTROPHE = "’"

        fun getNotInDictEmptySuggestions(reportAsTypo: Boolean): SuggestionsInfo {
            return SuggestionsInfo(
                if (reportAsTypo) SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO else 0,
                EMPTY_STRING_ARRAY
            )
        }

        fun getInDictEmptySuggestions(): SuggestionsInfo {
            return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY_STRING_ARRAY)
        }
    }
}
