package com.zodl.slipstream.internal

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single-thread control lane for every [com.zodl.slipstream.SlipstreamNative] call that
 * takes a handle (`FFI_JNI_CONTRACT.md` section 5).
 */
internal object SlipstreamDispatchers {
    /**
     * Every SlipstreamNative call that takes a handle runs here, without exception - including
     * free(). One thread == structural impossibility of two concurrent FFI calls on one handle.
     * Mirrors the upstream SDK's single "zc-io" thread; named differently so the two libraries'
     * queues are distinguishable in traces. Deliberately NOT
     * `Dispatchers.IO.limitedParallelism(1)` - that serializes but hops threads per dispatch; a
     * dedicated thread is the proven shape (`FFI_JNI_CONTRACT.md` section 5.1).
     */
    val SLIPSTREAM_IO =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, "slipstream-io").apply { isDaemon = true }
            }.asCoroutineDispatcher()
}
