package io.max.paperplay.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.AuroraSeek
import io.max.paperplay.ui.components.uniqueKeys
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.LocalReduceMotion
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.desktopHoverSpec
import io.max.paperplay.ui.theme.motionColorSpec
import io.max.paperplay.ui.theme.motionEffectTween
import io.max.paperplay.ui.theme.motionItem
import org.jetbrains.compose.ui.tooling.preview.Preview

internal enum class NowPlayingPane { Queue, Lyrics }

/** State machine for desktop Now Playing view transitions and drag-and-drop queue reordering. */
internal class DesktopNowPlayingState {
    var pane by mutableStateOf(NowPlayingPane.Queue)
    var fullLyrics by mutableStateOf(false)
    var draggingIndex by mutableStateOf<Int?>(null)
    var dragOffsetPx by mutableFloatStateOf(0f)

    fun selectPane(next: NowPlayingPane) {
        pane = next
    }

    fun setFullLyricsMode(on: Boolean) {
        fullLyrics = on
    }

    fun startDrag(index: Int) {
        draggingIndex = index
        dragOffsetPx = 0f
    }

    fun updateDrag(
        deltaPx: Float,
        itemHeightPx: Float,
        queueSize: Int,
        onMove: (from: Int, to: Int) -> Unit,
    ) {
        if (draggingIndex == null || itemHeightPx <= 1f || queueSize <= 1) return
        dragOffsetPx += deltaPx
        val threshold = itemHeightPx * 0.55f
        while (dragOffsetPx > threshold && (draggingIndex ?: 0) < queueSize - 1) {
            val from = draggingIndex ?: break
            val to = from + 1
            onMove(from, to)
            draggingIndex = to
            dragOffsetPx -= itemHeightPx
        }
        while (dragOffsetPx < -threshold && (draggingIndex ?: 0) > 0) {
            val from = draggingIndex ?: break
            val to = from - 1
            onMove(from, to)
            draggingIndex = to
            dragOffsetPx += itemHeightPx
        }
    }

    fun endDrag() {
        draggingIndex = null
        dragOffsetPx = 0f
    }
}

/**
 * Desktop only. The current song as a full content-area page: big cover and controls on the left,
 * synced lyrics in the middle, the queue on the right, all washed with the cover's colours.
 */
