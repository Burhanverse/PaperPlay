package io.max.paperplay.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.max.paperplay.ui.theme.sheetCloseSpring
import io.max.paperplay.ui.theme.sheetSpring

/**
 * Where the full player sits between closed and open. [progress] is 0 closed and 1 open; [pulled] is how far a finger
 * has dragged the open sheet down, in pixels. Both are read only in a layer block, so moving the sheet never recomposes it.
 */
class PlayerSheetState {
    val progress = Animatable(0f)
    var pulled by mutableFloatStateOf(0f)
        private set
    var isAnimating by mutableStateOf(false)
        private set

    internal fun setAnimating(running: Boolean) {
        isAnimating = running
    }

    /** Follows a finger that has moved [dy] pixels down from where the drag began. Upward movement leaves the sheet open. */
    fun pull(dy: Float) {
        pulled = dy.coerceAtLeast(0f)
    }

    /**
     * Lets go of a drag: the sheet settles open or closed, carrying the finger's speed ([velocityY], pixels per second,
     * positive is down). Returns true when it settled closed.
     */
    suspend fun release(
        velocityY: Float,
        heightPx: Float,
        openSpec: FiniteAnimationSpec<Float>,
        closeSpec: FiniteAnimationSpec<Float> = openSpec,
    ): Boolean {
        val closing = pulled > heightPx * 0.35f || velocityY > 1_200f
        // The drag offset becomes progress in one step, so the sheet does not jump.
        progress.snapTo((1f - pulled / heightPx).coerceIn(0f, 1f))
        pulled = 0f
        isAnimating = true
        try {
            progress.animateTo(
                if (closing) 0f else 1f,
                if (closing) closeSpec else openSpec,
                initialVelocity = -velocityY / heightPx,
            )
        } finally {
            isAnimating = false
        }
        return closing
    }
}

/** The sheet the full player is in. Outside a [PlayerSheetHost] (previews) it is a stand-alone one that moves nothing. */
val LocalPlayerSheet = staticCompositionLocalOf { PlayerSheetState() }

/**
 * Slides [content] up from the bottom when [open] turns true and back down when it turns false, following a drag in
 * between. The content stays composed until the slide has finished.
 */
@Composable
fun PlayerSheetHost(open: Boolean, sheet: PlayerSheetState, content: @Composable () -> Unit) {
    val openSpec = sheetSpring<Float>()
    val closeSpec = sheetCloseSpring<Float>()
    LaunchedEffect(open) {
        sheet.setAnimating(true)
        try {
            sheet.progress.animateTo(if (open) 1f else 0f, if (open) openSpec else closeSpec)
        } finally {
            sheet.setAnimating(false)
        }
    }
    val active by remember { derivedStateOf { sheet.isAnimating || sheet.progress.value > 0f } }
    if (open || active) {
        CompositionLocalProvider(LocalPlayerSheet provides sheet) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = (size.height * (1f - sheet.progress.value.coerceIn(0f, 1f)) + sheet.pulled).coerceAtLeast(0f)
                }.blockTouchesBehind(),
            ) { content() }
        }
    }
}
