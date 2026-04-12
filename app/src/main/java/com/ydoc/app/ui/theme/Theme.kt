package com.ydoc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Sage700,
    onPrimary = WarmWhite,
    primaryContainer = Sage100,
    onPrimaryContainer = Sage900,
    inversePrimary = Sage200,

    secondary = Sky600,
    onSecondary = WarmWhite,
    secondaryContainer = Sky100,
    onSecondaryContainer = Sky800,

    tertiary = Amber600,
    onTertiary = Ink900,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Amber700,

    error = Rose600,
    onError = WarmWhite,
    errorContainer = Rose300,
    onErrorContainer = Rose700,

    background = WarmCanvas,
    onBackground = Ink900,

    surface = WarmSurface,
    onSurface = Ink900,
    surfaceVariant = Sand100,
    onSurfaceVariant = Ink600,

    // Material3 Expressive surface 分层
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,

    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,

    scrim = Ink900,
)

private val DarkColors = darkColorScheme(
    primary = Sage200,
    onPrimary = Sage900,
    primaryContainer = Sage700,
    onPrimaryContainer = Sage100,
    inversePrimary = Sage700,

    secondary = Sky200,
    onSecondary = Sky800,
    secondaryContainer = Sky800,
    onSecondaryContainer = Sky100,

    tertiary = Amber200,
    onTertiary = Amber700,
    tertiaryContainer = Amber700,
    onTertiaryContainer = Amber100,

    error = Rose300,
    onError = Rose700,
    errorContainer = Rose700,
    onErrorContainer = Rose300,

    background = Ink900,
    onBackground = WarmWhite,

    surface = Ink800,
    onSurface = WarmWhite,
    surfaceVariant = Ink700,
    onSurfaceVariant = Sand100,

    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,

    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,

    scrim = Ink900,
)

@Composable
fun YDocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalYdropSpacing provides YdropSpacing(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            shapes = YdropShapes,
            content = content,
        )
    }
}
