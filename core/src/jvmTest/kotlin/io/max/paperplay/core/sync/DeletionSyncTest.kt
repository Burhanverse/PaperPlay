package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.filing.GONE_KEY
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeletionSyncTest {
    private var now = 1_000_000L

    private fun song(msg: Long, uid: String, date: Long = 100) = Song(
        1, msg, uid, "r$msg", "t$msg", "artist", "", 0, 100, "audio/mpeg", "t$msg.mp3", date,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private fun library(vararg songs: Song): LibraryRepository {
        val db = LibraryDatabase(libraryDriver())
        songs.forEach { db.enrichQueries.replaceSong(it) }
        return LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
    }

    @Test fun aSongDeletedOnOneDeviceLeavesTheOther() {
        val a = library(song(1, "u1"))
        val b = library(song(1, "u1"), song(2, "u2"))
        a.setPref(GONE_KEY, """{"u1":${System.currentTimeMillis()}}""")
        b.let { SnapshotStore(it) }.apply(SnapshotStore(a).build())
        assertNull(b.songByUniqueId("u1"))
        assertNotNull(b.songByUniqueId("u2"))
        // The record travels on, so a third device learns of it from this one.
        assertEquals(true, b.pref(GONE_KEY)?.contains("u1"))
    }

    @Test fun aFilePostedAgainAfterTheDeletionStays() {
        val a = library()
        val b = library(song(1, "u1", date = 4_000_000_000L))
        a.setPref(GONE_KEY, """{"u1":${System.currentTimeMillis()}}""")
        SnapshotStore(b).apply(SnapshotStore(a).build())
        assertNotNull(b.songByUniqueId("u1"))
    }

    @Test fun aDeletionWhosePostIsStillInTheMainChatIsIgnoredAndDropped() {
        // The other device's record says u1 was deleted, but the index still sees its post in the main chat: the deletion is stale.
        val a = library()
        val b = library(song(1, "u1"), song(2, "u2"))
        a.setPref(GONE_KEY, """{"u1":${System.currentTimeMillis()},"u2":${System.currentTimeMillis()}}""")
        SnapshotStore(b) { ids -> ids.filterTo(HashSet()) { it == "u1" } }.apply(SnapshotStore(a).build())
        assertNotNull(b.songByUniqueId("u1"))
        assertNull(b.songByUniqueId("u2"))
        assertEquals(false, b.pref(GONE_KEY)?.contains("u1"))
    }
}
