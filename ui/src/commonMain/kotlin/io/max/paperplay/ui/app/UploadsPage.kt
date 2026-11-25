package io.max.paperplay.ui.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import io.max.paperplay.core.app.MIN_UPLOAD_BYTES
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import io.max.paperplay.core.app.UploadJob
import io.max.paperplay.core.app.UploadStatus
import io.max.paperplay.ui.LocalAppEnv
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.flow.MutableStateFlow

/** Files handed to the app from outside (the Android share sheet). The uploads page queues them and clears this. */
object IncomingShares {
    val files = MutableStateFlow<List<String>>(emptyList())
}

/** Files dropped on the desktop window. The uploads page queues them (as originals, never deleted) and clears this. */
object IncomingDrops {
    val files = MutableStateFlow<List<String>>(emptyList())
    val dragging = MutableStateFlow(false)
    val dragCount = MutableStateFlow(0)
}

/** A song found in a folder. [id] is a file path (desktop) or a document URI (Android); [name] is its path inside the folder. */
class FolderSong(val id: String, val name: String, val size: Long)

/**
 * What a picked folder holds: the songs directly in it, and each subfolder's songs kept apart. Subfolders are only uploaded when the
 * user looks at their contents and agrees. [resolve] makes real files of the chosen songs (Android copies them) and calls back with
 * their paths and whether they are temporary copies.
 */
class FolderScan(
    val top: List<FolderSong>,
    val subfolders: Map<String, List<FolderSong>>,
    val resolve: (List<FolderSong>, (List<String>, Boolean) -> Unit) -> Unit,
)

private data class UploadTarget(val chatId: Long, val chatTitle: String, val topicId: Long? = null, val topicName: String? = null)

