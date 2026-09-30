package org.pixora.app.data

import java.util.Locale

data class AppLanguage(val tag: String, val label: String)

object AppLanguages {
    const val SYSTEM = "system"
    val supported = listOf(
        AppLanguage("ar", "العربية"), AppLanguage("cs", "Čeština"),
        AppLanguage("de", "Deutsch"), AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"), AppLanguage("hi", "हिन्दी"),
        AppLanguage("hu", "Magyar"), AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("it", "Italiano"), AppLanguage("ja", "日本語"),
        AppLanguage("en", "English"), AppLanguage("ko", "한국어"),
        AppLanguage("nl", "Nederlands"), AppLanguage("pl", "Polski"),
        AppLanguage("pt-BR", "Português (Brasil)"), AppLanguage("ru", "Русский"),
        AppLanguage("tr", "Türkçe"), AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("zh-CN", "简体中文"), AppLanguage("zh-TW", "繁體中文"),
    )

    fun normalizeSelection(tag: String): String =
        tag.takeIf { value -> value == SYSTEM || supported.any { it.tag == value } } ?: SYSTEM

    fun resolve(tag: String, systemLocale: Locale): String {
        val locale = if (tag == SYSTEM || tag.isBlank()) systemLocale else Locale.forLanguageTag(tag)
        if (locale.language == "zh") {
            return if (locale.script == "Hant" ||
                (locale.script != "Hans" && locale.country in setOf("TW", "HK", "MO"))) "zh-TW" else "zh-CN"
        }
        if (locale.language == "pt") return "pt-BR"
        val language = if (locale.language == "in") "id" else locale.language
        return supported.firstOrNull { it.tag == language }?.tag ?: "en"
    }
}
