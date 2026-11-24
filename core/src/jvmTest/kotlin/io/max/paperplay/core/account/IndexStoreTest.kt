package io.max.paperplay.core.account

import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.integrations.looseTitleKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IndexStoreTest {
    private val store = IndexStore(libraryDriver())

    private fun post(chat: Long, msg: Long, title: String = "T$msg", artist: String = "A", uid: String = "u$chat:$msg") =
        ChatPost(chat, msg, uid, title, artist, 200, 1000, "audio/flac", "$title.flac", msg)

    @Test
    fun pagesCountsAndSearchRunInTheDatabase() {
        store.saveChat(-5, "Big")
        store.upsertPosts((1L..1000L).map { post(-5, it, title = if (it % 100 == 0L) "Special $it" else "T$it") }, 1)
        assertEquals(1000, store.chat(-5)!!.postCount)
        val first = store.page(-5, IndexFilter.All, "", 0, 200, null)
        assertEquals(200, first.size)
        assertEquals(1000L, first.first().post.messageId) // newest first
        assertEquals(800L, store.page(-5, IndexFilter.All, "", 200, 200, null).first().post.messageId)
        assertEquals(10, store.page(-5, IndexFilter.All, "special", 0, 200, null).size)
        assertEquals(listOf(10, 10, 0, 0, 0), store.counts(-5, "special"))
        // Search is word based: punctuation is not a word, so a wildcard never reaches LIKE and a query of only punctuation filters nothing.
        assertEquals(1000, store.counts(-5, "%")[0])
        assertEquals(0, store.counts(-5, "nothing like this")[0])
    }

    @Test
    fun unchangedPostsAreNotRewritten() {
        store.saveChat(-5, "C")
        assertEquals(2, store.upsertPosts(listOf(post(-5, 1), post(-5, 2)), 1))
        assertEquals(0, store.upsertPosts(listOf(post(-5, 1), post(-5, 2)), 2))
        assertEquals(1, store.upsertPosts(listOf(post(-5, 1, title = "Renamed"), post(-5, 2)), 3))
        assertEquals(3, store.lastUpdate())
    }

    @Test
    fun flagsMarkWhatTheLibraryHasAndWhatPlaylistsWant() {
        store.saveChat(-5, "C")
        store.upsertPosts(listOf(post(-5, 1, uid = "same"), post(-5, 2, "Song", "Artist"), post(-5, 3, "Wanted", "B"), post(-5, 4, "New", "C")), 1)
        store.prepare()
        store.refreshFlags(setOf("same"), setOf(looseTitleKey("Song", "Artist")), setOf(looseTitleKey("Wanted", "B"))) { p -> true to (if (p.messageId == 2L) "FLAC instead of your MP3" else null) }
        assertEquals(listOf(4, 2, 1, 2, 1), store.counts(-5, ""))
        val better = store.page(-5, IndexFilter.Better, "", 0, 10, null).single()
        assertEquals("FLAC instead of your MP3", better.betterWhy)
        assertEquals(3L, store.page(-5, IndexFilter.Wanted, "", 0, 10, null).single().post.messageId)
        store.markCopied(post(-5, 3), 5)
        assertEquals(0, store.counts(-5, "")[4]) // copied leaves "wanted"
    }

    @Test
    fun anExportIsReadBackAndSharingLeavesOutChatsNobodyCanJoin() = runBlocking {
        store.saveChat(-5, "Public"); store.setChatInfo(-5, "pubchan", "", true, true, 1)
        store.saveChat(-6, "Private")
        store.upsertPosts(listOf(post(-5, 1), post(-5, 2), post(-6, 3)), 1)
        val shared = StringBuilder().also { IndexFile.write(store, it, null, forSharing = true, now = 9) }.toString()
        val other = IndexStore(libraryDriver())
        val fresh = ArrayList<Long>()
        val r = IndexFile.read(shared, other, 10, own = false, newChatIds = fresh)
        assertEquals(IndexImport(1, 2, 1), r)
        assertEquals(listOf(-5L), fresh)
        val c = other.chat(-5)!!
        assertEquals("https://t.me/pubchan", c.publicLink)
        assertTrue(!c.joined)
        assertEquals(2, c.postCount)
        // Reading the same file again adds nothing.
        assertEquals(0, IndexFile.read(shared, other, 11, own = false).posts)
        assertFailsWith<IllegalArgumentException> { IndexFile.read("{}", other, 12, own = false) }
        Unit
    }
}
