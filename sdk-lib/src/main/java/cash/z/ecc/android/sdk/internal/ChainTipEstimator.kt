package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.time.Clock

internal interface ChainTipEstimator {
    suspend fun estimatedTip(): Long

    /**
     * Measured average seconds per block over the recent scanned window (header timestamps),
     * clamped to [MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK]. Falls back to the 75s protocol
     * target when fewer than two blocks are available. Testnet's minimum-difficulty rule makes
     * real spacing bursty (seconds-per-block during bursts) — a constant 75s there turns every
     * height-to-time projection into an overestimate.
     */
    suspend fun estimatedSecondsPerBlock(): Long
}

internal const val FALLBACK_SECONDS_PER_BLOCK = 75L

/** Default when no estimator is wired: no estimate, protocol-constant rate. */
internal object NoOpChainTipEstimator : ChainTipEstimator {
    override suspend fun estimatedTip(): Long = -1L

    override suspend fun estimatedSecondsPerBlock(): Long = FALLBACK_SECONDS_PER_BLOCK
}

internal const val MIN_SECONDS_PER_BLOCK = 5L
internal const val MAX_SECONDS_PER_BLOCK = 150L

/** Blocks of history used for the rate measurement. */
internal const val RATE_WINDOW_BLOCKS = 100L

/** Pure rate computation: clamped average spacing between two scanned blocks. */
internal fun measuredSecondsPerBlock(
    olderHeight: Long,
    olderTimeEpochSeconds: Long,
    newerHeight: Long,
    newerTimeEpochSeconds: Long,
): Long {
    val blocks = newerHeight - olderHeight
    if (blocks <= 0) return FALLBACK_SECONDS_PER_BLOCK
    val span = (newerTimeEpochSeconds - olderTimeEpochSeconds).coerceAtLeast(0)
    return (span / blocks).coerceIn(MIN_SECONDS_PER_BLOCK, MAX_SECONDS_PER_BLOCK)
}

/**
 * Estimates the current chain tip from the latest scanned block plus elapsed wall-clock time at a
 * 75-second average block interval. Returns -1 when no block has been scanned yet.
 */
internal fun estimateTip(
    scannedHeight: Long,
    scannedBlockTimeEpochSeconds: Long,
    nowEpochSeconds: Long,
    secondsPerBlock: Long = FALLBACK_SECONDS_PER_BLOCK,
): Long =
    scannedHeight +
        ((nowEpochSeconds - scannedBlockTimeEpochSeconds).coerceAtLeast(0L) / secondsPerBlock.coerceAtLeast(1L))

/**
 * Pure derivation of the measured rate from a `blockRateSamples` array
 * (`[latestHeight, latestTime, olderHeight, olderTime]`): the clamped average spacing between the
 * two samples, or the protocol fallback when the older sample is absent (array shorter than 4).
 */
internal fun secondsPerBlockFromSamples(samples: LongArray): Long =
    if (samples.size >= 4) {
        measuredSecondsPerBlock(
            olderHeight = samples[2],
            olderTimeEpochSeconds = samples[3],
            newerHeight = samples[0],
            newerTimeEpochSeconds = samples[1],
        )
    } else {
        FALLBACK_SECONDS_PER_BLOCK
    }

/**
 * A [ChainTipEstimator] that reads its block samples through the engine's BUNDLED SQLite (a JNI
 * call), never through a second Android-framework SQLite instance on the engine-owned
 * `data.sqlite3`.
 *
 * This deliberately does NOT use `ReadOnlySupportSqliteOpenHelper`/`BlockTable` (framework
 * `android.database.sqlite.SQLiteDatabase`): running a second SQLite library instance against the
 * engine's DB in one process makes a framework `close()` drop the engine's fcntl/WAL locks and
 * truncate the `-shm` index under the engine's live mmap → deterministic SIGBUS (Milan's
 * dual-SQLite-instance incident; the production host reads moved to bundled rusqlite for exactly
 * this reason). An earlier version of this class opened framework SQLite here and both leaked the
 * handle (StrictMode `LeakedClosableViolation`) and reintroduced that hazard.
 */
internal class LazyDataDbChainTipEstimator(
    private val context: Context,
    private val network: ZcashNetwork,
    private val alias: String,
    private val clock: Clock = Clock.System,
    private val rustBackendProvider: suspend () -> MigrationRustBackend = { MigrationRustBackend.new() },
) : ChainTipEstimator {
    private suspend fun samples(): LongArray {
        val dbFile = DatabaseCoordinator.getInstance(context).dataDbFile(network, alias)
        // blockRateSamples dispatches its own JNI read onto SdkDispatchers.DATABASE_IO.
        return rustBackendProvider().blockRateSamples(dbFile.absolutePath, RATE_WINDOW_BLOCKS)
    }

    override suspend fun estimatedTip(): Long =
        try {
            val s = samples()
            if (s.size < 2) {
                -1L
            } else {
                estimateTip(
                    scannedHeight = s[0],
                    scannedBlockTimeEpochSeconds = s[1],
                    nowEpochSeconds = clock.now().epochSeconds,
                    secondsPerBlock = secondsPerBlockFromSamples(s),
                )
            }
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not read latest block — returning -1" }
            -1L
        }

    override suspend fun estimatedSecondsPerBlock(): Long =
        try {
            secondsPerBlockFromSamples(samples())
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not measure block rate — falling back" }
            FALLBACK_SECONDS_PER_BLOCK
        }
}
