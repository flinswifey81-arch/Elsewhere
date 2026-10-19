package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.ColorPalette

@Immutable
data class ElsewhereSemanticColors(
    val personaBubble: Color,
    val onPersonaBubble: Color,
    val characterBubble: Color,
    val onCharacterBubble: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color
)

val LocalElsewhereSemanticColors = staticCompositionLocalOf {
    ElsewhereSemanticColors(
        Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified,
        Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified,
        Color.Unspecified, Color.Unspecified
    )
}

private data class PaletteTones(
    val lightBackground: Color,
    val lightSurface: Color,
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightSecondary: Color,
    val lightSecondaryContainer: Color,
    val lightTertiary: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceHigh: Color,
    val darkPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkTertiary: Color
)

private fun tones(palette: ColorPalette): PaletteTones = when (palette) {
    ColorPalette.PURPLE -> PaletteTones(
        Parchment, WarmWhite, DeepIris, RoseBlush, LightSecondary, LightSecondaryContainer,
        LightTertiary, NightInk, CharcoalPlum, DeepEggplant, Doorlight, DeepIris, MutedRose, RoseBlush
    )
    ColorPalette.OCEAN_BLUE -> PaletteTones(
        OceanBackgroundLight, OceanSurfaceLight, OceanPrimaryLight, OceanPrimaryContainerLight,
        Color(0xFF526873), Color(0xFFD9E7EC), Color(0xFF6A5D8D), OceanInk, OceanSurface,
        OceanSurfaceHigh, OceanPrimaryDark, Color(0xFF24566C), Color(0xFFAFC8D3), Color(0xFFC4B9E5)
    )
    ColorPalette.FOREST_GREEN -> PaletteTones(
        ForestBackgroundLight, ForestSurfaceLight, ForestPrimaryLight, ForestPrimaryContainerLight,
        Color(0xFF5E6A55), Color(0xFFE0E7D8), Color(0xFF81613D), ForestInk, ForestSurface,
        ForestSurfaceHigh, ForestPrimaryDark, Color(0xFF31583E), Color(0xFFC1CDAE), Color(0xFFE4C28F)
    )
    ColorPalette.ROSE -> PaletteTones(
        RoseBackgroundLight, RoseSurfaceLight, RosePrimaryLight, RosePrimaryContainerLight,
        Color(0xFF755D67), Color(0xFFEBDDE2), Color(0xFF7A5F87), RoseInk, RoseSurface,
        RoseSurfaceHigh, RosePrimaryDark, Color(0xFF76445A), Color(0xFFD2B3BE), Color(0xFFD1B7DB)
    )
    ColorPalette.SLATE -> PaletteTones(
        SlateBackgroundLight, SlateSurfaceLight, SlatePrimaryLight, SlatePrimaryContainerLight,
        Color(0xFF616971), Color(0xFFE1E5E8), Color(0xFF6C5D73), SlateInk, SlateSurface,
        SlateSurfaceHigh, SlatePrimaryDark, Color(0xFF435363), Color(0xFFC1C8CE), Color(0xFFD0C0D5)
    )
}

fun getDarkColorScheme(
    palette: ColorPalette,
    legacyAccentOverride: String? = null
): ColorScheme {
    val t = tones(palette)
    val tertiary = if (legacyAccentOverride == "Warm Gold") AccentWarmGoldDark else t.darkTertiary
    return darkColorScheme(
        primary = t.darkPrimary,
        onPrimary = t.darkBackground,
        primaryContainer = t.darkPrimaryContainer,
        onPrimaryContainer = MoonCream,
        secondary = t.darkSecondary,
        onSecondary = t.darkBackground,
        secondaryContainer = t.darkSurfaceHigh,
        onSecondaryContainer = MoonCream,
        tertiary = tertiary,
        onTertiary = t.darkBackground,
        tertiaryContainer = t.darkSurfaceHigh,
        onTertiaryContainer = MoonCream,
        background = t.darkBackground,
        onBackground = MoonCream,
        surface = t.darkSurface,
        surfaceBright = t.darkSurfaceHigh,
        surfaceDim = t.darkBackground,
        surfaceContainerLowest = t.darkBackground,
        surfaceContainerLow = t.darkSurface,
        surfaceContainer = t.darkSurface,
        surfaceContainerHigh = t.darkSurfaceHigh,
        surfaceContainerHighest = t.darkSurfaceHigh,
        onSurface = MoonCream,
        surfaceVariant = t.darkSurfaceHigh,
        onSurfaceVariant = Color(0xFFD7CDD4),
        outline = Color(0xFF9B8E98),
        outlineVariant = t.darkSurfaceHigh,
        error = DestructiveLight,
        onError = NightInk,
        errorContainer = DestructiveWine,
        onErrorContainer = DestructiveBlush,
        inverseSurface = MoonCream,
        inverseOnSurface = t.darkBackground,
        inversePrimary = t.lightPrimary,
        surfaceTint = t.darkPrimary
    )
}

