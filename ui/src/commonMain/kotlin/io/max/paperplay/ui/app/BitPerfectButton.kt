package io.max.paperplay.ui.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.TrueAudioIcon
import io.max.paperplay.ui.components.toggleOnColor
import io.max.paperplay.ui.theme.LocalReduceMotion

/**
 * The bit-perfect switch for the player bar: a gem for true audio. While it is on, tiny notes and sparks drift up from it
 * (not with reduced motion, where the gem just takes the on colour).
 */
@Composable
fun BitPerfectButton(on: Boolean, off: Color, iconSize: Modifier, onToggle: () -> Unit) {
    val accent = toggleOnColor()
    val foreground = io.max.paperplay.ui.rememberAppForeground()
    Box {
        if (on && foreground && !LocalReduceMotion.current) Floaters(accent)
        IconButton(onToggle) { AppIcon(TrueAudioIcon, if (on) "Bit-perfect (on)" else "Bit-perfect", iconSize, tint = if (on) accent else off) }
    }
}

/** Four small marks that rise from the button and fade, each a quarter of the cycle behind the one before. */
@Composable
private fun Floaters(color: Color) {
    val t = rememberInfiniteTransition(label = "true audio")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val marks = listOf(
        Triple(Icons.Rounded.MusicNote, (-10).dp, 0.00f),
        Triple(Icons.Rounded.Bolt, 8.dp, 0.25f),
        Triple(Icons.Rounded.MusicNote, 12.dp, 0.50f),
        Triple(Icons.Rounded.Bolt, (-6).dp, 0.75f),
    )
    for ((icon, dx, shift) in marks) {
        Icon(
            icon, null,
            Modifier.size(10.dp).graphicsLayer {
                val p = (phase.value + shift) % 1f
                translationX = (19.dp + dx).toPx()
                translationY = (30f - 34f * p).dp.toPx()
                alpha = if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f
            },
            tint = color,
        )
    }
}
