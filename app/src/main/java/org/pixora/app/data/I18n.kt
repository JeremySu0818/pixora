package org.pixora.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.json.JSONObject
import java.util.Locale

/** Immutable catalogs keep the selected language and translations in sync during recomposition. */
class I18n(context: Context, val tag: String) {
    private val fallback = load(context, "strings.json")
    private val translations = if (tag == "en") fallback else runCatching {
        load(context, "locales/$tag/strings.json")
    }.getOrDefault(JSONObject())
    private val locale = Locale.forLanguageTag(tag)

    fun text(key: String, vararg args: Any): String {
        val raw = translations.optString(key).ifBlank { fallback.optString(key, key) }
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    private fun load(context: Context, path: String): JSONObject =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
}

private val LocalI18n = staticCompositionLocalOf<I18n> { error("I18nProvider is missing") }

@Composable
fun I18nProvider(languageTag: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
        ?: context.findActivityResultRegistryOwner()
        ?: error("I18nProvider requires an ActivityResultRegistryOwner")
    val systemConfiguration = LocalConfiguration.current
    val tag = AppLanguages.resolve(languageTag, systemConfiguration.locales[0])
    val locale = remember(tag) { Locale.forLanguageTag(tag) }
    val configuration = remember(systemConfiguration, tag) {
        Configuration(systemConfiguration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
    }
    val catalog = remember(context, tag) { I18n(context, tag) }
    val direction = if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL)
        LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalI18n provides catalog,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides direction,
        content = content,
    )
}

private fun Context.findActivityResultRegistryOwner(): ActivityResultRegistryOwner? {
    var current: Context? = this
    while (current != null) {
        if (current is ActivityResultRegistryOwner) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

@Composable
fun t(key: String, vararg args: Any): String = LocalI18n.current.text(key, *args)
