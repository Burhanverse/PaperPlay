package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.Dp
import io.max.paperplay.core.account.IndexProgress
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import io.max.paperplay.core.account.ChatInfo
import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.IndexFilter
import io.max.paperplay.core.account.IndexSelection
import io.max.paperplay.core.account.IndexedSong
import io.max.paperplay.core.account.asSong
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.coroutines.launch

/** Titles of the open chat pages ("index:<id>") for the settings header. */
internal val indexPageTitles = mutableMapOf<String, String>()

/** A search to start a chat page with ("index:<id>"), set when it is opened from a search result. */
internal val indexPageQuery = mutableMapOf<String, String>()

internal fun settingsPageTitle(id: String?): String? = id?.let { subTitles[it] ?: indexPageTitles[it] }

/** Settings > Telegram > Chats and channels: your own chats, what is indexed, and the auto options. */
@Composable
internal fun IndexPage(backend: CoreBackend, stack: MutableList<String>, bottomPadding: Dp = 0.dp) {
    val loggedIn by backend.account.loggedIn.collectAsState()
    val indexed by backend.chatIndex.indexedChats.collectAsState()
    val chatRows by backend.chatIndex.chats.collectAsState()
    val pausedIndex by backend.chatIndex.paused.collectAsState()
    val progressState = backend.chatIndex.progress.collectAsState()
    // Rows only care whether indexing runs, not about each progress tick.
    val indexing by remember { derivedStateOf { progressState.value != null } }
    val scope = rememberCoroutineScope()
    val list = backend.myChats.collectAsState().value
    var auto by remember { mutableStateOf(backend.pref("index_auto") == "true") }
    var autoCopy by remember { mutableStateOf(backend.pref("index_auto_copy") != "false") }
    var autoAdd by remember { mutableStateOf(backend.pref("index_auto_add") == "true") }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ChatInfo?>(null) }
    var libOnly by remember { mutableStateOf(backend.chatIndex.libraryOnlyCount()) }
    var libCopying by remember { mutableStateOf(false) }
    var libPaused by remember { mutableStateOf(false) }
    var libStatus by remember { mutableStateOf<String?>(null) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current

    if (!loggedIn) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Log in with your own account to see your chats and channels here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ stack.add("account") }) { Text("Log in") }
        }
        return
    }
    LaunchedEffect(loggedIn) { runCatching { backend.refreshChats() } }

    // Indexed chats are kept even when the account's chat list has not loaded yet.
    val all = remember(list, indexed) {
        list.orEmpty() + indexed.filterKeys { id -> list.orEmpty().none { it.id == id } }.map { (id, title) -> ChatInfo(id, title, true) }
    }
    val q = search.trim().lowercase()
    val split = remember(all, indexed, q) {
        val shown = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) }
        shown.partition { it.id in indexed }
    }
    val done = split.first
    val rest = split.second
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    removing?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove from the index?") },
            text = { Text("${c.title} is forgotten: its indexed songs are removed from the index. Songs already added to your library, and the chat itself in Telegram, are not touched.") },
            confirmButton = { TextButton({ backend.chatIndex.forget(c.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item(key = "auto") {
            Column {
                SectionLabel("Automatic")
                SwitchRow("Index automatically", auto, { auto = it; backend.setPref("index_auto", "$it") }, Icons.Rounded.Sync,
                    "Reads only newer posts of indexed chats when the app starts and every few hours")
                SwitchRow("Add new songs automatically", autoAdd, { autoAdd = it; backend.setPref("index_auto_add", "$it") }, Icons.Rounded.Add,
                    "Adds songs of your main chat that the bot cannot see, such as posts by other bots, when the index refreshes")
                SwitchRow("Fetch songs automatically", autoCopy, { autoCopy = it; backend.setPref("index_auto_copy", "$it") }, Icons.Rounded.ContentCopy,
                    "Copies songs found in the index into your main chat when a Spotify playlist still waits for them")
                var reupload by remember { mutableStateOf(backend.pref(io.max.paperplay.core.account.COPY_REUPLOAD) == "true") }
                SwitchRow("Send again when a chat blocks copying", reupload, { reupload = it; backend.setPref(io.max.paperplay.core.account.COPY_REUPLOAD, "$it") }, Icons.Rounded.CloudUpload,
                    "Copying uses no data. A chat that blocks it can only be copied by downloading each song and uploading it again: tens of MB per song each way")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A paused read keeps its place; the same button goes on from there.
                    val unfinished = chatRows.any { !it.complete }
                    // Pausing here also pauses a linked device that is doing the reading, and stays paused after a restart.
                    if (indexing) OutlinedButton({ backend.chatIndex.setPaused(true) }) { Text("Pause indexing") }
                    else OutlinedButton({ backend.chatIndex.setPaused(false); backend.refreshIndex { message = it } }, enabled = indexed.isNotEmpty()) {
                        Text(if (unfinished || pausedIndex) "Resume indexing" else "Refresh index now")
                    }
                    if (pausedIndex && !indexing) Text("Indexing is paused. Nothing is read until you resume.", color = muted)
                    // The index as a file: to keep, to move, or to give someone so they can skip reading the same chats.
                    val pickFile = io.max.paperplay.ui.LocalAppEnv.current?.restore?.pickBackup
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ scope.launch { message = exportIndex(backend, null, "all") } }, enabled = indexed.isNotEmpty()) { Text("Export index") }
                        if (pickFile != null) OutlinedButton({ pickFile { src -> scope.launch { message = importIndex(backend, src) } } }) { Text("Import index") }
                    }
                    IndexProgressText(progressState)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    // Songs added to the library with no copy in Telegram ("Bot can't reach"): copied into the main chat, then the bot owns them.
                    if (libOnly > 0 || libCopying) {
                        Text("$libOnly songs are only in your library, not in Telegram.", color = muted)
                        if (!libCopying) OutlinedButton({
                            libCopying = true; libPaused = false
                            scope.launch {
                                val rate = (backend.pref("copy_rate")?.toIntOrNull() ?: 20).coerceIn(5, 120)
                                backend.chatIndex.copyLibraryOnly(60_000L / rate, { libPaused }) { ok, bad, total, why ->
                                    libStatus = (if (libPaused) "Paused: " else "") + "$ok of $total copied" + if (bad > 0) ", $bad failed. ${why.orEmpty()}" else ""
                                }
                                libOnly = backend.chatIndex.libraryOnlyCount()
                                libCopying = false; libPaused = false
                            }
                        }) { Text("Copy them to Telegram") }
                        else OutlinedButton({ libPaused = !libPaused }) { Text(if (libPaused) "Resume copy" else "Pause copy") }
                        libStatus?.let { Text(it, color = muted) }
                    }
                }
                OutlinedTextField(
                    search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    label = { Text("Search chats and channels") }, singleLine = true,
                )
                SectionLabel("Indexed (${done.size})")
                if (done.isEmpty()) Text(if (q.isEmpty()) "Nothing indexed yet." else "No indexed chat matches.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = muted)
            }
        }
        items(done, key = { "d${it.id}" }) { c ->
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsInfo provides false) { SettingsRow(
                c.title, icon = if (c.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Forum,
                subtitle = indexedSubtitle(c, chatRows.firstOrNull { it.id == c.id }, progressState.value?.takeIf { it.chatId == c.id }?.found),
                onClick = { indexPageTitles["index:${c.id}"] = c.title; stack.add("index:${c.id}") },
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    (chatRows.firstOrNull { it.id == c.id }?.publicLink ?: telegramLink(c.id))?.let { link -> IconButton({ uri.openUri(link) }) { AppIcon(Icons.Rounded.OpenInNew, "Open in Telegram") } }
                    IconButton({ removing = c }) { AppIcon(Icons.Rounded.DeleteOutline, "Remove from the index") }
                }
            } }
        }
        item(key = "restHead") {
            Column {
                SectionLabel("Your other chats and channels (${rest.size})")
                when {
                    list == null && indexed.isEmpty() -> Text("Reading your chats", Modifier.padding(20.dp), color = muted)
                    rest.isEmpty() -> Text(if (q.isEmpty()) "No other groups or channels." else "No other chat matches.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = muted)
                }
            }
        }
        items(rest, key = { "r${it.id}" }) { c ->
            SettingsRow(
                c.title, icon = if (c.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Forum,
                subtitle = if (c.isChannel) "Channel" else "Group",
            ) {
                OutlinedButton({ backend.indexChat(c) { message = it } }, enabled = !indexing) { Text("Index") }
            }
        }
    }
}

