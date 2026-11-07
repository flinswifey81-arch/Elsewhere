package com.example

import android.app.Application
import android.util.Log
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class ElsewhereApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Recovery only runs if a previous selective World restore left a persistent
        // journal or staging files. Do not delay ordinary app startup.
        container.worldArchiveRestorer?.takeIf { it.needsRecovery() }?.let { restorer ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { restorer.recoverInterruptedImports() }
                    .onFailure { Log.e("ElsewhereWorlds", "Interrupted World restore recovery failed", it) }
            }
        }
    }
}
