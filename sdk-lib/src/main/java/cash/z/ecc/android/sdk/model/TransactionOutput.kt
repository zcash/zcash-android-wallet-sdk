package cash.z.ecc.android.sdk.model

data class TransactionOutput(
    val pool: TransactionPool,
)

enum class TransactionPool {
    TRANSPARENT,
    SAPLING,
    ORCHARD,
    IRONWOOD
}
