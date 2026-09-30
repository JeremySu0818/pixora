package org.pixora.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguagesTest {
    @Test fun resolvesRegionalAndUnsupportedSystemLanguages() {
        mapOf("en-US" to "en", "es-MX" to "es", "pt-PT" to "pt-BR", "id-ID" to "id",
            "ar-EG" to "ar", "ja-JP" to "ja", "sv-SE" to "en").forEach { (input, expected) ->
            assertEquals(expected, AppLanguages.resolve(AppLanguages.SYSTEM, Locale.forLanguageTag(input)))
        }
    }

    @Test fun chineseScriptTakesPrecedenceOverRegion() {
        mapOf("zh" to "zh-CN", "zh-SG" to "zh-CN", "zh-HK" to "zh-TW",
            "zh-MO" to "zh-TW", "zh-TW" to "zh-TW", "zh-Hans-TW" to "zh-CN",
            "zh-Hant-CN" to "zh-TW").forEach { (input, expected) ->
            assertEquals(expected, AppLanguages.resolve(AppLanguages.SYSTEM, Locale.forLanguageTag(input)))
        }
    }

    @Test fun explicitSelectionOverridesSystemAndInvalidStoredValuesReset() {
        AppLanguages.supported.forEach {
            assertEquals(it.tag, AppLanguages.resolve(it.tag, Locale.JAPAN))
            assertEquals(it.tag, AppLanguages.normalizeSelection(it.tag))
        }
        assertEquals(AppLanguages.SYSTEM, AppLanguages.normalizeSelection("invalid"))
        assertEquals(AppLanguages.SYSTEM, AppLanguages.normalizeSelection(AppLanguages.SYSTEM))
        assertEquals(20, AppLanguages.supported.map { it.tag }.toSet().size)
    }
}
