package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.integrations.defaultHttpFetcher
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.TdRuntime
import io.max.paperplay.core.dupes.asCaller
import io.max.paperplay.core.telegram.request
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock

/**
 * The one set of app services per process: database, Telegram bot session, scanner and player.
 * No UI code. Create it once, call [connect] when credentials exist, and read the StateFlows.
 */
class AppServices(
    val platform: Platform,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val driver = platform.openDriver("${platform.dataDir}/library.sqlite")
    val db = LibraryDatabase(driver)
    val library = LibraryRepository(LibraryRepo(db, clock), clock, scope)
    val scanner = Scanner(library, scope)

    /** Looks for newer releases on GitHub; the person's update choices are kept as settings of this device. */
    val updates = io.max.paperplay.core.update.UpdateService(
        defaultHttpFetcher(), platform.updateInstaller,
        io.max.paperplay.core.update.UpdatePrefs({ platform.settings.get(it) }, { k, v -> platform.settings.put(k, v) }),
        platform.updatePlatform,
        io.max.paperplay.core.update.Version.parse(platform.appVersion) ?: io.max.paperplay.core.update.Version(0, 0, 0, io.max.paperplay.core.update.Stage.Dev),
        clock,
    )

    /** Local network sync and connect. Null (off) until [enableLanSync] is called; it does not listen until `start()`. */
    var lanSync: io.max.paperplay.core.sync.LanSync? = null
        private set

    fun enableLanSync(isDesktop: Boolean): io.max.paperplay.core.sync.LanSync = lanSync ?: io.max.paperplay.core.sync.createLanSync(
        io.max.paperplay.core.sync.LanEnv(
            library, platform.secrets, scope, isDesktop, platform.deviceModel, { platform.secrets.get(Keys.BOT_TOKEN) },
            coverBytes = { uid ->
                val song = library.songByUniqueId(uid)
                val path = song?.cover_path
                if (song == null || path == null) null
                else fileLength(path).takeIf { it > 0 }?.let { n -> io.max.paperplay.core.sync.CoverData(song.cover_at, readRange(path, 0, n.toInt()), song.cover_url) }
            },
            saveCover = { uid, at, bytes, sourceUrl ->
                library.songByUniqueId(uid)?.let { song ->
                    // A hand-chosen cover keeps the time it was chosen (so the devices stop offering it to each other); an automatic one only
                    // fills a song that has none and is never marked as chosen by hand.
                    if (at != 0L && at > song.cover_at) {
                        enricher.setCoverBytes(listOf(song), bytes)
                        driver.execute(null, "UPDATE songs SET cover_at = ? WHERE unique_id = ?", 2) { bindLong(0, at); bindString(1, uid) }
                        library.invalidate()
                    } else if (song.cover_path == null || (at == 0L && song.cover_at == 0L && sourceUrl != null && song.cover_url == sourceUrl)) {
                        // An automatic cover fills a song that has none, or replaces ours when both devices now point at the same source.
                        val dir = "${platform.dataDir.trimEnd('/')}/covers"
                        io.max.paperplay.core.backup.Fs.mkdirs(dir)
                        val path = "$dir/${uid.replace(Regex("[^A-Za-z0-9._-]"), "_")}_sync.jpg"
                        io.max.paperplay.core.backup.Fs.write(path, bytes)
                        library.replaceSong(song.copy(cover_path = path))
                        library.invalidate()
                    }
                }
            },
        ),
    ).also { lanSync = it; bindLan(it) }

    /** The device this one controls from its player: null is none (this device plays), else a paired device's id. */
    private val _activeOutput = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val activeOutput: kotlinx.coroutines.flow.StateFlow<String?> = _activeOutput

    /** This device's sound output, (name, kind), filled in by the screen layer: headphones, speakers or phone. */
    @kotlin.concurrent.Volatile
    var outputInfo: () -> Pair<String, String>? = { null }

    /** Plays [uniqueId] here from [positionMs]; false when this device's library does not have the song. */
    private fun playHere(uniqueId: String, positionMs: Long): Boolean {
        val song = library.songByUniqueId(uniqueId) ?: return false
        playback.setQueue(listOf(song), 0)
        scope.launch {
            // The engine takes a moment to load the song; the seek lands once it can.
            repeat(40) {
                if (playback.nowPlayingStatic.value.song?.unique_id == uniqueId && playback.nowPlayingStatic.value.durationMs > 0) {
                    playback.seek(positionMs); return@launch
                }
                kotlinx.coroutines.delay(100)
            }
            playback.seek(positionMs)
        }
        return true
    }

    /**
     * Sends the song playing here to [deviceId] and makes this screen control that device, or back to this device when null.
     * Both devices keep playing on their own. Nothing changes until the other side has answered.
     * False (nothing changed) when the other device did not answer in time or cannot play that song.
     */
    suspend fun switchOutput(deviceId: String?, move: Boolean = false): Boolean {
        val lan = lanSync ?: return false
        val from = _activeOutput.value
        if (deviceId == from && !move) return true
        if (deviceId != null) {
            val np = if (playbackStarted) playback.nowPlaying.value else null
            val uid = np?.song?.unique_id
            if (uid == null) {
                // Nothing plays here: just control that device.
                _activeOutput.value = deviceId
                return true
            }
            if (!sendQueue(lan, deviceId, np.queue, np.index, np.positionMs)) return false
            val answered = kotlinx.coroutines.withTimeoutOrNull(6_000) {
                while (true) {
                    val n = lan.remoteNow.value[deviceId]
                    if (n != null && n.uniqueId == uid && n.fresh(System.currentTimeMillis())) return@withTimeoutOrNull true
                    kotlinx.coroutines.delay(100)
                }
                @Suppress("UNREACHABLE_CODE") false
            } == true
            if (!answered) { lan.sendControl(deviceId, "pause"); return false }
            // "Play on": this device keeps playing, each device plays its own song, and this screen controls the chosen one.
            // "Move": the song and queue go over and this device stops.
            if (move) playback.pause()
            _activeOutput.value = deviceId
            return true
        }
        if (move && from != null) {
            // Move back here: the song the other device plays (and the queue, when it sent one) continues here, and it stops.
            val now = lan.remoteNow.value[from]
            val uid = now?.uniqueId
            if (uid != null && !playHere(uid, now.positionAt(System.currentTimeMillis()))) return false
            lan.sendControl(from, "pause")
        }
        // Back to this device's own player; without "move" the other device keeps playing what it plays.
        _activeOutput.value = null
        return true
    }

    /** Sends [queue] to [deviceId] to play from [index] at [positionMs]; capped so the message stays small. */
    suspend fun sendQueue(lan: io.max.paperplay.core.sync.LanSync, deviceId: String, queue: List<io.max.paperplay.core.library.Song>, index: Int, positionMs: Long): Boolean {
        if (queue.isEmpty()) return false
        val from = (index - 20).coerceAtLeast(0)
        val part = queue.drop(from).take(400)
        return lan.sendControl(deviceId, "queue:${index - from}:$positionMs", part.map { it.unique_id })
    }

    /** Plays [uids] from [index] here, as a queue sent by another device. Songs this library lacks are left out. */
    private fun playHereQueue(uids: List<String>, index: Int, positionMs: Long) {
        val songs = uids.mapNotNull { library.songByUniqueId(it) }
        if (songs.isEmpty()) return
        val want = uids.getOrNull(index)
        val start = songs.indexOfFirst { it.unique_id == want }.coerceAtLeast(0)
        playback.setQueue(songs, start)
        scope.launch {
            repeat(40) {
                val np = playback.nowPlayingStatic.value
                if (np.song?.unique_id == songs[start].unique_id && np.durationMs > 0) { if (positionMs > 0) playback.seek(positionMs); return@launch }
                kotlinx.coroutines.delay(100)
            }
            if (positionMs > 0) playback.seek(positionMs)
        }
    }

    /** Lets a paired device drive playback here, and tells linked devices what is playing and where the sound goes. */
    private fun bindLan(lan: io.max.paperplay.core.sync.LanSync) {
        scope.launch {
            lan.messages.collect { m ->
                if (m.type != "control") return@collect
                val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(m.body) as kotlinx.serialization.json.JsonObject }.getOrNull()
                val action = runCatching { obj?.get("action")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
                if (action != null && action.startsWith("queue:")) {
                    val parts = action.removePrefix("queue:").split(':')
                    val uids = (obj?.get("uids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                    val idx = parts.getOrNull(0)?.toIntOrNull()
                    val pos = parts.getOrNull(1)?.toLongOrNull()
                    if (idx != null && pos != null) playHereQueue(uids, idx, pos.coerceAtLeast(0))
                    return@collect
                }
                when (val a = io.max.paperplay.core.sync.RemoteAction.parse(action)) {
                    io.max.paperplay.core.sync.RemoteAction.Play -> playback.play()
                    io.max.paperplay.core.sync.RemoteAction.Pause -> playback.pause()
                    io.max.paperplay.core.sync.RemoteAction.Next -> playback.next()
                    io.max.paperplay.core.sync.RemoteAction.Previous -> playback.previous()
                    is io.max.paperplay.core.sync.RemoteAction.Seek -> playback.seek(a.positionMs)
                    is io.max.paperplay.core.sync.RemoteAction.PlaySong -> {
                        playHere(a.uniqueId, a.positionMs)
                    }
                    null -> Unit
                }
            }
        }
        // A playlist edited here reaches the linked devices within seconds, not at the next 10-minute exchange.
        // The exchange that follows one change makes no change itself, so this does not repeat.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch {
            library.playlistSummaries().drop(1).debounce(4_000).collect {
                if (lan.connected.value.any { d -> d.paired }) runCatching { lan.syncNow() }
            }
        }
        scope.launch {
            // The state goes out when the song, the play state or the output changes (checked twice a second) and every 5 s otherwise,
            // also when nothing plays, so the other device never keeps showing a song that stopped.
            var sent = emptyMap<String, List<Any?>>()
            var sentAt = emptyMap<String, Long>()
            var followTicks = 0
            while (true) {
                kotlinx.coroutines.delay(500)
                // No linked device and no other output: nothing to send or follow, so sleep longer.
                if (lan.connected.value.isEmpty() && _activeOutput.value == null) { kotlinx.coroutines.delay(2_000); continue }
                val started = playbackStarted
                val np = if (started) playback.nowPlayingStatic.value else null
                val uid = np?.song?.unique_id
                val playing = np?.isPlaying == true
                // A device that was disconnected (by hand or by distance) is no longer the output, or the controls keep aiming at it.
                _activeOutput.value?.let { id -> if (lan.connected.value.none { it.id == id }) _activeOutput.value = null }
                // Nothing plays here (stopped or paused) and a paired device plays: this screen switches to what that device plays,
                // after a moment so a quick pause is not taken for it.
                val leader = if (playing || _activeOutput.value != null) null
                else lan.connected.value.firstOrNull { it.paired && lan.remoteNow.value[it.id]?.playingNow(System.currentTimeMillis()) == true }
                if (leader != null) { followTicks++; if (followTicks >= 3) { _activeOutput.value = leader.id; followTicks = 0 } } else followTicks = 0
                val out = outputInfo()
                val state = listOf(uid, playing, out?.first)
                val linked = lan.connected.value
                val now = System.currentTimeMillis()
                for (d in linked) {
                    if (sent[d.id] == state && now - (sentAt[d.id] ?: 0L) < 5_000) continue
                    val live = if (started) playback.nowPlaying.value else null
                    runCatching {
                        lan.sendNowPlaying(d.id, uid, live?.positionMs ?: 0, playing, np?.song?.title, np?.song?.artist, live?.durationMs ?: 0, out?.first, out?.second)
                    }
                    sent = sent + (d.id to state); sentAt = sentAt + (d.id to now)
                }
                sent = sent.filterKeys { id -> linked.any { it.id == id } }
            }
        }
    }

    /** Sends audio files to a chat or topic as the bot. */
    val uploader: Uploader by lazy {
        Uploader(scope, { client }, library::pref, {
            library.repo.db.songsQueries.allSongs().executeAsList().mapTo(HashSet()) { it.file_name to it.size }
        }, botPool, library::setPref, { song -> library.upsertBotSong(song) })
    }

    /** The main bot plus extra bots that share uploads. Each extra bot has its own TDLib session. */
    val botPool: io.max.paperplay.core.telegram.BotPool by lazy {
        io.max.paperplay.core.telegram.BotPool(
            scope, platform.secrets, io.max.paperplay.core.dupes.defaultHttpPoster(),
            { platform.secrets.get(Keys.BOT_TOKEN) }, { client },
            open = { id, token ->
                val apiId = platform.settings.get(Keys.API_ID)?.toIntOrNull()
                val apiHash = platform.settings.get(Keys.API_HASH)
                val key = platform.secrets.get(Keys.TD_KEY)
                if (apiId == null || apiHash.isNullOrEmpty() || key == null) null
                else {
                    val rt = runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it }
                    TdClient(
                        rt,
                        TdConfig(
                            apiId, apiHash, token, "${platform.dataDir}/td_extra_$id", "${platform.dataDir}/td_extra_files_$id", key,
                            // Extra bots only upload and read posts by id from the server, so they keep no message database in memory.
                            deviceModel = platform.deviceModel, appVersion = platform.appVersion, useMessageDatabase = false,
                        ),
                    )
                }
            },
        )
    }

    /** The owner's own account (login, chats, copying). The real one replaces the stand-in. */
    val account: io.max.paperplay.core.account.AccountApi by lazy {
        io.max.paperplay.core.account.UserSession(
            scope, { runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it } },
            platform.dataDir, platform.secrets, platform.settings, library::pref, library::setPref, platform.deviceModel, platform.appVersion,
        )
    }

    /** The saved index of the account's chats. The real one replaces the stand-in. */
    val chatIndex: io.max.paperplay.core.account.ChatIndexApi by lazy {
        io.max.paperplay.core.account.ChatIndex(
            account, io.max.paperplay.core.account.IndexStore(driver), library,
            { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) }, clock,
        ).also { it.onRunFinished = { runCatching { indexCloud.save() } } }
    }

    /** Music folders on this device, with optional sending of new songs to Telegram. */
    val localFolders: LocalFolders by lazy {
        LocalFolders(
            library::pref, library::setPref, uploader,
            { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
            {
                val id = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                id?.let { library.chat(it)?.title } ?: "Main chat"
            },
        )
    }

    /** Brings back songs the bot cannot read (posted by other bots) by posting them again where it can. */
    val recovery: io.max.paperplay.core.account.SongRecovery by lazy {
        val session = account as? io.max.paperplay.core.account.UserSession
        io.max.paperplay.core.account.SongRecovery(
            index = chatIndex, account = account, uploader = uploader,
            mainChat = { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
            mainTitle = {
                val id = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                id?.let { library.chat(it)?.title } ?: "Main chat"
            },
            download = { post -> session?.downloadPost(post.chatId, post.messageId) },
            release = { id -> session?.releaseFile(id) },
            deletePost = { chat, id -> session?.deleteMessages(chat, listOf(id)) ?: false },
            scope = scope,
            perMinute = { (library.pref("rate_accountCopy")?.toIntOrNull() ?: 20) },
            onFinished = {
                val main = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                library.chat(main ?: 0L)?.let { scanner.rescanChat(it) }
                enricher.kick()
            },
        )
    }

    /** Refreshes imported Spotify playlists and the index in the background, and fetches songs from the index. */
    /** The owner's own Spotify login, for reading whole playlists. */
    val spotify: io.max.paperplay.core.integrations.SpotifyAccount by lazy {
        io.max.paperplay.core.integrations.SpotifyAccount(platform.secrets, io.max.paperplay.core.integrations.defaultHttp())
    }

    val playlistSync: io.max.paperplay.core.integrations.PlaylistAutoSync by lazy {
        io.max.paperplay.core.integrations.PlaylistAutoSync(
            library, io.max.paperplay.core.integrations.ImportStore(library.repo, driver), io.max.paperplay.core.integrations.defaultHttp(),
            account, chatIndex, scope, official = spotify::fetchPlaylist, canRun = platform::canRunBackground,
        )
    }

    /** What the app is doing now, for the notification: scan, then index, then playlist sync. */
    val activity: StateFlow<String?> by lazy {
        combine(scanner.progress, chatIndex.progress, playlistSync.progress) { scan, idx, sync ->
            scan?.text ?: idx?.let { "Indexing ${it.chatTitle}: ${it.found} songs" } ?: sync
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** Saves the index to Telegram (Saved Messages) and restores it after a login. */
    val indexCloud: io.max.paperplay.core.account.IndexCloud by lazy { io.max.paperplay.core.account.IndexCloud(account, chatIndex, library) }
    val resolver = TrackResolver(
        { client }, TdStreamSource.DEFAULT_WINDOW_SECONDS, { (account as? io.max.paperplay.core.account.UserSession)?.streamClient() }, { id -> botPool.clientOf(id) },
        { listOfNotNull(client) + botPool.readyBots().map { it.second } },
        // The bot that found an account song's post keeps it: later plays, downloads and covers go straight to that bot.
        { song, c, rid ->
            val owner = if (c === client) null else botPool.readyBots().firstOrNull { it.second === c }?.first?.id
            library.claimRemote(song, if (owner != null) botRemote(owner, rid) else rid)
        },
    )
    val covers = CoverService(
        platform.dataDir,
        { if (scanner.progress.value == null) client else null },
        library,
        defaultHttpFetcher(),
        scope,
        clock,
        tdFor = { s -> botOwnerOf(s.remote_id)?.let { botPool.clientOf(it) } },
        accountClient = { (account as? io.max.paperplay.core.account.UserSession)?.streamClient() },
        isStreaming = { it in resolver.activeIds() },
    )

    /** Fills title, artist, album and cover from tags inside downloaded files. */
    val embeddedTags = EmbeddedTagFiller(platform.dataDir, library, clock)

    /** Looks songs up on MusicBrainz, Deezer and iTunes and writes the result back. */
    val enricher = MetadataEnricher(platform.dataDir, library, defaultHttpFetcher(), scope, clock)

    /** Tags songs with moods from lyrics and metadata, one at a time, once lookups are idle. */
    val moodScanner = io.max.paperplay.core.recs.MoodScanner(
        driver, library.repo, io.max.paperplay.core.integrations.defaultHttp(), scope, clock,
        allowed = { io.max.paperplay.core.recs.TasteSettings.parse(library.pref(io.max.paperplay.core.recs.TasteSettings.PREF_KEY)).lookups },
        enrichRunning = { enricher.progress.value != null },
    )

    /** How each song sounds, measured from its file on the phone, for recommendations. */
    val audioFeatures = io.max.paperplay.core.recs.AudioFeatureStore(driver, scope, clock, platform::canRunBackground) { uid ->
        val s = library.songByUniqueId(uid) ?: return@AudioFeatureStore null
        val main = client ?: return@AudioFeatureStore null
        val c = botOwnerOf(s.remote_id)?.let { botPool.clientOf(it) } ?: main
        val file = c.call(request("getRemoteFile") { put("remote_file_id", s.remote_id) })
        val local = file.obj("local")
        local?.str("path")?.takeIf { it.isNotEmpty() && (local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true && io.max.paperplay.core.backup.Fs.exists(it) }
    }

    /** Every listen with how much was heard, for recommendations. */
    val playEvents = io.max.paperplay.core.recs.PlayEventLog(driver).also { log ->
        library.onListen = { s, ms, at ->
            log.record(s.unique_id, at, ms, s.duration_sec * 1000L)
            io.max.paperplay.player.PlayLog.log("recs", "listen \"${s.title}\" heard ${ms / 1000}s of ${s.duration_sec}s")
        }
    }

    /** Deletes duplicate posts and songs: bot, then Bot API, then the owner's account. */
    val deleteQueue: io.max.paperplay.core.dupes.DeleteQueue by lazy {
        val rules = io.max.paperplay.core.dupes.DefaultSongRules
        val caller = object : io.max.paperplay.core.dupes.TdCaller {
            override suspend fun call(request: kotlinx.serialization.json.JsonObject, timeout: kotlin.time.Duration) =
                (client ?: throw io.max.paperplay.core.telegram.TdError(500, "The bot is not connected.")).call(request, timeout)
        }
        val bot = io.max.paperplay.core.dupes.BotApi({ platform.secrets.get(Keys.BOT_TOKEN) }, io.max.paperplay.core.dupes.defaultHttpPoster())
        val acct = (account as? io.max.paperplay.core.account.UserSession)?.let { io.max.paperplay.core.account.UserAccountDeleter(it) }
            ?: object : io.max.paperplay.core.dupes.AccountDeleter {
                override val isLoggedIn = false
                override suspend fun deleteBatch(chatId: Long, ids: List<Long>) = ids
            }
        io.max.paperplay.core.dupes.DeleteQueue(
            scope, caller, bot, acct, io.max.paperplay.core.dupes.LibraryDeleteStore(library.repo, rules),
            rules, { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
            log = { line -> io.max.paperplay.player.PlayLog.log("delete", line); activityLog("Delete", line) },
            extraCallers = { botPool.readyBots().map { (b, c) -> b.id to c.asCaller() } },
            extraBots = {
                botPool.tokens().mapNotNull { t ->
                    t.substringBefore(':').toLongOrNull()?.let { id -> id to (io.max.paperplay.core.dupes.BotApi({ t }, io.max.paperplay.core.dupes.defaultHttpPoster()) as io.max.paperplay.core.dupes.BotDeleter) }
                }
            },
            accountOnly = { chat, message -> library.repo.db.songsQueries.song(chat, message).executeAsOneOrNull()?.remote_id == ACCOUNT_REMOTE },
            ownerOf = { chat, message -> library.repo.db.songsQueries.song(chat, message).executeAsOneOrNull()?.let { botOwnerOf(it.remote_id) } },
        )
    }

    /** Where background work writes lines for the Activity log; the screen layer sets it. */
    @kotlin.concurrent.Volatile
    var activityLog: (source: String, line: String) -> Unit = { _, _ -> }

    /** Moves songs between forum topics by reposting them. */
    val topicMover: io.max.paperplay.core.filing.TopicMover by lazy {
        val botApi = io.max.paperplay.core.dupes.BotApi({ platform.secrets.get(Keys.BOT_TOKEN) }, io.max.paperplay.core.dupes.defaultHttpPoster())
        io.max.paperplay.core.filing.TopicMover(
            scope, { client }, library.repo,
            playingNow = { if (playbackStarted) playback.nowPlaying.value.song?.let { it.chat_id to it.message_id } else null },
            moveProblem = { chat -> botApi.moveProblem(chat) },
        )
    }

    /** Files songs from other chats into the main chat's topics: copy when the main bot can see them, else download and upload. */
    val autoFiler: io.max.paperplay.core.filing.AutoFiler by lazy {
        io.max.paperplay.core.filing.AutoFiler(
            scope, { client }, { id -> botPool.clientOf(id) }, { botPool.readyBots().map { it.second } }, library.repo,
            { line -> activityLog("Filing", line) },
        )
    }

    /** Created on first use, so a screen that never plays does not load the audio stack. */
    private val playbackLazy = lazy { PlaybackController(platform.createPlayer(), resolver, library, scope) }
    val playback: PlaybackController get() = playbackLazy.value

    /** Network play: listening sessions with other people on the same network. Nothing listens until a session starts. */
    val netplay: io.max.paperplay.core.sync.netplay.Netplay by lazy {
        io.max.paperplay.core.sync.netplay.createNetplay(
            io.max.paperplay.core.sync.netplay.NetplayEnv(
                PlaybackNetplayPlayer({ playback }, library, resolver), scope, { lanSync?.deviceName?.value ?: platform.deviceModel },
            ),
        )
    }

    /** True once the player has been created. */
    val playbackStarted: Boolean get() = playbackLazy.isInitialized()

    /** Releases the player if one was created; the desktop engine writes its plugin cache on release. */
    fun releasePlayback() {
        if (!playbackLazy.isInitialized()) return
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(4_000) { playback.release() } } }
    }

    @Volatile private var client: TdClient? = null
    private var runtime: TdRuntime? = null
    private val cachePending = HashMap<String, Pair<Long, Boolean>>()
    private val clockMs = clock
    private var lastEvict = 0L
    private var lastTrim = 0L
    private var cacheFlushJob: Job? = null
    private val _auth = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    /** TDLib's `authorizationState*` name; empty before [connect]. */
    val auth: StateFlow<String> = _auth

    /** True once the bot is logged in. */
    val ready: StateFlow<Boolean> = _auth.map { it == "authorizationStateReady" }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Last login or scan failure as text; null when none. */
    val error: StateFlow<String?> = combine(_error, scanner.error) { a, b -> a ?: b }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Keeps the bot token in the secret store and the API id and hash in settings. */
    fun saveCredentials(apiId: Int, apiHash: String, botToken: String) {
        platform.settings.put(Keys.API_ID, apiId.toString())
        platform.settings.put(Keys.API_HASH, apiHash)
        platform.secrets.put(Keys.BOT_TOKEN, botToken)
    }

    val hasCredentials: Boolean
        get() = platform.settings.get(Keys.API_ID)?.toIntOrNull() != null &&
            !platform.settings.get(Keys.API_HASH).isNullOrEmpty() && !platform.secrets.get(Keys.BOT_TOKEN).isNullOrEmpty()

    /**
     * Logs the bot in and then reads the library chats. Returns at once; watch [auth], [ready] and [error].
     * Does nothing when already connected.
     */
    fun connect() {
        if (client != null) return
        val apiId = platform.settings.get(Keys.API_ID)?.toIntOrNull()
        val apiHash = platform.settings.get(Keys.API_HASH)
        val token = platform.secrets.get(Keys.BOT_TOKEN)
        if (apiId == null || apiHash.isNullOrEmpty() || token.isNullOrEmpty()) {
            _error.value = "Enter the API id, API hash and bot token first."
            return
        }
        _error.value = null
        // Once: songs whose names were split badly ("Song - Lofi - Artist") get their title and artist right, and are looked up again.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (library.pref("split_names_v4") == "done") return@launch
            val fixed = repairSplitNames(library.repo.db.songsQueries.allSongs().executeAsList())
            if (fixed.isNotEmpty()) library.replaceSongs(fixed)
            library.setPref("split_names_v4", "done")
            activityLog("Library", "Fixed the title and artist of ${fixed.size} songs named like \"Song - Version - Artist\"")
        }
        scope.launch {
            try {
                val key = platform.secrets.get(Keys.TD_KEY) ?: (newUuid() + newUuid()).also { platform.secrets.put(Keys.TD_KEY, it) }
                val rt = runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it }
                val c = TdClient(
                    rt,
                    TdConfig(
                        apiId, apiHash, token, "${platform.dataDir}/td", "${platform.dataDir}/td_files", key,
                        deviceModel = platform.deviceModel, appVersion = platform.appVersion,
                        useMessageDatabase = false,
                    ),
                )
                client = c
                scanner.attach(c)
                launch { c.auth.collect { _auth.value = it } }
                launch { c.authError.collect { _error.value = it } }
                launch {
                    c.updates.collect { u ->
                        if (u.type == "updateFile") u.obj("file")?.let { onFileUpdate(c, it) }
                    }
                }
                c.start()
                c.ready()
                playlistSync.start()
                botPool.load()
                
                platform.secrets.get("restoredExtraBots")?.let { raw ->
                    val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }.getOrNull()
                    if (array != null) {
                        botPool.adopt(array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
                    }
                    platform.secrets.remove("restoredExtraBots")
                }

                // Songs read through the account need its client running before they are played.
                launch {
                    if (!account.loggedIn.value) return@launch
                    // Every library chat, not only those with account songs: covers read thumbnails through the account too.
                    val chats = driver.executeQuery(null, "SELECT DISTINCT chat_id FROM songs", { cur ->
                        val ids = mutableListOf<Long>()
                        while (cur.next().value) cur.getLong(0)?.let { ids += it }
                        app.cash.sqldelight.db.QueryResult.Value(ids)
                    }, 0).value
                    for (id in chats) (account as? io.max.paperplay.core.account.UserSession)?.warmUp(id)
                }
                // After the first login on this install, bring back the saved index and the user's song data once.
                launch {
                    account.loggedIn.first { it }
                    delay(5.seconds)
                    runCatching { indexCloud.restoreOnce() }
                }
                // Covers wait a while, so the first seconds after connecting stay light.
                launch {
                    delay(30.seconds)
                    covers.start()
                }
                // Catching up and the one-time check run beside the rest, never in the way of connecting.
                launch {
                    try {
                        // Only posts newer than the last read; a full read of a chat is started by hand from Scan library.
                        scanner.catchUp()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        _error.value = e.message ?: e::class.simpleName
                    }
                    enricher.kick()
                    moodScanner.start()
                    audioFeatures.startBackfill { library.cachedSongs().sortedByDescending { it.plays }.map { it.unique_id } }
                    downloadPins()
                    runCatching { localFolders.syncNow() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TdError) {
                _error.value = e.message
            } catch (e: Throwable) {
                _error.value = e.message ?: e::class.simpleName
            }
        }
    }

    /**
     * Stops background work and closes the library file, so a restore can replace it (Windows will not rename an open
     * file). Use it only before [connect]; once connected, use [shutdown].
     */
    fun close() {
        scope.cancel()
        library.close()
        runCatching { driver.close() }
    }

    /**
     * Stops everything that holds the library file, a socket or TDLib's receive loop, then closes the file, so a restore
     * can replace it while the app runs. Use it instead of [close] once connected. The receive loop is awaited because
     * TDLib aborts when a new runtime receives while the old one still does.
     */
    suspend fun shutdown() {
        try {
            lanSync?.let { lan ->
                val on = library.pref("lan_on") // stop() records "off"; the restore keeps this device's own choice
                lan.stop()
                if (on != null) library.setPref("lan_on", on)
            }
            netplay.endSession()
            netplay.leave()
            netplay.stopDiscovery()
            if (playbackStarted) kotlinx.coroutines.withTimeoutOrNull(4.seconds) { playback.release() }
            disconnect()
            botPool.stopAll()
            (account as? io.max.paperplay.core.account.UserSession)?.closeForBackup()
            runtime?.stop()
        } finally {
            close()
        }
    }

    /** Adds a library chat and reads it. */
    fun addChat(id: Long, title: String) {
        library.addChat(id, title)
        scope.launch {
            library.chat(id)?.let { scanner.scanChat(it) }
            covers.kick()
            enricher.kick()
        }
    }

    /** Asks the bot for one post and says what it got, to find out why a song is missing from the library. */
    suspend fun botCheck(chatId: Long, messageId: Long): String {
        val c = client ?: return "The bot is not connected."
        return try {
            val m = c.call(request("getMessage") { put("chat_id", chatId); put("message_id", messageId) })
            if (songFromMessage(m) != null) "The bot can read this post and sees a song." else "The bot reads this post, but it does not see a song in it."
        } catch (e: TdError) {
            "The bot cannot read this post: ${e.message}"
        }
    }

    /** Deletes the audio TDLib downloaded for streaming. Songs download again when played. False when not connected. */
    suspend fun clearDownloads(): Boolean {
        val c = client ?: return false
        runCatching { account.clearDownloads() }
        // Extra bots keep their own download folders; the eviction below only looks at the main bot's, so clear theirs here.
        for (b in botPool.bots.value) botPool.clientOf(b.id)?.let { bc -> runCatching { bc.call(optimizeRequest(0L), 300.seconds) } }
        c.call(optimizeRequest(0L), 300.seconds)
        driver.execute(null, "UPDATE songs SET cached_bytes = 0 WHERE pinned = 0", 0)
        library.invalidate()
        return true
    }

    /** TDLib's storage optimizer for audio, documents and the half-finished files in `temp`: keeps at most [size] bytes (0 deletes them all). */
    private fun optimizeRequest(size: Long) = request("optimizeStorage") {
        put("size", size)
        put("ttl", -1)
        put("count", -1)
        put("immunity_delay", if (size == 0L) 0 else 600)
        put("file_types", buildJsonArray {
            for (t in listOf("fileTypeAudio", "fileTypeDocument", "fileTypeTemporary")) add(buildJsonObject { put("@type", t) })
        })
        put("chat_ids", buildJsonArray { })
        put("exclude_chat_ids", buildJsonArray { })
        put("return_deleted_file_statistics", false)
        put("chat_limit", 0)
    }

    /** Pins or unpins songs; pinned songs download in full and are never evicted. */
    fun setPinned(uniqueIds: Collection<String>, pinned: Boolean) {
        library.setPinned(uniqueIds, pinned)
        if (pinned) scope.launch { downloadPins() }
    }

    /** Starts a full download for every pinned song that is not complete yet. Safe to call again. */
    suspend fun downloadPins() {
        val main = client ?: return
        for (s in library.incompletePins()) {
            runCatching {
                // A song only an extra bot can read is fetched by that bot.
                val c = botOwnerOf(s.remote_id)?.let { botPool.clientOf(it) } ?: main
                val msg = c.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) })
                val fileId = fileOfMessage(msg)?.long("id")?.toInt()
                if (fileId != null && fileId != 0) {
                    c.call(request("downloadFile") {
                        put("file_id", fileId); put("priority", 1); put("offset", 0); put("limit", 0); put("synchronous", false)
                    })
                }
            }
        }
    }

    private fun onFileUpdate(c: TdClient, file: kotlinx.serialization.json.JsonObject) {
        resolver.onFileUpdate(file)
        val uid = file.obj("remote")?.str("unique_id")?.takeIf { it.isNotEmpty() } ?: return
        val local = file.obj("local") ?: return
        val downloaded = local.long("downloaded_size") ?: return
        val completed = (local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true
        synchronized(cachePending) { cachePending[uid] = downloaded to completed }
        if (completed) {
            local.str("path")?.takeIf { it.isNotEmpty() }?.let { path ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { embeddedTags.apply(uid, path) }
                audioFeatures.onDownloaded(uid, path)
            }
        }
        if (cacheFlushJob?.isActive != true) {
            cacheFlushJob = scope.launch {
                delay(3.seconds)
                flushCache(c)
            }
        }
    }

    private suspend fun flushCache(c: TdClient) {
        val batch = synchronized(cachePending) { HashMap(cachePending).also { cachePending.clear() } }
        if (batch.isEmpty()) return
        for ((k, entry) in batch) {
            runCatching { library.setCachedBytes(k, entry.first, notify = entry.second) }
        }
        // Looking for songs to evict reads the whole cache, so it runs at most once a minute.
        val now = clockMs()
        if (now - lastEvict < 60_000L) return
        lastEvict = now
        val limit = library.cacheLimit()
        // The account's own downloads and the extra bots' are outside the song cache above, so each gets the same limit through TDLib's optimizer (every 10 minutes).
        if (now - lastTrim > 600_000L) {
            lastTrim = now
            scope.launch {
                runCatching { account.trimDownloads(limit) }
                for (b in botPool.bots.value) botPool.clientOf(b.id)?.let { bc -> runCatching { bc.call(optimizeRequest(limit), 300.seconds) } }
            }
        }
        val rows = library.cachedSongs().filter { !it.pinned }.sortedBy { it.last_played_at ?: 0L }
        var total = rows.sumOf { it.cached_bytes }
        val protected = resolver.activeIds() + batch.keys
        for (s in rows) {
            if (total <= limit) break
            if (s.unique_id in protected) continue
            val deleted = runCatching {
                val msg = c.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) })
                val fileId = fileOfMessage(msg)?.long("id")?.toInt()
                if (fileId != null && fileId != 0) {
                    c.call(request("deleteFile") { put("file_id", fileId) })
                    true
                } else false
            }.getOrDefault(false)
            if (deleted) {
                library.setCachedBytes(s.unique_id, 0L, notify = true)
                total -= s.cached_bytes
            }
        }
    }

    /** Disconnects and forgets the main bot's credentials, so setup shows again. The account, extra bots, library and prefs stay. */
    suspend fun logOutBot() {
        disconnect()
        platform.settings.put(Keys.API_ID, "")
        platform.settings.put(Keys.API_HASH, "")
        platform.secrets.remove(Keys.BOT_TOKEN)
    }

    /** Logs the account out, saving its chat index to the cloud first. Nothing happens when no account is logged in. */
    suspend fun logOutAccount() {
        if (!account.loggedIn.value) return
        runCatching { indexCloud.save() }
        account.logOut()
    }

    /** Logs the account out if there is one, then forgets the main bot. */
    suspend fun logOutAll() {
        logOutAccount()
        logOutBot()
    }

    /**
     * A clean start: logs the account out (so Telegram ends that session), shuts everything down and deletes all data
     * this device holds. The services are dead afterwards; the caller builds new ones.
     */
    suspend fun eraseEverything() {
        if (account.loggedIn.value) account.logOut()
        shutdown()
        platform.deleteAllData()
    }

    /** Disconnects; the session stays on disk, so [connect] logs in again without a code. */
    suspend fun disconnect() {
        val c = client ?: return
        client = null
        resolver.retain(emptyList())
        c.shutdown()
        _auth.value = ""
    }
}
