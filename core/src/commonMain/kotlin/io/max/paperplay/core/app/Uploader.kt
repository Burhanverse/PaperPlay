package io.max.paperplay.core.app

import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.audioContent
import io.max.paperplay.core.telegram.request
import io.max.paperplay.core.telegram.topicField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

enum class UploadStatus { Queued, Uploading, Done, Failed }

/** One file waiting to go to [chatId] (and [topicId] when the chat has topics). */
data class UploadJob(
    val id: String,
    val path: String,
    val name: String,
    val size: Long,
    val chatId: Long,
    val chatTitle: String,
    val topicId: Long? = null,
    val topicName: String? = null,
    val status: UploadStatus = UploadStatus.Queued,
    val progress: Float = 0f,
    val error: String? = null,
    val attempts: Int = 0,
    /** A copy made for the upload (a shared or picked file); deleted once it is sent or removed. */
    val temporary: Boolean = false,
)

/** Songs smaller than this (2 MB) are never uploaded: they are clips, call recordings and voice notes, not music. */
const val MIN_UPLOAD_BYTES = 2L * 1024 * 1024

/** Result of [Uploader.enqueue]: queued, left out as not audio, skipped as already in the library, or left out as too small. */
data class EnqueueResult(val queued: Int, val notAudio: Int, val known: Int, val tooSmall: Int = 0)

/**
 * Sends audio files to a Telegram chat or topic as the bot, in a queue. Starts are spaced out (8-20 s, or 30-60 s
 * when "upload_slower" is on, and never faster than the "rate_uploads" setting) and a failed file retries a few times.
 */
