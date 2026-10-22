package com.yeivikas.olyze.ui.components.piano

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WhiteKey(width: Dp, pressed: Boolean, label: String, onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    // IMPORTANT: this outer Box's width must be exactly `width` — Compose's
    // Row lays out children using this measured width, and noteAt() in
    // PianoKeyboard assumes every white key occupies exactly `whiteKeyWidth`
    // px. Splitting the width into "width - 1.dp" + "padding(end = 1.dp)"
    // (the old approach) rounds each piece to whole pixels SEPARATELY, which
    // can drift the actual rendered slot by ±1px per key. Across ~50 white
    // keys that drift accumulates into several pixels, enough for a tap to
    // land on the wrong key (worse the more keys are visible / the wider the
    // keyboard). Keeping the outer width untouched and pushing the visual

    // gap to an *inner* Box (whose width is derived from the already-fixed
    // parent size) keeps rendering and hit-testing perfectly in sync.
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInParent()) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(end = 1.dp)
                // No `.shadow()` here on purpose: a real elevation shadow is
                // a separate RenderNode + blur pass that Android has to
                // rebuild any time this key recomposes. With ~75 white keys
                // on screen that adds up fast, and since most keys are
                // sitting in this "resting" state at any given moment it was
                // the single biggest cost in the keyboard, even at idle.
                // The gradient + border below already read as a raised key;
                // we get the same premium look for a fraction of the cost.
                .clip(WHITE_KEY_SHAPE)
                .background(if (pressed) WHITE_KEY_GRADIENT_PRESSED else WHITE_KEY_GRADIENT_NORMAL)
                .border(
                    width = 0.6.dp,
                    color = if (pressed) WHITE_KEY_BORDER_PRESSED else WHITE_KEY_BORDER_NORMAL,
                    shape = WHITE_KEY_SHAPE
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text  = label,
                    style = TextStyle(
                        fontFamily   = FontFamily.Monospace,
                        fontWeight   = FontWeight.SemiBold,
                        fontSize     = 8.sp,
                        letterSpacing = 0.5.sp,
                        color = if (pressed) Color.White else WHITE_KEY_LABEL_NORMAL
                    ),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
fun BlackKey(xOffset: Dp, width: Dp, pressed: Boolean, onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    Box(
        modifier = Modifier
            .absoluteOffset(x = xOffset)
            .width(width)
            // 60% of the available key-row height, resolved at layout time —
            // changing the keyboard's overall height (header drag) no longer
            // needs to touch composition at all, just re-measure.
            .fillMaxHeight(0.6f)
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInParent()) }
            // No `.shadow()`: same reasoning as WhiteKey — real elevation
            // shadows on every black key were a big chunk of the per-frame
            // cost during zoom, for a depth cue the gradient already gives.
            .clip(BLACK_KEY_SHAPE)
            .background(if (pressed) BLACK_KEY_GRADIENT_PRESSED else BLACK_KEY_GRADIENT_NORMAL)
            .border(
                width = 0.6.dp,
                color = if (pressed) BLACK_KEY_BORDER_PRESSED else BLACK_KEY_BORDER_NORMAL,
                shape = BLACK_KEY_SHAPE
            )
    ) {
        // subtle top highlight for glossy feel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.12f)
                .align(Alignment.TopCenter)
                .background(if (pressed) BLACK_KEY_HIGHLIGHT_PRESSED else BLACK_KEY_HIGHLIGHT_NORMAL)
        )
    }
}
