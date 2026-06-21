package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.wallet.sdk.internal.rpc.Service
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.BlockIDUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpointInfoUnsafe
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import co.electriccoin.lightwallet.client.model.TreeStateUnsafe
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TorWalletClient private constructor(
    private var nativeHandle: Long?,
    private val backend: TypesafeBackend
) : PartialTorWalletClient {
    private val semaphore = Mutex()

    override suspend fun dispose() =
        withContext(Dispatchers.IO) {
            semaphore.withLock {
                nativeHandle?.let { freeLightwalletdConnection(it) }
                nativeHandle = null
            }
        }

    override suspend fun getServerInfo(): Response<LightWalletEndpointInfoUnsafe> =
        execute {
            val serverInfo = getServerInfo(it)
            LightWalletEndpointInfoUnsafe.new(Service.LightdInfo.parseFrom(serverInfo))
        }

    override suspend fun getLatestBlockHeight(): Response<BlockHeightUnsafe> =
        execute {
            val latestBlock = getLatestBlock(it)
            val blockId = BlockIDUnsafe.new(Service.BlockID.parseFrom(latestBlock))
            BlockHeightUnsafe(blockId.height)
        }

    override suspend fun fetchTransaction(txId: ByteArray): Response<RawTransactionUnsafe> =
        execute {
            val transaction = fetchTransaction(it, txId)
            RawTransactionUnsafe.new(
                Service.RawTransaction
                    .newBuilder()
                    .setData(transaction.data.toByteString())
                    .setHeight(transaction.height)
                    .build()
            )
        }

    override suspend fun submitTransaction(tx: ByteArray): Response<SendResponseUnsafe> =
        execute {
            submitTransaction(it, tx)
            SendResponseUnsafe(0, "")
        }

    override suspend fun getTreeState(height: BlockHeightUnsafe): Response<TreeStateUnsafe> =
        execute {
            val treeState = getTreeState(it, height.value)
            TreeStateUnsafe.new(Service.TreeState.parseFrom(treeState))
        }

    override suspend fun checkSingleUseTransparentAddress(accountUuid: ByteArray): Response<String?> =
        execute {
            checkSingleUseTaddr(
                it,
                backend.dataDbFile.absolutePath,
                backend.network.id,
                accountUuid,
            )
        }

    override suspend fun fetchUtxosByAddress(accountUuid: ByteArray, address: String): Response<String?> =
        execute {
            when (
                val result =
                    fetchUtxosByAddress(
                        nativeHandle = it,
                        dbDataPath = backend.dataDbFile.absolutePath,
                        networkId = backend.network.id,
                        accountUuid = accountUuid,
                        address = address
                    )
            ) {
                is JniAddressCheckResult.Found -> result.address
                JniAddressCheckResult.NotFound -> null
            }
        }

    // internal suspend fun updateTransparentAddressTransactions(
    //     backend: TypesafeBackend,
    //     address: String,
    //     startHeight: BlockHeightUnsafe,
    //     endHeight: BlockHeightUnsafe?,
    // ): Response<JniAddressCheckResult> =
    //     execute {
    //         updateTransparentAddressTransactions(
    //             it,
    //             backend.dataDbFile.absolutePath,
    //             address,
    //             startHeight.value,
    //             endHeight?.value ?: -1,
    //             networkId = backend.network.id
    //         )
    //     }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> execute(
        block: (handle: Long) -> T
    ) = semaphore.withLock {
        withContext(Dispatchers.IO) {
            val nativeHandle = nativeHandle
            checkNotNull(nativeHandle) { "TorWalletClient is disposed" }
            try {
                Response.Success(block(nativeHandle))
            } catch (e: Exception) {
                Response.Failure.OverTor(cause = e)
            }
        }
    }

    companion object {
        internal suspend fun new(nativeHandle: Long, backend: TypesafeBackend): TorWalletClient =
            withContext(Dispatchers.IO) {
                TorWalletClient(nativeHandle, backend)
            }

        @JvmStatic
        private external fun freeLightwalletdConnection(nativeHandle: Long)

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getServerInfo(nativeHandle: Long): ByteArray

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getLatestBlock(nativeHandle: Long): ByteArray

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun fetchTransaction(nativeHandle: Long, txId: ByteArray): JniTransaction

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun submitTransaction(nativeHandle: Long, tx: ByteArray)

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getTreeState(nativeHandle: Long, fromHeight: Long): ByteArray

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        @Suppress("LongParameterList")
        private external fun checkSingleUseTaddr(
            nativeHandle: Long,
            dbDataPath: String,
            networkId: Int,
            accountUuid: ByteArray,
        ): String?

        // /**
        //  * @throws RuntimeException as a common indicator of the operation failure
        //  */
        // @JvmStatic
        // @Throws(RuntimeException::class)
        // @Suppress("LongParameterList")
        // private external fun updateTransparentAddressTransactions(
        //     nativeHandle: Long,
        //     dbDataPath: String,
        //     address: String,
        //     startHeight: Long,
        //     endHeight: Long,
        //     networkId: Int,
        // ): JniAddressCheckResult

        /**
         * @throws RuntimeException as a common indicator of the operation failure
         */
        @JvmStatic
        @Throws(RuntimeException::class)
        @Suppress("LongParameterList")
        private external fun fetchUtxosByAddress(
            nativeHandle: Long,
            dbDataPath: String,
            networkId: Int,
            accountUuid: ByteArray,
            address: String,
        ): JniAddressCheckResult
    }
}