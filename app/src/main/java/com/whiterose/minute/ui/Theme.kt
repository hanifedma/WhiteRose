package com.whiterose.minute.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

// A white bloom on deep crimson, on the black an OLED watch renders for free.
val Rose = Color(0xFFFF6B85)
val RoseDim = Color(0xFFC24A61)
val RoseDeep = Color(0xFF61122A)
val RoseShadow = Color(0xFF2A0710)
val Blush = Color(0xFFFFD9DF)
val Ink = Color(0xFF000000)
val Ash = Color(0xFF191315)
val AshHigh = Color(0xFF241B1E)
val AshLow = Color(0xFF100C0D)
val Chalk = Color(0xFFF5ECEE)
val Muted = Color(0xFFB6A3A8)

private val WhiteRoseColors = ColorScheme(
    primary = Rose,
    primaryDim = RoseDim,
    primaryContainer = RoseDeep,
    onPrimary = Color(0xFF3B0512),
    onPrimaryContainer = Blush,
    secondary = Blush,
    secondaryDim = Color(0xFFCCA9B1),
    secondaryContainer = Color(0xFF3A2A2E),
    onSecondary = Color(0xFF2B1116),
    onSecondaryContainer = Blush,
    surfaceContainerLow = AshLow,
    surfaceContainer = Ash,
    surfaceContainerHigh = AshHigh,
    onSurface = Chalk,
    onSurfaceVariant = Muted,
    outline = Color(0xFF755D63),
    outlineVariant = Color(0xFF3D3033),
    background = Ink,
    onBackground = Chalk,
)

@Composable
fun WhiteRoseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WhiteRoseColors, content = content)
}
