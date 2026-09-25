package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.produceState
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ThemeMode
import com.example.ui.components.BrandedLaunchSurface
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as ElsewhereApplication).container
        
        setContent {
            val loadedAppearanceSettings = produceState<AppearanceSettings?>(initialValue = null) {
                appContainer.appearanceRepository.appearanceSettings.collect { value = it }
            }.value
            val appearanceSettings = loadedAppearanceSettings ?: AppearanceSettings()
            
            val darkTheme = when (appearanceSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            
            AppTheme(
                darkTheme = darkTheme,
                appearanceSettings = appearanceSettings
            ) {
                Crossfade(
                    targetState = loadedAppearanceSettings != null,
                    animationSpec = tween(durationMillis = 240),
                    label = "startup_content"
                ) { isInitialized ->
                    if (isInitialized) {
                        ElsewhereApp(container = appContainer)
                    } else {
                        BrandedLaunchSurface()
                    }
                }
            }
        }
    }
}
