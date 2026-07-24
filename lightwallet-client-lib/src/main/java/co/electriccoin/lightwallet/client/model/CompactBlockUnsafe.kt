package co.electriccoin.lightwallet.client.model

import cash.z.wallet.sdk.internal.rpc.CompactFormats
import cash.z.wallet.sdk.internal.rpc.CompactFormats.CompactBlock

/**
 * CompactBlock is a packaging of ONLY the data from a block that's needed to:
 * 1. Detect a payment to your shielded Sapling address
 * 2. Detect a spend of your shielded Sapling notes
 * 3. Update your witnesses to generate new Sapling spend proofs.
 *
 * It is marked as "unsafe" because it is not guaranteed to be valid.
 */
@Suppress("LongParameterList")
class CompactBlockUnsafe(
    val height: Long,
    val hash: ByteArray,
    val time: Int,
    val saplingOutputsCount: UInt,
    val orchardOutputsCount: UInt,
    val ironwoodOutputsCount: UInt,
    val compactBlockBytes: ByteArray
) {
    companion object {
        fun new(compactBlock: CompactBlock): CompactBlockUnsafe {
            val outputCounts = getOutputsCounts(compactBlock.vtxList)
            return CompactBlockUnsafe(
                height = compactBlock.height,
                hash = compactBlock.hash.toByteArray(),
                time = compactBlock.time,
                saplingOutputsCount = outputCounts.saplingOutputsCount,
                orchardOutputsCount = outputCounts.orchardActionsCount,
                ironwoodOutputsCount = outputCounts.ironwoodActionsCount,
                compactBlockBytes = compactBlock.toByteArray()
            )
        }

        private fun getOutputsCounts(vtxList: List<CompactFormats.CompactTx>): CompactBlockOutputsCounts {
            var outputsCount: UInt = 0u
            var actionsCount: UInt = 0u
            var ironwoodActionsCount: UInt = 0u

            vtxList.forEach { compactTx ->
                outputsCount += compactTx.outputsCount.toUInt()
                actionsCount += compactTx.actionsCount.toUInt()
                ironwoodActionsCount += compactTx.ironwoodActionsCount.toUInt()
            }

            return CompactBlockOutputsCounts(outputsCount, actionsCount, ironwoodActionsCount)
        }
    }

    data class CompactBlockOutputsCounts(
        val saplingOutputsCount: UInt,
        val orchardActionsCount: UInt,
        val ironwoodActionsCount: UInt
    )
}
