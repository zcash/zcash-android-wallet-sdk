package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdkBroadcasterTest {
    @Test
    fun create_proposed_transactions_does_not_submit() =
        runBlocking {
            val encodedTransaction = encodedTransaction(1)
            val txManager = FakeOutboundTransactionManager(proposedTransactions = listOf(encodedTransaction))
            val submitter = FakeTransactionSubmitter()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val broadcaster = SdkBroadcaster(txManager, submitter, pendingSubmitPlanStore)

            val result = broadcaster.createProposedTransactions(fakeProposal(), fakeUsk())

            assertEquals(listOf(encodedTransaction.toCreatedTransactionForTest()), result)
            assertEquals(1, txManager.proposedTransactionCreateCount)
            assertTrue(submitter.submissions.isEmpty())
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan,
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )
        }

    @Test
    fun create_proposed_transactions_registers_pending_plan_before_resubmission_reads_store() =
        runBlocking {
            val encodedTransaction = encodedTransaction(9)
            val createStarted = CompletableDeferred<Unit>()
            val allowCreateToFinish = CompletableDeferred<Unit>()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val txManager =
                FakeOutboundTransactionManager(
                    proposedTransactions = listOf(encodedTransaction),
                    beforeReturningProposedTransactions = {
                        createStarted.complete(Unit)
                        allowCreateToFinish.await()
                    }
                )
            val broadcaster = SdkBroadcaster(txManager, FakeTransactionSubmitter(), pendingSubmitPlanStore)
            val createJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcaster.createProposedTransactions(fakeProposal(), fakeUsk())
                }

            createStarted.await()

            val retainCompleted = CompletableDeferred<Unit>()
            val retainJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    pendingSubmitPlanStore.retainPlansFor(listOf(encodedTransaction.txId))
                    retainCompleted.complete(Unit)
                }

            assertFalse(retainCompleted.isCompleted)

            allowCreateToFinish.complete(Unit)

            assertEquals(listOf(encodedTransaction.toCreatedTransactionForTest()), createJob.await())
            retainCompleted.await()
            retainJob.join()
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan,
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )
        }

    @Test
    fun submit_targets_requested_endpoint() =
        runBlocking {
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val submitter = FakeTransactionSubmitter()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val broadcaster = SdkBroadcaster(FakeOutboundTransactionManager(), submitter, pendingSubmitPlanStore)
            val transaction = encodedTransaction(2).toCreatedTransactionForTest()

            val result = broadcaster.submit(transaction, endpoint)

            assertEquals(TransactionSubmitResult.Success(transaction.txId), result)
            assertEquals(listOf(Submission(transaction, endpoint)), submitter.submissions)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun create_transaction_from_pczt_does_not_submit() =
        runBlocking {
            val encodedTransaction = encodedTransaction(3)
            val txManager = FakeOutboundTransactionManager(pcztTransaction = encodedTransaction)
            val submitter = FakeTransactionSubmitter()
            val broadcaster = SdkBroadcaster(txManager, submitter, PendingSubmitPlanStore())

            val result = broadcaster.createTransactionFromPczt(Pczt(byteArrayOf(1)), Pczt(byteArrayOf(2)))

            assertEquals(listOf(encodedTransaction.toCreatedTransactionForTest()), result)
            assertEquals(1, txManager.pcztCreateCount)
            assertTrue(submitter.submissions.isEmpty())
        }

    @Test
    fun legacy_proposed_transactions_submit_once_to_current_endpoint() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val transaction = encodedTransaction(4).toCreatedTransactionForTest()
            val broadcaster = FakeBroadcaster(createdTransactions = listOf(transaction))

            val result =
                broadcaster
                    .createAndSubmitProposedTransactions(fakeProposal(), fakeUsk(), endpoint)
                    .toList()

            assertEquals(listOf(TransactionSubmitResult.Success(transaction.txId)), result)
            assertEquals(1, broadcaster.proposedTransactionCreateCount)
            assertEquals(listOf(Submission(transaction, endpoint)), broadcaster.submissions)
        }

    @Test
    fun sdk_legacy_proposed_transactions_do_not_register_submit_plans() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(8)
            val txManager = FakeOutboundTransactionManager(proposedTransactions = listOf(encodedTransaction))
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val broadcaster = SdkBroadcaster(txManager, FakeTransactionSubmitter(), pendingSubmitPlanStore)

            val result =
                broadcaster
                    .createAndSubmitProposedTransactions(fakeProposal(), fakeUsk(), endpoint)
                    .toList()

            assertEquals(listOf(TransactionSubmitResult.Success(encodedTransaction.txId)), result)
            assertEquals(null, pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId))
        }

    @Test
    fun legacy_proposed_transactions_stop_submitting_after_first_failure() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val firstTransaction = encodedTransaction(5).toCreatedTransactionForTest()
            val secondTransaction = encodedTransaction(6).toCreatedTransactionForTest()
            val firstFailure =
                TransactionSubmitResult.Failure(
                    txId = firstTransaction.txId,
                    grpcError = false,
                    code = -1,
                    description = "rejected"
                )
            val broadcaster =
                FakeBroadcaster(
                    createdTransactions = listOf(firstTransaction, secondTransaction),
                    submitResults = listOf(firstFailure)
                )

            val result =
                broadcaster
                    .createAndSubmitProposedTransactions(fakeProposal(), fakeUsk(), endpoint)
                    .toList()

            assertEquals(
                listOf(firstFailure, TransactionSubmitResult.NotAttempted(secondTransaction.txId)),
                result
            )
            assertEquals(listOf(Submission(firstTransaction, endpoint)), broadcaster.submissions)
        }

    @Test
    fun legacy_pczt_submits_once_to_current_endpoint() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val transaction = encodedTransaction(7).toCreatedTransactionForTest()
            val broadcaster = FakeBroadcaster(pcztTransactions = listOf(transaction))

            val result =
                broadcaster
                    .createAndSubmitTransactionFromPczt(Pczt(byteArrayOf(1)), Pczt(byteArrayOf(2)), endpoint)
                    .toList()

            assertEquals(listOf(TransactionSubmitResult.Success(transaction.txId)), result)
            assertEquals(1, broadcaster.pcztCreateCount)
            assertEquals(listOf(Submission(transaction, endpoint)), broadcaster.submissions)
        }

    private class FakeOutboundTransactionManager(
        private val proposedTransactions: List<EncodedTransaction> = emptyList(),
        private val pcztTransaction: EncodedTransaction = encodedTransaction(99),
        private val beforeReturningProposedTransactions: suspend () -> Unit = {}
    ) : OutboundTransactionManager {
        var proposedTransactionCreateCount = 0
        var pcztCreateCount = 0

        override suspend fun proposeTransferFromUri(
            account: Account,
            uri: String
        ): Proposal = error("Unused")

        override suspend fun proposeTransfer(
            account: Account,
            recipient: String,
            amount: Zatoshi,
            memo: String
        ): Proposal = error("Unused")

        override suspend fun proposeShielding(
            account: Account,
            shieldingThreshold: Zatoshi,
            memo: String,
            transparentReceiver: String?
        ): Proposal? = error("Unused")

        override suspend fun createProposedTransactions(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): List<EncodedTransaction> {
            proposedTransactionCreateCount += 1
            beforeReturningProposedTransactions()
            return proposedTransactions
        }

        override suspend fun submit(encodedTransaction: EncodedTransaction): TransactionSubmitResult = error("Unused")

        override suspend fun createPcztFromProposal(
            accountUuid: AccountUuid,
            proposal: Proposal
        ): Pczt = error("Unused")

        override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("Unused")

        override suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean = error("Unused")

        override suspend fun addProofsToPczt(pczt: Pczt): Pczt = error("Unused")

        override suspend fun extractAndStoreTxFromPczt(
            pcztWithProofs: Pczt,
            pcztWithSignatures: Pczt
        ): EncodedTransaction {
            pcztCreateCount += 1
            return pcztTransaction
        }

        override suspend fun isValidShieldedAddress(address: String): Boolean = error("Unused")

        override suspend fun isValidTransparentAddress(address: String): Boolean = error("Unused")

        override suspend fun isValidUnifiedAddress(address: String): Boolean = error("Unused")

        override suspend fun isValidTexAddress(address: String): Boolean = error("Unused")
    }

    private class FakeTransactionSubmitter(
        private val resultFactory: (CreatedTransaction) -> TransactionSubmitResult = {
            TransactionSubmitResult.Success(it.txId)
        }
    ) : TransactionSubmitter {
        val submissions = mutableListOf<Submission>()

        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint
        ): TransactionSubmitResult {
            submissions += Submission(transaction, endpoint)
            return resultFactory(transaction)
        }
    }

    private class FakeBroadcaster(
        private val createdTransactions: List<CreatedTransaction> = emptyList(),
        private val pcztTransactions: List<CreatedTransaction> = emptyList(),
        private val submitResults: List<TransactionSubmitResult> = emptyList()
    ) : Broadcaster {
        var proposedTransactionCreateCount = 0
        var pcztCreateCount = 0
        val submissions = mutableListOf<Submission>()

        override suspend fun createProposedTransactions(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): List<CreatedTransaction> {
            proposedTransactionCreateCount += 1
            return createdTransactions
        }

        override suspend fun createTransactionFromPczt(
            pcztWithProofs: Pczt,
            pcztWithSignatures: Pczt
        ): List<CreatedTransaction> {
            pcztCreateCount += 1
            return pcztTransactions
        }

        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint
        ): TransactionSubmitResult {
            submissions += Submission(transaction, endpoint)
            return submitResults.getOrNull(submissions.lastIndex) ?: TransactionSubmitResult.Success(transaction.txId)
        }
    }

    private data class Submission(
        val transaction: CreatedTransaction,
        val endpoint: LightWalletEndpoint
    )

    companion object {
        private fun encodedTransaction(index: Int) =
            EncodedTransaction(
                txId = FirstClassByteArray(byteArrayOf(index.toByte())),
                raw = FirstClassByteArray(byteArrayOf(index.toByte(), index.toByte())),
                expiryHeight = BlockHeight.new(index.toLong())
            )

        private fun EncodedTransaction.toCreatedTransactionForTest() =
            CreatedTransaction(
                txId = txId,
                raw = raw,
                expiryHeight = expiryHeight
            )

        private fun fakeProposal(): Proposal = mock(Proposal::class.java)

        private fun fakeUsk(): UnifiedSpendingKey = mock(UnifiedSpendingKey::class.java)
    }
}
