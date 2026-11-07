package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.repository.ColorPalette
import kotlin.math.abs

/**
 * The original Elsewhere illustration is the single source of truth: doorway,
 * moon, flora, wordmark and tagline are preserved for every palette.
 *
 * Palettes recolor the original violet/rose environment, but deliberately
 * preserve the warm orange-gold doorway. No remote images, network requests,
 * or theme-specific assets are required.
 */
private data class PaletteMood(
    val hue: Float,
    val saturation: Float,
    val border: Color,
    val motif: Color
)

private fun mood(palette: ColorPalette): PaletteMood = when (palette) {
    ColorPalette.PURPLE -> PaletteMood(282f, 1f, Color(0xFFC39AB9), Color(0xFFDBB0D2))
    ColorPalette.OCEAN_BLUE -> PaletteMood(191f, 0.9f, Color(0xFF8BCFE1), Color(0xFF81D8EC))
    ColorPalette.FOREST_GREEN -> PaletteMood(139f, 0.82f, Color(0xFFB6CFA4), Color(0xFFAAD5A0))
    ColorPalette.ROSE -> PaletteMood(330f, 0.8f, Color(0xFFF3BAD0), Color(0xFFF5ABC8))
    ColorPalette.SLATE -> PaletteMood(210f, 0.36f, Color(0xFFC3CFE0), Color(0xFFC2D8E8))
}

private fun recoloredDoorway(
    context: Context, palette: ColorPalette, dark: Boolean
): androidx.compose.ui.graphics.ImageBitmap? {
    // The purple dark theme always uses the unmodified original PNG.
    if (palette == ColorPalette.PURPLE && dark) return null
    val source = BitmapFactory.decodeResource(
        context.resources, R.drawable.elsewhere_logo,
        BitmapFactory.Options().apply { inScaled = false }
    ) ?: return null
    try {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val hsv = FloatArray(3)
        val target = mood(palette)
        for (index in pixels.indices) {
            val argb = pixels[index]
            val alpha = AndroidColor.alpha(argb)
            if (alpha == 0) continue

            AndroidColor.colorToHSV(argb, hsv)
            val originalHue = hsv[0]
            val isWarmDoorway = originalHue in 14f..79f &&
                hsv[1] > 0.17f && hsv[2] > 0.19f
            val isVioletOrRose = originalHue >= 240f ||
                originalHue <= 14f && hsv[1] > 0.24f

            if (!isWarmDoorway && isVioletOrRose && hsv[1] > 0.10f) {
                // Vary tint with original hue variation, preserving the hand-
                // painted texture instead of replacing the image with flat color.
                val variation = if (originalHue > 240f)
                    (originalHue - 302f) * 0.11f
                else (originalHue + 58f) * 0.11f
                hsv[0] = (target.hue + variation + 360f) % 360f
                hsv[1] = (hsv[1] * target.saturation).coerceIn(0f, 1f)
            } else if (!isWarmDoorway && palette == ColorPalette.SLATE) {
                hsv[1] *= 0.72f
            }
            if (!dark) {
                // A subtle, less saturated daytime treatment, without washing
                // out the light type on the right side of the illustration.
                hsv[1] *= 0.80f
                hsv[2] = (hsv[2] * 0.90f + 0.055f).coerceIn(0f, 1f)
            }
            pixels[index] = AndroidColor.HSVToColor(alpha, hsv)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result.asImageBitmap()
    } finally {
        source.recycle()
    }
}

/**
 * Each theme retains the original editorial artwork, enhanced by subtle,
 * palette-specific foreground accents. The art is scaled in the exact aspect
 * ratio used by the original Home banner.
 */
@Composable
fun ElsewhereThemeBanner(
    palette: ColorPalette,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = mood(palette)
    val themedBitmap = remember(context, palette, dark) {
        recoloredDoorway(context, palette, dark)
    }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier.fillMaxWidth().height(126.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border.copy(alpha = 0.75f), shape)
            .testTag("elsewhere_banner_" + palette.storageId)
            .semantics {
                contentDescription = "Elsewhere. Private stories, your way."
            }
    ) {
        if (themedBitmap == null) {
            Image(
                painter = painterResource(R.drawable.elsewhere_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } else {
            Image(
                bitmap = themedBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }

        if (palette != ColorPalette.PURPLE) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val accent = colors.motif.copy(alpha = if (dark) 0.66f else 0.45f)
                when (palette) {
                    ColorPalette.OCEAN_BLUE -> {
                        // Small moonlit ripples in the water at the threshold.
                        for (n in 0 until 3) {
                            val path = Path()
                            val y = h * (0.83f + 0.05f * n)
                            path.moveTo(w * 0.025f, y)
                            path.cubicTo(w * 0.080f, y - h * 0.024f,
                                w * 0.125f, y + h * 0.024f, w * 0.18f, y)
                            path.cubicTo(w * 0.23f, y - h * 0.02f,
                                w * 0.29f, y + h * 0.02f, w * 0.35f, y)
                            drawPath(path, accent, style = Stroke(width = 1.8f))
                        }
                    }
                    ColorPalette.FOREST_GREEN -> {
                        // Leaf clusters grow along the left side of the doorway.
                        for (n in 0 until 7) {
                            val x = w * (0.055f + 0.016f * (n % 3))
                            val y = h * (0.32f + 0.088f * n)
                            drawOval(accent, topLeft = Offset(x, y),
                                size = androidx.compose.ui.geometry.Size(9f, 4f))
                            drawOval(accent, topLeft = Offset(x + 11f, y + 8f),
                                size = androidx.compose.ui.geometry.Size(5f, 9f))
                        }
                    }
                    ColorPalette.ROSE -> {
                        // Falling blossom petals rather than generic stars.
                        for (n in 0 until 9) {
                            val x = w * (0.33f + (n % 4) * 0.12f)
                            val y = h * (0.15f + (n / 4) * 0.25f)
                            drawOval(accent, topLeft = Offset(x, y),
                                size = androidx.compose.ui.geometry.Size(5f, 8f))
                        }
                    }
                    ColorPalette.SLATE -> {
                        // Icy starlight and little glints in the stone.
                        for (n in 0 until 7) {
                            val x = w * (0.39f + (n % 4) * 0.16f)
                            val y = h * (0.18f + (n / 4) * 0.23f)
                            drawLine(accent, Offset(x - 3f, y), Offset(x + 3f, y), 1.4f)
                            drawLine(accent, Offset(x, y - 3f), Offset(x, y + 3f), 1.4f)
                        }
                    }
                    ColorPalette.PURPLE -> Unit
                }
            }
        }
    }
}
