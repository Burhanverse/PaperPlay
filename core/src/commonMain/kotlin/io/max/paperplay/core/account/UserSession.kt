package io.max.paperplay.core.account

import io.max.paperplay.core.app.Keys
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.app.Settings
import io.max.paperplay.core.app.long
import io.max.paperplay.core.app.obj
import io.max.paperplay.core.app.readRange
import io.max.paperplay.core.app.songFromMessage
import io.max.paperplay.core.app.str
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.backup.SecretKeys
import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.TdRuntime
import io.max.paperplay.core.telegram.documentContent
import io.max.paperplay.core.telegram.request
import io.max.paperplay.core.telegram.sendAndWait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The pref that remembers a signed-in account, so a restart reconnects without a code. */
const val USER_SESSION_PREF = "user_session"

/** "true" lets a copy from a chat that blocks copying download the song and upload it again (uses data). Off by default. */
const val COPY_REUPLOAD = "copy_reupload"

private const val READING_RATE_PREF = "rate_reading"
private const val DEFAULT_READING_RATE = 20
private const val PAGE = 100
private const val FLOOD_TRIES = 5

private val CHAT_LIST = buildJsonObject { put("@type", "chatListMain") }

/**
 * The owner's own account on a second TDLib client. Starts only when needed; a saved login
 * reconnects without a code. Never logs phone numbers, codes or passwords.
 */
