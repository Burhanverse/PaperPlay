package io.max.paperplay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Loads cover art for a URI. The app provides one; without it every cover shows its mosaic. */
fun interface CoverLoader {
    /** Null while loading or when the image is missing. */
    @Composable
    fun painter(uri: String, maxSide: Int): Painter?
}

val LocalCoverLoader = compositionLocalOf<CoverLoader?> { null }

/** True while covers are still being fetched: a song without art shows a placeholder shape, not the final mosaic, until they are done. */
val LocalCoversPending = compositionLocalOf { false }
val LocalOnMissingCover = compositionLocalOf<((String) -> Unit)?> { null }

/** Cover art, or the mosaic for [key] when there is none. */
@Composable
fun CoverImage(
    uri: String?,
    key: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    radius: Dp = 8.dp,
    coverId: String = key,
    /** Applied to the picture only (a grayscale cover, for example); the placeholder keeps its colours. */
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
) {
    val loader = LocalCoverLoader.current
    val onMissing = LocalOnMissingCover.current
    LaunchedEffect(coverId, uri) {
        if (uri == null) onMissing?.invoke(coverId)
    }
    // Decode at about the pixels shown, rounded up to a few sizes so covers share cache entries; large art stays sharp on sharp screens.
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() }
    val decode = when { px <= 160f -> 160; px <= 384f -> 384; px <= 512f -> 512; px <= 768f -> 768; else -> 1280 }
    val painter = if (uri != null && loader != null) loader.painter(uri, decode) else null
    val shape = if (radius * 2 >= size) androidx.compose.foundation.shape.CircleShape else io.max.paperplay.ui.app.appShape(radius, io.max.paperplay.ui.app.CornerArea.Covers)
    // A cover that arrives after the row is shown fades in over the mosaic; one already loaded appears at once.
    val fade = androidx.compose.runtime.remember(coverId) { androidx.compose.animation.core.Animatable(if (painter != null) 1f else 0f) }
    val fadeSpec = io.max.paperplay.ui.theme.motionTween<Float>(io.max.paperplay.ui.theme.Motion.FAST)
    LaunchedEffect(painter != null) { if (painter != null) fade.animateTo(1f, fadeSpec) }
    Box(modifier.size(size).clip(shape)) {
        if (painter == null || fade.value < 1f) {
            // While a cover is being looked up or decoded, the placeholder shimmers; the mosaic stays only for a song that has no cover.
            if ((uri == null && LocalCoversPending.current) || (uri != null && painter == null)) androidx.compose.foundation.layout.Spacer(Modifier.size(size).then(io.max.paperplay.ui.app.skeletonFill()))
            else CoverMosaic(key, Modifier.size(size))
        }
        if (painter != null) {
            Image(painter, contentDescription = null, Modifier.size(size).graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop, colorFilter = colorFilter)
        }
    }
}

/** Stable 2 by 2 tiles of related hues, picked from a hash of [key]. */
@Composable
fun CoverMosaic(key: String, modifier: Modifier = Modifier) {
    val tiles = remember(key) { mosaicColors(key) }
    Canvas(modifier) {
        val w = size.width / 2
        val h = size.height / 2
        for (i in 0 until 4) {
            drawRect(tiles[i], Offset((i % 2) * w, (i / 2) * h), Size(w + 0.5f, h + 0.5f))
        }
    }
}

/** Four tile colours for [key]; the same key always gives the same four. */
fun mosaicColors(key: String): List<Color> {
    var hash = 7
    for (ch in key) hash = hash * 31 + ch.code
    val base = ((hash.toLong() and 0x7fffffff) % 360).toFloat()
    return listOf(
        Color.hsv(base, 0.55f, 0.85f),
        Color.hsv((base + 40f) % 360f, 0.60f, 0.65f),
        Color.hsv((base + 80f) % 360f, 0.50f, 0.75f),
        Color.hsv((base + 20f) % 360f, 0.65f, 0.50f),
    )
}

@Preview
@Composable
private fun CoverImagePreview() {
    CoverImage(uri = null, key = "preview_key")
}

@Preview
@Composable
private fun CoverMosaicPreview() {
    CoverMosaic(key = "preview_mosaic_key", modifier = Modifier.size(64.dp))
}
