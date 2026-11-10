package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Owns directly constructed test ViewModels so their scopes are cancelled between tests. */
internal class TestViewModelOwner : ViewModelStoreOwner, AutoCloseable {
    override val viewModelStore = ViewModelStore()
    private var nextKey = 0
    private val scopedJobs = mutableListOf<Job>()

    fun <T : ViewModel> create(expectedClass: Class<T>, initializer: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <R : ViewModel> create(modelClass: Class<R>): R {
                require(modelClass == expectedClass) { "Unexpected ViewModel class ${modelClass.name}" }
                return initializer() as R
            }
        }
        val model = ViewModelProvider(this, factory)["test-${nextKey++}", expectedClass]
        // The ViewModelStore cancels scopes on clear(), but cancellation is not
        // the same as completion. Remember their parent Jobs for teardown.
        model.viewModelScope.coroutineContext[Job]?.let(scopedJobs::add)
        return model
    }

    override fun close() {
        viewModelStore.clear()
        try {
            // Do not reset Dispatchers.Main or close Room while cancelled
            // ViewModel child collectors still reference that dispatcher.
            runBlocking {
                withTimeout(10_000) { scopedJobs.joinAll() }
            }
        } finally {
            scopedJobs.clear()
        }
    }
}
