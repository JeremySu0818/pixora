package org.pixora.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pixora.app.ui.theme.ThemeMode

private val Context.dataStore by preferencesDataStore("pixora_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val options: UpscaleOptions = UpscaleOptions(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val model = stringPreferencesKey("model")
        val scale = intPreferencesKey("scale")
        val format = stringPreferencesKey("format")
        val folder = stringPreferencesKey("output_folder")
        val tile = intPreferencesKey("tile")
        val tta = booleanPreferencesKey("tta")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamic] ?: true,
            options = UpscaleOptions(
                modelId = p[Keys.model] ?: ModelCatalog.builtIn.first().id,
                scale = p[Keys.scale] ?: 4,
                format = runCatching { OutputFormat.valueOf(p[Keys.format] ?: "PNG") }.getOrDefault(OutputFormat.PNG),
                outputFolder = p[Keys.folder]?.let(Uri::parse),
                tileSize = p[Keys.tile] ?: 0,
                tta = p[Keys.tta] ?: false,
            ),
        )
    }

    suspend fun saveTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun saveDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.dynamic] = enabled }
    suspend fun saveOptions(value: UpscaleOptions) = context.dataStore.edit {
        it[Keys.model] = value.modelId
        it[Keys.scale] = value.scale
        it[Keys.format] = value.format.name
        value.outputFolder?.let { uri -> it[Keys.folder] = uri.toString() }
        it[Keys.tile] = value.tileSize
        it[Keys.tta] = value.tta
    }
}
