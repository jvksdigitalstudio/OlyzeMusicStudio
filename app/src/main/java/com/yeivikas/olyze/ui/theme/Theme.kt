package com.yeivikas.olyze.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Design tokens matching the HTML reference ──
val FlDark        = Color(0xFF050508)
val FlPanel       = Color(0xFF0D0D14)
val FlSurface     = Color(0xFF13131F)
val FlBorder      = Color(0xFF2A1F3D)
val FlPurple      = Color(0xFFa855f7)
val FlPurpleDim   = Color(0xFF7c3aed)
val FlPurpleLight = Color(0xFFc084fc)
val FlText        = Color(0xFFE8E0F8)
val FlMuted       = Color(0xFF5A4D72)
val FlGreen       = Color(0xFF00FF88)
val FlRed         = Color(0xFFFF3366)
val KeyWhite      = Color(0xFFF0ECFF)
val KeyBlack      = Color(0xFF0D0918)

private val DarkColorScheme = darkColorScheme(
    primary        = FlPurple,
    onPrimary      = Color.White,
    secondary      = FlPurpleDim,
    onSecondary    = Color.White,
    tertiary       = FlPurpleLight,
    background     = FlDark,
    onBackground   = FlText,
    surface        = FlSurface,
    onSurface      = FlText,
    surfaceVariant = FlPanel,
    outline        = FlBorder,
    error          = FlRed,
)

@Composable
fun OlyzeMusicStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content
    )
}
