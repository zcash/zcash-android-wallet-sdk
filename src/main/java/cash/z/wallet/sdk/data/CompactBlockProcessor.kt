package cash.z.wallet.sdk.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import cash.z.wallet.sdk.dao.CompactBlockDao
import cash.z.wallet.sdk.db.CompactBlockDb
import cash.z.wallet.sdk.exception.CompactBlockProcessorException
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.jni.RustBackendWelding
import cash.z.wallet.sdk.rpc.CompactFormats
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Responsible for processing the blocks on the stream. Saves them to the cacheDb and periodically scans for transactions.
 *
 * @property applicationContext used to connect to the DB on the device. No reference is kept beyond construction.
 */
class CompactBlockProcessor(
    applicationContext: Context,
    val rustBackend: RustBackendWelding = RustBackend(),
    cacheDbName: String = DEFAULT_CACHE_DB_NAME,
    dataDbName: String = DEFAULT_DATA_DB_NAME
) {

    internal val cacheDao: CompactBlockDao
    private val cacheDb: CompactBlockDb
    private val cacheDbPath: String
    private val dataDbPath: String

    val dataDbExists get() = File(dataDbPath).exists()
    val cachDbExists get() = File(cacheDbPath).exists()

    init {
        cacheDb = createCompactBlockCacheDb(applicationContext, cacheDbName)
        cacheDao = cacheDb.complactBlockDao()
        cacheDbPath = applicationContext.getDatabasePath(cacheDbName).absolutePath
        dataDbPath = applicationContext.getDatabasePath(dataDbName).absolutePath
    }

    private fun createCompactBlockCacheDb(applicationContext: Context, cacheDbName: String): CompactBlockDb {
        return Room.databaseBuilder(applicationContext, CompactBlockDb::class.java, cacheDbName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // this is a simple cache of blocks. destroying the db should be benign
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * Save blocks and periodically scan them.
     */
    suspend fun processBlocks(incomingBlocks: ReceiveChannel<CompactFormats.CompactBlock>) = withContext(IO) {
        ensureDataDb()
        twigTask("processing blocks") {
            var lastScanTime = System.currentTimeMillis()
            var hasScanned = false
            while (isActive && !incomingBlocks.isClosedForReceive) {
                twig("awaiting next block")
                val nextBlock = incomingBlocks.receive()
                val nextBlockHeight = nextBlock.height
                twig("received block with height ${nextBlockHeight} on thread ${Thread.currentThread().name}")
                cacheDao.insert(cash.z.wallet.sdk.entity.CompactBlock(nextBlockHeight.toInt(), nextBlock.toByteArray()))
                if (shouldScanBlocks(lastScanTime, hasScanned)) {
                    twig("last block prior to scan ${nextBlockHeight}")
                    scanBlocks()
                    lastScanTime = System.currentTimeMillis()
                    hasScanned = true
                }
            }
            cacheDb.close()
        }
    }

    private fun ensureDataDb() {
        if (!dataDbExists) throw CompactBlockProcessorException.DataDbMissing(dataDbPath)
    }

    private fun shouldScanBlocks(lastScanTime: Long, hasScanned: Boolean): Boolean {
        val deltaTime = System.currentTimeMillis() - lastScanTime
        twig("${deltaTime}ms since last scan. Have we ever scanned? $hasScanned")
        return (!hasScanned && deltaTime > INITIAL_SCAN_DELAY)
                || deltaTime > SCAN_FREQUENCY
    }

    suspend fun scanBlocks() = withContext(IO) {
        Twig.sprout("scan")
        twigTask("scanning blocks") {
            if (isActive) {
                try {
                    rustBackend.scanBlocks(cacheDbPath, dataDbPath)
                } catch (t: Throwable) {
                    twig("error while scanning blocks: $t")
                }
            }
        }
        Twig.clip("scan")
    }

    /**
     * Returns the height of the last processed block or -1 if no blocks have been processed.
     */
    suspend fun lastProcessedBlock(): Int = withContext(IO) {
        val lastBlock = Math.max(0, cacheDao.latestBlockHeight() - 1)
        if (lastBlock < SAPLING_ACTIVATION_HEIGHT) -1 else lastBlock
    }

    companion object {
        const val DEFAULT_CACHE_DB_NAME = "DownloadedCompactBlocks.db"
        const val DEFAULT_DATA_DB_NAME = "CompactBlockScanResults.db"

        /** Default amount of time to synchronize before initiating the first scan. This allows time to download a few blocks. */
        const val INITIAL_SCAN_DELAY = 3000L
        /** Minimum amount of time between scans. The frequency with which we check whether the block height has changed and, if so, trigger a scan */
        const val SCAN_FREQUENCY = 75_000L
        // TODO: find a better home for this constant
        const val SAPLING_ACTIVATION_HEIGHT = 280_000
    }
}
