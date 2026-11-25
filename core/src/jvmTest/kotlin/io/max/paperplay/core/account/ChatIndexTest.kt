package io.max.paperplay.core.account

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeAccount : AccountApi {
    override val step: StateFlow<LoginStep> = MutableStateFlow(LoginStep.Ready)
    override val loggedIn = MutableStateFlow(true)
    val reads = mutableListOf<Pair<Long, Long>>()
    var batches: List<List<ChatPost>> = emptyList()
    var cutAfter = 0L
    val copies = mutableListOf<Triple<Long, Long, Long>>()
    var cloud: String? = null
    var loads = 0
    override suspend fun begin() {}
    override suspend fun beginQr() {}
    override suspend fun phone(number: String) {}
    override suspend fun code(code: String) {}
    override suspend fun password(password: String) {}
    override suspend fun logOut() {}
    override suspend fun groupsAndChannels(): List<ChatInfo> = emptyList()
    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = flow {
        reads += chatId to afterMessageId
        for (b in batches) { emit(b.filter { it.messageId > afterMessageId }); if (b.any { it.messageId == cutAfter }) throw java.io.IOException("cut off") }
    }
    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) { copies += Triple(fromChat, messageId, toChat) }
    override suspend fun saveToCloud(tag: String, fileName: String, content: suspend (Appendable) -> Unit) { cloud = StringBuilder().also { content(it) }.toString() }
    override suspend fun loadFromCloud(tag: String): String? = cloud?.let { t -> loads++; java.io.File.createTempFile("cloud", ".json").also { it.writeText(t); it.deleteOnExit() }.path }
}

class ChatIndexTest {
    private val main = -100L
    private var now = 1_000L
    private val account = FakeAccount()
    private val lib = libraryDriver().let { d -> LibraryRepository(LibraryRepo(LibraryDatabase(d)) { ++now }) { ++now } to d }
    private val library get() = lib.first
    private val index = ChatIndex(account, IndexStore(lib.second), library, { main }, { ++now })

    private fun post(msg: Long, uid: String, title: String, artist: String = "A", dur: Int = 200, size: Long = 100) =
        ChatPost(-1_000_000_000_005, msg, uid, title, artist, dur, size, "audio/mpeg", "$title.mp3", msg)

