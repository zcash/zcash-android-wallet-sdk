package cash.z.ecc.android.sdk.internal.fixture

import cash.z.ecc.android.sdk.internal.model.JniAccountBalance

/**
 * This is a test fixture for [JniAccountBalance] class. It holds mocked values that are only used within
 * [JniWalletSummaryTest].
 */
object JniAccountBalanceFixture {
    val ACCOUNT_UUID: ByteArray = "random_uuid_16_b".toByteArray()

    // Every value is distinct on purpose. [JniAccountBalance] is constructed from Rust by
    // `encode_account_balance`, which passes ten positional `jlong`s under a JVM descriptor
    // (`([BJJJJJJJJJJ)V`) that cannot distinguish them, and the Kotlin side only checks that each
    // is non-negative. Identical values would let any permutation of the ten fields, such as
    // reporting Orchard funds as Ironwood, pass every test that uses this fixture.
    const val SAPLING_VERIFIED_BALANCE: Long = 1L
    const val SAPLING_CHANGE_PENDING: Long = 2L
    const val SAPLING_VALUE_PENDING: Long = 3L
    const val ORCHARD_VERIFIED_BALANCE: Long = 4L
    const val ORCHARD_CHANGE_PENDING: Long = 5L
    const val ORCHARD_VALUE_PENDING: Long = 6L
    const val IRONWOOD_VERIFIED_BALANCE: Long = 7L
    const val IRONWOOD_CHANGE_PENDING: Long = 8L
    const val IRONWOOD_VALUE_PENDING: Long = 9L
    const val UNSHIELDED_BALANCE: Long = 10L

    @Suppress("LongParameterList")
    fun new(
        accountUuid: ByteArray = ACCOUNT_UUID,
        saplingVerifiedBalance: Long = SAPLING_VERIFIED_BALANCE,
        saplingChangePending: Long = SAPLING_CHANGE_PENDING,
        saplingValuePending: Long = SAPLING_VALUE_PENDING,
        orchardVerifiedBalance: Long = ORCHARD_VERIFIED_BALANCE,
        orchardChangePending: Long = ORCHARD_CHANGE_PENDING,
        orchardValuePending: Long = ORCHARD_VALUE_PENDING,
        ironwoodVerifiedBalance: Long = IRONWOOD_VERIFIED_BALANCE,
        ironwoodChangePending: Long = IRONWOOD_CHANGE_PENDING,
        ironwoodValuePending: Long = IRONWOOD_VALUE_PENDING,
        unshieldedBalance: Long = UNSHIELDED_BALANCE,
    ) = JniAccountBalance(
        accountUuid = accountUuid,
        saplingVerifiedBalance = saplingVerifiedBalance,
        saplingChangePending = saplingChangePending,
        saplingValuePending = saplingValuePending,
        orchardVerifiedBalance = orchardVerifiedBalance,
        orchardChangePending = orchardChangePending,
        orchardValuePending = orchardValuePending,
        ironwoodVerifiedBalance = ironwoodVerifiedBalance,
        ironwoodChangePending = ironwoodChangePending,
        ironwoodValuePending = ironwoodValuePending,
        unshieldedBalance = unshieldedBalance,
    )
}
