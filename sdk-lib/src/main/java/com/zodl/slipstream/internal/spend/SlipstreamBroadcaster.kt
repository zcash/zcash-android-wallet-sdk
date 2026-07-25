@file:Suppress("LongParameterList")

package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * R29 (`broadcaster`): a full local implementation of the public [Broadcaster] interface - the
 * upstream `SdkBroadcaster` is `internal`, and the interface's default `UnavailableBroadcaster`
 * throws on every call, which is not shippable (Zodl Android calls all three methods,
 * `ProposalDataSource.kt:240,248,322`). Own [SubmitPlanStore] (`KOTLIN_ROSETTA.md` section 3.5).
 *
 * **NOTE (section 3.5 / R29):** unlike the Swift twin's `broadcaster.submit` (which has no poke),
 * this implementation DOES poke [SlipstreamEngine.notifyTxChange] on every `submit` - a gap the
 * Swift adapter has that this one deliberately does not copy, since the engine is the only
 * `allTransactions` signal on Android (there is no separate event stream to fall back on).
 */
internal class SlipstreamBroadcaster(
    private val backend: Backend,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags,
    private val engine: SlipstreamEngine,
    private val planStore: SubmitPlanStore,
    private val saplingParamsDir: File,
    private val transactionReader: SlipstreamTransactionReader
) : Broadcaster {
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): List<CreatedTransaction> {
        SaplingParams.ensureDownloaded(saplingParamsDir)
        val txIds = backend.createProposedTransactions(proposal.toUnsafe(), usk.copyBytes())
        return txIds.map { txId -> storeAsAwaitingSubmission(FirstClassByteArray(txId)) }
    }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): List<CreatedTransaction> {
        val txId = backend.extractAndStoreTxFromPczt(pcztWithProofs.toByteArray(), pcztWithSignatures.toByteArray())
        return listOf(storeAsAwaitingSubmission(FirstClassByteArray(txId)))
    }

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ): TransactionSubmitResult {
        val client = walletClientFactory.create(endpoint)
        try {
            val result = client.submitTransaction(transaction.raw, transaction.txId, sdkFlags)
            planStore.recordSubmitEndpoint(transaction.txId, endpoint)
            engine.notifyTxChange()
            return result
        } finally {
            withContext(NonCancellable) { client.dispose() }
        }
    }

    private suspend fun storeAsAwaitingSubmission(txId: FirstClassByteArray): CreatedTransaction {
        planStore.markAwaitingSubmission(txId)
        return transactionReader.readCreatedTransaction(txId)
    }
}