class Uploader(
    private val scope: CoroutineScope,
    private val client: () -> TdClient?,
    private val pref: (String) -> String?,
    /** (file name, size) of every song already in the library. */
    private val knownSongs: () -> Set<Pair<String, Long>>,
    /** Extra bots that share the sending; null sends everything with [client]. */
    private val pool: io.max.paperplay.core.telegram.BotPool? = null,
    /** Saves the queue so it survives a restart. */
    private val setPref: (String, String) -> Unit = { _, _ -> },
    /** Gets the song a bot just posted, owned by that bot: the main bot cannot read posts of the others. */
    private val onSent: (io.max.paperplay.core.library.Song) -> Unit = {},
) {
    private val _jobs = MutableStateFlow<List<UploadJob>>(emptyList())
    val jobs: StateFlow<List<UploadJob>> = _jobs
    private val paused = MutableStateFlow(false)

    /** The queue came back from a restart and is paused only for that reason; the user has not pressed Pause. */
    private var pausedByRestart = false

    init {
        // Files still waiting when the app closed come back, paused so the owner chooses when to send.
        val back = runCatching {
            Json.parseToJsonElement(pref(QUEUE_PREF) ?: "[]").jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                val path = s("p") ?: return@mapNotNull null
                val size = fileLength(path)
                if (size <= 0) return@mapNotNull null
                UploadJob(
                    s("i") ?: newUuid(), path, s("n").orEmpty(), size, s("c")?.toLongOrNull() ?: return@mapNotNull null, s("ct").orEmpty(),
                    s("t")?.toLongOrNull(), s("tn"), if (s("f") == "1") UploadStatus.Failed else UploadStatus.Queued,
                    error = s("e"), temporary = s("tmp") == "1",
                )
            }
        }.getOrDefault(emptyList())
        if (back.isNotEmpty()) {
            _jobs.value = back
            if (back.any { it.status == UploadStatus.Queued }) { paused.value = true; pausedByRestart = true }
        }
        scope.launch {
            _jobs.map { l -> l.filter { it.status != UploadStatus.Done }.map { it.copy(progress = 0f, status = if (it.status == UploadStatus.Failed) it.status else UploadStatus.Queued) } }
                .distinctUntilChanged().collect { l ->
                    runCatching {
                        setPref(QUEUE_PREF, buildJsonArray {
                            for (j in l) add(buildJsonObject {
                                put("i", j.id); put("p", j.path); put("n", j.name); put("c", j.chatId.toString()); put("ct", j.chatTitle)
                                j.topicId?.let { put("t", it.toString()) }; j.topicName?.let { put("tn", it) }
                                if (j.status == UploadStatus.Failed) put("f", "1"); j.error?.let { put("e", it) }
                                if (j.temporary) put("tmp", "1")
                            })
                        }.toString())
                    }
                }
        }
    }
    val isPaused: StateFlow<Boolean> = paused
    private var running = false
    private val gate = Mutex()
    private var uploaded = 0

    /** When a failed job may be tried again (Telegram's flood wait or a pause between attempts); workers skip it until then. */
    private val retryAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun setPaused(on: Boolean) {
        pausedByRestart = false
        paused.value = on
        if (!on) pump()
    }

    fun enqueue(
        paths: List<String>, chatId: Long, chatTitle: String, topicId: Long? = null, topicName: String? = null, temporary: Boolean = false,
    ): EnqueueResult {
        val skipKnown = pref("upload_skip_known") != "false"
        val known = if (skipKnown) knownSongs().mapTo(HashSet()) { sentKey(it.first, it.second) } else emptySet()
        val sentBefore = if (skipKnown) sentFiles().keys else emptySet()
        var notAudio = 0
        var skipped = 0
        var tooSmall = 0
        val fresh = mutableListOf<UploadJob>()
        for (p in paths.distinct()) {
            val name = p.substringAfterLast('/').substringAfterLast('\\')
            val size = fileLength(p)
            appLog("enqueue", "$name size=$size temporary=$temporary path=$p")
            if (size <= 0 || name.substringAfterLast('.', "").lowercase() !in audioExtensions) {
                notAudio++
                if (temporary) deleteFile(p)
                continue
            }
            if (size < MIN_UPLOAD_BYTES) {
                tooSmall++
                if (temporary) deleteFile(p)
                continue
            }
            // Known to the library, sent by this app before (the library only learns of a song after a scan), or already in the queue.
            val key = sentKey(name, size)
            val queuedAlready = skipKnown && _jobs.value.any { sentKey(it.name, it.size) == key && it.chatId == chatId && it.status != UploadStatus.Failed } || key in fresh.map { sentKey(it.name, it.size) }
            if (key in known || key in sentBefore || queuedAlready) {
                appLog("enqueue", "skipped $name: library=${key in known} sentBefore=${key in sentBefore} queued=$queuedAlready")
                skipped++
                if (temporary) deleteFile(p)
                continue
            }
            val waiting = _jobs.value.any { it.path == p && it.chatId == chatId && it.status != UploadStatus.Done && it.status != UploadStatus.Failed }
            if (waiting) continue
            fresh += UploadJob(newUuid(), p, name, size, chatId, chatTitle, topicId, topicName, temporary = temporary)
        }
        _jobs.update { it + fresh }
        // Choosing songs to upload is asking for them to go: it lifts the pause a restart put on the saved queue (a Pause the user pressed stays).
        if (fresh.isNotEmpty() && pausedByRestart) { pausedByRestart = false; paused.value = false }
        pump()
        return EnqueueResult(fresh.size, notAudio, skipped, tooSmall)
    }

    /** Name and size in lower case, how a file is told apart from the same file sent again (same as the Flutter app). */
    private fun sentKey(name: String, size: Long) = "${name.lowercase()}|$size"

    private var sent: MutableMap<String, Long>? = null

    /** Files this app has sent, kept across restarts. */
    private fun sentFiles(): MutableMap<String, Long> = sent ?: runCatching {
        Json.parseToJsonElement(pref(SENT_PREF) ?: "{}").jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }.toMutableMap()
    }.getOrElse { mutableMapOf() }.also { sent = it }

    private fun rememberSent(job: UploadJob) {
        val files = sentFiles()
        files[sentKey(job.name, job.size)] = nowMs()
        runCatching { setPref(SENT_PREF, kotlinx.serialization.json.JsonObject(files.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()) }
    }

    fun retry(id: String) {
        retryAt.update { it - id }
        update(id) { it.copy(status = UploadStatus.Queued, error = null, attempts = 0, progress = 0f) }
        pump()
    }

    fun retryAll() {
        retryAt.value = emptyMap()
        _jobs.update { l -> l.map { if (it.status == UploadStatus.Failed) it.copy(status = UploadStatus.Queued, error = null, attempts = 0, progress = 0f) else it } }
        pump()
    }

    /** Takes every file that has not started out of the queue (the one being sent finishes). */
    fun cancelWaiting() {
        _jobs.value.filter { it.status == UploadStatus.Queued }.forEach { remove(it.id) }
    }

    /** Takes a waiting or failed file out of the queue. A file being sent is left alone. */
    fun remove(id: String) {
        val j = _jobs.value.firstOrNull { it.id == id } ?: return
        if (j.status == UploadStatus.Uploading) return
        if (j.temporary && j.status != UploadStatus.Done) deleteFile(j.path)
        retryAt.update { it - id }
        _jobs.update { l -> l.filter { it.id != id } }
    }

    fun clearFinished() {
        _jobs.value.filter { it.status == UploadStatus.Done }.forEach { remove(it.id) }
    }

    private fun update(id: String, change: (UploadJob) -> UploadJob) = _jobs.update { l -> l.map { if (it.id == id) change(it) else it } }

    private fun pump() {
        if (running || paused.value) return
        running = true
        scope.launch {
            try {
                val parallel = (pref("upload_parallel")?.toIntOrNull() ?: 6).coerceIn(1, 20)
                List(parallel) { launch { worker() } }.joinAll()
            } finally {
                running = false
            }
            if (!paused.value && _jobs.value.any { it.status == UploadStatus.Queued }) pump()
        }
    }

    private suspend fun worker() {
        while (!paused.value) {
            var wait = 0L
            val job = gate.withLock {
                val now = nowMs()
                val queued = _jobs.value.filter { it.status == UploadStatus.Queued }
                val next = queued.firstOrNull { (retryAt.value[it.id] ?: 0L) <= now }
                if (next == null) {
                    // Nothing is queued: this worker is done. Otherwise every queued job is waiting to retry.
                    wait = (queued.minOfOrNull { retryAt.value[it.id] ?: 0L } ?: return) - now
                    null
                } else {
                    update(next.id) { it.copy(status = UploadStatus.Uploading, progress = 0f) }
                    next
                }
            }
            if (job == null) {
                delay(wait.coerceAtLeast(100L))
                continue
            }
            send(job)
            if (_jobs.value.any { it.status == UploadStatus.Queued }) delay(pause())
        }
    }

    /**
     * How long a worker rests after a file, as in the Flutter app: the "rate_uploads" gap (6 a minute is 10 s) varied by 30%,
     * three times longer when "upload_slower" is on, and a longer rest after every 20 songs. Each worker rests on its own, so
     * several workers (and bots) send side by side.
     */
    private fun pause(): Long {
        val rate = (pref("rate_uploads")?.toIntOrNull() ?: 6).coerceIn(1, 1200)
        val base = 60_000.0 / rate * (if (pref("upload_slower") == "true") 3 else 1)
        val factor = if (uploaded > 0 && uploaded % 20 == 0) 4 + Random.nextDouble() * 4 else 0.7 + Random.nextDouble() * 0.6
        return (base * factor).toLong()
    }

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    private suspend fun send(job: UploadJob) {
        var botId = 0L
        try {
            val picked = pool?.pick()
            // A copy made for an upload can vanish (the cache is cleared); Telegram would only answer "InputFile is not specified".
            if (fileLength(job.path) <= 0) throw TdError(400, "The file is gone from this device. Remove it and add the song again.")
            appLog("upload", "start ${job.name} size=${job.size} path=${job.path} exists=${fileLength(job.path)} chat=${job.chatId} topic=${job.topicId} attempt=${job.attempts + 1} pool=${picked?.id}")
            val c = picked?.client ?: client() ?: throw TdError(0, "The bot is not connected.")
            botId = picked?.id ?: 0L
            if (botId != 0L) runCatching { c.call(request("getChat") { put("chat_id", job.chatId) }) }
            val posted = sendOne(c, job)
            pool?.recordSent(botId)
            runCatching {
                songFromMessage(posted ?: return@runCatching)?.let { s ->
                    onSent(if (botId != 0L) s.copy(remote_id = botRemote(botId, s.remote_id)) else s)
                }
            }
            appLog("upload", "done ${job.name}")
            rememberSent(job)
            uploaded++
            update(job.id) { it.copy(status = UploadStatus.Done, progress = 1f, error = null) }
            if (job.temporary) deleteFile(job.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val attempts = job.attempts + 1
            val td = e as? TdError
            appLog("upload", "failed ${job.name} attempt=$attempts code=${td?.code} ${e::class.simpleName}: ${e.message}")
            td?.retryAfter?.let { pool?.recordFlood(botId, it) }
            // Telegram sometimes answers "InputFile is not specified" or "Internal Server Error during file upload" and accepts the same file a moment later.
            val flaky = td?.message?.let { it.contains("InputFile is not specified") || it.contains("Internal Server Error", ignoreCase = true) } == true
            val retryable = attempts < MAX_ATTEMPTS && (td == null || flaky || td.code == 429 || td.code == 408 || td.code == 0 || td.code >= 500)
            if (retryable) {
                // The wait is recorded first, so no worker can pick the job up before it is over.
                retryAt.update { it + (job.id to nowMs() + ((td?.retryAfter ?: (10 * attempts)) + 1) * 1000L) }
                update(job.id) { it.copy(status = UploadStatus.Queued, attempts = attempts, error = e.message, progress = 0f) }
            } else {
                update(job.id) { it.copy(status = UploadStatus.Failed, attempts = attempts, error = (e.message ?: "Failed") + if (td?.message?.contains("InputFile") == true) " (${job.size / 1024 / 1024} MB, name ${job.name.length} characters)" else "") }
            }
        }
    }

    private suspend fun sendOne(c: TdClient, job: UploadJob): JsonObject? {
        val sentId = CompletableDeferred<Long>()
        val fileId = CompletableDeferred<Long>()
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
            c.updates.first { u ->
                val type = u["@type"]?.jsonPrimitive?.content
                if (type == "updateFile") {
                    val f = u.obj("file")
                    val mine = if (fileId.isCompleted) fileId.await() else null
                    if (f != null && mine != null && f.long("id") == mine && job.size > 0) {
                        val up = f.obj("remote")?.long("uploaded_size") ?: 0L
                        update(job.id) { it.copy(progress = (up.toFloat() / job.size).coerceIn(0f, 1f)) }
                    }
                    false
                } else if (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") {
                    u.long("old_message_id") == sentId.await()
                } else {
                    false
                }
            }
        }
        try {
            val newApi = c.newApi()
            // The file's own tags first: "Song - Artist.flac" and "Artist - Song.flac" cannot be told apart by name.
            val own = runCatching { io.max.paperplay.core.metadata.readEmbeddedTags(io.max.paperplay.core.metadata.TagSource(fileLength(job.path)) { off, n -> readRange(job.path, off, n) }) }.getOrNull()
            val (artist, title) = if (own?.title != null && own.artist != null) own.artist to own.title else parseFileName(job.name)
            val seconds = runCatching { io.max.paperplay.core.metadata.readDurationSeconds(io.max.paperplay.core.metadata.TagSource(fileLength(job.path)) { off, n -> readRange(job.path, off, n) }) }.getOrDefault(0)
            appLog("upload", "duration ${job.name} = $seconds s")
            val sent = c.call(request("sendMessage") {
                put("chat_id", job.chatId)
                topicField(job.topicId, newApi)
                put("input_message_content", audioContent(buildJsonObject { put("@type", "inputFileLocal"); put("path", job.path) }, newApi, title, artist, seconds))
            }, 2.minutes)
            sent.obj("content")?.obj("audio")?.obj("audio")?.long("id")?.let { fileId.complete(it) }
            sentId.complete(sent.long("id") ?: throw TdError(0, "Telegram did not accept the file."))
            val done = withTimeoutOrNull(30.minutes) { outcome.await() } ?: throw TdError(408, "Upload timed out")
            if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                val err = done.obj("error")
                throw TdError(err?.long("code")?.toInt() ?: 0, err?.str("message") ?: "Telegram refused the file.")
            }
            return done.obj("message")
        } finally {
            outcome.cancel()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val QUEUE_PREF = "upload_queue"
        const val SENT_PREF = "uploaded_files"
    }
}
