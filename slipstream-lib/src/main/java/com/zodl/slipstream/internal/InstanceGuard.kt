package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** network + alias - the single-instance identity, mirroring the upstream SDK's `SynchronizerKey`. */
internal data class SlipstreamKey(
    val network: ZcashNetwork,
    val alias: String
)

/**
 * Mirrors the upstream SDK's `SynchronizerKey`/`InstanceState` single-instance guard verbatim
 * (`SdkSynchronizer.kt` lines 168-249: a companion `ConcurrentHashMap<SynchronizerKey,
 * InstanceState>`, `Active`/`ShuttingDown(job)` states). [acquire] awaits a pending [ShuttingDown]
 * for the same key under a mutex, then throws [IllegalStateException] if another instance is
 * still `Active` (their exact `check(...)` posture) - otherwise registers [Active] atomically with
 * the check, closing the same race their own `mutex.withLock { waitForShutdown(); check(...) }`
 * closes.
 */
internal object InstanceGuard {
    private sealed class InstanceState {
        data object Active : InstanceState()

        data class ShuttingDown(
            val job: Job
        ) : InstanceState()
    }

    private val mutex = Mutex()
    private val instances = ConcurrentHashMap<SlipstreamKey, InstanceState>()

    /**
     * Awaits a pending shutdown for [key], then registers [key] as [Active].
     *
     * @throws IllegalStateException when another instance with the same [key] is already [Active].
     */
    suspend fun acquire(key: SlipstreamKey) {
        mutex.withLock {
            (instances[key] as? InstanceState.ShuttingDown)?.job?.join()
            check(!instances.containsKey(key)) {
                "Another Slipstream synchronizer with $key is currently active"
            }
            instances[key] = InstanceState.Active
        }
    }

    /** Marks [key] as shutting down; [acquire] on the same key will await [job] before re-registering. */
    fun markShuttingDown(
        key: SlipstreamKey,
        job: Job
    ) {
        instances[key] = InstanceState.ShuttingDown(job)
    }

    /** Drops [key] entirely - called once the shutdown [Job] passed to [markShuttingDown] completes. */
    fun release(key: SlipstreamKey) {
        instances.remove(key)
    }

    /** True while [key] is registered [Active] - the C3 `erase` refusal check. */
    fun isActive(key: SlipstreamKey): Boolean = instances[key] is InstanceState.Active

    /**
     * Runs [block] with [key] guaranteed inactive and the guard mutex held for its whole duration,
     * mirroring the upstream `SdkSynchronizer.erase` posture (`mutex.withLock { waitForShutdown();
     * checkForExistingSynchronizers(); delete }`). Holding the mutex across [block] is the point:
     * no concurrent [acquire] can register [key] as [Active] while the files are being deleted.
     *
     * A pending [ShuttingDown] job is awaited first, because a synchronizer marks its key shutting
     * down synchronously in `close()` while the engine teardown that releases the database files
     * runs asynchronously. After the join the key may still be registered as [ShuttingDown] until
     * `release` runs, which is fine - only [Active] must refuse.
     *
     * @throws IllegalStateException when an instance with the same [key] is [Active].
     */
    suspend fun <T> withKeyInactive(
        key: SlipstreamKey,
        block: suspend () -> T
    ): T =
        mutex.withLock {
            (instances[key] as? InstanceState.ShuttingDown)?.job?.join()
            check(instances[key] !is InstanceState.Active) {
                "Cannot erase while a Slipstream synchronizer for $key is active"
            }
            block()
        }
}
