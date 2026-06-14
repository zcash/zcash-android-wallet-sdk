package cash.z.ecc.android.sdk.block.processor

import cash.z.ecc.android.sdk.model.FirstClassByteArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.pow

/**
 * Tracks per-txid enhance failures so the sync pipeline's transaction-enhance step can back
 * off cross-cycle on a tx that has repeatedly failed to enhance against the current lightwalletd
 * endpoint. Without this, the rust backend keeps re-queueing the same `TransactionDataRequest`
 * every sync cycle and the SDK keeps hammering the server with retries that immediately fail
 * again.
 */
internal class EnhanceFailureTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val failuresByTxId = mutableMapOf<FirstClassByteArray, Record>()

    suspend fun shouldSkipDueToBackoff(txId: ByteArray): Boolean =
        mutex.withLock {
            val record = failuresByTxId[FirstClassByteArray(txId)] ?: return@withLock false
            val backoff = backoffFor(attempt = record.attemptCount)
            (clock() - record.lastAttemptAt) < backoff
        }

    suspend fun recordFailure(txId: ByteArray) {
        mutex.withLock {
            val key = FirstClassByteArray(txId)
            val now = clock()
            val previous = failuresByTxId[key]
            failuresByTxId[key] =
                Record(
                    attemptCount = (previous?.attemptCount ?: 0) + 1,
                    lastAttemptAt = now
                )
            // Drop entries older than the retention window so the map stays bounded.
            failuresByTxId.entries.removeAll { now - it.value.lastAttemptAt >= MAX_RETENTION_MILLIS }
        }
    }

    suspend fun recordSuccess(txId: ByteArray) {
        mutex.withLock {
            failuresByTxId.remove(FirstClassByteArray(txId))
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0).toDouble()
        val raw = (BASE_BACKOFF_MILLIS * 2.0.pow(exponent)).toLong()
        return min(raw, MAX_BACKOFF_MILLIS)
    }

    private data class Record(
        val attemptCount: Int,
        val lastAttemptAt: Long
    )

    private companion object {
        /** Base wait after the first failure before retrying across sync cycles. */
        const val BASE_BACKOFF_MILLIS = 60_000L
        /** Cap so an unbounded exponential never extends past 30 minutes. */
        const val MAX_BACKOFF_MILLIS = 1_800_000L
        /** Drop entries older than this to keep the map bounded. */
        const val MAX_RETENTION_MILLIS = 7_200_000L
    }
}
