package io.max.paperplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LazyPlayerEngineTest {
    private class FakeEngine : PlayerEngine {
        var created = true
        var played = false
        var paused = false
        var seekPos = -1L
        var nextCalled = false
        var prevCalled = false
        var released = false
        var volumeLevel = 1f
        var eq: List<Float>? = null
        var playbackSpeed = 1f
        var isNormalized = false
        var queueItems: List<QueueItem> = emptyList()
        var queueIndex = -1

        private val _state = MutableStateFlow(PlayerState.Idle)
        private val _pos = MutableStateFlow(0L)
        private val _dur = MutableStateFlow(0L)
        private val _buf = MutableStateFlow(0L)
        private val _idx = MutableStateFlow(-1)

        override val state: StateFlow<PlayerState> get() = _state
        override val positionMs: StateFlow<Long> get() = _pos
        override val durationMs: StateFlow<Long> get() = _dur
        override val bufferedMs: StateFlow<Long> get() = _buf
        override val currentIndex: StateFlow<Int> get() = _idx

        override fun setQueue(items: List<QueueItem>, startIndex: Int) {
            queueItems = items
            queueIndex = startIndex
            _idx.value = startIndex
            _state.value = PlayerState.Playing
        }

        override fun play() {
            played = true
            _state.value = PlayerState.Playing
        }

        override fun pause() {
            paused = true
            _state.value = PlayerState.Paused
        }

        override fun next() { nextCalled = true }
        override fun previous() { prevCalled = true }
        override fun seek(positionMs: Long) {
            seekPos = positionMs
            _pos.value = positionMs
        }

        override fun setVolume(volume: Float) { this.volumeLevel = volume }
        override fun setEqualizer(gainsDb: List<Float>?) { this.eq = gainsDb }
        override fun setSpeed(speed: Float) { this.playbackSpeed = speed }
        override fun setNormalize(on: Boolean) { this.isNormalized = on }
        override fun release() { released = true }
    }

    @Test
    fun doesNotCreateRealEngineOnRestoreSession() {
        var factoryCalled = false
        val fake = FakeEngine()
        val lazy = LazyPlayerEngine {
            factoryCalled = true
            fake
        }

        assertFalse(lazy.isCreated)
        assertFalse(factoryCalled)

        val items = listOf(QueueItem("uri1"), QueueItem("uri2"))
        lazy.restoreQueue(items, startIndex = 1, positionMs = 5000L)

        assertFalse(lazy.isCreated)
        assertFalse(factoryCalled)
        assertEquals(PlayerState.Paused, lazy.state.value)
        assertEquals(1, lazy.currentIndex.value)
        assertEquals(5000L, lazy.positionMs.value)

        // Settings applied while uncreated
        lazy.setVolume(0.8f)
        lazy.setNormalize(true)
        lazy.setSpeed(1.2f)
        assertFalse(factoryCalled)

        // Now hit play -> should instantiate and apply pending state
        lazy.play()
        assertTrue(lazy.isCreated)
        assertTrue(factoryCalled)
        assertEquals(items, fake.queueItems)
        assertEquals(1, fake.queueIndex)
        assertEquals(5000L, fake.seekPos)
        assertEquals(0.8f, fake.volumeLevel)
        assertTrue(fake.isNormalized)
        assertEquals(1.2f, fake.playbackSpeed)
        assertTrue(fake.played)
    }

    @Test
    fun releaseWithoutPlayingDoesNotCreateEngine() {
        var factoryCalled = false
        val lazy = LazyPlayerEngine {
            factoryCalled = true
            FakeEngine()
        }

        lazy.release()
        assertFalse(factoryCalled)
        assertFalse(lazy.isCreated)
    }

    @Test
    fun seekInstantiatesAndMaintainsPause() {
        val fake = FakeEngine()
        val lazy = LazyPlayerEngine { fake }

        val items = listOf(QueueItem("uri1"))
        lazy.restoreQueue(items, 0, 1000L)
        assertFalse(lazy.isCreated)

        lazy.seek(2500L)
        assertTrue(lazy.isCreated)
        assertTrue(fake.paused)
        assertEquals(2500L, fake.seekPos)
    }
}