    @Test
    fun savesBatchesAndLists() = runBlocking {
        account.batches = listOf(listOf(post(30, "u3", "Three"), post(20, "u2", "Two")), listOf(post(10, "u1", "One")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        val songs = index.songs(-1_000_000_000_005).first()
        assertEquals(listOf(30L, 20L, 10L), songs.map { it.post.messageId })
        assertTrue(songs.none { it.inMainChat || it.copied })
        assertEquals(mapOf(-1_000_000_000_005L to "Chat"), index.indexedChats.value)
    }

    @Test
    fun refreshReadsOnlyNewer() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two"), post(10, "u1", "One")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        account.reads.clear()
        account.batches = listOf(listOf(post(40, "u4", "Four"), post(20, "u2", "Two")))
        index.refresh()
        assertEquals(listOf(-1_000_000_000_005L to 20L), account.reads)
        assertEquals(3, index.songs(-1_000_000_000_005).first().size)
    }

    @Test
    fun aCatchUpReadThatIsCutOffLosesNoPostAndRemovesNoSong() = runBlocking {
        account.batches = listOf((1L..20L).map { post(it, "u$it", "S$it") })
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), true)
        // Three new posts, read newest first; the link dies after the first page. The bot already added their songs.
        for (m in 1L..23L) library.upsertBotSong(post(m, "u$m", "S$m").asSong().copy(remote_id = "r$m"))
        account.batches = listOf(listOf(post(23, "u23", "S23")), listOf(post(22, "u22", "S22"), post(21, "u21", "S21")))
        account.cutAfter = 23
        runCatching { index.refresh() }
        assertEquals(0, index.pruneDeadRows())
        account.cutAfter = 0
        index.refresh()
        assertEquals(23, index.songs(-1_000_000_000_005).first().size)
        assertEquals(0, index.pruneDeadRows())
        assertEquals(23, library.songsInChat(-1_000_000_000_005).size)
    }

    @Test
    fun copiedAndInMainAreFlagged() = runBlocking {
        library.upsertBotSong(
            post(1, "u1", "One").asSong().copy(chat_id = main, message_id = 7),
        )
        account.batches = listOf(listOf(post(20, "u2", "Two"), post(10, "u1", "One")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        index.copyToMain(post(20, "u2", "Two"))
        val byId = index.songs(-1_000_000_000_005).first().associateBy { it.post.uniqueId }
        assertTrue(byId.getValue("u1").inMainChat)
        assertTrue(byId.getValue("u2").copied)
        assertEquals(listOf(Triple(-1_000_000_000_005L, 20L, main)), account.copies)
    }

    @Test
    fun aPlaylistWaitsForTheSongNotForEveryoneWithItsTitle() = runBlocking {
        val list = library.createPlaylist("Spotify")
        library.repo.db.playlistsQueries.insertMissingTrack(io.max.paperplay.core.db.Missing_tracks(list, "k", "Insomnia", "Craig David", 200, 0, null))
        account.batches = listOf(listOf(
            post(60, "a", "Insomnia", "Faithless"), post(50, "b", "Insomnia", "Eve"), post(40, "c", "Insomnia", "Craig David", dur = 320),
            post(30, "d", "Insomnia", "Craig David", size = 300), post(20, "e", "Insomnia", "Craig David", dur = 203), post(10, "f", "Insomnia", "Unknown artist"),
        ))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        index.updateFlags()
        // One copy, the best one, of the right artist and length.
        assertEquals(listOf("d"), index.page(-1_000_000_000_005, IndexFilter.Wanted, "", 0, 10).map { it.post.uniqueId })
    }

    @Test
    fun matchesPreferSureThenCloserLength() = runBlocking {
        account.batches = listOf(listOf(post(30, "a", "Song", dur = 300), post(20, "b", "Song", dur = 201), post(10, "c", "Other")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        val m = index.matchesFor("Song", "A", 200)
        assertEquals(listOf("b", "a"), m.map { it.uniqueId })
    }

    @Test
    fun exportImportAndCloudRoundTrip() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        index.copyToMain(post(20, "u2", "Two"))
        val cloud = IndexCloud(account, index, library)
        library.setPref(IndexPrefs.AUTO, "true")
        cloud.save()
        assertNotNull(account.cloud)
        index.forget(-1_000_000_000_005)
        assertTrue(index.indexedChats.value.isEmpty())
        library.setPref(IndexPrefs.AUTO, "false")
        assertTrue(cloud.restore())
        assertEquals("true", library.pref(IndexPrefs.AUTO))
        val songs = index.songs(-1_000_000_000_005).first()
        assertEquals(1, songs.size)
        assertTrue(songs.single().copied)
        assertFalse(index.indexedChats.value.isEmpty())
        // Restored once per install: opening the account page again must not download and read it all again.
        account.loads = 0
        library.setPref("cloud_restored", "0")
        assertTrue(cloud.restoreOnce())
        assertFalse(cloud.restoreOnce())
        assertEquals(1, account.loads)
    }

    @Test
    fun aSavedIndexIsTakenInBeforeANewOneHidesIt() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        IndexCloud(account, index, library).save()
        // Another login on an empty index: the first save must keep what the account had.
        index.forget(-1_000_000_000_005)
        library.setPref("cloud_restored", "0"); library.setPref("index_cloud_at", "0"); library.setPref("index_cloud_sig", "")
        account.batches = listOf(listOf(post(30, "u3", "Three").copy(chatId = -6)))
        IndexCloud(account, index, library).save()
        assertEquals("Chat", index.indexedChats.value[-1_000_000_000_005])
    }

    @Test
    fun jsonIsReadPieceByPieceWithAnyTextInIt() = runBlocking {
        val text = """ { "a" : 1, "b":"x\"},]\\" , "c":[ [1,"t\u00e9 \\\" ] ["], [] ,{"k":[null,true]} ], "d":{}, "e":[] } """
        val seen = ArrayList<String>()
        val json = JsonStream(java.io.StringReader(text))
        json.obj { k -> if (k == "c") json.arr { seen += "c=" + json.value() } else if (k == "d") { json.skip(); seen += "d" } else seen += k + "=" + json.value() }
        val tree = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals(listOf("a=" + tree["a"], "b=" + tree["b"]) + (tree["c"] as kotlinx.serialization.json.JsonArray).map { "c=$it" } + listOf("d", "e=[]"), seen)
        for (bad in listOf("{", "{\"a\":[1,2", "{\"a\" 1}", "{\"a\":\"x", "[1]")) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(bad) { runBlocking { JsonStream(java.io.StringReader(bad)).let { j -> j.obj { j.value() } } } }
        }
    }

    @Test
    fun anIndexZipMovesToAnotherDevice() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two"), post(10, "u1", "One")))
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), false)
        val dir = java.nio.file.Files.createTempDirectory("idx").toFile()
        val zip = "${dir.path}/i.zip"
        io.max.paperplay.core.app.IndexArchive.export(index, zip, "${dir.path}/t")
        val other = ChatIndex(account, IndexStore(libraryDriver()), library, { main }, { ++now })
        val r = io.max.paperplay.core.app.IndexArchive.import(other, io.max.paperplay.core.backup.fileBackupSource(zip), "${dir.path}/t2")
        assertEquals(2, r.posts)
        assertEquals(listOf(20L, 10L), other.page(-1_000_000_000_005, IndexFilter.All, "", 0, 10).map { it.post.messageId })
        assertEquals(2, other.chats.value.single().postCount)
    }

    @Test
    fun songsWhosePostIsGoneFromAFullyReadChatLeave() = runBlocking {
        // The library has 20 songs of chat -5; a full read finds 19 of them (post 7 was deleted in Telegram).
        for (m in 1L..20L) library.upsertBotSong(post(m, "u$m", "S$m").asSong().copy(remote_id = "r$m"))
        account.batches = listOf((1L..20L).filter { it != 7L }.map { post(it, "u$it", "S$it") })
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), true)
        assertEquals(1, index.pruneDeadRows())
        assertEquals(null, library.songByUniqueId("u7"))
        assertEquals(19, library.songsInChat(-1_000_000_000_005).size)
        assertEquals(0, index.pruneDeadRows())
    }

    @Test
    fun removingDeadRowsSignalsOneChangeNotOnePerSong() = runBlocking {
        for (m in 1L..100L) library.upsertBotSong(post(m, "u$m", "S$m").asSong().copy(remote_id = "r$m"))
        account.batches = listOf((11L..100L).map { post(it, "u$it", "S$it") })
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), true)
        kotlinx.coroutines.delay(2_500) // the signals of the writes above
        val before = library.changes.value
        assertEquals(10, index.pruneDeadRows())
        assertTrue(library.changes.value - before <= 1)
        kotlinx.coroutines.delay(2_500)
        assertEquals(1, library.changes.value - before)
        assertEquals(90, library.songsInChat(-1_000_000_000_005).size)
    }

    @Test
    fun whileSongInfoIsLookedUpTheLibrarySignalsRarely() = runBlocking {
        kotlinx.coroutines.delay(2_500) // whatever was signalled before
        library.enrichRunning.value = true
        val before = library.changes.value
        repeat(10) { library.invalidateSoon(); kotlinx.coroutines.delay(500) }
        assertEquals(1, library.changes.value - before)
        // What the owner does still shows at once.
        library.invalidate()
        assertEquals(2, library.changes.value - before)
    }

    @Test
    fun aRepeatOfAFileTheLibraryHasIsNotCountedAsAdded() = runBlocking {
        library.addChat(main, "Main")
        fun at(id: Long, uid: String) = post(id, uid, "S$uid").copy(chatId = main)
        library.upsertBotSong(at(1, "a").asSong().copy(remote_id = "r1"))
        // The chat holds the same file again (post 2) and one song the library lacks (post 3).
        account.batches = listOf(listOf(at(3, "c"), at(2, "a"), at(1, "a")))
        index.indexChat(ChatInfo(main, "Main", true), true)
        val first = index.addPostsNotInLibrary()
        assertEquals(1, first.added)
        assertEquals(1, first.repeats)
        assertEquals(2, library.songsInChat(main).size)
        // The next scan has nothing new to say.
        assertEquals(0, index.addPostsNotInLibrary().added)
    }

    @Test
    fun aReadThatMissesManyPostsRemovesNothing() = runBlocking {
        for (m in 1L..20L) library.upsertBotSong(post(m, "u$m", "S$m").asSong().copy(remote_id = "r$m"))
        account.batches = listOf((11L..20L).map { post(it, "u$it", "S$it") })
        index.indexChat(ChatInfo(-1_000_000_000_005, "Chat", true), true)
        assertEquals(0, index.pruneDeadRows())
        assertEquals(20, library.songsInChat(-1_000_000_000_005).size)
    }
}
