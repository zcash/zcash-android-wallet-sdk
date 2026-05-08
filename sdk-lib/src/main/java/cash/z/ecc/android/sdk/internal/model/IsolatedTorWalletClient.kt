package cash.z.ecc.android.sdk.internal.model

import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.util.use
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Tor wallet client that uses an isolated Tor connection for each individual RPC method query.
 */
class IsolatedTorWalletClient private constructor(
    private val torClient: TorClient,
    private val endpoint: String,
) : PartialTorWalletClient {
    private val semaphore = Mutex()

    override suspend fun dispose() = semaphore.withLock { torClient.dispose() }

    override suspend fun getServerInfo() = executeAndDispose { it.getServerInfo() }

    override suspend fun getLatestBlockHeight() = executeAndDispose { it.getLatestBlockHeight() }

    override suspend fun fetchTransaction(txId: ByteArray) = executeAndDispose { it.fetchTransaction(txId) }

    override suspend fun submitTransaction(tx: ByteArray) = executeAndDispose { it.submitTransaction(tx) }

    override suspend fun getTreeState(height: BlockHeightUnsafe) = executeAndDispose { it.getTreeState(height) }

    override suspend fun checkSingleUseTransparentAddress(accountUuid: ByteArray): Response<String?> =
        executeAndDispose { it.checkSingleUseTransparentAddress(accountUuid) }

    override suspend fun fetchUtxosByAddress(accountUuid: ByteArray, address: String): Response<String?> =
        executeAndDispose { it.fetchUtxosByAddress(accountUuid, address) }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> executeAndDispose(
        block: suspend (PartialTorWalletClient) -> Response<T>
    ): Response<T> =
        semaphore.withLock {
            try {
                torClient
                    .createWalletClient(endpoint)
                    .use {
                        block(it)
                    }
            } catch (e: Exception) {
                Response.Failure.OverTor(cause = e)
            }
        }

    companion object {
        suspend fun new(isolatedTorClient: TorClient, endpoint: String) =
            IsolatedTorWalletClient(isolatedTorClient, endpoint)
    }
}
