package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PercentDecimal
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.SlipstreamNative
import com.zodl.slipstream.SlipstreamSyncException
import com.zodl.slipstream.model.SlipstreamSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Slipstream handle owner and 2 s poll loop (`KOTLIN_ROSETTA.md` section 2.2). Every native
 * call that takes the handle runs on [SlipstreamDispatchers.SLIPSTREAM_IO]; the tick order is
 * fixed: snapshot -> drainEvents -> stall watchdog -> walletSummary -> scalar flows -> state
 * dispatch -> the section 5.4 tx re-query rule.
 */
internal class SlipstreamEngine(
    private val dbPath: String,
    private val endpoint: LightWalletEndpoint,
    /** 1 = mainnet, 0 = testnet (`FFI_JNI_CONTRACT.md` section 3.2). */
    private val networkId: Int,
    /** Engine-owned Tor state directory - NEVER the SDK's own `TorClient` dir (DECISIONS.md D7). */
    private val torDir: String?,
    /** Synchronizer-owned; cancelled in the synchronizer's `close()`. */
    private val scope: CoroutineScope
) {
    private var handle: Long = 0L
    private var gate = PollGate.INITIAL
    private var errorGate = ErrorEpisodeGate.INITIAL
    private var pollJob: Job? = null
    private var lastStartBirthday: Long = 0L
    private var running = false

    /**
     * Mirrors the iOS twin's `isRunning` member (`SlipstreamSynchronizer.swift`): true from a
     * successful [start] until the next [stop], including the internal retry [dispatchState] issues
     * on error-episode recovery. Lets a caller (`SlipstreamSynchronizer.onForeground`) tell an
     * already-running engine apart from a stopped one - `FFI_JNI_CONTRACT.md` section 3.5's `start`
     * unconditionally aborts any in-flight pass and reruns its bounded quiescence drain, so calling
     * it again on an engine that is already live churns useful work instead of resuming it.
     */
    val isRunning: Boolean
        get() = running

    /** R63: `state == 2` dispatch, once per error episode (`KOTLIN_ROSETTA.md` section 2.3). Wired in Phase 4, T10. */
    var onProcessorErrorHandler: ((Throwable?) -> Boolean)? = null

    /** R64: fires on the first `state ∈ {0, 1, 3}` tick after an error episode. Wired in Phase 4, T10. */
    var onProcessorErrorResolved: (() -> Unit)? = null

    /** R62: adapter-internal crash during [tick] itself - a poll-loop bug, not an engine state. Wired in Phase 4, T10. */
    var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * Fires once per tick, after every engine-owned state update - the only per-heartbeat signal
     * available to host logic that needs a cadence rather than a state change (e.g. the T8
     * resubmission tick, which needs "every 150 ticks" and cannot be built on a `StateFlow`, since
     * `StateFlow` suppresses unchanged values). Exceptions are caught by the same `runCatching` as
     * the rest of [tick] (see [startPolling]).
     */
    var onTick: (suspend (SlipstreamSnapshot) -> Unit)? = null

    val status = MutableStateFlow(Status.INITIALIZING)
    val progress = MutableStateFlow(PercentDecimal.ZERO_PERCENT)
    val areFundsSpendable = MutableStateFlow(false)
    val networkHeight = MutableStateFlow<BlockHeight?>(null)
    val fullyScannedHeight = MutableStateFlow<BlockHeight?>(null)
    val walletBalances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
    val lastSnapshot = MutableStateFlow<SlipstreamSnapshot?>(null)

    /** Bumped whenever the section 5.4 rule fires; the transaction read path maps it to a re-read. */
    val requeryTicks = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    suspend fun open(totalMemoryBytes: Long) =
        withContext(SlipstreamDispatchers.SLIPSTREAM_IO) {
            check(handle == 0L) { "engine already open" }
            SlipstreamNative.ensureLoaded()
            handle =
                SlipstreamNative.open(
                    dbPath = dbPath,
                    serverHost = endpoint.host,
                    serverPort = endpoint.port,
                    useTls = endpoint.isSecure,
                    networkId = networkId,
                    totalMemoryBytes = totalMemoryBytes
                )
            check(handle != 0L) { "slipstream open() failed" }
            tick()
        }

    suspend fun start(
        ufvk: String?,
        birthday: Long
    ) = withContext(SlipstreamDispatchers.SLIPSTREAM_IO) {
        check(handle != 0L) { "start before open" }
        lastStartBirthday = birthday
        SlipstreamNative.start(handle, ufvk, birthday, torDir)
        running = true
    }

    suspend fun stop() =
        withContext(SlipstreamDispatchers.SLIPSTREAM_IO) {
            if (handle != 0L) SlipstreamNative.stop(handle)
            running = false
        }

    suspend fun notifyTxChange() =
        withContext(SlipstreamDispatchers.SLIPSTREAM_IO) {
            if (handle != 0L) SlipstreamNative.notifyTxChange(handle)
        }

    /** A tick crash never kills the loop; [onCriticalErrorHandler] is offered the exception instead of a silent swallow. */
    fun startPolling() {
        pollJob?.cancel()
        pollJob =
            scope.launch(SlipstreamDispatchers.SLIPSTREAM_IO) {
                while (isActive) {
                    runCatching { tick() }.onFailure { onCriticalErrorHandler?.invoke(it) }
                    delay(POLL_INTERVAL_MS.milliseconds)
                }
            }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Runs ON [SlipstreamDispatchers.SLIPSTREAM_IO]. One tick, `KOTLIN_ROSETTA.md` section 2.2 order. */
    private suspend fun tick() {
        if (handle == 0L) return

        val snap = SlipstreamNative.snapshot(handle)

        SlipstreamNative.drainEvents(handle)

        if (snap.state == 1 && snap.stalledSeconds >= STALL_LOG_SECONDS) {
            android.util.Log.e(
                "slipstream",
                "[slipstream] sync stalled: no counter movement for ${snap.stalledSeconds}s (state=1)"
            )
        }

        val summary = SlipstreamNative.walletSummary(
            handle = handle,
            trustedConfirmations = TRUSTED,
            untrustedConfirmations = UNTRUSTED,
            allowZeroConfShielding = ALLOW_ZERO_CONF
        )
        summary?.let {
            walletBalances.value = it.toAccountBalances(isRecovering = snap.isRecovering, tipFresh = snap.tipFresh)
            fullyScannedHeight.value = it.fullyScannedHeight.takeIf { height -> height > 0 }?.let(BlockHeight::new)
        }

        if (snap.chainTip > 0) networkHeight.value = BlockHeight.new(snap.chainTip)
        progress.value = permilleToPercentDecimal(snap.progressPermille)
        areFundsSpendable.value = snap.spendableHint

        lastSnapshot.value = snap
        dispatchState(snap)

        val decision = gate.reduce(snap)
        gate = decision.next
        if (decision.requeryTransactions) requeryTicks.tryEmit(Unit)

        onTick?.invoke(snap)
    }

    /**
     * `KOTLIN_ROSETTA.md` section 2.3 verbatim: `state == 2` dispatches [onProcessorErrorHandler]
     * exactly once per episode (a `true` return retries via [start]); the first `state ∈ {0, 1, 3}`
     * tick after an episode dispatches [onProcessorErrorResolved]. [ErrorEpisodeGate] is the pure
     * enter/stay/leave/none decision; this function is only the side-effecting shell around it.
     */
    private suspend fun dispatchState(snap: SlipstreamSnapshot) {
        status.value = mapEngineState(snap.state)
        val (transition, next) = errorGate.reduce(snap.state)
        errorGate = next
        when (transition) {
            ErrorEpisodeTransition.ENTER -> {
                val retry = onProcessorErrorHandler?.invoke(SlipstreamSyncException(snap.chainTip)) ?: false
                if (retry) start(ufvk = null, birthday = lastStartBirthday)
            }
            ErrorEpisodeTransition.LEAVE -> onProcessorErrorResolved?.invoke()
            ErrorEpisodeTransition.STAY, ErrorEpisodeTransition.NONE -> Unit
        }
    }

    suspend fun free() =
        withContext(SlipstreamDispatchers.SLIPSTREAM_IO) {
            if (handle != 0L) {
                SlipstreamNative.free(handle)
                handle = 0L
            }
        }

    companion object {
        /** `HOSTING.md` section 9: a steady 1-2 s tick. */
        const val POLL_INTERVAL_MS = 2_000L
        const val STALL_LOG_SECONDS = 120L

        /** ZIP-315 `ConfirmationsPolicy` default `{3, 10, true}` (`FFI_JNI_CONTRACT.md` section 3.9). */
        const val TRUSTED = 3
        const val UNTRUSTED = 10
        const val ALLOW_ZERO_CONF = true
    }
}