/** Writes the index of [chatIds] (all when null) to a zip and asks where to save it. Returns what happened, for a status line. */
internal suspend fun exportIndex(backend: CoreBackend, chatIds: Set<Long>?, label: String): String {
    val dir = backend.services.platform.dataDir.trimEnd('/', '\\')
    val zip = "$dir/index-export.zip"
    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { io.max.paperplay.core.app.IndexArchive.export(backend.chatIndex, zip, "$dir/index-export.tmp", chatIds) }
    }
    ok.exceptionOrNull()?.let { return "Could not export: ${it.message}" }
    val m = io.max.paperplay.ui.nowMoment()
    fun two(n: Int) = n.toString().padStart(2, '0')
    val name = "paperplay-index-$label-${m.year}-${two(m.month)}-${two(m.day)}.zip"
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        saveFromFile(name, zip) { if (cont.isActive) cont.resumeWith(Result.success(it)) }
    }
}

/** Merges the index in an index zip or an app backup. Returns what happened, for a status line. */
internal suspend fun importIndex(backend: CoreBackend, source: io.max.paperplay.core.backup.BackupSource): String {
    val tmp = backend.services.platform.dataDir.trimEnd('/', '\\') + "/index-import.tmp"
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { io.max.paperplay.core.app.IndexArchive.import(backend.chatIndex, source, tmp) }.fold(
            { r -> "Imported ${r.posts} songs" + (if (r.chats > 0) " and ${r.chats} new chats" else "") + if (r.notJoined > 0) ". Join ${r.notJoined} of them to copy from them." else "." },
            { "Could not import: ${it.message}" },
        )
    }
}

