package cash.z.wallet.sdk.block

import cash.z.wallet.sdk.data.TransactionRepository
import cash.z.wallet.sdk.data.TroubleshootingTwig
import cash.z.wallet.sdk.data.Twig
import cash.z.wallet.sdk.entity.CompactBlock
import cash.z.wallet.sdk.ext.SAPLING_ACTIVATION_HEIGHT
import cash.z.wallet.sdk.jni.RustBackendWelding
import cash.z.wallet.sdk.service.LightWalletService
import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
internal class CompactBlockProcessorTest {

    private val frequency = 5L

    // Mocks/Spys
    @Mock lateinit var rustBackend: RustBackendWelding
    lateinit var processor: CompactBlockProcessor

    // Test variables
    private var latestBlockHeight: Int = 500_000
    private var lastDownloadedHeight: Int = SAPLING_ACTIVATION_HEIGHT
    private var lastScannedHeight: Int = SAPLING_ACTIVATION_HEIGHT
    private var errorBlock: Int = -1

    @BeforeEach
    fun setUp(
        @Mock lightwalletService: LightWalletService,
        @Mock compactBlockStore: CompactBlockStore,
        @Mock repository: TransactionRepository
    ) {
        Twig.plant(TroubleshootingTwig())


        lightwalletService.stub {
            onBlocking {
                getBlockRange(any())
            }.thenAnswer { invocation ->
                val range = invocation.arguments[0] as IntRange
                range.map { CompactBlock(it, ByteArray(0)) }
            }
        }
        lightwalletService.stub {
            onBlocking {
                getLatestBlockHeight()
            }.thenAnswer { latestBlockHeight }
        }

        compactBlockStore.stub {
            onBlocking {
                write(any())
            }.thenAnswer { invocation ->
                val lastBlockHeight = (invocation.arguments[0] as List<CompactBlock>).last().height
                lastDownloadedHeight = lastBlockHeight
                Unit
            }
        }
        compactBlockStore.stub {
            onBlocking {
                getLatestHeight()
            }.thenAnswer { lastDownloadedHeight }
        }
        compactBlockStore.stub {
            onBlocking {
                rewindTo(any())
            }.thenAnswer { invocation ->
                lastDownloadedHeight = invocation.arguments[0] as Int
                Unit
            }
        }
        repository.stub {
            onBlocking {
                lastScannedHeight()
            }.thenAnswer { lastScannedHeight }
        }

        val config = ProcessorConfig(retries = 1, blockPollFrequencyMillis = frequency, downloadBatchSize = 50_000)
        val downloader = spy(CompactBlockDownloader(lightwalletService, compactBlockStore))
        processor = spy(CompactBlockProcessor(config, downloader, repository, rustBackend))

        whenever(rustBackend.validateCombinedChain(any(), any())).thenAnswer {
            errorBlock
        }

        whenever(rustBackend.scanBlocks(any(), any())).thenAnswer {
            true
        }
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun `check for OBOE when downloading`() = runBlocking {
        // if the last block downloaded was 350_000, then we already have that block and should start with 350_001
        lastDownloadedHeight = 350_000

        processBlocks()
        verify(processor).downloadNewBlocks(350_001..latestBlockHeight)
    }

    @Test
    fun `chain error rewinds by expected amount`() = runBlocking {
        // if the highest block whose prevHash doesn't match happens at block 300_010
        errorBlock = 300_010

        // then  we should rewind the default (10) blocks
        val expectedBlock = errorBlock - processor.config.rewindDistance
        processBlocks(100L)
        verify(processor.downloader, atLeastOnce()).rewindTo(expectedBlock)
        verify(rustBackend, atLeastOnce()).rewindToHeight("", expectedBlock)
        assertNotNull(processor)
    }

    @Test
    fun `chain error downloads expected number of blocks`() = runBlocking {
        // if the highest block whose prevHash doesn't match happens at block 300_010
        // and our rewind distance is the default (10), then we want to download exactly ten blocks
        errorBlock = 300_010

        // plus 1 because the range is inclusive
        val expectedRange = (errorBlock - processor.config.rewindDistance + 1)..latestBlockHeight
        processBlocks(1500L)
        verify(processor, atLeastOnce()).downloadNewBlocks(expectedRange)
    }

    private fun processBlocks(delayMillis: Long? = null) = runBlocking {
        launch { processor.start() }
        val progressChannel = processor.progress()
        for (i in progressChannel) {
            if(i >= 100) {
                if(delayMillis != null) delay(delayMillis)
                processor.stop()
                break
            }
        }
    }
}