class UserSession(
    private val scope: CoroutineScope,
    private val runtime: () -> TdRuntime,
    private val dataDir: String,
    private val secrets: SecretStore,
    private val settings: Settings,
    private val pref: (String) -> String?,
    private val setPref: (String, String) -> Unit,
    private val deviceModel: String = "Android",
    private val appVersion: String = "dev",
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) : AccountApi {
    private val _step = MutableStateFlow<LoginStep>(if (pref(USER_SESSION_PREF) == "1") LoginStep.Ready else LoginStep.LoggedOut)
    private val _loggedIn = MutableStateFlow(pref(USER_SESSION_PREF) == "1")
    private val lock = Mutex()
    private var client: TdClient? = null
    private var watcher: Job? = null
    private var qrMode = false

    override val step: StateFlow<LoginStep> = _step
    override val loggedIn: StateFlow<Boolean> = _loggedIn

    private val databaseDir get() = "$dataDir/user_td"
    private val filesDir get() = "$dataDir/user_td_files"

    // ---- login ----

    override suspend fun begin() {
        qrMode = false
        try {
            val c = open()
            publish(c.auth.value, c.authLink.value, c.authError.value)
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun beginQr() {
        qrMode = true
        try {
            val c = open()
            withTimeoutOrNull(20.seconds) { c.auth.first { it == "authorizationStateWaitPhoneNumber" || it == "authorizationStateReady" } }
            if (c.auth.value == "authorizationStateWaitPhoneNumber") {
                c.call(request("requestQrCodeAuthentication") { put("other_user_ids", JsonArray(emptyList())) })
            }
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun phone(number: String) = answer { it.call(request("setAuthenticationPhoneNumber") { put("phone_number", number) }) }

    override suspend fun code(code: String) = answer { it.call(request("checkAuthenticationCode") { put("code", code) }) }

    override suspend fun password(password: String) = answer { it.call(request("checkAuthenticationPassword") { put("password", password) }) }

    private suspend fun answer(block: suspend (TdClient) -> Unit) {
        try {
            block(open())
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun logOut() {
        try {
            val c = open()
            runCatching { c.call(request("logOut"), 15.seconds) }
            c.shutdown()
        } catch (_: TdError) {
            // The session is forgotten here either way.
        }
        lock.withLock {
            watcher?.cancel()
            watcher = null
            client = null
        }
        runCatching { Fs.deleteRecursively(databaseDir) }
        runCatching { Fs.deleteRecursively(filesDir) }
        setPref(USER_SESSION_PREF, "0")
        _loggedIn.value = false
        _step.value = LoginStep.LoggedOut
    }

    /** Stops the client so its files are whole for a backup; it starts again when next needed. Stays logged in. */
    suspend fun closeForBackup() {
        val c = lock.withLock {
            watcher?.cancel()
            watcher = null
            client.also { client = null }
        }
        runCatching { c?.shutdown() }
    }

    /** The running client, starting it (and following its login state) when there is none. */
    private suspend fun open(): TdClient = lock.withLock {
        client?.let { return it }
        val apiId = settings.get(Keys.API_ID)?.toIntOrNull()
        val apiHash = settings.get(Keys.API_HASH)
        if (apiId == null || apiHash.isNullOrEmpty()) throw TdError(0, "Enter the API id and API hash first.")
        val key = secrets.get(SecretKeys.USER_SESSION_KEY) ?: (newUuid() + newUuid()).also { secrets.put(SecretKeys.USER_SESSION_KEY, it) }
        val c = TdClient(
            runtime(),
            TdConfig(
                apiId, apiHash, "", databaseDir, filesDir, key,
                deviceModel = deviceModel, appVersion = appVersion, useMessageDatabase = false,
            ),
        )
        client = c
        if (!_loggedIn.value) _step.value = LoginStep.Starting
        watcher = scope.launch {
            combine(c.auth, c.authLink, c.authError) { a, l, e -> Triple(a, l, e) }.collect { (a, l, e) -> publish(a, l, e) }
        }
        c.start()
        c
    }

    private fun publish(state: String, link: String?, error: String?) {
        if (error != null) {
            _step.value = LoginStep.Failed(error)
            return
        }
        when (state) {
            "authorizationStateWaitPhoneNumber" -> {
                if (_loggedIn.value) { // Telegram ended the saved session
                    _loggedIn.value = false
                    setPref(USER_SESSION_PREF, "0")
                }
                _step.value = if (qrMode) LoginStep.Starting else LoginStep.Phone
            }
            "authorizationStateWaitCode" -> _step.value = LoginStep.Code
            "authorizationStateWaitPassword" -> _step.value = LoginStep.Password
            "authorizationStateWaitOtherDeviceConfirmation" -> link?.let { _step.value = LoginStep.Qr(it) }
            "authorizationStateReady" -> {
                _loggedIn.value = true
                setPref(USER_SESSION_PREF, "1")
                _step.value = LoginStep.Ready
            }
            "", "authorizationStateWaitTdlibParameters" -> if (!_loggedIn.value) _step.value = LoginStep.Starting
            "authorizationStateWaitRegistration", "authorizationStateWaitEmailAddress", "authorizationStateWaitEmailCode",
            "authorizationStateWaitPremiumPurchase" -> _step.value = LoginStep.Failed("This account needs a sign-in step the app cannot do.")
        }
    }

    /** A client that is logged in; throws [TdError] when it cannot get there. */
    /** The account's TDLib client once it is logged in, for streaming songs the bot cannot read. */
    fun streamClient(): TdClient? = client.takeIf { _loggedIn.value }

    /** Starts the client and makes [chatId] known to it, so a song of that chat can be played right away. */
    suspend fun warmUp(chatId: Long) {
        runCatching {
            val c = ready()
            knowChat(c, chatId, loadList = true)
        }
    }

    private suspend fun ready(): TdClient {
        val c = open()
        // While the login waits for the person (phone, code, password) there is nothing to wait out; fail now instead of "timed out".
        val s = c.auth.value
        if (s == "authorizationStateWaitPhoneNumber" || s == "authorizationStateWaitCode" || s == "authorizationStateWaitPassword" ||
            s == "authorizationStateWaitOtherDeviceConfirmation"
        ) throw TdError(401, "Your account is not logged in yet.")
        c.ready(30.seconds)
        return c
    }

    /** Sends [req], waiting out flood control and trying again. */
    private suspend fun call(c: TdClient, req: JsonObject, timeoutSeconds: Int = 60): JsonObject {
        var tries = 0
        while (true) {
            try {
                return c.call(req, timeoutSeconds.seconds)
            } catch (e: TdError) {
                val wait = e.retryAfter
                if (wait == null || ++tries >= FLOOD_TRIES) throw e
                pause((wait + 1) * 1000L)
            }
        }
    }

    // ---- chats ----

    override suspend fun groupsAndChannels(): List<ChatInfo> {
        if (!_loggedIn.value) return emptyList()
        val c = ready()
        try {
            call(c, request("loadChats") { put("chat_list", CHAT_LIST); put("limit", 500) })
        } catch (e: TdError) {
            // Already all loaded (TDLib answers 404).
            if (e.code != 404) throw e
        }
        val ids = call(c, request("getChats") { put("chat_list", CHAT_LIST); put("limit", 500) })["chat_ids"] as? JsonArray ?: return emptyList()
        val out = ArrayList<ChatInfo>()
        for (id in ids) {
            val chatId = id.jsonPrimitive.longOrNull ?: continue
            val chat = call(c, request("getChat") { put("chat_id", chatId) })
            val type = chat.obj("type") ?: continue
            when (type.str("@type")) {
                "chatTypeBasicGroup" -> out += ChatInfo(chat.long("id") ?: continue, chat.str("title").orEmpty(), false)
                "chatTypeSupergroup" -> out += ChatInfo(chat.long("id") ?: continue, chat.str("title").orEmpty(), type["is_channel"]?.jsonPrimitive?.content == "true")
            }
        }
        return out
    }

    /** The tiny cover Telegram puts inside an audio post (a JPEG of about 40 px), or null. Part of the message: no download. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun miniCover(msg: JsonObject): ByteArray? {
        val data = msg.obj("content")?.obj("audio")?.obj("album_cover_minithumbnail")?.str("data") ?: return null
        return runCatching { kotlin.io.encoding.Base64.decode(data) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun postThumb(chatId: Long, messageId: Long): ByteArray? = try {
        if (!_loggedIn.value) null else {
            val c = ready()
            knowChat(c, chatId, loadList = false)
            miniCover(call(c, request("getMessage") { put("chat_id", chatId); put("message_id", messageId) }))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun chatLinks(chatId: Long): ChatLinks? {
        if (!_loggedIn.value) return null
        val c = ready()
        knowChat(c, chatId, loadList = false)
        val type = call(c, request("getChat") { put("chat_id", chatId) }).obj("type") ?: return null
        return when (type.str("@type")) {
            "chatTypeSupergroup" -> {
                val id = type.long("supergroup_id") ?: return null
                val group = call(c, request("getSupergroup") { put("supergroup_id", id) })
                val name = group.obj("usernames")?.let { u -> (u["active_usernames"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content } ?: group.str("username").orEmpty()
                // Only admins see the invite link; for everyone else this is empty and the public name has to do.
                val invite = runCatching { call(c, request("getSupergroupFullInfo") { put("supergroup_id", id) }).obj("invite_link")?.str("invite_link") }.getOrNull().orEmpty()
                ChatLinks(name, invite, type["is_channel"]?.jsonPrimitive?.content == "true")
            }
            "chatTypeBasicGroup" -> {
                val id = type.long("basic_group_id") ?: return null
                val invite = runCatching { call(c, request("getBasicGroupFullInfo") { put("basic_group_id", id) }).obj("invite_link")?.str("invite_link") }.getOrNull().orEmpty()
                ChatLinks("", invite, false)
            }
            else -> null
        }
    }

    override suspend fun joinChat(link: String): Long? {
        if (!_loggedIn.value) return null
        val c = ready()
        val l = link.trim().removePrefix("https://").removePrefix("http://").removePrefix("t.me/").removePrefix("telegram.me/")
        val chat = if (l.startsWith("+") || l.startsWith("joinchat/")) {
            call(c, request("joinChatByInviteLink") { put("invite_link", link.trim()) })
        } else {
            val found = call(c, request("searchPublicChat") { put("username", l.substringBefore('/').removePrefix("@")) })
            val id = found.long("id") ?: return null
            runCatching { call(c, request("joinChat") { put("chat_id", id) }) }
            found
        }
        return chat.long("id")
    }

    override suspend fun canSee(chatId: Long): Boolean {
        if (!_loggedIn.value) return false
        val c = ready()
        return try {
            knowChat(c, chatId, loadList = false)
            val type = call(c, request("getChat") { put("chat_id", chatId) }).obj("type") ?: return false
            if (type.str("@type") != "chatTypeSupergroup") return true
            val sg = type.long("supergroup_id") ?: return false
            val group = call(c, request("getSupergroup") { put("supergroup_id", sg) })
            val status = group.obj("status")?.str("@type")
            // A public channel can be read without joining; a private one only as a member.
            val public = group.obj("usernames")?.let { (it["active_usernames"] as? JsonArray)?.isNotEmpty() } == true || group.str("username").orEmpty().isNotEmpty()
            public || (status != "chatMemberStatusLeft" && status != "chatMemberStatusBanned")
        } catch (e: TdError) {
            false
        }
    }

    @kotlin.concurrent.Volatile override var lastReadNotes: String = ""
        private set

    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = songsIn(chatId, afterMessageId, null)

    override fun songsIn(chatId: Long, afterMessageId: Long, checkpoint: ReadCheckpoint?): Flow<List<ChatPost>> = flow {
        if (!_loggedIn.value) return@flow
        val skipped = HashMap<String, Int>()
        var small = 0
        val c = ready()
        // A session that has not seen the chat yet answers "Chat not found" to every read of it.
        knowChat(c, chatId, loadList = true)
        val seen = HashSet<Long>()
        val found = LinkedHashMap<String, Int>()
        val totals = HashMap<String, Long>()
        for ((fi, filter) in listOf("searchMessagesFilterAudio", "searchMessagesFilterDocument").withIndex()) {
            if (checkpoint != null && fi < checkpoint.filter) continue
            var from = if (checkpoint != null && fi == checkpoint.filter) checkpoint.from else 0L
            var emptyPages = 0
            while (true) {
                val page = call(c, request("searchChatMessages") {
                    put("chat_id", chatId)
                    put("query", "")
                    put("from_message_id", from)
                    put("offset", 0)
                    put("limit", PAGE)
                    put("filter", buildJsonObject { put("@type", filter) })
                })
                val messages = page["messages"] as? JsonArray ?: JsonArray(emptyList())
                found[filter] = (found[filter] ?: 0) + messages.size
                if (filter !in totals) page.long("total_count")?.let { totals[filter] = it }
                val batch = ArrayList<ChatPost>()
                var reached = false
                for (m in messages) {
                    val msg = m.jsonObject
                    val id = msg.long("id") ?: continue
                    if (afterMessageId > 0 && id <= afterMessageId) { reached = true; break }
                    val song = songFromMessage(msg)
                    if (song == null) {
                        val content = msg.obj("content")
                        val kind = (content?.str("@type") ?: "unknown").removePrefix("message") + (content?.obj("document")?.str("file_name")?.substringAfterLast('.', "")?.let { " .$it" } ?: "")
                        skipped[kind] = (skipped[kind] ?: 0) + 1
                        continue
                    }
                    if (!seen.add(id)) continue
                    if (song.size in 1 until 2L * 1024 * 1024) small++
                    batch += ChatPost(
                        chatId = chatId, messageId = id, uniqueId = song.unique_id, title = song.title, artist = song.artist,
                        durationSec = song.duration_sec.toInt(), size = song.size, mime = song.mime, fileName = song.file_name, date = song.date,
                        thumb = miniCover(msg),
                    )
                }
                // Sent even when empty, so the reader can stop between any two pages.
                emit(batch)
                val next = page.long("next_from_message_id") ?: 0L
                // TDLib may answer a page with fewer posts than asked, even none, while more follow; only the end marker or several empty pages in a row stop the read.
                emptyPages = if (messages.isEmpty()) emptyPages + 1 else 0
                if (reached || next == 0L || next == from) { checkpoint?.let { it.filter = fi + 1; it.from = 0L }; break }
                // A cursor that still moves means more may follow; ending here would record a partial read as whole.
                if (emptyPages > 3) throw TdError(0, "Telegram stopped returning posts before the end of the chat. Try the read again.")
                from = next
                checkpoint?.let { it.filter = fi; it.from = next }
                pause(60_000L / readingRate())
            }
        }
        // Only a full read writes the notes; the short catch-up read after it would overwrite them with its first page.
        if (afterMessageId == 0L) lastReadNotes = "Posts returned by Telegram: " + found.entries.joinToString(", ") { "${it.value} by " + it.key.removePrefix("searchMessagesFilter").lowercase() + (totals[it.key]?.let { t -> " (Telegram counts $t)" } ?: "") } + ". Songs under 2 MB read: $small." + if (skipped.isEmpty()) "" else " Posts skipped because they are not songs: " + skipped.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" } + "."
    }

    override suspend fun topicNames(chatId: Long): Map<Long, String> {
        if (!_loggedIn.value) return emptyMap()
        return try {
            val c = ready()
            knowChat(c, chatId, loadList = true)
            val out = HashMap<Long, String>()
            var offsetDate = 0L
            var offsetMessage = 0L
            var offsetTopic = 0L
            repeat(30) {
                val page = call(c, request("getForumTopics") {
                    put("chat_id", chatId)
                    put("query", "")
                    put("offset_date", offsetDate)
                    put("offset_message_id", offsetMessage)
                    put("offset_forum_topic_id", offsetTopic)
                    put("offset_message_thread_id", offsetTopic)
                    put("limit", 100)
                })
                val topics = page["topics"] as? JsonArray ?: return out
                if (topics.isEmpty()) return out
                for (t in topics) {
                    val info = t.jsonObject.obj("info") ?: continue
                    val id = info.long("forum_topic_id") ?: info.long("message_thread_id") ?: continue
                    info.str("name")?.takeIf { it.isNotEmpty() }?.let { out[id] = it }
                }
                offsetDate = page.long("next_offset_date") ?: 0L
                offsetMessage = page.long("next_offset_message_id") ?: 0L
                offsetTopic = page.long("next_offset_forum_topic_id") ?: page.long("next_offset_message_thread_id") ?: 0L
                if (offsetDate == 0L && offsetMessage == 0L && offsetTopic == 0L) return out
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: TdError) {
            emptyMap()
        }
    }

    private fun readingRate() = (pref(READING_RATE_PREF)?.toIntOrNull() ?: DEFAULT_READING_RATE).coerceIn(1, 1200)

    /** Makes [chatId] known to this session; a new session knows no chats yet. */
    private suspend fun knowChat(c: TdClient, chatId: Long, loadList: Boolean) {
        try {
            call(c, request("getChat") { put("chat_id", chatId) })
            return
        } catch (_: TdError) {
            // Not in this session's cache yet.
        }
        if (loadList) {
            try {
                call(c, request("loadChats") { put("chat_list", CHAT_LIST); put("limit", 500) })
            } catch (_: TdError) {
                // Already all loaded.
            }
        }
        // -100... is a supergroup or channel; any other negative id is a basic group.
        if (chatId <= -1_000_000_000_000L || !loadList) {
            call(c, request("createSupergroupChat") { put("supergroup_id", -chatId - 1_000_000_000_000L); put("force", false) })
        } else {
            call(c, request("createBasicGroupChat") { put("basic_group_id", -chatId); put("force", false) })
        }
    }

    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) {
        val c = ready()
        knowChat(c, toChat, loadList = false)
        // send_copy posts it as a new message with no "forwarded from" line; remove_caption leaves the other chat's text
        // ("reQuest by ...", links, tags) behind, so only the song goes in.
        var accepted = false
        var refused: Exception? = null
        try {
            // TDLib accepts the copy long before Telegram has it. Callers delete the original once this returns, so it returns only
            // when Telegram has confirmed the new post.
            c.sendAndWait(COPY_CONFIRM) {
                val answer = call(c, request("forwardMessages") {
                    put("chat_id", toChat)
                    put("from_chat_id", fromChat)
                    put("message_ids", buildJsonArray { add(messageId) })
                    put("send_copy", true)
                    put("remove_caption", true)
                })
                // Telegram answers with an empty entry for a post it will not copy, for example when the chat blocks saving content.
                val first = (answer["messages"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw IllegalStateException()
                accepted = true
                first
            }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refused = e
        }
        // Accepted but not confirmed: it may still arrive, so it is not sent a second time, and the original must stay.
        if (accepted) throw IllegalStateException("The copy was not confirmed by Telegram, so the original post was kept: ${refused?.message}.")
        // Refused. Sending it again means downloading the whole file and uploading it, tens of MB each way for a FLAC, so it only
        // happens when the owner turned it on. The reason is Telegram's own: the chat's "restrict saving content", or its error.
        if (pref(COPY_REUPLOAD) != "true") {
            val protected = runCatching { call(c, request("getChat") { put("chat_id", fromChat) })["has_protected_content"]?.jsonPrimitive?.content == "true" }.getOrNull()
            val why = refused?.message
            throw IllegalStateException(
                when {
                    protected == true -> "This chat does not allow copying (it restricts saving content)."
                    why != null -> "Telegram refused the copy: $why."
                    else -> "Telegram did not copy this post and gave no reason."
                } + " Turn on \"Send again when a chat blocks copying\" to download and upload it instead (uses data).",
            )
        }
        reupload(c, fromChat, messageId, toChat)
    }

    private val _transfer = kotlinx.coroutines.flow.MutableStateFlow<Transfer?>(null)
    override val transfer: kotlinx.coroutines.flow.StateFlow<Transfer?> = _transfer

    /**
     * Reports the progress of [fileId] once a second as [_transfer] until [finished] says so (or the caller cancels): bytes done
     * (downloaded or uploaded) of the file's size, and the speed over the last second.
     */
    private suspend fun watchFile(c: TdClient, fileId: Int, title: String, upload: Boolean, finished: (JsonObject) -> Boolean = { false }) {
        var last = -1L
        while (true) {
            val f = runCatching { call(c, request("getFile") { put("file_id", fileId) }) }.getOrNull()
            if (f != null) {
                val done = if (upload) f.obj("remote")?.long("uploaded_size") ?: 0L else f.obj("local")?.long("downloaded_size") ?: 0L
                val total = (f.long("size") ?: 0L).takeIf { it > 0 } ?: f.long("expected_size") ?: 0L
                _transfer.value = Transfer(title, upload, done, total, if (last < 0) 0 else (done - last).coerceAtLeast(0))
                last = done
                if (finished(f)) return
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun reupload(c: TdClient, fromChat: Long, messageId: Long, toChat: Long) {
        val message = call(c, request("getMessage") { put("chat_id", fromChat); put("message_id", messageId) })
        val song = io.max.paperplay.core.app.songFromMessage(message)
            ?: throw IllegalStateException("Telegram did not copy this post, and it holds no song to send again.")
        io.max.paperplay.player.PlayLog.log("copy", "Sending \"${song.title}\" again: the chat blocks copying, so it is downloaded and uploaded (${song.size / (1L shl 20)} MB each way)")
        val (path, fileId) = downloadPost(fromChat, messageId, song.title)
            ?: throw IllegalStateException("Telegram did not copy this post, and the file could not be downloaded to send it again.")
        try {
            val newApi = c.newApi()
            kotlinx.coroutines.coroutineScope {
                var watcher: kotlinx.coroutines.Job? = null
                try {
                    // The message is only queued at first: the file uploads now. The download is kept until Telegram has confirmed the post,
                    // or it would be deleted from under the upload.
                    c.sendAndWait(REUPLOAD_CONFIRM) {
                        val queued = call(c, request("sendMessage") {
                            put("chat_id", toChat)
                            put("input_message_content", io.max.paperplay.core.telegram.audioContent(
                                kotlinx.serialization.json.buildJsonObject { put("@type", "inputFileLocal"); put("path", path) },
                                newApi, song.title, song.artist, song.duration_sec.toInt(),
                            ))
                        }, 600)
                        val upId = queued.obj("content")?.let { it.obj("audio")?.obj("audio") ?: it.obj("document")?.obj("document") }?.long("id")?.toInt()
                        if (upId != null) watcher = launch { watchFile(c, upId, song.title, upload = true) }
                        queued
                    }
                } finally {
                    watcher?.cancel()
                }
            }
        } finally {
            _transfer.value = null
            releaseFile(fileId)
        }
    }

    /** Downloads the song of a post with the owner's account. The local path and TDLib's file id, or null when it holds no song. */
    suspend fun downloadPost(chatId: Long, messageId: Long, progressTitle: String? = null): Pair<String, Int>? {
        val c = ready()
        knowChat(c, chatId, loadList = false)
        val message = call(c, request("getMessage") { put("chat_id", chatId); put("message_id", messageId) })
        val fileId = io.max.paperplay.core.app.fileOfMessage(message)?.long("id")?.toInt() ?: return null
        val file = kotlinx.coroutines.coroutineScope {
            // While the download runs, its progress is reported once a second for the copy's status line.
            val watcher = progressTitle?.let { t -> launch { watchFile(c, fileId, t, upload = false) } }
            try {
                call(c, request("downloadFile") {
                    put("file_id", fileId); put("priority", 1); put("offset", 0); put("limit", 0); put("synchronous", true)
                }, 1800)
            } finally {
                watcher?.cancel()
            }
        }
        val path = file.obj("local")?.str("path")?.takeIf { it.isNotEmpty() } ?: return null
        return path to fileId
    }

    override suspend fun clearDownloads() { optimize(0L) }

    /** Keeps the account's own downloads (songs it plays that no bot can read, and half-finished files) under [maxBytes]. */
    override suspend fun trimDownloads(maxBytes: Long, open: Boolean): JsonObject? = optimize(maxBytes, open)

    private suspend fun optimize(size: Long, open: Boolean = true): JsonObject? {
        // The session opens only when something needs it; [open] lets a logged-in account be opened here too.
        val c = client ?: if (open && _loggedIn.value) ready() else return null
        return call(c, request("optimizeStorage") {
            put("size", size); put("ttl", -1); put("count", -1); put("immunity_delay", if (size == 0L) 0 else 600)
            put("file_types", buildJsonArray {
                for (t in listOf("fileTypeAudio", "fileTypeDocument")) add(buildJsonObject { put("@type", t) })
            })
            put("chat_ids", buildJsonArray { }); put("exclude_chat_ids", buildJsonArray { })
            put("return_deleted_file_statistics", true); put("chat_limit", 0)
        }, 300)
    }

    /** Lets TDLib forget a downloaded file once it has been sent. */
    suspend fun releaseFile(fileId: Int) {
        val c = ready()
        runCatching { call(c, request("deleteFile") { put("file_id", fileId) }) }
    }

    /** Deletes [ids] for everyone in one call. False when not logged in or Telegram refuses. */
    /** Telegram's reason the last delete failed, for the log. */
    @Volatile var lastDeleteError: String? = null

    /** Which of [ids] this account can still see in [chatId]; null when Telegram could not be asked. TDLib answers a delete with ok even for posts it never loaded, so a delete is only believed after this. */
    override suspend fun existingPosts(chatId: Long, ids: List<Long>): Set<Long>? = existing(chatId, ids)

    suspend fun existing(chatId: Long, ids: List<Long>): Set<Long>? = try {
        val c = ready()
        knowChat(c, chatId, loadList = true)
        val alive = HashSet<Long>()
        for (chunk in ids.chunked(100)) {
            val page = call(c, request("getMessages") {
                put("chat_id", chatId)
                put("message_ids", buildJsonArray { chunk.forEach { add(it) } })
            })
            val messages = page["messages"] as? JsonArray ?: return null
            chunk.forEachIndexed { i, id -> if (messages.getOrNull(i) is JsonObject) alive += id }
        }
        alive
    } catch (e: CancellationException) {
        throw e
    } catch (e: TdError) {
        lastDeleteError = e.message
        null
    }

    suspend fun deleteMessages(chatId: Long, ids: List<Long>): Boolean {
        if (!_loggedIn.value) return false
        return try {
            val c = ready()
            knowChat(c, chatId, loadList = true)
            call(c, request("deleteMessages") {
                put("chat_id", chatId)
                put("message_ids", buildJsonArray { ids.forEach { add(it) } })
                put("revoke", true)
            })
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastDeleteError = e.message ?: e::class.simpleName
            false
        }
    }

    // ---- Saved Messages ----

    private suspend fun savedChat(c: TdClient): Long {
        val me = call(c, request("getMe")).long("id") ?: throw TdError(0, "Could not read the account.")
        return call(c, request("createPrivateChat") { put("user_id", me); put("force", false) }).long("id") ?: me
    }

    override suspend fun saveToCloud(tag: String, fileName: String, content: suspend (Appendable) -> Unit) {
        val c = ready()
        val chat = savedChat(c)
        val dir = "$filesDir/cloud_${newUuid()}"
        val path = "$dir/${fileName.replace('/', '_').replace('\\', '_')}"
        Fs.mkdirs(dir)
        try {
            val out = io.max.paperplay.core.backup.FileAppender(path)
            content(out)
            out.close()
            val sentId = CompletableDeferred<Long>()
            val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
                c.updates.first { u ->
                    val type = u["@type"]?.jsonPrimitive?.content
                    (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") && u.long("old_message_id") == sentId.await()
                }
            }
            try {
                val newApi = c.newApi()
                val sent = call(c, request("sendMessage") {
                    put("chat_id", chat)
                    put("input_message_content", documentContent(
                        buildJsonObject { put("@type", "inputFileLocal"); put("path", path) }, newApi,
                        buildJsonObject { put("@type", "formattedText"); put("text", tag) },
                    ))
                })
                sentId.complete(sent.long("id") ?: throw TdError(0, "Telegram did not accept the file."))
                val done = withTimeoutOrNull(5.minutes) { outcome.await() } ?: throw TdError(408, "Upload timed out")
                if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                    throw TdError(0, done.obj("error")?.str("message") ?: "Telegram refused the file.")
                }
            } finally {
                outcome.cancel()
            }
        } finally {
            runCatching { Fs.deleteRecursively(dir) }
        }
    }

    override suspend fun loadFromCloud(tag: String): String? {
        val c = ready()
        val chat = savedChat(c)
        val page = call(c, request("searchChatMessages") {
            put("chat_id", chat)
            put("query", tag)
            put("from_message_id", 0)
            put("offset", 0)
            put("limit", 50)
            put("filter", buildJsonObject { put("@type", "searchMessagesFilterDocument") })
        })
        val message = (page["messages"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it.obj("content")?.obj("caption")?.str("text")?.contains(tag) == true }
            .maxByOrNull { it.long("id") ?: 0L } ?: return null
        val fileId = message.obj("content")?.obj("document")?.obj("document")?.long("id") ?: return null
        val file = call(c, request("downloadFile") {
            put("file_id", fileId)
            put("priority", 1)
            put("offset", 0)
            put("limit", 0)
            put("synchronous", true)
        }, 600)
        return file.obj("local")?.str("path")?.takeIf { it.isNotEmpty() }
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

/** Lets the delete queue ask the owner's account to delete what the bot is refused. */
class UserAccountDeleter(private val session: UserSession) : io.max.paperplay.core.dupes.AccountDeleter {
    override val isLoggedIn: Boolean get() = session.loggedIn.value

    override val lastError: String? get() = session.lastDeleteError

    override suspend fun existing(chatId: Long, ids: List<Long>): Set<Long>? = if (isLoggedIn) session.existing(chatId, ids) else null

    override suspend fun deleteBatch(chatId: Long, ids: List<Long>): List<Long> {
        if (!isLoggedIn) return ids
        val left = ArrayList<Long>()
        for (chunk in ids.chunked(100)) {
            if (session.deleteMessages(chatId, chunk)) continue
            // One refused post fails the whole call, so the rest go singly.
            for (id in chunk) if (!session.deleteMessages(chatId, listOf(id))) left += id
        }
        // Telegram is asked what is still there: a delete that answered ok for a post the account never loaded deleted nothing.
        // Not asked means not confirmed: everything stays unresolved.
        val alive = session.existing(chatId, ids) ?: return ids
        return ids.filter { it in alive }
    }
}

/** How long Telegram has to confirm a copied post, and one that is uploaded again. */
private val COPY_CONFIRM = 5.minutes
private val REUPLOAD_CONFIRM = 30.minutes
