package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.MissingTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifySyncTest {
    private var now = 1_000_000L

    private fun library(): Pair<LibraryDatabase, LibraryRepository> {
        val db = LibraryDatabase(libraryDriver())
        return db to LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
    }

    @Test fun anImportedPlaylistKeepsItsLinkAndWaitingListOnTheOtherDevice() {
        val (adb, a) = library()
        val (bdb, b) = library()
        adb.playlistsQueries.insertPlaylist("Shazam", "u-1", 10, "https://open.spotify.com/playlist/abc")
        val id = adb.playlistsQueries.lastInsertRowId().executeAsOne()
        adb.playlistsQueries.insertMissingTrack(MissingTrack(id, "k1", "Qaatil", "Hasan", 200, 0, null))
        adb.playlistsQueries.insertMissingTrack(MissingTrack(id, "k2", "Tokyo", "Leat'eq", 180, 1, null))

        SnapshotStore(b).apply(SnapshotStore(a).build())

        val p = bdb.lanQueries.allPlaylistsWithUuid().executeAsList().single()
        assertEquals("https://open.spotify.com/playlist/abc", p.source_url)
        assertEquals(listOf("k1", "k2"), bdb.playlistsQueries.missingTracks(p.id).executeAsList().map { it.track_key })
    }

    @Test fun aDeviceThatHasItsOwnLinkKeepsIt() {
        val (adb, a) = library()
        val (bdb, b) = library()
        adb.playlistsQueries.insertPlaylist("Shazam", "u-1", 10, "https://open.spotify.com/playlist/abc")
        bdb.playlistsQueries.insertPlaylist("Shazam", "u-1", 5, "https://open.spotify.com/playlist/mine")
        SnapshotStore(b).apply(SnapshotStore(a).build())
        assertEquals("https://open.spotify.com/playlist/mine", bdb.lanQueries.allPlaylistsWithUuid().executeAsList().single().source_url)
    }
}
