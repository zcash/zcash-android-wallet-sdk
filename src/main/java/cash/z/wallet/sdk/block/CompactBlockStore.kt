package cash.z.wallet.sdk.block

import cash.z.wallet.sdk.entity.CompactBlock

/**
 * Interface for storing compact blocks.
 */
interface CompactBlockStore {
    /**
     * Gets the highest block that is currently stored.
     */
    suspend fun getLatestHeight(): Int

    /**
     * Write the given blocks to this store, which may be anything from an in-memory cache to a DB.
     */
    suspend fun write(result: List<CompactBlock>)

    /**
     * Remove every block above and including the given height.
     *
     * After this operation, the data store will look the same as one that has not yet stored the given block height.
     * Meaning, if max height is 100 block and  rewindTo(50) is called, then the highest block remaining will be 49.
     */
    suspend fun rewindTo(height: Int)
}