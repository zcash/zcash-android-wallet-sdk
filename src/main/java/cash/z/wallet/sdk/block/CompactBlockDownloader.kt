package cash.z.wallet.sdk.block

import cash.z.wallet.sdk.data.twig
import cash.z.wallet.sdk.service.LightWalletService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * Serves as a source of compact blocks received from the light wallet server. Once started, it will use the given
 * lightwallet service to request all the appropriate blocks and compact block store to persist them. By delegating to
 * these dependencies, the downloader remains agnostic to the particular implementation of how to retrieve and store
 * data; although, by default the SDK uses gRPC and SQL.
 *
 * @property lightwalletService the service used for requesting compact blocks
 * @property compactBlockStore responsible for persisting the compact blocks that are received
 */
open class CompactBlockDownloader(
    val lightwalletService: LightWalletService,
    val compactBlockStore: CompactBlockStore
) {

    suspend fun downloadBlockRange(heightRange: IntRange) = withContext(IO) {
        val result = lightwalletService.getBlockRange(heightRange)
        compactBlockStore.write(result)
    }

    suspend fun rewindTo(height: Int) = withContext(IO) {
        // TODO: cancel anything in flight
        compactBlockStore.rewindTo(height)
    }

    suspend fun getLatestBlockHeight() = withContext(IO) {
        lightwalletService.getLatestBlockHeight()
    }

    suspend fun getLastDownloadedHeight() = withContext(IO) {
        compactBlockStore.getLatestHeight()
    }

}

