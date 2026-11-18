package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import io.max.paperplay.core.app.TaskKind
import io.max.paperplay.core.app.TaskState
import io.max.paperplay.core.dupes.DefaultSongRules
import io.max.paperplay.core.dupes.GENERAL_TOPIC_ID
import io.max.paperplay.core.dupes.DeleteStatus
import io.max.paperplay.core.dupes.DuplicateGroup
import io.max.paperplay.core.dupes.DuplicatePost
import io.max.paperplay.core.dupes.DuplicateSection
import io.max.paperplay.core.dupes.describeDuplicate
import io.max.paperplay.core.dupes.findDuplicates
import io.max.paperplay.core.dupes.sectionOf
import io.max.paperplay.core.dupes.survivorOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private typealias PostKey = Pair<Long, Long>

private val DuplicatePost.key: PostKey get() = chatId to messageId

/** dd/MM/yyyy (UTC) of a time in seconds. */
private fun dateText(seconds: Long): String {
    val z = seconds / 86400 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${day.toString().padStart(2, '0')}/${month.toString().padStart(2, '0')}/$year"
}

private fun sizeText(bytes: Long) = "${(bytes * 10 / 1_048_576) / 10.0} MB"

private fun postsOf(g: DuplicateGroup) = listOf(g.keep) + g.others

/**
 * One post can sit in two groups (as the copy to keep of an identical-file group, and as a worse file of a same-song group), and a tick
 * counts in both. When that ticks every post of a group, the group's copy to keep is unticked, so the page never marks all of them.
 */
private fun keepOnePerGroup(groups: List<DuplicateGroup>, ticked: Set<PostKey>): Set<PostKey> {
    var out = ticked
    for (g in groups) if (postsOf(g).all { it.key in out }) out = out - g.keep.key
    return out
}

