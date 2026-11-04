package com.example

import com.example.domain.generation.RetainedChatViewModelStores
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedChatViewModelStoresTest {
    @Test
    fun navigatingAwayDuringGenerationKeepsTheSameChatOwner() {
        val stores = RetainedChatViewModelStores()
        var busy = true
        val original = stores.ownerFor("chat-a")
        stores.onAttached("chat-a") { busy }
        assertTrue(stores.isVisible("chat-a"))

        stores.onDetached("chat-a")
        assertFalse(stores.isVisible("chat-a"))
        assertSame(original, stores.ownerFor("chat-a"))
        assertNotSame(original, stores.ownerFor("chat-b"))

        // Re-enter, finish generating, then leave. Idle owners must release normally.
        stores.onAttached("chat-a") { busy }
        busy = false
        stores.onDetached("chat-a")
        assertNotSame(original, stores.ownerFor("chat-a"))
    }

    @Test
    fun inactiveChatDoesNotRemainPinnedAfterNavigation() {
        val stores = RetainedChatViewModelStores()
        val owner = stores.ownerFor("chat-idle")
        stores.onAttached("chat-idle") { false }
        stores.onDetached("chat-idle")
        assertNotSame(owner, stores.ownerFor("chat-idle"))
    }

    @Test
    fun forgettingDeletedChatClearsItsOwner() {
        val stores = RetainedChatViewModelStores()
        val owner = stores.ownerFor("deleted")
        stores.onAttached("deleted") { true }
        stores.forget("deleted")
        assertNotSame(owner, stores.ownerFor("deleted"))
    }

    @Test
    fun anotherChatCanOpenWithoutEvictingAnInFlightReply() {
        val stores = RetainedChatViewModelStores(maxIdleStores = 1)
        val ongoing = stores.ownerFor("ongoing")
        stores.onAttached("ongoing") { true }
        stores.onDetached("ongoing")

        repeat(15) { index ->
            val id = "other-$index"
            stores.ownerFor(id)
            stores.onAttached(id) { false }
            stores.onDetached(id)
        }
        assertSame(ongoing, stores.ownerFor("ongoing"))
    }
}
