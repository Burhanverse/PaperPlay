package io.max.paperplay.player.usb

/**
 * Pure, JVM-testable policy for Bit-perfect player rebuilds, user-intent tracking,
 * USB-attach seek gating, and ordered MediaSession player swap transactions.
 */
object PlaybackStateConst {
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    const val SUPPRESSION_NONE = 0

    const val REASON_USER_REQUEST = 1
    const val REASON_AUDIO_FOCUS_LOSS = 2
    const val REASON_AUDIO_BECOMING_NOISY = 3
    const val REASON_REMOTE = 4
    const val REASON_END_OF_MEDIA_ITEM = 5
    const val REASON_SUPPRESSED_TOO_LONG = 6
}

/**
 * Tracks whether the user (or a remote media button) actually wants playback running.
 * Prevents internal focus-loss, end-of-queue, or rebuild side-effects from turning a paused
 * or ended session into an auto-playing one.
 */
class UserPlayIntentTracker {
    @Volatile
    var userWantsPlayback: Boolean = false
        private set

    fun onExplicitUserCommand(play: Boolean) {
        userWantsPlayback = play
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
            userWantsPlayback = false
        } else if (reason == PlaybackStateConst.REASON_USER_REQUEST ||
            reason == PlaybackStateConst.REASON_REMOTE
        ) {
            userWantsPlayback = true
        }
        // Any other reason (e.g. focus regain or internal) never flips false -> true.
    }

    fun onPlaybackStateChanged(playbackState: Int, mediaItemCount: Int) {
        if (playbackState == PlaybackStateConst.STATE_ENDED ||
            (playbackState == PlaybackStateConst.STATE_IDLE && mediaItemCount == 0)
        ) {
            userWantsPlayback = false
        }
    }

    fun reset(value: Boolean = false) {
        userWantsPlayback = value
    }
}

data class SinkRebuildNeedInput(
    val isExplicitModeOrChainChange: Boolean,
    val usbExclusiveMode: Boolean,
    val useUnifiedBitPerfect: Boolean,
    val devApi34MixerEnabled: Boolean,
    val hasUnifiedUsbSinkInstance: Boolean,
    val api34ActiveOnCurrentPlayer: Boolean,
)

data class PlayerRebuildSnapshot(
    val itemCount: Int,
    val currentIndex: Int,
    val positionMs: Long,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val playbackSuppressionReason: Int,
    val userPlayIntent: Boolean,
)

data class RebuildPlan(
    val shouldSetItems: Boolean,
    val shouldPrepareQueue: Boolean,
    val safeIndex: Int,
    val safePositionMs: Long,
    val targetPlayWhenReady: Boolean,
    val reason: String,
)

object BitPerfectRebuildPolicy {
    fun shouldRebuildPlayer(input: SinkRebuildNeedInput): Boolean {
        if (input.isExplicitModeOrChainChange) return true
        if (!input.usbExclusiveMode || !input.useUnifiedBitPerfect) return false
        if (input.devApi34MixerEnabled) {
            return !input.hasUnifiedUsbSinkInstance && !input.api34ActiveOnCurrentPlayer
        }
        return !input.hasUnifiedUsbSinkInstance
    }

    fun planRebuild(snap: PlayerRebuildSnapshot): RebuildPlan {
        if (snap.itemCount <= 0) {
            return RebuildPlan(
                shouldSetItems = false,
                shouldPrepareQueue = false,
                safeIndex = 0,
                safePositionMs = 0L,
                targetPlayWhenReady = false,
                reason = "empty queue",
            )
        }
        if (snap.playbackState == PlaybackStateConst.STATE_ENDED) {
            val safeIdx = if (snap.currentIndex in 0 until snap.itemCount) snap.currentIndex else 0
            return RebuildPlan(
                shouldSetItems = true,
                shouldPrepareQueue = false,
                safeIndex = safeIdx,
                safePositionMs = 0L,
                targetPlayWhenReady = false,
                reason = "ended queue preserved",
            )
        }
        val intendedPlay = snap.userPlayIntent &&
            snap.playWhenReady &&
            snap.playbackSuppressionReason == PlaybackStateConst.SUPPRESSION_NONE &&
            (snap.isPlaying || snap.playbackState == PlaybackStateConst.STATE_BUFFERING)
        val safeIdx = if (snap.currentIndex in 0 until snap.itemCount) snap.currentIndex else 0
        val safePos = snap.positionMs.coerceAtLeast(0L)
        val shouldPrepare = snap.playbackState != PlaybackStateConst.STATE_IDLE
        return RebuildPlan(
            shouldSetItems = true,
            shouldPrepareQueue = shouldPrepare,
            safeIndex = safeIdx,
            safePositionMs = safePos,
            targetPlayWhenReady = intendedPlay,
            reason = if (intendedPlay) "active playback" else "paused/idle preserved",
        )
    }

