package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionOverview
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Owns `allTransactions` (R18) and backs `getTransactions(accountUuid)` (R23) - the coroutine shape
 * `SDK_ADAPTER_PLAN.md` section 2.1 specifies: a `MutableStateFlow<List<TransactionOverview>>`,
 * re-set ONLY on [SlipstreamEngine.requeryTicks] (the section 3.2/5.4 re-query rule), full-list
 * replace, never diffed. Applies the section 3.1 visibility filter via
 * [SlipstreamTransactionReader.queryVisible] (the filter itself lives in [VisibleTransactionsQuery]).
 */
internal class TransactionsController(
    private val reader: SlipstreamTransactionReader,
    private val engine: SlipstreamEngine,
    private val scope: CoroutineScope
) {
    private val mutableAllTransactions = MutableStateFlow<List<TransactionOverview>>(emptyList())
    val allTransactions = mutableAllTransactions.asStateFlow()

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job =
            scope.launch {
                engine.requeryTicks.collect {
                    mutableAllTransactions.value = reader.queryVisible(isRecovering(), latestHeight())
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** R23: same machinery as R18 plus the `account_uuid = ?` filter; the interface has no per-account flow. */
    fun forAccount(accountUuid: AccountUuid): Flow<List<TransactionOverview>> =
        engine.requeryTicks
            .onStart { emit(Unit) }
            .map { reader.queryVisible(isRecovering(), latestHeight(), accountUuid) }

    private fun isRecovering(): Boolean = engine.lastSnapshot.value?.isRecovering ?: false

    private fun latestHeight(): BlockHeight? = engine.lastSnapshot.value?.chainTip?.takeIf { it > 0 }?.let(BlockHeight::new)
}
