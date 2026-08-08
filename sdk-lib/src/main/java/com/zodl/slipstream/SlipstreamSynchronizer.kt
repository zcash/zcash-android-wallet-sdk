@file:Suppress(
    "CyclomaticComplexMethod",
    "LargeClass",
    "LongParameterList",
    "MaxLineLength",
    "ReturnCount",
    "ThrowsCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions"
)

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
import cash.z.ecc.android.sdk.internal.ImportAccountErrors
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackendImpl
import cash.z.ecc.android.sdk.internal.exchange.UsdExchangeRateFetcher
import cash.z.ecc.android.sdk.internal.ext.getNoBackupFilesDirSuspend
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorDormantMode
import cash.z.ecc.android.sdk.internal.model.TorHttp
import cash.z.ecc.android.sdk.internal.model.TreeState
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
import com.zodl.slipstream.internal.DataDbPath
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.PrepareInputs
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import com.zodl.slipstream.internal.db.TransactionsController
import com.zodl.slipstream.internal.newestBundledCheckpointHeight
import com.zodl.slipstream.internal.resolveIntent
import com.zodl.slipstream.internal.runCatchingCancellable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

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
    val treeState: TreeState,
    val recoverUntil: Long?
)

/**
 * The deferred-preparation lifecycle of one [SlipstreamSynchronizer] instance (see
 * [SlipstreamSynchronizer.Companion.new]'s KDoc for why preparation is deferred at all).
 *
 * Linear order: [Preparing] -> [DbReady] -> [Ready], with [Failed] and [Closed] reachable from
 * either of the first two. [DbReady] is the iOS twin's "truthful from open" point - `Initializer`
 * migrates the data DB BEFORE it resolves the network anchor - and exists so read-only surfaces
 * can be served from the wallet database while the tail's anchor, `engine.open` and `engine.start`
 * are all still outstanding.
 *
 * Carried in a `MutableStateFlow` rather than a `CompletableDeferred` because all five states must
 * stay distinguishable: [Closed] is not a failure (a cancelled `Deferred` would throw
 * `CancellationException` into awaiters that were never themselves cancelled), [Preparing] must be
 * probeable synchronously (`syncBurst` runs under a worker's time budget and must never block on
 * it), and every gated caller needs its own fresh wrapper exception rather than a shared instance.
 */
private sealed interface PrepareState {
    /** The preparation job is still running; gated callers suspend. */
    data object Preparing : PrepareState

    /**
     * `initDataDb` has returned, so the wallet database's schema is current and every read-only
     * surface can be served straight from it. Writes and engine-backed members keep suspending -
     * the anchor is unresolved, so [SlipstreamSynchronizer.latestBirthdayHeight] is still
     * provisional and the engine handle does not exist yet.
     */
    data object DbReady : PrepareState

    /** Preparation completed; the instance behaves exactly as a fully-constructed one always did. */
    data object Ready : PrepareState

    /**
     * Preparation threw; gated callers fail with [SlipstreamNotReadyException] carrying [cause].
     * Terminal for the relaxed read-only surfaces too, not just for writes: an `ExistingWallet`
     * tail resolves no anchor at all, so a failure there IS an `initDataDb` failure and the
     * database is unusable, and a failed restore leaves an empty database with nothing to read.
     */
    data class Failed(
        val cause: Throwable
    ) : PrepareState

    /** [SlipstreamSynchronizer.close] ran before preparation finished; gated callers fail without a cause. */
    data object Closed : PrepareState
}