@Composable
fun DesktopNowPlayingView(
    state: PlaybackUiState, backend: PlayerControls, actions: ListActions,
    onClose: () -> Unit, modifier: Modifier = Modifier,
) {
    val song = state.current
    if (song == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing is playing", style = MaterialTheme.typography.titleMedium) }
        return
    }
    val npState = remember(song.id) { DesktopNowPlayingState() }
    val reduceMotion = LocalReduceMotion.current
    val shiftPx = with(LocalDensity.current) { Motion.Desktop.PaneShiftDp.dp.roundToPx() }
    CoverTinted(song.coverUri) {
        // The shell paints the cover wash behind the whole window while this view is open, so the card needs no fill of its own.
        Box(modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
            AnimatedContent(
                targetState = npState.fullLyrics,
                transitionSpec = { desktopPaneTransition(forward = targetState, reduced = reduceMotion, shiftPx = shiftPx) },
                label = "npFullLyrics",
            ) { full ->
                if (full) {
                    FullLyricsView(song, backend, { npState.setFullLyricsMode(false) })
                } else {
                    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        NowPlayingMain(song, state, backend, actions, onClose, { npState.setFullLyricsMode(true) }, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            val ink = MaterialTheme.colorScheme.onPrimaryContainer
                            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(NowPlayingPane.Queue to "Queue", NowPlayingPane.Lyrics to "Lyrics").forEach { (targetPane, name) ->
                                    val sel = npState.pane == targetPane
                                    val pillBg by animateColorAsState(if (sel) ink.copy(alpha = 0.14f) else Color.Transparent, motionColorSpec(), label = "npTab")
                                    Text(
                                        name,
                                        Modifier.clip(RoundedCornerShape(50))
                                            .desktopInteractive(RoundedCornerShape(50))
                                            .clickable { npState.selectPane(targetPane) }
                                            .background(pillBg).padding(horizontal = 14.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.titleMedium, color = if (sel) ink else ink.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            AnimatedContent(
                                targetState = npState.pane,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                transitionSpec = {
                                    desktopPaneTransition(
                                        forward = targetState == NowPlayingPane.Lyrics,
                                        reduced = reduceMotion,
                                        shiftPx = shiftPx,
                                    )
                                },
                                label = "npPane",
                            ) { pane ->
                                when (pane) {
                                    NowPlayingPane.Lyrics -> LyricsPane(song, backend, large = false, modifier = Modifier.fillMaxSize())
                                    NowPlayingPane.Queue -> NowPlayingQueue(state, backend, npState, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMain(
    song: SongUi, state: PlaybackUiState, backend: PlayerControls, actions: ListActions,
    onClose: () -> Unit, onFullLyrics: () -> Unit, modifier: Modifier,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Close now playing", tint = ink) }
            Text(
                if (song.album.isEmpty() || song.album.startsWith(song.title)) "Now playing" else song.album,
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = ink,
            )
            IconButton(onFullLyrics) { AppIcon(Icons.Outlined.Lyrics, "Full screen lyrics", tint = ink) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight, 420.dp)
            CoverImage(song.coverUri, song.id, size = side, radius = 16.dp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall, color = ink)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.7f))
            }
            PreviewCopyButton(song)
            IconButton({ actions.setFavorite(song, !song.favorite) }) {
                AppIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "Remove from favorites" else "Add to favorites", tint = ink)
            }
            io.max.paperplay.ui.components.SongMenu(song, actions.rowActions(), Icons.Rounded.MoreHoriz)
        }
        Spacer(Modifier.height(8.dp))
        NowPlayingSeek(song, state, backend, ink)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton({ backend.toggleShuffle() }) { AppIcon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else ink) }
            IconButton({ backend.previous() }) { AppIcon(Icons.Rounded.FastRewind, "Previous", Modifier.size(30.dp), tint = ink) }
            IconButton({ backend.togglePlay() }, Modifier.size(64.dp)) { AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(46.dp), tint = ink) }
            IconButton({ backend.next() }) { AppIcon(Icons.Rounded.FastForward, "Next", Modifier.size(30.dp), tint = ink) }
            IconButton({ backend.cycleRepeat() }) {
                AppIcon(if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeat != RepeatUi.Off) MaterialTheme.colorScheme.primary else ink)
            }
        }
    }
}

/** Elapsed time, a seek bar (hover-preview or Aurora Wave) tinted by the cover, and the total time. */
@Composable
private fun NowPlayingSeek(song: SongUi, state: PlaybackUiState, backend: PlayerControls, ink: Color) {
    val pos = backend.position.collectAsState()
    val durationMs = state.durationMs
    val style = MaterialTheme.typography.labelSmall.copy(color = ink.copy(alpha = 0.75f))
    val barStyle = LocalUiPrefs.current?.progressBarStyle ?: ProgressBarStyle.SQUIGGLY_WAVE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(pos.value.positionMs / 1000), style = style)
        if (barStyle == ProgressBarStyle.AURORA_WAVE) {
            AuroraSeek(
                progress = { pos.value.progress(durationMs) },
                buffered = { pos.value.bufferedProgress(durationMs) },
                playing = state.playing,
                onSeek = { backend.seekTo(it) },
                colors = auroraPalette(song.coverUri, MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                label = formatDuration(pos.value.positionMs / 1000),
                longPressLabel = { fraction -> formatDuration((durationMs * fraction / 1000L).toLong()) },
                compact = true,
            )
        } else {
            HoverSeek({ pos.value.progress(durationMs) }, durationMs, { backend.seekTo(it) }, ink, Modifier.weight(1f).padding(horizontal = 10.dp))
        }
        Text(formatDuration(durationMs / 1000), style = style)
    }
}

/** A seek bar that shows the time under the pointer while hovering. Tap or drag to seek; a drag seeks when released. */
@Composable
fun HoverSeek(progress: () -> Float, durationMs: Long, onSeek: (Float) -> Unit, color: Color, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableStateOf<Float?>(null) }
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val trackHeight by animateDpAsState(
        targetValue = if (hovered || dragging != null) 7.dp else 5.dp,
        animationSpec = desktopHoverSpec(),
        label = "hoverSeekTrack",
    )
    val tipAlpha by animateFloatAsState(
        targetValue = if (hover != null) 1f else 0f,
        animationSpec = motionEffectTween(Motion.Desktop.HoverMs),
        label = "hoverSeekTip",
    )
    BoxWithConstraints(modifier.height(24.dp)) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxWidth = maxWidth
        Box(
            Modifier.fillMaxSize()
                .pointerHoverIcon(PointerIcon.Hand)
                .hoverable(hoverSource)
                .pointerInput(w) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            hover = if (e.type == PointerEventType.Exit) null else (e.changes.first().position.x / w).coerceIn(0f, 1f)
                        }
                    }
                }
                .pointerInput(w) { detectTapGestures { onSeek((it.x / w).coerceIn(0f, 1f)) } }
                .pointerInput(w) {
                    detectHorizontalDragGestures(
                        onDragEnd = { dragging?.let(onSeek); dragging = null }, onDragCancel = { dragging = null },
                    ) { change, _ -> change.consume(); dragging = (change.position.x / w).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(CircleShape).background(color.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(CircleShape).drawBehind { drawRect(color, size = Size(size.width * (dragging ?: progress()).coerceIn(0f, 1f), size.height)) })
            val h = hover
            if (h != null || tipAlpha > 0.01f) {
                val frac = h ?: 0f
                Text(
                    formatDuration((durationMs * frac).toLong() / 1000),
                    Modifier
                        .offset(x = (boxWidth * frac) - 18.dp, y = (-22).dp)
                        .graphicsLayer { alpha = tipAlpha }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                )
            }
        }
    }
}

