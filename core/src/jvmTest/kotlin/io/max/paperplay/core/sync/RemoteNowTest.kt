package io.max.paperplay.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteNowTest {
    @Test fun playingPositionMovesOnWithTime() {
        val n = RemoteNow("u", "t", "a", true, positionMs = 10_000, durationMs = 200_000, at = 1_000)
        assertEquals(13_000, n.positionAt(4_000))
    }

    @Test fun pausedPositionStaysAndNeverPassesTheEnd() {
        assertEquals(10_000, RemoteNow("u", null, null, false, 10_000, 200_000, 1_000).positionAt(9_000))
        assertEquals(200_000, RemoteNow("u", null, null, true, 199_000, 200_000, 1_000).positionAt(60_000))
    }

    @Test fun actionsParse() {
        assertEquals(RemoteAction.Pause, RemoteAction.parse("pause"))
        assertEquals(RemoteAction.Seek(4500), RemoteAction.parse("seek:4500"))
        assertEquals(RemoteAction.PlaySong("tg-AgAD:x", 1200), RemoteAction.parse("play:tg-AgAD:x:1200"))
        assertNull(RemoteAction.parse("seek:abc"))
        assertNull(RemoteAction.parse("play:nopos"))
        assertNull(RemoteAction.parse(null))
    }
}

class RemoteNowFreshTest {
    @Test fun anOldStateCountsAsQuiet() {
        val n = RemoteNow("u", "t", "a", true, 0, 0, at = 1_000)
        assertEquals(true, n.playingNow(5_000))
        assertEquals(false, n.playingNow(20_000))
        assertEquals(false, RemoteNow(null, null, null, true, at = 1_000).playingNow(2_000))
    }
}
