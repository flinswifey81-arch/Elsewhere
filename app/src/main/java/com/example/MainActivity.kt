package com.example

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ThemeMode
import com.example.ui.components.BrandedLaunchSurface
import com.example.ui.navigation.ElsewhereApp
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationChatId = mutableStateOf<String?>(null)

    private fun chatIdFromReplyIntent(incoming: Intent?): String? {
        val uri = incoming?.data ?: return null
        if (incoming.action != Intent.ACTION_VIEW ||
            uri.scheme != "elsewhere" || uri.host != "chat" ||
            uri.pathSegments.size != 1
        ) return null
        return uri.lastPathSegment?.takeIf(String::isNotBlank)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationChatId.value = chatIdFromReplyIntent(intent)
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
                        ElsewhereApp(
                            container = appContainer,
                            requestedChatId = notificationChatId.value,
                            onRequestedChatHandled = { notificationChatId.value = null }
                        )
                    } else {
                        BrandedLaunchSurface()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationChatId.value = chatIdFromReplyIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as ElsewhereApplication).container.generationNotifications?.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            (application as ElsewhereApplication).container.generationNotifications?.setAppVisible(false)
        }
        if (!isChangingConfigurations) {
            val appContainer = (application as ElsewhereApplication).container
            lifecycleScope.launch { appContainer.coopSessionManager?.stopForBackground() }
        }
    }
}
