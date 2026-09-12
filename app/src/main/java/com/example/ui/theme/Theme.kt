package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.domain.repository.AppearanceSettings

fun getDarkColorScheme(accentName: String) = darkColorScheme(
    primary = when (accentName) {
        "Dusty Lavender" -> AccentDustyLavenderDark
        "Muted Rose" -> AccentMutedRoseDark
        "Sage" -> AccentSageDark
        "Soft Blue" -> AccentSoftBlueDark
        "Warm Gold" -> AccentWarmGoldDark
        "Plum" -> AccentPlumDark
        else -> AccentDustyLavenderDark
    },
    onPrimary = Color(0xFF1E1A29),
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = PrimaryTextDark,
    secondary = SecondaryAccentDark,
    onSecondary = Color(0xFF1E1A29),
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = PrimaryTextDark,
    background = BackgroundDark,
    onBackground = PrimaryTextDark,
    surface = SurfaceDark,
    onSurface = PrimaryTextDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = SecondaryTextDark,
    outline = OutlineDark
)

fun getLightColorScheme(accentName: String) = lightColorScheme(
    primary = when (accentName) {
        "Dusty Lavender" -> AccentDustyLavenderLight
        "Muted Rose" -> AccentMutedRoseLight
        "Sage" -> AccentSageLight
        "Soft Blue" -> AccentSoftBlueLight
        "Warm Gold" -> AccentWarmGoldLight
        "Plum" -> AccentPlumLight
        else -> AccentDustyLavenderLight
    },
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = PrimaryTextLight,
    secondary = SecondaryAccentLight,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = PrimaryTextLight,
    background = BackgroundLight,
    onBackground = PrimaryTextLight,
    surface = SurfaceLight,
    onSurface = PrimaryTextLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = SecondaryTextLight,
    outline = OutlineLight
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearanceSettings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) getDarkColorScheme(appearanceSettings.accentPreset) 
                      else getLightColorScheme(appearanceSettings.accentPreset)

    // Apply font scaling
    val scale = appearanceSettings.fontSize / 16f
    
    fun scaleStyle(style: TextStyle) = style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = style.lineHeight * scale
    )

    val scaledTypography = androidx.compose.material3.Typography(
        displayLarge = scaleStyle(Typography.displayLarge),
        headlineMedium = scaleStyle(Typography.headlineMedium),
        titleLarge = scaleStyle(Typography.titleLarge),
        titleMedium = scaleStyle(Typography.titleMedium),
        bodyLarge = scaleStyle(Typography.bodyLarge),
        bodyMedium = scaleStyle(Typography.bodyMedium),
        labelLarge = scaleStyle(Typography.labelLarge)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}
