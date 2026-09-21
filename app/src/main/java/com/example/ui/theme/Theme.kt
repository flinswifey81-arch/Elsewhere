package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.domain.repository.AppearanceSettings

private fun darkAtmosphereAccent(accentName: String) = when (accentName) {
    "Muted Rose" -> AccentMutedRoseDark
    "Sage" -> AccentSageDark
    "Soft Blue" -> AccentSoftBlueDark
    "Warm Gold" -> AccentWarmGoldDark
    "Plum" -> AccentPlumDark
    else -> AccentDustyLavenderDark
}

private fun lightAtmosphereAccent(accentName: String) = when (accentName) {
    "Muted Rose" -> AccentMutedRoseLight
    "Sage" -> AccentSageLight
    "Soft Blue" -> AccentSoftBlueLight
    "Warm Gold" -> AccentWarmGoldLight
    "Plum" -> AccentPlumLight
    else -> AccentDustyLavenderLight
}

fun getDarkColorScheme(accentName: String) = darkColorScheme(
    primary = Doorlight,
    onPrimary = NightInk,
    primaryContainer = DeepIris,
    onPrimaryContainer = MoonCream,
    secondary = MutedRose,
    onSecondary = NightInk,
    secondaryContainer = TwilightPlum,
    onSecondaryContainer = MoonCream,
    tertiary = darkAtmosphereAccent(accentName),
    onTertiary = NightInk,
    tertiaryContainer = DeepEggplant,
    onTertiaryContainer = MoonCream,
    background = NightInk,
    onBackground = MoonCream,
    surface = CharcoalPlum,
    onSurface = MoonCream,
    surfaceVariant = DeepEggplant,
    onSurfaceVariant = RoseBlush,
    outline = LavenderDusk,
    outlineVariant = TwilightPlum,
    inverseSurface = MoonCream,
    inverseOnSurface = NightInk,
    inversePrimary = DeepIris,
    surfaceTint = Doorlight
)

fun getLightColorScheme(accentName: String) = lightColorScheme(
    primary = DeepIris,
    onPrimary = Color.White,
    primaryContainer = RoseBlush,
    onPrimaryContainer = NightInk,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = NightInk,
    tertiary = lightAtmosphereAccent(accentName),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DFC5),
    onTertiaryContainer = NightInk,
    background = Parchment,
    onBackground = NightInk,
    surface = WarmWhite,
    onSurface = CharcoalPlum,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = DeepEggplant,
    outline = LavenderDusk,
    outlineVariant = LightOutlineVariant,
    inverseSurface = CharcoalPlum,
    inverseOnSurface = MoonCream,
    inversePrimary = Doorlight,
    surfaceTint = DeepIris
)

private val ElsewhereShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearanceSettings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        getDarkColorScheme(appearanceSettings.accentPreset)
    } else {
        getLightColorScheme(appearanceSettings.accentPreset)
    }

    val scale = appearanceSettings.fontSize / 16f

    fun scaleStyle(style: TextStyle) = style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = style.lineHeight * scale
    )

    val scaledTypography = Typography(
        displayLarge = scaleStyle(ElsewhereTypography.displayLarge),
        headlineMedium = scaleStyle(ElsewhereTypography.headlineMedium),
        titleLarge = scaleStyle(ElsewhereTypography.titleLarge),
        titleMedium = scaleStyle(ElsewhereTypography.titleMedium),
        bodyLarge = scaleStyle(ElsewhereTypography.bodyLarge),
        bodyMedium = scaleStyle(ElsewhereTypography.bodyMedium),
        labelLarge = scaleStyle(ElsewhereTypography.labelLarge)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        shapes = ElsewhereShapes,
        content = content
    )
}
