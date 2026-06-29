package com.example.androidvideostreamer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary              = Teal500,
    onPrimary            = DarkBg,
    primaryContainer     = Teal700,
    onPrimaryContainer   = Teal200,
    secondary            = DeepPurple,
    onSecondary          = OnDarkPrimary,
    secondaryContainer   = androidx.compose.ui.graphics.Color(0xFF3A2580),
    onSecondaryContainer = Purple200,
    background           = DarkBg,
    onBackground         = OnDarkPrimary,
    surface              = SurfaceDark,
    onSurface            = OnDarkPrimary,
    surfaceVariant       = CardDark,
    onSurfaceVariant     = OnDarkSecondary,
    outline              = OutlineColor,
    error                = StatusDisconnected,
    onError              = OnDarkPrimary,
)

/**
 * App-wide Material3 theme — always dark, matching the streaming-console aesthetic.
 */
@Composable
fun AndroidVideoStreamerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content
    )
}