/** The uploads hub: where songs go, picking files, and the queue with retry. */
@Composable
internal fun UploadsPage(backend: CoreBackend) {
    val env = LocalAppEnv.current
    val uploader = backend.services.uploader
    val jobs by uploader.jobs.collectAsState()
    val paused by uploader.isPaused.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val chats = remember { backend.services.library.chatList() }
    val topics = remember { backend.services.library.repo.db.miscQueries.topics().executeAsList() }
    var target by remember {
        val main = backend.mainChatId()
        val chat = chats.firstOrNull { it.id == main } ?: chats.firstOrNull()
        mutableStateOf(chat?.let { UploadTarget(it.id, it.title) })
    }

    fun queue(paths: List<String>, temporary: Boolean) {
        val t = target
        if (t == null) { message = "Choose where to upload first."; return }
        val r = uploader.enqueue(paths, t.chatId, t.chatTitle, t.topicId, t.topicName, temporary)
        message = when {
            r.queued == 0 && r.known == 0 && r.tooSmall == 0 -> "No audio files found."
            else -> buildString {
                append("Added ${r.queued} song${if (r.queued == 1) "" else "s"}.")
                if (r.known > 0) append(" ${r.known} already in your library.")
                if (r.notAudio > 0) append(" Left out ${r.notAudio} that are not audio.")
                if (r.tooSmall > 0) append(" Left out ${r.tooSmall} under 2 MB.")
            }
        }
    }

    // Gets real files for songs found in a folder (Android copies them) and queues them; says so when some could not be copied.
    fun copyAndQueue(scan: FolderScan, songs: List<FolderSong>) {
        message = "Copying ${songs.size} song${if (songs.size == 1) "" else "s"} for upload..."
        // Gigabytes can be copied here, so not where the screen is drawn.
        backend.services.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            scan.resolve(songs) { paths, temp ->
                queue(paths, temp)
                val missed = songs.size - paths.size
                if (missed > 0) message = "${message.orEmpty()} Could not copy $missed: the phone is low on free space or the file cannot be read."
            }
        }
    }

    // A picked folder: its own songs go in at once; subfolders wait here until the user has seen what is in them and agreed.
    var subfolders by remember { mutableStateOf<Pair<FolderScan, Map<String, List<FolderSong>>>?>(null) }
    fun takeFolder(scan: FolderScan) {
        val big = { l: List<FolderSong> -> l.filter { it.size >= MIN_UPLOAD_BYTES } }
        val top = big(scan.top)
        val subs = scan.subfolders.mapValues { big(it.value) }.filterValues { it.isNotEmpty() }
        val small = scan.top.size - top.size
        if (top.isNotEmpty()) copyAndQueue(scan, top)
        else message = if (subs.isEmpty()) "No songs of 2 MB or more in that folder." else "No songs directly in that folder."
        if (top.isNotEmpty() && small > 0) message = "Left out $small under 2 MB."
        if (subs.isNotEmpty()) subfolders = scan to subs
    }

    val shared by IncomingShares.files.collectAsState()
    LaunchedEffect(shared, target) {
        if (shared.isNotEmpty() && target != null) {
            val files = shared
            IncomingShares.files.value = emptyList()
            queue(files, temporary = true)
        }
    }

    val dropped by IncomingDrops.files.collectAsState()
    LaunchedEffect(dropped, target) {
        if (dropped.isNotEmpty() && target != null) {
            val files = dropped
            IncomingDrops.files.value = emptyList()
            queue(files, temporary = false)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Upload to")
        SettingsRow(
            target?.chatTitle ?: "No chat chosen", icon = Icons.Rounded.Forum,
            subtitle = target?.topicName?.let { "Topic: $it" } ?: if (target == null) "Add a channel in Settings first" else "Tap to change",
            onClick = { choosing = true }, enabled = chats.isNotEmpty(),
        )
        val pick = env?.pickAudio
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { pick?.invoke { paths, temp -> queue(paths, temp) } }, enabled = pick != null && target != null) {
                AppIcon(Icons.Rounded.Upload, null, Modifier.size(18.dp))
                Text("Choose songs", Modifier.padding(start = 8.dp))
            }
            val pickFolder = env?.pickAudioFolder
            if (pickFolder != null) OutlinedButton(onClick = { pickFolder { scan -> takeFolder(scan) } }, enabled = target != null) { Text("Choose folder") }
            OutlinedButton(onClick = { uploader.setPaused(!paused) }, enabled = jobs.isNotEmpty()) { Text(if (paused) "Resume" else "Pause") }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

        val failed = jobs.count { it.status == UploadStatus.Failed }
        val done = jobs.count { it.status == UploadStatus.Done }
        // Totals and speed of the whole queue. The speed is the bytes sent in the last seconds, smoothed, and the time left follows from it.
        val sentBytes = jobs.sumOf { when (it.status) { UploadStatus.Done -> it.size; UploadStatus.Uploading -> (it.size * it.progress).toLong(); else -> 0L } }
        val totalBytes = jobs.filter { it.status != UploadStatus.Failed }.sumOf { it.size }
        val sentNow by androidx.compose.runtime.rememberUpdatedState(sentBytes)
        var speed by remember { mutableStateOf(0.0) }
        LaunchedEffect(Unit) {
            var last = sentNow
            while (true) {
                kotlinx.coroutines.delay(1000)
                val delta = (sentNow - last).coerceAtLeast(0L)
                last = sentNow
                speed = speed * 0.6 + delta * 0.4
            }
        }
        val sending = jobs.count { it.status == UploadStatus.Uploading }
        val waiting = jobs.count { it.status == UploadStatus.Queued }
        if (jobs.isNotEmpty()) {
            val left = (totalBytes - sentBytes).coerceAtLeast(0L)
            val active = sending > 0 && !paused && speed > 1024
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    if (active) "${megabytes(speed.toLong())}/s" else if (paused) "Paused" else if (sending > 0) "Starting" else "Idle",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    buildString {
                        append("${megabytes(sentBytes)} of ${megabytes(totalBytes)}")
                        if (active && left > 0) append(" - about ${duration((left / speed).toLong())} left")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Sending $sending, waiting $waiting, done $done, failed $failed",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalBytes > 0) LinearProgressIndicator(progress = { (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        SectionLabel("Queue (${jobs.size})")
        if (jobs.isEmpty()) {
            Text(if (io.max.paperplay.ui.isPhone) "Nothing is uploading. Songs you choose, or share to Paper Play from another app, wait here." else "Nothing is uploading. Songs you choose, or drop onto the window while this page is open, wait here.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed > 0) Button({ uploader.retryAll() }) { Text("Retry $failed failed") }
                if (done > 0) androidx.compose.material3.FilledTonalButton({ uploader.clearFinished() }) { Text("Clear $done finished") }
            }
            if (paused) Text("Paused. Files being sent finish first.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (job in jobs) JobRow(job, { uploader.retry(job.id) }, { uploader.remove(job.id) })
        }
    }

    subfolders?.let { (scan, subs) ->
        SubfolderDialog(subs, onDismiss = { subfolders = null }) { chosen ->
            subfolders = null
            copyAndQueue(scan, chosen)
        }
    }
    if (choosing) TargetDialog(chats.map { it.id to it.title }, topics.map { Triple(it.chat_id, it.topic_id, it.name) }, target, { target = it; choosing = false }) { choosing = false }
}

private fun megabytes(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "${bytes / 10_000_000 / 100.0} GB" else "${bytes / 100_000 / 10.0} MB"

private fun duration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "$seconds s"
}

@Composable
private fun JobRow(job: UploadJob, onRetry: () -> Unit, onRemove: () -> Unit) {
    val where = job.topicName?.let { "${job.chatTitle} / $it" } ?: job.chatTitle
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
            val sub = when (job.status) {
                UploadStatus.Queued -> if (job.error != null) "Retrying, attempt ${job.attempts + 1}: ${job.error}" else "Waiting - $where"
                UploadStatus.Uploading -> "$where - ${(job.progress * 100).toInt()}% of ${megabytes(job.size)}"
                UploadStatus.Done -> "Uploaded to $where"
                UploadStatus.Failed -> "$where - ${job.error ?: "Failed"}"
            }
            Text(sub, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.status == UploadStatus.Uploading) LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (job.status == UploadStatus.Failed) IconButton(onRetry) { AppIcon(Icons.Rounded.Refresh, "Retry") }
        if (job.status != UploadStatus.Uploading) IconButton(onRemove) { AppIcon(Icons.Rounded.Close, "Remove") }
    }
}

/** Lists what each subfolder holds, so nothing like call recordings goes up unseen. Nothing is ticked at first. */
@Composable
private fun SubfolderDialog(subs: Map<String, List<FolderSong>>, onDismiss: () -> Unit, onAdd: (List<FolderSong>) -> Unit) {
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songs in subfolders") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("These were left out. Read the file names and tick only the folders you want to upload.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((folder, songs) in subs) {
                    val on = folder in ticked
                    Row(Modifier.fillMaxWidth().clickable { ticked = if (on) ticked - folder else ticked + folder }, verticalAlignment = Alignment.Top) {
                        Checkbox(on, { ticked = if (on) ticked - folder else ticked + folder })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text("$folder (${songs.size} song${if (songs.size == 1) "" else "s"})", style = MaterialTheme.typography.titleSmall)
                            for (s in songs.take(8)) Text(s.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (songs.size > 8) Text("and ${songs.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(subs.filterKeys { it in ticked }.values.flatten()) }, enabled = ticked.isNotEmpty()) {
                Text("Add ${subs.filterKeys { it in ticked }.values.sumOf { it.size }} songs")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Skip subfolders") } },
    )
}

@Composable
private fun TargetDialog(
    chats: List<Pair<Long, String>>, topics: List<Triple<Long, Long, String>>, current: UploadTarget?,
    onPick: (UploadTarget) -> Unit, onDismiss: () -> Unit,
) {
    var chat by remember { mutableStateOf(chats.firstOrNull { it.first == current?.chatId } ?: chats.firstOrNull()) }
    // "General" is its own row below (it means no topic); Telegram's own General topic would only repeat it.
    val mine = topics.filter { it.first == chat?.first && !it.third.equals("General", ignoreCase = true) }
    var topic by remember { mutableStateOf(current?.topicId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload to") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                for (c in chats) {
                    Row(Modifier.fillMaxWidth().clickable { chat = c; topic = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chat?.first == c.first, { chat = c; topic = null })
                        Text(c.second, maxLines = 1)
                    }
                }
                if (mine.isNotEmpty()) {
                    Text("Topic", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().clickable { topic = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(topic == null, { topic = null })
                        Text("General")
                    }
                    for (t in mine) {
                        Row(Modifier.fillMaxWidth().clickable { topic = t.second }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(topic == t.second, { topic = t.second })
                            Text(t.third, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                chat?.let { c -> onPick(UploadTarget(c.first, c.second, topic, mine.firstOrNull { it.second == topic }?.third)) }
            }) { Text("Choose") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun JobRowPhonePreview() {
    val sampleJob = UploadJob(id = "1", path = "/music/song.mp3", name = "Starlight.mp3", size = 10485760L, chatId = 100, chatTitle = "Music Channel", status = UploadStatus.Done)
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(8.dp)) {
            JobRow(job = sampleJob, onRetry = {}, onRemove = {})
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun JobRowDesktopPreview() {
    val sampleJob = UploadJob(id = "1", path = "/music/song.mp3", name = "Starlight.mp3", size = 10485760L, chatId = 100, chatTitle = "Music Channel", status = UploadStatus.Done)
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            JobRow(job = sampleJob, onRetry = {}, onRemove = {})
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Target dialog", showBackground = true)
@Composable
private fun TargetDialogPreview() = io.max.paperplay.ui.PreviewFrame {
    TargetDialog(
        chats = listOf(1L to "My Music", 2L to "Archive"),
        topics = listOf(Triple(1L, 10L, "Rock"), Triple(1L, 11L, "Jazz")),
        current = null, onPick = {}, onDismiss = {},
    )
}
