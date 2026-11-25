package io.max.paperplay.ui.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Work on the library as a whole (shutting it down, restoring a backup, erasing everything). It belongs to the process, not to a window:
 * closing the window must not stop it half way, and nothing may open the library while it runs.
 */
class LifeWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = MutableStateFlow(0)

    val busy: Boolean get() = running.value > 0

    fun launch(block: suspend () -> Unit) {
        running.update { it + 1 }
        scope.launch { try { block() } finally { running.update { it - 1 } } }
    }

    /** Waits until nothing runs any more. */
    suspend fun idle() { running.first { it == 0 } }

    companion object { val app = LifeWork() }
}
