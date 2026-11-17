package io.max.paperplay.player

/**
 * Spots a sound output that has fallen behind for good: [needed] under-run reports within [windowMs] say so, and then
 * [hit] answers true once, and not again for [coolDownMs] so one repair is not followed by a storm of them.
 */
internal class UnderrunWatch(
    private val clock: () -> Long = System::currentTimeMillis,
    private val needed: Int = 3,
    private val windowMs: Long = 1_000,
    private val coolDownMs: Long = 3_000,
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
}
