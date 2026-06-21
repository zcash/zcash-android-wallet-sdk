package cash.z.ecc.android.sdk.integration.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.annotation.MaintainedTest
import cash.z.ecc.android.sdk.annotation.TestPurpose
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackendImpl
import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.jni.FakeRustBackend
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.repository.CompactBlockRepository
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.test.ScopedTest
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import kotlin.io.path.createTempDirectory
import kotlin.test.Ignore

@MaintainedTest(TestPurpose.REGRESSION)
@RunWith(AndroidJUnit4::class)
@SmallTest
class ChangeServiceTest : ScopedTest() {
    val network = ZcashNetwork.Mainnet
    private val lightWalletEndpoint = LightWalletEndpoint("mainnet.lightwalletd.com", 9067, true)
    private val eccEndpoint = LightWalletEndpoint("lightwalletd.electriccoin.co", 9087, true)

    @Mock
    lateinit var mockBlockStore: CompactBlockRepository
    private var mockCloseable: AutoCloseable? = null

    private lateinit var service: CombinedWalletClient

    private lateinit var downloader: CompactBlockDownloader
    private lateinit var otherService: LightWalletClient

    @Before
    fun setup() =
        runTest {
            initMocks()
            downloader = CompactBlockDownloader(service, mockBlockStore)
            otherService = LightWalletClient.new(context, eccEndpoint)
        }

    @After
    fun tearDown() {
        mockCloseable?.close()
    }

    private suspend fun initMocks() {
        val torDir = createTempDirectory("tor-client-").toFile()
        val torClient = TorClient.new(torDir, TypesafeBackendImpl(FakeRustBackend(0, mutableListOf())))
        val factory = WalletClientFactory(context = context, torClient = torClient)
        service = factory.create(lightWalletEndpoint)
        mockCloseable = MockitoAnnotations.openMocks(this)
    }

    @Test
    @Ignore("Disabled as not working currently")
    fun testSanityCheck() =
        runTest {
            // Test the result, only if there is no server communication problem.
            runCatching {
                service.getLatestBlockHeight(ServiceMode.Direct)
            }.onFailure {
                Twig.debug(it) { "Failed to retrieve data" }
            }.onSuccess {
                assertTrue(it is Response.Success<BlockHeightUnsafe>)

                assertTrue(
                    (it as Response.Success<BlockHeightUnsafe>).result.value >
                        network.saplingActivationHeight
                            .value
                )
            }
        }
}
