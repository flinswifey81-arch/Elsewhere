package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.repository.ColorPalette

/**
 * Palette-driven Elsewhere doorway. One composition, five distinct atmospheres,
 * and separate light/dark treatments, without duplicating the wordmark in bitmaps.
 * Decorative content is excluded from accessibility: the text is real Compose text.
 */
private data class DoorwayColors(
    val start: Color, val end: Color, val frame: Color,
    val foliage: Color, val text: Color, val glow: Color
)

private fun doorwayColors(palette: ColorPalette, dark: Boolean): DoorwayColors {
    val backgrounds = when (palette) {
        ColorPalette.PURPLE -> if (dark) 0xFF23132E to 0xFF35213D else 0xFFF0DCEC to 0xFFE3C8E6
        ColorPalette.OCEAN_BLUE -> if (dark) 0xFF062B3C to 0xFF0B4150 else 0xFFD0EDF3 to 0xFFEAF6F6
        ColorPalette.FOREST_GREEN -> if (dark) 0xFF0D3025 to 0xFF224432 else 0xFFE0EDDB to 0xFFF1F3D8
        ColorPalette.ROSE -> if (dark) 0xFF422139 to 0xFF603148 else 0xFFF4D4E0 to 0xFFFFE8E2
        ColorPalette.SLATE -> if (dark) 0xFF172C40 to 0xFF2E4052 else 0xFFD8E3EE to 0xFFF0F2F8
    }
    val accent = when (palette) {
        ColorPalette.PURPLE -> 0xFFC78AAA
        ColorPalette.OCEAN_BLUE -> 0xFF79C5D9
        ColorPalette.FOREST_GREEN -> 0xFF9AC49B
        ColorPalette.ROSE -> 0xFFEFABB9
        ColorPalette.SLATE -> 0xFFB2C9DD
    }
    val text = if (dark) Color(0xFFFFF1E6) else when (palette) {
        ColorPalette.PURPLE -> Color(0xFF412949)
        ColorPalette.OCEAN_BLUE -> Color(0xFF13364D)
        ColorPalette.FOREST_GREEN -> Color(0xFF253D31)
        ColorPalette.ROSE -> Color(0xFF583143)
        ColorPalette.SLATE -> Color(0xFF233A4C)
    }
    return DoorwayColors(
        start = Color(backgrounds.first), end = Color(backgrounds.second),
        frame = Color(0xFFD9B38B), foliage = Color(accent),
        text = text, glow = if (dark) Color(0xFFFFD4A0) else Color(0xFFCD8C53)
    )
}

@Composable
fun ElsewhereThemeBanner(
    palette: ColorPalette,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = doorwayColors(palette, dark)
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(126.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(colors.start, colors.end)))
            .border(1.dp, colors.foliage.copy(alpha = 0.65f), shape)
            .testTag("elsewhere_banner_" + palette.storageId)
    ) {
        val bannerWidth = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.185f
            val archLeft = w * 0.095f
            val archRight = w * 0.275f
            val archTop = h * 0.12f
            val floor = h * 0.91f

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.glow.copy(alpha = 0.62f), Color.Transparent),
                    center = Offset(cx, h * 0.67f), radius = h * 0.7f
                ),
                radius = h * 0.7f,
                center = Offset(cx, h * 0.67f)
            )
            val arch = Path().apply {
                moveTo(archLeft, floor)
                lineTo(archLeft, h * 0.36f)
                cubicTo(archLeft, -h * 0.06f, archRight, -h * 0.06f, archRight, h * 0.36f)
                lineTo(archRight, floor)
                close()
            }
            drawPath(arch, color = colors.frame.copy(alpha = 0.95f))
            val inside = Path().apply {
                moveTo(archLeft + w * 0.012f, floor - h * 0.017f)
                lineTo(archLeft + w * 0.012f, h * 0.37f)
                cubicTo(archLeft + w * 0.012f, h * 0.055f, archRight - w * 0.012f, h * 0.055f, archRight - w * 0.012f, h * 0.37f)
                lineTo(archRight - w * 0.012f, floor - h * 0.017f)
                close()
            }
            drawPath(
                inside, Brush.verticalGradient(
                    listOf(colors.start.copy(alpha = 0.9f), colors.glow.copy(alpha = 0.9f))
                )
            )
            drawPath(inside, color = colors.end.copy(alpha = 0.8f), style = Stroke(width = 3f))
            // A partly opened door keeps the original "another world is waiting" invitation.
            val door = Path().apply {
                moveTo(archLeft + w * 0.016f, floor - h * 0.045f)
                lineTo(archLeft + w * 0.016f, h * 0.39f)
                quadraticTo(cx * 0.75f, h * 0.22f, cx * 0.93f, h * 0.23f)
                lineTo(cx * 0.92f, floor - h * 0.12f)
                close()
            }
            drawPath(door, color = colors.start.copy(alpha = 0.96f))
            drawPath(door, color = colors.frame.copy(alpha = 0.85f), style = Stroke(width = 2.5f))
            drawCircle(colors.glow, radius = 2.2f, center = Offset(cx * 0.83f, h * 0.59f))
            // Threshold: a few luminous steps.
            for (step in 0..2) {
                val y = h * (0.89f + step * 0.032f)
                drawLine(colors.glow.copy(alpha = 0.8f - step * 0.13f), Offset(w * 0.07f, y), Offset(w * 0.30f, y), strokeWidth = 2.5f)
            }

            // Botanical silhouettes echo the original border art across all five palettes.
            listOf(
                0.04f to 0.84f, 0.06f to 0.67f, 0.30f to 0.81f, 0.32f to 0.62f
            ).forEachIndexed { index, (x, y) ->
                val bottom = Offset(w * x, floor)
                val top = Offset(w * (x + if (index < 2) 0.014f else -0.013f), h * y)
                drawLine(colors.foliage.copy(alpha = 0.84f), bottom, top, strokeWidth = 2.4f)
                drawOval(colors.foliage.copy(alpha = 0.76f), topLeft = top - Offset(w * 0.009f, h * 0.045f), size = Size(w * 0.018f, h * 0.075f))
            }
            // Crescent and points of light keep the night-sky motif.
            val moon = Offset(w * 0.35f, h * 0.22f)
            drawCircle(colors.text.copy(alpha = 0.92f), radius = h * 0.046f, center = moon)
            drawCircle(colors.end, radius = h * 0.041f, center = moon + Offset(h * 0.029f, -h * 0.013f))
            listOf(0.055f to 0.15f, 0.39f to 0.57f, 0.62f to 0.20f, 0.89f to 0.14f, 0.94f to 0.72f).forEach { (x,y) ->
                drawCircle(colors.text.copy(alpha = 0.58f), radius = 1.9f, center = Offset(w * x, h * y))
            }
        }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.67f)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "Elsewhere",
                color = colors.text,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                fontSize = if (bannerWidth < 380.dp) 34.sp else 42.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text(
                "Private stories, your way.",
                color = colors.text.copy(alpha = 0.92f),
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = if (bannerWidth < 380.dp) 13.sp else 15.sp,
                maxLines = 1
            )
        }
    }
}
