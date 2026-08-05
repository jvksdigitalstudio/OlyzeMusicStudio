package com.yeivikas.olyze.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeivikas.olyze.ui.theme.*
import kotlinx.coroutines.launch

private val BLACK_SEMITONES = setOf(1, 3, 6, 8, 10)
private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
private val HEADER_HEIGHT = 34.dp

// ── Pre-built, allocation-free visual constants for piano keys ──
// Brush/Shape/Color objects built here ONCE at class-load time instead of
// inside WhiteKey()/BlackKey() (which used to allocate a fresh Brush + List
// on every single recomposition of every key — up to ~120 allocations per
// frame during drag/zoom, which is exactly what was feeding the garbage
// collector and showing up as stutter).
private val WHITE_KEY_SHAPE = RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
private val WHITE_KEY_GRADIENT_NORMAL = Brush.verticalGradient(
    listOf(Color(0xFFFFFFFF), Color(0xFFF3EEFF), Color(0xFFE2D6F7), Color(0xFFD2C2EE))
)
private val WHITE_KEY_GRADIENT_PRESSED = Brush.verticalGradient(
    listOf(FlPurpleLight, FlPurple, FlPurpleDim)
)
private val WHITE_KEY_BORDER_NORMAL  = Color(0xFFBBA8DD).copy(alpha = 0.5f)
private val WHITE_KEY_BORDER_PRESSED = FlPurpleLight.copy(alpha = 0.8f)
private val WHITE_KEY_LABEL_NORMAL   = Color(0xFF6B4FA8).copy(alpha = 0.85f)

private val BLACK_KEY_SHAPE = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
private val BLACK_KEY_GRADIENT_NORMAL = Brush.verticalGradient(
    listOf(Color(0xFF2A1840), Color(0xFF160B26), Color(0xFF0A0514), Color(0xFF050208))
)
private val BLACK_KEY_GRADIENT_PRESSED = Brush.verticalGradient(
    listOf(FlPurpleDim, Color(0xFF4C1D95), Color(0xFF3B1670))
)
private val BLACK_KEY_BORDER_NORMAL    = Color(0xFF4A2D78).copy(alpha = 0.6f)
private val BLACK_KEY_BORDER_PRESSED   = FlPurpleLight.copy(alpha = 0.9f)
private val BLACK_KEY_HIGHLIGHT_NORMAL = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
)
private val BLACK_KEY_HIGHLIGHT_PRESSED = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)
)

data class KeyInfo(
    val midiNote: Int,
    val isBlack: Boolean,
    val octave: Int,
    val semitone: Int,
    val noteName: String,
)

private val ALL_KEYS: List<KeyInfo> = (0..119).map { note ->
    val semi = note % 12
    val oct  = (note / 12) - 1
    KeyInfo(
        midiNote = note,
        isBlack  = semi in BLACK_SEMITONES,
        octave   = oct,
        semitone = semi,
        noteName = "${NOTE_NAMES[semi]}$oct"
    )
}