fun getLightColorScheme(
    palette: ColorPalette,
    legacyAccentOverride: String? = null
): ColorScheme {
    val t = tones(palette)
    val tertiary = if (legacyAccentOverride == "Warm Gold") AccentWarmGoldLight else t.lightTertiary
    return lightColorScheme(
        primary = t.lightPrimary,
        onPrimary = Color.White,
        primaryContainer = t.lightPrimaryContainer,
        onPrimaryContainer = NightInk,
        secondary = t.lightSecondary,
        onSecondary = Color.White,
        secondaryContainer = t.lightSecondaryContainer,
        onSecondaryContainer = NightInk,
        tertiary = tertiary,
        onTertiary = Color.White,
        tertiaryContainer = t.lightSecondaryContainer,
        onTertiaryContainer = NightInk,
        background = t.lightBackground,
        onBackground = NightInk,
        surface = t.lightSurface,
        surfaceBright = Color.White,
        surfaceDim = t.lightSecondaryContainer,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = t.lightSurface,
        surfaceContainer = t.lightSecondaryContainer.copy(alpha = 0.72f),
        surfaceContainerHigh = t.lightPrimaryContainer.copy(alpha = 0.72f),
        surfaceContainerHighest = t.lightPrimaryContainer,
        onSurface = CharcoalPlum,
        surfaceVariant = t.lightPrimaryContainer.copy(alpha = 0.68f),
        onSurfaceVariant = Color(0xFF403740),
        outline = t.lightSecondary,
        outlineVariant = t.lightSecondaryContainer,
        error = DestructiveWine,
        onError = DestructiveBlush,
        errorContainer = DestructiveContainerLight,
        onErrorContainer = DestructiveOnContainerLight,
        inverseSurface = CharcoalPlum,
        inverseOnSurface = MoonCream,
        inversePrimary = t.darkPrimary,
        surfaceTint = t.lightPrimary
    )
}

private fun semanticColors(scheme: ColorScheme, dark: Boolean) = ElsewhereSemanticColors(
    personaBubble = scheme.primaryContainer,
    onPersonaBubble = scheme.onPrimaryContainer,
    characterBubble = scheme.surfaceVariant,
    onCharacterBubble = scheme.onSurfaceVariant,
    successContainer = if (dark) Color(0xFF234B37) else Color(0xFFD9EFDF),
    onSuccessContainer = if (dark) Color(0xFFD7F5DF) else Color(0xFF173925),
    warningContainer = if (dark) Color(0xFF5A4523) else Color(0xFFF5E6C8),
    onWarningContainer = if (dark) Color(0xFFFFE5B5) else Color(0xFF493315),
    infoContainer = scheme.secondaryContainer,
    onInfoContainer = scheme.onSecondaryContainer
)

private val ElsewhereShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun scaledTypography(scale: Float): Typography {
    fun scaled(style: TextStyle) = style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = style.lineHeight * scale
    )
    return Typography(
        displayLarge = scaled(ElsewhereTypography.displayLarge),
        displayMedium = scaled(ElsewhereTypography.displayMedium),
        displaySmall = scaled(ElsewhereTypography.displaySmall),
        headlineLarge = scaled(ElsewhereTypography.headlineLarge),
        headlineMedium = scaled(ElsewhereTypography.headlineMedium),
        headlineSmall = scaled(ElsewhereTypography.headlineSmall),
        titleLarge = scaled(ElsewhereTypography.titleLarge),
        titleMedium = scaled(ElsewhereTypography.titleMedium),
        titleSmall = scaled(ElsewhereTypography.titleSmall),
        bodyLarge = scaled(ElsewhereTypography.bodyLarge),
        bodyMedium = scaled(ElsewhereTypography.bodyMedium),
        bodySmall = scaled(ElsewhereTypography.bodySmall),
        labelLarge = scaled(ElsewhereTypography.labelLarge),
        labelMedium = scaled(ElsewhereTypography.labelMedium),
        labelSmall = scaled(ElsewhereTypography.labelSmall)
    )
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearanceSettings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) {
        getDarkColorScheme(appearanceSettings.colorPalette, appearanceSettings.legacyAccentOverride)
    } else {
        getLightColorScheme(appearanceSettings.colorPalette, appearanceSettings.legacyAccentOverride)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalElsewhereSemanticColors provides semanticColors(scheme, darkTheme)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = scaledTypography(appearanceSettings.fontSize / 16f),
            shapes = ElsewhereShapes,
            content = content
        )
    }
}
