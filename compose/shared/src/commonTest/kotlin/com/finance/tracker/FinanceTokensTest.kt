package com.finance.tracker

import androidx.compose.ui.graphics.Color
import com.finance.tracker.core.design.FinanceTokens
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.pow

class FinanceTokensTest {
    @Test
    fun semanticRolesAreDefinedForBothThemes() {
        val light = FinanceTokens.lightColors
        val dark = FinanceTokens.darkColors
        assertNotEquals(light.background, light.surface)
        assertNotEquals(dark.background, dark.surface)
        assertNotEquals(light.fieldBorder, light.focus)
        assertNotEquals(dark.fieldBorder, dark.focus)
    }

    @Test
    fun componentRolesFollowSelectedTheme() {
        assertEquals(FinanceTokens.lightColors, FinanceTokens.forTheme(false))
        assertEquals(FinanceTokens.darkColors, FinanceTokens.forTheme(true))
    }

    @Test
    fun darkDangerTextHasReadableContrast() {
        val dark = FinanceTokens.darkColors
        assertTrue(contrast(dark.danger, dark.surface) >= 4.5f)
        assertTrue(contrast(dark.danger, dark.background) >= 4.5f)
    }

    private fun contrast(first: Color, second: Color): Float {
        fun luminance(color: Color): Float {
            fun linear(channel: Float) = if (channel <= 0.04045f) channel / 12.92f
                else ((channel + 0.055f) / 1.055f).pow(2.4f)
            return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
        }
        val brighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (brighter + 0.05f) / (darker + 0.05f)
    }
}
