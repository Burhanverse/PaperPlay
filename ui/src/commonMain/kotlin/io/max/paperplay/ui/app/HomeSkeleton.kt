package io.max.paperplay.ui.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.theme.LocalReduceMotion

/** A grey placeholder shape with a soft light sweeping across it (still when the system asks for reduced motion). */
@Composable
fun Modifier.skeleton(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)): Modifier {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    if (reduce) return this.clip(shape).background(base)
    val light = androidx.compose.ui.graphics.lerp(base, MaterialTheme.colorScheme.onSurface, 0.10f)
    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "skeleton sweep",
    )
    return this.clip(shape).background(base).drawWithCache {
        val w = size.width.coerceAtLeast(1f)
        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(androidx.compose.ui.graphics.Color.Transparent, light, androidx.compose.ui.graphics.Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(w, size.height * 0.4f),
        )
        onDrawWithContent {
            drawContent()
            withTransform({ translate(left = -w + sweep.value * 2f * w) }) {
                drawRect(brush)
            }
        }
    }
}

/** [skeleton] as a ready-made modifier for a cover-sized shape (the clip comes from the caller's box). */
@Composable
fun skeletonFill(): Modifier = Modifier.skeleton(androidx.compose.ui.graphics.RectangleShape)

/** The six quick tiles of Home as placeholders. */
