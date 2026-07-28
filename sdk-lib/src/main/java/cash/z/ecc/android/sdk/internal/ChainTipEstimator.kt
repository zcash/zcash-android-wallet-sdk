package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.db.ReadOnlySupportSqliteOpenHelper
import cash.z.ecc.android.sdk.internal.db.derived.BlockTable
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal fun interface ChainTipEstimator {
    suspend fun estimatedTip(): Long
}

/**
 * Estimates the current chain tip from the latest scanned block plus elapsed wall-clock time at a
 * 75-second average block interval. Returns -1 when no block has been scanned yet.
 */
internal fun estimateTip(
    scannedHeight: Long,
    scannedBlockTimeEpochSeconds: Long,
    nowEpochSeconds: Long,
): Long = scannedHeight + ((nowEpochSeconds - scannedBlockTimeEpochSeconds).coerceAtLeast(0L) / 75L)

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
        )
    }
}

/**
 * A [ChainTipEstimator] that opens the wallet's data database as read-only on first use and
 * queries the [BlockTable] for the latest scanned block. Safe to construct eagerly — the DB is
 * only opened on the first [estimatedTip] call, and the same open handle is reused from then on.
 *
 * DATABASE_VERSION must match the version the Rust backend wrote — currently 8 (see
 * `DerivedDataDb.DATABASE_VERSION`). The open helper bypasses Room migration checks and just opens
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
                        DATA_DB_VERSION,
                    )
                BlockTable(db)
            }
        }

    override suspend fun estimatedTip(): Long =
        try {
            val latestBlock = blockTableLazy.getInstance(Unit).findLatestBlock() ?: return -1L
            estimateTip(
                scannedHeight = latestBlock.height.value,
                scannedBlockTimeEpochSeconds = latestBlock.blockTimeEpochSeconds,
                nowEpochSeconds = clock.now().epochSeconds,
            )
        } catch (e: Exception) {
            Twig.warn(e) { "ChainTipEstimator: could not read latest block — returning -1" }
            -1L
        }

    private companion object {
        // Matches DerivedDataDb.DATABASE_VERSION — the version the Rust backend writes.
        const val DATA_DB_VERSION = 8
    }
}
