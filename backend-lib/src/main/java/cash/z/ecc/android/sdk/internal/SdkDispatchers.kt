package cash.z.ecc.android.sdk.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal object SdkExecutors {
    /**
     * Executor used for database IO that's shared with the Rust native library.
     *
     * Based on internal discussion, keep the SDK internals confined to a single IO thread.
     *
     * We don't expect things to break, but we don't have the WAL enabled for SQLite so this
     * is a simple solution.
     */
    val DATABASE_IO =
        Executors.newSingleThreadExecutor {
            Thread(it, "zc-io").apply { isDaemon = true }
        }
}

object SdkDispatchers {
    /**
     * Dispatcher used for database IO that's shared with the Rust native library.
     *
     * Based on internal discussion, keep the SDK internals confined to a single IO thread.
     *
     * We don't expect things to break, but we don't have the WAL enabled for SQLite so this
     * is a simple solution.
     *
     * Don't use `Dispatchers.IO.limitedParallelism(1)`.
     * While it executes serially, each dispatch can be on a different thread.
     */
    val DATABASE_IO = SdkExecutors.DATABASE_IO.asCoroutineDispatcher()

    /**
     * For native calls that do real (CPU-bound) work but touch no wallet database and no migration
     * engine state — e.g. the Keystone batch-signing UR/PCZT bridge (2026-08-07 blocking-without-
     * reason audit: these were on [DATABASE_IO] "only for consistency", per their own kdoc, sharing
     * the one real OS thread with every genuine SQLite read/write in the SDK for no reason — a live
     * QR-scan frame decode could queue behind an unrelated migration DB retry). [Dispatchers.Default]
     * is the ordinary choice for CPU-bound work with no IO.
     */
    val CPU_BOUND = Dispatchers.Default
}
