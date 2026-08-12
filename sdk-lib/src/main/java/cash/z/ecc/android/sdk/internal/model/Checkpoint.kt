package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.ext.isInUIntRange
import cash.z.ecc.android.sdk.model.BlockHeight

/**
 * Represents a checkpoint, which is used to speed sync times.
 *
 * @param height the height of the checkpoint.
 * @param hash the hash of the block at [height].
 * @param epochSeconds the time of the block at [height].
 * @param saplingTree the sapling tree corresponding to [height].
 * @param orchardTree the orchard tree corresponding to [height].
 * @param ironwoodTree the ironwood tree corresponding to [height], or null for a
 * checkpoint generated before Ironwood tree states were published. Mainnet
 * checkpoints do not carry it yet, so this cannot be required the way
 * [orchardTree] is.
 */
internal data class Checkpoint(
    val height: BlockHeight,
    val hash: String,
    // Note: this field does NOT match the name of the JSON, so will break with field-based JSON parsing
    val epochSeconds: Long,
    val saplingTree: String,
    val orchardTree: String,
    val ironwoodTree: String?
) {
    fun treeState(): TreeState {
        require(epochSeconds.isInUIntRange()) {
            "epochSeconds $epochSeconds is outside of allowed UInt range"
        }
        return TreeState.fromParts(
            height.value,
            hash,
            epochSeconds.toInt(),
            saplingTree,
            orchardTree,
            ironwoodTree
        )
    }

    @Suppress("MagicNumber")
    internal val epochTimeMillis: Long by lazy { epochSeconds * 1000 }

    companion object
}
