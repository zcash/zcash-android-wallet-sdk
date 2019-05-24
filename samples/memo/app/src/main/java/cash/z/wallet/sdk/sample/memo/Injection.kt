package cash.z.wallet.sdk.sample.memo

import android.content.Context
import cash.z.wallet.sdk.data.*
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.secure.Wallet

object Injection {
    private const val host: String = "lightwalletd.z.cash"
    private const val port: Int = 9067

    fun provideSynchronizer(appContext: Context): Synchronizer {
        val dataDbName = CompactBlockProcessor.DEFAULT_DATA_DB_NAME
        val repository = PollingTransactionRepository(appContext, dataDbName, 5000L)
        val downloader = CompactBlockStream(host, port)
        val wallet = Wallet(
            context = appContext,
            rustBackend = RustBackend(),
            dataDbPath = appContext.getDatabasePath(dataDbName).absolutePath,
            paramDestinationDir = "${appContext.cacheDir.absolutePath}/params",
            seedProvider = SampleSeedProvider("testreferencealice"),
            spendingKeyProvider = SimpleProvider("dummyValue")
        )
        return SdkSynchronizer(
            downloader = downloader,
            processor = CompactBlockProcessor(appContext),
            repository = repository,
            activeTransactionManager = ActiveTransactionManager(repository, downloader.connection, wallet),
            wallet = wallet
        )
    }
}