/** "Channel, 1,234 songs, read 3 h ago" plus whether the whole history was read. */
private fun indexedSubtitle(c: ChatInfo, d: io.max.paperplay.core.account.IndexedChat?, readingNow: Int?): String {
    val kind = if (c.isChannel) "Channel" else "Group"
    if (d == null) return "$kind, indexed"
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val m = if (d.lastRefreshAt > 0) (now - d.lastRefreshAt) / 60_000 else -1
    val when_ = when {
        readingNow != null -> "reading now"
        m < 0 -> "never read"
        m < 1 -> "read just now"
        m < 60 -> "read $m min ago"
        m < 60 * 24 -> "read ${m / 60} h ago"
        else -> "read ${m / (60 * 24)} d ago"
    }
    val songs = readingNow ?: d.postCount
    return "$kind, $songs songs, $when_" + (if (d.complete) "" else ", not fully read") + (if (!d.joined) ", not joined" else "")
}

/** A link that opens the chat in Telegram: channels and supergroups (ids -100...) by their private link; small groups have none. */
private fun telegramLink(chatId: Long): String? {
    val s = chatId.toString()
    return if (s.startsWith("-100") && s.length > 4) "https://t.me/c/${s.drop(4)}/1" else null
}

@Composable
private fun IndexProgressText(state: State<IndexProgress?>) {
    state.value?.let { Text("Indexing ${it.chatTitle}: ${it.found} songs", color = MaterialTheme.colorScheme.primary) }
}

private val reviewFilters = listOf(
    IndexFilter.All to "All", IndexFilter.New to "New", IndexFilter.Better to "Better than yours",
    IndexFilter.Have to "Already have", IndexFilter.Wanted to "Missing, in Spotify lists",
)

private fun sizeText(b: Long) = if (b >= 1L shl 30) "${b / (1L shl 30)} GB" else "${b / (1L shl 20)} MB"

private fun formatOf(p: ChatPost) = io.max.paperplay.core.dupes.DefaultSongRules.format(
    p.asSong(),
)

@Immutable
private class PostRowModel(val id: Long, val title: String, val subtitle: String, val canAdd: Boolean, val song: IndexedSong)

private fun IndexedSong.toRow(): PostRowModel {
    val label = when {
        copied -> "Copied"
        better -> "Better than yours: ${betterWhy ?: "higher quality"}"
        inMainChat -> "Already have"
        ownChat -> "In your main chat, not in your library"
        wanted -> "Missing from your library, in your Spotify playlists"
        else -> null
    }
    return PostRowModel(
        post.messageId, post.title.ifBlank { post.fileName },
        listOfNotNull(post.artist.ifBlank { null }, post.album.ifBlank { null }, formatOf(post), if (post.size > 0) sizeText(post.size) else null, label).joinToString(" · "),
        !copied && !inMainChat, this,
    )
}

/** Which posts "Add all shown" takes for a filter: never ones the library already has. */
private fun addFilter(f: IndexFilter) = when (f) {
    IndexFilter.All, IndexFilter.New -> IndexFilter.New
    IndexFilter.Have -> null
    else -> f
}

