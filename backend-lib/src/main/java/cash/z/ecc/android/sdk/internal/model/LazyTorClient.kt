package cash.z.ecc.android.sdk.internal.model

import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lazily-initialized, shared holder for a base [TorClient].
 *
 * Creating a [TorClient] starts a Tor runtime, which is a relatively expensive operation (on the
 * order of a second). This holder defers that cost until the [TorClient] is actually needed (e.g.
 * for an exchange rate fetch, or a caller-requested Tor [io.ktor.client.HttpClient]) rather than
 * paying it eagerly while constructing a [cash.z.ecc.android.sdk.Synchronizer].
 *
 * The created [TorClient] is cached and shared between all callers of [getOrCreate], so the
 * expensive runtime creation happens at most once, and [dispose] frees it at most once.
 *
 * Once [dispose] has been called, this holder is permanently disposed: [getOrCreate] will throw
 * [IllegalStateException] rather than creating a new [TorClient].
 */
class LazyTorClient(
    private val factory: suspend () -> TorClient
) : Disposable {
    private val mutex = Mutex()
    private var instance: TorClient? = null
    private var disposed = false

    /**
     * Returns the shared base [TorClient], creating it via [factory] on first call.
     *
     * @throws IllegalStateException if this holder has already been [dispose]d.
     */
    suspend fun getOrCreate(): TorClient =
        mutex.withLock {
            check(!disposed) { "LazyTorClient is disposed" }

            instance ?: factory().also { instance = it }
        }

    /**
     * Runs [action] against the underlying [TorClient] only if it has already been created,
     * without forcing creation as a side effect. Useful for best-effort operations, such as
     * toggling dormant mode, that are meaningless before the client exists.
     */
    suspend fun ifCreated(action: suspend (TorClient) -> Unit) {
        val current = mutex.withLock { instance }
        current?.let { action(it) }
    }

    override suspend fun dispose() =
        mutex.withLock {
            instance?.dispose()
            instance = null
            disposed = true
        }
}
