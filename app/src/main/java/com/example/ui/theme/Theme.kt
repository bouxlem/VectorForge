package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VectorForgeColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF032830),
    primaryContainer = Color(0xFF083344),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = VioletSecondary,
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF3B0764),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = EmeraldSuccess,
    onTertiary = Color(0xFF022C22),
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = RoseError,
    onError = Color.White
)

@Composable
fun VectorForgeTheme(
    content: @Composable () -> Unit
) {
    // VectorForge always defaults to its curated precision dark studio theme for vector fidelity
    MaterialTheme(
        colorScheme = VectorForgeColorScheme,
        typography = Typography,
        content = content
    )
}
