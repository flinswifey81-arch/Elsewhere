package com.example.domain.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "appearance_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ColorPalette(val storageId: String, val displayName: String) {
    PURPLE("purple", "Purple"),
    OCEAN_BLUE("ocean_blue", "Ocean Blue"),
    FOREST_GREEN("forest_green", "Forest Green"),
    ROSE("rose", "Rose"),
    SLATE("slate", "Slate");

    companion object {
        val selectable = entries.toList()

        fun fromStorage(value: String?): ColorPalette? = entries.firstOrNull {
            it.storageId.equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true) ||
                it.displayName.equals(value, ignoreCase = true)
        }

        fun fromLegacyAccent(value: String?): ColorPalette? = when (value) {
            "Dusty Lavender", "Plum" -> PURPLE
            "Soft Blue" -> OCEAN_BLUE
            "Sage" -> FOREST_GREEN
            "Muted Rose" -> ROSE
            else -> null
        }
    }
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: Float = 16f,
    val colorPalette: ColorPalette = ColorPalette.PURPLE,
    /** Keeps the old Warm Gold tertiary accent until the user explicitly chooses a new palette. */
    val legacyAccentOverride: String? = null
)

class AppearanceRepository private constructor(
    private val dataStore: DataStore<Preferences>
) {

    constructor(context: Context) : this(context.dataStore)

    companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val FONT_SIZE_KEY = floatPreferencesKey("font_size")
        val COLOR_PALETTE_KEY = stringPreferencesKey("color_palette_v1")
        // Retained for a nondestructive read of preferences written by earlier builds.
        val ACCENT_PRESET_KEY = stringPreferencesKey("accent_preset")

        internal fun forDataStore(dataStore: DataStore<Preferences>) = AppearanceRepository(dataStore)
    }

    val appearanceSettings: Flow<AppearanceSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val themeModeStr = try { preferences[THEME_MODE_KEY] } catch (e: Exception) { null } ?: ThemeMode.SYSTEM.name
            val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (e: Exception) { ThemeMode.SYSTEM }
            val fontSize = (try { preferences[FONT_SIZE_KEY] } catch (e: Exception) { null } ?: 16f)
                .coerceIn(12f, 24f)
            val storedPalette = try { preferences[COLOR_PALETTE_KEY] } catch (e: Exception) { null }
            val legacyAccent = try { preferences[ACCENT_PRESET_KEY] } catch (e: Exception) { null }
            val colorPalette = ColorPalette.fromStorage(storedPalette)
                ?: ColorPalette.fromLegacyAccent(legacyAccent)
                ?: ColorPalette.PURPLE
            val legacyOverride = if (storedPalette == null && legacyAccent == "Warm Gold") {
                legacyAccent
            } else {
                null
            }

            AppearanceSettings(themeMode, fontSize, colorPalette, legacyOverride)
        }

    suspend fun updateThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode.name
        }
    }

    suspend fun updateFontSize(size: Float) {
        dataStore.edit { prefs ->
            prefs[FONT_SIZE_KEY] = size.coerceIn(12f, 24f)
        }
    }

    suspend fun updateColorPalette(palette: ColorPalette) {
        dataStore.edit { prefs ->
            prefs[COLOR_PALETTE_KEY] = palette.storageId
        }
    }

    suspend fun replaceSettings(settings: AppearanceSettings) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = settings.themeMode.name
            prefs[FONT_SIZE_KEY] = settings.fontSize.coerceIn(12f, 24f)
            prefs[COLOR_PALETTE_KEY] = settings.colorPalette.storageId
            if (settings.legacyAccentOverride == null) {
                prefs.remove(ACCENT_PRESET_KEY)
            } else {
                prefs[ACCENT_PRESET_KEY] = settings.legacyAccentOverride
            }
        }
    }

    /** Compatibility entry point for older call sites; it never deletes the legacy preference. */
    suspend fun updateAccentPreset(preset: String) {
        val palette = ColorPalette.fromStorage(preset) ?: ColorPalette.fromLegacyAccent(preset)
            ?: ColorPalette.PURPLE
        updateColorPalette(palette)
    }
}
