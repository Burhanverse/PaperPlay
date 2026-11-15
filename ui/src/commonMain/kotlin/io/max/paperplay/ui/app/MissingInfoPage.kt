package io.max.paperplay.ui.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.SongMenu
import io.max.paperplay.ui.logic.Selection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.integrations.ExportList
import io.max.paperplay.core.integrations.ExportRow
import io.max.paperplay.core.integrations.exportCsv
import io.max.paperplay.core.library.EnrichState
import io.max.paperplay.core.library.Song
import io.max.paperplay.ui.components.AppIcon

/** Something a song still lacks. [todo] ones are work not done yet; the rest were looked for and not found. */
/** A filter to open the page with, set by whoever sends the owner here (the Tasks page). Read once. */
internal object MissingStart {
    var filter: Missing? = null
    fun take(): Missing? = filter.also { filter = null }
}

internal enum class Missing(val label: String, val todo: Boolean = false) {
    Waiting("Waiting for song info", true),
    ScanPending("Lyrics and genre not looked up yet", true),
    NoCover("No cover yet", true),
    NoMatch("No match found"),
    SmallCover("Small cover only"),
    NoAlbum("No album"),
    NoYear("No year"),
    NoGenre("No genre"),
    UnknownArtist("Unknown artist"),
}

/** What [s] still lacks. With [lookupsOn] off the lyrics and genre scan never runs, so it is not counted. */
internal fun missingOf(s: Song, lookupsOn: Boolean): Set<Missing> {
    val looked = s.enrich_state != EnrichState.PENDING
    val scanned = !lookupsOn || s.mood_scanned_at > 0
    return buildSet {
        if (!looked) add(Missing.Waiting)
        if (lookupsOn && !scanned) add(Missing.ScanPending)
        if (s.cover_path == null) add(Missing.NoCover) else if (!s.cover_hi_res && s.cover_at == 0L) add(Missing.SmallCover)
        if (s.enrich_state == EnrichState.NO_MATCH) add(Missing.NoMatch)
        if (looked && s.album.isEmpty()) add(Missing.NoAlbum)
        if (looked && s.year == null) add(Missing.NoYear)
        if (looked && scanned && s.genre == null) add(Missing.NoGenre)
        if (s.artist == "Unknown artist") add(Missing.UnknownArtist)
    }
}

