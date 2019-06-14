package cash.z.wallet.sdk.db

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.wallet.sdk.data.SampleSeedProvider
import cash.z.wallet.sdk.data.TroubleshootingTwig
import cash.z.wallet.sdk.data.Twig
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okio.Okio
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.properties.Delegates
import kotlin.properties.ReadWriteProperty

@ExperimentalCoroutinesApi
class AddressGeneratorUtil {

    private val dataDbName = "AddressUtilData.db"
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val rustBackend = RustBackend()

    private lateinit var wallet: Wallet

    @Before
    fun setup() {
        Twig.plant(TroubleshootingTwig())
        rustBackend.initLogs()
    }

    private fun deleteDb() {
        context.getDatabasePath(dataDbName).absoluteFile.delete()
    }

    @Test
    fun generateAddresses() = runBlocking {
        readLines().collect { seed ->
            val keyStore = initWallet(seed)
            val address = wallet.getAddress()
            val pk by keyStore
            println("xrxrx2\t$seed\t$address\t$pk")
        }
        Thread.sleep(5000)
        assertEquals("foo", "bar")
    }

    @Throws(IOException::class)
    fun readLines() = flow<String> {
        val seedFile = javaClass.getResourceAsStream("/utils/seeds.txt")
        Okio.buffer(Okio.source(seedFile)).use { source ->
            var line: String? = source.readUtf8Line()
            while (line != null) {
                emit(line)
                line = source.readUtf8Line()
            }
        }
    }

    private fun initWallet(seed: String): ReadWriteProperty<Any?, String> {
        deleteDb()
        val spendingKeyProvider = Delegates.notNull<String>()
        wallet = Wallet(
            context,
            rustBackend,
            context.getDatabasePath(dataDbName).absolutePath,
            context.cacheDir.absolutePath,
            arrayOf(0),
            SampleSeedProvider(seed),
            spendingKeyProvider
        )
        wallet.initialize()
        return spendingKeyProvider
    }
}