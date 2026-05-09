package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.StandardPreferenceKeys
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AutomaticResubmissionGuard(
    private val preferenceProvider: PreferenceProvider? = null
) {
    private val mutex = Mutex()
    private val callerManagedTransactionIds = mutableSetOf<String>()
    private var loadedFromPreferences = false

    suspend fun excludeFromAutomaticResubmission(transaction: CreatedTransaction) {
        excludeFromAutomaticResubmission(transaction.txId)
    }

    suspend fun excludeFromAutomaticResubmission(transactions: List<CreatedTransaction>) {
        val txIds = transactions.map { it.txId }
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            callerManagedTransactionIds += txIds.map { it.toStableKey() }
            saveToPreferences()
        }
    }

    suspend fun shouldAutomaticallyResubmit(txId: FirstClassByteArray): Boolean =
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            txId.toStableKey() !in callerManagedTransactionIds
        }

    private suspend fun excludeFromAutomaticResubmission(txId: FirstClassByteArray) {
        mutex.withLock {
            loadFromPreferencesIfNeeded()
            callerManagedTransactionIds += txId.toStableKey()
            saveToPreferences()
        }
    }

    private suspend fun loadFromPreferencesIfNeeded() {
        if (loadedFromPreferences) {
            return
        }
        val storedTransactionIds =
            preferenceProvider
                ?.getString(StandardPreferenceKeys.AUTOMATIC_RESUBMISSION_EXCLUDED_TX_IDS.key)
                .orEmpty()

        callerManagedTransactionIds +=
            storedTransactionIds
                .lineSequence()
                .filter { it.isNotBlank() }
                .toSet()
        loadedFromPreferences = true
    }

    private suspend fun saveToPreferences() {
        preferenceProvider?.putString(
            StandardPreferenceKeys.AUTOMATIC_RESUBMISSION_EXCLUDED_TX_IDS.key,
            callerManagedTransactionIds.sorted().joinToString(separator = "\n")
        )
    }

    private fun FirstClassByteArray.toStableKey() = byteArray.toHexReversed()
}
