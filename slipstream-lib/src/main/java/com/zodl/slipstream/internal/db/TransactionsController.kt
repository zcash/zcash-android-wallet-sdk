package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionOverview
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Owns `allTransactions` (R18) and backs `getTransactions(accountUuid)` (R23).
 *
 * Deliberately deviates from `SDK_ADAPTER_PLAN.md` section 2.1's literal shape - a
 * `MutableStateFlow<List<TransactionOverview>>`, re-set ONLY on [SlipstreamEngine.requeryTicks]
 * and shared hot across every collector. What's actually implemented is a COLD
 * `requeryTicks.onStart { emit(Unit) }.mapLatest { queryVisible }.flowOn(Dispatchers.Default)`
 * chain: each collector re-runs its own query on the section 3.2/5.4 re-query rule rather than
 * observing one shared re-set value, and there is no state held between requery ticks - full-list
 * replace, never diffed, is still true, just per-collector rather than per-controller. Applies the
 * section 3.1 visibility filter via [SlipstreamTransactionReader.queryVisible] (the filter itself
 * lives in `host_read.rs`'s `list_transactions_sql`, moved from the Kotlin `VisibleTransactionsQuery`
 * this reader used to build).
 */
internal class TransactionsController(
    private val reader: SlipstreamTransactionReader,
    private val engine: SlipstreamEngine,
    private val typesafeBackend: TypesafeBackend,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val allTransactions =
        engine.requeryTicks
            .onStart { emit(Unit) }
            .mapLatest { reader.queryVisible(isRecovering(), latestHeight()) }
            .flowOn(Dispatchers.Default)

    /**
     * R23: same machinery as R18 plus the `account_uuid = ?` filter; the interface has no
     * per-account flow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forAccount(accountUuid: AccountUuid): Flow<List<TransactionOverview>> =
        engine.requeryTicks
            .onStart { emit(Unit) }
            .mapLatest { reader.queryVisible(isRecovering(), latestHeight(), accountUuid) }
            .flowOn(Dispatchers.Default)

    private fun isRecovering(): Boolean = engine.lastSnapshot.value?.isRecovering ?: false

    /**
     * MOB-1664: [SlipstreamEngine.lastSnapshot] is per-engine-instance state that starts back at
     * `null` every time the synchronizer is rebuilt (e.g. an automatic server switch tears down
     * and reconstructs the whole engine via `WalletCoordinator`'s `flatMapLatest`) - unlike the
     * legacy `SdkSynchronizer.getTransactions()`, which always had `backend.getMaxScannedHeight()`
     * as a DB-backed fallback for exactly this gap. That fallback was lost when this path was
     * ported; restoring it here (rather than trying to seed the new engine instance from the old
     * one's last-known height) keeps every account's confirmation math tied to its own DB file,
     * so it can't leak state across a network/account switch and stays reorg-safe (a reorged-out
     * tx's `minedHeight` is cleared in the DB, so `computeTransactionState` still won't report it
     * Confirmed even once `latestHeight` resolves again).
     */
    private suspend fun latestHeight(): BlockHeight? =
        resolveLiveChainTip(engine.lastSnapshot.value?.chainTip) ?: typesafeBackend.getMaxScannedHeight()
}

/**
 * MOB-1664: the live-height half of [TransactionsController.latestHeight]'s decision, pulled out
 * as a pure function so the "is this snapshot's chainTip trustworthy" check is directly testable
 * without a live [SlipstreamEngine]/native handle. Returns `null` for a fresh engine instance
 * (chainTip absent) or a degraded snapshot (chainTip <= 0), signalling the caller to fall back to
 * the DB-backed scanned height instead of treating the raw reading as ground truth.
 */
internal fun resolveLiveChainTip(chainTip: Long?): BlockHeight? = chainTip?.takeIf { it > 0 }?.let(BlockHeight::new)
