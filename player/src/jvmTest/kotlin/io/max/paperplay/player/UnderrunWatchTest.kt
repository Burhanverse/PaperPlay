package io.max.paperplay.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderrunWatchTest {
    @Test
    fun `three under-runs within a second fire once`() {
        var now = 0L
        val w = UnderrunWatch(clock = { now })
        assertFalse(w.hit()); now += 100
        assertFalse(w.hit()); now += 100
        assertTrue(w.hit())
    }

    @Test
    fun `two are not enough and slow ones are forgotten`() {
        var now = 0L
        val w = UnderrunWatch(clock = { now })
        assertFalse(w.hit()); now += 100
        assertFalse(w.hit()); now += 2_000
        assertFalse(w.hit()); now += 2_000
        assertFalse(w.hit())
    }

    @Test
    fun `after firing it waits out the cool-down`() {
        var now = 0L
        val w = UnderrunWatch(clock = { now })
        repeat(2) { w.hit(); now += 10 }
        assertTrue(w.hit())
        // A storm right after a repair does not fire again until the cool-down has passed.
        val during = (1..10).count { now += 50; w.hit() }
        assertEquals(0, during)
        now += 3_000
        repeat(2) { w.hit(); now += 10 }
        assertTrue(w.hit())
    }

    @Test
    fun `reconfigure escalator triggers on two repairs within 15 seconds`() {
        var now = 0L
        val e = ReconfigureEscalator(clock = { now })
        assertFalse(e.recordReconfigureAndShouldFallback())
        now += 5_000
        assertTrue(e.recordReconfigureAndShouldFallback())
    }

    @Test
    fun `reconfigure escalator forgets slow repairs and resets cleanly`() {
        var now = 0L
        val e = ReconfigureEscalator(clock = { now })
        assertFalse(e.recordReconfigureAndShouldFallback())
        now += 20_000
        assertFalse(e.recordReconfigureAndShouldFallback())
        e.reset()
        assertFalse(e.recordReconfigureAndShouldFallback())
    }
}
