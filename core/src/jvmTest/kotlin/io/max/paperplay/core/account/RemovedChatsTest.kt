package io.max.paperplay.core.account

import io.max.paperplay.core.db.libraryDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemovedChatsTest {
    private val a = IndexStore(libraryDriver())
    private val b = IndexStore(libraryDriver())

    @Test
    fun aRemovedChatIsDeletedOnTheOtherDevice() {
        a.saveChat(-5, "Old"); b.saveChat(-5, "Old")
        a.removeChat(-5, at = 100)
        assertNull(a.chat(-5))
        assertEquals(1, b.mergeRemoved(a.removedRows()))
        assertNull(b.chat(-5))
        assertTrue(b.isGone(-5))
    }

    @Test
    fun anOlderMarkDoesNotDeleteAChatAddedBack() {
        a.saveChat(-5, "Old")
        a.removeChat(-5, at = 100)
        b.saveChat(-5, "Old"); b.removeChat(-5, at = 100)
        b.saveChat(-5, "Old"); b.addBack(-5, at = 200)
        // The device that still holds the older removal takes the newer add-back, and the chat is allowed again.
        assertEquals(0, a.mergeRemoved(b.removedRows()))
        assertFalse(a.isGone(-5))
        // And the newer add-back does not get undone by the older removal coming back.
        assertEquals(0, b.mergeRemoved(listOf(Triple(-5L, true, 100L))))
        assertFalse(b.isGone(-5))
    }

    @Test
    fun addingAChatThatWasNeverRemovedLeavesNoMark() {
        a.saveChat(-7, "New"); a.addBack(-7, at = 5)
        assertTrue(a.removedRows().isEmpty())
    }
}
