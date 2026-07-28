package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.db.ReadOnlySupportSqliteOpenHelper
import cash.z.ecc.android.sdk.internal.db.derived.BlockTable
import cash.z.ecc.android.sdk.internal.db.derived.DerivedDataDb
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal class ChainTipEstimatorImpl(
    private val blockTable: BlockTable,
    private val clock: Clock = Clock.System,
) : ChainTipEstimator {
    override suspend fun estimatedTip(): Long {
        val latestBlock = blockTable.findLatestBlock() ?: return -1L
        return estimateTip(
            scannedHeight = latestBlock.height.value,
            scannedBlockTimeEpochSeconds = latestBlock.blockTimeEpochSeconds,
            nowEpochSeconds = clock.now().epochSeconds,
            secondsPerBlock = estimatedSecondsPerBlock(),
        )
    }

    override suspend fun estimatedSecondsPerBlock(): Long =
        blockTable.measuredRateOrFallback()
}

/** Shared rate read: latest block vs the block RATE_WINDOW_BLOCKS below it (or the oldest available). */
internal suspend fun BlockTable.measuredRateOrFallback(): Long {
    val latest = findLatestBlock() ?: return FALLBACK_SECONDS_PER_BLOCK
    val older =
        findBlockByExpiryHeight(
            cash.z.ecc.android.sdk.model.BlockHeight((latest.height.value - RATE_WINDOW_BLOCKS).coerceAtLeast(0L))
        ) ?: return FALLBACK_SECONDS_PER_BLOCK
    return measuredSecondsPerBlock(
        olderHeight = older.height.value,
        olderTimeEpochSeconds = older.blockTimeEpochSeconds,
        newerHeight = latest.height.value,
        newerTimeEpochSeconds = latest.blockTimeEpochSeconds,
    )
}

/**
 * A [ChainTipEstimator] that opens the wallet's data database as read-only on first use and
 * queries the [BlockTable] for the latest scanned block. Safe to construct eagerly — the DB is
 * only opened on the first [estimatedTip] call, and the same open handle is reused from then on.
 *
 * DATABASE_VERSION must match the version the Rust backend wrote — references the authoritative
 * `DerivedDataDb.DATABASE_VERSION`. The open helper bypasses Room migration checks and just opens
 * the existing file read-only, so a version mismatch would surface as a SQLite error at open time
 * rather than a silent schema divergence.
 */
internal class LazyDataDbChainTipEstimator(
    private val context: Context,
    private val network: ZcashNetwork,
    private val alias: String,
    private val clock: Clock = Clock.System,
) : ChainTipEstimator {
    private val blockTableLazy: SuspendingLazy<Unit, BlockTable> =
        SuspendingLazy {
            withContext(Dispatchers.IO) {
                val coordinator = DatabaseCoordinator.getInstance(context)
                val dbFile = coordinator.dataDbFile(network, alias)
                val db =
                    ReadOnlySupportSqliteOpenHelper.openExistingDatabaseAsReadOnly(
                        NoBackupContextWrapper(context, dbFile.parentFile!!),
                        dbFile,
                        DerivedDataDb.DATABASE_VERSION,
                    )
                BlockTable(db)
            }
        }

    override suspend fun estimatedTip(): Long =
        try {
            val table = blockTableLazy.getInstance(Unit)
            val latestBlock = table.findLatestBlock() ?: return -1L
            estimateTip(
                scannedHeight = latestBlock.height.value,
                scannedBlockTimeEpochSeconds = latestBlock.blockTimeEpochSeconds,
                nowEpochSeconds = clock.now().epochSeconds,
                secondsPerBlock = table.measuredRateOrFallback(),
            )
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not read latest block — returning -1" }
            -1L
        }

    override suspend fun estimatedSecondsPerBlock(): Long =
        try {
            blockTableLazy.getInstance(Unit).measuredRateOrFallback()
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not measure block rate — falling back" }
            FALLBACK_SECONDS_PER_BLOCK
        }
}
