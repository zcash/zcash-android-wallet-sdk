package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.fromHexReversed
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingSubmitPlanStoreTest {
    @Test
    fun created_transactions_wait_for_submit_plan() =
        runBlocking {
            val transaction = createdTransaction(1)
            val store = PendingSubmitPlanStore()

            store.createAndMarkAwaitingSubmitPlan {
                listOf(transaction)
            }

            assertEquals(PendingSubmitPlanStore.StoredSubmitPlan.AwaitingPlan, store.getSubmitPlan(transaction.txId))
            assertNull(store.getSubmitPlan(createdTransaction(2).txId))
        }

    @Test
    fun persists_submit_plans() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val endpoint = endpoint("submit.z.cash")
            val firstStore = PendingSubmitPlanStore(preferenceProvider)

            firstStore.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpoint)))

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint))),
                secondStore.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun ignores_submit_plans_with_unsupported_version() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            preferenceProvider.putString(
                EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
                "version=2\n${transaction.txId.byteArray.toHexReversed()}=submit.z.cash,443,true"
            )

            val store = PendingSubmitPlanStore(preferenceProvider)

            assertNull(store.getSubmitPlan(transaction.txId))
        }

    @Test
    fun submitted_endpoints_are_added_to_existing_plan() =
        runBlocking {
            val transaction = createdTransaction(1)
            val firstEndpoint = endpoint("a.z.cash")
            val secondEndpoint = endpoint("b.z.cash")
            val store = PendingSubmitPlanStore()

            store.createAndMarkAwaitingSubmitPlan {
                listOf(transaction)
            }
            store.addSubmitEndpoint(transaction, firstEndpoint)
            store.addSubmitEndpoint(transaction, secondEndpoint)

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(
                    TransactionSubmitPlan(listOf(firstEndpoint, secondEndpoint))
                ),
                store.getSubmitPlan(transaction.txId)
            )
        }

    @Test
    fun prunes_plans_that_are_no_longer_resubmission_candidates() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val retainedTransaction = createdTransaction(1)
            val prunedTransaction = createdTransaction(2)
            val firstStore = PendingSubmitPlanStore(preferenceProvider)

            firstStore.storeSubmitPlan(retainedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            firstStore.storeSubmitPlan(prunedTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))
            firstStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { listOf(retainedTransaction.txId) },
                transactionId = { it }
            )

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                secondStore.getSubmitPlan(retainedTransaction.txId)
            )
            assertNull(secondStore.getSubmitPlan(prunedTransaction.txId))
        }

    @Test
    fun default_retain_missing_prunes_missing_plan() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val prunedTransaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.storeSubmitPlan(prunedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it }
            )

            assertNull(store.getSubmitPlan(prunedTransaction.txId))
        }

    @Test
    fun retain_missing_returning_true_retains_plan_for_missing_transaction() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val retainedTransaction = createdTransaction(1)
            val store = PendingSubmitPlanStore(preferenceProvider)
            store.storeSubmitPlan(retainedTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            store.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { true }
            )

            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("a.z.cash")))),
                store.getSubmitPlan(retainedTransaction.txId)
            )
        }

    @Test
    fun retain_missing_callback_receives_original_txid_bytes_after_rehydration() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val txId = FirstClassByteArray(byteArrayOf(0x01, 0x02, 0x03, 0xAB.toByte(), 0x00))
            val transaction =
                CreatedTransaction(
                    txId = txId,
                    raw = FirstClassByteArray(byteArrayOf(0x01)),
                    expiryHeight = null
                )
            val firstStore = PendingSubmitPlanStore(preferenceProvider)
            firstStore.storeSubmitPlan(transaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))

            val secondStore = PendingSubmitPlanStore(preferenceProvider)
            var receivedTxId: FirstClassByteArray? = null
            secondStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { candidateTxId ->
                    receivedTxId = candidateTxId
                    true
                }
            )

            assertContentEquals(txId.byteArray, receivedTxId?.byteArray)
        }

    @Test
    fun namespaced_store_only_consults_retain_missing_for_its_own_keys() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val ownTransaction = createdTransaction(1)
            val otherNamespaceTransaction = createdTransaction(2)
            val ownStore = PendingSubmitPlanStore(preferenceProvider, namespace = "own")
            val otherStore = PendingSubmitPlanStore(preferenceProvider, namespace = "other")

            ownStore.storeSubmitPlan(ownTransaction, TransactionSubmitPlan(listOf(endpoint("a.z.cash"))))
            otherStore.storeSubmitPlan(otherNamespaceTransaction, TransactionSubmitPlan(listOf(endpoint("b.z.cash"))))

            val consultedTxIds = mutableListOf<FirstClassByteArray>()
            val reloadedOwnStore = PendingSubmitPlanStore(preferenceProvider, namespace = "own")
            reloadedOwnStore.loadTransactionsAndRetainSubmitPlans(
                loadTransactions = { emptyList<FirstClassByteArray>() },
                transactionId = { it },
                retainMissing = { txId ->
                    consultedTxIds += txId
                    true
                }
            )

            assertEquals(listOf(ownTransaction.txId), consultedTxIds)

            val otherStoreReloaded = PendingSubmitPlanStore(preferenceProvider, namespace = "other")
            assertEquals(
                PendingSubmitPlanStore.StoredSubmitPlan.Ready(TransactionSubmitPlan(listOf(endpoint("b.z.cash")))),
                otherStoreReloaded.getSubmitPlan(otherNamespaceTransaction.txId)
            )
        }

    @Test
    fun to_hex_reversed_and_from_hex_reversed_round_trip() {
        val bytes = Random(42).nextBytes(37)

        assertContentEquals(bytes, bytes.toHexReversed().fromHexReversed())
    }

    private class FakePreferenceProvider : PreferenceProvider {
        private val values = mutableMapOf<String, String?>()

        override suspend fun hasKey(key: PreferenceKey): Boolean = values.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            values[key.key] = value
        }

        override suspend fun getString(key: PreferenceKey): String? = values[key.key]

        override fun observe(key: PreferenceKey): Flow<Unit> = emptyFlow()

        override suspend fun clearPreferences(): Boolean {
            values.clear()
            return true
        }
    }

    companion object {
        private fun createdTransaction(index: Int) =
            CreatedTransaction(
                txId = FirstClassByteArray(byteArrayOf(index.toByte())),
                raw = FirstClassByteArray(byteArrayOf(index.toByte(), index.toByte())),
                expiryHeight = null
            )

        private fun endpoint(host: String) = LightWalletEndpoint(host, 443, true)
    }
}
