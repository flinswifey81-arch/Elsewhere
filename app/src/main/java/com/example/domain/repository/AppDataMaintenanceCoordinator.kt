package com.example.domain.repository

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@JvmInline
value class AppDataRevision internal constructor(internal val value: Long)

data class AppDataMaintenanceState(
    val revision: AppDataRevision,
    val isReplacingData: Boolean
)

class StaleAppDataMutationException : IllegalStateException(
    "App data was replaced while this operation was in progress. Reload and try again."
)

/**
 * Process-wide serialization boundary for durable app-data mutations and maintenance.
 *
 * Backup/restore holds the same boundary while it snapshots or replaces Room rows,
 * attachments, profile images, and appearance settings. Repository writes therefore
 * cannot land between the database and file-system portions of those operations. A
 * replacement advances the revision at both boundaries so work from the old data set,
 * including work launched while replacement is running, is rejected instead of replayed.
 */
class AppDataMaintenanceCoordinator {
    private val gate = Mutex()
    private val revisionCounter = AtomicLong(0L)
    private val mutableState = MutableStateFlow(
        AppDataMaintenanceState(AppDataRevision(0L), isReplacingData = false)
    )

    val state: StateFlow<AppDataMaintenanceState> = mutableState.asStateFlow()

    fun captureRevision(): AppDataRevision = AppDataRevision(revisionCounter.get())

    suspend fun <T> withMutation(
        expectedRevision: AppDataRevision = captureRevision(),
        block: suspend () -> T
    ): T {
        val existing = coroutineContext[Lease]
        if (existing?.coordinator === this) return block()
        return gate.withLock {
            if (expectedRevision != captureRevision()) throw StaleAppDataMutationException()
            withContext(Lease(this)) { block() }
        }
    }

    suspend fun <T> withSnapshotMaintenance(block: suspend () -> T): T =
        withExclusiveAccess(block)

    suspend fun <T> withDataReplacement(block: suspend () -> T): T {
        val existing = coroutineContext[Lease]
        if (existing?.coordinator === this) return block()
        return gate.withLock {
            withContext(Lease(this)) {
                advanceRevision(isReplacingData = true)
                try {
                    block()
                } finally {
                    advanceRevision(isReplacingData = false)
                }
            }
        }
    }

    private suspend fun <T> withExclusiveAccess(block: suspend () -> T): T {
        val existing = coroutineContext[Lease]
        if (existing?.coordinator === this) return block()
        return gate.withLock { withContext(Lease(this)) { block() } }
    }

    private fun advanceRevision(isReplacingData: Boolean) {
        val revision = AppDataRevision(revisionCounter.incrementAndGet())
        mutableState.value = AppDataMaintenanceState(revision, isReplacingData)
    }

    private class Lease(
        val coordinator: AppDataMaintenanceCoordinator
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    companion object {
        /** Shared by production repositories and by legacy/test constructors that omit injection. */
        val Global = AppDataMaintenanceCoordinator()
    }
}