/** Every song that still lacks something, what, selecting, a way to look them up again, and a CSV of the list. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun MissingInfoPage(backend: CoreBackend) {
    val all by backend.services.library.allSongs().collectAsState(emptyList())
    val taste by backend.taste.collectAsState()
    val running by backend.services.enricher.running.collectAsState()
    val coversQueued by backend.services.covers.queued.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listActions = LocalListActions.current
    var only by remember { mutableStateOf(MissingStart.take()) }
    var note by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(Selection()) }

    // One row per song, not one per version.
    val songs = remember(all) { all.distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" } }
    val rows = songs.map { it to missingOf(it, taste.lookups) }.filter { it.second.isNotEmpty() }
    val counts = Missing.entries.associateWith { m -> rows.count { m in it.second } }
    val shown = rows.filter { only == null || only in it.second }
        .sortedWith(compareByDescending<Pair<Song, Set<Missing>>> { it.second.size }.thenBy { it.first.title })
    val scheme = MaterialTheme.colorScheme

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalPageBottomPadding.current)) {
        item {
            Column(Modifier.fillMaxWidth()) {
                // 1. Where the library stands.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (rows.isEmpty()) "All ${songs.size} songs are complete" else "${songs.size - rows.size} of ${songs.size} songs are complete",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (songs.isNotEmpty()) androidx.compose.material3.LinearProgressIndicator(
                        progress = { (songs.size - rows.size).toFloat() / songs.size }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        listOfNotNull(
                            if (running) "Looking up song info now" else "No song info lookup running",
                            if (coversQueued > 0) "Loading $coversQueued covers" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                if (rows.isNotEmpty()) {
                    HorizontalDivider()
                    // 2. What is missing: tap one to see only those songs.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Show only", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            for (m in Missing.entries) if (counts.getValue(m) > 0) {
                                FilterChip(selected = only == m, onClick = { only = if (only == m) null else m }, label = { Text("${m.label} (${counts.getValue(m)})") })
                            }
                        }
                    }
                    HorizontalDivider()
                    // 3. What to do with them. The buttons wrap, so none is squeezed.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Actions", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = {
                                val picked = selection.ids
                                val list = shown.map { it.first }.let { l -> if (picked.isEmpty()) l else l.filter { it.unique_id in picked } }
                                val n = backend.services.enricher.retrySongs(list)
                                selection = Selection()
                                note = if (n == 0) "Nothing to look up." else "Looking up $n songs again, info and covers."
                            }) {
                                AppIcon(Icons.Rounded.Refresh, null, Modifier.padding(end = 8.dp))
                                Text("Look up again")
                            }
                            OutlinedButton(enabled = shown.isNotEmpty(), onClick = { selection = Selection(shown.map { it.first.unique_id }.toSet()) }) { Text("Select all") }
                            if (selection.ids.isNotEmpty()) OutlinedButton(onClick = { selection = Selection() }) { Text("Clear ${selection.ids.size}") }
                            val noMatch = rows.filter { Missing.NoMatch in it.second }.map { it.first }
                            if (noMatch.isNotEmpty()) OutlinedButton(onClick = {
                                val n = backend.services.enricher.retrySongs(noMatch)
                                note = if (n == 0) "Nothing to look up." else "Retrying $n songs with no match."
                            }) { Text("Retry no match (${noMatch.size})") }
                            OutlinedButton(enabled = shown.isNotEmpty(), onClick = {
                                val list = ExportList("missing", "Songs missing info", 0, shown.map { (s, _) -> ExportRow(s.title, s.artist, s.duration_sec.toInt()) })
                                clipboard.setText(AnnotatedString(exportCsv(list)))
                                note = "Copied ${shown.size} songs as CSV."
                            }) { Text("Copy CSV") }
                            OutlinedButton(enabled = shown.isNotEmpty(), onClick = {
                                val list = ExportList("missing", "Songs missing info", 0, shown.map { (s, _) -> ExportRow(s.title, s.artist, s.duration_sec.toInt()) })
                                saveText(backend.services.platform.dataDir, "paperplay-missing-info", exportCsv(list)) { note = it }
                            }) { Text("Save file") }
                        }
                        Text(
                            note ?: "Look up again redoes the songs shown by the filter, or the ones you selected. Songs you edited keep their info.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Text(
                        "${shown.size} ${if (shown.size == 1) "song" else "songs"}" + (only?.let { " · ${it.label}" } ?: ""),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(shown, key = { it.first.unique_id }) { (s, missing) ->
            val picked = s.unique_id in selection.ids
            val ui = remember(s) { s.toUi() }
            Column {
                MissingSongRow(
                    ui, s.title, s.artist, missing, picked,
                    // Tapping plays the song (with the shown songs after it); while some are picked it picks instead.
                    onClick = {
                        if (selection.ids.isNotEmpty()) selection = selection.toggle(s.unique_id)
                        else listActions?.play(shown.map { it.first.toUi() }, shown.indexOfFirst { it.first.unique_id == s.unique_id }.coerceAtLeast(0))
                    },
                    onLongClick = { selection = selection.toggle(s.unique_id) },
                    menu = listActions?.let { la -> @Composable { SongMenu(ui, la.rowActions()) } },
                )
                HorizontalDivider(Modifier.padding(start = 72.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun MissingSongRow(
    ui: io.max.paperplay.ui.state.SongUi,
    title: String,
    artist: String,
    missing: Set<Missing>,
    picked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: (@Composable () -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (picked) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (picked) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            AppIcon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(28.dp), tint = scheme.primary)
        } else CoverImage(ui.coverUri, ui.album.ifBlank { ui.title.ifBlank { ui.id } }, size = 44.dp, radius = 8.dp, coverId = ui.id)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in Missing.entries) if (m in missing) {
                    Text(
                        m.label, style = MaterialTheme.typography.labelSmall,
                        color = if (m.todo) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (m.todo) scheme.primaryContainer else scheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        menu?.invoke()
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MissingSongRowPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(390.dp, 200.dp)) {
            MissingSongRow(previewUi, previewUi.title, previewUi.artist, setOf(Missing.Waiting, Missing.NoCover, Missing.NoYear), false, {}, {}, null)
            MissingSongRow(previewUi, previewUi.title, previewUi.artist, setOf(Missing.NoGenre), true, {}, {}, null)
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MissingSongRowDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(1024.dp, 200.dp)) {
            MissingSongRow(previewUi, previewUi.title, previewUi.artist, setOf(Missing.NoMatch, Missing.NoAlbum), false, {}, {}, null)
        }
    }
}

private val previewUi = io.max.paperplay.ui.state.SongUi(id = "1", title = "Sample song", artist = "Sample artist")

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MissingChipPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 200.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("${Missing.NoCover.label} (5)") })
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MissingChipDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(1024.dp, 200.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("${Missing.NoCover.label} (5)") })
        }
    }
}
