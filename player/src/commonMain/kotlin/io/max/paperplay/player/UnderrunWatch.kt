package io.max.paperplay.player

/**
 * Spots a sound output that has fallen behind for good: [needed] under-run reports within [windowMs] say so, and then
 * [hit] answers true once, and not again for [coolDownMs] so one repair is not followed by a storm of them.
 */
internal class UnderrunWatch(
    private val clock: () -> Long = ::monotonicNowMs,
    private val needed: Int = 3,
    private val windowMs: Long = 1_000L,
    private val coolDownMs: Long = 3_000L,
) {
    private val times = ArrayDeque<Long>()
    private var lastFire = Long.MIN_VALUE / 2

    fun hit(): Boolean {
        val now = clock()
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= needed && now - lastFire >= coolDownMs) {
            lastFire = now
            times.clear()
            return true
        }
        return false
    }

    fun reset() {
        times.clear()
        lastFire = Long.MIN_VALUE / 2
    }
}

/**
 * Escalates persistent underrun repairs to a clean fallback when [maxReconfigures]
 * repairs occur within [windowMs].
 */
internal class ReconfigureEscalator(
    private val clock: () -> Long = ::monotonicNowMs,
    private val maxReconfigures: Int = 2,
    private val windowMs: Long = 15_000L,
) {
    private val repairs = ArrayDeque<Long>()

    /** Records an in-session reconfigure repair and returns true if escalation to fallback is required. */
    fun recordReconfigureAndShouldFallback(): Boolean {
        val now = clock()
        repairs.addLast(now)
        while (repairs.isNotEmpty() && now - repairs.first() > windowMs) repairs.removeFirst()
        if (repairs.size >= maxReconfigures) {
            repairs.clear()
            return true
        }
        return false
    }

    fun reset() {
        repairs.clear()
    }
}