/** Settings > Library > Find duplicates. Tick the posts to delete; nothing goes before the confirmation. */
@Composable
internal fun DuplicatesPage(backend: CoreBackend, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var ticked by remember { mutableStateOf(setOf<PostKey>()) }
    var confirm by remember { mutableStateOf(false) }
    // The topic names Telegram gave (the same table the Upload page lists), with any name the owner set on top.
    val topicNames = remember {
        val names = HashMap<String, String>()
        backend.services.library.repo.db.miscQueries.topics().executeAsList().forEach { names["topic:${it.chat_id}:${it.topic_id}"] = it.name }
        names.putAll(backend.taste.value.topicNames)
        names
    }
    val queue = backend.services.deleteQueue
    val jobs by queue.jobs.collectAsState()
    val main = backend.mainChatId()
    val scope = backend.services.scope
    val active = jobs.any { it.status == DeleteStatus.Queued || it.status == DeleteStatus.Deleting }
    // Telegram lets a bot delete only posts younger than 48 hours; older ones go through your own account, so say so before anything is tried.
    val accountOn by backend.account.loggedIn.collectAsState()
    val oldTicked = remember(ticked, groups) {
        val limit = kotlin.time.Clock.System.now().epochSeconds - 47 * 3600L
        groups.orEmpty().flatMap { postsOf(it) }.distinctBy { it.key }.count { it.key in ticked && it.date in 1..limit }
    }

    LaunchedEffect(reload) {
        val repo = backend.services.library.repo
        suspend fun find() = withContext(Dispatchers.Default) {
            findDuplicates(
                repo.db.songsQueries.allSongs().executeAsList(), repo.db.filingQueries.allPostCopies().executeAsList(),
                DefaultSongRules, onlyChat = main,
            )
        }
        var found = find()
        // A post Telegram no longer has would be listed as a repeat forever; drop what is gone and list again.
        if (!active && found.isNotEmpty() && queue.dropGonePosts(found.flatMap { g -> postsOf(g).map { it.key } }) > 0) found = find()
        groups = found
        // The extras are ticked at once: repeats in the same topic and clearly worse copies. The best copy of every song is never in it.
        ticked = keepOnePerGroup(found, found.filter { sectionOf(it) == DuplicateSection.Extras }
            .flatMap { g -> g.others.filter { it.messageId in g.suggested }.map { it.key } }.toSet())
    }

    fun jobOf(p: DuplicatePost) = jobs.lastOrNull { it.isDuplicate && it.postChat == p.chatId && it.postMessage == p.messageId }
    fun busy(p: DuplicatePost) = jobOf(p)?.status.let { it == DeleteStatus.Queued || it == DeleteStatus.Deleting || it == DeleteStatus.Done }

    /** Sets the ticked posts of a group to [wanted], unless that would tick every post: one always survives. */
    fun tick(g: DuplicateGroup, wanted: Collection<DuplicatePost>) {
        val keys = wanted.filter { !busy(it) }.map { it.key }.toSet()
        val next = (ticked - postsOf(g).map { it.key }.toSet()) + keys
        if (survivorOf(g) { it.key in next } != null) ticked = keepOnePerGroup(groups.orEmpty(), next)
    }

    // Posts that are gone leave the page at once; a group with no extra copy left goes with them.
    val goneNow = jobs.filter { it.isDuplicate && it.status == DeleteStatus.Done }.map { it.postChat to it.postMessage }.toSet()
    val found = remember(groups, goneNow) {
        groups?.let { l -> if (goneNow.isEmpty()) l else l.mapNotNull { g -> g.copy(others = g.others.filter { (it.chatId to it.messageId) !in goneNow }).takeIf { it.others.isNotEmpty() } } }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var toTop by remember { mutableStateOf(0) }
    LaunchedEffect(toTop) { if (toTop > 0) listState.scrollToItem(0) }
    val labels = mapOf(
        DuplicateSection.Extras to "Extra copies",
        DuplicateSection.OtherTopics to "Also in other topics",
        DuplicateSection.Maybe to "Might be the same song",
    )
    // Only the rows on screen are built: hundreds of groups, each with covers, made the page slow when it was one long column.
    val sections = remember(found) { found.orEmpty().groupBy { sectionOf(it) } }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding + 96.dp)) {
            when {
                found == null -> item {
                    // A delete started earlier carries on while this page loads, so its progress shows at once.
                    val done = jobs.count { it.status == DeleteStatus.Done }
                    Text(
                        if (jobs.isEmpty()) "Looking for repeats" else "Deleting: $done of ${jobs.size} done" + (if (active) "" else ". Looking for repeats") ,
                        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                found.isEmpty() -> item { Text("No repeated songs in your main chat.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    item {
                        Text(
                            "${found.size} songs appear more than once in your main chat. Tick the posts to delete from Telegram. One copy of every song always stays.",
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (oldTicked > 0) item {
                        Text(
                            if (accountOn) "$oldTicked of the ticked posts are older than 48 hours. Your account deletes those."
                            else "$oldTicked of the ticked posts are older than 48 hours. Telegram lets only your own account delete those: log in under Settings, Library. The others are deleted as usual.",
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = if (accountOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    item {
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { found.forEach { g -> tick(g, g.others.filter { it.messageId in g.suggested }) } }, enabled = !active) { Text("Tick extras") }
                            // The same file in several topics (topics act as playlists, so it is never ticked on its own): keep one copy per
                            // song, a named topic before General, and tick the others.
                            OutlinedButton(
                                onClick = {
                                    sections[DuplicateSection.OtherTopics].orEmpty().forEach { g ->
                                        val named = postsOf(g).filter { it.topicId != null && it.topicId != 1L && it.topicId != GENERAL_TOPIC_ID }
                                        val keep = named.firstOrNull { it.key == g.keep.key } ?: named.firstOrNull() ?: g.keep
                                        tick(g, postsOf(g).filter { it.key != keep.key })
                                    }
                                },
                                enabled = !active && sections[DuplicateSection.OtherTopics].orEmpty().isNotEmpty(),
                            ) { Text("Tick topic repeats") }
                            // Songs that only might be the same: tick every copy but the best one (shown first), after you looked them over.
                            OutlinedButton(
                                onClick = { sections[DuplicateSection.Maybe].orEmpty().forEach { g -> tick(g, g.others) } },
                                enabled = !active && sections[DuplicateSection.Maybe].orEmpty().isNotEmpty(),
                            ) { Text("All but the best", softWrap = false) }
                            TextButton(onClick = { ticked = emptySet() }, enabled = ticked.isNotEmpty()) { Text("Clear") }
                        }
                    }
                    if (jobs.isNotEmpty()) item {
                        val done = jobs.count { it.status == DeleteStatus.Done }
                        val failed = jobs.count { it.status == DeleteStatus.Failed }
                        val skipped = jobs.count { it.status == DeleteStatus.Skipped }
                        androidx.compose.foundation.layout.Column {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Deleting: $done of ${jobs.size} done" + (if (failed > 0) ", $failed failed" else "") + (if (skipped > 0) ", $skipped kept" else ""),
                                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (active) TextButton(onClick = { queue.cancel() }) { Text("Stop") }
                                else TextButton(onClick = { reload++ }) { Text("Refresh") }
                            }
                            jobs.firstOrNull { it.status == DeleteStatus.Failed }?.error?.let {
                                Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    for (section in DuplicateSection.entries) {
                        val inSection = sections[section].orEmpty()
                        if (inSection.isEmpty()) continue
                        item(key = "section:$section") { SectionLabel("${labels.getValue(section)} (${inSection.size})") }
                        for (g in inSection) {
                            val groupKey = "${g.keep.chatId}:${g.keep.messageId}"
                            val art = postsOf(g).firstNotNullOfOrNull { it.song?.cover_path }
                            item(key = "group:$groupKey") {
                                SettingsRow(
                                    "${g.title} - ${g.artist}", icon = Icons.Rounded.ContentCopy,
                                    subtitle = describeDuplicate(g) { p -> p.topicId?.let { topicNames["topic:${p.chatId}:$it"] } },
                                    leading = { io.max.paperplay.ui.components.CoverImage(art, "${g.title}|${g.artist}", size = 52.dp, radius = 8.dp) },
                                )
                            }
                            items(postsOf(g), key = { "post:${it.chatId}:${it.messageId}" }) { p ->
                                val job = jobOf(p)
                                val state = when (job?.status) {
                                    DeleteStatus.Queued -> "Waiting"
                                    DeleteStatus.Deleting -> "Deleting"
                                    DeleteStatus.Done -> "Deleted"
                                    DeleteStatus.Failed -> job?.error ?: "Failed"
                                    DeleteStatus.Skipped -> job?.error ?: "Kept"
                                    null -> null
                                }
                                val enabled = !busy(p) && !active
                                // Tapping anywhere on the row ticks it, not only the box.
                                val toggle = {
                                    if (p.key !in ticked) tick(g, postsOf(g).filter { it.key in ticked || it.key == p.key }) else ticked = ticked - p.key
                                }
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle).padding(start = 36.dp, end = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(p.key in ticked, { toggle() }, enabled = enabled)
                                    io.max.paperplay.ui.components.CoverImage(p.song?.cover_path ?: art, "${g.title}|${g.artist}", Modifier.padding(end = 12.dp), size = 36.dp, radius = 6.dp)
                                    val topic = p.topicId?.takeIf { it != 1L && it != GENERAL_TOPIC_ID }?.let { topicNames["topic:${p.chatId}:$it"] ?: "Unnamed topic" } ?: "General"
                                    val verdict = state ?: if (p.key in ticked) "Will be deleted" else "Stays"
                                    androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(
                                            "$topic · ${p.format} · ${sizeText(p.size)} · ${dateText(p.date)}",
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            verdict, style = MaterialTheme.typography.bodySmall,
                                            color = if (job?.status == DeleteStatus.Failed || (state == null && p.key in ticked)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            p.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            backend.ownerLabel(p.song), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                    // The same menu entry as in the song lists: open this post in Telegram.
                                    val link = backend.postLink(p.chatId, p.messageId, p.topicId)
                                    if (link != null) {
                                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                                        var menu by remember { mutableStateOf(false) }
                                        androidx.compose.foundation.layout.Box {
                                            androidx.compose.material3.IconButton({ menu = true }) { io.max.paperplay.ui.components.AppIcon(Icons.Rounded.MoreVert, "Post options") }
                                            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text("Open in Telegram") },
                                                    onClick = { menu = false; runCatching { uri.openUri(link) } },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Just above the bars; bottomPadding grows by the mini player's height while one shows and carries 40 dp of spare room.
        val buttonBottom by androidx.compose.animation.core.animateDpAsState((bottomPadding - 40.dp).coerceAtLeast(0.dp) + 16.dp, label = "deleteButton")
        if (ticked.isNotEmpty() && !active && main != null) androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { confirm = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = buttonBottom),
            icon = { io.max.paperplay.ui.components.AppIcon(Icons.Rounded.Delete, null) },
            text = { Text("Delete ${ticked.size}") },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${ticked.size} posts?") },
            text = { Text("They are removed from Telegram for everyone and cannot be brought back. Playlists and favorites move to the copy that stays." + (if (oldTicked > 0 && !accountOn) " $oldTicked older than 48 hours will not be deleted until your account is logged in." else "")) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val pairs = groups.orEmpty().flatMap { g ->
                        val keep = survivorOf(g) { it.key in ticked } ?: return@flatMap emptyList()
                        postsOf(g).filter { it.key in ticked }.map { it to keep }
                    }
                    ticked = emptySet()
                    toTop++
                    if (pairs.isEmpty()) return@TextButton
                    val tasks = backend.tasks
                    val id = tasks.start(TaskKind.Other, "Deleting duplicates", pairs.size, "Starting", onStop = { queue.cancel() })
                    queue.deleteDuplicates(pairs)
                    scope.launch {
                        val watch = launch {
                            queue.jobs.collect { l ->
                                tasks.update(id, done = l.count { it.status != DeleteStatus.Queued && it.status != DeleteStatus.Deleting })
                            }
                        }
                        queue.awaitIdle()
                        watch.cancel()
                        val l = queue.jobs.value
                        val failed = l.count { it.status == DeleteStatus.Failed }
                        tasks.finish(
                            id, if (failed > 0 && failed == l.size) TaskState.Failed else TaskState.Done,
                            "${l.count { it.status == DeleteStatus.Done }} deleted" + if (failed > 0) ", $failed failed" else "",
                        )
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Preview
@Composable
private fun DuplicatesPagePreview() {
    SettingsRow(
        "Supermassive Black Hole - Muse",
        icon = Icons.Rounded.ContentCopy,
        subtitle = "2 copies found · 320 kbps (12.5 MB)",
    )
}
