package com.example

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.ColorPalette
import com.example.domain.repository.ThemeMode
import com.example.ui.theme.getDarkColorScheme
import com.example.ui.theme.getLightColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppearanceRepositoryTest {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsAndFivePaletteSelectionPersistWithoutChangingOtherAppearanceSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "datastore/appearance-test-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val repository = AppearanceRepository.forDataStore(dataStore)

        val defaults = repository.appearanceSettings.first()
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(16f, defaults.fontSize)
        assertEquals(ColorPalette.PURPLE, defaults.colorPalette)

        repository.updateThemeMode(ThemeMode.DARK)
        repository.updateFontSize(21f)
        repository.updateColorPalette(ColorPalette.FOREST_GREEN)

        val recreated = AppearanceRepository.forDataStore(dataStore).appearanceSettings.first()
        assertEquals(ThemeMode.DARK, recreated.themeMode)
        assertEquals(21f, recreated.fontSize)
        assertEquals(ColorPalette.FOREST_GREEN, recreated.colorPalette)
        assertNull(recreated.legacyAccentOverride)
    }

    @Test
    fun legacyAccentValuesMapNondestructivelyAndWrongPrimitiveTypesFallBackSafely() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "datastore/appearance-legacy-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

        dataStore.edit { preferences ->
            preferences[AppearanceRepository.ACCENT_PRESET_KEY] = "Soft Blue"
        }
        var settings = AppearanceRepository.forDataStore(dataStore).appearanceSettings.first()
        assertEquals(ColorPalette.OCEAN_BLUE, settings.colorPalette)

        dataStore.edit { preferences ->
            preferences[AppearanceRepository.ACCENT_PRESET_KEY] = "Warm Gold"
        }
        settings = AppearanceRepository.forDataStore(dataStore).appearanceSettings.first()
        assertEquals(ColorPalette.PURPLE, settings.colorPalette)
        assertEquals("Warm Gold", settings.legacyAccentOverride)

        dataStore.edit { preferences ->
            preferences.remove(AppearanceRepository.ACCENT_PRESET_KEY)
            preferences[intPreferencesKey("theme_mode")] = 7
            preferences[intPreferencesKey("font_size")] = 7
            preferences[intPreferencesKey("color_palette_v1")] = 7
        }
        settings = AppearanceRepository.forDataStore(dataStore).appearanceSettings.first()
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(16f, settings.fontSize)
        assertEquals(ColorPalette.PURPLE, settings.colorPalette)
    }

    @Test
    fun everyPaletteKeepsCoreLightAndDarkTextPairsAtReadableContrast() {
        ColorPalette.selectable.forEach { palette ->
            listOf(getLightColorScheme(palette), getDarkColorScheme(palette)).forEach { scheme ->
                assertTrue("$palette background contrast", contrast(scheme.background, scheme.onBackground) >= 4.5)
                assertTrue("$palette surface contrast", contrast(scheme.surface, scheme.onSurface) >= 4.5)
                assertTrue("$palette primary contrast", contrast(scheme.primary, scheme.onPrimary) >= 4.5)
                assertTrue("$palette error contrast", contrast(scheme.error, scheme.onError) >= 4.5)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
        val darker = minOf(first.luminance(), second.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