/** The queue with the playing song marked; click a row to play it, or drag its handle to reorder. */
@Composable
private fun NowPlayingQueue(
    state: PlaybackUiState,
    backend: PlayerControls,
    npState: DesktopNowPlayingState,
    modifier: Modifier,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val list = rememberLazyListState()
    LaunchedEffect(state.queueIndex) {
        if (state.queueIndex > 0 && npState.draggingIndex == null) {
            list.animateScrollToItem(state.queueIndex - 1)
        }
    }
    val keys = remember(state.queue) { state.queue.map { it.id }.uniqueKeys() }
    var rowHeightPx by remember { mutableFloatStateOf(56f) }
    DesktopScrollHost(list, modifier) { scrollMod ->
        LazyColumn(scrollMod, state = list) {
            itemsIndexed(state.queue, key = { i, _ -> keys[i] }) { i, s ->
                val on = i == state.queueIndex
                val isDragging = npState.draggingIndex == i
                val rowFill by animateColorAsState(
                    when {
                        isDragging -> ink.copy(alpha = 0.20f)
                        on -> ink.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    },
                    motionColorSpec(),
                    label = "queueRow",
                )
                Row(
                    Modifier
                        .then(if (isDragging) Modifier.zIndex(1f) else motionItem())
                        .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = npState.dragOffsetPx
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(rowFill)
                        .desktopInteractive(RoundedCornerShape(8.dp))
                        .clickable { backend.playQueueIndex(i) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        Icons.Rounded.DragHandle,
                        contentDescription = "Reorder",
                        tint = ink.copy(alpha = if (isDragging) 0.9f else 0.45f),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .pointerInput(i, state.queue.size) {
                                detectVerticalDragGestures(
                                    onDragStart = { npState.startDrag(i) },
                                    onDragEnd = { npState.endDrag() },
                                    onDragCancel = { npState.endDrag() },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        npState.updateDrag(dragAmount, rowHeightPx, state.queue.size) { from, to ->
                                            backend.moveInQueue(from, to)
                                        }
                                    },
                                )
                            },
                    )
                    CoverImage(s.coverUri, s.id, size = 40.dp, radius = 6.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (on) MaterialTheme.colorScheme.primary else ink)
                        Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
                    }
                    Text(formatDuration(s.durationSec.toLong()), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Preview(name = "Now playing desktop", widthDp = 1200, heightDp = 700)
@Composable
private fun DesktopNowPlayingViewPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    DesktopNowPlayingView(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls(), io.max.paperplay.ui.previewActions(), onClose = {})
}
