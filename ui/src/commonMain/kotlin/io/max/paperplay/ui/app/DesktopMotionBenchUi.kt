package io.max.paperplay.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.FakePlayerControls
import io.max.paperplay.ui.components.SongRow
import io.max.paperplay.ui.previewActions
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.PaperPlayTheme
import io.max.paperplay.ui.theme.animatedWidth
import io.max.paperplay.ui.theme.panelEnter
import io.max.paperplay.ui.theme.panelExit

fun createDesktopBenchSongs(count: Int = 500): List<SongUi> = List(count) { i ->
    SongUi(
        id = "bench-$i",
        title = "Track ${i + 1} - Deterministic Motion Sample",
        artist = "Artist ${(i % 25) + 1}",
        album = "Album ${(i % 12) + 1}",
        durationSec = 180 + (i % 120),
        qualityLabel = if (i % 3 == 0) "FLAC 24/96" else "320 kbps",
        lossless = i % 3 == 0,
        favorite = i % 7 == 0,
    )
}

@Composable
fun DesktopMotionBenchUi(
    songs: List<SongUi>,
    trackIndex: Int,
    showNow: Boolean,
    showSettings: Boolean,
    pageName: String,
    navMoveForward: Int, // -1 Back, 0 Switch, 1 Forward
    sidebarExpanded: Boolean,
    showPanel: Boolean,
    showMini: Boolean,
    listState: LazyListState,
    onCloseNow: () -> Unit,
    onOpenNow: () -> Unit,
) {
    val controls = remember { FakePlayerControls() }
    val actions = remember { previewActions() }
    val rowActions = remember(actions) { actions.rowActions() }
    val pb = remember(songs, trackIndex) {
        val current = songs[trackIndex.coerceIn(songs.indices)]
        PlaybackUiState(
            current = current,
            playing = true,
            durationMs = current.durationSec * 1000L,
            volume = 0.8f,
            queue = songs.take(40),
            queueIndex = trackIndex.coerceIn(0, 39),
        )
    }
    val move = when {
        navMoveForward > 0 -> NavMove.Forward
        navMoveForward < 0 -> NavMove.Back
        else -> NavMove.Switch
    }

    PaperPlayTheme(dark = true, dynamic = false) {
        val density = LocalDensity.current
        val shiftPx = with(density) { Motion.Desktop.PageShiftDp.dp.roundToPx() }
        val sideShiftPx = with(density) { Motion.Desktop.PageSideShiftDp.dp.roundToPx() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier
                        .animatedWidth(if (sidebarExpanded) 220.dp else 72.dp)
                        .fillMaxHeight()
                        .deskCard()
                        .padding(12.dp),
                ) {
                    Text(if (sidebarExpanded) "Sidebar" else "SB", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).fillMaxHeight().deskCard()) {
                    AnimatedContent(
                        targetState = DeskKey(showNow, showSettings, null, pageName),
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { desktopPageTransition(move, reduced = false, shiftPx = shiftPx, sideShiftPx = sideShiftPx) },
                        label = "benchPage",
                    ) { k ->
                        when {
                            k.now -> DesktopNowPlayingView(pb, controls, actions, onCloseNow, Modifier.fillMaxSize())
                            k.settings -> Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
                            k.page == "songs" -> DesktopScrollHost(listState, Modifier.fillMaxSize()) { scrollMod ->
                                LazyColumn(scrollMod, state = listState) {
                                    itemsIndexed(songs, key = { _, s -> s.id }) { _, s ->
                                        SongRow(s, onClick = {}, onLongClick = {}, actions = rowActions)
                                    }
                                }
                            }
                            else -> Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Page: ${k.page}", style = MaterialTheme.typography.headlineMedium) }
                        }
                    }
                }
                AnimatedVisibility(showPanel && !showNow, enter = panelEnter(), exit = panelExit()) {
                    Row {
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.width(300.dp).fillMaxHeight().deskCard().padding(16.dp)) {
                            Text("Now Playing Panel", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PlayerBar(pb, 0.8f, controls, actions, onOpen = onOpenNow)
        }
        if (showMini) {
            Box(Modifier.width(400.dp).height(110.dp)) {
                DesktopMiniBodyForBench(pb, controls)
            }
        }
    }
}
