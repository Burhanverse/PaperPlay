package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.PlayerState
import io.max.paperplay.player.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayerEngine : PlayerEngine {
    val mutableState = MutableStateFlow(PlayerState.Idle)
    val mutablePositionMs = MutableStateFlow(0L)
    val mutableDurationMs = MutableStateFlow(0L)
    val mutableBufferedMs = MutableStateFlow(0L)
    val mutableCurrentIndex = MutableStateFlow(-1)

    override val state: StateFlow<PlayerState> = mutableState
    override val positionMs: StateFlow<Long> = mutablePositionMs
    override val durationMs: StateFlow<Long> = mutableDurationMs
    override val bufferedMs: StateFlow<Long> = mutableBufferedMs
    override val currentIndex: StateFlow<Int> = mutableCurrentIndex

    var lastQueue: List<QueueItem> = emptyList()
    var lastStartIndex: Int = -1
    var lastStartPositionMs: Long = 0L
    var playCalls = 0
    var pauseCalls = 0
    var lastEqualizer: List<Float>? = emptyList()
    var released = false

    override fun setQueue(items: List<QueueItem>, startIndex: Int) {
        setQueue(items, startIndex, 0L)
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int, startPositionMs: Long) {
        lastQueue = items
        lastStartIndex = startIndex
        lastStartPositionMs = startPositionMs
        mutableCurrentIndex.value = if (items.isEmpty()) -1 else startIndex
        mutablePositionMs.value = if (items.isEmpty()) 0L else startPositionMs
        mutableState.value = if (items.isEmpty()) PlayerState.Idle else PlayerState.Playing
    }

    override fun play() {
        playCalls++
        if (lastQueue.isNotEmpty()) mutableState.value = PlayerState.Playing
    }

    override fun pause() {
        pauseCalls++
        mutableState.value = PlayerState.Paused
    }

    override fun next() {
        if (mutableCurrentIndex.value + 1 < lastQueue.size) mutableCurrentIndex.value += 1
    }

    override fun previous() {
        if (mutableCurrentIndex.value > 0) mutableCurrentIndex.value -= 1
    }

    override fun seek(positionMs: Long) {
        mutablePositionMs.value = positionMs
    }

    val volumes = java.util.concurrent.CopyOnWriteArrayList<Float>()
    override fun setVolume(volume: Float) { volumes.add(volume) }

    override fun setEqualizer(gainsDb: List<Float>?) {
        lastEqualizer = gainsDb
    }

    override fun release() {
        released = true
    }
}

