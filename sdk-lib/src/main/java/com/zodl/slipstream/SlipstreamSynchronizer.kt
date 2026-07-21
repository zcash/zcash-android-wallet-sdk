package com.zodl.slipstream

import android.app.ActivityManager
import android.content.Context
import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.exception.CompactBlockProcessorException
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.RustLayerException
import cash.z.ecc.android.sdk.exception.TorInitializationErrorException
import cash.z.ecc.android.sdk.exception.TorUnavailableException
import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.Files
import cash.z.ecc.android.sdk.internal.TypesafeBackendImpl
import cash.z.ecc.android.sdk.internal.exchange.UsdExchangeRateFetcher
import cash.z.ecc.android.sdk.internal.ext.getNoBackupFilesDirSuspend
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorDormantMode
import cash.z.ecc.android.sdk.internal.model.TorHttp
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
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
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.CheckpointTool
import cash.z.ecc.android.sdk.tool.DerivationTool
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
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import com.zodl.slipstream.SlipstreamSynchronizer.Companion.newLocked
import com.zodl.slipstream.internal.DataDbPath
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import com.zodl.slipstream.internal.db.TransactionsController
import com.zodl.slipstream.internal.newestBundledCheckpointHeight
import com.zodl.slipstream.internal.resolveIntent
import com.zodl.slipstream.internal.shouldCreateAccount
import com.zodl.slipstream.internal.spend.ResubmissionTicker
import com.zodl.slipstream.internal.spend.SaplingParams
import com.zodl.slipstream.internal.spend.SlipstreamBroadcaster
import com.zodl.slipstream.internal.spend.SlipstreamSpendService
import com.zodl.slipstream.internal.spend.SubmitPlanStore
import com.zodl.slipstream.internal.toProcessorInfo
import com.zodl.slipstream.internal.validateAlias
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The three facts [SlipstreamSynchronizer.Companion.newLocked] derives from resolving
 * [WalletInitMode]'s anchor - not just `startBirthday` (`engine.start`'s scan floor) but also the
 * `treeState` and `recoverUntil` the upstream SDK threads through
 * `resolveWalletInitializationState` -> `DerivedDataDb.new` (`Synchronizer.kt` ~1138,
 * `DerivedDataDb.kt` ~126) to provision the fresh-wallet account row. Bundled together so the
 * `when(intent)` block computes each of an anchor's three derived facts exactly once.
 */
private data class WalletProvisioningPlan(
    val startBirthday: BlockHeight,
    val treeState: ByteArray,
    val recoverUntil: Long?
)

/**
 * `SlipstreamSynchronizer : CloseableSynchronizer` - the drop-in replacement for `SdkSynchronizer`
 * behind the Slipstream engine. Owns provisioning (`Companion.new`, `importAccountByUfvk`),
 * lifecycle (`close`/`onForeground`/`onBackground`/`erase`), and spend/PCZT/broadcaster
 * delegation.
 *
 * Two single-threaded lanes throughout: all [SlipstreamEngine] calls serialize on
 * `slipstream-io`; all [Backend]/`RustBackend` calls serialize on `sdk-lib`'s own `zc-io`.
 * Cross-lane DB safety is WAL + `busy_timeout`, never thread exclusion.
 */
