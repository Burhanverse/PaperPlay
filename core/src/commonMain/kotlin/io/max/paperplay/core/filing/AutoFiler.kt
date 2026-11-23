package io.max.paperplay.core.filing

import io.max.paperplay.core.app.botOwnerOf
import io.max.paperplay.core.app.fileOfMessage
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the auto filer is doing, for the Sort page. */
data class AutoFileState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val note: String? = null)

/**
 * Files songs that sit in other chats into the main chat's topics, using the same evidence as the Sort page ([suggestTopics],
 * confident matches only). For each song the main bot first copies the post itself (no download, no upload) when it can see the
 * source chat. Otherwise a bot that can read the post downloads it and the main bot uploads it to the topic. The original post is
 * deleted only after the new post is read back and is the same song (same file for a copy; same size and length for an upload);
 * anything unclear leaves the original alone. Songs are spaced out like the topic mover, and each one is logged.
 */
class AutoFiler(
    private val scope: CoroutineScope,
    /** The main bot. */
    private val main: () -> TdClient?,
    /** An extra bot by id, for songs only it can read. */
    private val botClient: (Long) -> TdClient?,
    /** Every other connected bot, tried in turn when the first reader cannot fetch a post. */
    private val others: () -> List<TdClient>,
    private val repo: LibraryRepo,
    private val log: (String) -> Unit,
) {
    private val _state = MutableStateFlow(AutoFileState())
    val state: StateFlow<AutoFileState> = _state
    private var cancelled = false
    /** Songs that failed, with when; they are left out for an hour, then tried again. */
    private val failed = HashMap<String, Long>()
    private var runMain: Long? = null
    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private fun recentlyFailed(k: String) = failed[k]?.let { now() - it < RETRY_MS } == true

    /** True when the owner has switched automatic filing on (Sort General page). */
    fun enabled(): Boolean = repo.pref(PREF_ON) == "true"
    fun setEnabled(on: Boolean) = repo.setPref(PREF_ON, on.toString())

    fun cancel() { cancelled = true }

    private fun key(s: Song) = "${s.chat_id}:${s.message_id}"

    private fun filedKeys(): MutableSet<String> = runCatching {
        Json.parseToJsonElement(repo.pref(PREF_DONE) ?: "[]").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toMutableSet()
    }.getOrElse { mutableSetOf() }

    private fun remember(s: Song, done: MutableSet<String>) {
        done += key(s)
        // Only the newest few thousand are kept.
        val keep = done.toList().takeLast(5000)
        repo.setPref(PREF_DONE, JsonArray(keep.map(::JsonPrimitive)).toString())
    }

    /** The confident suggestions for songs outside the main chat, best first. Empty without a main chat or topics. */
    fun plan(): List<TopicSuggestion> {
        val mainChat = parseMainChat(repo.pref(MAIN_CHAT_KEY)) ?: return emptyList()
        val topics = runCatching { repo.db.miscQueries.topics().executeAsList() }.getOrDefault(emptyList())
        val choices = topicChoices(topics, mainChat, emptyMap())
        if (choices.none { !it.isGeneral }) return emptyList()
        val all = repo.db.songsQueries.allSongs().executeAsList()
        val inMain = all.filter { it.chat_id == mainChat }
        val mainUnique = inMain.mapTo(HashSet()) { it.unique_id }
        val mainFiles = inMain.mapTo(HashSet()) { it.file_name.lowercase() to it.size }
        val copies = repo.db.filingQueries.allPostCopies().executeAsList()
        val copyUnique = copies.filter { it.chat_id == mainChat }.mapTo(HashSet()) { it.unique_id }
        val done = filedKeys()
        val candidates = all.filter {
            it.chat_id != mainChat && key(it) !in done && !recentlyFailed(key(it)) &&
                it.unique_id !in mainUnique && it.unique_id !in copyUnique && (it.file_name.lowercase() to it.size) !in mainFiles
        }
        if (candidates.isEmpty()) return emptyList()
        return suggestTopics(inMain, choices, copies.filter { it.chat_id == mainChat }, incoming = candidates)
            .filter { it.topic != null && !it.blocked && it.confidence == TopicConfidence.High }
    }

    /** Starts filing; does nothing when a run is going or nothing is confident enough. Returns how many songs are queued. */
    fun start(): Int {
        if (_state.value.running) return 0
        val picks = plan()
        if (picks.isEmpty()) { _state.value = AutoFileState(note = "Nothing to file"); return 0 }
        cancelled = false
        runMain = parseMainChat(repo.pref(MAIN_CHAT_KEY))
        _state.value = AutoFileState(true, 0, picks.size)
        scope.launch {
            var done = 0
            val filed = filedKeys()
            try {
                for ((i, p) in picks.withIndex()) {
                    if (cancelled) break
                    if (i > 0) delay(Random.nextLong(6000, 12000))
                    if (cancelled) break
                    val c = main()
                    if (c == null) { log("Auto filing stopped: the main bot is not connected"); break }
                    try {
                        fileOne(c, p, filed)
                        done++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TdError) {
                        failed[key(p.song)] = now()
                        log("Auto filing: \"${p.song.title}\" not filed: ${e.message}")
                        e.retryAfter?.let { delay((it + 1) * 1000L) }
                    } catch (e: Throwable) {
                        failed[key(p.song)] = now()
                        log("Auto filing: \"${p.song.title}\" not filed: ${e.message ?: e::class.simpleName}")
                    }
                    _state.value = AutoFileState(true, done, picks.size)
                }
            } finally {
                _state.value = AutoFileState(false, done, picks.size, "Filed $done of ${picks.size}")
                log("Auto filing finished: $done of ${picks.size} songs filed")
            }
        }
        return picks.size
    }

    private suspend fun fileOne(c: TdClient, p: TopicSuggestion, filed: MutableSet<String>) {
        val song = p.song
        val topic = p.topic ?: return
        val mainChat = parseMainChat(repo.pref(MAIN_CHAT_KEY)) ?: throw TdError(0, "No main chat")
        // The topics were planned for the main chat this run started with.
        if (runMain != null && runMain != mainChat) throw TdError(0, "The main chat changed during the run")
        // Re-read: the song may have been removed or filed while this run waited.
        val now = repo.db.songsQueries.song(song.chat_id, song.message_id).executeAsOneOrNull() ?: return
        var owner = botOwnerOf(now.remote_id)?.let(botClient) ?: c

        // 1. Copy the post as it is: the same file, no download or upload.
        var posted: JsonObject? = runCatching { copyPost(c, now, mainChat, topic.id) }.getOrNull()
        var how = "copied"
        // 2. Otherwise a bot that can read the post downloads it and the main bot uploads it.
        if (posted == null) {
            val (path, reader) = download(listOf(owner, c) + others(), now)
            owner = reader
            posted = upload(c, now, path, mainChat, topic.id)
            how = "downloaded and uploaded"
        }
        val newId = posted.long("id") ?: throw TdError(0, "The new post was not confirmed")
        // Older TDLib answers with message_thread_id instead of topic_id.
        val landed = posted.obj("topic_id")?.long("forum_topic_id")
            ?: posted.long("message_thread_id")?.takeIf { it != 0L } ?: GENERAL_TOPIC_ID
        // Read the new post back before trusting it.
        val back = c.call(request("getMessage") { put("chat_id", mainChat); put("message_id", newId) }, 30.seconds)
        val file = fileOfMessage(back) ?: throw TdError(0, "The new post is not a song")
        val sameFile = file.obj("remote")?.str("unique_id") == now.unique_id
        val sameSong = file.long("size") == now.size && kotlin.math.abs(durationOf(back) - now.duration_sec) <= 2
        remember(now, filed)
        if (landed != topic.id && !(topic.isGeneral && landed == GENERAL_TOPIC_ID)) {
            log("Auto filing: \"${now.title}\" $how into topic $landed, wanted ${topic.name}; the original is kept")
            return
        }
        if (sameFile) {
            val remote = file.obj("remote")?.str("id") ?: now.remote_id
            // The new post must be registered (it carries the song's playlists and edits over) before the original may go.
            if (runCatching { repo.listAtCopy(now, mainChat, newId, remote, topic.id) }.isFailure) {
                log("Auto filing: \"${now.title}\" $how, but the new post could not be registered; the original is kept")
                return
            }
        }
        if (!sameFile && !sameSong) {
            log("Auto filing: \"${now.title}\" $how to ${topic.name}, but the new post does not match the original; the original is kept")
            return
        }
        log("Auto filing: \"${now.title}\" $how to ${topic.name} (${p.reason})")
        // 3. Delete the original only now that the same song is confirmed in the main chat.
        // TDLib answers ok for posts it never saw, so the bot's answer is not believed: the post must be reported missing afterwards.
        val removed = runCatching {
            owner.call(request("deleteMessages") {
                put("chat_id", now.chat_id)
                put("message_ids", JsonArray(listOf(JsonPrimitive(now.message_id))))
                put("revoke", true)
            }, 30.seconds)
            try {
                owner.call(request("getMessage") { put("chat_id", now.chat_id); put("message_id", now.message_id) }, 30.seconds)
                false
            } catch (e: TdError) {
                e.code == 404 || e.message.contains("not found", ignoreCase = true)
            }
        }.getOrDefault(false)
        if (removed) {
            runCatching { repo.removePost(now.chat_id, now.message_id, mainChat) }
            log("Auto filing: original of \"${now.title}\" deleted")
        } else {
            log("Auto filing: original of \"${now.title}\" could not be deleted and was left in place")
        }
    }

    private fun durationOf(message: JsonObject): Int {
        val c = message.obj("content")
        return (c?.obj("audio")?.long("duration") ?: c?.obj("document")?.long("duration") ?: 0L).toInt()
    }

    private suspend fun copyPost(c: TdClient, s: Song, mainChat: Long, topicId: Long): JsonObject {
        val newApi = c.newApi()
        return sendAndWait(c) {
            c.call(request("forwardMessages") {
                put("chat_id", mainChat)
                topicField(topicId, newApi)
                put("from_chat_id", s.chat_id)
                put("message_ids", JsonArray(listOf(JsonPrimitive(s.message_id))))
                put("send_copy", true)
                // Only the song: the source post's caption stays behind.
                put("remove_caption", true)
                put("options", buildJsonObject { put("@type", "messageSendOptions"); put("disable_notification", true) })
            }, 60.seconds).let { r -> r["messages"]?.jsonArray?.firstOrNull() as? JsonObject ?: throw TdError(0, "Telegram gave no copy") }
        }
    }

    /** Downloads the post with the first bot that can read it; returns the local path and that bot. */
    private suspend fun download(readers: List<TdClient>, s: Song): Pair<String, TdClient> {
        var last: Throwable? = null
        for (r in readers.distinct()) {
            try {
                val msg = r.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) }, 30.seconds)
                val id = fileOfMessage(msg)?.long("id") ?: continue
                val f = r.call(request("downloadFile") {
                    put("file_id", id); put("priority", 16); put("offset", 0); put("limit", 0); put("synchronous", true)
                }, 30.minutes)
                val local = f.obj("local")
                val complete = (local?.get("is_downloading_completed") as? JsonPrimitive)?.contentOrNull == "true"
                local?.str("path")?.takeIf { complete && it.isNotEmpty() }?.let { return it to r }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw TdError(0, "No bot could download this post" + (last?.message?.let { ": $it" } ?: ""))
    }

    private suspend fun upload(c: TdClient, s: Song, path: String, mainChat: Long, topicId: Long): JsonObject {
        val newApi = c.newApi()
        return sendAndWait(c) {
            c.call(request("sendMessage") {
                put("chat_id", mainChat)
                topicField(topicId, newApi)
                put("options", buildJsonObject { put("@type", "messageSendOptions"); put("disable_notification", true) })
                put("input_message_content", audioContent(buildJsonObject { put("@type", "inputFileLocal"); put("path", path) }, newApi, s.title, s.artist.takeIf { it != "Unknown artist" }.orEmpty(), s.duration_sec.toInt()))
            }, 2.minutes)
        }
    }

    /** Runs [send] (which returns the pending message) and waits for Telegram to confirm it; returns the confirmed message. */
    private suspend fun sendAndWait(c: TdClient, send: suspend () -> JsonObject): JsonObject {
        val sentId = CompletableDeferred<Long>()
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
            c.updates.first { u ->
                val t = u["@type"]?.jsonPrimitive?.content
                (t == "updateMessageSendSucceeded" || t == "updateMessageSendFailed") && u.long("old_message_id") == sentId.await()
            }
        }
        try {
            val pending = send()
            sentId.complete(pending.long("id") ?: throw TdError(0, "Telegram did not accept the post"))
            val done = withTimeoutOrNull(30.minutes) { outcome.await() } ?: throw TdError(408, "Posting timed out")
            if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                val err = done.obj("error")
                throw TdError(err?.long("code")?.toInt() ?: 0, err?.str("message") ?: "Telegram refused the post")
            }
            return done.obj("message") ?: throw TdError(0, "The new post was not confirmed")
        } finally {
            outcome.cancel()
        }
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val PREF_ON = "auto_file"
        const val PREF_DONE = "auto_filed"
        const val RETRY_MS = 60L * 60 * 1000
    }
}
