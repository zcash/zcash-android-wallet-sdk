package cash.z.ecc.android.sdk.model

import android.content.Context
import cash.z.ecc.android.sdk.tool.CheckpointTool

/**
 * Represents a block height, which is a UInt32.  SDK clients use this class to represent the "birthday" of a wallet.
 *
 * New instances are constructed using the [new] factory method.
 *
 * @param value The block height.  Must be in range of a UInt32.
 *
 * For easier compatibility with Java clients, this class represents the height value as a Long with
 * assertions to ensure that it is a 32-bit unsigned integer.
 */
@ConsistentCopyVisibility
data class BlockHeight internal constructor(
    val value: Long
) : Comparable<BlockHeight> {
    init {
        require(UINT_RANGE.contains(value)) { "Height $value is outside of allowed range $UINT_RANGE" }
    }

    override fun compareTo(other: BlockHeight): Int = value.compareTo(other.value)

    operator fun plus(other: Int): BlockHeight {
        require(other >= 0) {
            "Cannot add negative value $other to BlockHeight"
        }

        return BlockHeight(value + other.toLong())
    }

    operator fun plus(other: Long): BlockHeight {
        require(other >= 0) {
            "Cannot add negative value $other to BlockHeight"
        }

        return BlockHeight(value + other)
    }

    operator fun minus(other: BlockHeight) = value - other.value

    operator fun minus(other: Int): BlockHeight {
        require(other >= 0) {
            "Cannot subtract negative value $other from BlockHeight"
        }

        return BlockHeight(value - other.toLong())
    }

    operator fun minus(other: Long): BlockHeight {
        require(other >= 0) {
            "Cannot subtract negative value $other from BlockHeight"
        }

        return BlockHeight(value - other)
    }

    companion object {
        private val UINT_RANGE = 0.toLong()..UInt.MAX_VALUE.toLong()

        /**
         * @param blockHeight The block height.  Must be in range of a UInt32.
         */
        fun new(blockHeight: Long): BlockHeight = BlockHeight(blockHeight)

        /**
         * Useful when creating a new wallet to reduce sync times.
         *
         * @param zcashNetwork Network to use for the block height.
         * @return The block height of the newest checkpoint known by the SDK.
         */
        suspend fun ofLatestCheckpoint(
            context: Context,
            zcashNetwork: ZcashNetwork
        ): BlockHeight = CheckpointTool.loadNearest(context, zcashNetwork, null).height
    }
}
