package com.yeivikas.olyze.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.ui.theme.*

// ── Pre-built, allocation-free visual constants for piano keys ──
// Brush/Shape/Color objects built here ONCE at class-load time instead of
// inside WhiteKey()/BlackKey() (which used to allocate a fresh Brush + List
// on every single recomposition of every key — up to ~120 allocations per
// frame during drag/zoom, which is exactly what was feeding the garbage
// collector and showing up as stutter).
internal val WHITE_KEY_SHAPE = RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
internal val WHITE_KEY_GRADIENT_NORMAL = Brush.verticalGradient(
    listOf(Color(0xFFFFFFFF), Color(0xFFF3EEFF), Color(0xFFE2D6F7), Color(0xFFD2C2EE))
)
internal val WHITE_KEY_GRADIENT_PRESSED = Brush.verticalGradient(
    listOf(FlPurpleLight, FlPurple, FlPurpleDim)
)
internal val WHITE_KEY_BORDER_NORMAL  = Color(0xFFBBA8DD).copy(alpha = 0.5f)
internal val WHITE_KEY_BORDER_PRESSED = FlPurpleLight.copy(alpha = 0.8f)
internal val WHITE_KEY_LABEL_NORMAL   = Color(0xFF6B4FA8).copy(alpha = 0.85f)

internal val BLACK_KEY_SHAPE = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
internal val BLACK_KEY_GRADIENT_NORMAL = Brush.verticalGradient(
    listOf(Color(0xFF2A1840), Color(0xFF160B26), Color(0xFF0A0514), Color(0xFF050208))
)
internal val BLACK_KEY_GRADIENT_PRESSED = Brush.verticalGradient(
    listOf(FlPurpleDim, Color(0xFF4C1D95), Color(0xFF3B1670))
)
internal val BLACK_KEY_BORDER_NORMAL    = Color(0xFF4A2D78).copy(alpha = 0.6f)
internal val BLACK_KEY_BORDER_PRESSED   = FlPurpleLight.copy(alpha = 0.9f)
internal val BLACK_KEY_HIGHLIGHT_NORMAL = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
)
internal val BLACK_KEY_HIGHLIGHT_PRESSED = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)
)
