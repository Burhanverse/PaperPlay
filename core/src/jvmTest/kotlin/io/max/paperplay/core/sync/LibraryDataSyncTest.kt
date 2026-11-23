package io.max.paperplay.core.sync

import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.IndexStore
import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDataSyncTest {
    private class Device(val name: String) {
        var now = 1_000L
        val driver = libraryDriver()
        val library = LibraryRepository(LibraryRepo(LibraryDatabase(driver)) { ++now }) { ++now }
        val index = IndexStore(driver)
        var paused = false to 0L
        val sync = LibraryDataSync(driver, library, { index }, { paused }, { p, at -> paused = p to at }, mainChat = { -1L })

        fun song(uid: String, msg: Long) = driver.execute(
            null,
            "INSERT INTO songs (chat_id, message_id, unique_id, remote_id, title, artist, size, mime, file_name, date, cover_path, cached_bytes, plays) " +
                "VALUES (-1, $msg, '$uid', 'r$msg', 'Song $msg', 'Art', 10, 'audio/flac', 'f.flac', 5, '/local/cover.jpg', 99, 2)", 0,
        )

        fun count(sql: String) = driver.executeQuery(null, sql, { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value
    }

    private fun post(chat: Long, msg: Long) = ChatPost(chat, msg, "u$chat:$msg", "T$msg", "A", 100, 1, "audio/flac", "f.flac", msg)

    private suspend fun pull(into: Device, from: Device) = into.sync.pull(from.name) { q -> from.sync.answer(into.name, q) }

    @Test
    fun bothDevicesEndWithTheSameSongsAndIndex() = runBlocking {
        val pc = Device("pc")
        val phone = Device("phone")
        pc.song("a", 1); pc.song("b", 2); phone.song("c", 3)
        // The phone deleted "b" on purpose: it must not come back.
        phone.library.setPref(io.max.paperplay.core.filing.GONE_KEY, """{"b":${Long.MAX_VALUE / 2}}""")
        pc.index.saveChat(-5, "Big"); pc.index.setChatInfo(-5, "bigchan", "", true, true, 1)
        pc.index.upsertPosts((1L..4500L).map { post(-5, it) }, 7)
        phone.index.saveChat(-6, "Small"); phone.index.upsertPosts(listOf(post(-6, 1)), 8)

        assertEquals(1, pull(phone, pc)) // "a" only
        pull(pc, phone)
        assertEquals(listOf("a", "c"), listOf("a", "b", "c").filter { phone.count("SELECT COUNT(*) FROM songs WHERE unique_id = '$it'") == 1L })
        assertEquals(3L, pc.count("SELECT COUNT(*) FROM songs"))
        // A synced row keeps its data but not the other device's cover file or cache.
        assertEquals(0L, phone.count("SELECT COUNT(*) FROM songs WHERE unique_id = 'a' AND (cover_path IS NOT NULL OR cached_bytes <> 0)"))
        assertEquals(2L, phone.count("SELECT plays FROM songs WHERE unique_id = 'a'"))

        assertEquals(4500, phone.index.chat(-5)!!.postCount)
        assertEquals("https://t.me/bigchan", phone.index.chat(-5)!!.publicLink)
        assertEquals(1, pc.index.chat(-6)!!.postCount)
        // Nothing is sent back to where it came from, and a second pull finds nothing new.
        assertEquals(listOf(-6L), phone.index.changedAfter(-1, 0, 0, "pc", 10).map { it.first.chatId })
        pull(phone, pc)
        assertEquals(4500, phone.index.chat(-5)!!.postCount)
    }

    @Test
    fun aDeletionWhosePostIsStillInTheMainChatIsStale() = runBlocking {
        val pc = Device("pc")
        val phone = Device("phone")
        pc.song("alive", 1); pc.song("elsewhere", 2)
        phone.library.setPref(io.max.paperplay.core.filing.GONE_KEY, """{"alive":5,"elsewhere":5}""")
        // The phone's index still sees "alive" posted in the main chat (-1); "elsewhere" only in another chat, which is never deleted from.
        phone.index.saveChat(-1, "Main"); phone.index.upsertPosts(listOf(post(-1, 1).copy(uniqueId = "alive")), 1)
        phone.index.saveChat(-9, "Other"); phone.index.upsertPosts(listOf(post(-9, 4).copy(uniqueId = "elsewhere")), 1)
        assertEquals(1, pull(phone, pc))
        assertEquals(1L, phone.count("SELECT COUNT(*) FROM songs WHERE unique_id = 'alive'"))
        assertEquals(0L, phone.count("SELECT COUNT(*) FROM songs WHERE unique_id = 'elsewhere'"))
        assertEquals("""{"elsewhere":5}""", phone.library.pref(io.max.paperplay.core.filing.GONE_KEY))
    }

    @Test
    fun theLaterPauseDecisionWins() = runBlocking {
        val pc = Device("pc")
        val phone = Device("phone")
        pc.paused = true to 50L
        phone.paused = false to 10L
        pull(phone, pc)
        assertEquals(true to 50L, phone.paused)
        phone.paused = false to 90L
        pull(pc, phone)
        assertEquals(false to 90L, pc.paused)
    }
}
