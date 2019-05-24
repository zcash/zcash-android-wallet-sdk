package cash.z.wallet.sdk.db

import android.text.format.DateUtils
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.wallet.sdk.data.*
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.secure.Wallet
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

    private lateinit var downloader: CompactBlockStream
    private lateinit var processor: CompactBlockProcessor
    private lateinit var wallet: Wallet

    @Before
    fun setup() {
        deleteDbs()
    }

    private fun deleteDbs() {
        // prior to each run, delete the DBs for sanity
        listOf(cacheDdName, dataDbName).map { context.getDatabasePath(it).absoluteFile }.forEach {
            println("Deleting ${it.name}")
            it.delete()
        }
    }

    @Test(timeout = 1L * DateUtils.MINUTE_IN_MILLIS/10)
    fun testSync() = runBlocking<Unit> {
        val rustBackend = RustBackend()
        rustBackend.initLogs()
        val logger = TroubleshootingTwig()

        downloader = CompactBlockStream("10.0.2.2", 9067, logger)
        processor = CompactBlockProcessor(context, rustBackend, cacheDdName, dataDbName, logger = logger)
        repository = PollingTransactionRepository(context, dataDbName, 10_000L)
        wallet = Wallet(
            context,
            rustBackend,
            context.getDatabasePath(dataDbName).absolutePath,
            context.cacheDir.absolutePath,
            arrayOf(0),
            SampleSeedProvider("dummyseed"),
            SampleSpendingKeyProvider("dummyseed")
        )

//        repository.start(this)
        synchronizer = SdkSynchronizer(
            downloader,
            processor,
            repository,
            ActiveTransactionManager(repository, downloader.connection, wallet, logger),
            wallet,
            1000
        ).start(this)

        for(i in synchronizer.progress()) {
            logger.twig("made progress: $i")
        }
    }

    companion object {
        private lateinit var synchronizer: Synchronizer
        private lateinit var repository: TransactionRepository
        @AfterClass
        fun tearDown() {
            repository.stop()
            synchronizer.stop()
        }
    }
}