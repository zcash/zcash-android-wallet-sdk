package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticResubmissionGuardTest {
    @Test
    fun excludes_transactions_from_automatic_resubmission() =
        runBlocking {
            val transaction = createdTransaction(1)
            val guard = AutomaticResubmissionGuard()

            guard.excludeFromAutomaticResubmission(transaction)

            assertFalse(guard.shouldAutomaticallyResubmit(transaction.txId))
            assertTrue(guard.shouldAutomaticallyResubmit(createdTransaction(2).txId))
        }

    @Test
    fun persists_excluded_transactions() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val firstGuard = AutomaticResubmissionGuard(preferenceProvider)

            firstGuard.excludeFromAutomaticResubmission(transaction)

            val secondGuard = AutomaticResubmissionGuard(preferenceProvider)
            assertFalse(secondGuard.shouldAutomaticallyResubmit(transaction.txId))
        }

    @Test
    fun duplicate_exclusions_do_not_rewrite_preferences() =
        runBlocking {
            val preferenceProvider = FakePreferenceProvider()
            val transaction = createdTransaction(1)
            val guard = AutomaticResubmissionGuard(preferenceProvider)

            guard.excludeFromAutomaticResubmission(transaction)
            guard.excludeFromAutomaticResubmission(transaction)

            assertEquals(1, preferenceProvider.putStringCount)
        }

    private class FakePreferenceProvider : PreferenceProvider {
        private val values = mutableMapOf<String, String?>()
        var putStringCount = 0

        override suspend fun hasKey(key: PreferenceKey): Boolean = values.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            putStringCount += 1
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
    }
}
