package cash.z.ecc.android.sdk.block.processor

import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.PendingSubmitPlanStore
import cash.z.ecc.android.sdk.internal.transaction.SubmitPlanExecutor
import cash.z.ecc.android.sdk.internal.transaction.TransactionSubmitPlan
import cash.z.ecc.android.sdk.internal.transaction.TransactionSubmitter
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactBlockProcessorTest {
    @Test
    fun should_refresh_preparation_test() {
        assertTrue {
            CompactBlockProcessor.shouldRefreshPreparation(
                lastPreparationTime = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT,
                currentTimeMillis = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT * 2,
                limitTime = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT
            )
        }
    }

    @Test
    fun should_not_refresh_preparation_test() {
        assertFalse {
            CompactBlockProcessor.shouldRefreshPreparation(
                lastPreparationTime = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT,
                currentTimeMillis = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT,
                limitTime = CompactBlockProcessor.SYNCHRONIZATION_RESTART_TIMEOUT
            )
        }
    }

    @Test
    fun resubmission_skips_broadcaster_transactions_until_submit_plan_is_registered() {
        runBlocking {
            val pendingPlanTransaction = transactionOverview(1)
            val resubmittableTransaction = transactionOverview(2)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val resubmittableEncodedTransaction = encodedTransaction(resubmittableTransaction.rawId)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.markAwaitingSubmitPlan(
                listOf(
                    CreatedTransaction(
                        txId = pendingPlanTransaction.rawId,
                        raw = FirstClassByteArray(byteArrayOf(0x01)),
                        expiryHeight = pendingPlanTransaction.expiryHeight
                    )
                )
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(
                listOf(pendingPlanTransaction, resubmittableTransaction)
            )
            `when`(repository.findEncodedTransactionByTxId(resubmittableTransaction.rawId)).thenReturn(
                resubmittableEncodedTransaction
            )
            `when`(txManager.submit(resubmittableEncodedTransaction)).thenReturn(
                TransactionSubmitResult.Success(resubmittableTransaction.rawId)
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            verify(repository, never()).findEncodedTransactionByTxId(pendingPlanTransaction.rawId)
            verify(txManager).submit(resubmittableEncodedTransaction)
        }
    }

    @Test
    fun resubmission_uses_registered_submit_plan() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val encodedTransaction = encodedTransaction(transaction.rawId)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val submitter = FakeTransactionSubmitter()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore,
                    submitter = submitter
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(listOf(transaction))
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenReturn(encodedTransaction)

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            verify(txManager, never()).submit(encodedTransaction)
            assertTrue(submitter.submissions.contains(Submission(encodedTransaction.txId, endpoint)))
        }
    }

    private suspend fun CompactBlockProcessor.resubmitUnminedTransactionsForTest(blockHeight: BlockHeight) {
        val function =
            CompactBlockProcessor::class
                .declaredMemberFunctions
                .single { it.name == "resubmitUnminedTransactions" }

        function.isAccessible = true
        function.callSuspend(this, blockHeight)
    }

    private fun processor(
        repository: DerivedDataRepository,
        txManager: OutboundTransactionManager,
        pendingSubmitPlanStore: PendingSubmitPlanStore,
        submitter: TransactionSubmitter = FakeTransactionSubmitter()
    ): CompactBlockProcessor {
        val backend = mock(TypesafeBackend::class.java)
        `when`(backend.network).thenReturn(ZcashNetwork.Testnet)

        return CompactBlockProcessor(
            backend = backend,
            downloader = mock(CompactBlockDownloader::class.java),
            minimumHeight = ZcashNetwork.Testnet.saplingActivationHeight,
            repository = repository,
            txManager = txManager,
            sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
            saplingParamFetcher = mock(SaplingParamFetcher::class.java),
            pendingSubmitPlanStore = pendingSubmitPlanStore,
            submitPlanExecutor = SubmitPlanExecutor(submitter)
        )
    }

    private class FakeTransactionSubmitter : TransactionSubmitter {
        val submissions = mutableListOf<Submission>()

        override suspend fun submit(
            transaction: CreatedTransaction,
            endpoint: LightWalletEndpoint
        ): TransactionSubmitResult {
            submissions += Submission(transaction.txId, endpoint)
            return TransactionSubmitResult.Success(transaction.txId)
        }
    }

    private data class Submission(
        val txId: FirstClassByteArray,
        val endpoint: LightWalletEndpoint
    )

    companion object {
        private fun transactionOverview(index: Int) =
            DbTransactionOverview(
                rawId = FirstClassByteArray(byteArrayOf(index.toByte())),
                minedHeight = null,
                expiryHeight = BlockHeight(1000),
                index = null,
                raw = FirstClassByteArray(byteArrayOf(index.toByte(), index.toByte())),
                isSentTransaction = true,
                netValue = Zatoshi(0),
                totalSpent = Zatoshi(0),
                totalReceived = Zatoshi(0),
                feePaid = null,
                isChange = false,
                receivedNoteCount = 0,
                sentNoteCount = 1,
                memoCount = 0,
                blockTimeEpochSeconds = null,
                isShielding = false,
                isExpiredUnmined = false
            )

        private fun encodedTransaction(txId: FirstClassByteArray) =
            EncodedTransaction(
                txId = txId,
                raw = FirstClassByteArray(byteArrayOf(0x01, 0x02)),
                expiryHeight = BlockHeight(1000)
            )
    }
}
