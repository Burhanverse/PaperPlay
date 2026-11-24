package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BitPerfectRebuildAndAttachTest {

    private class FakePlayer(
        var items: MutableList<String> = mutableListOf("track-0", "track-1", "track-2"),
        override var currentIndex: Int = 1,
        override var positionMs: Long = 42_500L,
        override var playbackState: Int = PlaybackStateConst.STATE_READY,
        private var pwr: Boolean = false,
        override var isPlaying: Boolean = false,
        override var playbackSuppressionReason: Int = PlaybackStateConst.SUPPRESSION_NONE,
        override var repeatMode: Int = 2,
        override var shuffleModeEnabled: Boolean = true,
        override var playbackParameters: String = "1.0x",
        val events: MutableList<String> = mutableListOf(),
        val name: String = "old",
    ) : RebuildPlayerAdapter<String, String> {
        override val playWhenReady: Boolean get() = pwr
        var prepared = false
        var released = false
        var listenersDetached = false
        var mediaItemsSet = false

        override val itemCount: Int get() = items.size
        override fun getItemAt(index: Int): String = items[index]

        override fun setMediaItems(items: List<String>, startIndex: Int, startPositionMs: Long) {
            mediaItemsSet = true
            this.items = items.toMutableList()
            this.currentIndex = startIndex
            this.positionMs = startPositionMs
            events.add("$name:setMediaItems(${items.size},$startIndex,$startPositionMs)")
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            this.pwr = playWhenReady
            events.add("$name:setPlayWhenReady($playWhenReady)")
        }

        override fun prepare() {
            prepared = true
            events.add("$name:prepare")
        }

        override fun detachListeners() {
            listenersDetached = true
            events.add("$name:detachListeners")
        }

        override fun release() {
            released = true
            events.add("$name:release")
        }

        fun playCommand() {
            check(!released) { "Cannot play on released player" }
            pwr = true
            isPlaying = true
            events.add("$name:cmd:play")
        }

        fun pauseCommand() {
            check(!released) { "Cannot pause on released player" }
            pwr = false
            isPlaying = false
            events.add("$name:cmd:pause")
        }

        fun nextCommand() {
            check(!released) { "Cannot next on released player" }
            if (currentIndex + 1 < items.size) {
                currentIndex++
                positionMs = 0L
            }
            events.add("$name:cmd:next->$currentIndex")
        }

        fun prevCommand() {
            check(!released) { "Cannot prev on released player" }
            if (currentIndex > 0) {
                currentIndex--
                positionMs = 0L
            }
            events.add("$name:cmd:prev->$currentIndex")
        }
    }

    private class FakeSession {
        var player: FakePlayer? = null
        val events = mutableListOf<String>()

        fun adapter(): SessionSwapAdapter<FakePlayer> = object : SessionSwapAdapter<FakePlayer> {
            override fun swapSessionPlayer(newPlayer: FakePlayer) {
                player = newPlayer
                events.add("session:swap(${newPlayer.name})")
            }
        }
    }

    @Test
    fun `attach while paused stays paused and does not rebuild when UnifiedUsbAudioSink is already installed`() {
        val tracker = UserPlayIntentTracker()
        tracker.onExplicitUserCommand(false)
        tracker.onPlayWhenReadyChanged(false, PlaybackStateConst.REASON_USER_REQUEST)

        val shouldRebuild = BitPerfectRebuildPolicy.shouldRebuildPlayer(
            SinkRebuildNeedInput(
                isExplicitModeOrChainChange = false,
                usbExclusiveMode = true,
                useUnifiedBitPerfect = true,
                devApi34MixerEnabled = false,
                hasUnifiedUsbSinkInstance = true,
                api34ActiveOnCurrentPlayer = false,
            ),
        )
        assertFalse(shouldRebuild, "DAC attach must not recreate ExoPlayer when UnifiedUsbAudioSink is already installed")

        val shouldSeek = BitPerfectRebuildPolicy.shouldSeekOnUsbAttach(
            playbackState = PlaybackStateConst.STATE_READY,
            mediaItemCount = 3,
            playWhenReady = false,
            isPlaying = false,
            playbackSuppressionReason = PlaybackStateConst.SUPPRESSION_NONE,
            userPlayIntent = tracker.userWantsPlayback,
        )
        assertFalse(shouldSeek, "DAC attach while paused must not seek or restart playback")
    }

    @Test
    fun `attach while ended or focus-suppressed with stale playWhenReady true never starts playback or seeks`() {
        val tracker = UserPlayIntentTracker()
        tracker.onExplicitUserCommand(true)
        // Song ended: ExoPlayer leaves playWhenReady=true in STATE_ENDED!
        tracker.onPlaybackStateChanged(PlaybackStateConst.STATE_ENDED, 2)
        assertFalse(tracker.userWantsPlayback)

        assertFalse(
            BitPerfectRebuildPolicy.shouldSeekOnUsbAttach(
                playbackState = PlaybackStateConst.STATE_ENDED,
                mediaItemCount = 2,
                playWhenReady = true,
                isPlaying = false,
                playbackSuppressionReason = PlaybackStateConst.SUPPRESSION_NONE,
                userPlayIntent = tracker.userWantsPlayback,
            ),
        )

        // Focus-suppressed: transient audio focus suppression while playWhenReady=true
        tracker.onExplicitUserCommand(true)
        assertFalse(
            BitPerfectRebuildPolicy.shouldSeekOnUsbAttach(
                playbackState = PlaybackStateConst.STATE_READY,
                mediaItemCount = 2,
                playWhenReady = true,
                isPlaying = false,
                playbackSuppressionReason = 1, // SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                userPlayIntent = tracker.userWantsPlayback,
            ),
        )
    }

    @Test
    fun `attach while playing keeps playing and allows in-place transition without rebuild`() {
        val tracker = UserPlayIntentTracker()
        tracker.onExplicitUserCommand(true)
        tracker.onPlayWhenReadyChanged(true, PlaybackStateConst.REASON_USER_REQUEST)

        assertFalse(
            BitPerfectRebuildPolicy.shouldRebuildPlayer(
                SinkRebuildNeedInput(
                    isExplicitModeOrChainChange = false,
                    usbExclusiveMode = true,
                    useUnifiedBitPerfect = true,
                    devApi34MixerEnabled = false,
                    hasUnifiedUsbSinkInstance = true,
                    api34ActiveOnCurrentPlayer = false,
                ),
            ),
        )
        assertTrue(
            BitPerfectRebuildPolicy.shouldSeekOnUsbAttach(
                playbackState = PlaybackStateConst.STATE_READY,
                mediaItemCount = 3,
                playWhenReady = true,
                isPlaying = true,
                playbackSuppressionReason = PlaybackStateConst.SUPPRESSION_NONE,
                userPlayIntent = tracker.userWantsPlayback,
            ),
        )
    }

    @Test
    fun `rebuild preserves queue, index, position, repeat, shuffle, speed, and paused state while swapping session before releasing old`() {
        val events = mutableListOf<String>()
        val old = FakePlayer(
            items = mutableListOf("a", "b", "c"),
            currentIndex = 1,
            positionMs = 31_415L,
            playbackState = PlaybackStateConst.STATE_READY,
            pwr = true, // Stale playWhenReady=true (e.g. from earlier focus or bug) while userIntent=false!
            isPlaying = false,
            repeatMode = 1,
            shuffleModeEnabled = true,
            playbackParameters = "1.0x",
            events = events,
            name = "old",
        )
        val session = FakeSession().apply { player = old }

        val (next, plan) = PlaybackRebuildTransaction.execute(
            old = old,
            userPlayIntent = false,
            createNext = { FakePlayer(items = mutableListOf(), currentIndex = 0, positionMs = 0L, playbackState = PlaybackStateConst.STATE_IDLE, events = events, name = "next") },
            wrapForwarding = { it },
            session = object : SessionSwapAdapter<FakePlayer> {
                override fun swapSessionPlayer(newPlayer: FakePlayer) {
                    session.player = newPlayer
                    events.add("session:swap(${newPlayer.name})")
                }
            },
        )

        assertFalse(plan.targetPlayWhenReady, "Paused/non-intent rebuild must stay paused even if old.playWhenReady was stale true")
        assertTrue(plan.shouldSetItems)
        assertTrue(plan.shouldPrepareQueue)
        assertEquals(listOf("a", "b", "c"), next.items)
        assertEquals(1, next.currentIndex)
        assertEquals(31_415L, next.positionMs)
        assertEquals(1, next.repeatMode)
        assertTrue(next.shuffleModeEnabled)
        assertEquals("1.0x", next.playbackParameters)
        assertFalse(next.playWhenReady)
        assertTrue(next.prepared)
        assertTrue(old.released)
        assertSame(next, session.player)
        assertNotSame(old, session.player)

        // Verify session swap happened BEFORE old.release() and prepare happened AFTER old.release()
        val swapIdx = events.indexOf("session:swap(next)")
        val releaseIdx = events.indexOf("old:release")
        val prepareIdx = events.indexOf("next:prepare")
        assertTrue(swapIdx in 0 until releaseIdx, "MediaSession must swap to next before old.release(): $events")
        assertTrue(releaseIdx < prepareIdx, "old.release() must finish before next.prepare(): $events")

        // Verify session accepts play/pause/next/previous commands immediately after rebuild
        session.player!!.playCommand()
        assertTrue(next.playWhenReady)
        assertTrue(next.isPlaying)
        session.player!!.nextCommand()
        assertEquals(2, next.currentIndex)
        session.player!!.prevCommand()
        assertEquals(1, next.currentIndex)
        session.player!!.pauseCommand()
        assertFalse(next.playWhenReady)
        assertFalse(next.isPlaying)
    }

    @Test
    fun `rebuild on empty or ended queue never calls setMediaItems prepare or play`() {
        val emptyEvents = mutableListOf<String>()
        val emptyOld = FakePlayer(
            items = mutableListOf(),
            currentIndex = 0,
            positionMs = 0L,
            playbackState = PlaybackStateConst.STATE_IDLE,
            pwr = true,
            events = emptyEvents,
            name = "oldEmpty",
        )
        val emptySession = FakeSession().apply { player = emptyOld }
        val (nextEmpty, emptyPlan) = PlaybackRebuildTransaction.execute(
            old = emptyOld,
            userPlayIntent = true,
            createNext = { FakePlayer(items = mutableListOf(), events = emptyEvents, name = "nextEmpty") },
            wrapForwarding = { it },
            session = emptySession.adapter(),
        )
        assertFalse(emptyPlan.shouldSetItems)
        assertFalse(emptyPlan.shouldPrepareQueue)
        assertFalse(emptyPlan.targetPlayWhenReady)
        assertFalse(nextEmpty.mediaItemsSet)
        assertFalse(nextEmpty.prepared)
        assertFalse(nextEmpty.playWhenReady)

        val endedEvents = mutableListOf<String>()
        val endedOld = FakePlayer(
            items = mutableListOf("only-song"),
            currentIndex = 0,
            positionMs = 180_000L,
            playbackState = PlaybackStateConst.STATE_ENDED,
            pwr = true,
            events = endedEvents,
            name = "oldEnded",
        )
        val endedSession = FakeSession().apply { player = endedOld }
        val (nextEnded, endedPlan) = PlaybackRebuildTransaction.execute(
            old = endedOld,
            userPlayIntent = true,
            createNext = { FakePlayer(items = mutableListOf(), events = endedEvents, name = "nextEnded") },
            wrapForwarding = { it },
            session = endedSession.adapter(),
        )
        assertFalse(endedPlan.shouldSetItems)
        assertFalse(endedPlan.shouldPrepareQueue)
        assertFalse(endedPlan.targetPlayWhenReady)
        assertFalse(nextEnded.mediaItemsSet)
        assertFalse(nextEnded.prepared)
        assertFalse(nextEnded.playWhenReady)
    }

    @Test
    fun `UserPlayIntentTracker distinguishes explicit user and remote intent from focus loss noisy end and unknown reasons`() {
        val tracker = UserPlayIntentTracker()
        assertFalse(tracker.userWantsPlayback)

        // Focus regain / unknown reason must NOT turn false into true
        tracker.onPlayWhenReadyChanged(true, PlaybackStateConst.REASON_AUDIO_FOCUS_LOSS)
        assertFalse(tracker.userWantsPlayback)
        tracker.onPlayWhenReadyChanged(true, 99)
        assertFalse(tracker.userWantsPlayback)

        // Explicit user play or remote button sets intent true
        tracker.onPlayWhenReadyChanged(true, PlaybackStateConst.REASON_USER_REQUEST)
        assertTrue(tracker.userWantsPlayback)

        // Becoming noisy or pause clears intent
        tracker.onPlayWhenReadyChanged(false, PlaybackStateConst.REASON_AUDIO_BECOMING_NOISY)
        assertFalse(tracker.userWantsPlayback)

        tracker.onPlayWhenReadyChanged(true, PlaybackStateConst.REASON_REMOTE)
        assertTrue(tracker.userWantsPlayback)

        // Ending playback clears intent
        tracker.onPlaybackStateChanged(PlaybackStateConst.STATE_ENDED, 1)
        assertFalse(tracker.userWantsPlayback)
    }
}
