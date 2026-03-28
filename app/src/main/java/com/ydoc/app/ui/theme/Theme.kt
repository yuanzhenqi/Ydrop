package com.ydoc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Sage700,
    onPrimary = WarmWhite,
    primaryContainer = Sage200,
    onPrimaryContainer = Ink900,
    secondary = Sky600,
    onSecondary = WarmWhite,
    secondaryContainer = Sky100,
    onSecondaryContainer = Ink900,
    tertiary = Amber600,
    onTertiary = Ink900,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Ink900,
    background = WarmCanvas,
    onBackground = Ink900,
    surface = WarmWhite,
    onSurface = Ink900,
    surfaceVariant = Sand100,
    onSurfaceVariant = Ink600,
    error = Rose600,
    onError = WarmWhite,
)

private val DarkColors = darkColorScheme(
    primary = Sage200,
    onPrimary = Ink900,
    primaryContainer = Sage700,
    onPrimaryContainer = WarmWhite,
    secondary = Sky200,
    onSecondary = Ink900,
    secondaryContainer = Sky600,
    onSecondaryContainer = WarmWhite,
    tertiary = Amber200,
    onTertiary = Ink900,
    tertiaryContainer = Amber600,
    onTertiaryContainer = Ink900,
    background = Ink900,
    onBackground = WarmWhite,
    surface = Ink800,
    onSurface = WarmWhite,
    surfaceVariant = Ink700,
    onSurfaceVariant = Sand100,
    error = Rose300,
    onError = Ink900,
)

@Composable
fun YDocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
