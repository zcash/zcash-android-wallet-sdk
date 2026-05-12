package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PendingSubmitPlanStore(
    private val preferenceProvider: PreferenceProvider? = null,
    private val namespace: String = DEFAULT_NAMESPACE
) {
    private val mutex = Mutex()
    private val namespacePrefix = namespace.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()
    private val plansByTransactionId = mutableMapOf<String, List<LightWalletEndpoint>>()
    private var loadedFromPreferences = false

    suspend fun markAwaitingSubmitPlan(transactions: List<CreatedTransaction>) {
        val transactionIds = transactions.map { it.txId.toStableKey() }
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            var changed = false
            transactionIds.forEach { transactionId ->
                if (!plansByTransactionId.containsKey(transactionId)) {
                    plansByTransactionId[transactionId] = emptyList()
                    changed = true
                }
            }
            if (changed) {
                saveToPreferences()
            }
        }
    }

    suspend fun storeSubmitPlan(
        transaction: CreatedTransaction,
        submitPlan: TransactionSubmitPlan
    ) {
        storeSubmitPlan(transaction.txId, submitPlan.endpoints)
    }

    suspend fun addSubmitEndpoint(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = transaction.txId.toStableKey()
            val updatedEndpoints =
                (plansByTransactionId[transactionId].orEmpty() + endpoint)
                    .distinct()
            if (plansByTransactionId[transactionId] != updatedEndpoints) {
                plansByTransactionId[transactionId] = updatedEndpoints
                saveToPreferences()
            }
        }
    }

    suspend fun getSubmitPlan(txId: FirstClassByteArray): StoredSubmitPlan? =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            when (val endpoints = plansByTransactionId[txId.toStableKey()]) {
                null -> null
                emptyList<LightWalletEndpoint>() -> StoredSubmitPlan.AwaitingPlan
                else -> StoredSubmitPlan.Ready(TransactionSubmitPlan(endpoints))
            }
        }

    suspend fun retainPlansFor(txIds: List<FirstClassByteArray>) {
        val retainedTransactionIds = txIds.map { it.toStableKey() }.toSet()
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            if (plansByTransactionId.keys.removeAll { it.isInNamespace() && it !in retainedTransactionIds }) {
                saveToPreferences()
            }
        }
    }

    private suspend fun storeSubmitPlan(
        txId: FirstClassByteArray,
        endpoints: List<LightWalletEndpoint>
    ) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            val transactionId = txId.toStableKey()
            val normalizedEndpoints = endpoints.distinct()
            if (plansByTransactionId[transactionId] != normalizedEndpoints) {
                plansByTransactionId[transactionId] = normalizedEndpoints
                saveToPreferences()
            }
        }
    }

    private suspend fun loadFromPreferencesIfNeeded() {
        if (loadedFromPreferences) {
            return
        }

        val storedPlans =
            preferenceProvider
                ?.getString(EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key)
                .orEmpty()

        if (storedPlans.isNotBlank()) {
            plansByTransactionId.putAll(PendingSubmitPlanCodec.decode(storedPlans))
        }
        loadedFromPreferences = true
    }

    private suspend fun saveToPreferences() {
        preferenceProvider?.putString(
            EncryptedPreferenceKeys.PENDING_SUBMIT_PLANS.key,
            PendingSubmitPlanCodec.encode(plansByTransactionId)
        )
    }

    private fun FirstClassByteArray.toStableKey() = namespacePrefix + byteArray.toHexReversed()

    private fun String.isInNamespace() = namespacePrefix.isBlank() || startsWith(namespacePrefix)

    sealed interface StoredSubmitPlan {
        data object AwaitingPlan : StoredSubmitPlan

        data class Ready(
            val submitPlan: TransactionSubmitPlan
        ) : StoredSubmitPlan
    }

    companion object {
        private const val DEFAULT_NAMESPACE = ""
    }
}
