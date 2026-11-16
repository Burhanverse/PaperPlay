package io.max.paperplay.ui.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes this area take every touch that lands on it, even on empty parts, so nothing underneath (a list behind the player, a row behind
 * the bars) is pressed through it. Touches are not consumed: whatever sits inside still gets them.
 */
fun Modifier.blockTouchesBehind(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}
