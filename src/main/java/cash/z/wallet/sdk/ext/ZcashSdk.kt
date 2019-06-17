package cash.z.wallet.sdk.ext

//
// Constants
//

/**
 * Miner's fee in zatoshi.
 */
const val MINERS_FEE_ZATOSHI = 10_000L

/**
 * The number of zatoshi that equal 1 ZEC.
 */
const val ZATOSHI = 100_000_000L

/**
 * The height of the first sapling block. When it comes to shielded transactions, we do not need to consider any blocks
 * prior to this height, at all.
 */
const val SAPLING_ACTIVATION_HEIGHT = 280_000

/**
 * The theoretical maximum number of blocks in a reorg, due to other bottlenecks in the protocol design.
 */
const val MAX_REORG_SIZE = 100


//
// Defaults
//

/**
 * Default size of batches of blocks to request from the compact block service.
 */
const val DEFAULT_BATCH_SIZE = 100

/**
 * Default amount of time, in milliseconds, to poll for new blocks. Typically, this should be about half the average
 * block time.
 */
const val DEFAULT_POLL_INTERVAL = 75_000L

/**
 * Default attempts at retrying.
 */
const val DEFAULT_RETRIES = 5

/**
 * The default maximum amount of time to wait during retry backoff intervals. Failed loops will never wait longer than
 * this before retyring.
 */
const val DEFAULT_MAX_BACKOFF_INTERVAL = 600_000L

/**
 * Default number of blocks to rewind when a chain reorg is detected. This should be large enough to recover from the
 * reorg but smaller than the theoretical max reorg size of 100.
 */
const val DEFAULT_REWIND_DISTANCE = 10

/**
 * The number of blocks to allow before considering our data to be stale. This usually helps with what to do when
 * returning from the background and is exposed via the Synchronizer's isStale function.
 */
const val DEFAULT_STALE_TOLERANCE = 10