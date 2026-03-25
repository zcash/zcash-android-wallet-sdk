package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.ext.toHexReversed

/**
 * Typesafe wrapper class for the transaction ID identifier.
 *
 * @param value Byte array of the the transaction ID wrapped in [FirstClassByteArray]
 */
@ConsistentCopyVisibility
data class TransactionId internal constructor(
    val value: FirstClassByteArray,
) {
    init {
        require(value.byteArray.isNotEmpty()) {
            "Transaction ID must not be empty"
        }
    }

    companion object {
        fun new(byteArray: FirstClassByteArray) = TransactionId(byteArray)

        fun new(byteArray: ByteArray) = TransactionId(FirstClassByteArray(byteArray))

        @Suppress("MagicNumber")
        fun new(txId: String): TransactionId {
            require(txId.isNotEmpty()) {
                "Transaction ID string must not be empty"
            }
            require(txId.length % 2 == 0) {
                "Transaction ID hex string must have even length"
            }
            require(txId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "Transaction ID string must be a valid hex string"
            }

            // Parse hex string to bytes and reverse to match the original byte order
            val bytes = ByteArray(txId.length / 2)
            for (i in bytes.indices) {
                val index = i * 2
                bytes[bytes.size - 1 - i] = txId.substring(index, index + 2).toInt(16).toByte()
            }

            return TransactionId(FirstClassByteArray(bytes))
        }
    }

    /**
     * @return Transaction ID in String
     */
    fun txIdString() = value.byteArray.toHexReversed()

    override fun toString() = "TransactionId"
}
