package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/** What the scan page shows. [lines] is the running log; [summary] appears when the run ends. */
internal data class RepairUi(val running: Boolean = false, val lines: List<String> = emptyList(), val summary: List<String> = emptyList(), val gone: Int = 0)

private const val SRC_MAIN = "main"
private const val SRC_ACCOUNT = "account"
private fun srcBot(id: Long) = "bot$id"

/** Sources the owner switched off, saved as one comma separated pref. Everything is on until switched off. */
private fun sourcesOff(backend: CoreBackend): Set<String> = backend.pref("scan_off")?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

/**
 * One page that reads the library chats again with every source switched on: the main bot, each extra bot and your account.
 * It adds missing songs and lists songs whose post is gone (removing them needs a yes). Moving and deleting stay on their
 * own pages.
 */
@Composable
internal fun RepairPage(backend: CoreBackend, stack: MutableList<String>) {
    var ui by remember { mutableStateOf(RepairUi()) }
    var off by remember { mutableStateOf(sourcesOff(backend)) }
    fun toggle(key: String, on: Boolean) {
        off = if (on) off - key else off + key
        backend.setPref("scan_off", off.joinToString(","))
    }
    val loggedIn by backend.account.loggedIn.collectAsState()
    val extraBots by backend.services.botPool.bots.collectAsState()
    val main by backend.services.botPool.mainBot.collectAsState()
    RepairContent(
        ui,
        speed = {
            Text("Read with", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                main.name ?: main.username ?: "Main bot", SRC_MAIN !in off, { toggle(SRC_MAIN, it) }, null,
                "Main bot. Reads the chats and checks every saved song still exists",
            )
            for (b in extraBots) SwitchRow(
                b.name ?: b.username ?: "Bot ${b.id}", srcBot(b.id) !in off, { toggle(srcBot(b.id), it) }, null,
                "Extra bot, " + if (b.state == "working") "connected. Sees the songs it posted itself" else (b.error ?: "not connected yet"),
            )
            SwitchRow(
                "Your account", SRC_ACCOUNT !in off && loggedIn, { toggle(SRC_ACCOUNT, it) }, null,
                if (loggedIn) "Sees every post in the main chat, whoever sent it" else "Log in with your own account first (Telegram > Your account)",
            )
            RateSlider("reading", backend, remember { mutableStateOf<String?>(null) })
        },
        onRun = {
            ui = RepairUi(running = true)
            backend.services.scope.launch { runScan(backend, off, loggedIn) { ui = it(ui) } }
        },
        onOpen = { stack.add(it) },
        onRemoveGone = { backend.services.scanner.removeGone(); ui = ui.copy(gone = 0) },
    )
}

