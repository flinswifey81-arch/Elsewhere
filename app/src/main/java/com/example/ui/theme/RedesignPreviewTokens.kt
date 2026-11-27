package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.example.domain.repository.ColorPalette

/** Final handoff semantic colors, used only in the independently installable redesign Lab. */
internal object RedesignPreviewTokens {
    private data class Palette(val bg: Long, val surface: Long, val raised: Long, val text: Long,
        val secondary: Long, val accent: Long, val border: Long, val onAccent: Long)
    private fun tones(palette: ColorPalette, dark: Boolean): Palette? = when (palette) {
        ColorPalette.PURPLE -> if (dark) Palette(0xFF21182D,0xFF30243E,0xFF473354,0xFFF6E9F0,0xFFCBBAD0,0xFFE4B7DC,0xFF574264,0xFF21182D)
            else Palette(0xFFFBF4FC,0xFFFFFAFD,0xFFF0E4F2,0xFF39263E,0xFF705C76,0xFF78518E,0xFFD9C7DD,0xFFFFFFFF)
        ColorPalette.OCEAN_BLUE -> if (dark) Palette(0xFF0C1921,0xFF162832,0xFF203945,0xFFF3E8E0,0xFFA5BAC3,0xFFB5DDE6,0xFF304954,0xFF0C1921)
            else Palette(0xFFEDF5F6,0xFFFBFDFD,0xFFDCEBF0,0xFF203A46,0xFF58727E,0xFF285D73,0xFFC7DCE3,0xFFFFFFFF)
        ColorPalette.FOREST_GREEN -> if (dark) Palette(0xFF14231D,0xFF20332A,0xFF2E4939,0xFFF4EFDF,0xFFB7C6AF,0xFFC1DDBD,0xFF3C5648,0xFF14231D)
            else Palette(0xFFF0F6ED,0xFFFBFDF8,0xFFE1ECDD,0xFF253B2D,0xFF5E7462,0xFF3D7454,0xFFC9DBC8,0xFFFFFFFF)
        ColorPalette.ROSE -> if (dark) Palette(0xFF2A1925,0xFF382734,0xFF503849,0xFFF8E9ED,0xFFCDB5C2,0xFFEAC2D5,0xFF604654,0xFF2A1925)
            else Palette(0xFFFAF0F3,0xFFFFF9FB,0xFFF4E2E9,0xFF492C3C,0xFF856475,0xFF985C79,0xFFE7CCD8,0xFFFFFFFF)
        ColorPalette.SLATE -> null // No revised tokens in the pack: keep the installed family.
    }

    fun apply(base: ColorScheme, palette: ColorPalette, dark: Boolean): ColorScheme {
        val t = tones(palette, dark) ?: return base
        val decoration = Color(t.border)
        return base.copy(
            background = Color(t.bg), onBackground = Color(t.text),
            surface = Color(t.surface), onSurface = Color(t.text),
            surfaceContainerLowest = Color(t.bg), surfaceContainerLow = Color(t.surface),
            surfaceContainer = Color(t.surface), surfaceContainerHigh = Color(t.raised),
            surfaceContainerHighest = Color(t.raised), surfaceDim = Color(t.bg), surfaceBright = Color(t.surface),
            surfaceVariant = Color(t.raised),
            // Existing stronger text/control colors are retained where the muted design tokens
            // would not meet the handoff's contrast targets on a raised surface.
            onSurfaceVariant = accessiblePair(Color(t.secondary), Color(t.raised), Color(t.text)),
            primary = Color(t.accent), onPrimary = Color(t.onAccent),
            primaryContainer = Color(t.raised), onPrimaryContainer = Color(t.text),
            secondaryContainer = Color(t.raised), onSecondaryContainer = Color(t.text),
            outline = accessiblePair(base.outline, Color(t.surface), Color(t.secondary), 3.0),
            outlineVariant = decoration, surfaceTint = Color(t.accent)
        )
    }

    private fun accessiblePair(desired: Color, bg: Color, fallback: Color, minimum: Double = 4.5): Color {
        fun lum(c: Color): Double {
            fun f(v: Float): Double = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
        }
        val a = lum(desired); val b = lum(bg)
        return if ((maxOf(a,b)+0.05)/(minOf(a,b)+0.05) >= minimum) desired else fallback
    }
}