class SlipstreamSynchronizer internal constructor(
    private val context: Context,
    override val network: ZcashNetwork,
    private val alias: String,
    private val key: SlipstreamKey,
    private val engine: SlipstreamEngine,
    private val backend: Backend,
    private val walletClient: CombinedWalletClient,
    private val walletClientFactory: WalletClientFactory,
    private val defaultEndpoint: LightWalletEndpoint,
    private val lazyTorClient: LazyTorClient?,
    private val exchangeRateFetcher: UsdExchangeRateFetcher?,
    private val sdkFlags: SdkFlags,
    private val fastestServerFetcher: FastestServerFetcher,
    private val transactionReader: SlipstreamTransactionReader,
    private val transactionsController: TransactionsController,
    private val spendService: SlipstreamSpendService,
    private val broadcasterImpl: SlipstreamBroadcaster,
    private val resubmissionTicker: ResubmissionTicker,
    private var startBirthday: BlockHeight,
) : CloseableSynchronizer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val accountsBus = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val refreshExchangeRateTrigger = MutableSharedFlow<Unit>()
    private var lastExchangeRateValue = ObserveFiatCurrencyResult()

    /** R13's double-free guard - Kotlin has no deinit, so `close()` must be safely re-entrant. */
    private val closed = AtomicBoolean(false)

    override val status: Flow<Synchronizer.Status> = engine.status
    override val progress: Flow<PercentDecimal> = engine.progress
    override val areFundsSpendable: Flow<Boolean> = engine.areFundsSpendable
    override val networkHeight = engine.networkHeight
    override val fullyScannedHeight = engine.fullyScannedHeight
    override val walletBalances = engine.walletBalances
    override val allTransactions: Flow<List<TransactionOverview>> = transactionsController.allTransactions

    /** Degraded on purpose - `overallSyncRange`/`firstUnenhancedHeight` describe processor internals that don't exist under this engine. */
    override val processorInfo: Flow<CompactBlockProcessor.ProcessorInfo> = engine.networkHeight.map(::toProcessorInfo)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val exchangeRateUsd: StateFlow<ObserveFiatCurrencyResult> =
        channelFlow {
            refreshExchangeRateTrigger
                .onStart { emit(Unit) }
                .flatMapLatest {
                    flow {
                        if (exchangeRateFetcher == null) {
                            emit(lastExchangeRateValue)
                        } else {
                            emit(lastExchangeRateValue.copy(isLoading = true))
                            lastExchangeRateValue =
                                when (val result = exchangeRateFetcher()) {
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
        }.flowOn(Dispatchers.Default).stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = ObserveFiatCurrencyResult()
        )

    override val latestHeight: BlockHeight?
        get() = engine.lastSnapshot.value?.chainTip?.takeIf { it > 0 }?.let(BlockHeight::new)

    override val latestBirthdayHeight: BlockHeight?
        get() = startBirthday

    /**
     * [lazyTorClient] is only ever `null` when [SdkFlags.isTorEnabled] is also `false` (see how [lazyTorClient]
     * is constructed in [Companion.newLocked]), so this condition is never actually met: Tor client creation is
     * lazy, and its failure is no longer observable at construction time. See
     * [Synchronizer.InitializationError.TOR_NOT_AVAILABLE].
     */
    override val initializationError: Synchronizer.InitializationError?
        get() = if (lazyTorClient == null && sdkFlags.isTorEnabled) Synchronizer.InitializationError.TOR_NOT_AVAILABLE else null

    override val broadcaster: Broadcaster get() = broadcasterImpl

    override val accountsFlow: Flow<List<Account>?> =
        accountsBus.onStart { emit(Unit) }.map { runCatching { getAccounts() }.getOrNull() }

    /** R62-R64: forwarded straight to the engine, which owns the tick and the error-episode state (Phase 4 T10). */
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)?
        get() = engine.onCriticalErrorHandler
        set(value) {
            engine.onCriticalErrorHandler = value
        }

    override var onProcessorErrorHandler: ((Throwable?) -> Boolean)?
        get() = engine.onProcessorErrorHandler
        set(value) {
            engine.onProcessorErrorHandler = value
        }

    override var onProcessorErrorResolved: (() -> Unit)?
        get() = engine.onProcessorErrorResolved
        set(value) {
            engine.onProcessorErrorResolved = value
        }

    /**
     * Settable for interface completeness, but never invoked: [Companion.new] throws directly on
     * construction-time failures rather than returning a half-built instance to call this on.
     * Server-mismatch and runtime setup problems surface through [onProcessorErrorHandler] instead.
     */
    override var onSetupErrorHandler: ((Throwable?) -> Boolean)? = null

    /** Settable, but never invoked - the engine owns reorg recovery internally; there is no host-visible chain-error event. */
    override var onChainErrorHandler: ((BlockHeight, BlockHeight) -> Any)? = null

    /**
     * The Android [Synchronizer] interface has no public `start()`; `new` auto-starts the poll
     * loop as part of construction, and [onForeground]/[onBackground] only pause/resume it
     * thereafter. [SlipstreamEngine.startPolling] cancels any prior job before launching, so
     * this is just the loop's first start, not a restart.
     */
    init {
        engine.onTick =
            { snap -> resubmissionTicker.onTick(engine.status.value == Synchronizer.Status.SYNCED, snap.chainTip) }
        engine.startPolling()
    }

    override suspend fun getAccounts(): List<Account> = backend.getAccounts().map(Account::new)

    /**
     * Sequence: `restoreAnchor(intent = 1)` for `recoverUntil` (network-only, engine stays live)
     * -> stop engine -> `RustBackend.importAccountUfvk` -> restart. The restart's `ufvk = null`
     * is `FFI_JNI_CONTRACT.md` section 3.5's keyless mode - legal here only because the
     * `importAccountUfvk` call just above it wrote that account row.
     */
    override suspend fun importAccountByUfvk(setup: AccountImportSetup): Account {
        val fallbackCheckpoint = newestBundledCheckpointHeight(context, network)
        val anchor =
            withContext(Dispatchers.IO) {
                SlipstreamNative.restoreAnchor(
                    serverHost = defaultEndpoint.host,
                    serverPort = defaultEndpoint.port,
                    useTls = defaultEndpoint.isSecure,
                    networkId = network.id,
                    intent = 1,
                    birthdayHeight = (setup.birthday?.value) ?: fallbackCheckpoint,
                    fallbackCheckpointHeight = fallbackCheckpoint,
                    torDir = null
                )
            }
        val recoverUntil = anchor.height
        val treeState = CheckpointTool.loadNearest(context, network, setup.birthday).treeState().encoded
        val purpose = setup.purpose

        engine.stop()
        val jniAccount =
            backend.importAccountUfvk(
                accountName = setup.accountName,
                keySource = setup.keySource,
                ufvk = setup.ufvk.encoding,
                treeState = treeState,
                recoverUntil = recoverUntil,
                purpose = purpose.value,
                seedFingerprint = (purpose as? AccountPurpose.Spending)?.seedFingerprint,
                zip32AccountIndex = (purpose as? AccountPurpose.Spending)?.zip32AccountIndex?.index
            )
        engine.start(ufvk = null, birthday = startBirthday.value)
        accountsBus.tryEmit(Unit)
        return Account.new(jniAccount)
    }

    override suspend fun getFastestServers(servers: List<LightWalletEndpoint>) = fastestServerFetcher(servers)

    override suspend fun getUnifiedAddress(account: Account): String =
        wrapGetAddress { backend.getCurrentAddress(account.accountUuid.value) }

    override suspend fun getCustomUnifiedAddress(
        account: Account,
        request: UnifiedAddressRequest
    ): String = wrapGetAddress { backend.getNextAvailableAddress(account.accountUuid.value, request.flags) }

    override suspend fun getSaplingAddress(account: Account): String =
        wrapGetAddress {
            val ua = backend.getCurrentAddress(account.accountUuid.value)
            requireNotNull(backend.getSaplingReceiver(ua)) { "No Sapling receiver for this account's address" }
        }

    override suspend fun getTransparentAddress(account: Account): String =
        wrapGetAddress {
            val ua = backend.getCurrentAddress(account.accountUuid.value)
            requireNotNull(backend.getTransparentReceiver(ua)) { "No transparent receiver for this account's address" }
        }

    private suspend fun wrapGetAddress(block: suspend () -> String): String =
        runCatching { block() }.getOrElse { throw RustLayerException.GetAddressException(it) }

    override fun refreshExchangeRateUsd() {
        scope.launch { refreshExchangeRateTrigger.emit(Unit) }
    }

    override suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal = spendService.proposeTransfer(account, recipient, amount, memo)

    override suspend fun proposeFulfillingPaymentUri(
        account: Account,
        uri: String
    ): Proposal = spendService.proposeFulfillingPaymentUri(account, uri)

    override suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal? = spendService.proposeShielding(account, shieldingThreshold, memo, transparentReceiver)

    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): Flow<TransactionSubmitResult> = spendService.createProposedTransactions(proposal, usk)

    override suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt =
        runCatching { spendService.createPcztFromProposal(accountUuid, proposal) }
            .getOrElse { throw PcztException.CreatePcztFromProposalException(it.message, it) }

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt =
        runCatching { spendService.redactPcztForSigner(pczt) }
            .getOrElse { throw PcztException.RedactPcztForSignerException(it.message, it) }

    override suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean =
        runCatching { spendService.pcztRequiresSaplingProofs(pczt) }
            .getOrElse { throw PcztException.PcztRequiresSaplingProofsException(it.message, it) }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt =
        runCatching { spendService.addProofsToPczt(pczt) }
            .getOrElse { throw PcztException.AddProofsToPcztException(it.message, it) }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): Flow<TransactionSubmitResult> = spendService.createTransactionFromPczt(pcztWithProofs, pcztWithSignatures)

    override suspend fun isValidShieldedAddr(address: String): Boolean = backend.isValidSaplingAddr(address)

    override suspend fun isValidTransparentAddr(address: String): Boolean = backend.isValidTransparentAddr(address)

    override suspend fun isValidUnifiedAddr(address: String): Boolean = backend.isValidUnifiedAddr(address)

    override suspend fun isValidTexAddr(address: String): Boolean = backend.isValidTexAddr(address)

    /** R49: composition of R45-R48 in their exact order, `AddressType.Invalid` on failure (ports `SdkSynchronizer.kt`'s `validateAddress`). */
    override suspend fun validateAddress(address: String): AddressType =
        runCatching {
            when {
                isValidShieldedAddr(address) -> Shielded
                isValidTransparentAddr(address) -> Transparent
                isValidUnifiedAddr(address) -> Unified
                isValidTexAddr(address) -> Tex
                else -> AddressType.Invalid("Not a Zcash address")
            }
        }.getOrElse { AddressType.Invalid(it.message ?: "Invalid") }

    /**
     * R51: ported verbatim, including the upstream's own caveat that this check is unreliable
     * (upstream issue #1405: the two branch IDs being compared can come from mismatched heights).
     * Behavior-preserving beats silently-fixed - see `KOTLIN_ROSETTA.md` section 4.8.
     */
    override suspend fun validateConsensusBranch(): ConsensusMatchType {
        val serviceMode = sdkFlags ifTor ServiceMode.Group("SlipstreamSynchronizer.validateConsensusBranch")
        val serverBranchId =
            runCatching { (walletClient.getServerInfo(serviceMode) as? Response.Success)?.result?.consensusBranchId }.getOrNull()
        val currentChainTip =
            when (val response = walletClient.getLatestBlockHeight(serviceMode)) {
                is Response.Success -> runCatching { BlockHeight.new(response.result.value) }.getOrNull()
                is Response.Failure -> null
            }
        val sdkBranchId =
            currentChainTip?.let { tip -> runCatching { backend.getBranchIdForHeight(tip.value) }.getOrNull() }

        return ConsensusMatchType(
            sdkBranch = sdkBranchId?.let(ConsensusBranchId::fromId),
            serverBranch = serverBranchId?.let(ConsensusBranchId::fromHex)
        )
    }

    override suspend fun validateServerEndpoint(
        context: Context,
        endpoint: LightWalletEndpoint
    ): ServerValidation {
        val client = walletClientFactory.create(endpoint)
        try {
            val serviceMode =
                sdkFlags ifTor ServiceMode.Group("SlipstreamSynchronizer.validateServerEndpoint(${endpoint.host}:${endpoint.port})")
            val remoteInfo =
                when (val response = client.getServerInfo(serviceMode)) {
                    is Response.Success -> response.result
                    is Response.Failure -> return ServerValidation.InValid(response.toThrowable())
                }
            if (!remoteInfo.matchingNetwork(network.networkName)) {
                return ServerValidation.InValid(
                    CompactBlockProcessorException.MismatchedNetwork(
                        clientNetwork = network.networkName,
                        serverNetwork = remoteInfo.chainName
                    )
                )
            }
            val remoteSaplingActivation =
                runCatching { BlockHeight.new(remoteInfo.saplingActivationHeightUnsafe.value) }.getOrElse {
                    return ServerValidation.InValid(
                        it
                    )
                }
            if (network.saplingActivationHeight != remoteSaplingActivation) {
                return ServerValidation.InValid(
                    CompactBlockProcessorException.MismatchedSaplingActivationHeight(
                        clientHeight = network.saplingActivationHeight.value,
                        serverHeight = remoteSaplingActivation.value
                    )
                )
            }
            val currentChainTip =
                when (val response = client.getLatestBlockHeight(serviceMode)) {
                    is Response.Success -> runCatching { BlockHeight.new(response.result.value) }.getOrElse {
                        return ServerValidation.InValid(
                            it
                        )
                    }

                    is Response.Failure -> return ServerValidation.InValid(response.toThrowable())
                }
            val sdkBranchId =
                runCatching { "%x".format(Locale.ROOT, backend.getBranchIdForHeight(currentChainTip.value)) }
                    .getOrElse { return ServerValidation.InValid(it) }
            return if (remoteInfo.consensusBranchId.equals(sdkBranchId, ignoreCase = true)) {
                ServerValidation.Valid
            } else {
                ServerValidation.InValid(
                    CompactBlockProcessorException.MismatchedConsensusBranch(sdkBranchId, remoteInfo.consensusBranchId)
                )
            }
        } finally {
            client.dispose()
        }
    }

    override suspend fun getTransparentBalance(tAddr: String): Zatoshi = spendService.getTransparentBalance(tAddr)

    override suspend fun refreshUtxos(
        account: Account,
        since: BlockHeight
    ): Int? =
        runCatching {
            var count = 0
            val tAddresses = backend.listTransparentReceivers(account.accountUuid.value)
            walletClient
                .fetchUtxos(
                    tAddresses = tAddresses,
                    startHeight = BlockHeightUnsafe(since.value),
                    serviceMode = ServiceMode.Direct
                ).collect { response ->
                    if (response is Response.Success) {
                        val utxo = response.result
                        backend.putUtxo(
                            txId = utxo.txid,
                            index = utxo.index,
                            script = utxo.script,
                            value = utxo.valueZat,
                            height = utxo.height
                        )
                        count++
                    }
                }
            count
        }.getOrNull()

    /** The `engine.start(ufvk = null, ...)` restart below is keyless (`FFI_JNI_CONTRACT.md` section 3.5) - legal only because provisioning guarantees an account row already exists by the time any restart runs. */
    override suspend fun rewindToNearestHeight(height: BlockHeight): BlockHeight? {
        engine.stop()
        val result = rewindRetrying(height)
        engine.start(ufvk = null, birthday = startBirthday.value)
        return result
    }

    private suspend fun rewindRetrying(height: BlockHeight): BlockHeight? {
        val result =
            runCatching { backend.rewindToHeight(height.value) }
                .getOrElse { return null }
        return when (result) {
            is JniRewindResult.Success -> BlockHeight.new(result.height)
            is JniRewindResult.Invalid ->
                if (result.safeRewindHeight != -1L) rewindRetrying(BlockHeight.new(result.safeRewindHeight)) else null
        }
    }

    /** Same `ufvk = null` keyless-restart contract as [rewindToNearestHeight] (`FFI_JNI_CONTRACT.md` section 3.5). */
    override suspend fun rewindToHeight(height: BlockHeight) {
        engine.stop()
        backend.rewindToHeight(height.value)
        engine.start(ufvk = null, birthday = startBirthday.value)
    }

    override fun getMemos(transactionOverview: TransactionOverview): Flow<String> =
        flow {
            val outputs = transactionReader.getOutputProperties(transactionOverview.txId.value)
            for (output in outputs) {
                if (output.poolCode == TRANSPARENT_POOL_CODE) {
                    emit("")
                } else {
                    val memo =
                        runCatching {
                            backend.getMemoAsUtf8(
                                transactionOverview.txId.value.byteArray,
                                output.poolCode,
                                output.index
                            )
                        }
                            .getOrNull()
                    emit(memo ?: "")
                }
            }
        }

    override fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>> =
        flow { emit(transactionReader.getTransactionsByMemoSubstring(query).map { TransactionId.new(it) }) }

    override fun getRecipients(transactionOverview: TransactionOverview): Flow<TransactionRecipient> {
        require(transactionOverview.isSentTransaction) { "Recipients can only be queried for sent transactions" }
        return flow { transactionReader.getRecipients(transactionOverview.txId.value).forEach { emit(it) } }
    }

    override suspend fun getRecipients(): Map<TransactionId, List<TransactionRecipient>> =
        transactionReader.getAllRecipients().mapKeys { (txId, _) -> TransactionId.new(txId) }

    override suspend fun getExistingDataDbFilePath(
        context: Context,
        network: ZcashNetwork,
        alias: String
    ): String {
        val dbFile = DataDbPath.dataDbFile(context.getNoBackupFilesDirSuspend(), alias, network)
        if (!dbFile.exists()) throw InitializeException.MissingDatabaseException(network, alias)
        return dbFile.absolutePath
    }

    override suspend fun getTransactionOutputs(transactionOverview: TransactionOverview): List<TransactionOutput> =
        transactionReader.getTransactionOutputs(transactionOverview.txId.value)

    override suspend fun getTransactionOutputs(): Map<TransactionId, List<TransactionOutput>> =
        transactionReader.getAllTransactionOutputs().mapKeys { (txId, _) -> TransactionId.new(txId) }

    override suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>> =
        transactionsController.forAccount(accountUuid)

    override suspend fun getSingleUseTransparentAddress(accountUuid: AccountUuid): SingleUseTransparentAddress =
        SingleUseTransparentAddress.new(backend.getSingleUseTransparentAddress(accountUuid.value))

    override suspend fun checkSingleUseTransparentAddress(accountUuid: AccountUuid): Boolean =
        when (val response = walletClient.checkSingleUseTransparentAddress(accountUuid.value, ServiceMode.UniqueTor)) {
            is Response.Success -> response.result != null
            is Response.Failure -> false
        }

    override suspend fun fetchUtxosByAddress(
        accountUuid: AccountUuid,
        address: String
    ): Boolean =
        when (val response = walletClient.fetchUtxosByAddress(accountUuid.value, address, ServiceMode.UniqueTor)) {
            is Response.Success -> response.result != null
            is Response.Failure -> false
        }

    /** R24: on-demand only - the engine performs in-pass enhancement itself; this is the explicit, app-triggered path. */
    override fun enhanceTransaction(txId: TransactionId) {
        scope.launch {
            val serviceMode = sdkFlags ifTor ServiceMode.Group("enhance-${txId.txIdString()}")
            when (val response = walletClient.fetchTransaction(txId.value.byteArray, serviceMode)) {
                is Response.Success -> {
                    backend.decryptAndStoreTransaction(response.result.data, minedHeight = null)
                }

                is Response.Failure -> {
                    backend.setTransactionStatus(txId.value.byteArray, status = TXID_NOT_RECOGNIZED_STATUS)
                }
            }
            engine.notifyTxChange()
        }
    }

    /** Pairs with [onForeground]'s [SlipstreamEngine.isRunning] guard: [SlipstreamEngine.stop] always clears it, so a following foreground sees an honest running/stopped state. */
    override fun onBackground() {
        scope.launch {
            engine.stopPolling()
            engine.stop()
            lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.SOFT) }
        }
    }

    /**
     * [SlipstreamEngine.start] unconditionally aborts any in-flight pass and reruns its bounded
     * quiescence drain, so restarting an already-running engine on every foreground would churn
     * useful work instead of resuming it. [SlipstreamEngine.isRunning] guards against that - skip
     * the native restart when the engine is already live. [SlipstreamEngine.startPolling] stays
     * unconditional; it is already an idempotent cancel-and-relaunch.
     */
    override fun onForeground() {
        scope.launch {
            lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.NORMAL) }
            if (!engine.isRunning) {
                engine.start(ufvk = null, birthday = startBirthday.value)
            }
            engine.startPolling()
        }
    }

    override suspend fun getTorHttpClient(config: HttpClientConfig<HttpClientEngineConfig>.() -> Unit): HttpClient {
        if (!sdkFlags.isTorEnabled && !sdkFlags.isExchangeRateEnabled) throw TorUnavailableException()
        val client = lazyTorClient
            ?: throw TorInitializationErrorException(NullPointerException("Tor has not been initialized during synchronizer setup"))
        val isolatedTor = try {
            client.getOrCreate().isolatedTorClient()
        } catch (e: Exception) {
            throw TorInitializationErrorException(e)
        }
        @Suppress("UNCHECKED_CAST")
        return HttpClient(TorHttp) {
            engine {
                tor = isolatedTor
                retryLimit = 1
            }
            config(this as HttpClientConfig<HttpClientEngineConfig>)
        }
    }

    override suspend fun debugQuery(query: String): String = transactionReader.debugQuery(query)

    /**
     * Sequence: stop engine -> delete -> restart -> poke the accounts bus. The restart's
     * `ufvk = null` is `FFI_JNI_CONTRACT.md` section 3.5's keyless mode - safe here because any
     * other accounts still exist in `data.db` for the engine to read.
     */
    override suspend fun deleteAccount(accountUuid: AccountUuid): Boolean {
        engine.stop()
        val deleted = backend.deleteAccount(accountUuid.value)
        engine.start(ufvk = null, birthday = startBirthday.value)
        engine.notifyTxChange()
        accountsBus.tryEmit(Unit)
        return deleted
    }

    override suspend fun getTreeState(height: BlockHeight): ByteArray {
        val serviceMode = sdkFlags ifTor ServiceMode.UniqueTor
        return when (val response = walletClient.getTreeState(BlockHeightUnsafe(height.value), serviceMode)) {
            is Response.Success -> response.result.encoded
            is Response.Failure -> throw response.toThrowable()
        }
    }

    /** Made public only for snapshot release, mirroring the upstream `SdkSynchronizer` posture (DECISIONS.md D12.4); flagged for post-v1 voting review. */
    override suspend fun getWalletDbPathForVoting(): String =
        DataDbPath.dataDbFile(context.getNoBackupFilesDirSuspend(), alias, network).absolutePath

    /**
     * Kotlin has no deinit, so a double-free guard ([closed]) is required. Registers the
     * shutdown job synchronously, on the caller's thread, before this function returns; the
     * actual stop/free work then runs asynchronously on [scope]. This makes `close()` safe to
     * call from `Dispatchers.Main`.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val shutdownJob =
                scope.launch {
                    engine.stopPolling()
                    engine.stop()
                    engine.free()
                    lazyTorClient?.dispose()
                    walletClient.dispose()
                    exchangeRateFetcher?.dispose()
                }
            InstanceGuard.markShuttingDown(key, shutdownJob)
            shutdownJob.invokeOnCompletion {
                InstanceGuard.release(key)
                scope.cancel()
            }
        }
    }

    /**
     * Companion factory surface (`KOTLIN_ROSETTA.md` rows C1-C3). `Synchronizer.new`/`newBlocking`/
     * `erase` are companion members of THEIR interface, which an external implementation cannot
     * ride - this artifact ships the parallel surface here instead.
     */
    companion object {
        private const val TRANSPARENT_POOL_CODE = 0
        private const val TXID_NOT_RECOGNIZED_STATUS = -1L

        /**
         * Mirrors `Synchronizer.new` parameter-for-parameter - same names, order, and defaults -
         * so an app call site changes exactly one token. Sequence: alias + instance guard ->
         * `SlipstreamNative.ensureLoaded()` -> resolve the UFVK -> NewWallet/RestoreWallet call
         * `restoreAnchor` -> `engine.open` -> `engine.start` -> store `latestBirthdayHeight`.
         *
         * Runs on `Dispatchers.IO`: construction is disk/JNI-heavy and callers include
         * `Dispatchers.Main` scopes, so dispatching here keeps every caller agnostic to that.
         */
        suspend fun new(
            alias: String = ZcashSdk.DEFAULT_ALIAS,
            birthday: BlockHeight?,
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            zcashNetwork: ZcashNetwork,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean
        ): CloseableSynchronizer {
            validateAlias(alias)
            val applicationContext = context.applicationContext
            val key = SlipstreamKey(zcashNetwork, alias)
            InstanceGuard.acquire(key)
            try {
                return withContext(Dispatchers.IO) {
                    newLocked(
                        alias = alias,
                        birthday = birthday,
                        applicationContext = applicationContext,
                        lightWalletEndpoint = lightWalletEndpoint,
                        setup = setup,
                        walletInitMode = walletInitMode,
                        zcashNetwork = zcashNetwork,
                        isTorEnabled = isTorEnabled,
                        isExchangeRateEnabled = isExchangeRateEnabled,
                        key = key
                    )
                }
            } catch (t: Throwable) {
                InstanceGuard.release(key)
                throw t
            }
        }

        /** [SlipstreamNative.ensureLoaded] uses `logLevel = "info"` here, not its `"warn"` default: a dev-time default for field diagnosability (surfaces engine lifecycle logs). */
        @Suppress("LongMethod")
        private suspend fun newLocked(
            alias: String,
            birthday: BlockHeight?,
            applicationContext: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            zcashNetwork: ZcashNetwork,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean,
            key: SlipstreamKey
        ): CloseableSynchronizer {
            SlipstreamNative.ensureLoaded(logLevel = "info")
            val sdkFlags = SdkFlags(isTorEnabled = isTorEnabled, isExchangeRateEnabled = isExchangeRateEnabled)

            val ufvk: String? =
                when (walletInitMode) {
                    WalletInitMode.ExistingWallet -> null
                    WalletInitMode.NewWallet, WalletInitMode.RestoreWallet -> {
                        val seed =
                            requireNotNull(setup?.seed) { "AccountCreateSetup with a seed is required for $walletInitMode" }
                        DerivationTool.getInstance().deriveUnifiedFullViewingKeys(
                            seed.byteArray,
                            zcashNetwork,
                            numberOfAccounts = 1
                        )[0].encoding
                    }
                }

            val noBackupRoot = applicationContext.getNoBackupFilesDirSuspend()
            val zcashNoBackupDir = Files.getZcashNoBackupSubdirectory(applicationContext)
            val engineTorDir =
                if (isTorEnabled) File(zcashNoBackupDir, ENGINE_TOR_SUBDIR).apply { mkdirs() }.absolutePath else null
            val fallbackCheckpoint = newestBundledCheckpointHeight(applicationContext, zcashNetwork)

            val intent = resolveIntent(walletInitMode)
            val provisioning: WalletProvisioningPlan =
                when (intent) {
                    1 -> {
                        val requestedBirthday =
                            requireNotNull(birthday) { "birthday is required for WalletInitMode.RestoreWallet" }
                        val anchor =
                            withContext(Dispatchers.IO) {
                                SlipstreamNative.restoreAnchor(
                                    serverHost = lightWalletEndpoint.host,
                                    serverPort = lightWalletEndpoint.port,
                                    useTls = lightWalletEndpoint.isSecure,
                                    networkId = zcashNetwork.id,
                                    intent = intent,
                                    birthdayHeight = requestedBirthday.value,
                                    fallbackCheckpointHeight = fallbackCheckpoint,
                                    torDir = engineTorDir
                                )
                            }
                        WalletProvisioningPlan(
                            startBirthday = BlockHeight.new(anchor.height),
                            treeState = CheckpointTool.loadNearest(applicationContext, zcashNetwork, requestedBirthday)
                                .treeState().encoded,
                            recoverUntil = anchor.height
                        )
                    }

                    0 -> {
                        val anchor =
                            withContext(Dispatchers.IO) {
                                SlipstreamNative.restoreAnchor(
                                    serverHost = lightWalletEndpoint.host,
                                    serverPort = lightWalletEndpoint.port,
                                    useTls = lightWalletEndpoint.isSecure,
                                    networkId = zcashNetwork.id,
                                    intent = intent,
                                    birthdayHeight = 0L,
                                    fallbackCheckpointHeight = fallbackCheckpoint,
                                    torDir = engineTorDir
                                )
                            }
                        WalletProvisioningPlan(
                            startBirthday = anchor.height.takeIf { it > 0 }?.let(BlockHeight::new) ?: BlockHeight.new(
                                fallbackCheckpoint
                            ),
                            treeState = anchor.treestate ?: CheckpointTool.loadLast(applicationContext, zcashNetwork)
                                .treeState().encoded,
                            recoverUntil = null
                        )
                    }

                    else ->
                        WalletProvisioningPlan(
                            startBirthday = birthday ?: BlockHeight.new(fallbackCheckpoint),
                            treeState =
                                CheckpointTool.loadNearest(
                                    applicationContext,
                                    zcashNetwork,
                                    birthday ?: zcashNetwork.saplingActivationHeight
                                )
                                    .treeState()
                                    .encoded,
                            recoverUntil = null
                        )
                }
            val startBirthday = provisioning.startBirthday

            val dbFile = DataDbPath.dataDbFile(noBackupRoot, alias, zcashNetwork)

            val fsBlockDbRoot = File(applicationContext.filesDir, "slipstream_unused_fsblockdb").apply { mkdirs() }
            val saplingParamsDir = File(zcashNoBackupDir, "sapling_params")
            val backend =
                RustBackend.new(
                    fsBlockDbRoot = fsBlockDbRoot,
                    dataDbFile = dbFile,
                    saplingSpendFile = File(saplingParamsDir, "sapling-spend.params"),
                    saplingOutputFile = File(saplingParamsDir, "sapling-output.params"),
                    zcashNetworkId = zcashNetwork.id
                )
            val typesafeBackend = TypesafeBackendImpl(backend)

            /**
             * Mirrors `DerivedDataDb.new`: unconditional [Backend.initDataDb], then
             * [Backend.createAccount] gated on `setup != null && accounts.isEmpty()`.
             */
            when (backend.initDataDb(setup?.seed?.byteArray)) {
                0 -> Unit
                1 -> throw InitializeException.SeedRequired
                2 -> throw InitializeException.SeedNotRelevant
                -1 -> error("Rust backend only uses -1 as an error sentinel")
                else -> error("Rust backend used a code that needs to be defined here")
            }
            if (shouldCreateAccount(hasSetup = setup != null, accountsAreEmpty = backend.getAccounts().isEmpty())) {
                val accountSetup = requireNotNull(setup)
                runCatching {
                    backend.createAccount(
                        accountName = accountSetup.accountName,
                        keySource = accountSetup.keySource,
                        seed = accountSetup.seed.byteArray,
                        treeState = provisioning.treeState,
                        recoverUntil = provisioning.recoverUntil
                    )
                }.getOrElse { throw InitializeException.CreateAccountException(it) }
            }

            val engine = SlipstreamEngine(
                dbFile.absolutePath,
                lightWalletEndpoint,
                zcashNetwork.id,
                engineTorDir,
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )

            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            engine.open(totalMemoryBytes = memoryInfo.totalMem)
            /**
             * `ufvk` stays non-null even though the account row already exists: `FFI_JNI_CONTRACT.md`
             * section 3.5's `start(ufvk != null)` is a no-op when an account is already present.
             */
            engine.start(ufvk, startBirthday.value)

            /**
             * Tor is only needed for on-demand/background work, never on the cold-start critical
             * path, so its creation (~1s) is deferred to first use via [LazyTorClient].
             */
            val lazyTorClient =
                if (isTorEnabled || isExchangeRateEnabled) {
                    LazyTorClient { TorClient.new(Files.getTorDir(applicationContext), backend) }
                } else {
                    null
                }
            val exchangeRateFetcher =
                if (isExchangeRateEnabled) {
                    lazyTorClient?.let { holder ->
                        UsdExchangeRateFetcher(isolatedTorClient = LazyTorClient { holder.getOrCreate().isolatedTorClient() })
                    }
                } else {
                    null
                }

            val walletClientFactory =
                WalletClientFactory(context = applicationContext, torClient = lazyTorClient.takeIf { isTorEnabled })
            val walletClient = walletClientFactory.create(endpoint = lightWalletEndpoint)
            val fastestServerFetcher =
                FastestServerFetcher(typesafeBackend, zcashNetwork, walletClientFactory, sdkFlags)

            val transactionReader = SlipstreamTransactionReader(dbFile)
            val transactionsController = TransactionsController(transactionReader, engine)

            val spendService =
                SlipstreamSpendService(
                    backend = backend,
                    walletClient = walletClient,
                    engine = engine,
                    sdkFlags = sdkFlags,
                    ensureSaplingParams = { SaplingParams.ensureDownloaded(saplingParamsDir) },
                    readRawTransaction = transactionReader::readRawTransaction
                )

            val submitPlanPreferences =
                applicationContext.getSharedPreferences(
                    "com.zodl.slipstream.submit_plan_${zcashNetwork.id}_$alias",
                    Context.MODE_PRIVATE
                )
            val broadcaster =
                SlipstreamBroadcaster(
                    backend = backend,
                    walletClientFactory = walletClientFactory,
                    sdkFlags = sdkFlags,
                    engine = engine,
                    planStore = SubmitPlanStore(submitPlanPreferences),
                    saplingParamsDir = saplingParamsDir,
                    transactionReader = transactionReader
                )

            val resubmissionTicker =
                ResubmissionTicker(
                    findCandidates = transactionReader::findResubmissionCandidates,
                    resubmit = { candidate ->
                        walletClient.submitTransaction(
                            FirstClassByteArray(candidate.raw),
                            FirstClassByteArray(candidate.txId),
                            sdkFlags
                        )
                        Unit
                    },
                    notifyTxChange = engine::notifyTxChange
                )

            return SlipstreamSynchronizer(
                context = applicationContext,
                network = zcashNetwork,
                alias = alias,
                key = key,
                engine = engine,
                backend = backend,
                walletClient = walletClient,
                walletClientFactory = walletClientFactory,
                defaultEndpoint = lightWalletEndpoint,
                lazyTorClient = lazyTorClient,
                exchangeRateFetcher = exchangeRateFetcher,
                sdkFlags = sdkFlags,
                fastestServerFetcher = fastestServerFetcher,
                transactionReader = transactionReader,
                transactionsController = transactionsController,
                spendService = spendService,
                broadcasterImpl = broadcaster,
                resubmissionTicker = resubmissionTicker,
                startBirthday = startBirthday
            )
        }

        /** `@JvmStatic`-shaped twin of their `newBlocking` for Java callers (C2). */
        @JvmStatic
        fun newBlocking(
            alias: String = ZcashSdk.DEFAULT_ALIAS,
            birthday: BlockHeight?,
            context: Context,
            lightWalletEndpoint: LightWalletEndpoint,
            setup: AccountCreateSetup?,
            walletInitMode: WalletInitMode,
            zcashNetwork: ZcashNetwork,
            isTorEnabled: Boolean,
            isExchangeRateEnabled: Boolean
        ): CloseableSynchronizer =
            runBlocking {
                new(
                    alias = alias,
                    birthday = birthday,
                    context = context,
                    lightWalletEndpoint = lightWalletEndpoint,
                    setup = setup,
                    walletInitMode = walletInitMode,
                    zcashNetwork = zcashNetwork,
                    isTorEnabled = isTorEnabled,
                    isExchangeRateEnabled = isExchangeRateEnabled
                )
            }

        /**
         * Deletes `data.sqlite3` + `-wal` + `-shm`; refuses while an instance is `Active`. No
         * separate on-disk block cache directory to delete alongside it - every persisted fact
         * Slipstream keeps lives inside `data.sqlite3` itself.
         */
        suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String = ZcashSdk.DEFAULT_ALIAS
        ): Boolean {
            val key = SlipstreamKey(network, alias)
            check(!InstanceGuard.isActive(key)) { "Cannot erase while a Slipstream synchronizer for $key is active" }
            return withContext(Dispatchers.IO) {
                val dbFile =
                    DataDbPath.dataDbFile(appContext.applicationContext.getNoBackupFilesDirSuspend(), alias, network)
                val walFile = File("${dbFile.path}-wal")
                val shmFile = File("${dbFile.path}-shm")
                listOf(dbFile, walFile, shmFile).map { !it.exists() || it.delete() }.all { it }
            }
        }

        private const val ENGINE_TOR_SUBDIR = "slipstream_tor"
    }
}
