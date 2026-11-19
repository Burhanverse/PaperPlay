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
import io.max.paperplay.core.account.IndexSelection
import io.max.paperplay.core.account.IndexedSong
import io.max.paperplay.core.account.asSong
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Titles of the open chat pages ("index:<id>") for the settings header. */
internal val indexPageTitles = mutableMapOf<String, String>()

internal fun settingsPageTitle(id: String?): String? = id?.let { subTitles[it] ?: indexPageTitles[it] }

/** Settings > Telegram > Chats and channels: your own chats, what is indexed, and the auto options. */
@Composable
internal fun IndexPage(backend: CoreBackend, stack: MutableList<String>, bottomPadding: Dp = 0.dp) {
    val loggedIn by backend.account.loggedIn.collectAsState()
    val indexed by backend.chatIndex.indexedChats.collectAsState()
    val progressState = backend.chatIndex.progress.collectAsState()
    // Rows only care whether indexing runs, not about each progress tick.
    val indexing by remember { derivedStateOf { progressState.value != null } }
    val scope = rememberCoroutineScope()
    val list = backend.myChats.collectAsState().value
    var auto by remember { mutableStateOf(backend.pref("index_auto") == "true") }
    var autoCopy by remember { mutableStateOf(backend.pref("index_auto_copy") == "true") }
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
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A paused read keeps its place; the same button goes on from there.
                    val unfinished = indexed.keys.any { backend.chatIndex.details(it)?.complete == false }
                    if (indexing) OutlinedButton({ backend.chatIndex.stopRun() }) { Text("Pause indexing") }
                    else OutlinedButton({ backend.refreshIndex { message = it } }, enabled = indexed.isNotEmpty()) {
                        Text(if (unfinished) "Resume indexing" else "Refresh index now")
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
                subtitle = indexedSubtitle(c, backend.chatIndex.details(c.id), progressState.value?.takeIf { it.chatId == c.id }?.found),
                onClick = { indexPageTitles["index:${c.id}"] = c.title; stack.add("index:${c.id}") },
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    telegramLink(c.id)?.let { link -> IconButton({ uri.openUri(link) }) { AppIcon(Icons.Rounded.OpenInNew, "Open in Telegram") } }
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

/** "Channel, 1,234 songs, read 3 h ago" plus whether the whole history was read. */
private fun indexedSubtitle(c: ChatInfo, d: io.max.paperplay.core.account.ChatIndexDetails?, readingNow: Int?): String {
    val kind = if (c.isChannel) "Channel" else "Group"
    if (d == null) return "$kind, indexed"
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val m = if (d.refreshedAt > 0) (now - d.refreshedAt) / 60_000 else -1
    val when_ = when {
        readingNow != null -> "reading now"
        m < 0 -> "never read"
        m < 1 -> "read just now"
        m < 60 -> "read $m min ago"
        m < 60 * 24 -> "read ${m / 60} h ago"
        else -> "read ${m / (60 * 24)} d ago"
    }
    val songs = readingNow ?: d.songs
    return "$kind, $songs songs, $when_" + if (d.complete) "" else ", not fully read"
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

private val reviewFilters = listOf("All", "New", "Better than yours", "Already have", "Missing, in Spotify lists")

private fun sizeText(b: Long) = if (b >= 1L shl 30) "${b / (1L shl 30)} GB" else "${b / (1L shl 20)} MB"

private fun formatOf(p: ChatPost) = io.max.paperplay.core.dupes.DefaultSongRules.format(
    p.asSong(),
)

@Immutable
private class ChatView(val shown: List<IndexedSong>, val counts: List<Int>, val missing: Int)

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
        listOfNotNull(post.artist.ifBlank { null }, formatOf(post), if (post.size > 0) sizeText(post.size) else null, label).joinToString(" · "),
        !copied && !inMainChat, this,
    )
}

/** The songs of one indexed chat, live while it is still being read. Nothing is picked until tapped. */
@Composable
internal fun ChatSongsPage(backend: CoreBackend, chatId: Long, stack: MutableList<String>, bottomPadding: Dp = 0.dp) {
    val songs by remember(chatId) { backend.chatIndex.songs(chatId) }.collectAsState(emptyList())
    val progressState = backend.chatIndex.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf(IndexSelection()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Copies per minute; the pause between two copies is a minute divided by this. Faster copying can run into Telegram's limits.
    var copying by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var copyRate by remember { mutableStateOf((backend.pref("copy_rate")?.toIntOrNull() ?: 20).coerceIn(5, 120)) }

    fun inFilter(s: IndexedSong, f: Int) = when (f) {
        1 -> !s.inMainChat && !s.copied
        2 -> s.better
        3 -> s.inMainChat
        4 -> s.wanted && !s.inMainChat && !s.copied
        else -> true
    }
    // Counts, filtering and sorting of tens of thousands of posts run off the main thread, in one pass; rows are built only for what is on screen.
    val view by produceState(ChatView(emptyList(), List(reviewFilters.size) { 0 }, 0), songs, query, filter) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val q = query.trim().lowercase()
            val counts = IntArray(reviewFilters.size)
            var missingCount = 0
            val found = ArrayList<IndexedSong>()
            for (s in songs) {
                for (f in counts.indices) if (inFilter(s, f)) counts[f]++
                if (!s.inMainChat && !s.copied) missingCount++
                if (inFilter(s, filter) && (q.isEmpty() || "${s.post.title} ${s.post.artist}".lowercase().contains(q))) found += s
            }
            // Songs that are not in the library come first; each group keeps its newest-first order.
            ChatView(found.sortedBy { it.inMainChat || it.copied }, counts.toList(), missingCount)
        }
    }
    val shown = view.shown
    val counts = view.counts
    val chipLabels = remember(counts) { reviewFilters.mapIndexed { i, l -> "$l ${counts[i]}" } }
    val missing = view.missing
    val shownMissing = remember(shown) { shown.count { !it.inMainChat && !it.copied } }
    val running by remember { derivedStateOf { progressState.value?.chatId == chatId } }
    // In the main chat itself nothing is copied (it would repeat the post): songs the library lacks are only added to it.
    val own = songs.firstOrNull()?.ownChat == true

    var more by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (status != null) { delay(5_000); status = null } }
    val pad = Modifier.padding(horizontal = 20.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(pad.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ChatLiveHeader(progressState, chatId, songs.size)
                    if (running) IconButton({ backend.chatIndex.stopRun() }) { AppIcon(Icons.Rounded.Pause, "Pause indexing") }
                    else IconButton({ backend.refreshIndex() }) { AppIcon(Icons.Rounded.Refresh, "Refresh index") }
                }
                ChipRow(chipLabels, filter, { filter = it }, Modifier.padding(horizontal = 4.dp))
                Row(pad, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ selection = IndexSelection(shown.map { it.post.messageId }.toSet()) }, enabled = shown.isNotEmpty()) { Text("Select all") }
                    OutlinedButton({ selection = selection.clear() }, enabled = selection.picked.isNotEmpty()) { Text("Clear") }
                    Button(
                        {
                            val todo = if (selection.picked.isNotEmpty()) selection.toCopy(songs) else shown.filter { !it.inMainChat && !it.copied }.map { it.post }
                            busy = true
                            scope.launch {
                                if (own) {
                                    todo.forEach { runCatching { backend.chatIndex.addToLibrary(it) } }
                                    status = "Added ${todo.size} to your library. They are already in your main chat."
                                    selection = selection.clear()
                                    busy = false
                                    return@launch
                                }
                                // Copied one by one into the main chat. A song leaves these lists only once Telegram accepted its copy.
                                var ok = 0
                                var failed = 0
                                var why: String? = null
                                copying = true
                                paused = false
                                todo.forEachIndexed { i, p ->
                                    // Paused between two copies: what is done stays done, and the rest waits here.
                                    while (paused) { status = "Paused: $i of ${todo.size} copied"; delay(300) }
                                    status = "Copying ${i + 1} of ${todo.size} to your main chat"
                                    runCatching { backend.chatIndex.copyToMain(p) }.onSuccess { ok++ }.onFailure { failed++; if (why == null) why = it.message }
                                    if (i < todo.size - 1) delay(60_000L / copyRate)
                                }
                                status = if (failed == 0) "Copied $ok to your main chat" else "Copied $ok, $failed failed. ${why.orEmpty()}"
                                selection = selection.clear()
                                copying = false
                                paused = false
                                busy = false
                            }
                        },
                        enabled = !busy && (selection.picked.isNotEmpty() || shownMissing > 0),
                    ) {
                        val n = if (selection.picked.isNotEmpty()) selection.picked.size else shownMissing
                        Text(if (selection.picked.isNotEmpty()) "Add $n" else "Add all shown ($n)")
                    }
                }
                if (copying) OutlinedButton({ paused = !paused }, pad) { Text(if (paused) "Resume copy" else "Pause copy") }
                Column(pad) {
                    Text("Copy speed: $copyRate per minute (a copy every ${"%.1f".format(60.0 / copyRate)} s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        copyRate.toFloat(), { copyRate = it.toInt().coerceIn(5, 120) },
                        valueRange = 5f..120f, onValueChangeFinished = { backend.setPref("copy_rate", "$copyRate") },
                    )
                }
                OutlinedTextField(query, { query = it }, pad.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
                Text("$missing of ${songs.size} are not in your library", pad, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { Text(it, pad, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton({ more = !more }, pad) { Text(if (more) "Less" else "More") }
                if (more) Column(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        {
                            val todo = selection.toCopy(songs)
                            busy = true
                            scope.launch {
                                todo.forEach { runCatching { backend.chatIndex.addToLibrary(it) } }
                                status = "Added ${todo.size} to your library only. Nothing is copied to Telegram; they play through your account."
                                selection = selection.clear()
                                busy = false
                            }
                        },
                        enabled = selection.picked.isNotEmpty() && !busy,
                    ) { Text("Add ${selection.picked.size} to library only (no copy in Telegram)") }
                    OutlinedButton({
                        val first = songs.firstOrNull { !it.inMainChat && !it.copied } ?: return@OutlinedButton
                        scope.launch { status = "Asking the bot about \"${first.post.title}\"..."; status = backend.services.botCheck(first.post.chatId, first.post.messageId) }
                    }, enabled = missing > 0) { Text("Check why the first missing song is missing") }
                    OutlinedButton({ backend.chatIndex.forget(chatId); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }) { Text("Forget this chat") }
                }
            }
        }
        items(shown, key = { "${it.post.chatId}:${it.post.messageId}" }) { s ->
            val r = remember(s) { s.toRow() }
            SongPostRow(r, r.id in selection.picked,
                onToggle = { selection = selection.toggle(r.id) },
                onCopy = { scope.launch {
                    status = if (own) runCatching { backend.chatIndex.addToLibrary(r.song.post) }.fold({ "Added ${r.title} to your library" }, { "Could not add ${r.title}: ${it.message}" })
                    else runCatching { backend.chatIndex.copyToMain(r.song.post) }.fold({ "Copied ${r.title} to your main chat" }, { "Could not copy ${r.title}: ${it.message}" })
                } })
        }
    }
}

@Composable
private fun ChatLiveHeader(progress: State<IndexProgress?>, chatId: Long, total: Int) {
    val live = progress.value?.takeIf { it.chatId == chatId }
    Text(
        if (live != null) "Indexing: ${live.found} songs read so far" else "$total songs",
        color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SongPostRow(r: PostRowModel, picked: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    // Song details are long, but they are facts, not an explanation to hide behind an info button.
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsInfo provides false) {
        SettingsRow(r.title, onClick = onToggle, subtitle = r.subtitle) {
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
    SongPostRow(r = sampleSong.toRow(), picked = true, onToggle = {}, onCopy = {})
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
