package cash.z.ecc.android.sdk.block.processor

import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.internal.model.EncodedTransaction
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.transaction.AutomaticResubmissionGuard
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
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
    fun resubmission_skips_caller_managed_transactions() {
        runBlocking {
            val excludedTransaction = transactionOverview(1)
            val resubmittableTransaction = transactionOverview(2)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val resubmittableEncodedTransaction = encodedTransaction(resubmittableTransaction.rawId)
            val automaticResubmissionGuard = AutomaticResubmissionGuard()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    automaticResubmissionGuard = automaticResubmissionGuard
                )

            automaticResubmissionGuard.excludeFromAutomaticResubmission(
                CreatedTransaction(
                    txId = excludedTransaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = excludedTransaction.expiryHeight
                )
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(
                listOf(excludedTransaction, resubmittableTransaction)
            )
            `when`(repository.findEncodedTransactionByTxId(resubmittableTransaction.rawId)).thenReturn(
                resubmittableEncodedTransaction
            )
            `when`(txManager.submit(resubmittableEncodedTransaction)).thenReturn(
                TransactionSubmitResult.Success(resubmittableTransaction.rawId)
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            verify(repository, never()).findEncodedTransactionByTxId(excludedTransaction.rawId)
            verify(txManager).submit(resubmittableEncodedTransaction)
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
        automaticResubmissionGuard: AutomaticResubmissionGuard
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
            automaticResubmissionGuard = automaticResubmissionGuard
        )
    }

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
