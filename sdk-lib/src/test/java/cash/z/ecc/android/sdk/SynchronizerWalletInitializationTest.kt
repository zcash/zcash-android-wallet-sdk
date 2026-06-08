package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.model.JniBlockMeta
import cash.z.ecc.android.sdk.internal.model.TreeState
import cash.z.ecc.android.sdk.internal.repository.CompactBlockRepository
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SdkFlags
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.CompactBlockUnsafe
import co.electriccoin.lightwallet.client.model.GetAddressUtxosReplyUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import co.electriccoin.lightwallet.client.model.ShieldedProtocolEnum
import co.electriccoin.lightwallet.client.model.SubtreeRootUnsafe
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SynchronizerWalletInitializationTest {
    @Test
    fun new_wallet_uses_chain_tip_tree_state_when_fetches_succeed() =
        runBlocking {
            val tipHeight = BlockHeightUnsafe(2_000_000)
            val chainTipTreeState = treeStateUnsafe(height = tipHeight.value)
            val walletClient =
                FakeCombinedWalletClient(
                    latestBlockHeightResponse = Response.Success(tipHeight),
                    treeStateResponse = Response.Success(chainTipTreeState)
                )

            val result =
                resolveWalletInitializationState(
                    downloader = downloader(walletClient),
                    fallbackTreeState = treeState(height = 1_000_000),
                    sdkFlags = sdkFlags,
                    walletInitMode = WalletInitMode.NewWallet
                )

            assertTrue(chainTipTreeState.encoded.contentEquals(result.treeState.encoded))
            assertEquals(null, result.recoverUntil)
            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), walletClient.latestBlockHeightRequests)
            assertEquals(listOf(TreeStateRequest(tipHeight, ServiceMode.Direct)), walletClient.treeStateRequests)
        }

    @Test
    fun new_wallet_falls_back_to_checkpoint_when_height_fetch_fails() =
        runBlocking {
            val fallbackTreeState = treeState(height = 1_000_000)
            val walletClient =
                FakeCombinedWalletClient(
                    latestBlockHeightResponse = failure(),
                    treeStateResponse = Response.Success(treeStateUnsafe(height = 2_000_000))
                )

            val result =
                resolveWalletInitializationState(
                    downloader = downloader(walletClient),
                    fallbackTreeState = fallbackTreeState,
                    sdkFlags = sdkFlags,
                    walletInitMode = WalletInitMode.NewWallet
                )

            assertSame(fallbackTreeState, result.treeState)
            assertEquals(null, result.recoverUntil)
            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), walletClient.latestBlockHeightRequests)
            assertTrue(walletClient.treeStateRequests.isEmpty())
        }

    @Test
    fun new_wallet_falls_back_to_checkpoint_when_height_fetch_times_out() =
        runBlocking {
            val fallbackTreeState = treeState(height = 1_000_000)
            val neverCompletes = CompletableDeferred<Unit>()
            val walletClient =
                FakeCombinedWalletClient(
                    latestBlockHeightResponse = Response.Success(BlockHeightUnsafe(2_000_000)),
                    treeStateResponse = Response.Success(treeStateUnsafe(height = 2_000_000)),
                    beforeLatestBlockHeightResponse = { neverCompletes.await() }
                )

            val result =
                resolveWalletInitializationState(
                    downloader = downloader(walletClient),
                    fallbackTreeState = fallbackTreeState,
                    sdkFlags = sdkFlags,
                    walletInitMode = WalletInitMode.NewWallet,
                    newWalletTreeStateTimeout = 1.milliseconds
                )

            assertSame(fallbackTreeState, result.treeState)
            assertEquals(null, result.recoverUntil)
            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), walletClient.latestBlockHeightRequests)
            assertTrue(walletClient.treeStateRequests.isEmpty())
        }

    @Test
    fun new_wallet_falls_back_to_checkpoint_when_tree_state_fetch_fails() =
        runBlocking {
            val tipHeight = BlockHeightUnsafe(2_000_000)
            val fallbackTreeState = treeState(height = 1_000_000)
            val walletClient =
                FakeCombinedWalletClient(
                    latestBlockHeightResponse = Response.Success(tipHeight),
                    treeStateResponse = failure()
                )

            val result =
                resolveWalletInitializationState(
                    downloader = downloader(walletClient),
                    fallbackTreeState = fallbackTreeState,
                    sdkFlags = sdkFlags,
                    walletInitMode = WalletInitMode.NewWallet
                )

            assertSame(fallbackTreeState, result.treeState)
            assertEquals(null, result.recoverUntil)
            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), walletClient.latestBlockHeightRequests)
            assertEquals(listOf(TreeStateRequest(tipHeight, ServiceMode.Direct)), walletClient.treeStateRequests)
        }

    @Test
    fun new_wallet_falls_back_to_checkpoint_when_tree_state_fetch_times_out() =
        runBlocking {
            val tipHeight = BlockHeightUnsafe(2_000_000)
            val fallbackTreeState = treeState(height = 1_000_000)
            val neverCompletes = CompletableDeferred<Unit>()
            val walletClient =
                FakeCombinedWalletClient(
                    latestBlockHeightResponse = Response.Success(tipHeight),
                    treeStateResponse = Response.Success(treeStateUnsafe(height = tipHeight.value)),
                    beforeTreeStateResponse = { neverCompletes.await() }
                )

            val result =
                resolveWalletInitializationState(
                    downloader = downloader(walletClient),
                    fallbackTreeState = fallbackTreeState,
                    sdkFlags = sdkFlags,
                    walletInitMode = WalletInitMode.NewWallet,
                    newWalletTreeStateTimeout = 1.milliseconds
                )

            assertSame(fallbackTreeState, result.treeState)
            assertEquals(null, result.recoverUntil)
            assertEquals(listOf<ServiceMode>(ServiceMode.Direct), walletClient.latestBlockHeightRequests)
            assertEquals(listOf(TreeStateRequest(tipHeight, ServiceMode.Direct)), walletClient.treeStateRequests)
        }

    private class FakeCombinedWalletClient(
        private val latestBlockHeightResponse: Response<BlockHeightUnsafe>,
        private val treeStateResponse: Response<TreeStateUnsafe>,
        private val beforeLatestBlockHeightResponse: suspend () -> Unit = {},
        private val beforeTreeStateResponse: suspend () -> Unit = {}
    ) : CombinedWalletClient {
        val latestBlockHeightRequests = mutableListOf<ServiceMode>()
        val treeStateRequests = mutableListOf<TreeStateRequest>()

        override suspend fun getLatestBlockHeight(serviceMode: ServiceMode): Response<BlockHeightUnsafe> {
            latestBlockHeightRequests += serviceMode
            beforeLatestBlockHeightResponse()
            return latestBlockHeightResponse
        }

        override suspend fun getTreeState(
            height: BlockHeightUnsafe,
            serviceMode: ServiceMode
        ): Response<TreeStateUnsafe> {
            treeStateRequests += TreeStateRequest(height, serviceMode)
            beforeTreeStateResponse()
            return treeStateResponse
        }

        override suspend fun dispose() = Unit

        override fun reconnect() = Unit

        override suspend fun checkSingleUseTransparentAddress(
            accountUuid: ByteArray,
            serviceMode: ServiceMode
        ): Response<String?> = error("Unused")

        override suspend fun fetchTransaction(
            txId: ByteArray,
            serviceMode: ServiceMode
        ): Response<RawTransactionUnsafe> = error("Unused")

        override suspend fun fetchUtxos(
            tAddresses: List<String>,
            startHeight: BlockHeightUnsafe,
            serviceMode: ServiceMode
        ): Flow<Response<GetAddressUtxosReplyUnsafe>> = error("Unused")

        override suspend fun fetchUtxosByAddress(
            accountUuid: ByteArray,
            address: String,
            serviceMode: ServiceMode
        ): Response<String?> = error("Unused")

        override suspend fun getBlockRange(
            heightRange: ClosedRange<BlockHeightUnsafe>,
            serviceMode: ServiceMode
        ): Flow<Response<CompactBlockUnsafe>> = error("Unused")

        override suspend fun getServerInfo(serviceMode: ServiceMode): Response<LightWalletEndpointInfoUnsafe> =
            error("Unused")

        override suspend fun getSubtreeRoots(
            startIndex: UInt,
            shieldedProtocol: ShieldedProtocolEnum,
            maxEntries: UInt,
            serviceMode: ServiceMode
        ): Flow<Response<SubtreeRootUnsafe>> = error("Unused")

        override suspend fun getTAddressTransactions(
            tAddress: String,
            blockHeightRange: ClosedRange<BlockHeightUnsafe>,
            serviceMode: ServiceMode
        ): Flow<Response<RawTransactionUnsafe>> = error("Unused")

        override suspend fun observeMempool(serviceMode: ServiceMode): Flow<Response<RawTransactionUnsafe>> =
            error("Unused")

        override suspend fun submitTransaction(
            tx: ByteArray,
            serviceMode: ServiceMode
        ): Response<SendResponseUnsafe> = error("Unused")
    }

    private data class TreeStateRequest(
        val height: BlockHeightUnsafe,
        val serviceMode: ServiceMode
    )

    private companion object {
        val sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false)

        fun downloader(walletClient: CombinedWalletClient) =
            CompactBlockDownloader(walletClient, FakeCompactBlockRepository())

        fun treeState(height: Long) = TreeState.new(treeStateUnsafe(height))

        fun treeStateUnsafe(height: Long) =
            TreeStateUnsafe.fromParts(
                height = height,
                hash = "hash-$height",
                time = height.toInt(),
                saplingTree = "sapling-tree-$height",
                orchardTree = "orchard-tree-$height"
            )

        fun <T> failure(): Response.Failure<T> =
            Response.Failure.Connection(cause = IllegalStateException("test failure"))
    }

    private class FakeCompactBlockRepository : CompactBlockRepository {
        override suspend fun deleteAllCompactBlockFiles(): Boolean = error("Unused")

        override suspend fun deleteCompactBlockFiles(blocks: List<JniBlockMeta>): Boolean = error("Unused")

        override suspend fun findCompactBlock(height: BlockHeight): JniBlockMeta? = error("Unused")

        override suspend fun getLatestHeight(): BlockHeight? = error("Unused")

        override suspend fun rewindTo(height: BlockHeight) = error("Unused")

        override suspend fun write(blocks: Flow<CompactBlockUnsafe>): List<JniBlockMeta> = error("Unused")
    }
}
