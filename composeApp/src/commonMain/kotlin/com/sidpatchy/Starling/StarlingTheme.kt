package com.sidpatchy.Starling

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class StarlingPalette(
    val ink: Color,
    val canvas: Color,
    val paper: Color,
    val muted: Color,
    val errorText: Color,
    val line: Color,
    val accent: Color,
    val paleAccent: Color,
)

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

internal val LightPalette = StarlingPalette(
    ink = Color(0xFF383A42),
    canvas = Color(0xFFFAFAFA),
    paper = Color.White,
    muted = Color(0xFF656770),
    errorText = Color(0xFFB63838),
    line = Color(0xFFE5E5E6),
    accent = Color(0xFF7056C5),
    paleAccent = Color(0xFFEFEBFA),
)

internal val DarkPalette = StarlingPalette(
    ink = Color(0xFFABB2BF),
    canvas = Color(0xFF282C34),
    paper = Color(0xFF21252B),
    muted = Color(0xFFA0A6B2),
    errorText = Color(0xFFF08080),
    line = Color(0xFF3E4451),
    accent = Color(0xFFB4A0F5),
    paleAccent = Color(0xFF393248),
)

internal val LocalStarlingPalette = staticCompositionLocalOf { LightPalette }
internal val LocalTimestampTimeZonePreference = staticCompositionLocalOf {
    TimestampTimeZonePreference.SYSTEM
}
internal val LocalReducedMotion = staticCompositionLocalOf { false }

internal val ReducedMotion: Boolean @Composable get() = LocalReducedMotion.current

internal val Ink: Color @Composable get() = LocalStarlingPalette.current.ink
internal val Canvas: Color @Composable get() = LocalStarlingPalette.current.canvas
internal val Paper: Color @Composable get() = LocalStarlingPalette.current.paper
internal val Muted: Color @Composable get() = LocalStarlingPalette.current.muted
internal val ErrorText: Color @Composable get() = LocalStarlingPalette.current.errorText
internal val Line: Color @Composable get() = LocalStarlingPalette.current.line
internal val Accent: Color @Composable get() = LocalStarlingPalette.current.accent
internal val PaleAccent: Color @Composable get() = LocalStarlingPalette.current.paleAccent

@Composable
internal fun StarlingTheme(
    preference: ThemePreference,
    timestampTimeZonePreference: TimestampTimeZonePreference,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDarkPalette = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val palette = when (preference) {
        ThemePreference.LIGHT -> LightPalette
        ThemePreference.DARK -> DarkPalette
        ThemePreference.SYSTEM -> if (useDarkPalette) DarkPalette else LightPalette
    }
    val colorScheme = if (useDarkPalette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.canvas,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.paper,
            onSurface = palette.ink,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.paper,
            onSurface = palette.ink,
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalStarlingPalette provides palette,
            LocalTimestampTimeZonePreference provides timestampTimeZonePreference,
            LocalReducedMotion provides reducedMotion,
            content = content,
        )
    }
}
