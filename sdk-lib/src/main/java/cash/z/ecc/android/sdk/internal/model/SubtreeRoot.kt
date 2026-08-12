package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.lightwallet.client.model.SubtreeRootUnsafe

internal data class SubtreeRoot(
    val rootHash: ByteArray,
    val completingBlockHash: ByteArray,
    val completingBlockHeight: BlockHeight
) {
    override fun toString() = "SubtreeRoot(completingBlockHeight=${completingBlockHeight.value})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SubtreeRoot

        if (!rootHash.contentEquals(other.rootHash)) return false
        if (!completingBlockHash.contentEquals(other.completingBlockHash)) return false
        if (completingBlockHeight != other.completingBlockHeight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rootHash.contentHashCode()
        result = 31 * result + completingBlockHash.contentHashCode()
        result = 31 * result + completingBlockHeight.hashCode()
        return result
    }

    companion object {
        fun new(unsafe: SubtreeRootUnsafe): SubtreeRoot =
            SubtreeRoot(
                rootHash = unsafe.rootHash,
                completingBlockHash = unsafe.completingBlockHash,
                completingBlockHeight = BlockHeight.new(unsafe.completingBlockHeight.value)
            )
    }
}
