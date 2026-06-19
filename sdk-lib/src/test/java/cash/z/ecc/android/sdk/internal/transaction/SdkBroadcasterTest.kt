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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.IOException
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
                    pendingSubmitPlanStore.loadTransactionsAndRetainSubmitPlans(
                        loadTransactions = { listOf(encodedTransaction.txId) },
                        transactionId = { it }
                    )
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
    fun sdk_legacy_proposed_transactions_register_submit_plan_with_endpoint() =
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
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )
        }

    @Test
    fun sdk_legacy_pczt_transactions_register_submit_plan_with_endpoint() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(10)
            val txManager = FakeOutboundTransactionManager(pcztTransaction = encodedTransaction)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val broadcaster = SdkBroadcaster(txManager, FakeTransactionSubmitter(), pendingSubmitPlanStore)

            val result =
                broadcaster
                    .createAndSubmitTransactionFromPczt(
                        Pczt(byteArrayOf(1)),
                        Pczt(byteArrayOf(2)),
                        endpoint
                    ).toList()

            assertEquals(listOf(TransactionSubmitResult.Success(encodedTransaction.txId)), result)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )
        }

    @Test
    fun sdk_legacy_proposed_transactions_block_retain_while_create_in_progress() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(11)
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
                    broadcaster
                        .createAndSubmitProposedTransactions(fakeProposal(), fakeUsk(), endpoint)
                        .toList()
                }

            createStarted.await()

            val retainCompleted = CompletableDeferred<Unit>()
            val retainJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    pendingSubmitPlanStore.loadTransactionsAndRetainSubmitPlans(
                        loadTransactions = { listOf(encodedTransaction.txId) },
                        transactionId = { it }
                    )
                    retainCompleted.complete(Unit)
                }

            assertFalse(retainCompleted.isCompleted)

            allowCreateToFinish.complete(Unit)

            createJob.await()
            retainCompleted.await()
            retainJob.join()
        }

    @Test
    fun sdk_legacy_proposed_transactions_stay_awaiting_plan_while_submit_in_progress() =
        runBlocking {
            val endpoint = LightWalletEndpoint("current.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(12)
            val submitStarted = CompletableDeferred<Unit>()
            val allowSubmitToFinish = CompletableDeferred<Unit>()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val txManager = FakeOutboundTransactionManager(proposedTransactions = listOf(encodedTransaction))
            val submitter =
                FakeTransactionSubmitter(
                    beforeReturning = {
                        submitStarted.complete(Unit)
                        allowSubmitToFinish.await()
                    }
                )
            val broadcaster = SdkBroadcaster(txManager, submitter, pendingSubmitPlanStore)

            val submitJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcaster
                        .createAndSubmitProposedTransactions(fakeProposal(), fakeUsk(), endpoint)
                        .toList()
                }

            submitStarted.await()

            // While submit is in flight, plan must still be AwaitingPlan so the sync loop's
            // resubmitUnminedTransactions step skips it instead of racing the in-flight broadcast.
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan,
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )

            allowSubmitToFinish.complete(Unit)
            submitJob.await()

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(encodedTransaction.txId)
            )
        }

    @Test
    fun broadcaster_submit_stays_awaiting_plan_while_submit_in_progress() =
        runBlocking {
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(13)
            val submitStarted = CompletableDeferred<Unit>()
            val allowSubmitToFinish = CompletableDeferred<Unit>()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val submitter =
                FakeTransactionSubmitter(
                    beforeReturning = {
                        submitStarted.complete(Unit)
                        allowSubmitToFinish.await()
                    }
                )
            val broadcaster = SdkBroadcaster(FakeOutboundTransactionManager(), submitter, pendingSubmitPlanStore)
            val transaction = encodedTransaction.toCreatedTransactionForTest()

            // The public Broadcaster.submit expects createProposedTransactions has already
            // registered the txid as AwaitingPlan. Simulate that here.
            pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan { listOf(transaction) }

            val submitJob =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcaster.submit(transaction, endpoint)
                }

            submitStarted.await()

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan,
                pendingSubmitPlanStore.getSubmitPlan(transaction.txId)
            )

            allowSubmitToFinish.complete(Unit)
            submitJob.await()

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun broadcaster_submit_records_endpoint_even_if_cancelled_mid_submit() =
        runBlocking {
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val encodedTransaction = encodedTransaction(14)
            val submitStarted = CompletableDeferred<Unit>()
            val neverFinish = CompletableDeferred<Unit>()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val submitter =
                FakeTransactionSubmitter(
                    beforeReturning = {
                        submitStarted.complete(Unit)
                        neverFinish.await()
                    }
                )
            val broadcaster = SdkBroadcaster(FakeOutboundTransactionManager(), submitter, pendingSubmitPlanStore)
            val transaction = encodedTransaction.toCreatedTransactionForTest()
            pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan { listOf(transaction) }

            val submitJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    broadcaster.submit(transaction, endpoint)
                }

            submitStarted.await()
            submitJob.cancelAndJoin()

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun broadcaster_submit_records_endpoint_even_if_submit_throws_non_cancellation() =
        runBlocking {
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val transaction = encodedTransaction(15).toCreatedTransactionForTest()
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val boom = IOException("simulated network failure")
            val submitter = FakeTransactionSubmitter(beforeReturning = { throw boom })
            val broadcaster = SdkBroadcaster(FakeOutboundTransactionManager(), submitter, pendingSubmitPlanStore)
            pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan { listOf(transaction) }

            val thrown =
                runCatching { broadcaster.submit(transaction, endpoint) }.exceptionOrNull()

            assertEquals(boom, thrown)
            // The endpoint must be recorded so the resubmit loop retries through SubmitPlanExecutor.
            // Leaving the plan at AwaitingPlan would strand the tx — the resubmit loop skips
            // AwaitingPlan entries on the assumption they were never submitted from this session.
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.txId)
            )
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
        },
        private val beforeReturning: suspend () -> Unit = {}
    ) : TransactionSubmitter {
        val submissions = mutableListOf<Submission>()

        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint
        ): TransactionSubmitResult {
            submissions += Submission(transaction, endpoint)
            beforeReturning()
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