class PlaybackControllerTest {
    @Test
    fun rewindGoesBackToTheSongPlayedBeforeEvenInAShuffledQueue() = runBlocking {
        var clock = 10_000L
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..6L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope, clock = { clock })
        try {
            controller.setQueue(songs, startIndex = 0)
            await { engine.lastQueue.size == 6 && engine.mutableCurrentIndex.value == 0 }
            controller.playIndex(4)
            await { engine.mutableCurrentIndex.value == 4 && controller.nowPlayingStatic.value.song?.unique_id == "u5" }
            controller.playIndex(2)
            await { engine.mutableCurrentIndex.value == 2 && controller.nowPlayingStatic.value.song?.unique_id == "u3" }

            // Rewind twice: first restarts u3, the second goes to u5, the song played before it (not to u2 next to it in the queue).
            controller.previous()
            delay(150)
            clock += 1_000
            controller.previous()
            await { controller.nowPlayingStatic.value.song?.unique_id == "u5" }
            // And once more back: u1.
            clock += 10_000
            controller.previous()
            delay(150)
            clock += 1_000
            controller.previous()
            await { controller.nowPlayingStatic.value.song?.unique_id == "u1" }
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun aNewQueueAtTheSameIndexIsANewSong() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..4L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        val heard = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        controller.onListened = { s, ms -> heard += s.unique_id to ms }
        try {
            controller.setQueue(songs.take(2), 0)
            await { engine.lastQueue.size == 2 && controller.nowPlayingStatic.value.song?.unique_id == "u1" }
            delay(150)
            for (t in 1L..6L) { engine.mutablePositionMs.value = t * 500; delay(20) }
            // Another list, also from its first song.
            controller.setQueue(songs.drop(2), 0)
            await { engine.lastQueue.firstOrNull()?.uri == "tg-u3" }
            await { heard.isNotEmpty() }
            assertEquals("u1", heard.single().first)
            delay(150)
            for (t in 1L..6L) { engine.mutablePositionMs.value = t * 500; delay(20) }
            controller.setQueue(songs.take(1), 0)
            await { heard.size == 2 }
            assertEquals("u3", heard[1].first)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun aSongWhosePostIsDeletedLeavesTheQueueAndTheNextOnePlays() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..4L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        controller.isDeleted = { it.unique_id == "u2" }
        try {
            controller.setQueue(songs, startIndex = 1)
            await { engine.mutableCurrentIndex.value == 1 && controller.nowPlayingStatic.value.song?.unique_id == "u2" }
            engine.mutableState.value = PlayerState.Error
            await { engine.lastQueue.map { it.uri } == listOf("tg-u1", "tg-u3", "tg-u4") && controller.nowPlayingStatic.value.song?.unique_id == "u3" }
            // Paused by hand: the deleted song leaves, the next one does not start.
            controller.isDeleted = { it.unique_id == "u3" }
            controller.pause()
            delay(400)
            val plays = engine.playCalls
            engine.mutableState.value = PlayerState.Error
            await { engine.lastQueue.map { it.uri } == listOf("tg-u1", "tg-u4") }
            delay(150)
            assertEquals(plays, engine.playCalls)
            controller.isDeleted = { it.unique_id == "u2" }
            // A failure for any other reason leaves the queue alone.
            delay(150)
            engine.mutableState.value = PlayerState.Error
            delay(300)
            assertEquals(2, engine.lastQueue.size)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun pickingFromTheQueueKeepsItsOrderEvenWithShuffleOn() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..8L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        try {
            controller.setShuffle(true)
            await { controller.nowPlayingStatic.value.shuffle }
            controller.setQueue(songs, startIndex = 2)
            await { engine.lastQueue.size == 8 && engine.mutableCurrentIndex.value == 0 }
            val order = engine.lastQueue.map { it.uri }

            controller.playIndex(5)
            await { engine.mutableCurrentIndex.value == 5 }
            // The same queue, in the same order, with the picked song playing.
            assertEquals(order, engine.lastQueue.map { it.uri })

            // Turning shuffle off still gives the list as it was made, not the shuffled one.
            controller.setShuffle(false)
            await { engine.lastQueue.map { it.uri } == songs.map { "tg-${it.unique_id}" } }
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun pauseAndPlayFadeButAFreshStartDoesNot() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..2L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        try {
            controller.setVolume(0.8f)
            controller.setQueue(songs, startIndex = 0)
            await { engine.lastQueue.size == 2 && engine.mutableState.value == PlayerState.Playing }
            // A song starting fresh is not faded: nothing but the set volume went to the engine.
            assertTrue(engine.volumes.all { it == 0.8f })

            engine.volumes.clear()
            controller.pause()
            await { engine.mutableState.value == PlayerState.Paused }
            // Faded down to silence before it paused, then the volume is back.
            assertTrue(engine.volumes.size >= 5)
            assertTrue(engine.volumes.any { it < 0.1f })
            await { engine.volumes.lastOrNull() == 0.8f }

            engine.volumes.clear()
            controller.play()
            await { engine.volumes.lastOrNull() == 0.8f && engine.volumes.size >= 5 }
            assertEquals(0f, engine.volumes.first())
            assertTrue(engine.volumes.zipWithNext().all { (a, b) -> b >= a })
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun rewindRestartsTheSongAndAGoesBackOnlyOnASecondQuickPress() = runBlocking {
        var clock = 10_000L
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val songs = (1L..3L).map { song(it) }
        songs.forEach { db.songsQueries.insertSong(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope, clock = { clock })
        try {
            controller.setQueue(songs, startIndex = 1)
            await { engine.lastQueue.size == 3 && engine.mutableCurrentIndex.value == 1 }

            // One press: the same song again from the start.
            engine.mutablePositionMs.value = 90_000L
            controller.previous()
            await { engine.mutablePositionMs.value == 0L }
            assertEquals(1, engine.mutableCurrentIndex.value)

            // Much later, one press again: still the same song.
            clock += 10_000
            engine.mutablePositionMs.value = 30_000L
            controller.previous()
            await { engine.mutablePositionMs.value == 0L }
            assertEquals(1, engine.mutableCurrentIndex.value)

            // A second press within the window: the song before it.
            clock += 1_000
            controller.previous()
            await { engine.mutableCurrentIndex.value == 0 }

            // First song of the list: every press restarts it, never goes anywhere.
            engine.mutablePositionMs.value = 50_000L
            clock += 20_000
            controller.previous()
            await { engine.mutablePositionMs.value == 0L }
            engine.mutablePositionMs.value = 5_000L
            clock += 500
            controller.previous()
            await { engine.mutablePositionMs.value == 0L }
            assertEquals(0, engine.mutableCurrentIndex.value)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    private fun song(id: Long) = Song(
        1L, id, "u$id", "r$id", "Title $id", "Artist", "Album", 200, 10_000, "audio/flac", "t$id.flac", id,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private suspend fun await(cond: () -> Boolean) {
        withTimeout(5_000) {
            while (!cond()) delay(10)
        }
    }

    @Test
    fun nowPlayingPropagatesBufferedMsAndHandlesRepeatOne() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val s1 = song(1)
        val s2 = song(2)
        db.songsQueries.insertSong(s1)
        db.songsQueries.insertSong(s2)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        try {
            controller.setQueue(listOf(s1, s2), startIndex = 0)
            await { engine.lastQueue.size == 2 && controller.nowPlaying.value.song?.unique_id == "u1" }

            engine.mutableDurationMs.value = 200_000L
            engine.mutablePositionMs.value = 45_000L
            engine.mutableBufferedMs.value = 120_000L

            await {
                val np = controller.nowPlaying.value
                np.durationMs == 200_000L && np.positionMs == 45_000L && np.bufferedMs == 120_000L && np.isPlaying
            }

            controller.setRepeat(Repeat.ONE)
            val beforePlays = engine.playCalls
            engine.mutableState.value = PlayerState.Ended
            await { engine.playCalls > beforePlays }

            val gains = listOf(4f, 2f, 0f, -1f, 3f)
            controller.setEqualizer(gains)
            assertEquals(gains, engine.lastEqualizer)
            controller.setEqualizer(null)
            assertNull(engine.lastEqualizer)

            controller.release()
            assertTrue(engine.released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoreSessionAndPlayResumesFromSavedPositionAndClampsNearEnd() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val s1 = song(1)
        val s2 = song(2) // duration_sec = 200 -> 200_000 ms
        db.songsQueries.insertSong(s1)
        db.songsQueries.insertSong(s2)

        repo.setPref("session_queue", "u1,u2")
        repo.setPref("session_index", "1")
        repo.setPref("session_pos", "45000")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val realEngine = FakePlayerEngine()
        val lazyEngine = io.max.paperplay.player.LazyPlayerEngine { realEngine }
        val controller = PlaybackController(lazyEngine, TrackResolver { null }, repo, scope)
        try {
            controller.restoreSession()
            await {
                val np = controller.nowPlaying.value
                np.song?.unique_id == "u2" && np.positionMs == 45_000L && !np.isPlaying
            }
            // Real engine is not created until Play is pressed.
            assertTrue(!lazyEngine.isCreated)

            controller.play()
            await {
                val np = controller.nowPlaying.value
                lazyEngine.isCreated && np.isPlaying && np.positionMs == 45_000L && realEngine.lastStartPositionMs == 45_000L
            }
            assertEquals(1, realEngine.lastStartIndex)
            assertEquals(45_000L, realEngine.lastStartPositionMs)
            assertEquals(45_000L, realEngine.mutablePositionMs.value)

            // Verify near-end position (>= durationMs - 2000) is clamped to 0 on saveSession().
            realEngine.mutableDurationMs.value = 200_000L
            realEngine.mutablePositionMs.value = 198_500L
            controller.pause()
            await { repo.pref("session_pos") == "0" }
            assertEquals("0", repo.pref("session_pos"))
        } finally {
            controller.release()
            scope.cancel()
        }
    }
}
