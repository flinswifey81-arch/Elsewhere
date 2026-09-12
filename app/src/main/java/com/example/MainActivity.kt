package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ThemeMode
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as ElsewhereApplication).container
        
        setContent {
            val appearanceSettings by appContainer.appearanceRepository.appearanceSettings.collectAsState(
                initial = AppearanceSettings()
            )
            
            val darkTheme = when (appearanceSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            
            AppTheme(
                darkTheme = darkTheme,
                appearanceSettings = appearanceSettings
            ) {
                ElsewhereApp(container = appContainer)
            }
        }
    }
}