private suspend fun runScan(backend: CoreBackend, off: Set<String>, loggedIn: Boolean, update: ((RepairUi) -> RepairUi) -> Unit) {
    fun log(line: String) { backend.addLog("Scan", line); update { it.copy(lines = it.lines + line) } }
    val services = backend.services
    try {
        val before = services.library.allSongs().first().size
        val chats = services.library.chatList()
        if (chats.isEmpty()) log("No chats in the library yet. Add one under Channels.")
        val bots = services.botPool.bots.value.filter { srcBot(it.id) !in off }
        val useMain = SRC_MAIN !in off
        val useAccount = SRC_ACCOUNT !in off && loggedIn
        if (!useMain && bots.isEmpty() && !useAccount) log("Nothing is switched on to read with.")
        services.scanner.retryEmptyIds = true
        try {
            for (chat in chats) {
                if (useMain) {
                    log("Reading ${chat.title}")
                    services.scanner.reader = services.scanner.mainBotName()
                    val ok = try { services.scanner.rescanChat(chat) } finally { services.scanner.reader = null }
                    log(if (ok) services.scanner.report.value ?: "${chat.title}: done" else "${chat.title}: could not read it completely (is the bot connected and an admin?)")
                }
                // Each extra bot reads the chat on its own: a bot sees the posts it made, which the others cannot see.
                for (b in bots) readChatWithBot(backend, chat, b, ::log)
            }
            // Your own account sees every post in the main chat, whoever sent it.
            if (useAccount) readMainChatWithAccount(backend, ::log)
        } finally {
            services.scanner.retryEmptyIds = false
        }
        // Started by hand, so what no source could find is asked for again.
        val unmatched = services.library.unmatchedSongs()
        log("Starting info and cover lookups" + if (unmatched.isEmpty()) "" else ", including ${unmatched.size} songs with no match before")
        if (unmatched.isNotEmpty()) services.enricher.retrySongs(unmatched)
        services.covers.forgetGiveUps()
        services.enricher.kick()
        services.covers.kick()
        val songs = services.library.allSongs().first()
        val blank = { s: String? -> s.isNullOrBlank() || s == "Unknown artist" }
        update {
            it.copy(
                running = false,
                gone = services.scanner.gone.value.size,
                summary = listOf(
                    "Songs: $before before, ${songs.size} now",
                    "Without an artist: ${songs.count { s -> blank(s.artist) }}",
                    "Without an album: ${songs.count { s -> s.album.isBlank() }}",
                    "Without a cover: ${songs.count { s -> s.cover_path.isNullOrEmpty() && s.cover_url.isNullOrEmpty() }}",
                    "Lookups keep running in the background; check again in a few minutes.",
                ),
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        update { it.copy(running = false, lines = it.lines + "Stopped: ${e.message ?: e::class.simpleName}") }
    }
}

@Composable
internal fun RepairContent(ui: RepairUi, onRun: () -> Unit, onOpen: (String) -> Unit, onRemoveGone: () -> Unit, speed: @Composable () -> Unit = {}) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmDialog(
        "Remove ${ui.gone} songs from the app?",
        "Telegram said their posts are not there. A bot cannot see posts another bot made as itself, so check first. Nothing is deleted from Telegram, and a rescan adds them back if they exist.",
        "Remove", onRemoveGone, { confirm = false },
    )
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Reads every chat again with the sources switched on below, adds songs that were missed, lists songs whose post is gone, and starts the info and cover lookups. Nothing is moved or deleted from Telegram.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        speed()
        Button(onRun, Modifier.fillMaxWidth(), enabled = !ui.running) { Text(if (ui.running) "Scanning" else "Scan library") }
        if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.summary.isNotEmpty()) {
            Text("Result", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            ui.summary.forEach { Text(it) }
        }
        if (ui.gone > 0) {
            Text("${ui.gone} songs could not be found on Telegram. They were kept.", color = MaterialTheme.colorScheme.error)
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Remove them from the app") }
        }
        OutlinedButton({ onOpen("recover") }, Modifier.fillMaxWidth()) { Text("Recover songs") }
        Text(
            "A bot cannot read songs that another bot posted as itself. Recover songs: your account posts them again so the bot can read them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Repair idle", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RepairIdlePreview() = io.max.paperplay.ui.PreviewFrame { RepairContent(RepairUi(), {}, {}, {}) }

@Preview(name = "Repair done", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RepairDonePreview() = io.max.paperplay.ui.PreviewFrame {
    RepairContent(
        RepairUi(
            lines = listOf("Reading My Music", "My Music: 3269 song posts read up to message 41233, 516 new, 12 found on a second ask, 40 ids held nothing", "Starting info and cover lookups"),
            summary = listOf("Songs: 2753 before, 3269 now", "Without an artist: 4", "Without an album: 31", "Without a cover: 210", "Lookups keep running in the background; check again in a few minutes."),
        gone = 12), {}, {}, {},
    )
}

@Preview(name = "Repair running", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun RepairRunningPreview() = io.max.paperplay.ui.PreviewFrame { RepairContent(RepairUi(running = true, lines = listOf("Reading My Music")), {}, {}, {}) }

/** Reads one chat with extra bot [b]: a bot sees the posts it made, which the others cannot see. Adds only what the library lacks. */
private suspend fun readChatWithBot(backend: CoreBackend, chat: io.max.paperplay.core.library.LibraryChat, b: io.max.paperplay.core.telegram.PoolBot, log: (String) -> Unit) {
    val services = backend.services
    val label = b.name ?: b.username ?: "Bot ${b.id}"
    val client = services.botPool.clientOf(b.id)
    if (client == null) { log("$label is not connected, skipped"); return }
    log("Reading ${chat.title} with $label")
    services.scanner.reader = label
    val seen = try { services.scanner.scanChat(chat, full = true, via = client, owner = b.id) } finally { services.scanner.reader = null }
    log(if (seen != null) services.scanner.report.value ?: "${chat.title}: done" else "$label could not read it (is it an admin of the chat?)")
}

/** Your own account sees every post in the main chat, whoever sent it; what no bot could read is added to play through it. */
private suspend fun readMainChatWithAccount(backend: CoreBackend, log: (String) -> Unit) {
    val services = backend.services
    val main = backend.mainChatId() ?: run { log("Your account: no main chat is set"); return }
    log("Reading the main chat with your account")
    val watch = services.scope.launch {
        backend.chatIndex.progress.collect { p -> services.scanner.announce(p?.let { "Your account: reading ${it.chatTitle}, ${it.found} posts (repeats included)" }) }
    }
    runCatching {
        backend.chatIndex.indexChat(io.max.paperplay.core.account.ChatInfo(main, services.library.chat(main)?.title ?: "Main chat", true))
        val r = backend.chatIndex.addPostsNotInLibrary()
        log(
            if (r.seen == 0) "Your account read no posts from the main chat"
            else "Your account saw ${r.seen} posts in the main chat; " + (if (r.added > 0) "added ${r.added} the library lacked" else "the library has all of them") +
                " (${r.repeats} of the posts are repeats of a file, kept for the Duplicates page)",
        )
        backend.account.lastReadNotes.takeIf { it.isNotEmpty() }?.let { log("Your account: $it") }
        // Your account saw every post, so songs it did not see are gone from Telegram; dropping them lets another post of the same file take over.
        when (val gone = backend.chatIndex.pruneGoneFromMainChat()) {
            -2 -> log("Songs whose post is gone were not removed: your account saw far fewer posts than the library holds")
            -1 -> {}
            0 -> log("No saved song has a deleted post")
            else -> log("Removed $gone saved songs whose post is gone from Telegram")
        }
    }.onFailure { log("The account could not read the chat: ${it.message}") }
    watch.cancel()
    services.scanner.announce(null)
}
