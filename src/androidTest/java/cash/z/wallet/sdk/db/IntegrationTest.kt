package cash.z.wallet.sdk.db

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.wallet.sdk.block.CompactBlockDbStore
import cash.z.wallet.sdk.block.CompactBlockDownloader
import cash.z.wallet.sdk.block.CompactBlockProcessor
import cash.z.wallet.sdk.block.ProcessorConfig
import cash.z.wallet.sdk.data.*
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.secure.Wallet
import cash.z.wallet.sdk.service.LightWalletGrpcService
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test

/*
TODO:
setup a test that we can run and just watch things happen, to give confidence that logging is expressive enough to
verify that the SDK is behaving as expected.
 */
class IntegrationTest {

    private val dataDbName = "IntegrationData41.db"
    private val cacheDdName = "IntegrationCache41.db"
    private val context = InstrumentationRegistry.getInstrumentation().context

    private lateinit var downloader: CompactBlockDownloader
    private lateinit var processor: CompactBlockProcessor
    private lateinit var wallet: Wallet

    @Before
    fun setup() {
        deleteDbs()
        Twig.plant(TroubleshootingTwig())
    }

    private fun deleteDbs() {
        // prior to each run, delete the DBs for sanity
        listOf(cacheDdName, dataDbName).map { context.getDatabasePath(it).absoluteFile }.forEach {
            println("Deleting ${it.name}")
            it.delete()
        }
    }

    @Test(timeout = 120_000L)
    fun testSync() = runBlocking<Unit> {
        val rustBackend = RustBackend()
        rustBackend.initLogs()
        val config = ProcessorConfig(
            cacheDbPath = context.getDatabasePath(cacheDdName).absolutePath,
            dataDbPath = context.getDatabasePath(dataDbName).absolutePath,
            downloadBatchSize = 2000,
            blockPollFrequencyMillis = 10_000L
        )

        val lightwalletService = LightWalletGrpcService(context,"192.168.1.134")
        val compactBlockStore = CompactBlockDbStore(context, config.cacheDbPath)

        downloader = CompactBlockDownloader(lightwalletService, compactBlockStore)
        processor = CompactBlockProcessor(config, downloader, repository, rustBackend)
        repository = PollingTransactionRepository(context, dataDbName, rustBackend, 10_000L)
        wallet = Wallet(
            context = context,
            rustBackend = rustBackend,
            dataDbName = dataDbName,
            seedProvider = SampleSeedProvider("dummyseed"),
            spendingKeyProvider = SampleSpendingKeyProvider("dummyseed")
        )

//        repository.start(this)
        synchronizer = SdkSynchronizer(
            processor,
            repository,
            ActiveTransactionManager(repository, lightwalletService, wallet),
            wallet,
            1000
        ).start(this)

        for(i in synchronizer.progress()) {
            twig("made progress: $i")
        }
    }

    companion object {
        private lateinit var synchronizer: Synchronizer
        private lateinit var repository: PollingTransactionRepository
        @AfterClass
        fun tearDown() {
            repository.stop()
            synchronizer.stop()
        }
    }
}