/**
 * `SlipstreamSynchronizer : CloseableSynchronizer` - the drop-in replacement for `SdkSynchronizer`
 * behind the Slipstream engine. Owns provisioning (`Companion.new`, `importAccountByUfvk`),
 * lifecycle (`close`/`onForeground`/`onBackground`/`erase`), and spend/PCZT/broadcaster
 * delegation.
 *
 * Two single-threaded lanes throughout: all [SlipstreamEngine] calls serialize on
 * `slipstream-io`; all [Backend]/`RustBackend` calls serialize on `sdk-lib`'s own `zc-io`.
 * Cross-lane DB safety is WAL + `busy_timeout`, never thread exclusion.
 *
 * Construction is cheap and preparation is deferred (the iOS twin's `prepare()` split, mirrored
 * behind the unchanged Android API - see [Companion.new]). An instance built with a non-null
 * [prepareInputs] starts in [PrepareState.Preparing] with `status == INITIALIZING`, and preparation
 * failures surface through [onSetupErrorHandler] rather than out of [Companion.new]. Members await
 * one of two gates: read-only, database-backed ones await [PrepareState.DbReady] - published as
 * soon as the tail's `initDataDb` returns, before the network anchor - while writes and every
 * engine-backed member await [PrepareState.Ready].
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
    private val engineTorDir: String?,
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
    /** `null` means "already prepared" - the instance is [PrepareState.Ready] from the first line of init. */
    private val prepareInputs: PrepareInputs? = null,
) : CloseableSynchronizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Bumped by every writer of the `accounts` table - the preparation tail's `createAccount`,
     * [importAccountByUfvk], [deleteAccount] - so [accountsFlow] re-reads. A `MutableStateFlow`
     * rather than a replaying `MutableSharedFlow` because a state flow replays its current value to
     * a fresh subscriber on its own, which drops [accountsFlow]'s `onStart` primer and with it the
     * duplicate first fetch that primer caused.
     */
    private val accountsVersion = MutableStateFlow(0)
    private val refreshExchangeRateTrigger = MutableSharedFlow<Unit>()
    private var lastExchangeRateValue = ObserveFiatCurrencyResult()

    /** R13's double-free guard - Kotlin has no deinit, so `close()` must be safely re-entrant. */
    private val closed = AtomicBoolean(false)

    /** True while a migration sync-block is pausing this synchronizer (see [pause]/[resume]). */
    private val migrationPaused = MutableStateFlow(false)

    /**
     * The deferred-preparation gate every engine- or database-backed member awaits (see
     * [awaitReady]/[awaitPrepared]). Declared before [status] because that combine reads it eagerly.
     */
    private val prepareState =
        MutableStateFlow(
            if (prepareInputs == null) {
                PrepareState.Ready
            } else {
                PrepareState.Preparing
            }
        )

    /** The job running [runPrepare]; cancelled and joined as [close]'s first shutdown step. */
    private var prepareJob: Job? = null

    /**
     * The preparation failure, latched so it can be replayed to an [onSetupErrorHandler] that is
     * attached after the failure already happened (see that property's KDoc).
     */
    private val latchedSetupError = AtomicReference<Throwable?>(null)

    /*
     * Last lifecycle signal seen via [onForeground]/[onBackground]; [syncBurst]'s restore reads it
     * to decide whether to stop the engine (backgrounded) or leave it polling (foreground). Starts
     * `false` because the dominant [syncBurst] caller is a headless background worker.
     */
    private val inForeground = AtomicBoolean(false)

    /**
     * True while [syncBurst] is driving the engine. [onForeground]/[onBackground] record their
     * lifecycle signal but defer the engine work while it is set, so a lifecycle event that lands
     * mid-burst cannot stop the engine out from under the burst — the burst's own restore applies
     * the final state instead.
     */
    private val burstActive = AtomicBoolean(false)

    /**
     * The `paused -> SYNCED` mask applies only once preparation is [PrepareState.Ready]: [pause]
     * sets [migrationPaused] regardless of readiness, so an unmasked combine would report a failed
     * preparation (which forces `DISCONNECTED`) as SYNCED for as long as the pause lasts.
     */
    override val status: Flow<Synchronizer.Status> =
        combine(engine.status, migrationPaused, prepareState) { status, paused, prepare ->
            if (paused && prepare is PrepareState.Ready) Synchronizer.Status.SYNCED else status
        }
    override val progress: Flow<PercentDecimal> = engine.progress
    override val areFundsSpendable: Flow<Boolean> = engine.areFundsSpendable
    override val networkHeight = engine.networkHeight
    override val fullyScannedHeight = engine.fullyScannedHeight
    override val walletBalances = engine.walletBalances

    /**
     * Served from [PrepareState.DbReady] onwards: the rows this reads live in
     * `zcash_client_sqlite`'s own views, which `initDataDb` creates, so an existing wallet's history
     * renders while the tail's network anchor is still outstanding. The engine-created `reconcile`
     * view is only joined while `isRecovering` is true, which it cannot be before `engine.open`.
     *
     * Gated non-throwingly, and terminated by [untilPreparationFails] rather than left idling: the
     * app collects this in an eager `stateIn`, so a preparation that fails AFTER this flow was
     * already subscribed must complete it rather than kill the collector or strand it forever on a
     * requery tick that will never come.
     */
    override val allTransactions: Flow<List<TransactionOverview>> =
        flow {
            if (dbReadyReached()) emitAll(transactionsController.allTransactions.untilPreparationFails())
        }

    /** Degraded on purpose - `overallSyncRange`/`firstUnenhancedHeight` describe processor internals that don't exist under this engine. */
    override val processorInfo: Flow<CompactBlockProcessor.ProcessorInfo> =
        engine.networkHeight.map(::toProcessorInfo)

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
                                    is FetchFiatCurrencyResult.Error -> {
                                        lastExchangeRateValue.copy(isLoading = false)
                                    }

                                    is FetchFiatCurrencyResult.Success -> {
                                        lastExchangeRateValue.copy(
                                            isLoading = false,
                                            currencyConversion = result.currencyConversion
                                        )
                                    }
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
        get() =
            engine.lastSnapshot.value
                ?.chainTip
                ?.takeIf { it > 0 }
                ?.let(BlockHeight::new)

    /**
     * While preparation is still running this reports a provisional value (the requested birthday,
     * or sapling activation when there is none); the resolved anchor overwrites it before
     * preparation publishes [PrepareState.Ready]. [rewindToNearestHeight]/[rewindToHeight] coerce
     * against the resolved value precisely because a caller may have read the provisional one.
     */
    override val latestBirthdayHeight: BlockHeight
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

    /**
     * Leads with `null` ("not loaded yet") so a collector attached during preparation gets an
     * immediate emission, then switches to the account rows from [PrepareState.DbReady] onwards -
     * `getAccounts` reads a table `initDataDb` has by then created.
     *
     * Empty reads are filtered out while [isAccountCreationPending] holds, because to the host `null`
     * means "still loading" while an empty list means "loaded, and this wallet has no accounts". A
     * restore reaches [PrepareState.DbReady] before the tail's `createAccount` - that call needs the
     * network anchor's `recoverUntil` - so the `accounts` table is briefly, and untruthfully, empty;
     * swallowing that read leaves the collector on the leading `null` until `createAccount` bumps
     * [accountsVersion] and the real rows flow through. Non-empty lists always pass, so an existing
     * wallet's rows still surface at [PrepareState.DbReady] unchanged, and an empty list still passes
     * once no creation can happen any more (no [PrepareInputs.setup], or preparation already
     * [PrepareState.Ready]).
     *
     * Gated non-throwingly for the same reason as [allTransactions]: a failed preparation completes
     * the flow after that leading `null` instead of cancelling the app's eager collector - including
     * a failure that lands while an empty read is being suppressed, which simply leaves the leading
     * `null` as the only emission. [untilPreparationFails] covers a failure landing after this flow
     * subscribed; the [catch] then covers the narrow race where [getAccounts]' own gate observes the
     * failure first.
     */
    override val accountsFlow: Flow<List<Account>?> =
        flow {
            emit(null)
            if (dbReadyReached()) {
                emitAll(
                    accountsVersion
                        .untilPreparationFails()
                        .map { getAccounts() }
                        .filter { accounts -> accounts.isNotEmpty() || !isAccountCreationPending() }
                        .catch { if (it !is SlipstreamNotReadyException) throw it }
                )
            }
        }

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
     * Invoked with the failure of the deferred preparation tail ([Companion.new]'s heavy work, which
     * no longer throws out of `new()`). Latch-and-replay: the failure is retained, so a handler
     * attached after it already happened is invoked immediately on assignment - the host attaches
     * its handlers only once the synchronizer has been emitted, which is routinely later than the
     * failure. Assigning `null` (the host's teardown) never replays anything. A handler that is
     * detached and re-attached is therefore invoked once per attachment; that repeat is benign,
     * since the host maps it onto the same latched error state.
     *
     * The two sides run on different threads - [runPrepare] fails on `Dispatchers.IO` while the host
     * attaches from its own - so the `@Volatile` backing field pairs with the atomic
     * [latchedSetupError]: each side stores into its own before loading the other's, which makes at
     * least one of the two loads observe the other's store. Neither side can therefore miss the
     * error; the worst case is the benign double invocation above.
     *
     * Server-mismatch and runtime sync problems still surface through [onProcessorErrorHandler].
     */
    @Volatile
    override var onSetupErrorHandler: ((Throwable?) -> Boolean)? = null
        set(value) {
            field = value
            if (value != null) {
                latchedSetupError.get()?.let { value.invoke(it) }
            }
        }

    // Settable, but never invoked - the engine owns reorg recovery internally; there is no host-visible chain-error event.
    override var onChainErrorHandler: ((BlockHeight, BlockHeight) -> Any)? = null

    /*
     * The Android [Synchronizer] interface has no public `start()`; `new` auto-starts the poll
     * loop as part of construction, and [onForeground]/[onBackground] only pause/resume it
     * thereafter. [SlipstreamEngine.startPolling] cancels any prior job before launching, so
     * this is just the loop's first start, not a restart.
     */
    init {
        engine.onTick =
            { snap ->
                resubmissionTicker.onTick(
                    isSynced = engine.status.value == Synchronizer.Status.SYNCED,
                    chainTip = snap.chainTip
                )
            }
        val inputs = prepareInputs
        if (inputs == null) {
            engine.startPolling()
        } else {
            prepareJob = scope.launch { runPrepare(inputs) }
        }
    }

    /**
     * The single predicate behind [awaitReady]/[awaitPrepared]: preparation has run to completion,
     * one way or another. [PrepareState.DbReady] is deliberately NOT settled here, so a write or an
     * engine-backed call that arrives during the tail's network anchor still waits for the engine
     * instead of failing against a handle that does not exist yet.
     */
    private suspend fun awaitSettled(): PrepareState =
        prepareState.first {
            it is PrepareState.Ready || it is PrepareState.Failed || it is PrepareState.Closed
        }

    /**
     * Suspends until preparation settles.
     *
     * @throws SlipstreamNotReadyException when preparation failed or was cancelled by [close].
     */
    private suspend fun awaitReady() {
        val settled = awaitSettled()
        if (settled !is PrepareState.Ready) {
            throw SlipstreamNotReadyException((settled as? PrepareState.Failed)?.cause)
        }
    }

    /**
     * The non-throwing twin of [awaitReady], for fire-and-forget lifecycle work: a
     * settled-but-not-ready instance makes the caller a no-op instead of spamming
     * [onCriticalErrorHandler].
     *
     * @return `true` when preparation succeeded.
     */
    private suspend fun awaitPrepared(): Boolean = awaitSettled() is PrepareState.Ready

    /**
     * The single predicate behind [awaitDbReady]/[dbReadyReached]: preparation has got at least as
     * far as [PrepareState.DbReady], or has stopped short of it.
     */
    private suspend fun awaitDbSettled(): PrepareState =
        prepareState.first { it !is PrepareState.Preparing }

    /**
     * The read-only gate: suspends only until the wallet database is usable, so an existing wallet
     * serves its accounts, transactions and addresses as soon as `initDataDb`'s migrations are
     * done - instead of behind the tail's network anchor, which on a cold Tor bootstrap during a
     * restore is bounded in tens of seconds.
     *
     * @throws SlipstreamNotReadyException when preparation failed or was cancelled by [close].
     */
    private suspend fun awaitDbReady() {
        val settled = awaitDbSettled()
        if (settled is PrepareState.Failed || settled is PrepareState.Closed) {
            throw SlipstreamNotReadyException((settled as? PrepareState.Failed)?.cause)
        }
    }

    /**
     * The non-throwing twin of [awaitDbReady], for the cold flows the host collects eagerly: an
     * instance that never reached [PrepareState.DbReady] completes the flow instead of killing its
     * collector.
     *
     * @return `true` when the wallet database is usable.
     */
    private suspend fun dbReadyReached(): Boolean =
        awaitDbSettled().let { it is PrepareState.DbReady || it is PrepareState.Ready }

    /**
     * True while the preparation tail may still write this wallet's first account row: it does so
     * only when it was given an [PrepareInputs.setup], and only after the network anchor resolves -
     * well past [PrepareState.DbReady]. [accountsFlow] reads it to tell a database that is empty
     * because the row is not written YET from one that is empty because there is nothing to read.
     */
    private fun isAccountCreationPending(): Boolean =
        prepareInputs?.setup != null && prepareState.value !is PrepareState.Ready

    /**
     * Completes [this] - rather than letting it fail, or idle forever on a tick that will never
     * come - as soon as preparation leaves [PrepareState.DbReady] for [PrepareState.Failed] or
     * [PrepareState.Closed]. Both cold flows are subscribed from [PrepareState.DbReady] onwards, so
     * unlike under the old all-or-nothing gate the tail can still fail underneath a live collector.
     * The terminal transition is merged in as a `null` sentinel precisely so it COMPLETES the
     * collector instead of cancelling it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T : Any> Flow<T>.untilPreparationFails(): Flow<T> =
        merge(
            this,
            prepareState
                .filter { it is PrepareState.Failed || it is PrepareState.Closed }
                .take(1)
                .map { null }
        ).transformWhile { value ->
            if (value == null) {
                false
            } else {
                emit(value)
                true
            }
        }

    /**
     * Runs the deferred preparation tail and publishes its verdict. Every terminal transition is a
     * compare-and-set against the two non-terminal states, never a plain assignment: [close] may
     * write [PrepareState.Closed] concurrently and must win, so a teardown-induced throw can neither
     * overwrite it nor paint a setup error over an ordinary shutdown.
     *
     * [CancellationException] is rethrown untouched - a cancelled preparation is [close]'s business,
     * not a failure.
     */
    private suspend fun runPrepare(inputs: PrepareInputs) {
        try {
            withContext(Dispatchers.IO) { prepare(inputs) }
            prepareState.update {
                if (it is PrepareState.Preparing || it is PrepareState.DbReady) PrepareState.Ready else it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Twig.error(t) { "Slipstream synchronizer preparation failed" }
            val landedFailed =
                prepareState.updateAndGet {
                    if (it is PrepareState.Preparing || it is PrepareState.DbReady) {
                        PrepareState.Failed(
                            t
                        )
                    } else {
                        it
                    }
                } is PrepareState.Failed
            if (landedFailed) {
                /*
                 * Safe to force: the engine writes `status` only from a tick, ticks no-op while the
                 * handle is 0, and the poll loop is the tail's very last step - so nothing can be
                 * racing this write. A failure after a successful `open` merely repeats what that
                 * open's own tick already reported.
                 */
                engine.status.value = Synchronizer.Status.DISCONNECTED
                latchedSetupError.set(t)
                onSetupErrorHandler?.invoke(t)
            }
        }
    }

    /**
     * The heavy tail [Companion.newLocked] used to run inline, reordered the way the iOS twin's
     * `Initializer.initialize` already runs it - data-DB provisioning FIRST, network anchor second:
     * fallback checkpoint -> `initDataDb` -> publish [PrepareState.DbReady] -> anchor resolution ->
     * `createAccount` -> `engine.open` -> `engine.start` -> the poll loop's first start. Post-tail
     * state is identical to what `new()` used to return; the reorder is safe because `initDataDb`
     * consumes no anchor input, and the anchor's three derived facts are consumed only by the steps
     * that follow it.
     *
     * Two consequences the reorder buys and costs:
     * - every read-only surface is served from [PrepareState.DbReady], so an existing wallet
     *   renders its accounts and history without waiting for a cold-Tor anchor fetch;
     * - an anchor failure during a restore now leaves a migrated but account-less database file.
     *   The next launch's retry is safe: `createAccount` is gated on [shouldCreateAccount]'s
     *   `accountsAreEmpty`, which such a database still satisfies.
     *
     * WAL nuance for the [PrepareState.DbReady] window: on a FRESH database WAL mode is only
     * established at [SlipstreamEngine.open], so reads served before it run against
     * rollback-journal mode. `createAccount`'s millisecond-long commit is covered by the readers'
     * 5 s `busy_timeout`, and [backend] reads serialize on the single-threaded `zc-io` dispatcher
     * regardless.
     *
     * The final [SlipstreamEngine.startPolling] is skipped while [migrationPaused] holds, mirroring
     * [onForeground]: a migration privacy pause that landed mid-preparation would otherwise get a
     * stray polling tick, since [pause]'s own queued `stopPolling` block only runs once
     * [PrepareState.Ready] is published.
     */
    private suspend fun prepare(inputs: PrepareInputs) {
        val fallbackCheckpoint = inputs.fallbackCheckpointHeight()

        /*
         * Mirrors `DerivedDataDb.new`: unconditional [Backend.initDataDb], then
         * [Backend.createAccount] gated on `setup != null && accounts.isEmpty()`.
         */
        when (backend.initDataDb(inputs.setup?.seed?.byteArray)) {
            0 -> Unit
            1 -> throw InitializeException.SeedRequired
            2 -> throw InitializeException.SeedNotRelevant
            -1 -> error("Rust backend only uses -1 as an error sentinel")
            else -> error("Rust backend used a code that needs to be defined here")
        }

        /*
         * Losing this compare-and-set means [close] already published [PrepareState.Closed]. Bail
         * out before the anchor rather than after it: the anchor is a blocking, engine-bounded
         * network call that close()'s `cancelAndJoin` would otherwise have to wait out for a result
         * nothing will ever read.
         */
        val dbReady =
            prepareState.updateAndGet { if (it is PrepareState.Preparing) PrepareState.DbReady else it }
        if (dbReady !is PrepareState.DbReady) return

        /*
         * Truthful FROM DbReady, not merely from the engine's first tick: the account row and the
         * balances the database already knows surface in the same phase, so an existing wallet never
         * renders a hard zero while the anchor is still in flight. Best-effort by design - a
         * never-scanned database answers null or throws ("Target height not available"), which
         * simply leaves the balances null and the host on its shimmer, the correct restore
         * behaviour. The first engine tick overwrites the seed wholesale, including the stale-tip
         * masking [toAccountBalances] applies and this raw summary does not: totals are identical,
         * only the spendable/pending split may shift at that tick.
         */
        runCatchingCancellable { inputs.dbWalletSummary() }
            .getOrNull()
            ?.let { engine.walletBalances.value = it.accountBalances }

        val provisioning = resolveProvisioning(inputs, fallbackCheckpoint)
        startBirthday = provisioning.startBirthday

        if (shouldCreateAccount(
                hasSetup = inputs.setup != null,
                accountsAreEmpty = backend.getAccounts().isEmpty()
            )
        ) {
            val accountSetup = requireNotNull(inputs.setup)
            runCatchingCancellable {
                backend.createAccount(
                    accountName = accountSetup.accountName,
                    keySource = accountSetup.keySource,
                    seed = accountSetup.seed.byteArray,
                    treeState = provisioning.treeState.encoded,
                    recoverUntil = provisioning.recoverUntil
                )
            }.getOrElse { throw InitializeException.CreateAccountException(it) }
            /*
             * Releases [accountsFlow], which suppressed the empty reads of the whole DbReady window.
             */
            accountsVersion.update { it + 1 }
        }

        engine.open(totalMemoryBytes = inputs.totalMemoryBytes)
        /*
         * `ufvk` stays non-null even though the account row already exists: `FFI_JNI_CONTRACT.md`
         * section 3.5's `start(ufvk != null)` is a no-op when an account is already present.
         */
        engine.start(inputs.ufvk, provisioning.startBirthday.value)
        if (!migrationPaused.value) engine.startPolling()
    }

    /** The `when(intent)` block of the original `newLocked`, verbatim but behind [PrepareInputs]' lambdas. */
    private suspend fun resolveProvisioning(
        inputs: PrepareInputs,
        fallbackCheckpoint: Long
    ): WalletProvisioningPlan =
        when (val intent = resolveIntent(inputs.walletInitMode)) {
            1 -> {
                val requestedBirthday = requireNotNull(inputs.requestedBirthday)
                val anchor =
                    inputs.anchorSource(
                        intent = intent,
                        birthdayHeight = requestedBirthday.value,
                        fallbackCheckpointHeight = fallbackCheckpoint
                    )
                WalletProvisioningPlan(
                    startBirthday = BlockHeight.new(anchor.height),
                    treeState = inputs.treeState(requestedBirthday),
                    recoverUntil = anchor.height
                )
            }

            0 -> {
                val anchor =
                    inputs.anchorSource(
                        intent = intent,
                        birthdayHeight = 0L,
                        fallbackCheckpointHeight = fallbackCheckpoint
                    )
                WalletProvisioningPlan(
                    startBirthday =
                        anchor.height.takeIf { it > 0 }?.let(BlockHeight::new)
                            ?: BlockHeight.new(fallbackCheckpoint),
                    treeState = anchor.treestate?.let(::TreeState) ?: inputs.lastCheckpointTreeState(),
                    recoverUntil = null
                )
            }

            else -> {
                WalletProvisioningPlan(
                    startBirthday = inputs.requestedBirthday ?: BlockHeight.new(fallbackCheckpoint),
                    treeState =
                        inputs.treeState(
                            inputs.requestedBirthday ?: network.saplingActivationHeight
                        ),
                    recoverUntil = null
                )
            }
        }

    override suspend fun getAccounts(): List<Account> {
        awaitDbReady()
        return runCatchingCancellable { backend.getAccounts().map(Account::new) }
            .onFailure { Twig.error(it) { "Get wallet accounts failed." } }
            .getOrElse { throw InitializeException.GetAccountsException(it) }
    }

    /**
     * [MOB-1455 follow-up] The engine session's anchor-retention floor
     * (`min_pending_migration_anchor_boundary` → `EngineConfig.anchor_retention`) is computed once,
     * at `start_session`. A session that was already live when a migration plan committed has NO
     * retention for the new plan's boundaries — and on the compressed testnet grid the plan's
     * first boundary is drawn blocks from the tip, so the live session scans past it within
     * minutes, permanently losing the checkpoint the transfer must later be proved against
     * (observed live 2026-07-28: boundary 4211652 crossed by the pre-commit session →
     * `AnchorNotFound` forever). Stop-then-restart recomputes the floor; the restart itself
     * follows every other stop-then-restart call site here (see [restartEngineAfter]).
     */
    override suspend fun restartSyncSession(): Boolean {
        if (closed.get()) return false
        if (!awaitPrepared()) return false
        engine.stop()
        restartEngineAfter("migration-plan commit (anchor-retention floor refresh)")
        return true
    }

    /**
     * The keyless engine restart every stop-then-restart call site above runs from a
     * [NonCancellable] context, so the engine always comes back up even when the caller itself
     * was cancelled; a restart failure is logged rather than thrown, so it can never mask
     * whatever outcome the caller was already returning or throwing. [operation] is a short,
     * human-readable name for the log message only (e.g. "account import", "rewind").
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun restartEngineAfter(operation: String) {
        withContext(NonCancellable) {
            try {
                engine.start(ufvk = null, birthday = startBirthday.value)
            } catch (e: Exception) {
                Twig.error(e) { "Slipstream engine restart after $operation failed" }
            }
        }
    }

    /**
     * Sequence: `restoreAnchor(intent = 1)` for `recoverUntil` (network-only, engine stays live)
     * -> stop engine -> `RustBackend.importAccountUfvk` -> restart. The restart's `ufvk = null`
     * is `FFI_JNI_CONTRACT.md` section 3.5's keyless mode - legal here only because the
     * `importAccountUfvk` call just above it wrote that account row.
     *
     * The engine is ALWAYS restarted once stopped, even when the import fails, so a failed
     * import never leaves the wallet permanently stuck in a non-syncing state. If [engine]
     * fails to quiesce before the import (see `FFI_JNI_CONTRACT.md` section 3.6), the import is
     * refused up front - `import_account_ufvk` is transactional, so nothing has been written
     * yet - and [InitializeException.ImportAccountCheckpointsNotReadyException] is thrown
     * instead of risking a write racing the still-unwinding sync pass.
     */
    override suspend fun importAccountByUfvk(setup: AccountImportSetup): Account {
        awaitReady()
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
                    torDir = engineTorDir
                )
            }
        val recoverUntil = anchor.height
        val treeState =
            CheckpointTool.loadNearest(context, network, setup.birthday).treeState().encoded
        val purpose = setup.purpose

        val quiescent = engine.stop()
        if (!quiescent) {
            engine.start(ufvk = null, birthday = startBirthday.value)
            throw InitializeException.ImportAccountCheckpointsNotReadyException(
                IllegalStateException(
                    "Slipstream engine did not quiesce before account import; refusing to import " +
                        "to avoid racing the still-unwinding sync pass."
                )
            )
        }

        val jniAccount =
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw if (ImportAccountErrors.isCheckpointsNotReady(e)) {
                    InitializeException.ImportAccountCheckpointsNotReadyException(e)
                } else {
                    InitializeException.ImportAccountException(e)
                }
            } finally {
                restartEngineAfter("account import")
            }
        accountsVersion.update { it + 1 }
        return Account.new(jniAccount)
    }

    override suspend fun getFastestServers(servers: List<LightWalletEndpoint>) =
        fastestServerFetcher(servers)

    override suspend fun getUnifiedAddress(account: Account): String =
        wrapGetAddress { backend.getCurrentAddress(account.accountUuid.value) }

    override suspend fun getCustomUnifiedAddress(
        account: Account,
        request: UnifiedAddressRequest
    ): String =
        wrapGetAddress { backend.getNextAvailableAddress(account.accountUuid.value, request.flags) }

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

    /**
     * The single readiness gate for all four address getters above; it sits OUTSIDE
     * [runCatchingCancellable] so a [SlipstreamNotReadyException] propagates as itself rather than
     * as a [RustLayerException.GetAddressException]. Relaxed to [awaitDbReady] - all four are pure
     * reads of address rows `initDataDb` provisions; the reserving
     * [getSingleUseTransparentAddress] is deliberately NOT one of them.
     */
    private suspend fun wrapGetAddress(block: suspend () -> String): String {
        awaitDbReady()
        return runCatchingCancellable { block() }.getOrElse {
            throw RustLayerException.GetAddressException(
                it
            )
        }
    }

    override fun refreshExchangeRateUsd() {
        scope.launch { refreshExchangeRateTrigger.emit(Unit) }
    }

    override suspend fun proposeTransfer(
        account: Account,
        recipient: String,
        amount: Zatoshi,
        memo: String
    ): Proposal {
        awaitReady()
        return spendService.proposeTransfer(account, recipient, amount, memo)
    }

    override suspend fun proposeFulfillingPaymentUri(
        account: Account,
        uri: String
    ): Proposal {
        awaitReady()
        return spendService.proposeFulfillingPaymentUri(account, uri)
    }

    override suspend fun proposeShielding(
        account: Account,
        shieldingThreshold: Zatoshi,
        memo: String,
        transparentReceiver: String?
    ): Proposal? {
        awaitReady()
        return spendService.proposeShielding(account, shieldingThreshold, memo, transparentReceiver)
    }

    override suspend fun proposeOrchardToIronwoodMigration(account: Account): Proposal {
        awaitReady()
        return spendService.proposeOrchardToIronwoodMigration(account)
    }

    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): Flow<TransactionSubmitResult> {
        awaitReady()
        return spendService.createProposedTransactions(proposal, usk)
    }

    override suspend fun createPcztFromProposal(
        accountUuid: AccountUuid,
        proposal: Proposal
    ): Pczt {
        awaitReady()
        return runCatchingCancellable { spendService.createPcztFromProposal(accountUuid, proposal) }
            .getOrElse { throw PcztException.CreatePcztFromProposalException(it.message, it) }
    }

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt {
        awaitReady()
        return runCatchingCancellable { spendService.redactPcztForSigner(pczt) }
            .getOrElse { throw PcztException.RedactPcztForSignerException(it.message, it) }
    }

    override suspend fun pcztRequiresSaplingProofs(pczt: Pczt): Boolean {
        awaitReady()
        return runCatchingCancellable { spendService.pcztRequiresSaplingProofs(pczt) }
            .getOrElse { throw PcztException.PcztRequiresSaplingProofsException(it.message, it) }
    }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
        awaitReady()
        return runCatchingCancellable { spendService.addProofsToPczt(pczt) }
            .getOrElse { throw PcztException.AddProofsToPcztException(it.message, it) }
    }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt
    ): Flow<TransactionSubmitResult> {
        awaitReady()
        return spendService.createTransactionFromPczt(pcztWithProofs, pcztWithSignatures)
    }

    override suspend fun isValidShieldedAddr(address: String): Boolean =
        backend.isValidSaplingAddr(address)

    override suspend fun isValidTransparentAddr(address: String): Boolean =
        backend.isValidTransparentAddr(address)

    override suspend fun isValidUnifiedAddr(address: String): Boolean =
        backend.isValidUnifiedAddr(address)

    override suspend fun isValidTexAddr(address: String): Boolean = backend.isValidTexAddr(address)

    /** R49: composition of R45-R48 in their exact order, `AddressType.Invalid` on failure (ports `SdkSynchronizer.kt`'s `validateAddress`). */
    override suspend fun validateAddress(address: String): AddressType =
        runCatchingCancellable {
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
        val serviceMode =
            sdkFlags ifTor ServiceMode.Group("SlipstreamSynchronizer.validateConsensusBranch")
        val serverBranchId =
            runCatchingCancellable {
                (walletClient.getServerInfo(serviceMode) as? Response.Success)?.result?.consensusBranchId
            }.getOrNull()
        val currentChainTip =
            when (val response = walletClient.getLatestBlockHeight(serviceMode)) {
                is Response.Success -> runCatchingCancellable { BlockHeight.new(response.result.value) }.getOrNull()
                is Response.Failure -> null
            }
        val sdkBranchId =
            currentChainTip?.let { tip ->
                runCatchingCancellable { backend.getBranchIdForHeight(tip.value) }.getOrNull()
            }

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
                runCatchingCancellable { BlockHeight.new(remoteInfo.saplingActivationHeightUnsafe.value) }.getOrElse {
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
                    is Response.Success -> {
                        runCatchingCancellable { BlockHeight.new(response.result.value) }.getOrElse {
                            return ServerValidation.InValid(
                                it
                            )
                        }
                    }

                    is Response.Failure -> {
                        return ServerValidation.InValid(response.toThrowable())
                    }
                }
            val sdkBranchId =
                runCatchingCancellable { "%x".format(Locale.ROOT, backend.getBranchIdForHeight(currentChainTip.value)) }
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

    override suspend fun getTransparentBalance(tAddr: String): Zatoshi {
        awaitDbReady()
        return spendService.getTransparentBalance(tAddr)
    }

    override suspend fun refreshUtxos(
        account: Account,
        since: BlockHeight
    ): Int? {
        awaitReady()
        return runCatchingCancellable {
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
    }

    /**
     * The `engine.start(ufvk = null, ...)` restart below is keyless (`FFI_JNI_CONTRACT.md` section
     * 3.5) - legal only because provisioning guarantees an account row already exists by the time
     * any restart runs. Restarted from a [NonCancellable] `finally` so the engine always comes back
     * up - even under cancellation - and a restart failure is logged rather than masking the
     * original rewind outcome.
     *
     * [height] is coerced to at least the resolved [startBirthday]: `WalletCoordinator.rescanBlockchain`
     * reads [latestBirthdayHeight] BEFORE this call suspends on the readiness gate, so during
     * preparation it captures the provisional birthday - rewinding to which would mean a full-chain
     * rescan of a wallet whose real birthday is far higher.
     */
    override suspend fun rewindToNearestHeight(height: BlockHeight): BlockHeight? {
        awaitReady()
        engine.stop()
        return try {
            rewindRetrying(maxOf(height, startBirthday))
        } finally {
            restartEngineAfter("rewind")
        }
    }

    private suspend fun rewindRetrying(height: BlockHeight): BlockHeight? {
        val result =
            runCatchingCancellable { backend.rewindToHeight(height.value) }
                .getOrElse { return null }
        return when (result) {
            is JniRewindResult.Success -> {
                BlockHeight.new(result.height)
            }

            is JniRewindResult.Invalid -> {
                if (result.safeRewindHeight != -1L) rewindRetrying(BlockHeight.new(result.safeRewindHeight)) else null
            }
        }
    }

    /**
     * Same `ufvk = null` keyless-restart contract as [rewindToNearestHeight]
     * (`FFI_JNI_CONTRACT.md` section 3.5), and the same [startBirthday] coercion.
     */
    override suspend fun rewindToHeight(height: BlockHeight) {
        awaitReady()
        engine.stop()
        try {
            backend.rewindToHeight(maxOf(height, startBirthday).value)
        } finally {
            restartEngineAfter("rewind")
        }
    }

    override fun getMemos(transactionOverview: TransactionOverview): Flow<String> =
        flow {
            val outputs = transactionReader.getOutputProperties(transactionOverview.txId.value)
            for (output in outputs) {
                if (output.poolCode == TRANSPARENT_POOL_CODE) {
                    emit("")
                } else {
                    val memo =
                        runCatchingCancellable {
                            backend.getMemoAsUtf8(
                                transactionOverview.txId.value.byteArray,
                                output.poolCode,
                                output.index
                            )
                        }.getOrNull()
                    emit(memo ?: "")
                }
            }
        }.onStart { awaitDbReady() }

    override fun getTransactionsByMemoSubstring(query: String): Flow<List<TransactionId>> =
        flow {
            emit(
                transactionReader
                    .getTransactionsByMemoSubstring(query)
                    .map { TransactionId.new(it) }
            )
        }.onStart { awaitDbReady() }

    override fun getRecipients(transactionOverview: TransactionOverview): Flow<TransactionRecipient> {
        require(transactionOverview.isSentTransaction) { "Recipients can only be queried for sent transactions" }
        return flow {
            transactionReader.getRecipients(transactionOverview.txId.value).forEach { emit(it) }
        }.onStart { awaitDbReady() }
    }

    override suspend fun getRecipients(): Map<TransactionId, List<TransactionRecipient>> {
        awaitDbReady()
        return transactionReader.getAllRecipients().mapKeys { (txId, _) -> TransactionId.new(txId) }
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

    override suspend fun getTransactionOutputs(transactionOverview: TransactionOverview): List<TransactionOutput> {
        awaitDbReady()
        return transactionReader.getTransactionOutputs(transactionOverview.txId.value)
    }

    override suspend fun getTransactionOutputs(): Map<TransactionId, List<TransactionOutput>> {
        awaitDbReady()
        return transactionReader
            .getAllTransactionOutputs()
            .mapKeys { (txId, _) -> TransactionId.new(txId) }
    }

    override suspend fun getTransactions(accountUuid: AccountUuid): Flow<List<TransactionOverview>> {
        awaitDbReady()
        return transactionsController.forAccount(accountUuid)
    }

    /**
     * Gated on full readiness, unlike its read-only neighbours: `getSingleUseTransparentAddress`
     * RESERVES an address (`reserve_next_n_ephemeral_addresses`), so it writes to the very tables
     * the preparation tail is still provisioning.
     */
    override suspend fun getSingleUseTransparentAddress(accountUuid: AccountUuid): SingleUseTransparentAddress {
        awaitReady()
        return SingleUseTransparentAddress.new(backend.getSingleUseTransparentAddress(accountUuid.value))
    }

    override suspend fun checkSingleUseTransparentAddress(accountUuid: AccountUuid): Boolean =
        when (
            val response =
                walletClient.checkSingleUseTransparentAddress(
                    accountUuid.value,
                    ServiceMode.UniqueTor
                )
        ) {
            is Response.Success -> response.result != null
            is Response.Failure -> false
        }

    override suspend fun fetchUtxosByAddress(
        accountUuid: AccountUuid,
        address: String
    ): Boolean =
        when (
            val response =
                walletClient.fetchUtxosByAddress(accountUuid.value, address, ServiceMode.UniqueTor)
        ) {
            is Response.Success -> response.result != null
            is Response.Failure -> false
        }

    /**
     * Launches [block] on [scope], guarded against escaping exceptions: a [CancellationException]
     * is always rethrown (structured concurrency), any other exception is logged and forwarded to
     * [SlipstreamEngine.onCriticalErrorHandler] instead of propagating out of [scope]'s
     * [SupervisorJob] as an unhandled crash. [operation] is a short, human-readable name for the
     * log message only.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun launchGuarded(
        operation: String,
        block: suspend () -> Unit
    ) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error(e) { "Slipstream $operation failed" }
                engine.onCriticalErrorHandler?.invoke(e)
            }
        }
    }

    /**
     * R24: on-demand only - the engine performs in-pass enhancement itself; this is the explicit,
     * app-triggered path. A no-op once [close] has already run - post-close lifecycle calls are
     * no-ops.
     */
    override fun enhanceTransaction(txId: TransactionId) {
        if (closed.get()) return
        launchGuarded("enhanceTransaction") {
            if (!awaitPrepared()) return@launchGuarded
            val serviceMode = sdkFlags ifTor ServiceMode.Group("enhance-${txId.txIdString()}")
            when (val response = walletClient.fetchTransaction(txId.value.byteArray, serviceMode)) {
                is Response.Success -> {
                    backend.decryptAndStoreTransaction(response.result.data, minedHeight = null)
                }

                is Response.Failure -> {
                    backend.setTransactionStatus(
                        txId.value.byteArray,
                        status = TXID_NOT_RECOGNIZED_STATUS
                    )
                }
            }
            engine.notifyTxChange()
        }
    }

    /**
     * Pairs with [onForeground]'s [SlipstreamEngine.isRunning] guard: [SlipstreamEngine.stop]
     * always clears it, so a following foreground sees an honest running/stopped state. A no-op
     * once [close] has already run - post-close lifecycle calls are no-ops.
     */
    override fun onBackground() {
        if (closed.get()) return
        inForeground.set(false)
        // A burst owns the engine while it runs; its restore will apply this backgrounded state.
        if (burstActive.get()) return
        launchGuarded("onBackground") {
            if (!awaitPrepared()) return@launchGuarded
            engine.stopPolling()
            engine.stop()
            lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.SOFT) }
        }
    }

    override fun pause() {
        if (closed.get()) return
        migrationPaused.update { true }
        launchGuarded("pause") {
            if (!awaitPrepared()) return@launchGuarded
            engine.stopPolling()
        }
    }

    override fun resume() {
        if (closed.get()) return
        migrationPaused.update { false }
        launchGuarded("resume") {
            if (!awaitPrepared()) return@launchGuarded
            engine.startPolling()
        }
    }

    /**
     * Force-starts the engine (which [onBackground] stopped) and drives a bounded sync pass, then
     * restores the pre-burst lifecycle state in a [NonCancellable] `finally`. Refuses to run while a
     * migration privacy pause is active — sync traffic and a later migration broadcast must stay
     * decoupled — and never broadcasts itself. See [Synchronizer.syncBurst].
     *
     * Readiness is PROBED, never awaited: the callers are background workers under a time budget,
     * so an instance that is still preparing reports UNAVAILABLE instead of blocking one.
     */
    override suspend fun syncBurst(
        timeout: Duration,
        targetCheckInterval: Duration,
        isTargetReached: suspend () -> Boolean,
    ): Synchronizer.SyncBurstResult {
        if (closed.get()) return Synchronizer.SyncBurstResult.UNAVAILABLE
        if (prepareState.value !is PrepareState.Ready) return Synchronizer.SyncBurstResult.UNAVAILABLE
        if (migrationPaused.value) return Synchronizer.SyncBurstResult.PRIVACY_BLOCKED
        if (!burstActive.compareAndSet(false, true)) return Synchronizer.SyncBurstResult.UNAVAILABLE

        val wasRunning = engine.isRunning
        // A stopped engine's lastSnapshot retains its last (stale) value; only a snapshot produced
        // by a tick AFTER this burst (re)started the engine proves real progress. When the engine
        // was already running (foreground), the current snapshot is live and trusted.
        val staleSnapshot = if (wasRunning) null else engine.lastSnapshot.value
        return try {
            if (!wasRunning) engine.start(ufvk = null, birthday = startBirthday.value)
            engine.startPolling()
            withTimeoutOrNull(timeout) {
                while (true) {
                    if (isTargetReached()) return@withTimeoutOrNull Synchronizer.SyncBurstResult.TARGET_REACHED
                    val snap = engine.lastSnapshot.value
                    if (snap != null && snap !== staleSnapshot) {
                        when (snap.state) {
                            // 3 = done/following the tip; tipFresh = this run refreshed the tip.
                            SNAPSHOT_STATE_DONE -> {
                                if (snap.tipFresh) return@withTimeoutOrNull Synchronizer.SyncBurstResult.SYNCED_TO_TIP
                            }

                            // 2 = error episode (server unreachable etc.). 0 (idle) is NOT terminal —
                            // it's the transient just-started phase; the timeout covers a real hang.
                            SNAPSHOT_STATE_ERROR -> {
                                return@withTimeoutOrNull Synchronizer.SyncBurstResult.DISCONNECTED
                            }
                        }
                    }
                    delay(targetCheckInterval)
                }
                @Suppress("UNREACHABLE_CODE")
                Synchronizer.SyncBurstResult.TIMEOUT
            } ?: Synchronizer.SyncBurstResult.TIMEOUT
        } finally {
            withContext(NonCancellable) { restoreAfterBurst() }
            burstActive.set(false)
        }
    }

    /** Re-applies whatever lifecycle/pause state should hold now that a [syncBurst] is over. */
    private suspend fun restoreAfterBurst() {
        when {
            // Mirror onBackground(): app is backgrounded — fully stop so no sync traffic lingers
            // between the burst and a later broadcast (privacy), and to spare the battery.
            !inForeground.get() -> {
                engine.stopPolling()
                engine.stop()
                lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.SOFT) }
            }

            // Mirror pause(): the privacy gate closed mid-burst (typically because the burst reached
            // the target and hasOverdueTransfers flipped, so WalletCoordinator paused us). Keep the
            // engine warm but stop polling, per the keep-alive sync-block design.
            migrationPaused.value -> {
                engine.stopPolling()
            }

            // Mirror onForeground(): a live foreground wallet — leave it running and polling.
            else -> {
                engine.startPolling()
            }
        }
    }

    /**
     * [SlipstreamEngine.start] unconditionally aborts any in-flight pass and reruns its bounded
     * quiescence drain, so restarting an already-running engine on every foreground would churn
     * useful work instead of resuming it. [SlipstreamEngine.isRunning] guards against that - skip
     * the native restart when the engine is already live. [SlipstreamEngine.startPolling] stays
     * unconditional; it is already an idempotent cancel-and-relaunch, but is skipped while paused
     * so a foreground event does not undo an in-flight migration sync-block. A no-op once [close]
     * has already run - post-close lifecycle calls are no-ops.
     */
    override fun onForeground() {
        if (closed.get()) return
        inForeground.set(true)
        // A burst owns the engine while it runs; its restore will apply this foregrounded state.
        if (burstActive.get()) return
        launchGuarded("onForeground") {
            if (!awaitPrepared()) return@launchGuarded
            lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.NORMAL) }
            if (!engine.isRunning) {
                engine.start(ufvk = null, birthday = startBirthday.value)
            }
            if (!migrationPaused.value) {
                engine.startPolling()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun getTorHttpClient(config: HttpClientConfig<HttpClientEngineConfig>.() -> Unit): HttpClient {
        if (!sdkFlags.isTorEnabled && !sdkFlags.isExchangeRateEnabled) throw TorUnavailableException()
        val client =
            lazyTorClient
                ?: throw TorInitializationErrorException(NullPointerException("Tor has not been initialized during synchronizer setup"))
        val isolatedTor =
            try {
                client.getOrCreate().isolatedTorClient()
            } catch (e: CancellationException) {
                throw e
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

    override suspend fun debugQuery(query: String): String {
        awaitDbReady()
        return transactionReader.debugQuery(query)
    }

    /**
     * Sequence: stop engine -> delete -> restart -> bump [accountsVersion]. The restart's
     * `ufvk = null` is `FFI_JNI_CONTRACT.md` section 3.5's keyless mode - safe here because any
     * other accounts still exist in `data.db` for the engine to read.
     */
    override suspend fun deleteAccount(accountUuid: AccountUuid): Boolean {
        awaitReady()
        engine.stop()
        val deleted =
            try {
                backend.deleteAccount(accountUuid.value)
            } finally {
                restartEngineAfter("account deletion")
            }
        engine.notifyTxChange()
        accountsVersion.update { it + 1 }
        return deleted
    }

    override suspend fun getTreeState(height: BlockHeight): ByteArray {
        val serviceMode = sdkFlags ifTor ServiceMode.UniqueTor
        return when (
            val response =
                walletClient.getTreeState(BlockHeightUnsafe(height.value), serviceMode)
        ) {
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
     *
     * [prepareState] flips to [PrepareState.Closed] synchronously, before this returns - from
     * [PrepareState.DbReady] just as from [PrepareState.Preparing], since the tail is still live in
     * both - so gated callers waiting on either readiness gate are released immediately rather than
     * at the end of the shutdown. Cancelling and joining [prepareJob] is then the FIRST shutdown
     * step: it keeps a still-running [prepare] from calling [SlipstreamEngine.open] (which asserts
     * `handle == 0`) against a handle [SlipstreamEngine.free] has meanwhile dropped. The join can
     * take as long as the in-flight `restoreAnchor` JNI call - a blocking, engine-bounded network
     * call, which is why [prepare] checks for a lost [PrepareState.DbReady] compare-and-set before
     * starting one - but [InstanceGuard.markShuttingDown] already makes a concurrent `acquire` wait
     * for exactly this job, so a synchronizer replacement still sequences correctly.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            prepareState.update {
                if (it is PrepareState.Preparing || it is PrepareState.DbReady) PrepareState.Closed else it
            }
            val shutdownJob =
                scope.launch {
                    /*
                     * Isolates each shutdown step from the others: a single step's failure is
                     * logged but never skips the remaining steps, so a wedged native call can
                     * never leave e.g. [walletClient] undisposed.
                     */
                    @Suppress("TooGenericExceptionCaught")
                    suspend fun step(
                        label: String,
                        block: suspend () -> Unit
                    ) {
                        try {
                            block()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Twig.error(e) { "Slipstream close step $label failed" }
                        }
                    }

                    step("prepare-cancel") { prepareJob?.cancelAndJoin() }
                    step("stopPolling") { engine.stopPolling() }
                    step("stop") { engine.stop() }
                    step("free") { engine.free() }
                    step("shutdown") { engine.shutdown() }
                    step("lazyTorClient.dispose") { lazyTorClient?.dispose() }
                    step("walletClient.dispose") { walletClient.dispose() }
                    step("exchangeRateFetcher.dispose") { exchangeRateFetcher?.dispose() }
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

        /** [com.zodl.slipstream.model.SlipstreamSnapshot.state] values [syncBurst] treats as terminal. */
        private const val SNAPSHOT_STATE_ERROR = 2
        private const val SNAPSHOT_STATE_DONE = 3

        /**
         * Mirrors `Synchronizer.new` parameter-for-parameter - same names, order, and defaults -
         * so an app call site changes exactly one token.
         *
         * DELIBERATE DEVIATION from `Synchronizer.new`'s "returns a ready instance" contract, and
         * the reason this factory now returns in milliseconds: only the cheap wiring runs here
         * (`ensureLoaded`, UFVK derivation, directories, `RustBackend.new`, the collaborators).
         * Everything heavy - `restoreAnchor` (a blocking network call that, on a cold Tor bootstrap
         * during a restore, is bounded in tens of seconds), `initDataDb`/`createAccount`,
         * `engine.open`, `engine.start` - is deferred into the returned instance's own preparation
         * job, exactly as the iOS twin splits `init` from `prepare()`. The host therefore gets a
         * live synchronizer reporting `INITIALIZING` instead of a null `synchronizer` StateFlow for
         * the whole of provisioning, and a provisioning failure surfaces on
         * [onSetupErrorHandler] instead of killing the host's `callbackFlow` with no UI feedback.
         *
         * Consequences a caller must know:
         * - argument validation still throws synchronously (alias, single-instance guard, a
         *   missing seed or birthday);
         * - every engine- or database-backed member awaits preparation, so a call made immediately
         *   after this returns suspends rather than failing;
         * - a preparation failure does NOT release the [InstanceGuard] key - the instance owns it
         *   until [close], which the host's `awaitClose` always calls.
         *
         * Runs on `Dispatchers.IO`: even the cheap wiring touches disk, and callers include
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
                /*
                 * Only cheap-wiring failures can land here now; the preparation tail's failures are
                 * latched on the instance, which keeps the guard key until its own close().
                 */
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
            val sdkFlags =
                SdkFlags(isTorEnabled = isTorEnabled, isExchangeRateEnabled = isExchangeRateEnabled)

            val ufvk: String? =
                when (walletInitMode) {
                    WalletInitMode.ExistingWallet -> {
                        null
                    }

                    WalletInitMode.NewWallet, WalletInitMode.RestoreWallet -> {
                        val seed =
                            requireNotNull(setup?.seed) { "AccountCreateSetup with a seed is required for $walletInitMode" }
                        DerivationTool
                            .getInstance()
                            .deriveUnifiedFullViewingKeys(
                                seed.byteArray,
                                zcashNetwork,
                                numberOfAccounts = 1
                            )[0]
                            .encoding
                    }
                }

            val noBackupRoot = applicationContext.getNoBackupFilesDirSuspend()
            val zcashNoBackupDir = Files.getZcashNoBackupSubdirectory(applicationContext)
            val engineTorDir =
                if (isTorEnabled) {
                    File(
                        zcashNoBackupDir,
                        ENGINE_TOR_SUBDIR
                    ).apply { mkdirs() }.absolutePath
                } else {
                    null
                }
            /*
             * Fail-fast on caller bugs stays here, synchronous to `new()`: a missing restore
             * birthday must never degrade into an asynchronous setup error.
             */
            val requestedBirthday =
                when (walletInitMode) {
                    WalletInitMode.RestoreWallet -> {
                        requireNotNull(birthday) { "birthday is required for WalletInitMode.RestoreWallet" }
                    }

                    WalletInitMode.NewWallet, WalletInitMode.ExistingWallet -> {
                        birthday
                    }
                }

            val dbFile = DataDbPath.dataDbFile(noBackupRoot, alias, zcashNetwork)

            val fsBlockDbRoot =
                File(
                    applicationContext.filesDir,
                    "slipstream_unused_fsblockdb"
                ).apply { mkdirs() }
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

            val engine =
                SlipstreamEngine(
                    dbFile.absolutePath,
                    lightWalletEndpoint,
                    zcashNetwork.id,
                    engineTorDir,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                )

            val activityManager =
                applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

            /*
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
                        UsdExchangeRateFetcher(
                            isolatedTorClient =
                                LazyTorClient {
                                    holder.getOrCreate().isolatedTorClient()
                                }
                        )
                    }
                } else {
                    null
                }

            val walletClientFactory =
                WalletClientFactory(
                    context = applicationContext,
                    torClient = lazyTorClient.takeIf { isTorEnabled }
                )
            val walletClient = walletClientFactory.create(endpoint = lightWalletEndpoint)
            val fastestServerFetcher =
                FastestServerFetcher(typesafeBackend, zcashNetwork, walletClientFactory, sdkFlags)

            val transactionReader = SlipstreamTransactionReader(dbFile)
            val transactionsController = TransactionsController(transactionReader, engine, typesafeBackend)

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
                    },
                    notifyTxChange = engine::notifyTxChange
                )

            /*
             * The deferred tail's every JNI/assets touch, captured as a lambda: `restoreAnchor`
             * behind [SlipstreamAnchorSource], the bundled-checkpoint height, the two
             * [CheckpointTool] reads and the DbReady balance seed's summary read behind their own.
             * `totalMemoryBytes` is a plain value - the
             * `ActivityManager` binder call above is cheap enough to stay on this path.
             */
            val prepareInputs =
                PrepareInputs(
                    walletInitMode = walletInitMode,
                    requestedBirthday = requestedBirthday,
                    setup = setup,
                    ufvk = ufvk,
                    anchorSource = { intent, birthdayHeight, fallbackCheckpointHeight ->
                        SlipstreamNative.restoreAnchor(
                            serverHost = lightWalletEndpoint.host,
                            serverPort = lightWalletEndpoint.port,
                            useTls = lightWalletEndpoint.isSecure,
                            networkId = zcashNetwork.id,
                            intent = intent,
                            birthdayHeight = birthdayHeight,
                            fallbackCheckpointHeight = fallbackCheckpointHeight,
                            torDir = engineTorDir
                        )
                    },
                    fallbackCheckpointHeight = {
                        newestBundledCheckpointHeight(applicationContext, zcashNetwork)
                    },
                    treeState = { height ->
                        CheckpointTool
                            .loadNearest(applicationContext, zcashNetwork, height)
                            .treeState()
                    },
                    lastCheckpointTreeState = {
                        CheckpointTool
                            .loadLast(applicationContext, zcashNetwork)
                            .treeState()
                    },
                    dbWalletSummary = { typesafeBackend.getWalletSummary() },
                    totalMemoryBytes = memoryInfo.totalMem
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
                engineTorDir = engineTorDir,
                lazyTorClient = lazyTorClient,
                exchangeRateFetcher = exchangeRateFetcher,
                sdkFlags = sdkFlags,
                fastestServerFetcher = fastestServerFetcher,
                transactionReader = transactionReader,
                transactionsController = transactionsController,
                spendService = spendService,
                broadcasterImpl = broadcaster,
                resubmissionTicker = resubmissionTicker,
                /*
                 * Provisional until the preparation tail resolves the anchor; see
                 * [SlipstreamSynchronizer.latestBirthdayHeight].
                 */
                startBirthday = requestedBirthday ?: zcashNetwork.saplingActivationHeight,
                prepareInputs = prepareInputs
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
         *
         * Like `SdkSynchronizer.erase`, this awaits an in-flight shutdown of the same key before
         * deleting, and holds the [InstanceGuard] mutex across the deletion. That await is what
         * makes the reset path safe: [close] marks the key shutting down synchronously, but the
         * engine teardown that drops the database handles runs asynchronously afterwards, and
         * unlinking the files under a live engine mmap is corruption territory.
         */
        suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String = ZcashSdk.DEFAULT_ALIAS
        ): Boolean {
            val key = SlipstreamKey(network, alias)
            return InstanceGuard.withKeyInactive(key) {
                withContext(Dispatchers.IO) {
                    val dbFile =
                        DataDbPath.dataDbFile(
                            appContext.applicationContext.getNoBackupFilesDirSuspend(),
                            alias,
                            network
                        )
                    val walFile = File("${dbFile.path}-wal")
                    val shmFile = File("${dbFile.path}-shm")
                    listOf(dbFile, walFile, shmFile).map { !it.exists() || it.delete() }.all { it }
                }
            }
        }

        private const val ENGINE_TOR_SUBDIR = "slipstream_tor"
    }
}
