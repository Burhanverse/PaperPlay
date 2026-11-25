package io.max.paperplay.core.integrations

import io.max.paperplay.core.account.AccountApi
import io.max.paperplay.core.account.ChatIndexApi
import io.max.paperplay.core.account.ChatInfo
import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.IndexProgress
import io.max.paperplay.core.account.IndexedSong
import io.max.paperplay.core.account.LoginStep
import io.max.paperplay.core.account.asSong
import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private class StubAccount(logged: Boolean = true) : AccountApi {
    override val step: StateFlow<LoginStep> = MutableStateFlow(LoginStep.Ready)
    override val loggedIn = MutableStateFlow(logged)
    override suspend fun begin() {}
    override suspend fun beginQr() {}
    override suspend fun phone(number: String) {}
    override suspend fun code(code: String) {}
    override suspend fun password(password: String) {}
    override suspend fun logOut() {}
    override suspend fun groupsAndChannels(): List<ChatInfo> = emptyList()
    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = emptyFlow()
    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) {}
    override suspend fun saveToCloud(tag: String, fileName: String, content: suspend (Appendable) -> Unit) {}
    override suspend fun loadFromCloud(tag: String): String? = null
}

private class StubIndex(val matches: (String) -> List<ChatPost>) : ChatIndexApi {
    val copied = mutableListOf<ChatPost>()
    override val indexedChats: StateFlow<Map<Long, String>> = MutableStateFlow(emptyMap())
    override val progress: StateFlow<IndexProgress?> = MutableStateFlow(null)
    override suspend fun indexChat(chat: ChatInfo, full: Boolean) {}
    override suspend fun refresh() {}
    override fun songs(chatId: Long): Flow<List<IndexedSong>> = emptyFlow()
    override suspend fun matchesFor(title: String, artist: String, durationSec: Int) = matches(title)
    override suspend fun copyToMain(post: ChatPost) { copied += post }
    override fun forget(chatId: Long) {}
    override suspend fun export() = "{}"
    override suspend fun import(json: io.max.paperplay.core.account.JsonStream) {}
}

class PlaylistAutoSyncTest {
    private var now = 1L
    private val driver = libraryDriver()
    private val library = LibraryRepository(LibraryRepo(LibraryDatabase(driver)) { ++now }) { ++now }
    private val store = ImportStore(library.repo, driver) { ++now }

    private fun post(msg: Long, title: String, dur: Int = 200) = ChatPost(-5, msg, "u$msg", title, "A", dur, 10, "audio/mpeg", "$title.mp3", msg)

    private fun playlistWithMissing(vararg titles: String) {
        val id = store.createPlaylist("PL", "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv")
        titles.forEachIndexed { i, t -> store.insertMissing(id, ImportedTrack("k$i", t, "A", 200), i) }
    }

    private fun sync(index: StubIndex, logged: Boolean = true) =
        PlaylistAutoSync(library, store, defaultHttp(), StubAccount(logged), index, CoroutineScope(Dispatchers.Unconfined), pause = {})

    @Test
    fun offWhenTurnedOff() = runBlocking {
        library.setPref(io.max.paperplay.core.account.IndexPrefs.AUTO_COPY, "false")
        playlistWithMissing("One")
        val index = StubIndex { listOf(post(1, "One")) }
        assertEquals(0, sync(index).fetchFromIndex())
        assertEquals(0, index.copied.size)
    }

    @Test
    fun copiesOnlySureMatchesWhenOn() = runBlocking {
        library.setPref("index_auto_copy", "true")
        playlistWithMissing("One", "Two")
        val index = StubIndex { t -> if (t == "One") listOf(post(1, "One")) else listOf(post(2, "Two", dur = 90)) }
        assertEquals(1, sync(index).fetchFromIndex())
        assertEquals(listOf(1L), index.copied.map { it.messageId })
    }

    @Test
    fun samePostIsCopiedOnce() = runBlocking {
        library.setPref("index_auto_copy", "true")
        playlistWithMissing("One", "One")
        val index = StubIndex { listOf(post(1, "One")) }
        assertEquals(1, sync(index).fetchFromIndex())
    }

    @Test
    fun capsOneRun() = runBlocking {
        library.setPref("index_auto_copy", "true")
        playlistWithMissing(*Array(70) { "S$it" })
        val index = StubIndex { t -> listOf(post(t.drop(1).toLong() + 1, t)) }
        assertEquals(50, sync(index).fetchFromIndex())
    }

    /** A song that arrives joins its playlist without Spotify being read again, and is not fetched a second time. */
    @Test
    fun aNewSongIsMatchedWithoutSpotify() = runBlocking {
        library.setPref("index_auto_copy", "true")
        playlistWithMissing("One", "Three (Live)")
        val index = StubIndex { listOf(post(9, it)) }
        val sync = sync(index)
        assertEquals(false, sync.matchLibrary())
        library.repo.db.songsQueries.insertSong(post(1, "One").asSong())
        library.repo.db.songsQueries.insertSong(post(2, "Three").asSong())
        val list = store.livePlaylists().single().id
        assertEquals(0, sync.fetchFromIndex())
        assertEquals(listOf(1L), store.entries(list).map { it.message_id })
        val waiting = store.missing(list)
        // Only a suggestion: the other title is for the owner to confirm.
        assertEquals(2L, reviewCandidates(store, waiting).getValue(waiting.single().track_key).single().message_id)
        assertEquals(false, sync.matchLibrary())
    }

    /** Telegram and file names cut long titles; the start, the artist and the length still tell the song, and it is not fetched again. */
    @Test
    fun aCutTitleIsMatched() = runBlocking {
        library.setPref("index_auto_copy", "true")
        val film = "Annihilate (Spider-Man: Across the Spider-Verse)"
        val live = "Here Without You - Rarities Edition: Live At Cynthia Woods Mitchell Pavilion"
        playlistWithMissing("$film (A & Swae Lee)", live, "Some Quite Long Title (Part One)")
        fun add(id: Long, title: String, dur: Int = 200) = library.repo.db.songsQueries.insertSong(post(id, title, dur).asSong())
        add(1, "$film (A")
        add(2, "Here Without You (Rarities Edition: Live At Cynthia Woods Mitchell...")
        add(3, "Some Quite Long Title (Part", dur = 215)
        val index = StubIndex { if (it.startsWith("Some")) emptyList() else listOf(post(9, it)) }
        assertEquals(0, sync(index).fetchFromIndex())
        val list = store.livePlaylists().single().id
        assertEquals(setOf(1L, 2L), store.entries(list).map { it.message_id }.toSet())
        // Another length: only a suggestion.
        assertEquals("Some Quite Long Title (Part One)", store.missing(list).single().title)
    }

    @Test
    fun notLoggedInDoesNothing() = runBlocking {
        library.setPref("index_auto_copy", "true")
        playlistWithMissing("One")
        assertEquals(0, sync(StubIndex { listOf(post(1, "One")) }, logged = false).fetchFromIndex())
    }
}
