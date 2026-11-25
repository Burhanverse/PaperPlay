package io.max.paperplay.ui.app

import io.max.paperplay.ui.logic.LifeWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeWorkTest {
    @Test
    fun itOutlivesTheScreenThatStartedItAndOthersWaitForIt() = runBlocking<Unit> {
        val work = LifeWork()
        val go = CompletableDeferred<Unit>()
        var finished = false
        // The screen starts it and goes away.
        coroutineScope { launch { work.launch { go.await(); finished = true } } }
        assertTrue(work.busy)
        assertEquals(null, withTimeoutOrNull(200) { work.idle() })
        go.complete(Unit)
        withTimeout(2_000) { work.idle() }
        assertTrue(finished)
    }
}