private const val PAGE = 200

/**
 * The songs of one indexed chat, read from the saved index a page at a time: a chat of 200,000 songs costs no more than one of 200.
 * Filters, counts and search run in the database; while the chat is being read the page refreshes at most every two seconds.
 * Nothing is picked until tapped.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatSongsPage(backend: CoreBackend, chatId: Long, stack: MutableList<String>, bottomPadding: Dp = 0.dp) {
    val index = backend.chatIndex
    val progressState = index.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(indexPageQuery.remove("index:$chatId").orEmpty()) }
    var search by remember { mutableStateOf(query) }
    var filter by remember { mutableStateOf(IndexFilter.All) }
    var selection by remember { mutableStateOf(IndexSelection()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Copying runs as an app task (Tasks shows it), so it goes on when this page is closed.
    val copier = backend.services.indexCopier
    val copyProgress by copier.progress.collectAsState()
    val copyResult by copier.lastResult.collectAsState()
    // A song being sent again (downloaded, then uploaded) reports its bytes and speed once a second.
    val transfer by backend.account.transfer.collectAsState()
    val copying = copyProgress != null
    // Copies per minute; the pause between two copies is a minute divided by this. Faster copying can run into Telegram's limits.
    var copyRate by remember { mutableStateOf((backend.pref("copy_rate")?.toIntOrNull() ?: 20).coerceIn(5, 120)) }
    var tick by remember { mutableStateOf(0L) }
    var wanted by remember(chatId, filter, search) { mutableStateOf(PAGE) }
    var shown by remember(chatId) { mutableStateOf(emptyList<IndexedSong>()) }
    var counts by remember(chatId) { mutableStateOf(List(reviewFilters.size) { 0 }) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Typing is not a query per key.
    LaunchedEffect(query) { delay(250); search = query.trim() }
    // The index changes on every batch while the chat is read; the page follows at most every two seconds.
    LaunchedEffect(chatId) { index.changes.sample(2_000).collect { tick = it } }
    LaunchedEffect(chatId, filter, search, wanted, tick) {
        val (rows, c) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            index.page(chatId, filter, search, 0, wanted) to index.counts(chatId, search)
        }
        shown = rows
        counts = c
    }
    // More rows load as the list nears its end.
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > listState.layoutInfo.totalItemsCount - 40 } }
    LaunchedEffect(nearEnd, shown.size) { if (nearEnd && shown.size >= wanted) wanted += PAGE }

    val chat = index.chats.collectAsState().value.firstOrNull { it.id == chatId }
    val running by remember { derivedStateOf { progressState.value?.chatId == chatId } }
    // In the main chat itself nothing is copied (it would repeat the post): songs the library lacks are only added to it.
    val own = shown.firstOrNull()?.ownChat == true
    val add = addFilter(filter)
    val addCount = add?.let { counts[reviewFilters.indexOfFirst { f -> f.first == it }] } ?: 0
    val uri = androidx.compose.ui.platform.LocalUriHandler.current

    var more by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (status != null && !copying) { delay(5_000); status = null } }
    val pad = Modifier.padding(horizontal = 20.dp)

    fun copyAll(todo: List<ChatPost>) {
        // In the main chat itself a song is only added to the library: copying would post it twice.
        status = if (copier.start(todo, libraryOnly = own)) null else "A copy is still running. Pause or stop it in Tasks first."
        selection = selection.clear()
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(pad.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ChatLiveHeader(progressState, chatId, counts[0])
                    Row {
                        chat?.publicLink?.let { link -> IconButton({ uri.openUri(link) }) { AppIcon(Icons.Rounded.OpenInNew, "Open in Telegram") } }
                        if (running) IconButton({ index.setPaused(true) }) { AppIcon(Icons.Rounded.Pause, "Pause indexing") }
                        else IconButton({ index.setPaused(false); backend.refreshIndex() }) { AppIcon(Icons.Rounded.Refresh, "Resume or refresh") }
                    }
                }
                if (chat != null && !chat.joined) Row(pad, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From an index file. Join the chat to copy or play its songs.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ scope.launch { status = if (index.join(chatId)) "Joined ${chat.title}" else "Could not join. Open the link in Telegram and join there." } }) { Text("Join") }
                }
                ChipRow(reviewFilters.mapIndexed { i, f -> "${f.second} ${counts[i]}" }, reviewFilters.indexOfFirst { it.first == filter }, { filter = reviewFilters[it].first }, Modifier.padding(horizontal = 4.dp))
                Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ selection = selection.clear() }, enabled = selection.picked.isNotEmpty()) { Text("Clear") }
                    Button(
                        {
                            val picked = selection.picked
                            scope.launch {
                                val todo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (picked.isNotEmpty()) index.postsByIds(chatId, picked) else add?.let { index.select(chatId, it, search) }.orEmpty()
                                }
                                copyAll(todo)
                            }
                        },
                        enabled = !busy && chat?.joined != false && (selection.picked.isNotEmpty() || addCount > 0),
                    ) {
                        val n = if (selection.picked.isNotEmpty()) selection.picked.size else addCount
                        Text(if (selection.picked.isNotEmpty()) "Add $n" else "Add all shown ($n)")
                    }
                }
                copyProgress?.let { p ->
                    Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text((if (p.paused) "Paused: " else if (p.libraryOnly) "Adding " else "Copying ") + "${p.done + p.skipped + p.failed} of ${p.total}" + if (p.failed > 0) ", ${p.failed} failed" else "",
                            Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        OutlinedButton({ copier.setPaused(!p.paused) }) { Text(if (p.paused) "Resume" else "Pause") }
                        TextButton({ copier.stop() }) { Text("Stop") }
                    }
                    transfer?.let { t ->
                        fun mb(b: Long) = "%.1f".format(b / 1_048_576.0)
                        val left = if (t.bytesPerSecond > 0 && t.total > t.done) " · ${(t.total - t.done) / t.bytesPerSecond} s left" else ""
                        Column(pad) {
                            Text(
                                (if (t.upload) "Uploading " else "Downloading ") + "\"${t.title}\": ${mb(t.done)} of ${mb(t.total)} MB · ${mb(t.bytesPerSecond)} MB/s$left",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            androidx.compose.material3.LinearProgressIndicator({ if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    p.why?.let { Text(it, pad, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (!copying) copyResult?.let { Text(it, pad, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(pad) {
                    Text("Copy speed: $copyRate per minute (a copy every ${"%.1f".format(60.0 / copyRate)} s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        copyRate.toFloat(), { copyRate = it.toInt().coerceIn(5, 120) },
                        valueRange = 5f..120f, onValueChangeFinished = { backend.setPref("copy_rate", "$copyRate") },
                    )
                }
                OutlinedTextField(query, { query = it }, pad.fillMaxWidth(), label = { Text("Search title, artist, album or file") }, singleLine = true)
                Text("${counts[1]} of ${counts[0]} are not in your library", pad, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { Text(it, pad, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton({ more = !more }, pad) { Text(if (more) "Less" else "More") }
                if (more) Column(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        {
                            val picked = selection.picked
                            busy = true
                            scope.launch {
                                val todo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { index.postsByIds(chatId, picked) }
                                todo.forEach { runCatching { index.addToLibrary(it) } }
                                status = "Added ${todo.size} to your library only. Nothing is copied to Telegram; they play through your account."
                                selection = selection.clear()
                                busy = false
                            }
                        },
                        enabled = selection.picked.isNotEmpty() && !busy,
                    ) { Text("Add ${selection.picked.size} to library only (no copy in Telegram)") }
                    OutlinedButton({
                        val first = shown.firstOrNull { !it.inMainChat && !it.copied } ?: return@OutlinedButton
                        scope.launch { status = "Asking the bot about \"${first.post.title}\"..."; status = backend.services.botCheck(first.post.chatId, first.post.messageId) }
                    }, enabled = counts[1] > 0) { Text("Check why the first missing song is missing") }
                    OutlinedButton({ scope.launch { status = exportIndex(backend, setOf(chatId), "chat") } }) { Text("Export this chat's index") }
                    OutlinedButton({ forgetting = true }) { Text("Forget this chat") }
                }
            }
        }
        items(shown, key = { "${it.post.chatId}:${it.post.messageId}" }) { s ->
            val r = remember(s) { s.toRow() }
            SongPostRow(r, r.id in selection.picked, thumb = { index.thumb(r.song.post.chatId, r.song.post.messageId) },
                onToggle = { selection = selection.toggle(r.id) },
                onCopy = { scope.launch {
                    status = if (own) runCatching { index.addToLibrary(r.song.post) }.fold({ "Added ${r.title} to your library" }, { "Could not add ${r.title}: ${it.message}" })
                    else runCatching { index.copyToMain(r.song.post) }.fold({ "Copied ${r.title} to your main chat" }, { "Could not copy ${r.title}: ${it.message}" })
                } })
        }
        if (shown.size >= wanted) item(key = "more") { Text("Loading more", pad.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (forgetting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text("Forget ${chat?.title ?: "this chat"}?") },
        text = { Text("Its ${counts[0]} indexed songs are removed from the index, and reading it again can take days. Songs already in your library stay. Export the index first if you may want it back.") },
        confirmButton = { TextButton({ forgetting = false; index.forget(chatId); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }) { Text("Forget") } },
        dismissButton = { TextButton({ forgetting = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ChatLiveHeader(progress: State<IndexProgress?>, chatId: Long, total: Int) {
    val live = progress.value?.takeIf { it.chatId == chatId }
    Text(
        if (live != null) "Indexing: ${live.found} songs so far" else "$total songs",
        color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The small cover Telegram keeps inside the post, decoded once per row and only when the row is on screen. */
@OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
@Composable
private fun PostThumb(key: Long, load: () -> ByteArray?) {
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { load()?.decodeToImageBitmap() }.getOrNull()
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val m = Modifier.size(40.dp).clip(shape)
    img?.let { androidx.compose.foundation.Image(it, null, m, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        ?: androidx.compose.foundation.layout.Box(m.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = androidx.compose.ui.Alignment.Center) {
            AppIcon(Icons.Rounded.MusicNote, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
}

@Composable
private fun SongPostRow(r: PostRowModel, picked: Boolean, thumb: () -> ByteArray?, onToggle: () -> Unit, onCopy: () -> Unit) {
    // Song details are long, but they are facts, not an explanation to hide behind an info button.
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsInfo provides false) {
        SettingsRow(r.title, onClick = onToggle, subtitle = r.subtitle, leading = { PostThumb(r.song.post.messageId, thumb) }) {
            Checkbox(picked, { onToggle() })
            IconButton(onCopy, enabled = r.canAdd) { AppIcon(Icons.Rounded.Add, "Copy to main chat") }
        }
    }
}

/** For a track a Spotify playlist still waits for: the best song the index has, with a plus button to copy it. */
@Composable
internal fun IndexMatchRow(backend: CoreBackend, title: String, artist: String, durationSec: Int) {
    val scope = rememberCoroutineScope()
    var post by remember(title, artist) { mutableStateOf<ChatPost?>(null) }
    var state by remember(title, artist) { mutableStateOf<String?>(null) }
    LaunchedEffect(title, artist) {
        post = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { backend.chatIndex.matchesFor(title, artist, durationSec).firstOrNull() }.getOrNull()
        }
    }
    val p = post ?: return
    val chat = backend.chatIndex.indexedChats.value[p.chatId] ?: "your chats"
    Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(state ?: "In $chat: ${p.title} - ${p.artist}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (state == null) IconButton({ scope.launch { state = if (runCatching { backend.chatIndex.copyToMain(p) }.isSuccess) "Copying" else "Could not copy" } }) {
            AppIcon(Icons.Rounded.Add, "Copy to main chat")
        }
    }
}

@Preview
@Composable
// Changed visibility from private to internal so Compose Preview reflection can locate and invoke the method
internal fun SongPostRowPreview() {
    val sampleSong = IndexedSong(
        post = ChatPost(
            chatId = 1, messageId = 1, uniqueId = "1_1", title = "Supermassive Black Hole",
            artist = "Muse", durationSec = 209, size = 10_000_000, mime = "audio/mpeg", fileName = "muse.mp3", date = 0,
        ),
        inMainChat = false,
        copied = false,
        better = true,
        wanted = false,
    )
    SongPostRow(r = sampleSong.toRow(), picked = true, thumb = { null }, onToggle = {}, onCopy = {})
}

@Preview(name = "Progress text", showBackground = true)
@Composable
private fun IndexProgressTextPreview() = io.max.paperplay.ui.PreviewFrame {
    IndexProgressText(remember { mutableStateOf<IndexProgress?>(IndexProgress(1, "My Music", 42, true)) })
}

@Preview(name = "Live header", showBackground = true)
@Composable
private fun ChatLiveHeaderPreview() = io.max.paperplay.ui.PreviewFrame {
    ChatLiveHeader(remember { mutableStateOf<IndexProgress?>(IndexProgress(1, "My Music", 42, true)) }, chatId = 1, total = 120)
}
