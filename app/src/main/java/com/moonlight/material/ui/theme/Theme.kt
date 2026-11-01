package com.moonlight.material.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = DeepNavy,
    primaryContainer = CardNavy,
    onPrimaryContainer = TextPrimary,
    secondary = PurpleAccent,
    onSecondary = DeepNavy,
    background = DeepNavy,
    onBackground = TextPrimary,
    surface = MidnightBlue,
    onSurface = TextPrimary,
    surfaceVariant = CardNavy,
    onSurfaceVariant = TextSecondary,
    error = RoseError
)

private val LightColorScheme = lightColorScheme(
    primary = CyanAccent,
    onPrimary = TextPrimary,
    primaryContainer = CardNavy,
    onPrimaryContainer = TextPrimary,
    secondary = PurpleAccent,
    background = DeepNavy,
    surface = MidnightBlue
)

@Composable
fun MoonlightTheme(
    darkTheme: Boolean = true, // Default to dark immersion for game streaming
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = DeepNavy.toArgb()
                window.navigationBarColor = DeepNavy.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
