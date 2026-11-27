package com.sidpatchy.Starling

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class StarlingPalette(
    val isDark: Boolean,
    val ink: Color,
    val canvas: Color,
    val paper: Color,
    val chrome: Color,
    val muted: Color,
    val errorText: Color,
    val line: Color,
    val accent: Color,
    val paleAccent: Color,
    val selectionHighlight: Color = accent.copy(alpha = 0.28f),
    val errorIndicator: Color = errorText,
    val errorBackground: Color = Color(0x22C44545),
    val noticeBackground: Color = if (isDark) Color(0xFF383226) else Color(0xFFFFF7E8),
    val noticeLabel: Color = if (isDark) Color(0xFFE1B86A) else Color(0xFF986520),
    val noticeText: Color = if (isDark) Color(0xFFC6B895) else Color(0xFF826B4B),
)

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class PalettePreference(val label: String, internal val storageKey: String) {
    CLASSIC("Classic", "SLATE"),
    IRIS("Iris", "IRIS"),
    ;

    companion object {
        internal fun fromStorage(value: String?): PalettePreference = when (value) {
            // Earlier builds used both keys for the Rosé Pine–based palette.
            "CLASSIC", "ROSE_PINE" -> IRIS
            else -> entries.firstOrNull { it.storageKey == value } ?: IRIS
        }
    }
}

internal enum class StarlingUiStyle {
    DEFAULT,
    IOS,
}

internal val LocalStarlingUiStyle = staticCompositionLocalOf { StarlingUiStyle.DEFAULT }

private val ClassicLight = StarlingPalette(
    isDark = false,
    ink = Color(0xFF282B33),
    canvas = Color(0xFFFAFBFC),
    paper = Color(0xFFF0F2F5),
    chrome = Color(0xFFF0F2F5),
    muted = Color(0xFF5A606D),
    errorText = Color(0xFFA53443),
    line = Color(0xFFD8DCE4),
    accent = Color(0xFF6246A7),
    paleAccent = Color(0xFFEEEBF6),
)

private val ClassicDark = StarlingPalette(
    isDark = true,
    ink = Color(0xFFE4E7EE),
    canvas = Color(0xFF282C34),
    paper = Color(0xFF21252B),
    chrome = Color(0xFF21252B),
    muted = Color(0xFFA9B1BF),
    errorText = Color(0xFFFFA0AB),
    line = Color(0xFF3E4451),
    accent = Color(0xFFC0ADF8),
    paleAccent = Color(0xFF363343),
)

private val IrisLightAccent = Color(0xFF62458C)
private val IrisLightMuted = Color(0xFF5C5568)
private val IrisLightTint = Color(0xFFEAE2F8)

private val IrisLight = StarlingPalette(
    isDark = false,
    ink = Color(0xFF292435),
    canvas = Color(0xFFFDFCFF),
    paper = Color(0xFFF6F4FA),
    chrome = Color(0xFFF0EDF7),
    muted = IrisLightMuted,
    errorText = Color(0xFFA53452),
    line = Color(0xFFDCD4E6),
    accent = IrisLightAccent,
    paleAccent = IrisLightTint,
    selectionHighlight = Color(0xFFDDD0F5),
    errorBackground = Color(0xFFFBE8EE),
    noticeBackground = IrisLightTint,
    noticeLabel = IrisLightAccent,
    noticeText = IrisLightMuted,
)

// Dark surfaces are from OpenCode TUI's Rosé Pine (v2.4.1,
// packages/tui/src/theme/assets/rosepine.json).
private val IrisDarkAccent = Color(0xFFC4A7E7)

private val IrisDark = StarlingPalette(
    isDark = true,
    ink = Color(0xFFE0DEF4),
    canvas = Color(0xFF191724),
    paper = Color(0xFF1F1D2E),
    chrome = Color(0xFF1F1D2E),
    muted = Color(0xFF908CAA), // subtle
    errorText = Color(0xFFEB6F92),
    line = Color(0xFF403D52),
    accent = IrisDarkAccent,
    paleAccent = Color(0xFF26233A),
    selectionHighlight = Color(0xFF403D52),
    errorBackground = Color(0xFF3A1F2D),
    noticeBackground = Color(0xFF26233A),
    noticeLabel = IrisDarkAccent,
    noticeText = Color(0xFF908CAA),
)

internal fun starlingPalette(preference: PalettePreference, dark: Boolean): StarlingPalette = when (preference) {
    PalettePreference.CLASSIC -> if (dark) ClassicDark else ClassicLight
    PalettePreference.IRIS -> if (dark) IrisDark else IrisLight
}

internal val LocalStarlingPalette = staticCompositionLocalOf { IrisLight }
internal val LocalTimestampTimeZonePreference = staticCompositionLocalOf {
    TimestampTimeZonePreference.SYSTEM
}
internal val LocalReducedMotion = staticCompositionLocalOf { false }

internal val ReducedMotion: Boolean @Composable get() = LocalReducedMotion.current

internal val Ink: Color @Composable get() = LocalStarlingPalette.current.ink
internal val Canvas: Color @Composable get() = LocalStarlingPalette.current.canvas
internal val Paper: Color @Composable get() = LocalStarlingPalette.current.paper
internal val Chrome: Color @Composable get() = LocalStarlingPalette.current.chrome
internal val Muted: Color @Composable get() = LocalStarlingPalette.current.muted
internal val ErrorText: Color @Composable get() = LocalStarlingPalette.current.errorText
internal val ErrorIndicator: Color @Composable get() = LocalStarlingPalette.current.errorIndicator
internal val Line: Color @Composable get() = LocalStarlingPalette.current.line
internal val Accent: Color @Composable get() = LocalStarlingPalette.current.accent
internal val PaleAccent: Color @Composable get() = LocalStarlingPalette.current.paleAccent

@Composable
internal fun StarlingTheme(
    preference: ThemePreference,
    palettePreference: PalettePreference,
    timestampTimeZonePreference: TimestampTimeZonePreference,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDarkPalette = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val palette = starlingPalette(palettePreference, useDarkPalette)
    val defaults = if (useDarkPalette) darkColorScheme() else lightColorScheme()
    val colorScheme = defaults.copy(
        primary = palette.accent,
        onPrimary = palette.canvas,
        primaryContainer = palette.paleAccent,
        onPrimaryContainer = palette.ink,
        secondaryContainer = palette.paleAccent,
        onSecondaryContainer = palette.ink,
        background = palette.canvas,
        onBackground = palette.ink,
        surface = palette.paper,
        onSurface = palette.ink,
        surfaceContainer = palette.paper,
        surfaceContainerHigh = palette.canvas,
        surfaceContainerHighest = palette.paleAccent,
        onSurfaceVariant = palette.muted,
        outline = palette.muted,
        outlineVariant = palette.line,
        error = palette.errorText,
    )

    MaterialTheme(colorScheme = colorScheme, typography = starlingTypography()) {
        CompositionLocalProvider(
            LocalStarlingPalette provides palette,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = palette.accent,
                backgroundColor = palette.selectionHighlight,
            ),
            LocalTimestampTimeZonePreference provides timestampTimeZonePreference,
            LocalReducedMotion provides reducedMotion,
            content = content,
        )
    }
}
