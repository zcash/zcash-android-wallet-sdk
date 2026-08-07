package cash.z.ecc.android.sdk.internal.fixture

import cash.z.ecc.android.sdk.internal.model.JniAccountBalance

/**
 * This is a test fixture for [JniAccountBalance] class. It holds mocked values that are only used within
 * [JniWalletSummaryTest].
 */
object JniAccountBalanceFixture {
    val ACCOUNT_UUID: ByteArray = "random_uuid_16_b".toByteArray()
    const val SAPLING_VERIFIED_BALANCE: Long = 0L
    const val SAPLING_CHANGE_PENDING: Long = 0L
    const val SAPLING_VALUE_PENDING: Long = 0L
    const val SAPLING_LOCKED_BALANCE: Long = 0L
    const val ORCHARD_VERIFIED_BALANCE: Long = 0L
    const val ORCHARD_CHANGE_PENDING: Long = 0L
    const val ORCHARD_VALUE_PENDING: Long = 0L
    const val ORCHARD_LOCKED_BALANCE: Long = 0L
    const val IRONWOOD_VERIFIED_BALANCE: Long = 0L
    const val IRONWOOD_CHANGE_PENDING: Long = 0L
    const val IRONWOOD_VALUE_PENDING: Long = 0L
    const val IRONWOOD_LOCKED_BALANCE: Long = 0L
    const val UNSHIELDED_BALANCE: Long = 0L

    @Suppress("LongParameterList")
    fun new(
        accountUuid: ByteArray = ACCOUNT_UUID,
        saplingVerifiedBalance: Long = SAPLING_VERIFIED_BALANCE,
        saplingChangePending: Long = SAPLING_CHANGE_PENDING,
        saplingValuePending: Long = SAPLING_VALUE_PENDING,
        saplingLockedBalance: Long = SAPLING_LOCKED_BALANCE,
        orchardVerifiedBalance: Long = ORCHARD_VERIFIED_BALANCE,
        orchardChangePending: Long = ORCHARD_CHANGE_PENDING,
        orchardValuePending: Long = ORCHARD_VALUE_PENDING,
        orchardLockedBalance: Long = ORCHARD_LOCKED_BALANCE,
        ironwoodVerifiedBalance: Long = IRONWOOD_VERIFIED_BALANCE,
        ironwoodChangePending: Long = IRONWOOD_CHANGE_PENDING,
        ironwoodValuePending: Long = IRONWOOD_VALUE_PENDING,
        ironwoodLockedBalance: Long = IRONWOOD_LOCKED_BALANCE,
        unshieldedBalance: Long = UNSHIELDED_BALANCE,
    ) = JniAccountBalance(
        accountUuid = accountUuid,
        saplingVerifiedBalance = saplingVerifiedBalance,
        saplingChangePending = saplingChangePending,
        saplingValuePending = saplingValuePending,
        saplingLockedBalance = saplingLockedBalance,
        orchardVerifiedBalance = orchardVerifiedBalance,
        orchardChangePending = orchardChangePending,
        orchardValuePending = orchardValuePending,
        orchardLockedBalance = orchardLockedBalance,
        ironwoodVerifiedBalance = ironwoodVerifiedBalance,
        ironwoodChangePending = ironwoodChangePending,
        ironwoodValuePending = ironwoodValuePending,
        ironwoodLockedBalance = ironwoodLockedBalance,
        unshieldedBalance = unshieldedBalance,
    )
}
