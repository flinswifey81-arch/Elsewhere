package com.example.domain.generation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Keeps a chat's ViewModel alive while an active reply is streaming, even if its screen is
 * removed from the navigation back stack. The owner is application-scoped, not activity-scoped.
 *
 * This is the navigation-lifetime foundation, NOT a process-death or Android background-service
 * guarantee. A foreground service is still required for reliable app-background execution.
 */
class RetainedChatViewModelStores(private val maxIdleStores: Int = 8) {
    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private data class Entry(
        val owner: Owner,
        var visible: Boolean = false,
        var hasActiveWork: () -> Boolean = { false }
    )

    // Access-order permits LRU cleanup of dormant screens without affecting active generation.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun ownerFor(chatId: String): ViewModelStoreOwner {
        require(chatId.isNotBlank()) { "A chat ID is required" }
        val entry = entries.getOrPut(chatId) { Entry(Owner()) }
        trimIdleLocked()
        return entry.owner
    }

    @Synchronized
    fun onAttached(chatId: String, hasActiveWork: () -> Boolean) {
        val entry = entries[chatId] ?: return
        entry.visible = true
        entry.hasActiveWork = hasActiveWork
    }

    @Synchronized
    fun onDetached(chatId: String) {
        val entry = entries[chatId] ?: return
        entry.visible = false
        // If saving/sending/streaming has begun, destroying the ViewModel would cancel the
        // provider request and potentially throw away already-billed output.
        if (!entry.hasActiveWork()) {
            entries.remove(chatId)?.owner?.viewModelStore?.clear()
        }
        trimIdleLocked()
    }

    @Synchronized
    fun isVisible(chatId: String): Boolean = entries[chatId]?.visible == true

    @Synchronized
    fun forget(chatId: String) {
        entries.remove(chatId)?.owner?.viewModelStore?.clear()
    }

    private fun trimIdleLocked() {
        val idle = entries.filter { !it.value.visible && !it.value.hasActiveWork() }
        val excess = idle.size - maxIdleStores
        if (excess <= 0) return
        idle.take(excess).forEach { (chatId, entry) ->
            entries.remove(chatId)
            entry.owner.viewModelStore.clear()
        }
    }
}
