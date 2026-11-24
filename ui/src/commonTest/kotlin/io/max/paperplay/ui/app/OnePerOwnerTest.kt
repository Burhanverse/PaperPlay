package io.max.paperplay.ui.app

import io.max.paperplay.ui.logic.OnePerOwner
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class OnePerOwnerTest {
    private class Backend(var alive: Boolean = true)

    @Test
    fun reopeningTheWindowGetsTheSameBackendUntilTheServicesEnd() {
        val one = OnePerOwner<Any, Backend>()
        val services = Any()
        val first = one.of(services, { it.alive }) { Backend() }
        assertSame(first, one.of(services, { it.alive }) { Backend() })
        // Shut down for a restore: a new one, also for new services.
        first.alive = false
        val second = one.of(services, { it.alive }) { Backend() }
        assertNotSame(first, second)
        assertNotSame(second, one.of(Any(), { it.alive }) { Backend() })
    }
}