    fun shouldSeekOnUsbAttach(
        playbackState: Int,
        mediaItemCount: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        playbackSuppressionReason: Int,
        userPlayIntent: Boolean,
    ): Boolean {
        return mediaItemCount > 0 &&
            (playbackState == PlaybackStateConst.STATE_READY ||
                playbackState == PlaybackStateConst.STATE_BUFFERING) &&
            userPlayIntent &&
            playWhenReady &&
            playbackSuppressionReason == PlaybackStateConst.SUPPRESSION_NONE &&
            (isPlaying || playbackState == PlaybackStateConst.STATE_BUFFERING)
    }
}

interface RebuildPlayerAdapter<Item, Params> {
    val itemCount: Int
    val currentIndex: Int
    val positionMs: Long
    val playbackState: Int
    val playWhenReady: Boolean
    val isPlaying: Boolean
    val playbackSuppressionReason: Int
    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    var playbackParameters: Params
    fun getItemAt(index: Int): Item
    fun setMediaItems(items: List<Item>, startIndex: Int, startPositionMs: Long)
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun prepare()
    fun detachListeners()
    fun release()
}

interface SessionSwapAdapter<P> {
    fun swapSessionPlayer(newPlayer: P)
}

/**
 * Executes the ordered player rebuild transaction:
 * 1. Snapshot old player and compute [RebuildPlan] from user intent.
 * 2. Create next player + forwarding wrapper.
 * 3. Copy repeat/shuffle/playbackParameters and (if non-empty & non-ended) setMediaItems + playWhenReady.
 * 4. Swap MediaSession's player to the new forwarding player BEFORE releasing old.
 * 5. Detach listeners and release old player.
 * 6. Prepare next player only if [RebuildPlan.shouldPrepareQueue] is true.
 */
object PlaybackRebuildTransaction {
    fun <Item, Params, P : RebuildPlayerAdapter<Item, Params>, F> execute(
        old: P,
        userPlayIntent: Boolean,
        createNext: () -> P,
        wrapForwarding: (P) -> F,
        session: SessionSwapAdapter<F>,
        onAfterSwapBeforeRelease: (next: P, forwarding: F, plan: RebuildPlan) -> Unit = { _, _, _ -> },
        onAfterPrepare: (next: P, plan: RebuildPlan) -> Unit = { _, _ -> },
    ): Pair<P, RebuildPlan> {
        val count = old.itemCount
        val items = if (count > 0) (0 until count).map { old.getItemAt(it) } else emptyList()
        val snap = PlayerRebuildSnapshot(
            itemCount = count,
            currentIndex = old.currentIndex,
            positionMs = old.positionMs,
            playbackState = old.playbackState,
            playWhenReady = old.playWhenReady,
            isPlaying = old.isPlaying,
            playbackSuppressionReason = old.playbackSuppressionReason,
            userPlayIntent = userPlayIntent,
        )
        val plan = BitPerfectRebuildPolicy.planRebuild(snap)
        val repeatMode = old.repeatMode
        val shuffleEnabled = old.shuffleModeEnabled
        val playbackParams = old.playbackParameters

        val next = createNext()
        val forwarding = wrapForwarding(next)

        next.repeatMode = repeatMode
        next.shuffleModeEnabled = shuffleEnabled
        next.playbackParameters = playbackParams

        if (plan.shouldSetItems && items.isNotEmpty()) {
            next.setMediaItems(items, plan.safeIndex, plan.safePositionMs)
            next.setPlayWhenReady(plan.targetPlayWhenReady)
        } else {
            next.setPlayWhenReady(false)
        }

        // Swap MediaSession player BEFORE releasing old so controllers never see a released player.
        session.swapSessionPlayer(forwarding)
        onAfterSwapBeforeRelease(next, forwarding, plan)

        old.detachListeners()
        old.release()

        if (plan.shouldPrepareQueue) {
            next.prepare()
        }
        onAfterPrepare(next, plan)
        return next to plan
    }
}