@Composable
fun PianoKeyboard(
    modifier: Modifier = Modifier,
    activeNotes: Set<Int> = emptySet(),
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onClose: () -> Unit = {},
    expanded: Boolean = true,
    initialKeyWidth: Dp = 42.dp,
    minKeyWidth: Dp = 22.dp,
    maxKeyWidth: Dp = 72.dp,
    initialHeight: Dp = 220.dp,
    minHeight: Dp = HEADER_HEIGHT,
    maxHeight: Dp = 480.dp,
) {
    val density     = LocalDensity.current
    val whiteKeys   = remember { ALL_KEYS.filter { !it.isBlack } }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val pointerMap = remember { mutableStateMapOf<Long, Int>() }

    // ── Resizable height (1-finger drag on header) — grows up until it meets
    //    the app header above, shrinks down until only this header remains. ──
    var keyboardHeight by remember { mutableStateOf(initialHeight) }
    var lastExpandedHeight by remember { mutableStateOf(initialHeight) }
    val minHeightPx = with(density) { minHeight.toPx() }
    val maxHeightPx = with(density) { maxHeight.toPx() }

    // ── External collapse/expand (the "hide keyboard" toggle button) ──
    // Mirrors exactly what the header's own tap-to-collapse gesture does:
    // collapse down to header-only height, or restore whatever height it
    // was expanded to before. This is why the header itself must NEVER be
    // wrapped in an AnimatedVisibility by the caller — only the keys area
    // (driven by this height) should ever collapse away.
    LaunchedEffect(expanded) {
        if (expanded) {
            keyboardHeight = lastExpandedHeight.coerceIn(minHeight, maxHeight)
        } else {
            if (keyboardHeight > minHeight) lastExpandedHeight = keyboardHeight
            keyboardHeight = minHeight
        }
    }

    // ── Resizable key width (2-finger pinch on header) — like FL Mobile's
    //    piano roll zoom: spread = fewer/bigger keys, pinch = more/smaller keys. ──
    var whiteKeyWidth by remember { mutableStateOf(initialKeyWidth) }
    val blackKeyW     = whiteKeyWidth * 0.6f
    val minKeyWidthPx = with(density) { minKeyWidth.toPx() }
    val maxKeyWidthPx = with(density) { maxKeyWidth.toPx() }

    // ── Premium frame: dark housing + header bar + keys ──
    Column(
        modifier = modifier
            .height(keyboardHeight)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0712), Color(0xFF050309))
                )
            )
    ) {
        // ── Header bar — accent rail + close (×) button, sits ABOVE the keys ──
        // Closing this (×) hides the ENTIRE piano roll, header included.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF140B22), Color(0xFF0D0716), Color(0xFF140B22))
                    )
                )
                // ── FL Mobile-style header resize handle ──
                // • 1 finger, press+drag anywhere on the header:
                //     - drag UP/DOWN grows/shrinks the keyboard (up to
                //       touching the app header above / down to just the
                //       header bar remaining).
                //     - drag LEFT/RIGHT pans the keyboard through octaves
                //       (same feel as dragging your finger across any
                //       horizontally-scrollable list) — both can happen at
                //       once on a diagonal drag.
                // • 2 fingers, pinch horizontally on the header: spreading apart
                //   zooms the keys IN (bigger keys, fewer octaves visible),
                //   pinching together zooms OUT (smaller keys, more octaves).
                //
                // NOTE: we deliberately use change.positionChange() (the delta
                // computed by Compose *within* a single event) instead of storing
                // a raw Y/distance and diffing it against the next event. Because
                // this very gesture resizes the header itself, the header moves
                // on screen every frame — comparing raw positions captured on two
                // different frames was measuring that self-movement as "finger
                // movement" and feeding it back into the resize, which is what
                // caused the bounce/jitter/delay.
                .pointerInput(Unit) {
                    val tapSlopPx = with(density) { 8.dp.toPx() }
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        var totalDrag = 0f
                        var multiTouchUsed = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }

                            when (pressed.size) {
                                0 -> {
                                    // all fingers lifted — gesture over
                                    break
                                }
                                1 -> {
                                    val change = pressed[0]
                                    val dy = -change.positionChange().y // up = positive
                                    val dx = change.positionChange().x  // right = positive
                                    totalDrag += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                                    if (dy != 0f) {
                                        val newHeightPx =
                                            (with(density) { keyboardHeight.toPx() } + dy)
                                                .coerceIn(minHeightPx, maxHeightPx)
                                        keyboardHeight = with(density) { newHeightPx.toDp() }
                                    }
                                    if (dx != 0f) {
                                        // Drag left → pan toward higher octaves (scroll
                                        // forward); drag right → pan toward lower octaves.
                                        // NOTE: scrollBy is a suspend fun, but this gesture
                                        // block runs on Compose's *restricted* pointer-input
                                        // coroutine scope, which can only call its own
                                        // built-in suspend functions (awaitPointerEvent,
                                        // etc.) directly. Launching on a regular
                                        // rememberCoroutineScope() sidesteps that
                                        // restriction.
                                        coroutineScope.launch { scrollState.scrollBy(-dx) }
                                    }
                                }
                                else -> {
                                    multiTouchUsed = true
                                    val c1 = pressed[0]
                                    val c2 = pressed[1]
                                    // Horizontal spread between the two fingers — this is
                                    // what drives the octave/key-width zoom (not vertical).
                                    val currDistance = kotlin.math.abs(c1.position.x - c2.position.x)
                                    val prevP1 = c1.position - c1.positionChange()
                                    val prevP2 = c2.position - c2.positionChange()
                                    val prevDistance = kotlin.math.abs(prevP1.x - prevP2.x)
                                    val dDistance = currDistance - prevDistance
                                    if (dDistance != 0f) {
                                        val newWidthPx =
                                            (with(density) { whiteKeyWidth.toPx() } + dDistance * 0.5f)
                                                .coerceIn(minKeyWidthPx, maxKeyWidthPx)
                                        whiteKeyWidth = with(density) { newWidthPx.toDp() }
                                    }
                                }
                            }

                            event.changes.forEach { it.consume() }
                        }

                        // ── Tap-to-collapse / tap-to-restore (FL Mobile style) ──
                        // A clean tap (one finger, negligible movement — i.e. not a
                        // drag and not part of a pinch) toggles the keyboard between
                        // collapsed (header-only) and however tall it was before it
                        // was collapsed, instead of always snapping to a fixed size.
                        if (!multiTouchUsed && totalDrag < tapSlopPx) {
                            if (keyboardHeight <= minHeight + 1.dp) {
                                keyboardHeight = lastExpandedHeight.coerceIn(minHeight, maxHeight)
                            } else {
                                lastExpandedHeight = keyboardHeight
                                keyboardHeight = minHeight
                            }
                        }
                    }
                }
        ) {
            // glowing separator line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                FlPurple.copy(alpha = 0.15f),
                                FlPurpleLight.copy(alpha = 0.55f),
                                FlPurple.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // (label text intentionally removed — header kept for drag/pinch resize + close button)

            // Close (×) button — top-right, in the keyboard's own header.
            // Hides the whole piano roll (header + keys) when tapped.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2A1F40), Color(0xFF160E26))
                        )
                    )
                    .border(1.dp, FlPurple.copy(alpha = 0.55f), CircleShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Default.Piano,
                    contentDescription = "Ocultar teclado",
                    tint               = FlPurpleLight,
                    modifier           = Modifier.size(14.dp)
                )
            }
        }

        // Only render the keys area when there's real room for it. At minimum
        // height (header only) this avoids a near-zero-height rounded box,
        // which used to leave a stray sliver/line visible at the bottom.
        val keysAreaVisible = keyboardHeight > HEADER_HEIGHT + 2.dp

        if (!keysAreaVisible) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 2.dp, end = 2.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(Color(0xFF070410))
                .horizontalScroll(scrollState)
        ) {
            run {
                // ── Root fix for drag/zoom lag ──
                // This used to be `BoxWithConstraints`, which by design
                // recomposes its ENTIRE content block every time the measured
                // size changes — and the available height here changes on
                // every single pixel of the header's vertical resize drag.
                // That meant ~120 key composables (with shadows, gradients,
                // borders) were being torn down and rebuilt on every frame
                // just to resize the keyboard.
                //
                // The only thing that measured height was used for was the
                // black keys' height (60% of available height) — a pure
                // LAYOUT concern, not a composition concern. Expressing it as
                // `Modifier.fillMaxHeight(0.6f)` (resolved at measure time)
                // gives the identical visual result without ever re-running
                // composition when the keyboard is resized vertically. Only a
                // real pinch-zoom (which actually changes each key's width)
                // still needs to recompose now.
                val whiteKeyWPx = with(density) { whiteKeyWidth.toPx() }
                val blackKeyWPx = with(density) { blackKeyW.toPx() }

                // Used only to PLACE black keys visually (absolute x offset).
                val keyPositions = remember(whiteKeyWidth) {
                    var wIdx = 0
                    ALL_KEYS.map { key ->
                        if (!key.isBlack) {
                            val x = wIdx * whiteKeyWPx
                            wIdx++
                            x
                        } else {
                            (wIdx - 1) * whiteKeyWPx + whiteKeyWPx - blackKeyWPx / 2f
                        }
                    }
                }

                // ── Ground-truth hit-testing ──
                // Instead of re-deriving each key's on-screen rectangle from
                // float math (which can drift from what's actually drawn once
                // you factor in pixel rounding, zoom, and scroll), every key
                // reports its OWN real measured bounds here as it's laid out.
                // Touch detection then just asks "which key's real rectangle
                // contains this point?" — so rendering and hit-testing can
                // never disagree, at any zoom level or keyboard size.
                val keyBoundsPx = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }

                fun noteAt(x: Float, y: Float): Int? {
                    // Black keys are drawn on top, so they win on overlap.
                    for (key in ALL_KEYS) {
                        if (!key.isBlack) continue
                        val b = keyBoundsPx[key.midiNote] ?: continue
                        if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) {
                            return key.midiNote
                        }
                    }
                    for (key in whiteKeys) {
                        val b = keyBoundsPx[key.midiNote] ?: continue
                        if (x >= b.left && x <= b.right) {
                            return key.midiNote
                        }
                    }
                    return null
                }

                val totalWidth = whiteKeyWidth * whiteKeys.size

                Box(
                    modifier = Modifier
                        .width(totalWidth)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        val rawX = change.position.x
                                        val rawY = change.position.y
                                        val id   = change.id.value

                                        when {
                                            change.pressed && !change.previousPressed -> {
                                                val note = noteAt(rawX, rawY)
                                                if (note != null) {
                                                    pointerMap[id] = note
                                                    onNoteOn(note)
                                                }
                                                change.consume()
                                            }
                                            !change.pressed && change.previousPressed -> {
                                                pointerMap[id]?.let { onNoteOff(it) }
                                                pointerMap.remove(id)
                                                change.consume()
                                            }
                                            change.pressed -> {
                                                val note = noteAt(rawX, rawY)
                                                val prev = pointerMap[id]
                                                if (note != null && note != prev) {
                                                    prev?.let { onNoteOff(it) }
                                                    pointerMap[id] = note
                                                    onNoteOn(note)
                                                }
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    // White keys
                    Row(modifier = Modifier.fillMaxSize()) {
                        whiteKeys.forEach { key ->
                            WhiteKey(
                                width   = whiteKeyWidth,
                                pressed = key.midiNote in activeNotes,
                                label   = if (key.semitone == 0) key.noteName else "",
                                onBoundsChanged = { bounds -> keyBoundsPx[key.midiNote] = bounds }
                            )
                        }
                    }

                    // Black keys — absolute positioned, drawn on top
                    ALL_KEYS.forEachIndexed { i, key ->
                        if (key.isBlack) {
                            val xDp = with(density) { keyPositions[i].toDp() }
                            BlackKey(
                                xOffset = xDp,
                                width   = blackKeyW,
                                pressed = key.midiNote in activeNotes,
                                onBoundsChanged = { bounds -> keyBoundsPx[key.midiNote] = bounds }
                            )
                        }
                    }
                }
            }
        }
        } // end keysAreaVisible else-branch
    }
}

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
