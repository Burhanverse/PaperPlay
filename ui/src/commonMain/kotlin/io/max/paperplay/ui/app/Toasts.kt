package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** The app's snackbars: coloured (the page [tint], else the theme's container colour) and swiped sideways to dismiss, like [StatusToast]. */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier, tint: Color? = null) {
    SnackbarHost(state, modifier) { data -> SwipeToast(data, tint) }
}

@Composable
private fun SwipeToast(data: SnackbarData, tint: Color?) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (tint != null) lerp(tint, scheme.onSurface, 0.16f) else scheme.primaryContainer
    val fg = if (bg.luminance() < 0.5f) Color.White else Color.Black
    var offset by remember(data) { mutableFloatStateOf(0f) }
    Row(
        Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp)
            .offset { IntOffset(offset.toInt(), 0) }
            .graphicsLayer { alpha = (1f - kotlin.math.abs(offset) / 600f).coerceIn(0f, 1f) }
            .shadow(6.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(bg)
            .pointerInput(data) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (kotlin.math.abs(offset) > 80f) data.dismiss() else offset = 0f },
                    onDragCancel = { offset = 0f },
                ) { _, dx -> offset += dx }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(data.visuals.message, Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyMedium)
        data.visuals.actionLabel?.let { label ->
            TextButton({ data.performAction() }) { Text(label, color = fg) }
        }
    }
}
