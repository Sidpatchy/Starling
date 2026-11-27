package com.sidpatchy.Starling

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarlingPaletteContrastTest {
    private val palettes = PalettePreference.entries.flatMap { preference ->
        listOf(starlingPalette(preference, dark = false), starlingPalette(preference, dark = true))
    }

    @Test
    fun irisLightUsesCoolSurfacesAndLavenderAccents() {
        val light = starlingPalette(PalettePreference.IRIS, dark = false)
        assertEquals(Color(0xFFFDFCFF), light.canvas)
        assertEquals(Color(0xFFF0EDF7), light.chrome)
        assertEquals(Color(0xFFEAE2F8), light.paleAccent)
        assertEquals(Color(0xFF62458C), light.accent)
    }

    @Test
    fun irisDarkKeepsRosePineSurfaces() {
        val dark = starlingPalette(PalettePreference.IRIS, dark = true)
        assertEquals(Color(0xFF191724), dark.canvas)
        assertEquals(Color(0xFF1F1D2E), dark.chrome)
        assertEquals(Color(0xFF26233A), dark.paleAccent)
        assertEquals(Color(0xFFE0DEF4), dark.ink)
        assertEquals(Color(0xFFC4A7E7), dark.accent)
    }

    @Test
    fun textAndActionColorsMeetNormalTextContrastOnPaletteSurfaces() {
        for (palette in palettes) {
            for (surface in listOf(palette.canvas, palette.paper, palette.chrome, palette.paleAccent)) {
                assertNormalTextContrast(palette.ink, surface)
                assertNormalTextContrast(palette.muted, surface)
                assertNormalTextContrast(palette.errorText, surface)
                assertNormalTextContrast(palette.accent, surface)
            }
        }
    }

    @Test
    fun selectedTextStaysReadableOnChatAndInputSurfaces() {
        for (palette in palettes) {
            for (surface in listOf(palette.canvas, palette.paper, palette.paleAccent)) {
                assertNormalTextContrast(palette.ink, palette.selectionHighlight.compositeOver(surface))
            }
        }
    }

    @Test
    fun noticeAndErrorTextAndIconsStayVisible() {
        for (palette in palettes) {
            assertNormalTextContrast(palette.errorText, palette.errorBackground.compositeOver(palette.canvas))
            assertNormalTextContrast(palette.noticeLabel, palette.noticeBackground)
            assertNormalTextContrast(palette.noticeText, palette.noticeBackground)
            for (surface in listOf(palette.canvas, palette.chrome, palette.paleAccent)) {
                assertTrue(contrastRatio(palette.errorIndicator, surface) >= 3.0)
            }
        }
    }

    @Test
    fun ircNicknamesStayReadableOnChatAndHeaderSurfaces() {
        for (palette in palettes) {
            for (hue in 0 until 360) {
                val foreground = ircNickTextColorForHue(hue.toFloat(), darkTheme = palette.isDark)
                for (surface in listOf(palette.canvas, palette.paper, palette.chrome, palette.paleAccent)) {
                    assertNormalTextContrast(foreground, surface)
                }
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
