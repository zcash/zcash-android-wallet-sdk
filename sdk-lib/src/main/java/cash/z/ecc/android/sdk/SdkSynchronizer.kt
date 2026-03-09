package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer.Status.DISCONNECTED
import cash.z.ecc.android.sdk.Synchronizer.Status.INITIALIZING
import cash.z.ecc.android.sdk.Synchronizer.Status.STOPPED
import cash.z.ecc.android.sdk.Synchronizer.Status.SYNCED
import cash.z.ecc.android.sdk.Synchronizer.Status.SYNCING
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor.State.Disconnected
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor.State.Initializing
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor.State.Stopped
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor.State.Synced
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor.State.Syncing
import cash.z.ecc.android.sdk.exception.CompactBlockProcessorException
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.exception.TorInitializationErrorException
import cash.z.ecc.android.sdk.exception.TorUnavailableException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.SaplingParamFetcher
import cash.z.ecc.android.sdk.internal.SaplingParamTool
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.TypesafeBackendImpl
import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.db.derived.DbDerivedDataRepository
import cash.z.ecc.android.sdk.internal.db.derived.DerivedDataDb
import cash.z.ecc.android.sdk.internal.exchange.UsdExchangeRateFetcher
import cash.z.ecc.android.sdk.internal.ext.existsSuspend
import cash.z.ecc.android.sdk.internal.ext.tryNull
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.model.Checkpoint
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorDormantMode
import cash.z.ecc.android.sdk.internal.model.TorHttp
import cash.z.ecc.android.sdk.internal.model.TreeState
import cash.z.ecc.android.sdk.internal.model.ZcashProtocol
import cash.z.ecc.android.sdk.internal.model.ext.toBlockHeight
import cash.z.ecc.android.sdk.internal.repository.CompactBlockRepository
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.storage.block.FileCompactBlockRepository
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.StandardPreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.StandardPreferenceKeys.SDK_VERSION_OF_LAST_FIX_WITNESSES_CALL
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManagerImpl
import cash.z.ecc.android.sdk.internal.transaction.TransactionEncoder
import cash.z.ecc.android.sdk.internal.transaction.TransactionEncoderImpl
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountUsk
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FetchFiatCurrencyResult
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.SingleUseTransparentAddress
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.CheckpointTool
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.android.sdk.type.AddressType.Shielded
import cash.z.ecc.android.sdk.type.AddressType.Tex
import cash.z.ecc.android.sdk.type.AddressType.Transparent
import cash.z.ecc.android.sdk.type.AddressType.Unified
import cash.z.ecc.android.sdk.type.ConsensusMatchType
import cash.z.ecc.android.sdk.type.ServerValidation
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.util.use
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * A Synchronizer that attempts to remain operational, despite any number of errors that can occur.
 * It acts as the glue that ties all the pieces of the SDK together. Each component of the SDK is
 * designed for the potential of stand-alone usage but coordinating all the interactions is non-
 * trivial. So the Synchronizer facilitates this, acting as reference that demonstrates how all the
 * pieces can be tied together. Its goal is to allow a developer to focus on their app rather than
 * the nuances of how Zcash works.
 *
 * @property synchronizerKey Identifies the synchronizer's on-disk state
 * @property storage exposes flows of wallet transaction information.
 * @property txManager manages and tracks outbound transactions.
 * @property processor saves the downloaded compact blocks to the cache and then scans those blocks for
 * data related to this wallet.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class SdkSynchronizer private constructor(
    private val context: Context,
    private val synchronizerKey: SynchronizerKey,
    private val storage: DerivedDataRepository,
    private val txManager: OutboundTransactionManager,
    val processor: CompactBlockProcessor,
    private val backend: TypesafeBackend,
    private val fetchFastestServers: FastestServerFetcher,
    private val fetchExchangeChangeUsd: UsdExchangeRateFetcher?,
    private val preferenceProvider: PreferenceProvider,
    private val torClient: TorClient?,
    private val walletClient: CombinedWalletClient,
    private val walletClientFactory: WalletClientFactory,
    private val sdkFlags: SdkFlags
) : CloseableSynchronizer {
    companion object {
        private sealed class InstanceState {
            data object Active : InstanceState()

            data class ShuttingDown(
                val job: Job
            ) : InstanceState()
        }

        private val instances: MutableMap<SynchronizerKey, InstanceState> =
            ConcurrentHashMap<SynchronizerKey, InstanceState>()

        private val mutex = Mutex()

        /**
         * Convenience method to create new SdkSynchronizer instance.
         *
         * @return Synchronizer instance as CloseableSynchronizer
         *
         * @throws IllegalStateException If multiple instances of synchronizer with the same network+alias are
         * active at the same time.  Call `close` to finish one synchronizer before starting another one with the same
         * network+alias.
         */
        @Suppress("LongParameterList")
        internal suspend fun new(
            context: Context,
            zcashNetwork: ZcashNetwork,
            alias: String,
            repository: DerivedDataRepository,
            txManager: OutboundTransactionManager,
            processor: CompactBlockProcessor,
            backend: TypesafeBackend,
            fastestServerFetcher: FastestServerFetcher,
            fetchExchangeChangeUsd: UsdExchangeRateFetcher?,
            preferenceProvider: PreferenceProvider,
            torClient: TorClient?,
            walletClient: CombinedWalletClient,
            walletClientFactory: WalletClientFactory,
            sdkFlags: SdkFlags
        ): CloseableSynchronizer {
            val synchronizerKey = SynchronizerKey(zcashNetwork, alias)
            return mutex.withLock {
                waitForShutdown(synchronizerKey)
                checkForExistingSynchronizers(synchronizerKey)
                SdkSynchronizer(
                    context = context,
                    synchronizerKey = synchronizerKey,
                    storage = repository,
                    txManager = txManager,
                    processor = processor,
                    backend = backend,
                    fetchFastestServers = fastestServerFetcher,
                    fetchExchangeChangeUsd = fetchExchangeChangeUsd,
                    preferenceProvider = preferenceProvider,
                    torClient = torClient,
                    walletClient = walletClient,
                    walletClientFactory = walletClientFactory,
                    sdkFlags = sdkFlags
                ).apply {
                    instances[synchronizerKey] = InstanceState.Active
                    start()
                }
            }
        }

        private suspend fun waitForShutdown(synchronizerKey: SynchronizerKey) {
            instances[synchronizerKey]?.let {
                if (it is InstanceState.ShuttingDown) {
                    Twig.debug { "Waiting for prior synchronizer instance to shut down" } // $NON-NLS-1$
                    it.job.join()
                }
            }
        }

        private fun checkForExistingSynchronizers(synchronizerKey: SynchronizerKey) {
            check(!instances.containsKey(synchronizerKey)) {
                "Another synchronizer with $synchronizerKey is currently active" // $NON-NLS-1$
            }
        }

        internal suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String
        ): Boolean {
            val key = SynchronizerKey(network, alias)

            return mutex.withLock {
                waitForShutdown(key)
                checkForExistingSynchronizers(key)

                val standardPrefsCleared = StandardPreferenceProvider(appContext).clear()
                val encryptedPrefsCleared = EncryptedPreferenceProvider(appContext).clear()

                Twig.info { "Both preferences cleared: ${standardPrefsCleared && encryptedPrefsCleared}" }

                DatabaseCoordinator.getInstance(appContext).deleteDatabases(network, alias)
            }
        }

        suspend fun estimateBirthdayHeight(
            context: Context,
            date: Instant,
            network: ZcashNetwork,
        ): BlockHeight =
            CheckpointTool.estimateBirthdayHeight(
                context = context,
                date = date,
                network = network
            )

        suspend fun estimateBirthdayDate(
            context: Context,
            blockHeight: BlockHeight,
            network: ZcashNetwork
        ): Instant? =
            CheckpointTool.estimateBirthdayDate(
                context = context,
                blockHeight = blockHeight,
                network = network
            )
    }

    private val _status = MutableStateFlow(DISCONNECTED)

    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val walletBalances = processor.walletBalances.asStateFlow()

    private val refreshExchangeRateUsd = MutableSharedFlow<Unit>()

    private var lastExchangeRateValue = ObserveFiatCurrencyResult()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val exchangeRateUsd =
        channelFlow {
            refreshExchangeRateUsd
                .onStart { emit(Unit) }
                .flatMapLatest {
                    flow {
                        if (fetchExchangeChangeUsd == null) {
                            emit(lastExchangeRateValue)
                        } else {
                            emit(lastExchangeRateValue.copy(isLoading = true))
                            lastExchangeRateValue =
                                when (val result = fetchExchangeChangeUsd.invoke()) {
                                    is FetchFiatCurrencyResult.Error -> lastExchangeRateValue.copy(isLoading = false)

                                    is FetchFiatCurrencyResult.Success ->
                                        lastExchangeRateValue.copy(
                                            isLoading = false,
                                            currencyConversion = result.currencyConversion
                                        )
                                }
                            emit(lastExchangeRateValue)
                        }
                    }
                }.onEach { send(it) }
                .flowOn(Dispatchers.Default)
                .launchIn(this)

            awaitClose {
                // do nothing
            }
        }.flowOn(Dispatchers.Default).stateIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(), // stop immediately
            initialValue = ObserveFiatCurrencyResult()
        )

    override val allTransactions
        get() =
            combine(processor.networkHeight, storage.allTransactions) { networkHeight, allTransactions ->
                val latestBlockHeight =
                    networkHeight ?: runCatching {
                        backend.getMaxScannedHeight()
                    }.onFailure {
                        Twig.error(it) { "Failed to get max scanned height" }
                    }.getOrNull()

                allTransactions
                    .map { TransactionOverview.new(it, latestBlockHeight) }
                    .map { it.checkAndFillInTime(storage) }
            }

    override val network: ZcashNetwork get() = processor.network

    /**
     * Indicates the status of this Synchronizer. This implementation basically simplifies the
     * status of the processor to focus only on the high level states that matter most. Whenever the
     * processor is finished scanning, the synchronizer updates transaction and balance info and
     * then emits a [SYNCED] status.
     */
    override val status = _status.asStateFlow()

    /**
     * Indicates the download progress of the Synchronizer.
     *
     * When progress reaches `PercentDecimal.ONE_HUNDRED_PERCENT`, it signals that the Synchronizer
     * is up-to-date with the network's current chain tip. Balances should be considered inaccurate
     * and outbound transactions should be prevented until this sync is complete.
     */
    override val progress: Flow<PercentDecimal> = processor.progress

    /**
     * Indicates whether are the shielded wallet balances spendable or not during the block synchronization process.
     */
    override val areFundsSpendable: Flow<Boolean> =
        processor.scanProgress.map { it == PercentDecimal.ONE_HUNDRED_PERCENT }

    /**
     * Indicates the latest information about the blocks that have been processed by the SDK. This
     * is very helpful for conveying detailed progress and status to the user.
     */
    override val processorInfo: Flow<CompactBlockProcessor.ProcessorInfo> = processor.processorInfo

    /**
     * The latest height seen on the network while processing blocks. This may differ from the
     * latest height scanned and is useful for determining block confirmations and expiration.
     */
    override val networkHeight: StateFlow<BlockHeight?> = processor.networkHeight

    //
    // Error Handling
    //

    /**
     * A callback to invoke whenever an uncaught error is encountered. By definition, the return
     * value of the function is ignored because this error is unrecoverable. The only reason the
     * function has a return value is so that all error handlers work with the same signature which
     * allows one function to handle all errors in simple apps. This callback is not called on the
     * main thread so any UI work would need to switch context to the main thread.
     */
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a processor error is encountered. Returning true signals that
     * the error was handled and a retry attempt should be made, if possible. This callback is not
     * called on the main thread so any UI work would need to switch context to the main thread.
     */
    override var onProcessorErrorHandler: ((Throwable?) -> Boolean)? = null

    override var onProcessorErrorResolved: (() -> Unit)? = null

    /**
     * A callback to invoke whenever a processor is not setup correctly. Returning true signals that
     * the invalid setup should be ignored. If no handler is set, then any setup error will result
     * in a critical error. This callback is not called on the main thread so any UI work would need
     * to switch context to the main thread.
     */
    override var onSetupErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a chain error is encountered. These occur whenever the
     * processor detects a missing or non-chain-sequential block (i.e. a reorg).
     */
    override var onChainErrorHandler: ((errorHeight: BlockHeight, rewindHeight: BlockHeight) -> Any)? = null

    //
    // Public API
    //

    /**
     * Convenience function for the latest height. Specifically, this value represents the last
     * height that the synchronizer has observed from the lightwalletd server. Instead of using
     * this, a wallet will more likely want to consume the flow of processor info using
     * [processorInfo].
     */
    override val latestHeight
        get() = processor.processorInfo.value.networkBlockHeight

    override val latestBirthdayHeight
        get() = processor.birthdayHeight

    override suspend fun getFastestServers(servers: List<LightWalletEndpoint>) = fetchFastestServers(servers)

    internal fun start() {
        coroutineScope.onReady()
    }

    override fun close() {
        // Note that stopping will continue asynchronously.  Race conditions with starting a new synchronizer are
        // avoided with a delay during startup.

        val shutdownJob =
            coroutineScope.launch {
                Twig.info { "Stopping synchronizer $synchronizerKey…" }
                processor.stop()
                torClient?.dispose()
                walletClient.dispose()
                fetchExchangeChangeUsd?.dispose()
            }

        instances[synchronizerKey] = InstanceState.ShuttingDown(shutdownJob)

        shutdownJob.invokeOnCompletion {
            coroutineScope.cancel()
            _status.value = STOPPED
            Twig.debug { "Synchronizer $synchronizerKey stopped" }

            instances.remove(synchronizerKey)
        }
    }

    /**
     * A Flow-providing version of [close] function. This safely closes the Synchronizer together with the related
     * components.
     */
    fun closeFlow(): Flow<Unit> =
        callbackFlow {
            val shutdownJob =
                coroutineScope.launch {
                    Twig.info { "Stopping synchronizer $synchronizerKey…" }
                    processor.stop()
                    torClient?.dispose()
                    walletClient.dispose()
                    fetchExchangeChangeUsd?.dispose()
                }

            instances[synchronizerKey] = InstanceState.ShuttingDown(shutdownJob)

            shutdownJob.invokeOnCompletion {
                coroutineScope.cancel()
                _status.value = STOPPED
                Twig.info { "Synchronizer $synchronizerKey stopped" }

                instances.remove(synchronizerKey)

                trySend(Unit)
            }

            awaitClose {
                // Nothing to close here
            }
        }

    override suspend fun rewindToNearestHeight(height: BlockHeight): BlockHeight? =
        processor.rewindToNearestHeight(height)

    override fun getMemos(transactionOverview: TransactionOverview): Flow<String> =
        storage.getOutputProperties(transactionOverview.txId).map { properties ->
            if (!properties.protocol.isShielded()) {
                ""
            } else {
                runCatching {
                    backend.getMemoAsUtf8(
                        txId = transactionOverview.txId.value.byteArray,
                        protocol = properties.protocol,
                        outputIndex = properties.index
                    )
                }.onFailure {
                    Twig.error { "Failed to get memo with: $it" }
                }.onSuccess {
                    Twig.debug { "Transaction memo queried: $it" }
                }.fold(
                    onSuccess = { it ?: "" },
                    onFailure = { "" }
                )
            }
        }

    override fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>> =
        storage.getTransactionsByMemoSubstring(query)

    override fun getRecipients(transactionOverview: TransactionOverview): Flow<TransactionRecipient> {
        require(transactionOverview.isSentTransaction) { "Recipients can only be queried for sent transactions" }

        return storage.getRecipients(transactionOverview.txId)
    }

    override suspend fun getTransactionOutputs(transactionOverview: TransactionOverview): List<TransactionOutput> =
        storage.getOutputProperties(transactionOverview.txId).toList().map {
            TransactionOutput(
                when (it.protocol) {
                    ZcashProtocol.TRANSPARENT -> TransactionPool.TRANSPARENT
                    ZcashProtocol.SAPLING -> TransactionPool.SAPLING
                    ZcashProtocol.ORCHARD -> TransactionPool.ORCHARD
                }
            )
        }

    override suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>> =
        combine(
            processor.networkHeight,
            storage.getTransactions(accountUuid)
        ) { networkHeight, allAccountTransactions ->
            val latestBlockHeight =
                networkHeight ?: runCatching {
                    backend.getMaxScannedHeight()
                }.onFailure {
                    Twig.error(it) { "Failed to get max scanned height" }
                }.getOrNull()

            allAccountTransactions
                .map { TransactionOverview.new(it, latestBlockHeight) }
                .map { it.checkAndFillInTime(storage) }
        }

    override suspend fun getSingleUseTransparentAddress(accountUuid: AccountUuid): SingleUseTransparentAddress =
        backend
            .getSingleUseTransparentAddress(
                accountUuid
            )

    override suspend fun checkSingleUseTransparentAddress(accountUuid: AccountUuid): Boolean =
        when (
            val result =
                walletClient
                    .checkSingleUseTransparentAddress(
                        accountUuid = accountUuid.value,
                        serviceMode = ServiceMode.UniqueTor
                    )
        ) {
            is Response.Success<String?> -> result.result != null
            is Response.Failure -> false
        }

    override suspend fun fetchUtxosByAddress(accountUuid: AccountUuid, address: String): Boolean =
        when (
            val result =
                walletClient
                    .fetchUtxosByAddress(
                        accountUuid = accountUuid.value,
                        address = address,
                        serviceMode = ServiceMode.UniqueTor
                    )
        ) {
            is Response.Failure -> false
            is Response.Success<String?> -> result.result != null
        }

    override fun enhanceTransaction(txId: TransactionId) {
        coroutineScope.launch {
            processor.enhanceTransaction(txId)
        }
    }

    override fun onBackground() {
        coroutineScope.launch { torClient?.setDormant(TorDormantMode.SOFT) }
    }

    override fun onForeground() {
        coroutineScope.launch { torClient?.setDormant(TorDormantMode.NORMAL) }
    }

    //
    // Storage APIs
    //

    // TODO [#682]: turn this section into the data access API. For now, just aggregate all the things that we want
    //  to do with the underlying data
    // TODO [#682]: https://github.com/zcash/zcash-android-wallet-sdk/issues/682

    suspend fun getTransactionCount(): Int = storage.getTransactionCount().toInt()

    fun refreshTransactions() {
        storage.invalidate()
    }

    /**
     * Recalculate the latest balance based on the blocks that have been scanned.
     */
    suspend fun refreshAllBalances() {
        processor.refreshWalletSummary()
    }

    override fun refreshExchangeRateUsd() {
        coroutineScope.launch { refreshExchangeRateUsd.emit(Unit) }
    }

    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    override suspend fun getTorHttpClient(
        config: HttpClientConfig<HttpClientEngineConfig>.() -> Unit
    ): HttpClient =
        if (sdkFlags.isTorEnabled || sdkFlags.isExchangeRateEnabled) {
            if (torClient == null) {
                throw TorInitializationErrorException(
                    NullPointerException("Tor has not been initialized during synchronizer setup")
                )
            }

            val isolatedTor =
                try {
                    torClient.isolatedTorClient()
                } catch (e: Exception) {
                    throw TorInitializationErrorException(e)
                }

            HttpClient(TorHttp) {
                engine {
                    tor = isolatedTor
                    retryLimit = 1
                }
                config(this as HttpClientConfig<HttpClientEngineConfig>)
            }
        } else {
            throw TorUnavailableException()
        }

    override suspend fun debugQuery(query: String): String = storage.debugQuery(query)

    suspend fun isValidAddress(address: String): Boolean = !validateAddress(address).isNotValid

    //
    // Private API
    //

    private fun CoroutineScope.onReady() {
        Twig.debug { "Starting synchronizer…" }

        // Verify processor setup as soon as possible
        launch(CoroutineExceptionHandler(::onCriticalError)) {
            processor.verifySetup()
        }

        // Triggering UTXOs and transactions fetching at the beginning of the block synchronization right after the
        //  app starts makes the transparent transactions appear faster.
        launch(CoroutineExceptionHandler(::onCriticalError)) {
            // Refresh UTXOs and transactions for all the wallet's accounts
            refreshAllAccountsUtxos()
            refreshTransactions()
        }

        launch(CoroutineExceptionHandler(::onCriticalError)) {
            dataMaintenance()
        }

        launch(CoroutineExceptionHandler(::onCriticalError)) {
            processor.onProcessorErrorListener = ::onProcessorError
            processor.onProcessorErrorResolved = ::onProcessorErrorResolved
            processor.onSetupErrorListener = ::onSetupError
            processor.onChainErrorListener = ::onChainError

            processor.state
                .onEach {
                    when (it) {
                        is Initializing -> INITIALIZING
                        is Synced -> {
                            onScanComplete()
                            SYNCED
                        }

                        is Stopped -> STOPPED
                        is Disconnected -> DISCONNECTED
                        is Syncing -> SYNCING
                    }.let { synchronizerStatus ->
                        _status.value = synchronizerStatus
                    }
                }.launchIn(this)
            processor.start()
            Twig.debug { "Completed starting synchronizer" }
        }

        launch {
            processor.startObservingMempool()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCriticalError(
        unused: CoroutineContext?,
        error: Throwable
    ) {
        Twig.error(error) { "Critical error occurred" }

        if (onCriticalErrorHandler == null) {
            Twig.debug {
                "WARNING: a critical error occurred but no callback is registered to be notified " +
                    "of critical errors! THIS IS PROBABLY A MISTAKE. To respond to these " +
                    "errors (perhaps to update the UI or alert the user) set " +
                    "synchronizer.onCriticalErrorHandler to a non-null value."
            }
        }
        onCriticalErrorHandler?.invoke(error)
    }

    private fun onProcessorError(error: Throwable): Boolean {
        Twig.debug { "ERROR while processing data: $error" }
        if (onProcessorErrorHandler == null) {
            Twig.debug {
                "WARNING: falling back to the default behavior for processor errors. To add" +
                    " custom behavior, set synchronizer.onProcessorErrorHandler to" +
                    " a non-null value"
            }
            return true
        }
        return onProcessorErrorHandler?.invoke(error)?.also {
            Twig.debug {
                "processor error handler signaled that we should " +
                    "${if (it) "try again" else "abort"}!"
            }
        } == true
    }

    private fun onProcessorErrorResolved() {
        onProcessorErrorResolved?.invoke()
    }

    private fun onSetupError(error: Throwable): Boolean {
        if (onSetupErrorHandler == null) {
            Twig.debug {
                "WARNING: falling back to the default behavior for setup errors. To add custom" +
                    " behavior, set synchronizer.onSetupErrorHandler to a non-null value"
            }
            return false
        }
        return onSetupErrorHandler?.invoke(error) == true
    }

    private fun onChainError(
        errorHeight: BlockHeight,
        rewindHeight: BlockHeight
    ) {
        Twig.debug { "Chain error detected at height: $errorHeight. Rewinding to: $rewindHeight" }
        if (onChainErrorHandler == null) {
            Twig.debug {
                "WARNING: a chain error occurred but no callback is registered to be notified of " +
                    "chain errors. To respond to these errors (perhaps to update the UI or alert the" +
                    " user) set synchronizer.onChainErrorHandler to a non-null value"
            }
        }
        onChainErrorHandler?.invoke(errorHeight, rewindHeight)
    }

    private suspend fun onScanComplete() {
        // Refresh UTXOs, balances, and transactions for all the wallet's accounts
        Twig.debug { "Triggering UTXOs refresh" }
        refreshAllAccountsUtxos()

        Twig.debug { "Triggering balance refresh" }
        refreshAllBalances()

        Twig.debug { "Triggering transaction refresh" }
        refreshTransactions()
    }

    private suspend fun refreshAllAccountsUtxos() {
        getAccounts().forEach {
            refreshUtxos(
                account = it,
                // Refreshing UTXOs from 0 to be able to discover e.g. blocked Ledger funds
                since = BlockHeight(0)
            )
        }
        processor.downloadSaplingParams()
    }

    private suspend fun dataMaintenance() {
        // Check and repair broken wallet data due to bugs in `shardtree`
        if (BuildConfig.LIBRARY_VERSION != preferenceProvider.getString(SDK_VERSION_OF_LAST_FIX_WITNESSES_CALL.key)) {
            Twig.info { "Wallet data check and repair starting..." }
            backend.fixWitnesses()
            SDK_VERSION_OF_LAST_FIX_WITNESSES_CALL.putValue(preferenceProvider, BuildConfig.LIBRARY_VERSION)
            Twig.info { "Wallet data check and repair done" }
        }
    }

    //
    // Account management
    //

    private val refreshAccountsBus = MutableSharedFlow<Unit>()

    // Not ready to be a public API; internal for testing only
    internal suspend fun createAccount(
        accountName: String,
        keySource: String?,
        recoverUntil: BlockHeight?,
        seed: FirstClassByteArray,
        treeState: TreeState,
    ): AccountUsk =
        runCatching {
            backend
                .createAccountAndGetSpendingKey(
                    accountName = accountName,
                    keySource = keySource,
                    seed = seed,
                    treeState = treeState,
                    recoverUntil = recoverUntil
                ).also {
                    refreshAccountsBus.emit(Unit)
                }
        }.onFailure {
            Twig.error(it) { "Create account failed." }
        }.getOrElse {
            throw InitializeException.CreateAccountException(it)
        }

    override suspend fun importAccountByUfvk(setup: AccountImportSetup): Account {
        val chainTip: BlockHeight? =
            when (
                val response =
                    processor.downloader
                        .getLatestBlockHeight(serviceMode = sdkFlags ifTor ServiceMode.UniqueTor)
            ) {
                is Response.Success -> {
                    Twig.info { "Chain tip for recovery until param fetched: ${response.result.value}" }
                    runCatching { response.result.toBlockHeight() }.getOrNull()
                }

                is Response.Failure -> {
                    Twig.error {
                        "Chain tip fetch for recovery until failed with: ${response.toThrowable()}"
                    }
                    null
                }
            }

        val loadedCheckpoint =
            CheckpointTool.loadNearest(
                context = context,
                network = network,
                chainTip ?: network.saplingActivationHeight
            )
        val treeState: TreeState = loadedCheckpoint.treeState()

        return runCatching {
            backend
                .importAccountUfvk(
                    recoverUntil = chainTip,
                    setup = setup,
                    treeState = treeState,
                ).also {
                    refreshAccountsBus.emit(Unit)
                }
        }.onFailure {
            Twig.error(it) { "Import account failed." }
        }.getOrElse {
            throw InitializeException.ImportAccountException(it)
        }
    }

    override suspend fun getAccounts(): List<Account> =
        runCatching {
            backend.getAccounts()
        }.onFailure {
            Twig.error(it) { "Get wallet accounts failed." }
        }.getOrElse {
            throw InitializeException.GetAccountsException(it)
        }

    override val accountsFlow: Flow<List<Account>?> =
        channelFlow {
            send(getAccounts())
            launch {
                refreshAccountsBus.collect {
                    send(getAccounts())
                }
            }
            awaitClose()
        }.stateIn(
            coroutineScope,
            SharingStarted.WhileSubscribed(),
            null
        )

    override val initializationError =
        if (torClient == null && sdkFlags.isTorEnabled) {
            Synchronizer.InitializationError.TOR_NOT_AVAILABLE
        } else {
            null
        }

    /**
     * Returns the current Unified Address for this account.
     */
    override suspend fun getUnifiedAddress(account: Account): String =
        CompactBlockProcessor.getCurrentAddress(
            backend,
            account
        )

    override suspend fun getCustomUnifiedAddress(account: Account, request: UnifiedAddressRequest): String =
        CompactBlockProcessor.getNextAvailableAddress(
            backend,
            account,
            request
        )

    /**
     * Returns the legacy Sapling address corresponding to the current Unified Address for this account.
     */
    override suspend fun getSaplingAddress(account: Account): String =
        CompactBlockProcessor.getLegacySaplingAddress(
            backend,
            account
        )

    /**
     * Returns the legacy transparent address corresponding to the current Unified Address for this account.
     */
    override suspend fun getTransparentAddress(account: Account): String =
        CompactBlockProcessor.getTransparentAddress(
            backend,
            account
        )

    /**
     * Creates a proposal for fulfilling a payment ZIP-321 URI
     *
     * @param account the account from which to transfer funds.
     * @param uri a ZIP-321 compliant payment URI String
     *
     * @throws TransactionEncoderException.ProposalFromUriException in case the proposal creation failed
     *
     * @return the proposal or an exception
     */
    @Throws(TransactionEncoderException.ProposalFromUriException::class)
    override suspend fun proposeFulfillingPaymentUri(
        account: Account,
        uri: String
    ): Proposal = txManager.proposeTransferFromUri(account, uri)

    /**
     * @throws TransactionEncoderException.ProposalFromParametersException in case the proposal creation failed
     *
     * @return the proposal or an exception
     */
    @Throws(TransactionEncoderException.ProposalFromParametersException::class)
    override suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal = txManager.proposeTransfer(account, recipient, amount, memo)

    /**
     * @throws TransactionEncoderException.ProposalShieldingException in case the proposal creation failed
     *
     * @return the proposal or an exception
     */
    @Throws(TransactionEncoderException.ProposalShieldingException::class)
    override suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal? = txManager.proposeShielding(account, shieldingThreshold, memo, transparentReceiver)

    @Throws(
        TransactionEncoderException.TransactionNotCreatedException::class,
        TransactionEncoderException.TransactionNotFoundException::class
    )
    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): Flow<TransactionSubmitResult> {
        // Internally, this logic submits and checks every incoming transaction, and once [Failure] or
        // [NotAttempted] submission result occurs, it returns [NotAttempted] for the rest of them
        var anySubmissionFailed = false
        return txManager
            .createProposedTransactions(proposal, usk)
            .asFlow()
            .map { transaction ->
                if (anySubmissionFailed) {
                    TransactionSubmitResult.NotAttempted(transaction.txId)
                } else {
                    val submission = txManager.submit(transaction)
                    when (submission) {
                        is TransactionSubmitResult.Success -> {
                            // Expected state
                        }

                        is TransactionSubmitResult.Failure,
                        is TransactionSubmitResult.NotAttempted -> {
                            anySubmissionFailed = true
                        }
                    }
                    submission
                }
            }
    }

    override suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ) = txManager.createPcztFromProposal(accountUuid, proposal)

    override suspend fun redactPcztForSigner(pczt: Pczt) = txManager.redactPcztForSigner(pczt)

    override suspend fun pcztRequiresSaplingProofs(pczt: Pczt) = txManager.pcztRequiresSaplingProofs(pczt)

    override suspend fun addProofsToPczt(pczt: Pczt) = txManager.addProofsToPczt(pczt)

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): Flow<TransactionSubmitResult> {
        // Internally, this logic submits and checks the newly stored and encoded transaction
        return flowOf(txManager.extractAndStoreTxFromPczt(pcztWithProofs, pcztWithSignatures))
            .map { transaction -> txManager.submit(transaction) }
    }

    override suspend fun refreshUtxos(
        account: Account,
        since: BlockHeight
    ): Int = processor.refreshUtxos(account, since)

    override suspend fun getTransparentBalance(tAddr: String): Zatoshi = processor.getUtxoCacheBalance(tAddr)

    override suspend fun isValidShieldedAddr(address: String) = txManager.isValidShieldedAddress(address)

    override suspend fun isValidTransparentAddr(address: String) = txManager.isValidTransparentAddress(address)

    override suspend fun isValidUnifiedAddr(address: String) = txManager.isValidUnifiedAddress(address)

    override suspend fun isValidTexAddr(address: String) = txManager.isValidTexAddress(address)

    override suspend fun validateAddress(address: String): AddressType =
        runCatching {
            if (isValidShieldedAddr(address)) {
                Shielded
            } else if (isValidTransparentAddr(address)) {
                Transparent
            } else if (isValidUnifiedAddr(address)) {
                Unified
            } else if (isValidTexAddr(address)) {
                Tex
            } else {
                AddressType.Invalid("Not a Zcash address")
            }
        }.getOrElse { error ->
            AddressType.Invalid(error.message ?: "Invalid")
        }

    // TODO [#1405]: Fix/Remove broken SdkSynchronizer.validateConsensusBranch function
    // TODO [#1405]: https://github.com/Electric-Coin-Company/zcash-android-wallet-sdk/issues/1405
    override suspend fun validateConsensusBranch(): ConsensusMatchType {
        val serverBranchId =
            tryNull {
                processor.downloader
                    .getServerInfo(
                        serviceMode =
                            sdkFlags ifTor
                                ServiceMode.Group("SdkSynchronizer.validateConsensusBranch")
                    )?.consensusBranchId
            }

        val currentChainTip =
            when (
                val response =
                    processor.downloader.getLatestBlockHeight(
                        serviceMode =
                            sdkFlags ifTor
                                ServiceMode.Group(
                                    "SdkSynchronizer.validateConsensusBranch"
                                )
                    )
            ) {
                is Response.Success -> {
                    Twig.info { "Chain tip for validate consensus branch action fetched: ${response.result.value}" }
                    runCatching { response.result.toBlockHeight() }.getOrNull()
                }

                is Response.Failure -> {
                    Twig.error {
                        "Chain tip fetch failed for validate consensus branch action with:" +
                            " ${response.toThrowable()}"
                    }
                    null
                }
            }

        val sdkBranchId =
            currentChainTip?.let {
                tryNull {
                    (txManager as OutboundTransactionManagerImpl).encoder.getConsensusBranchId(currentChainTip)
                }
            }

        return ConsensusMatchType(
            sdkBranchId?.let { ConsensusBranchId.fromId(it) },
            serverBranchId?.let { ConsensusBranchId.fromHex(it) }
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    override suspend fun validateServerEndpoint(
        context: Context,
        endpoint: LightWalletEndpoint
    ): ServerValidation {
        // Create a dedicated light wallet client for the validation
        // The single request timeout is changed from default to 5 seconds to speed up a possible custom server
        // endpoint validation
        walletClientFactory
            .create(endpoint = endpoint)
            .use { lightWalletClient ->
                val remoteInfo =
                    when (
                        val response =
                            lightWalletClient.getServerInfo(
                                sdkFlags ifTor
                                    ServiceMode.Group(
                                        "SdkSynchronizer.validateServerEndpoint(${endpoint.host}:${endpoint.port})"
                                    )
                            )
                    ) {
                        is Response.Success -> response.result
                        is Response.Failure -> {
                            return ServerValidation.InValid(response.toThrowable())
                        }
                    }

                // Check network type
                if (!remoteInfo.matchingNetwork(network.networkName)) {
                    return ServerValidation.InValid(
                        CompactBlockProcessorException.MismatchedNetwork(
                            clientNetwork = network.networkName,
                            serverNetwork = remoteInfo.chainName
                        )
                    )
                }

                // Check sapling activation height
                runCatching {
                    val remoteSaplingActivationHeight = remoteInfo.saplingActivationHeightUnsafe.toBlockHeight()
                    if (network.saplingActivationHeight != remoteSaplingActivationHeight) {
                        return ServerValidation.InValid(
                            CompactBlockProcessorException.MismatchedSaplingActivationHeight(
                                clientHeight = network.saplingActivationHeight.value,
                                serverHeight = remoteSaplingActivationHeight.value
                            )
                        )
                    }
                }.getOrElse {
                    return ServerValidation.InValid(it)
                }

                val currentChainTip =
                    when (
                        val response =
                            lightWalletClient.getLatestBlockHeight(
                                serviceMode =
                                    sdkFlags ifTor
                                        ServiceMode.Group(
                                            "SdkSynchronizer.validateServerEndpoint(${endpoint.host}:${endpoint.port})"
                                        )
                            )
                    ) {
                        is Response.Success -> {
                            runCatching { response.result.toBlockHeight() }.getOrElse {
                                return ServerValidation.InValid(it)
                            }
                        }

                        is Response.Failure -> {
                            return ServerValidation.InValid(response.toThrowable())
                        }
                    }

                val sdkBranchId =
                    runCatching {
                        "%x".format(
                            Locale.ROOT,
                            backend.getBranchIdForHeight(currentChainTip)
                        )
                    }.getOrElse {
                        return ServerValidation.InValid(it)
                    }

                // Check branch id
                return if (remoteInfo.consensusBranchId.equals(sdkBranchId, true)) {
                    ServerValidation.Valid
                } else {
                    ServerValidation.InValid(
                        CompactBlockProcessorException.MismatchedConsensusBranch(
                            sdkBranchId,
                            remoteInfo.consensusBranchId
                        )
                    )
                }
            }
    }

    @Throws(InitializeException.MissingDatabaseException::class)
    override suspend fun getExistingDataDbFilePath(
        context: Context,
        network: ZcashNetwork,
        alias: String
    ): String =
        DatabaseCoordinator
            .getInstance(context)
            .dataDbFile(
                network = network,
                alias = alias
            ).run {
                if (!existsSuspend()) {
                    throw InitializeException.MissingDatabaseException(network, alias)
                }
                absolutePath
            }
}

/**
 * Provides a way of constructing a synchronizer where dependencies are injected in.
 *
 * See the helper methods for generating default values.
 */
internal object DefaultSynchronizerFactory {
    internal suspend fun defaultBackend(
        network: ZcashNetwork,
        alias: String,
        saplingParamTool: SaplingParamTool,
        coordinator: DatabaseCoordinator
    ): TypesafeBackend =
        TypesafeBackendImpl(
            RustBackend.new(
                coordinator.fsBlockDbRoot(network, alias),
                coordinator.dataDbFile(network, alias),
                saplingOutputFile = saplingParamTool.outputParamsFile,
                saplingSpendFile = saplingParamTool.spendParamsFile,
                zcashNetworkId = network.id
            )
        )

    @Suppress("LongParameterList")
    internal suspend fun defaultDerivedDataRepository(
        context: Context,
        databaseFile: File,
        checkpoint: Checkpoint,
        recoverUntil: BlockHeight?,
        rustBackend: TypesafeBackend,
        setup: AccountCreateSetup?,
    ): DerivedDataRepository =
        DbDerivedDataRepository(
            DerivedDataDb.new(
                context = context,
                backend = rustBackend,
                databaseFile = databaseFile,
                checkpoint = checkpoint,
                recoverUntil = recoverUntil,
                setup = setup,
            )
        )

    internal suspend fun defaultCompactBlockRepository(
        blockCacheRoot: File,
        backend: TypesafeBackend
    ): CompactBlockRepository =
        FileCompactBlockRepository.new(
            blockCacheRoot,
            backend
        )

    internal fun defaultEncoder(
        backend: TypesafeBackend,
        saplingParamFetcher: SaplingParamFetcher,
        repository: DerivedDataRepository
    ): TransactionEncoder = TransactionEncoderImpl(backend, saplingParamFetcher, repository)

    fun defaultDownloader(
        walletClient: CombinedWalletClient,
        blockStore: CompactBlockRepository
    ): CompactBlockDownloader = CompactBlockDownloader(walletClient, blockStore)

    internal fun defaultTxManager(
        encoder: TransactionEncoder,
        service: CombinedWalletClient,
        sdkFlags: SdkFlags
    ): OutboundTransactionManager = OutboundTransactionManagerImpl.new(encoder, service, sdkFlags)

    @Suppress("LongParameterList")
    internal fun defaultProcessor(
        backend: TypesafeBackend,
        downloader: CompactBlockDownloader,
        repository: DerivedDataRepository,
        birthdayHeight: BlockHeight,
        txManager: OutboundTransactionManager,
        sdkFlags: SdkFlags,
        saplingParamFetcher: SaplingParamFetcher
    ): CompactBlockProcessor =
        CompactBlockProcessor(
            backend = backend,
            downloader = downloader,
            minimumHeight = birthdayHeight,
            repository = repository,
            txManager = txManager,
            sdkFlags = sdkFlags,
            saplingParamFetcher = saplingParamFetcher
        )
}

internal data class SynchronizerKey(
    val zcashNetwork: ZcashNetwork,
    val alias: String
)
