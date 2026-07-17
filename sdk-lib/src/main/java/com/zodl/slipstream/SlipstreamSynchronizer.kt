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
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Pczt
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
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zatoshi
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
import com.zodl.slipstream.internal.DataDbPath
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import com.zodl.slipstream.internal.db.TransactionsController
import com.zodl.slipstream.internal.newestBundledCheckpointHeight
import com.zodl.slipstream.internal.resolveIntent
import com.zodl.slipstream.internal.toProcessorInfo
import com.zodl.slipstream.internal.spend.ResubmissionTicker
import com.zodl.slipstream.internal.spend.SaplingParams
import com.zodl.slipstream.internal.spend.SlipstreamBroadcaster
import com.zodl.slipstream.internal.spend.SlipstreamSpendService
import com.zodl.slipstream.internal.spend.SubmitPlanStore
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
 * `SlipstreamSynchronizer : CloseableSynchronizer` - the drop-in replacement for `SdkSynchronizer`
 * behind the Slipstream engine. Built member-by-member across T5-T10 (`SDK_ADAPTER_PLAN.md`
 * Appendix Z item 2); this file is T7-T10's slice: provisioning (`Companion.new`,
 * `importAccountByUfvk`), lifecycle (`close`/`onForeground`/`onBackground`/`erase`), spend/PCZT/
 * broadcaster delegation, and every remaining member.
 *
 * Two single-threaded lanes throughout (`KOTLIN_ROSETTA.md` section 0.3): all [SlipstreamEngine]
 * calls serialize on `slipstream-io`; all [Backend]/`RustBackend` calls serialize on `sdk-lib`'s own
 * `zc-io`. Cross-lane DB safety is WAL + `busy_timeout` (DECISIONS.md D5), never thread exclusion.
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
    private val torClient: TorClient?,
    private val exchangeRateFetcher: UsdExchangeRateFetcher?,
    private val sdkFlags: SdkFlags,
    private val fastestServerFetcher: FastestServerFetcher,
    private val transactionReader: SlipstreamTransactionReader,
    private val transactionsController: TransactionsController,
    private val spendService: SlipstreamSpendService,
    private val broadcasterImpl: SlipstreamBroadcaster,
    private val resubmissionTicker: ResubmissionTicker,
    private var startBirthday: BlockHeight,
    private val scope: CoroutineScope
) : CloseableSynchronizer {
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

    /** R5, section 5.2: degraded on purpose - `overallSyncRange`/`firstUnenhancedHeight` describe processor internals that do not exist under this engine. */
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
                                        lastExchangeRateValue.copy(isLoading = false, currencyConversion = result.currencyConversion)
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

    override val initializationError: Synchronizer.InitializationError?
        get() = if (torClient == null && sdkFlags.isTorEnabled) Synchronizer.InitializationError.TOR_NOT_AVAILABLE else null

    override val broadcaster: Broadcaster get() = broadcasterImpl

    override val accountsFlow: Flow<List<Account>?> =
        accountsBus.onStart { emit(Unit) }.map { runCatching { getAccounts() }.getOrNull() }

    /** R62-R64: forwarded straight to the engine, which owns the tick and the error-episode state (Phase 4 T10). */
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)?
        get() = engine.onCriticalErrorHandler
        set(value) { engine.onCriticalErrorHandler = value }

    override var onProcessorErrorHandler: ((Throwable?) -> Boolean)?
        get() = engine.onProcessorErrorHandler
        set(value) { engine.onProcessorErrorHandler = value }

    override var onProcessorErrorResolved: (() -> Unit)?
        get() = engine.onProcessorErrorResolved
        set(value) { engine.onProcessorErrorResolved = value }

    /**
     * R65, section 5.4 (narrowed scope): invoked only for construction-time failures
     * (open/provisioning/Tor) - which, since [Companion.new] throws directly on those failures
     * rather than returning a half-built instance, means this adapter never actually has an
     * instance alive to invoke it on. Settable for interface completeness; server-mismatch and
     * runtime setup problems surface through [onProcessorErrorHandler] instead (R63).
     */
    override var onSetupErrorHandler: ((Throwable?) -> Boolean)? = null

    /** R66, section 5.1: settable, documented never-invoked - the engine owns reorg recovery internally; there is no host-visible chain-error event. */
    override var onChainErrorHandler: ((BlockHeight, BlockHeight) -> Any)? = null

    init {
        engine.onTick = { snap -> resubmissionTicker.onTick(engine.status.value == Synchronizer.Status.SYNCED, snap.chainTip) }
        transactionsController.start()
    }

    override suspend fun getAccounts(): List<Account> = backend.getAccounts().map(Account::new)

    /**
     * R37: `restoreAnchor(intent = 1)` for the `recoverUntil` (network-only, engine stays live) ->
     * stop engine -> `RustBackend.importAccountUfvk` -> restart (the H2 serialization invariant;
     * the restart re-baselines the progress floor, section 5.1). Never fetches the chain tip
     * itself - the anchor is the policy. The `treeState` the backend needs to bootstrap the new
     * account's scan window is a DIFFERENT fact than `recoverUntil` (a height, not a tree) - it
     * comes from the same bundled-checkpoint mechanism the T7 `NewWallet` provisioning path uses
     * (`CheckpointTool`, `internal object`, reachable in-module), nearest to the account's own
     * birthday.
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
        val sdkBranchId = currentChainTip?.let { tip -> runCatching { backend.getBranchIdForHeight(tip.value) }.getOrNull() }

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
            val serviceMode = sdkFlags ifTor ServiceMode.Group("SlipstreamSynchronizer.validateServerEndpoint(${endpoint.host}:${endpoint.port})")
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
                runCatching { BlockHeight.new(remoteInfo.saplingActivationHeightUnsafe.value) }.getOrElse { return ServerValidation.InValid(it) }
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
                    is Response.Success -> runCatching { BlockHeight.new(response.result.value) }.getOrElse { return ServerValidation.InValid(it) }
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
                        runCatching { backend.getMemoAsUtf8(transactionOverview.txId.value.byteArray, output.poolCode, output.index) }
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

    override fun onBackground() {
        scope.launch {
            engine.stopPolling()
            engine.stop()
            torClient?.setDormant(TorDormantMode.SOFT)
        }
    }

    override fun onForeground() {
        scope.launch {
            torClient?.setDormant(TorDormantMode.NORMAL)
            engine.start(ufvk = null, birthday = startBirthday.value)
            engine.startPolling()
        }
    }

    override suspend fun getTorHttpClient(config: HttpClientConfig<HttpClientEngineConfig>.() -> Unit): HttpClient {
        if (!sdkFlags.isTorEnabled && !sdkFlags.isExchangeRateEnabled) throw TorUnavailableException()
        val client = torClient ?: throw TorInitializationErrorException(NullPointerException("Tor has not been initialized during synchronizer setup"))
        val isolatedTor = try {
            client.isolatedTorClient()
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

    /** R38: stop engine (B4-16 invariant) -> delete -> poke (dead rows drop next tick) -> restart -> poke the accounts bus. */
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

    private suspend fun shutdown() {
        val shutdownJob =
            scope.launch {
                transactionsController.stop()
                engine.stopPolling()
                engine.stop()
                engine.free()
                torClient?.dispose()
                walletClient.dispose()
                exchangeRateFetcher?.dispose()
                transactionReader.close()
            }
        InstanceGuard.markShuttingDown(key, shutdownJob)
        shutdownJob.join()
        InstanceGuard.release(key)
        scope.cancel()
    }

    /** R13: explicit stop -> free -> drop the instance-guard key. Kotlin has no deinit, so a double-free guard is required. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runBlocking { shutdown() }
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
         * Mirrors `Synchronizer.new` (`Synchronizer.kt:855`) parameter-for-parameter - same names,
         * order, and defaults - so an app call site changes exactly one token
         * (`Synchronizer.new` -> `SlipstreamSynchronizer.new`). Sequence (`SDK_ADAPTER_PLAN.md` T7):
         * alias + instance guard -> `SlipstreamNative.ensureLoaded()` -> resolve the UFVK ->
         * NewWallet/RestoreWallet call `restoreAnchor` -> `engine.open` -> `engine.start` -> store
         * `latestBirthdayHeight`.
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
                return newLocked(
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
            } catch (t: Throwable) {
                InstanceGuard.release(key)
                throw t
            }
        }

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
            SlipstreamNative.ensureLoaded()
            val sdkFlags = SdkFlags(isTorEnabled = isTorEnabled, isExchangeRateEnabled = isExchangeRateEnabled)

            val ufvk: String? =
                when (walletInitMode) {
                    WalletInitMode.ExistingWallet -> null
                    WalletInitMode.NewWallet, WalletInitMode.RestoreWallet -> {
                        val seed = requireNotNull(setup?.seed) { "AccountCreateSetup with a seed is required for $walletInitMode" }
                        DerivationTool.getInstance().deriveUnifiedFullViewingKeys(seed.byteArray, zcashNetwork, numberOfAccounts = 1)[0].encoding
                    }
                }

            val noBackupRoot = applicationContext.getNoBackupFilesDirSuspend()
            val zcashNoBackupDir = Files.getZcashNoBackupSubdirectory(applicationContext)
            val engineTorDir = if (isTorEnabled) File(zcashNoBackupDir, ENGINE_TOR_SUBDIR).apply { mkdirs() }.absolutePath else null
            val fallbackCheckpoint = newestBundledCheckpointHeight(applicationContext, zcashNetwork)

            val intent = resolveIntent(walletInitMode)
            val startBirthday: BlockHeight =
                when (intent) {
                    1 -> {
                        val requestedBirthday = requireNotNull(birthday) { "birthday is required for WalletInitMode.RestoreWallet" }
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
                        BlockHeight.new(anchor.height)
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
                        anchor.height.takeIf { it > 0 }?.let(BlockHeight::new) ?: BlockHeight.new(fallbackCheckpoint)
                    }
                    else -> birthday ?: BlockHeight.new(fallbackCheckpoint)
                }

            val dbFile = DataDbPath.dataDbFile(noBackupRoot, alias, zcashNetwork)
            val engine = SlipstreamEngine(dbFile.absolutePath, lightWalletEndpoint, zcashNetwork.id, engineTorDir, CoroutineScope(SupervisorJob() + Dispatchers.Default))

            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            engine.open(totalMemoryBytes = memoryInfo.totalMem)
            engine.start(ufvk, startBirthday.value)

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

            val torClient =
                if (isTorEnabled || isExchangeRateEnabled) {
                    runCatching { TorClient.new(Files.getTorDir(applicationContext), backend) }.getOrNull()
                } else {
                    null
                }
            val exchangeRateFetcher =
                if (isExchangeRateEnabled) {
                    runCatching { torClient?.isolatedTorClient()?.let(::UsdExchangeRateFetcher) }.getOrNull()
                } else {
                    null
                }

            val walletClientFactory = WalletClientFactory(context = applicationContext, torClient = torClient.takeIf { isTorEnabled })
            val walletClient = walletClientFactory.create(endpoint = lightWalletEndpoint)
            val fastestServerFetcher = FastestServerFetcher(typesafeBackend, zcashNetwork, walletClientFactory, sdkFlags)

            val transactionReader = SlipstreamTransactionReader(applicationContext, dbFile)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transactionsController = TransactionsController(transactionReader, engine, scope)

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
                applicationContext.getSharedPreferences("com.zodl.slipstream.submit_plan_${zcashNetwork.id}_$alias", Context.MODE_PRIVATE)
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
                        walletClient.submitTransaction(FirstClassByteArray(candidate.raw), FirstClassByteArray(candidate.txId), sdkFlags)
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
                torClient = torClient,
                exchangeRateFetcher = exchangeRateFetcher,
                sdkFlags = sdkFlags,
                fastestServerFetcher = fastestServerFetcher,
                transactionReader = transactionReader,
                transactionsController = transactionsController,
                spendService = spendService,
                broadcasterImpl = broadcaster,
                resubmissionTicker = resubmissionTicker,
                startBirthday = startBirthday,
                scope = scope
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
         * C3: delete `data.sqlite3` + `-wal` + `-shm` via the R57 path derivation; refuse while an
         * instance is `Active` (their semantic). This engine has no separate on-disk block cache
         * directory to delete alongside it (unlike the upstream SDK's `fs_cache`) - every persisted
         * fact Slipstream keeps lives inside `data.sqlite3` itself.
         */
        suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String = ZcashSdk.DEFAULT_ALIAS
        ): Boolean {
            val key = SlipstreamKey(network, alias)
            check(!InstanceGuard.isActive(key)) { "Cannot erase while a Slipstream synchronizer for $key is active" }
            return withContext(Dispatchers.IO) {
                val dbFile = DataDbPath.dataDbFile(appContext.applicationContext.getNoBackupFilesDirSuspend(), alias, network)
                val walFile = File("${dbFile.path}-wal")
                val shmFile = File("${dbFile.path}-shm")
                listOf(dbFile, walFile, shmFile).map { !it.exists() || it.delete() }.all { it }
            }
        }

        private const val ENGINE_TOR_SUBDIR = "slipstream_tor"
    }
}
