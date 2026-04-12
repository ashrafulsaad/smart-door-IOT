package com.bizarrewind.doorsentinel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Pure AMOLED black Material3 colour scheme — no dynamic colour
private val AmoledColorScheme = darkColorScheme(
    // Surfaces
    background       = Black,
    surface          = SurfaceDark,
    surfaceVariant   = CardDark,
    surfaceContainer = CardDark,

    // Primary accent (blue)
    primary          = AccentBlue,
    onPrimary        = Black,
    primaryContainer = CardDark,

    // Secondary accent (green)
    secondary        = AccentGreen,
    onSecondary      = Black,

    // Text
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,

    // Error
    error            = androidx.compose.ui.graphics.Color(0xFFFF5C5C),
    onError          = Black,
)

@Composable
fun DoorsentinelTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AmoledColorScheme,
        typography  = Typography,
        content     = content
    )
}