package io.max.paperplay.player.usb

/**
 * Pure ref-counted token controller for USB streaming wakelock management (JVM-testable).
 * Calls [onAcquire] strictly on 0 -> 1 transitions and [onRelease] strictly on 1 -> 0 transitions.
 */
internal class RefCountedWakeLockController(
    private val onAcquire: () -> Unit,
    private val onRelease: () -> Unit,
) {
    private val lock = Any()
    private val owners = LinkedHashSet<String>()

    val refCount: Int
        get() = synchronized(lock) { owners.size }

    val isHeld: Boolean
        get() = synchronized(lock) { owners.isNotEmpty() }

    fun acquire(owner: String): Boolean {
        val shouldAcquire = synchronized(lock) {
            val wasEmpty = owners.isEmpty()
            owners.add(owner)
            wasEmpty && owners.isNotEmpty()
        }
        if (shouldAcquire) {
            onAcquire()
        }
        return shouldAcquire
    }

    fun release(owner: String): Boolean {
        val shouldRelease = synchronized(lock) {
            val removed = owners.remove(owner)
            removed && owners.isEmpty()
        }
        if (shouldRelease) {
            onRelease()
        }
        return shouldRelease
    }

    fun releaseAll(): Boolean {
        val shouldRelease = synchronized(lock) {
            val hadAny = owners.isNotEmpty()
            owners.clear()
            hadAny
        }
        if (shouldRelease) {
            onRelease()
        }
        return shouldRelease
    }
}
