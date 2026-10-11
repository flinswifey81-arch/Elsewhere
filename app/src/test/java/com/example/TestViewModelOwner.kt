package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Owns directly constructed test ViewModels so their scopes are cancelled between tests. */
internal class TestViewModelOwner : ViewModelStoreOwner, AutoCloseable {
    override val viewModelStore = ViewModelStore()
    private var nextKey = 0

    fun <T : ViewModel> create(expectedClass: Class<T>, initializer: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <R : ViewModel> create(modelClass: Class<R>): R {
                require(modelClass == expectedClass) { "Unexpected ViewModel class ${modelClass.name}" }
                return initializer() as R
            }
        }
        return ViewModelProvider(this, factory)["test-${nextKey++}", expectedClass]
    }

    override fun close() {
        viewModelStore.clear()
    }
}
