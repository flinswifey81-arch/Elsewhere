package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.repository.ColorPalette
import com.example.ui.components.ElsewhereThemeBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElsewhereThemeBannerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allFiveStablePaletteIdsHaveMatchingBrandedBanners() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ColorPalette.selectable.forEach { palette ->
                        ElsewhereThemeBanner(palette = palette)
                    }
                }
            }
        }
        ColorPalette.selectable.forEach { palette ->
            composeRule.onNodeWithTag("elsewhere_banner_" + palette.storageId).assertExists()
        }
        // The illustrated wordmark and tagline are part of the original PNG.
        // Screen readers receive their text through the banner semantics.
        composeRule.onAllNodesWithContentDescription(
            "Elsewhere. Private stories, your way."
        ).assertCountEquals(5)
    }

    @Test
    fun poeticPaletteNamesDoNotChangePersistedIdentifiers() {
        val names = ColorPalette.selectable.map { it.themeName }
        org.junit.Assert.assertEquals(
            listOf("The Original", "Moonlit Waters", "Enchanted Woodland", "Twilight Garden", "Starlit Stone"),
            names
        )
        org.junit.Assert.assertEquals(ColorPalette.OCEAN_BLUE, ColorPalette.fromStorage("ocean_blue"))
        org.junit.Assert.assertEquals(ColorPalette.OCEAN_BLUE, ColorPalette.fromStorage("Ocean Blue"))
    }
}
