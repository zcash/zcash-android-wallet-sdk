package cash.z.wallet.sdk.sample.memo

import android.content.Context
import cash.z.wallet.sdk.block.CompactBlockDbStore
import cash.z.wallet.sdk.block.CompactBlockDownloader
import cash.z.wallet.sdk.block.CompactBlockProcessor
import cash.z.wallet.sdk.block.ProcessorConfig
import cash.z.wallet.sdk.data.*
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.secure.Wallet
import cash.z.wallet.sdk.service.LightWalletGrpcService

object Injection {
    private const val host: String = "lightwalletd.z.cash"
    private const val port: Int = 9067
    private const val cacheDbName = "memos-cache.db"
    private const val dataDbName = "memos-data.db"
    private val rustBackend = RustBackend()

    fun provideSynchronizer(appContext: Context): Synchronizer {
        val config = ProcessorConfig(cacheDbName.toDbPath(appContext), dataDbName.toDbPath(appContext), downloadBatchSize = 1_000) // settings
        val service = LightWalletGrpcService(appContext, host, port) // connects to lightwalletd
        val blockStore = CompactBlockDbStore(appContext, cacheDbName) // enables compact block storage in cache
        val downloader = CompactBlockDownloader(service, blockStore) // downloads blocks an puts them in storage
        val repository = PollingTransactionRepository(appContext, dataDbName, rustBackend) // provides access to txs
        val processor = CompactBlockProcessor(config, downloader, repository, rustBackend) // decrypts compact blocks
        // wrapper for rustbackend
        val wallet = Wallet(
            context = appContext,
            birthday = Wallet.loadBirthdayFromAssets(appContext, 421720),
            rustBackend = rustBackend,
            dataDbName = dataDbName,
            seedProvider = SampleSeedProvider("testreferencecarol"),
            spendingKeyProvider = SimpleProvider("dummyValue")
        )
        val activeTransactionManager = ActiveTransactionManager(repository, service, wallet) // monitors active txs

        // ties everything together
        return SdkSynchronizer(processor, repository, activeTransactionManager, wallet)
    }

    private fun String.toDbPath(context: Context): String {
        return context.getDatabasePath(this).absolutePath
    }
}

