package com.sidpatchy.Starling

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class StarlingPaletteContrastTest {
    @Test
    fun textAndActionColorsMeetNormalTextContrastOnPaletteSurfaces() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            for (surface in listOf(palette.canvas, palette.paper, palette.paleAccent)) {
                assertNormalTextContrast(palette.ink, surface)
                assertNormalTextContrast(palette.muted, surface)
                assertNormalTextContrast(palette.errorText, surface)
                assertNormalTextContrast(palette.accent, surface)
            }
        }
    }

    private fun assertNormalTextContrast(foreground: Color, background: Color) {
        val contrast = contrastRatio(foreground, background)
        assertTrue(contrast >= 4.5, "$foreground has only $contrast:1 contrast on $background")
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }
}
