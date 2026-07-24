@file:Suppress("MaxLineLength")

package com.zodl.slipstream.internal.spend

import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.CombinedWalletClient
import com.zodl.slipstream.internal.SlipstreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The R25-R28/R30-R34/R17 send + PCZT delegation over the *published* backend artifact
 * (`SDK_ADAPTER_PLAN.md` T8; DECISIONS.md D6: the engine is never in this path except the
 * post-store poke). Every proof/propose/create call is a one-hop delegation to [Backend]/
 * `RustBackend` - keys, proofs, and the mempool round-trip never touch `SlipstreamNative`.
 *
 * Threading residual (MASTER_SPEC R2's two-lane model, `KOTLIN_ROSETTA.md` section 0.3): every
 * `backend.*` call here rides `sdk-lib`'s own `SdkDispatchers.DATABASE_IO` ("zc-io") internally;
 * every `SlipstreamNative` call the poll loop makes rides `SlipstreamDispatchers.SLIPSTREAM_IO`
 * ("slipstream-io"). The two lanes are never the same thread and never share a mutex - DB safety
 * between them is WAL + `busy_timeout` (DECISIONS.md D5), not thread exclusion. This means a spend
 * call and a poll tick CAN interleave their SQLite transactions; the residual window this leaves
 * open is the same one the upstream SDK itself accepts between its own `zc-io` writer and any
 * third-party reader - this class adds no new exposure beyond what D5's WAL/busy_timeout pact
 * already covers.
 */
internal class SlipstreamSpendService(
    private val backend: Backend,
    private val walletClient: CombinedWalletClient,
    private val engine: SlipstreamEngine,
    private val sdkFlags: SdkFlags,
    /** Production: `{ SaplingParams.ensureDownloaded(dir) }`. Injected so tests never trigger a real download. */
    private val ensureSaplingParams: suspend () -> Unit,
    private val readRawTransaction: suspend (FirstClassByteArray) -> FirstClassByteArray
) {
    suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal = Proposal.fromUnsafe(backend.proposeTransfer(account.accountUuid.value, recipient, amount.value, memo.toMemoBytesOrNull()))

    suspend fun proposeFulfillingPaymentUri(
        account: Account,
        uri: String
    ): Proposal = Proposal.fromUnsafe(backend.proposeTransferFromUri(account.accountUuid.value, uri))

    suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal? =
        backend
            .proposeShielding(account.accountUuid.value, shieldingThreshold.value, memo.toMemoBytesOrNull(), transparentReceiver)
            ?.let(Proposal::fromUnsafe)

    /**
     * Store-first (S-SPEND): `create` already wrote the transactions into `data.db`, so the FIRST
     * poke fires BEFORE any broadcast; the second poke covers whatever the submit fallback itself
     * stored (mined/expiry updates).
     */
    fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): Flow<TransactionSubmitResult> =
        flow {
            ensureSaplingParams()
            val txIds = backend.createProposedTransactions(proposal.toUnsafe(), usk.copyBytes())
            engine.notifyTxChange()
            for (txId in txIds) {
                val txIdBytes = FirstClassByteArray(txId)
                emit(walletClient.submitTransaction(readRawTransaction(txIdBytes), txIdBytes, sdkFlags))
            }
            engine.notifyTxChange()
        }

    suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt = Pczt(backend.createPcztFromProposal(accountUuid.value, proposal.toUnsafe()))

    suspend fun redactPcztForSigner(pczt: Pczt): Pczt = Pczt(backend.redactPcztForSigner(pczt.toByteArray()))

    suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean = backend.pcztRequiresSaplingProofs(pczt.toByteArray())

    suspend fun addProofsToPczt(pczt: Pczt): Pczt {
        ensureSaplingParams()
        return withContext(Dispatchers.Default) {
            Pczt(backend.addProofsToPczt(pczt.toByteArray()))
        }
    }

    fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): Flow<TransactionSubmitResult> =
        flow {
            val txId = backend.extractAndStoreTxFromPczt(pcztWithProofs.toByteArray(), pcztWithSignatures.toByteArray())
            engine.notifyTxChange()
            val txIdBytes = FirstClassByteArray(txId)
            emit(walletClient.submitTransaction(readRawTransaction(txIdBytes), txIdBytes, sdkFlags))
            engine.notifyTxChange()
        }

    suspend fun getTransparentBalance(tAddr: String): Zatoshi = Zatoshi(backend.getTotalTransparentBalance(tAddr))
}

private fun String.toMemoBytesOrNull(): ByteArray? = takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
