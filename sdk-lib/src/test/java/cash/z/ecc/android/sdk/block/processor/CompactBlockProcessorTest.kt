package cash.z.ecc.android.sdk.block.processor

import cash.z.ecc.android.sdk.block.processor.model.GetSubtreeRootsResult
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
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.ShieldedProtocolEnum
import co.electriccoin.lightwallet.client.model.SubtreeRootUnsafe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

            pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan {
                listOf(
                    CreatedTransaction(
                        txId = pendingPlanTransaction.rawId,
                        raw = FirstClassByteArray(byteArrayOf(0x01)),
                        expiryHeight = pendingPlanTransaction.expiryHeight
                    )
                )
            }
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

    @Test
    fun resubmission_does_not_prune_plan_created_after_candidate_list_lookup() {
        runBlocking {
            val createdTransaction = transactionOverview(1)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )
            val createCompleted = CompletableDeferred<Unit>()
            val testScope = this
            lateinit var createJob: Job

            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenAnswer {
                createJob =
                    testScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        pendingSubmitPlanStore.createAndMarkAwaitingSubmitPlan {
                            listOf(
                                CreatedTransaction(
                                    txId = createdTransaction.rawId,
                                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                                    expiryHeight = createdTransaction.expiryHeight
                                )
                            )
                        }
                        createCompleted.complete(Unit)
                    }

                assertFalse(createCompleted.isCompleted)
                emptyList<DbTransactionOverview>()
            }

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))
            createJob.join()

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan,
                pendingSubmitPlanStore.getSubmitPlan(createdTransaction.rawId)
            )
        }
    }

    @Test
    fun resubmission_pruning_keeps_view_invisible_unexpired_wallet_store_transaction() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(emptyList())
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenReturn(
                encodedTransaction(transaction.rawId, expiryHeight = BlockHeight(1000))
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.rawId)
            )
            verifyNoInteractions(txManager)
        }
    }

    @Test
    fun resubmission_pruning_removes_view_invisible_expired_wallet_store_transaction() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(emptyList())
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenReturn(
                encodedTransaction(transaction.rawId, expiryHeight = BlockHeight(50))
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            assertNull(pendingSubmitPlanStore.getSubmitPlan(transaction.rawId))
        }
    }

    @Test
    fun resubmission_pruning_keeps_plan_when_wallet_store_read_fails() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(emptyList())
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenThrow(
                RuntimeException("wallet store unavailable")
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.rawId)
            )
        }
    }

    @Test
    fun resubmission_pruning_removes_plan_for_transaction_absent_from_wallet_store() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(emptyList())
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenReturn(null)

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            assertNull(pendingSubmitPlanStore.getSubmitPlan(transaction.rawId))
        }
    }

    @Test
    fun resubmission_pruning_keeps_plan_when_expiry_is_disabled() {
        runBlocking {
            val transaction = transactionOverview(1)
            val endpoint = LightWalletEndpoint("submit.z.cash", 443, true)
            val repository = mock(DerivedDataRepository::class.java)
            val txManager = mock(OutboundTransactionManager::class.java)
            val pendingSubmitPlanStore = PendingSubmitPlanStore()
            val processor =
                processor(
                    repository = repository,
                    txManager = txManager,
                    pendingSubmitPlanStore = pendingSubmitPlanStore
                )

            pendingSubmitPlanStore.storeSubmitPlan(
                CreatedTransaction(
                    txId = transaction.rawId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = transaction.expiryHeight
                ),
                TransactionSubmitPlan(listOf(endpoint))
            )
            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(emptyList())
            `when`(repository.findEncodedTransactionByTxId(transaction.rawId)).thenReturn(
                encodedTransaction(transaction.rawId, expiryHeight = null)
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                pendingSubmitPlanStore.getSubmitPlan(transaction.rawId)
            )
        }
    }

    @Test
    fun resubmission_skips_transaction_whose_bytes_cannot_be_read() {
        runBlocking {
            val unreadableTransaction = transactionOverview(1)
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

            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(
                listOf(unreadableTransaction, resubmittableTransaction)
            )
            `when`(repository.findEncodedTransactionByTxId(unreadableTransaction.rawId)).thenReturn(null)
            `when`(repository.findEncodedTransactionByTxId(resubmittableTransaction.rawId)).thenReturn(
                resubmittableEncodedTransaction
            )
            `when`(txManager.submit(resubmittableEncodedTransaction)).thenReturn(
                TransactionSubmitResult.Success(resubmittableTransaction.rawId)
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            verify(txManager).submit(resubmittableEncodedTransaction)
        }
    }

    @Test
    fun resubmission_skips_transaction_when_wallet_store_read_throws() {
        runBlocking {
            val unreadableTransaction = transactionOverview(1)
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

            `when`(repository.findUnminedTransactionsWithinExpiry(BlockHeight(100))).thenReturn(
                listOf(unreadableTransaction, resubmittableTransaction)
            )
            `when`(repository.findEncodedTransactionByTxId(unreadableTransaction.rawId)).thenThrow(
                RuntimeException("wallet store unavailable")
            )
            `when`(repository.findEncodedTransactionByTxId(resubmittableTransaction.rawId)).thenReturn(
                resubmittableEncodedTransaction
            )
            `when`(txManager.submit(resubmittableEncodedTransaction)).thenReturn(
                TransactionSubmitResult.Success(resubmittableTransaction.rawId)
            )

            processor.resubmitUnminedTransactionsForTest(BlockHeight(100))

            verify(txManager).submit(resubmittableEncodedTransaction)
        }
    }

    @Test
    fun unknown_pool_failure_is_tolerated_without_retry() =
        runTest {
            val unknownPoolFailures =
                listOf(
                    unknownFailure(code = 2, description = "unrecognized shielded protocol"),
                    otherFailure(code = 3, description = "GetSubtreeRoots: bad shielded protocol specifier error: x"),
                    otherFailure(code = 12, description = null)
                )

            unknownPoolFailures.forEach { failure ->
                val downloader = mock(CompactBlockDownloader::class.java)
                val processor = subtreeRootsProcessor()

                stubSubtreeRoots(downloader, SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, successFlow())
                stubSubtreeRoots(downloader, ORCHARD_START_INDEX, ShieldedProtocolEnum.ORCHARD, successFlow())
                stubSubtreeRoots(downloader, IRONWOOD_START_INDEX, ShieldedProtocolEnum.IRONWOOD, flowOf(failure))

                val result =
                    processor.getSubtreeRoots(
                        downloader = downloader,
                        saplingStartIndex = SAPLING_START_INDEX,
                        orchardStartIndex = ORCHARD_START_INDEX,
                        ironwoodStartIndex = IRONWOOD_START_INDEX
                    )

                assertTrue(result is GetSubtreeRootsResult.SpendBeforeSync)
                assertTrue(result.ironwoodSubtreeRootList.isEmpty())
                verify(downloader, times(1))
                    .getSubtreeRoots(
                        IRONWOOD_START_INDEX,
                        ShieldedProtocolEnum.IRONWOOD,
                        UInt.MIN_VALUE,
                        ServiceMode.Direct
                    )
            }
        }

    @Test
    fun orchard_genuine_failure_is_not_masked_by_successful_sapling_fetch() =
        runTest {
            val downloader = mock(CompactBlockDownloader::class.java)
            val processor = subtreeRootsProcessor()

            stubSubtreeRoots(downloader, SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, successFlow())
            stubSubtreeRoots(
                downloader,
                ORCHARD_START_INDEX,
                ShieldedProtocolEnum.ORCHARD,
                flowOf(otherFailure(code = 13, description = "internal")),
                flowOf(otherFailure(code = 13, description = "internal")),
                flowOf(otherFailure(code = 13, description = "internal"))
            )
            stubSubtreeRoots(downloader, IRONWOOD_START_INDEX, ShieldedProtocolEnum.IRONWOOD, successFlow())

            val result =
                processor.getSubtreeRoots(
                    downloader = downloader,
                    saplingStartIndex = SAPLING_START_INDEX,
                    orchardStartIndex = ORCHARD_START_INDEX,
                    ironwoodStartIndex = IRONWOOD_START_INDEX
                )

            assertTrue(result is GetSubtreeRootsResult.OtherFailure)
            verify(downloader, times(3))
                .getSubtreeRoots(ORCHARD_START_INDEX, ShieldedProtocolEnum.ORCHARD, UInt.MIN_VALUE, ServiceMode.Direct)
        }

    @Test
    fun sapling_transient_failure_then_success_is_not_poisoned_by_first_attempt() =
        runTest {
            val downloader = mock(CompactBlockDownloader::class.java)
            val processor = subtreeRootsProcessor()

            stubSubtreeRoots(
                downloader,
                SAPLING_START_INDEX,
                ShieldedProtocolEnum.SAPLING,
                flowOf(otherFailure(code = 13, description = "temporary")),
                successFlow()
            )
            stubSubtreeRoots(downloader, ORCHARD_START_INDEX, ShieldedProtocolEnum.ORCHARD, successFlow())
            stubSubtreeRoots(downloader, IRONWOOD_START_INDEX, ShieldedProtocolEnum.IRONWOOD, successFlow())

            val result =
                processor.getSubtreeRoots(
                    downloader = downloader,
                    saplingStartIndex = SAPLING_START_INDEX,
                    orchardStartIndex = ORCHARD_START_INDEX,
                    ironwoodStartIndex = IRONWOOD_START_INDEX
                )

            assertTrue(result is GetSubtreeRootsResult.SpendBeforeSync)
            verify(downloader, times(2))
                .getSubtreeRoots(SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, UInt.MIN_VALUE, ServiceMode.Direct)
        }

    @Test
    fun empty_sapling_roots_yields_linear_result() =
        runTest {
            val downloader = mock(CompactBlockDownloader::class.java)
            val processor = subtreeRootsProcessor()

            stubSubtreeRoots(downloader, SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, emptyFlow())
            stubSubtreeRoots(downloader, ORCHARD_START_INDEX, ShieldedProtocolEnum.ORCHARD, successFlow())
            stubSubtreeRoots(downloader, IRONWOOD_START_INDEX, ShieldedProtocolEnum.IRONWOOD, successFlow())

            val result =
                processor.getSubtreeRoots(
                    downloader = downloader,
                    saplingStartIndex = SAPLING_START_INDEX,
                    orchardStartIndex = ORCHARD_START_INDEX,
                    ironwoodStartIndex = IRONWOOD_START_INDEX
                )

            assertEquals(GetSubtreeRootsResult.Linear, result)
        }

    @Test
    fun sapling_unavailable_on_all_attempts_yields_failure_connection() =
        runTest {
            val downloader = mock(CompactBlockDownloader::class.java)
            val processor = subtreeRootsProcessor()

            stubSubtreeRoots(
                downloader,
                SAPLING_START_INDEX,
                ShieldedProtocolEnum.SAPLING,
                flowOf(unavailableFailure(code = 14, description = "unavailable")),
                flowOf(unavailableFailure(code = 14, description = "unavailable")),
                flowOf(unavailableFailure(code = 14, description = "unavailable"))
            )
            stubSubtreeRoots(downloader, ORCHARD_START_INDEX, ShieldedProtocolEnum.ORCHARD, successFlow())
            stubSubtreeRoots(downloader, IRONWOOD_START_INDEX, ShieldedProtocolEnum.IRONWOOD, successFlow())

            val result =
                processor.getSubtreeRoots(
                    downloader = downloader,
                    saplingStartIndex = SAPLING_START_INDEX,
                    orchardStartIndex = ORCHARD_START_INDEX,
                    ironwoodStartIndex = IRONWOOD_START_INDEX
                )

            assertEquals(GetSubtreeRootsResult.FailureConnection, result)
            verify(downloader, times(3))
                .getSubtreeRoots(SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, UInt.MIN_VALUE, ServiceMode.Direct)
        }

    @Test
    fun connection_failure_takes_precedence_over_other_failure() =
        runTest {
            val downloader = mock(CompactBlockDownloader::class.java)
            val processor = subtreeRootsProcessor()

            stubSubtreeRoots(downloader, SAPLING_START_INDEX, ShieldedProtocolEnum.SAPLING, successFlow())
            stubSubtreeRoots(
                downloader,
                ORCHARD_START_INDEX,
                ShieldedProtocolEnum.ORCHARD,
                flowOf(unavailableFailure(code = 14, description = "unavailable")),
                flowOf(unavailableFailure(code = 14, description = "unavailable")),
                flowOf(unavailableFailure(code = 14, description = "unavailable"))
            )
            stubSubtreeRoots(
                downloader,
                IRONWOOD_START_INDEX,
                ShieldedProtocolEnum.IRONWOOD,
                flowOf(otherFailure(code = 13, description = "internal")),
                flowOf(otherFailure(code = 13, description = "internal")),
                flowOf(otherFailure(code = 13, description = "internal"))
            )

            val result =
                processor.getSubtreeRoots(
                    downloader = downloader,
                    saplingStartIndex = SAPLING_START_INDEX,
                    orchardStartIndex = ORCHARD_START_INDEX,
                    ironwoodStartIndex = IRONWOOD_START_INDEX
                )

            assertEquals(GetSubtreeRootsResult.FailureConnection, result)
        }

    private fun subtreeRootsProcessor(): CompactBlockProcessor =
        processor(
            repository = mock(DerivedDataRepository::class.java),
            txManager = mock(OutboundTransactionManager::class.java),
            pendingSubmitPlanStore = PendingSubmitPlanStore()
        )

    private fun successFlow(): Flow<Response<SubtreeRootUnsafe>> =
        flowOf(
            Response.Success(
                SubtreeRootUnsafe(
                    rootHash = byteArrayOf(0x01),
                    completingBlockHash = byteArrayOf(0x02),
                    completingBlockHeight = BlockHeightUnsafe(SUBTREE_ROOT_COMPLETING_HEIGHT)
                )
            )
        )

    private fun otherFailure(
        code: Int,
        description: String?
    ): Response.Failure.Server.Other<SubtreeRootUnsafe> =
        Response.Failure.Server.Other(
            cause = Exception(description ?: "failure"),
            code = code,
            description = description
        )

    private fun unavailableFailure(
        code: Int,
        description: String?
    ): Response.Failure.Server.Unavailable<SubtreeRootUnsafe> =
        Response.Failure.Server.Unavailable(
            cause = Exception(description ?: "failure"),
            code = code,
            description = description
        )

    private fun unknownFailure(
        code: Int,
        description: String?
    ): Response.Failure.Server.Unknown<SubtreeRootUnsafe> =
        Response.Failure.Server.Unknown(
            cause = Exception(description ?: "failure"),
            code = code,
            description = description
        )

    private suspend fun stubSubtreeRoots(
        downloader: CompactBlockDownloader,
        startIndex: UInt,
        shieldedProtocol: ShieldedProtocolEnum,
        vararg responses: Flow<Response<SubtreeRootUnsafe>>
    ) {
        `when`(
            downloader.getSubtreeRoots(
                startIndex = startIndex,
                shieldedProtocol = shieldedProtocol,
                maxEntries = UInt.MIN_VALUE,
                serviceMode = ServiceMode.Direct
            )
        ).thenReturn(responses[0], *responses.drop(1).toTypedArray())
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
                isExpiredUnmined = false,
                spentNoteCount = 1,
                poolCrossingValue = null,
                isTrusted = false,
                zip318Kind = Zip318Kind.NOT_CLASSIFIED
            )

        private fun encodedTransaction(
            txId: FirstClassByteArray,
            expiryHeight: BlockHeight? = BlockHeight(1000)
        ) = EncodedTransaction(
            txId = txId,
            raw = FirstClassByteArray(byteArrayOf(0x01, 0x02)),
            expiryHeight = expiryHeight
        )

        private const val SAPLING_START_INDEX: UInt = 10u
        private const val ORCHARD_START_INDEX: UInt = 20u
        private const val IRONWOOD_START_INDEX: UInt = 30u
        private const val SUBTREE_ROOT_COMPLETING_HEIGHT = 1_000_000L
    }
}
