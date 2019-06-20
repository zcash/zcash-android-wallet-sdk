package cash.z.wallet.sdk.block

import androidx.annotation.VisibleForTesting
import cash.z.wallet.sdk.annotation.OpenForTesting
import cash.z.wallet.sdk.data.TransactionRepository
import cash.z.wallet.sdk.data.Twig
import cash.z.wallet.sdk.data.twig
import cash.z.wallet.sdk.exception.CompactBlockProcessorException
import cash.z.wallet.sdk.ext.*
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.jni.RustBackendWelding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Responsible for processing the compact blocks that are received from the lightwallet server. This class encapsulates
 * all the business logic required to validate and scan the blockchain and is therefore tightly coupled with
 * librustzcash.
 */
@OpenForTesting
class CompactBlockProcessor(
    internal val config: ProcessorConfig,
    internal val downloader: CompactBlockDownloader,
    private val repository: TransactionRepository,
    private val rustBackend: RustBackendWelding = RustBackend()
) {
    var onErrorListener: ((Throwable) -> Boolean)? = null
    var isConnected: Boolean = false
    var isSyncing: Boolean = false
    var isScanning: Boolean = false
    private val progressChannel = ConflatedBroadcastChannel(0)
    private var isStopped = false
    private val consecutiveChainErrors = AtomicInteger(0)

    fun progress(): ReceiveChannel<Int> = progressChannel.openSubscription()

    /**
     * Download compact blocks, verify and scan them.
     */
    suspend fun start() = withContext(IO) {
        twig("processor starting")
        validateConfig()

        // using do/while makes it easier to execute exactly one loop which helps with testing this processor quickly
        // (because you can start and then immediately set isStopped=true to always get precisely one loop)
        do {
            retryWithBackoff(::onConnectionError, maxDelayMillis = config.maxBackoffInterval) {
                val result = processNewBlocks()
                // immediately process again after failures in order to download new blocks right away
                if (result < 0) {
                    isSyncing = false
                    isScanning = false
                    consecutiveChainErrors.set(0)
                    twig("Successfully processed new blocks. Sleeping for ${config.blockPollFrequencyMillis}ms")
                    delay(config.blockPollFrequencyMillis)
                } else {
                    if(consecutiveChainErrors.get() >= config.retries) {
                        val errorMessage = "ERROR: unable to resolve reorg at height $result after ${consecutiveChainErrors.get()} correction attempts!"
                        fail(CompactBlockProcessorException.FailedReorgRepair(errorMessage))
                    } else {
                        handleChainError(result)
                    }
                    consecutiveChainErrors.getAndIncrement()
                }
            }
        } while (isActive && !isStopped)
        twig("processor complete")
        stop()
    }

    /**
     * Validate the config to expose a common pitfall.
     */
    private fun validateConfig() {
        if(!config.cacheDbPath.contains(File.separator))
            throw CompactBlockProcessorException.FileInsteadOfPath(config.cacheDbPath)
        if(!config.dataDbPath.contains(File.separator))
            throw CompactBlockProcessorException.FileInsteadOfPath(config.dataDbPath)
    }

    fun stop() {
        isStopped = true
    }

    fun fail(error: Throwable) {
        stop()
        twig("${error.message}")
        throw error
    }

    /**
     * Process new blocks returning false whenever an error was found.
     *
     * @return -1 when processing was successful and did not encounter errors during validation or scanning. Otherwise
     * return the block height where an error was found.
     */
    private suspend fun processNewBlocks(): Int = withContext(IO) {
        twig("beginning to process new blocks...")

        // define ranges
        val latestBlockHeight = downloader.getLatestBlockHeight()
        isConnected = true // no exception on downloader call
        isSyncing = true
        val lastDownloadedHeight = Math.max(getLastDownloadedHeight(), SAPLING_ACTIVATION_HEIGHT - 1)
        val lastScannedHeight = getLastScannedHeight()

        twig("latestBlockHeight: $latestBlockHeight\tlastDownloadedHeight: $lastDownloadedHeight" +
                "\tlastScannedHeight: $lastScannedHeight")

        // as long as the database has the sapling tree (like when it's initialized from a checkpoint) we can avoid
        // downloading earlier blocks so take the larger of these two numbers
        val rangeToDownload = (Math.max(lastDownloadedHeight, lastScannedHeight) + 1)..latestBlockHeight
        val rangeToScan = (lastScannedHeight + 1)..latestBlockHeight

        downloadNewBlocks(rangeToDownload)
        val error = validateNewBlocks(rangeToScan)
        if (error < 0) {
            scanNewBlocks(rangeToScan)
            -1 // TODO: in theory scan should not fail when validate succeeds but maybe consider returning the failed block height whenever scan does fail
        } else {
            error
        }
    }


    @VisibleForTesting //allow mocks to verify how this is called, rather than the downloader, which is more complex
    internal suspend fun downloadNewBlocks(range: IntRange) = withContext<Unit>(IO) {
        if (range.isEmpty()) {
            twig("no blocks to download")
        } else {
            Twig.sprout("downloading")
            twig("downloading blocks in range $range")

            var downloadedBlockHeight = range.start
            val missingBlockCount = range.last - range.first + 1
            val batches = (missingBlockCount / config.downloadBatchSize
                    + (if (missingBlockCount.rem(config.downloadBatchSize) == 0) 0 else 1))
            var progress: Int
            twig("found $missingBlockCount missing blocks, downloading in $batches batches of ${config.downloadBatchSize}...")
            for (i in 1..batches) {
                retryUpTo(config.retries) {
                    val end = Math.min(range.first + (i * config.downloadBatchSize), range.last + 1)
                    val batchRange = downloadedBlockHeight..(end - 1)
                    twig("downloaded $batchRange (batch $i of $batches)") {
                        downloader.downloadBlockRange(batchRange)
                    }
                    progress = Math.round(i / batches.toFloat() * 100)
                    // only report during large downloads. TODO: allow for configuration of "large"
                    progressChannel.send(progress)
                    downloadedBlockHeight = end
                }
            }
            Twig.clip("downloading")
        }
        progressChannel.send(100)
    }

    private fun validateNewBlocks(range: IntRange?): Int {
        if (range?.isEmpty() != false) {
            twig("no blocks to validate: $range")
            return -1
        }
        Twig.sprout("validating")
        twig("validating blocks in range $range")
        val result = rustBackend.validateCombinedChain(config.cacheDbPath, config.dataDbPath)
        Twig.clip("validating")
        return result
    }

    private fun scanNewBlocks(range: IntRange?): Boolean {
        if (range?.isEmpty() != false) {
            twig("no blocks to scan")
            return true
        }
        Twig.sprout("scanning")
        twig("scanning blocks in range $range")
        isScanning = true
        val result = rustBackend.scanBlocks(config.cacheDbPath, config.dataDbPath)
        isScanning = false
        Twig.clip("scanning")
        return result
    }

    private suspend fun handleChainError(errorHeight: Int) = withContext(IO) {
        val lowerBound = determineLowerBound(errorHeight)
        twig("handling chain error at $errorHeight by rewinding to block $lowerBound")
        rustBackend.rewindToHeight(config.dataDbPath, lowerBound)
        downloader.rewindTo(lowerBound)
    }

    private fun onConnectionError(throwable: Throwable): Boolean {
        isConnected = false
        isSyncing = false
        isScanning = false
        return onErrorListener?.invoke(throwable) ?: true
    }

    private fun determineLowerBound(errorHeight: Int): Int {
        val offset = Math.min(MAX_REORG_SIZE, config.rewindDistance * (consecutiveChainErrors.get() + 1))
        return Math.max(errorHeight - offset, SAPLING_ACTIVATION_HEIGHT)
    }

    suspend fun getLastDownloadedHeight() = withContext(IO) {
        downloader.getLastDownloadedHeight()
    }

    suspend fun getLastScannedHeight() = withContext(IO) {
        repository.lastScannedHeight()
    }
}

/**
 * @property cacheDbPath absolute file path of the DB where raw, unprocessed compact blocks are stored.
 * @property dataDbPath absolute file path of the DB where all information derived from the cache DB is stored.
 */
data class ProcessorConfig(
    val cacheDbPath: String = "",
    val dataDbPath: String = "",
    val downloadBatchSize: Int = DEFAULT_BATCH_SIZE,
    val blockPollFrequencyMillis: Long = DEFAULT_POLL_INTERVAL,
    val retries: Int = DEFAULT_RETRIES,
    val maxBackoffInterval: Long = DEFAULT_MAX_BACKOFF_INTERVAL,
    val rewindDistance: Int = DEFAULT_REWIND_DISTANCE
)