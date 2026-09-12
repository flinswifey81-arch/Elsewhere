package com.example.domain.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "appearance_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: Float = 16f,
    val accentPreset: String = "Dusty Lavender"
)

class AppearanceRepository(private val context: Context) {

    companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val FONT_SIZE_KEY = floatPreferencesKey("font_size")
        val ACCENT_PRESET_KEY = stringPreferencesKey("accent_preset")
    }

    val appearanceSettings: Flow<AppearanceSettings> = context.dataStore.data
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
            val fontSize = try { preferences[FONT_SIZE_KEY] } catch (e: Exception) { null } ?: 16f
            val accentPreset = try { preferences[ACCENT_PRESET_KEY] } catch (e: Exception) { null } ?: "Dusty Lavender"
            
            AppearanceSettings(themeMode, fontSize, accentPreset)
        }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode.name
        }
    }

    suspend fun updateFontSize(size: Float) {
        context.dataStore.edit { prefs ->
            prefs[FONT_SIZE_KEY] = size
        }
    }

    suspend fun updateAccentPreset(preset: String) {
        context.dataStore.edit { prefs ->
            prefs[ACCENT_PRESET_KEY] = preset
        }
    }
}
