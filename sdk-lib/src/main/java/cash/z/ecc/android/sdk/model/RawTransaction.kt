package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.ext.toBlockHeight
import cash.z.ecc.android.sdk.model.RawTransaction.Companion.new
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe

/**
 * Represents a safe type transaction object obtained from Light wallet server. It contains complete transaction data.
 *
 * New instances are constructed using the [new] factory method.
 *
 * @param data The complete data of the transaction.
 * @param height The transaction mined height.
 */
@ConsistentCopyVisibility
data class RawTransaction internal constructor(
    val data: ByteArray,
    val height: BlockHeight?
) {
    init {
        require(data.isNotEmpty()) { "Empty RawTransaction data" }
    }

    companion object {
        fun new(rawTransactionUnsafe: RawTransactionUnsafe): RawTransaction =
            RawTransaction(
                data = rawTransactionUnsafe.data,
                height =
                    when (rawTransactionUnsafe) {
                        is RawTransactionUnsafe.MainChain -> rawTransactionUnsafe.height.toBlockHeight()
                        else -> null
                    }
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawTransaction

        if (!data.contentEquals(other.data)) return false
        if (height != other.height) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + height.hashCode()
        return result
    }
}
