package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.GraphicEq
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.state.SongUi
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Synced lyrics for the current song: the current line is bold and kept in view, any line seeks when clicked.
 * Plain (untimed) lyrics scroll as text. Colours come from the surrounding theme, so wrap it in [CoverTinted] for the cover wash.
 */
@Composable
fun LyricsPane(song: SongUi, backend: PlayerControls, large: Boolean, modifier: Modifier = Modifier) {
    val id = song.id
    var text by remember(id) { mutableStateOf<String?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { text = backend.lyricsFor(id); loaded = true }
    var choosing by remember(id) { mutableStateOf(false) }
    val lines = remember(text) { text?.let { io.max.paperplay.core.metadata.parseLrc(it) }.orEmpty() }
    val pos = backend.position.collectAsState()
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= pos.value.positionMs } } }
    val list = rememberLazyListState()
    // The view follows the song until the person scrolls; then a "Sync" pill brings it back to the current line.
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var following by remember(id) { mutableStateOf(true) }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(current, following) {
        if (following && current >= 0) {
            // The current line rests about a third of the way down, so the next lines are already visible below it.
            val offset = -(list.layoutInfo.viewportSize.height * 0.3f).toInt()
            list.animateScrollToItem(current, offset)
        }
    }
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val style = if (large) MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp)
        else MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
    Box(modifier) {
        when {
            !loaded -> Text("Looking for lyrics", Modifier.padding(24.dp), style = style, color = ink.copy(alpha = 0.6f))
            text == null -> Text("No lyrics found for this song.", Modifier.padding(24.dp), style = style, color = ink.copy(alpha = 0.6f))
            lines.isEmpty() -> PlainLyrics(text.orEmpty(), style, ink, backend, song.durationSec * 1000L, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 120.dp, bottom = 320.dp)) {
                itemsIndexed(lines) { i, l ->
                    // Sung lines dim, the line being sung is full strength, and the lines still to come are a lighter tint.
                    val alpha by androidx.compose.animation.core.animateFloatAsState(
                        if (i == current) 1f else if (i < current) 0.32f else 0.62f, androidx.compose.animation.core.tween(300),
                    )
                    Text(
                        l.text.ifBlank { " " },
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { following = true; backend.seekToMs(l.timeMs) }.padding(horizontal = 8.dp, vertical = if (large) 14.dp else 9.dp),
                        style = style,
                        color = ink.copy(alpha = alpha),
                    )
                }
            }
        }
        if (lines.isNotEmpty() && !following) androidx.compose.material3.Surface(
            { following = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            shape = RoundedCornerShape(50), color = ink, contentColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp))
                Text("Sync", style = MaterialTheme.typography.titleSmall)
            }
        }
        androidx.compose.material3.IconButton({ choosing = true }, Modifier.align(Alignment.TopEnd)) {
            AppIcon(Icons.Rounded.Translate, "Choose lyrics", tint = ink.copy(alpha = 0.7f))
        }
    }
    if (choosing) LyricsChoiceDialog(backend, song, onPicked = { text = it; choosing = false }, onDismiss = { choosing = false })
}

/** Lists the versions of this song's lyrics the sources have; picking one keeps it for this song on every linked device. */
@Composable
private fun LyricsChoiceDialog(backend: PlayerControls, song: SongUi, onPicked: (String?) -> Unit, onDismiss: () -> Unit) {
    var choices by remember(song.id) { mutableStateOf<List<LyricsChoiceUi>?>(null) }
    LaunchedEffect(song.id) { choices = backend.lyricsChoices(song.id) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose lyrics") },
        text = {
            val list = choices
            when {
                list == null -> Text("Looking for versions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text("No other versions found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(list) { _, c ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { backend.chooseLyrics(song.id, c.text); onPicked(c.text) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall)
                            Text(c.preview, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Close") } },
        dismissButton = { androidx.compose.material3.TextButton({ backend.chooseLyrics(song.id, null); onPicked(null) }) { Text("Automatic") } },
    )
}

/**
 * Lyrics with no timestamps (any language) cannot be followed line by line, so they drift down in step with the song: the scroll
 * position tracks the share of the song played. Dragging them takes over; after a few quiet seconds they follow the song again.
 */
@Composable
private fun PlainLyrics(text: String, style: androidx.compose.ui.text.TextStyle, ink: androidx.compose.ui.graphics.Color, backend: PlayerControls, totalMs: Long, modifier: Modifier) {
    val scroll = rememberScrollState()
    val pos = backend.position.collectAsState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var following by remember(text) { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) following = false
        else if (!following) { kotlinx.coroutines.delay(6000); following = true }
    }
    LaunchedEffect(text, following) {
        androidx.compose.runtime.snapshotFlow {
            // The first and last stretch of a song are usually instrumental, so the text is spread over the middle.
            val played = if (totalMs > 0) pos.value.positionMs.toFloat() / totalMs else 0f
            (((played - 0.05f) / 0.88f).coerceIn(0f, 1f) * scroll.maxValue).toInt()
        }.collect { target -> if (following) scroll.animateScrollTo(target, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
    }
    Column(modifier.verticalScroll(scroll).padding(24.dp)) { Text(text, style = style, color = ink) }
}

/** Lyrics filling the whole content area, with the song named at the top and a way back. */
@Composable
fun FullLyricsView(song: SongUi, backend: PlayerControls, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            IconButton(onClose) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Close lyrics", tint = ink) }
            Column(Modifier.padding(start = 8.dp)) {
                Text(song.title, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = ink)
                Text(song.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
            }
        }
        LyricsPane(song, backend, large = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp))
    }
}

@Preview(name = "Full lyrics", widthDp = 900, heightDp = 600)
@Composable
private fun FullLyricsViewPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    FullLyricsView(io.max.paperplay.ui.previewSongs[0], io.max.paperplay.ui.FakePlayerControls(), onClose = {})
}
