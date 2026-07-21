package cash.z.ecc.android.sdk.fixture

import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi

@Suppress("MagicNumber")
object AccountBalanceFixture {
    val TRANSPARENT_BALANCE: Zatoshi = Zatoshi(8)
    val SAPLING_BALANCE: WalletBalance = WalletBalanceFixture.new(Zatoshi(4), Zatoshi(4), Zatoshi(2))
    val ORCHARD_BALANCE: WalletBalance = WalletBalanceFixture.new(Zatoshi(5), Zatoshi(2), Zatoshi(1))
    val IRONWOOD_BALANCE: WalletBalance = WalletBalanceFixture.new(Zatoshi(0), Zatoshi(0), Zatoshi(0))

    fun new(
        orchardBalance: WalletBalance = ORCHARD_BALANCE,
        saplingBalance: WalletBalance = SAPLING_BALANCE,
        transparentBalance: Zatoshi = TRANSPARENT_BALANCE,
        ironwoodBalance: WalletBalance = IRONWOOD_BALANCE
    ) = AccountBalance(
        saplingBalance,
        orchardBalance,
        ironwoodBalance,
        transparentBalance
    )
}
