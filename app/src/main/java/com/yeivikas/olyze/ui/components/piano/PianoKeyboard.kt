package com.yeivikas.olyze.ui.components.piano

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.ui.theme.*
import kotlinx.coroutines.launch

private val HEADER_HEIGHT = 34.dp

@Composable
fun PianoKeyboard(
    modifier: Modifier = Modifier,
    activeNotes: Set<Int> = emptySet(),
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
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

    // ── Single row / double row toggle (Objetivo: FL Mobile & n-Track style
    //    split keyboard) — driven entirely by [PianoRowToggleButton] in the
    //    header. Survives config change (rotation) via rememberSaveable, but
    //    is intentionally NOT hoisted to MainViewModel: it is a pure view
    //    concern, it changes nothing about which notes are active or how
    //    the audio/MIDI engine works underneath.
    var dualRow by rememberSaveable { mutableStateOf(false) }

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

    // ── Reference-counted note dedup (dual-row overlap fix) ──
    // In dual-row mode the same MIDI note can sound from BOTH strips at
    // once (bottom strip's key N and top strip's key N-12 both sound N —
    // playing octaves across the split is the whole point of this mode).
    // Two fingers can therefore legitimately hold the SAME sounding note
    // at the same time. Without this, releasing just one of those two
    // fingers would forward a raw onNoteOff(note) straight to the engine —
    // which cuts the note entirely (AudioEngine::applyCommand's NoteOff
    // releases every voice matching that note number, not "one hold's
    // worth"), even though the other finger is still physically down.
    // Counting holds per sounding note here means onNoteOn only reaches
    // the engine on the 0→1 transition and onNoteOff only on the 1→0
    // transition, regardless of which strip(s) a note's holds came from.
    // La contabilidad vive en NoteHoldTracker; aquí solo se decide a quién reenviar.
    val holds = remember { NoteHoldTracker() }
    val dedupedNoteOn: (Int) -> Unit = { note -> if (holds.press(note)) onNoteOn(note) }
    val dedupedNoteOff: (Int) -> Unit = { note -> if (holds.release(note)) onNoteOff(note) }

    // Toggling dualRow tears down and rebuilds the PianoKeysStrip
    // composable(s) that were tracking any currently-held touch (e.g. the
    // user taps the row-toggle icon with one finger while another finger
    // is still holding a key down). That teardown cancels the strip's own
    // pointerInput gesture mid-stream — it never gets to fire onNoteOff
    // for whatever it was holding. Without this, that note would sound
    // forever ("stuck note"). This only releases notes THIS component's
    // touch layer believes are held (NoteHoldTracker) — it does not send a
    // blanket all-notes-off, so notes genuinely held by an external MIDI
    // controller are left untouched.
    LaunchedEffect(dualRow) {
        holds.releaseAll().forEach(onNoteOff)
    }

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
        // ── Header bar — accent rail + row-mode toggle, sits ABOVE the keys ──
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
                        // requireUnconsumed = true is the actual fix for
                        // "tapping the row-toggle icon also hides the
                        // keyboard": PianoRowToggleButton sits INSIDE this
                        // same header Box and consumes its own down/up via
                        // detectTapGestures. Compose's Main pointer pass
                        // runs child → parent, so by the time this gesture
                        // sees the down, an already-consumed one (from the
                        // button) must be ignored rather than treated as a
                        // real tap-to-collapse/drag gesture. A genuine tap
                        // on empty header space is never pre-consumed, so
                        // this changes nothing for that case.
                        awaitFirstDown(requireUnconsumed = true)

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

            // (label text intentionally removed — header kept for drag/pinch resize)

            // Row-mode toggle — top-right, in the keyboard's own header. The
            // old "Ocultar teclado" close button that used to sit here was
            // removed on the assumption that AppHeader's own show/hide
            // control (onKeyboardToggle, wired from MainScreen) already
            // covered the same need. Full-project audit fix: that
            // assumption was wrong at the time — Header.kt's
            // KeyboardToggleBtn existed but was never actually placed
            // anywhere in AppHeader, so removing this button briefly made
            // "hide the keyboard" unreachable from the UI. Header.kt now
            // really does wire KeyboardToggleBtn up (see its own fix note),
            // so this removal is correct now, not just in theory.
            // Icon rendered bare — no circle/background/border chrome
            // around it, per spec.
            PianoRowToggleButton(
                dualRow  = dualRow,
                onToggle = { dualRow = !dualRow },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            )
        }

        // Only render the keys area when there's real room for it. At minimum
        // height (header only) this avoids a near-zero-height rounded box,
        // which used to leave a stray sliver/line visible at the bottom.
        val keysAreaVisible = keyboardHeight > HEADER_HEIGHT + 2.dp

        if (!keysAreaVisible) {
            Spacer(modifier = Modifier.weight(1f))
        } else if (!dualRow) {
            // ── Single-row mode (unchanged behavior) ──
            PianoKeysStrip(
                modifier      = Modifier.fillMaxWidth().weight(1f).padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
                density       = density,
                scrollState   = scrollState,
                whiteKeys     = whiteKeys,
                whiteKeyWidth = whiteKeyWidth,
                blackKeyW     = blackKeyW,
                noteOffset    = 0,
                activeNotes   = activeNotes,
                onNoteOn      = dedupedNoteOn,
                onNoteOff     = dedupedNoteOff,
            )
        } else {
            // ── Dual-row mode (FL Mobile / n-Track style split keyboard) ──
            // Two independent strips stacked vertically, sharing the SAME
            // horizontal scroll state so panning either one pans both in
            // sync — the top strip is always exactly one octave (12
            // semitones) above whatever the bottom strip is showing, same
            // as the reference screenshots. Each strip tracks its own
            // touches (a finger on the top strip must never fight a finger
            // on the bottom strip for the same pointer-id bookkeeping).
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 2.dp, end = 2.dp, bottom = 4.dp)
            ) {
                PianoKeysStrip(
                    modifier      = Modifier.fillMaxWidth().weight(1f),
                    density       = density,
                    scrollState   = scrollState,
                    whiteKeys     = whiteKeys,
                    whiteKeyWidth = whiteKeyWidth,
                    blackKeyW     = blackKeyW,
                    noteOffset    = 12,
                    activeNotes   = activeNotes,
                    onNoteOn      = dedupedNoteOn,
                    onNoteOff     = dedupedNoteOff,
                    cornerRadius  = 10.dp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                PianoKeysStrip(
                    modifier      = Modifier.fillMaxWidth().weight(1f),
                    density       = density,
                    scrollState   = scrollState,
                    whiteKeys     = whiteKeys,
                    whiteKeyWidth = whiteKeyWidth,
                    blackKeyW     = blackKeyW,
                    noteOffset    = 0,
                    activeNotes   = activeNotes,
                    onNoteOn      = dedupedNoteOn,
                    onNoteOff     = dedupedNoteOff,
                    cornerRadius  = 10.dp,
                )
            }
        }
    }
}

