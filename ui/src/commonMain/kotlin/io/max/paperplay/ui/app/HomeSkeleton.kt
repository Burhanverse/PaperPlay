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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.theme.LocalReduceMotion

/** A grey placeholder shape that pulses softly (still when the system asks for reduced motion). */
@Composable
fun Modifier.skeleton(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)): Modifier {
    val reduce = LocalReduceMotion.current
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeleton alpha",
    )
    return this.graphicsLayer { alpha = if (reduce) 0.8f else pulse }
        .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
}

/** [skeleton] as a ready-made modifier for a cover-sized shape (the clip comes from the caller's box). */
@Composable
fun skeletonFill(): Modifier = Modifier.skeleton(androidx.compose.ui.graphics.RectangleShape)

/** The six quick tiles of Home as placeholders. */
@Composable
fun SkeletonTiles() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { Spacer(Modifier.weight(1f).height(56.dp).skeleton(RoundedCornerShape(12.dp))) }
            }
        }
    }
}

/** A titled row of cover cards as placeholders. */
@Composable
fun SkeletonShelf(card: Dp = 150.dp) {
    Column(Modifier.padding(top = 20.dp)) {
        Spacer(Modifier.padding(start = 16.dp).width(160.dp).height(20.dp).skeleton(RoundedCornerShape(6.dp)))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column {
                    Spacer(Modifier.size(card).skeleton(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.padding(top = 8.dp).width(card * 0.8f).height(14.dp).skeleton(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.padding(top = 4.dp).width(card * 0.5f).height(12.dp).skeleton(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
