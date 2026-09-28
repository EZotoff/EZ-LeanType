package helium314.keyboard.latin.utils

import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.PopupSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.TextKeyData
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.Locale

class PopupKeysUtilsTest {

    @Before
    fun setUp() {
        val field = Settings::class.java.getDeclaredField("mSettingsValues")
        field.isAccessible = true
        val mockValues = Mockito.mock(SettingsValues::class.java)
        Mockito.`when`(mockValues.mSecondaryLocales).thenReturn(emptyList())
        field.set(Settings.getInstance(), mockValues)
    }

    @After
    fun tearDown() {
        val field = Settings::class.java.getDeclaredField("mSettingsValues")
        field.isAccessible = true
        field.set(Settings.getInstance(), null)
    }

    private fun createTestParams(): KeyboardParams {
        val params = KeyboardParams()
        val mockId = Mockito.mock(KeyboardId::class.java)
        Mockito.`when`(mockId.isAlphabetKeyboard).thenReturn(true)
        params.mId = mockId
        params.mLocaleKeyboardInfos = LocaleKeyboardInfos(null, Locale("ru"))
        return params
    }

    @Test
    fun testHintLabelPromotedToFirstPopupKeyForRussianE() {
        val params = createTestParams()

        params.mPopupKeyTypes.addAll(getEnabledPopupKeys(POPUP_KEYS_ORDER_DEFAULT))
        params.mPopupKeyLabelSources.addAll(getEnabledPopupKeys(POPUP_KEYS_LABEL_DEFAULT))

        val ruStream = """
            [popup_keys]
            е ё е́ ѣ
        """.trimIndent().byteInputStream()
        params.mLocaleKeyboardInfos.addFile(ruStream, priority = true)

        val popupSet = PopupSet<TextKeyData>().apply {
            numberLabel = "5"
        }

        val hint = getHintLabel(popupSet, params, "е")
        assertEquals("5", hint)

        val popupKeys = createPopupKeysArray(popupSet, params, "е")
        assertNotNull(popupKeys)
        assertEquals("5", popupKeys!![0])
        assertEquals("ё", popupKeys[1])
        assertEquals("е́", popupKeys[2])
        assertEquals("ѣ", popupKeys[3])
    }

    @Test
    fun testHintLabelPromotedToFirstPopupKeyForRussianSoftSign() {
        val params = createTestParams()

        params.mPopupKeyTypes.addAll(getEnabledPopupKeys(POPUP_KEYS_ORDER_DEFAULT))
        params.mPopupKeyLabelSources.addAll(getEnabledPopupKeys(POPUP_KEYS_LABEL_DEFAULT))

        val ruStream = """
            [popup_keys]
            ь ъ ы
        """.trimIndent().byteInputStream()
        params.mLocaleKeyboardInfos.addFile(ruStream, priority = true)

        val popupSet = PopupSet<TextKeyData>().apply {
            symbols = listOf("?", "!")
        }

        val hint = getHintLabel(popupSet, params, "ь")
        assertEquals("?", hint)

        val popupKeys = createPopupKeysArray(popupSet, params, "ь")
        assertNotNull(popupKeys)
        assertEquals("?", popupKeys!![0])
        assertEquals("ъ", popupKeys[1])
        assertEquals("ы", popupKeys[2])
    }

    @Test
    fun testPopupKeyOrderPreservedWhenNoHint() {
        val params = createTestParams()

        params.mPopupKeyTypes.addAll(getEnabledPopupKeys(POPUP_KEYS_ORDER_DEFAULT))
        params.mPopupKeyLabelSources.addAll(getEnabledPopupKeys(POPUP_KEYS_LABEL_DEFAULT))

        val stream = """
            [popup_keys]
            x a b c
        """.trimIndent().byteInputStream()
        params.mLocaleKeyboardInfos.addFile(stream, priority = true)

        val popupSet = PopupSet<TextKeyData>()

        val popupKeys = createPopupKeysArray(popupSet, params, "x")
        assertNotNull(popupKeys)
        assertEquals("a", popupKeys!![0])
        assertEquals("b", popupKeys[1])
        assertEquals("c", popupKeys[2])
    }
}