/**
 * One horizontally-scrollable piano strip — the single row the keyboard has
 * always rendered, factored out so [PianoKeyboard] can place either exactly
 * one of these (single-row mode) or two stacked on top of each other
 * (dual-row mode, see [PianoKeyboard]'s `dualRow` state).
 *
 * [noteOffset] shifts every key's *sounding* MIDI note by this many
 * semitones (12 = one octave up) without changing the visual key layout —
 * this is what makes the dual-row top strip play an octave above the
 * bottom one while both keep the exact same key widths/positions and share
 * [scrollState]. The result is coerced into the engine's valid 0..119
 * range, so the handful of keys at the very top of the visible range in a
 * shifted strip simply saturate at the highest playable note rather than
 * producing an invalid one.
 */
@Composable
private fun PianoKeysStrip(
    modifier: Modifier,
    density: androidx.compose.ui.unit.Density,
    scrollState: androidx.compose.foundation.ScrollState,
    whiteKeys: List<KeyInfo>,
    whiteKeyWidth: Dp,
    blackKeyW: Dp,
    noteOffset: Int,
    activeNotes: Set<Int>,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    cornerRadius: Dp = 10.dp,
) {
    val pointerMap = remember { mutableStateMapOf<Long, Int>() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
            .background(Color(0xFF070410))
            .horizontalScroll(scrollState)
    ) {
        // ── Root fix for drag/zoom lag ──
        // This used to be `BoxWithConstraints`, which by design recomposes
        // its ENTIRE content block every time the measured size changes —
        // and the available height here changes on every single pixel of
        // the header's vertical resize drag. That meant ~120 key
        // composables (with shadows, gradients, borders) were being torn
        // down and rebuilt on every frame just to resize the keyboard.
        //
        // The only thing that measured height was used for was the black
        // keys' height (60% of available height) — a pure LAYOUT concern,
        // not a composition concern. Expressing it as
        // `Modifier.fillMaxHeight(0.6f)` (resolved at measure time) gives
        // the identical visual result without ever re-running composition
        // when the keyboard is resized vertically. Only a real pinch-zoom
        // (which actually changes each key's width) still needs to
        // recompose now.
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
        // Instead of re-deriving each key's on-screen rectangle from float
        // math (which can drift from what's actually drawn once you factor
        // in pixel rounding, zoom, and scroll), every key reports its OWN
        // real measured bounds here as it's laid out. Touch detection then
        // just asks "which key's real rectangle contains this point?" — so
        // rendering and hit-testing can never disagree, at any zoom level
        // or keyboard size.
        val keyBoundsPx = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }

        fun soundingNote(visualMidiNote: Int): Int = (visualMidiNote + noteOffset).coerceIn(0, 119)

        fun noteAt(x: Float, y: Float): Int? {
            // Black keys are drawn on top, so they win on overlap.
            for (key in ALL_KEYS) {
                if (!key.isBlack) continue
                val b = keyBoundsPx[key.midiNote] ?: continue
                if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) {
                    return soundingNote(key.midiNote)
                }
            }
            for (key in whiteKeys) {
                val b = keyBoundsPx[key.midiNote] ?: continue
                if (x >= b.left && x <= b.right) {
                    return soundingNote(key.midiNote)
                }
            }
            return null
        }

        val totalWidth = whiteKeyWidth * whiteKeys.size

        Box(
            modifier = Modifier
                .width(totalWidth)
                .fillMaxHeight()
                .pointerInput(noteOffset) {
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
                    val sounding = soundingNote(key.midiNote)
                    val label = if (key.semitone == 0) {
                        // key.semitone == 0 is always a C, and noteOffset is
                        // always a whole number of octaves, so the sounding
                        // note is a C too — only its octave number moves.
                        "${NOTE_NAMES[0]}${(sounding / 12) - 1}"
                    } else ""
                    WhiteKey(
                        width   = whiteKeyWidth,
                        pressed = sounding in activeNotes,
                        label   = label,
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
                        pressed = soundingNote(key.midiNote) in activeNotes,
                        onBoundsChanged = { bounds -> keyBoundsPx[key.midiNote] = bounds }
                    )
                }
            }
        }
    }
}
