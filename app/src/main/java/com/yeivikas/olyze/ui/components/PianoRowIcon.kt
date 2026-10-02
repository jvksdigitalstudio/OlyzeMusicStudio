package com.yeivikas.olyze.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yeivikas.olyze.R

/**
 * Renders one of the two premium keyboard-row icons exactly as supplied —
 * multi-density PNG resources (`res/drawable-*dpi/ic_piano_*.png`), decoded
 * by the platform's own bitmap decoder via [painterResource]. No SVG
 * runtime parsing, no extra imaging library: each density bucket ships a
 * pre-sized bitmap the OS picks automatically for the device, which is both
 * the standard Android approach for bitmap icons and far lighter than
 * shipping one oversized master image and decoding/scaling it on every
 * recomposition.
 */
@Composable
fun PianoRowIcon(@DrawableRes drawableRes: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = drawableRes),
        contentDescription = null,
        modifier = modifier,
    )
}

/**
 * The keyboard header's row-mode toggle button. Rendered as a bare icon —
 * no circle, background fill, or border around it — these premium icons
 * are used exactly as supplied, standing alone.
 *
 * Shows [R.drawable.ic_piano_double_row] while [dualRow] is false (tap →
 * switch to two rows) and [R.drawable.ic_piano_single_row] while [dualRow]
 * is true (tap → switch back to one row): the same slot swaps icon and
 * meaning together — never two icons shown at once for this control.
 */
@Composable
fun PianoRowToggleButton(dualRow: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    PianoRowIcon(
        drawableRes = if (dualRow) R.drawable.ic_piano_single_row else R.drawable.ic_piano_double_row,
        modifier = modifier
            .size(22.dp)
            .pointerInput(dualRow) { detectTapGestures(onTap = { onToggle() }) }
    )
}
