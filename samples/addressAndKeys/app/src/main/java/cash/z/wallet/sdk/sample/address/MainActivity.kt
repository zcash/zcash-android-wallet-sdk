package cash.z.wallet.sdk.sample.address

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cash.z.wallet.sdk.data.SampleSeedProvider
import cash.z.wallet.sdk.data.TroubleshootingTwig
import cash.z.wallet.sdk.data.Twig
import cash.z.wallet.sdk.jni.RustBackend
import cash.z.wallet.sdk.jni.RustBackendWelding
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates

/**
 * Sample app that shows how to access the address and spending key.
 */
class MainActivity : AppCompatActivity() {
    private val seedFromSecureElement = "testreferencebob"
    private lateinit var wallet: Wallet
    private lateinit var rustBackend: RustBackendWelding
    private lateinit var addressInfo: TextView

    // Secure storage is out of scope for this example (wallet makers know how to securely store things)
    // However, any class can implement the required interface for these dependencies. The expectation is that a wallet
    // maker would wrap an existing class with something that implements the property interface to access data. These
    // dependencies would then point to those wrappers.
    private val mockSecureStorage = Delegates.notNull<String>()
    private val mockSecureSeedProvider = SampleSeedProvider(seedFromSecureElement)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Twig.plant(TroubleshootingTwig())

        addressInfo = findViewById(R.id.text_address_info)

        rustBackend = Injection.provideRustBackend()
        wallet = Injection.provideWallet(mockSecureSeedProvider, mockSecureStorage)
        wallet.initialize()
    }

    override fun onResume() {
        super.onResume()

        val address = wallet.getAddress()

        // The wallet does not provide the spending key. Any request for the key must be delegated to the secure storage
        val key by mockSecureStorage

        val info = """
            seed:
            $seedFromSecureElement
            --------------------------------------
            address:
            $address
            --------------------------------------
            spendingKey:
            $key
        """.trimIndent()
        addressInfo.text = info
    }

    fun onTestThings(view: View) {
        testWalletSend()
    }

    private fun testWalletSend() = runBlocking {
        try {
            val result = wallet.createRawSendTransaction(20_000L, wallet.getAddress(), "")
            addressInfo.text = "\"Succeeded\" with value: $result"
        } catch (t: Throwable) {
            addressInfo.text = "Exception: ${t::class}\n\nMessage: ${t.message}"
        }
    }

    fun testRustSend() {
        try {
            val key by mockSecureStorage
            rustBackend.sendToAddress(
                dbData = "/data/user/0/cash.z.wallet.sdk.sample.address/cache/test_data_bob.db", // String,
                account = 0, // Int,
                extsk = key, // String,
                to = wallet.getAddress(), // String,
                value = 20_000L, // Long,
                memo = "", // String,
                spendParams = "/data/user/0/cash.z.wallet.sdk.sample.address/cache/sapling-spend.params", // String,
                outputParams = "/data/user/0/cash.z.wallet.sdk.sample.address/cache/sapling-output.params" // String
            )
        } catch (t: Throwable) {
            addressInfo.text = "Exception: ${t::class}\n\nMessage: ${t.message}"
        }
    }

}
