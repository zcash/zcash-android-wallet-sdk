package co.electriccoin.lightwallet.client.model

import cash.z.wallet.sdk.internal.rpc.Service.BlockID

/**
 * A BlockID message contains identifiers to select a block: a height or a
 * hash.
 *
 * It is marked as "unsafe" because it is not guaranteed to be valid.
 */
@ConsistentCopyVisibility
data class BlockIDUnsafe private constructor(
    val height: Long,
    val hash: ByteArray,
) : Comparable<BlockIDUnsafe> {
    override fun compareTo(other: BlockIDUnsafe): Int = height.compareTo(other.height)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockIDUnsafe

        if (height != other.height) return false
        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = height.hashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }

    companion object {
        fun new(blockID: BlockID): BlockIDUnsafe =
            BlockIDUnsafe(
                height = blockID.height,
                hash = blockID.hash.toByteArray(),
            )
    }
}
