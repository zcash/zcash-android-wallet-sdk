package com.zodl.slipstream

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.internal.model.JniAccount
import cash.z.ecc.android.sdk.internal.model.JniAccountUsk
import cash.z.ecc.android.sdk.internal.model.JniRewindResult
import cash.z.ecc.android.sdk.internal.model.RecoveryProgress
import cash.z.ecc.android.sdk.internal.model.ScanProgress
import cash.z.ecc.android.sdk.internal.model.TreeState
import cash.z.ecc.android.sdk.internal.model.WalletSummary
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.PrepareInputs
import com.zodl.slipstream.internal.SlipstreamAnchorSource
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import com.zodl.slipstream.internal.db.TransactionsController
import com.zodl.slipstream.internal.spend.ResubmissionTicker
import com.zodl.slipstream.internal.spend.SlipstreamBroadcaster
import com.zodl.slipstream.internal.spend.SlipstreamSpendService
import com.zodl.slipstream.model.SlipstreamRestoreAnchor
import com.zodl.slipstream.model.SlipstreamSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.after
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * H6: covers the lifecycle crash paths H1/H4/H5 fixed above - [SlipstreamSynchronizer.close]'s
 * step isolation, the [SlipstreamSynchronizer.closed] guards on `enhanceTransaction`/
 * `onBackground`/`onForeground`, [SlipstreamSynchronizer.launchGuarded]'s critical-error-handler
 * forwarding, and the restart-after-failure bracket shared by `rewindToHeight`/`deleteAccount`.
 *
 * Every mock here uses plain Mockito (`mockito-inline`'s mock maker, already enabled for this
 * source set - see `SlipstreamSpendServiceOrderingTest`), no matcher library, and asserts against
 * exact argument values. The synchronizer under test is built directly via its `internal`
 * constructor - `Companion.new`/`newLocked` are skipped entirely, so no test here ever touches
 * `SlipstreamNative`.
 *
 * Deliberately NOT covered: `importAccountByUfvk`. Its `restoreAnchor` anchor resolution is a
 * direct call to the `SlipstreamNative` object (a `object`-scoped native binding, not an injectable
 * collaborator), which would require `mockStatic` - out of scope for this plain-Mockito suite.
 *
 * The suite's size tracks the lifecycle surface it pins - construction, the readiness gates, the
 * close/prepare races, and the DbReady read phase belong to one state machine, so the class is
 * suppressed rather than split along an artificial seam.
 */
@Suppress("LargeClass")
class SlipstreamSynchronizerLifecycleTest {
    @Test
    fun close_runs_engine_shutdown_steps_in_order_and_disposes_wallet_client() {
        val engine = mock(SlipstreamEngine::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, walletClient = walletClient, key = key)
        try {
            synchronizer.close()

            val order = inOrder(engine, walletClient)
            runBlocking {
                order.verify(engine, timeout(TIMEOUT_MS)).stopPolling()
                order.verify(engine, timeout(TIMEOUT_MS)).stop()
                order.verify(engine, timeout(TIMEOUT_MS)).free()
                order.verify(engine, timeout(TIMEOUT_MS)).shutdown()
                order.verify(walletClient, timeout(TIMEOUT_MS)).dispose()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun close_is_idempotent() {
        val engine = mock(SlipstreamEngine::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, walletClient = walletClient, key = key)
        try {
            synchronizer.close()
            synchronizer.close()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS).times(1)).shutdown()
                verify(walletClient, timeout(TIMEOUT_MS).times(1)).dispose()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun close_step_failure_does_not_block_remaining_steps() {
        val engine = mock(SlipstreamEngine::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, walletClient = walletClient, key = key)
        try {
            runBlocking { `when`(engine.stop()).thenThrow(RuntimeException("stop failed")) }

            synchronizer.close()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).free()
                verify(engine, timeout(TIMEOUT_MS)).shutdown()
                verify(walletClient, timeout(TIMEOUT_MS)).dispose()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_after_close_is_noop() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.close()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).shutdown() }
            clearInvocations(engine)

            synchronizer.onForeground()

            runBlocking {
                verify(engine, after(SETTLE_MS).never()).start(null, STARTING_BIRTHDAY_VALUE)
                verify(engine, after(SETTLE_MS).never()).startPolling()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_background_after_close_is_noop() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.close()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).shutdown() }
            clearInvocations(engine)

            synchronizer.onBackground()

            runBlocking<Unit> { verify(engine, after(SETTLE_MS).never()).stop() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun enhance_transaction_after_close_is_noop() {
        val engine = mock(SlipstreamEngine::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, walletClient = walletClient, key = key)
        val txId = TransactionId.new(byteArrayOf(1, 2, 3))
        try {
            synchronizer.close()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).shutdown() }
            clearInvocations(walletClient)

            synchronizer.enhanceTransaction(txId)

            runBlocking {
                verify(walletClient, after(SETTLE_MS).never())
                    .fetchTransaction(txId.value.byteArray, ServiceMode.Direct)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_engine_start_throwing_invokes_critical_error_handler_without_crashing() {
        val engine = mock(SlipstreamEngine::class.java)
        val latch = CountDownLatch(1)
        val recordedError = AtomicReference<Throwable?>()
        val handler: (Throwable?) -> Boolean = { t ->
            recordedError.set(t)
            latch.countDown()
            false
        }
        runBlocking {
            `when`(engine.onCriticalErrorHandler).thenReturn(handler)
            `when`(engine.start(null, STARTING_BIRTHDAY_VALUE)).thenThrow(RuntimeException("start failed"))
        }
        val synchronizer = buildSynchronizer(engine = engine)

        synchronizer.onForeground()

        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(recordedError.get() is RuntimeException)
    }

    @Test
    fun on_background_engine_stop_throwing_invokes_critical_error_handler_without_crashing() {
        val engine = mock(SlipstreamEngine::class.java)
        val latch = CountDownLatch(1)
        val recordedError = AtomicReference<Throwable?>()
        val handler: (Throwable?) -> Boolean = { t ->
            recordedError.set(t)
            latch.countDown()
            false
        }
        runBlocking {
            `when`(engine.onCriticalErrorHandler).thenReturn(handler)
            `when`(engine.stop()).thenThrow(RuntimeException("stop failed"))
        }
        val synchronizer = buildSynchronizer(engine = engine)

        synchronizer.onBackground()

        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(recordedError.get() is RuntimeException)
    }

    @Test
    fun enhance_transaction_success_response_decrypts_then_notifies() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val synchronizer = buildSynchronizer(engine = engine, backend = backend, walletClient = walletClient)
        val txId = TransactionId.new(byteArrayOf(4, 5, 6))
        val rawBytes = byteArrayOf(7, 8, 9)
        runBlocking {
            `when`(walletClient.fetchTransaction(txId.value.byteArray, ServiceMode.Direct))
                .thenReturn(Response.Success(RawTransactionUnsafe.Mempool(rawBytes)))
        }

        synchronizer.enhanceTransaction(txId)

        val order = inOrder(backend, engine)
        runBlocking {
            order.verify(backend, timeout(TIMEOUT_MS)).decryptAndStoreTransaction(rawBytes, null)
            order.verify(engine, timeout(TIMEOUT_MS)).notifyTxChange()
        }
    }

    @Test
    fun enhance_transaction_failure_response_sets_status_then_notifies() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val walletClient = mock(CombinedWalletClient::class.java)
        val synchronizer = buildSynchronizer(engine = engine, backend = backend, walletClient = walletClient)
        val txId = TransactionId.new(byteArrayOf(10, 11, 12))
        runBlocking {
            `when`(walletClient.fetchTransaction(txId.value.byteArray, ServiceMode.Direct))
                .thenReturn(Response.Failure.Connection(cause = Exception("no network")))
        }

        synchronizer.enhanceTransaction(txId)

        val order = inOrder(backend, engine)
        runBlocking {
            val verifiedBackend = order.verify(backend, timeout(TIMEOUT_MS))
            verifiedBackend.setTransactionStatus(txId.value.byteArray, TXID_NOT_RECOGNIZED_STATUS)
            order.verify(engine, timeout(TIMEOUT_MS)).notifyTxChange()
        }
    }

    @Test
    fun rewind_to_height_backend_throwing_still_restarts_engine_and_propagates() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val synchronizer = buildSynchronizer(engine = engine, backend = backend)
        val height = BlockHeight.new(3_000_000L)
        runBlocking {
            `when`(backend.rewindToHeight(height.value)).thenThrow(RuntimeException("rewind failed"))
        }

        assertFailsWith<RuntimeException> {
            runBlocking { synchronizer.rewindToHeight(height) }
        }

        runBlocking { verify(engine).start(null, STARTING_BIRTHDAY_VALUE) }
    }

    @Test
    fun delete_account_backend_throwing_still_restarts_engine_but_skips_notify_and_propagates() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val synchronizer = buildSynchronizer(engine = engine, backend = backend)
        val accountUuid = AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES))
        runBlocking {
            `when`(backend.deleteAccount(accountUuid.value)).thenThrow(RuntimeException("delete failed"))
        }

        assertFailsWith<RuntimeException> {
            runBlocking { synchronizer.deleteAccount(accountUuid) }
        }

        runBlocking {
            verify(engine).start(null, STARTING_BIRTHDAY_VALUE)
            verify(engine, never()).notifyTxChange()
        }
    }

    @Test
    fun accounts_flow_wraps_runtime_exception_as_get_accounts_exception() {
        val backend = mock(Backend::class.java)
        val synchronizer = buildSynchronizer(backend = backend)
        runBlocking { `when`(backend.getAccounts()).thenThrow(RuntimeException("boom")) }

        assertFailsWith<InitializeException.GetAccountsException> {
            /*
             * drop(1): the flow now leads with a null "not loaded yet" emission (readiness gate).
             */
            runBlocking { synchronizer.accountsFlow.drop(1).first() }
        }
    }

    @Test
    fun accounts_flow_propagates_cancellation_unwrapped() {
        val backend = mock(Backend::class.java)
        val synchronizer = buildSynchronizer(backend = backend)
        runBlocking { `when`(backend.getAccounts()).thenThrow(CancellationException("cancelled")) }

        assertFailsWith<CancellationException> {
            runBlocking { synchronizer.accountsFlow.drop(1).first() }
        }
    }

    /**
     * MOB-1667: polling is local JNI reads only, so a migration pause must leave it running — the
     * host keeps seeing live balances, heights and status for however long the pause lasts.
     */
    @Test
    fun pause_keeps_polling_and_leaves_the_engine_alone() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            clearInvocations(engine) // drop the init() startPolling
            synchronizer.pause()

            runBlocking {
                verify(engine, after(SETTLE_MS).never()).stopPolling()
                verify(engine, never()).stop()
                verify(engine, never()).free()
                verify(engine, never()).shutdown()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The tick's one network-active consumer is the part a pause does suppress — resubmitting a
     * transaction is exactly the traffic a migration broadcast must stay decorrelated from.
     */
    @Test
    fun pause_suppresses_the_resubmission_tick_and_resume_restores_it() {
        val engine = mock(SlipstreamEngine::class.java)
        val ticker = mock(ResubmissionTicker::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key, resubmissionTicker = ticker)
        try {
            val onTick = captureOnTick(engine)

            runBlocking {
                synchronizer.pause()
                onTick(snapshot(state = 1))
                verify(ticker, never()).onTick(isSynced = true, chainTip = 0L)

                synchronizer.resume()
                onTick(snapshot(state = 1))
                verify(ticker).onTick(isSynced = true, chainTip = 0L)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun resume_restarts_polling() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.resume()

            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * A pause that spans a background/foreground cycle leaves the engine stopped by
     * [SlipstreamSynchronizer.onBackground]; resuming must restart it rather than poll a dead
     * session forever.
     */
    @Test
    fun resume_restarts_a_stopped_engine_when_foregrounded() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            clearInvocations(engine) // drop the init() startPolling
            synchronizer.onForeground()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
            synchronizer.pause()
            `when`(engine.isRunning).thenReturn(false)
            clearInvocations(engine)

            synchronizer.resume()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).start(null, STARTING_BIRTHDAY_VALUE)
                verify(engine, timeout(TIMEOUT_MS)).startPolling()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /** Backgrounded, the engine stays stopped — the next foreground event is what restarts it. */
    @Test
    fun resume_while_backgrounded_leaves_a_stopped_engine_stopped() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(false)
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.resume()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).startPolling()
                verify(engine, never()).start(null, STARTING_BIRTHDAY_VALUE)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * MOB-1667: the forced `paused -> SYNCED` mask existed only because a pause froze the poll
     * loop. Polling now continues, so the engine's own status is reported throughout — a wallet
     * that is genuinely syncing must not claim to be synced.
     */
    @Test
    fun status_is_not_masked_to_synced_while_paused() {
        val engine = mock(SlipstreamEngine::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.SYNCING)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key, engineStatusOverride = engineStatus)
        try {
            runBlocking {
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
                synchronizer.pause()
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
                engineStatus.value = Synchronizer.Status.SYNCED
                assertEquals(Synchronizer.Status.SYNCED, synchronizer.status.first())
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_while_paused_still_polls() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.onForeground()

            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    // ── syncBurst: bounded background sync advance ──────────────────────────────

    @Test
    fun sync_burst_refuses_while_paused_and_returns_privacy_blocked() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.pause()
            clearInvocations(engine)

            val result = runBlocking { synchronizer.syncBurst(timeout = ONE_SECOND) { false } }

            assertEquals(Synchronizer.SyncBurstResult.PRIVACY_BLOCKED, result)
            runBlocking {
                verify(engine, after(SETTLE_MS).never()).start(null, STARTING_BIRTHDAY_VALUE)
                verify(engine, never()).startPolling()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * MOB-1667 follow-through: a migration pause landing mid-burst must not leave the poll loop
     * stopped when the burst ends — [SlipstreamSynchronizer.pause] no longer suppresses polling,
     * and `restoreAfterBurst` mirrors that. Expects two `startPolling` calls: the burst's own at
     * start, and the foreground restore at the end.
     */
    @Test
    fun sync_burst_ending_under_a_pause_keeps_polling_in_the_foreground() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            clearInvocations(engine)
            synchronizer.onForeground()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
            clearInvocations(engine)

            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = TWO_SECONDS, targetCheckInterval = TICK) {
                        synchronizer.pause()
                        true
                    }
                }

            assertEquals(Synchronizer.SyncBurstResult.TARGET_REACHED, result)
            runBlocking<Unit> {
                verify(engine, timeout(TIMEOUT_MS).times(2)).startPolling()
                verify(engine, never()).stopPolling()
                verify(engine, never()).stop()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_after_close_returns_unavailable() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.close()
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).shutdown() }
            clearInvocations(engine)

            val result = runBlocking { synchronizer.syncBurst(timeout = ONE_SECOND) { false } }

            assertEquals(Synchronizer.SyncBurstResult.UNAVAILABLE, result)
            runBlocking { verify(engine, after(SETTLE_MS).never()).start(null, STARTING_BIRTHDAY_VALUE) }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_force_starts_stopped_engine_then_returns_target_reached() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(false)
            clearInvocations(engine) // drop the init() startPolling

            var polls = 0
            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = TWO_SECONDS, targetCheckInterval = TICK) { polls++ >= 1 }
                }

            assertEquals(Synchronizer.SyncBurstResult.TARGET_REACHED, result)
            runBlocking<Unit> {
                // Force-started the stopped engine and drove its poll loop...
                verify(engine).start(null, STARTING_BIRTHDAY_VALUE)
                verify(engine).startPolling()
                // ...then restored the backgrounded (inForeground=false) state by stopping it again.
                verify(engine).stop()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_returns_synced_to_tip_on_a_fresh_done_snapshot() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        // Engine already running (foreground/live) → current snapshots are trusted, not stale.
        val snapshots = MutableStateFlow<SlipstreamSnapshot?>(snapshot(state = 3, tipFresh = true))
        val synchronizer = buildSynchronizer(engine = engine, key = key, lastSnapshotOverride = snapshots)
        try {
            `when`(engine.isRunning).thenReturn(true)

            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = TWO_SECONDS, targetCheckInterval = TICK) { false }
                }

            assertEquals(Synchronizer.SyncBurstResult.SYNCED_TO_TIP, result)
            runBlocking { verify(engine, never()).start(null, STARTING_BIRTHDAY_VALUE) }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_returns_disconnected_on_a_fresh_error_snapshot() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val snapshots = MutableStateFlow<SlipstreamSnapshot?>(snapshot(state = 2))
        val synchronizer = buildSynchronizer(engine = engine, key = key, lastSnapshotOverride = snapshots)
        try {
            `when`(engine.isRunning).thenReturn(true)

            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = TWO_SECONDS, targetCheckInterval = TICK) { false }
                }

            assertEquals(Synchronizer.SyncBurstResult.DISCONNECTED, result)
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_does_not_terminate_on_the_stale_pre_start_snapshot() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        // A leftover "done" snapshot from before the engine was stopped in the background. Because
        // the engine was NOT running at burst start, this is treated as stale and ignored — proving
        // the burst waits for a snapshot produced AFTER it restarted the engine, not the frozen one.
        val stale = snapshot(state = 3, tipFresh = true)
        val snapshots = MutableStateFlow<SlipstreamSnapshot?>(stale)
        val synchronizer = buildSynchronizer(engine = engine, key = key, lastSnapshotOverride = snapshots)
        try {
            `when`(engine.isRunning).thenReturn(false)

            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = HALF_SECOND, targetCheckInterval = TICK) { false }
                }

            assertEquals(Synchronizer.SyncBurstResult.TIMEOUT, result)
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_times_out_when_neither_target_nor_terminal_is_reached() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)

            val result =
                runBlocking {
                    synchronizer.syncBurst(timeout = HALF_SECOND, targetCheckInterval = TICK) { false }
                }

            assertEquals(Synchronizer.SyncBurstResult.TIMEOUT, result)
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_background_during_a_burst_is_deferred_until_the_burst_restore() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        val burstStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        try {
            `when`(engine.isRunning).thenReturn(true)

            runBlocking {
                val job =
                    launch(kotlinx.coroutines.Dispatchers.Default) {
                        synchronizer.syncBurst(timeout = TWO_SECONDS, targetCheckInterval = TICK) {
                            if (calls.getAndIncrement() == 0) {
                                burstStarted.countDown()
                                proceed.await() // hold the burst active while the test checks the deferral
                                false
                            } else {
                                true // release → target reached → burst ends and runs its restore
                            }
                        }
                    }

                burstStarted.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Backgrounded mid-burst: the burst owns the engine, so onBackground must NOT stop it now.
                synchronizer.onBackground()
                verify(engine, after(SETTLE_MS).never()).stop()

                proceed.countDown()
                job.join()
            }

            // Once the burst finished, its restore applied the deferred backgrounded state exactly once.
            runBlocking<Unit> { verify(engine, timeout(TIMEOUT_MS)).stop() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /*
     * ── deferred preparation: the iOS-style constructor/prepare split ──────────
     */

    @Test
    fun construction_is_cheap_and_reports_initializing_while_preparing() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                engineStatusOverride = engineStatus,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            runBlocking {
                assertEquals(Synchronizer.Status.INITIALIZING, synchronizer.status.first())
                verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)
                verify(engine, never()).start(UFVK, ANCHOR_HEIGHT)
                verify(engine, never()).startPolling()
            }
        } finally {
            anchorGate.complete(Unit)
            InstanceGuard.release(key)
        }
    }

    /**
     * The probe for "awaits the gate" is a WRITE ([SlipstreamSynchronizer.rewindToHeight]) rather
     * than a read: reads are served from `DbReady`, which the tail now publishes before it ever
     * touches the anchor - that early completion is asserted here too, and is the whole point of
     * the phase.
     */
    @Test
    fun gated_call_awaits_preparation_then_succeeds_after_the_tail_ran_in_order() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val setup = AccountCreateSetup(ACCOUNT_NAME, KEY_SOURCE, FirstClassByteArray(ByteArray(SEED_BYTES)))
        stubPrepareBackend(backend, setup.seed.byteArray)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(setup = setup, anchorSource = gatedAnchor(anchorGate))
            )
        try {
            val accounts =
                runBlocking {
                    val early = withTimeout(TIMEOUT_MS) { synchronizer.getAccounts() }
                    val pending =
                        async(Dispatchers.Default) {
                            synchronizer.rewindToHeight(BlockHeight.new(BELOW_BIRTHDAY_VALUE))
                        }
                    verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)
                    assertTrue(pending.isActive)
                    anchorGate.complete(Unit)
                    withTimeout(TIMEOUT_MS) { pending.await() }
                    early
                }

            assertEquals(emptyList(), accounts)
            val order = inOrder(backend, engine)
            runBlocking {
                order.verify(backend, timeout(TIMEOUT_MS)).initDataDb(setup.seed.byteArray)
                order.verify(backend, timeout(TIMEOUT_MS)).createAccount(
                    ACCOUNT_NAME,
                    KEY_SOURCE,
                    setup.seed.byteArray,
                    TREE_STATE,
                    ANCHOR_HEIGHT
                )
                order.verify(engine, timeout(TIMEOUT_MS)).open(TOTAL_MEMORY_BYTES)
                order.verify(engine, timeout(TIMEOUT_MS)).start(UFVK, ANCHOR_HEIGHT)
                order.verify(engine, timeout(TIMEOUT_MS)).startPolling()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun failed_preparation_disconnects_latches_the_setup_error_and_keeps_the_guard() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val failure = RuntimeException("anchor unreachable")
        val earlyHandlerCalls = AtomicInteger(0)
        val recorded = AtomicReference<Throwable?>()
        /*
         * Acquired as Companion.new would, so the "a failed preparation keeps the key" claim is real.
         */
        runBlocking { InstanceGuard.acquire(key) }
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                engineStatusOverride = engineStatus,
                prepareInputs =
                    prepareInputs(
                        anchorSource =
                            SlipstreamAnchorSource { _, _, _ ->
                                anchorGate.await()
                                throw failure
                            }
                    )
            )
        val earlyHandlerFired = CountDownLatch(1)
        try {
            synchronizer.onSetupErrorHandler = { t ->
                recorded.set(t)
                earlyHandlerCalls.incrementAndGet()
                earlyHandlerFired.countDown()
                false
            }
            anchorGate.complete(Unit)

            assertTrue(earlyHandlerFired.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(TIMEOUT_MS) { synchronizer.status.first { it == Synchronizer.Status.DISCONNECTED } }
            }
            assertSameFailure(failure, recorded.get())
            assertEquals(1, earlyHandlerCalls.get())

            val thrown = assertFailsWith<SlipstreamNotReadyException> { runBlocking { synchronizer.getAccounts() } }
            assertSameFailure(failure, thrown.cause)

            val lateHandlerCalls = AtomicInteger(0)
            synchronizer.onSetupErrorHandler = {
                lateHandlerCalls.incrementAndGet()
                false
            }
            assertEquals(1, lateHandlerCalls.get())

            synchronizer.onSetupErrorHandler = null
            assertEquals(1, lateHandlerCalls.get())
            assertTrue(InstanceGuard.isActive(key))
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun close_during_preparation_cancels_it_without_opening_the_engine_or_erroring() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)
        val key = newKey()
        val handlerCalls = AtomicInteger(0)
        runBlocking { InstanceGuard.acquire(key) }
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                engineStatusOverride = engineStatus,
                prepareInputs = prepareInputs(anchorSource = SlipstreamAnchorSource { _, _, _ -> awaitCancellation() })
            )
        try {
            synchronizer.onSetupErrorHandler = {
                handlerCalls.incrementAndGet()
                false
            }

            synchronizer.close()

            val thrown = assertFailsWith<SlipstreamNotReadyException> { runBlocking { synchronizer.getAccounts() } }
            assertEquals(null, thrown.cause)
            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).shutdown()
                verify(engine, never()).open(TOTAL_MEMORY_BYTES)
            }
            assertEquals(0, handlerCalls.get())
            assertEquals(Synchronizer.Status.INITIALIZING, engineStatus.value)
            /*
             * The guard was released by the completed shutdown job, so a fresh acquire succeeds.
             */
            runBlocking { InstanceGuard.acquire(key) }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun sync_burst_while_preparing_returns_unavailable_without_suspending() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            val result = runBlocking { synchronizer.syncBurst(timeout = ONE_SECOND) { false } }

            assertEquals(Synchronizer.SyncBurstResult.UNAVAILABLE, result)
            runBlocking {
                verify(engine, after(SETTLE_MS).never()).start(null, ANCHOR_HEIGHT)
                verify(engine, never()).startPolling()
            }
        } finally {
            anchorGate.complete(Unit)
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_during_preparation_is_queued_behind_the_gate() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            `when`(engine.isRunning).thenReturn(true)

            synchronizer.onForeground()
            runBlocking { verify(engine, after(SETTLE_MS).never()).start(UFVK, ANCHOR_HEIGHT) }

            anchorGate.complete(Unit)

            runBlocking {
                val order = inOrder(engine)
                order.verify(engine, timeout(TIMEOUT_MS)).start(UFVK, ANCHOR_HEIGHT)
                verify(engine, timeout(TIMEOUT_MS).times(2)).startPolling()
                /*
                 * The queued block found a running engine, so it never restarted it keylessly.
                 */
                verify(engine, never()).start(null, ANCHOR_HEIGHT)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun cold_flows_emit_from_db_ready_before_the_anchor_resolves() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            runBlocking {
                assertEquals(null, synchronizer.accountsFlow.first())
                /*
                 * Both cold flows are served from the migrated DB while the anchor is still held.
                 */
                assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { synchronizer.accountsFlow.drop(1).first() })
                assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { synchronizer.allTransactions.first() })
                verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)

                anchorGate.complete(Unit)

                verify(engine, timeout(TIMEOUT_MS)).start(UFVK, ANCHOR_HEIGHT)
                assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { synchronizer.accountsFlow.drop(1).first() })
                assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { synchronizer.allTransactions.first() })
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The regression harness for a preparation that fails AFTER `DbReady` was published: both cold
     * flows are already subscribed and serving DB rows when the anchor throws, so they must
     * COMPLETE rather than kill the host's eager collectors or strand them forever - while the
     * relaxed suspend reads start throwing, `Failed` being terminal for them too.
     */
    @Test
    fun cold_flows_complete_instead_of_failing_when_preparation_fails() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val emitted = CountDownLatch(2)
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                engineStatusOverride = engineStatus,
                prepareInputs =
                    prepareInputs(
                        anchorSource =
                            SlipstreamAnchorSource { _, _, _ ->
                                anchorGate.await()
                                error("anchor unreachable")
                            }
                    )
            )
        try {
            runBlocking {
                val accounts =
                    async(Dispatchers.Default) {
                        synchronizer.accountsFlow.onEach { if (it != null) emitted.countDown() }.toList()
                    }
                val transactions =
                    async(Dispatchers.Default) {
                        synchronizer.allTransactions.onEach { emitted.countDown() }.toList()
                    }
                assertTrue(emitted.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

                anchorGate.complete(Unit)

                val accountEmissions = withTimeout(TIMEOUT_MS) { accounts.await() }
                assertEquals(2, accountEmissions.size)
                assertEquals(null, accountEmissions[0])
                assertEquals(emptyList(), accountEmissions[1])

                val transactionEmissions = withTimeout(TIMEOUT_MS) { transactions.await() }
                assertEquals(1, transactionEmissions.size)
                assertEquals(emptyList(), transactionEmissions[0])

                assertFailsWith<SlipstreamNotReadyException> { synchronizer.getAccounts() }
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun status_is_not_masked_to_synced_when_a_pause_lands_on_a_failed_preparation() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.INITIALIZING)
        val key = newKey()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                engineStatusOverride = engineStatus,
                prepareInputs =
                    prepareInputs(
                        anchorSource = SlipstreamAnchorSource { _, _, _ -> error("anchor unreachable") }
                    )
            )
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) { synchronizer.status.first { it == Synchronizer.Status.DISCONNECTED } }
                synchronizer.pause()
                assertEquals(Synchronizer.Status.DISCONNECTED, synchronizer.status.first())
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun a_preparation_finishing_around_close_never_overwrites_the_closed_state() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorEntered = CountDownLatch(1)
        val anchorGate = CompletableDeferred<Unit>()
        val handlerCalls = AtomicInteger(0)
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs =
                    prepareInputs(
                        anchorSource =
                            SlipstreamAnchorSource { _, _, _ ->
                                anchorEntered.countDown()
                                anchorGate.await()
                                SlipstreamRestoreAnchor(ANCHOR_HEIGHT, null)
                            }
                    )
            )
        try {
            synchronizer.onSetupErrorHandler = {
                handlerCalls.incrementAndGet()
                false
            }
            assertTrue(anchorEntered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            synchronizer.close()
            anchorGate.complete(Unit)

            val thrown = assertFailsWith<SlipstreamNotReadyException> { runBlocking { synchronizer.getAccounts() } }
            assertEquals(null, thrown.cause)
            runBlocking { verify(engine, timeout(TIMEOUT_MS)).shutdown() }
            assertEquals(0, handlerCalls.get())
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun rewind_below_the_resolved_birthday_is_coerced_up_to_it() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, backend = backend, key = key)
        try {
            runBlocking {
                `when`(backend.rewindToHeight(STARTING_BIRTHDAY_VALUE))
                    .thenReturn(JniRewindResult.Success(STARTING_BIRTHDAY_VALUE))
            }

            val rewound = runBlocking { synchronizer.rewindToNearestHeight(BlockHeight.new(BELOW_BIRTHDAY_VALUE)) }

            assertEquals(BlockHeight.new(STARTING_BIRTHDAY_VALUE), rewound)
            runBlocking {
                verify(backend).rewindToHeight(STARTING_BIRTHDAY_VALUE)
                verify(backend, never()).rewindToHeight(BELOW_BIRTHDAY_VALUE)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * MOB-1667: a pause that lands mid-preparation must not cost the host its observation of the
     * engine — the tail starts polling like any other preparation, and only the network-active tick
     * consumer stays suppressed.
     */
    @Test
    fun preparation_finishing_under_a_migration_pause_still_starts_polling() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            synchronizer.pause()
            anchorGate.complete(Unit)

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).start(UFVK, ANCHOR_HEIGHT)
                verify(engine, timeout(TIMEOUT_MS)).startPolling()
                verify(engine, after(SETTLE_MS).never()).stopPolling()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /*
     * ── DbReady: DB-backed reads served before the tail's network anchor ───────
     */

    @Test
    fun read_only_surfaces_are_served_at_db_ready_while_the_anchor_is_still_pending() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val transactionsController = mock(TransactionsController::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val accountUuid = AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES))
        stubPrepareBackend(backend, null)
        runBlocking {
            `when`(backend.getCurrentAddress(accountUuid.value)).thenReturn(UNIFIED_ADDRESS)
            `when`(transactionsController.forAccount(accountUuid)).thenReturn(flowOf(emptyList()))
        }
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                transactionsController = transactionsController,
                key = key,
                prepareInputs = prepareInputs(anchorSource = gatedAnchor(anchorGate))
            )
        try {
            runBlocking {
                assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { synchronizer.getAccounts() })
                assertEquals(
                    emptyList(),
                    withTimeout(TIMEOUT_MS) { synchronizer.getTransactions(accountUuid).first() }
                )
                assertEquals(
                    UNIFIED_ADDRESS,
                    withTimeout(TIMEOUT_MS) { synchronizer.getUnifiedAddress(Account.new(accountUuid)) }
                )

                /*
                 * A write stays gated on full readiness, so it is still suspended on the held anchor.
                 */
                val pending =
                    async(Dispatchers.Default) {
                        synchronizer.rewindToHeight(BlockHeight.new(BELOW_BIRTHDAY_VALUE))
                    }
                verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)
                assertTrue(pending.isActive)

                anchorGate.complete(Unit)
                withTimeout(TIMEOUT_MS) { pending.await() }
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The restore shape: the account row is written only once the anchor resolves, so the whole
     * `DbReady` window reads an empty table. That read is suppressed rather than served - the host
     * reads an empty list as "loaded, no accounts" - leaving the collector on the leading `null`
     * until the tail's `createAccount` makes the rows real.
     */
    @Test
    fun accounts_flow_refreshes_once_the_tail_creates_the_account_row() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val created = AtomicBoolean(false)
        val setup = AccountCreateSetup(ACCOUNT_NAME, KEY_SOURCE, FirstClassByteArray(ByteArray(SEED_BYTES)))
        val accountUuid = AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES))
        val jniAccount = jniAccount(accountUuid)
        runBlocking {
            `when`(backend.initDataDb(setup.seed.byteArray)).thenReturn(0)
            `when`(backend.getAccounts()).thenAnswer { if (created.get()) listOf(jniAccount) else emptyList() }
            `when`(
                backend.createAccount(
                    ACCOUNT_NAME,
                    KEY_SOURCE,
                    setup.seed.byteArray,
                    TREE_STATE,
                    ANCHOR_HEIGHT
                )
            ).thenAnswer {
                created.set(true)
                JniAccountUsk(accountUuid.value, ByteArray(USK_BYTES))
            }
        }
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(setup = setup, anchorSource = gatedAnchor(anchorGate))
            )
        try {
            runBlocking {
                val emissions = Channel<List<Account>?>(Channel.UNLIMITED)
                val collector =
                    launch(Dispatchers.Default) {
                        synchronizer.accountsFlow.collect { emissions.send(it) }
                    }
                try {
                    assertEquals(null, withTimeout(TIMEOUT_MS) { emissions.receive() })
                    /*
                     * The leading null is already consumed, so any element here would be the empty read.
                     */
                    assertEquals(null, withTimeoutOrNull(SETTLE_MS) { emissions.receive() })

                    anchorGate.complete(Unit)

                    val rows = withTimeout(TIMEOUT_MS) { emissions.receive() }
                    assertEquals(accountUuid, rows?.single()?.accountUuid)
                } finally {
                    collector.cancel()
                }
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The host distinguishes "still loading" (`null`) from "loaded, and there are no accounts" (an
     * empty list), so the restore window must never serve the latter: until the tail's
     * `createAccount` runs, the collector stays on the leading `null` and the account UI keeps
     * rendering as loading rather than as absent.
     */
    @Test
    fun accounts_flow_stays_loading_instead_of_emitting_empty_while_creation_is_pending() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val created = AtomicBoolean(false)
        val setup = AccountCreateSetup(ACCOUNT_NAME, KEY_SOURCE, FirstClassByteArray(ByteArray(SEED_BYTES)))
        val accountUuid = AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES))
        val jniAccount = jniAccount(accountUuid)
        runBlocking {
            `when`(backend.initDataDb(setup.seed.byteArray)).thenReturn(0)
            `when`(backend.getAccounts()).thenAnswer { if (created.get()) listOf(jniAccount) else emptyList() }
            `when`(
                backend.createAccount(
                    ACCOUNT_NAME,
                    KEY_SOURCE,
                    setup.seed.byteArray,
                    TREE_STATE,
                    ANCHOR_HEIGHT
                )
            ).thenAnswer {
                created.set(true)
                JniAccountUsk(accountUuid.value, ByteArray(USK_BYTES))
            }
        }
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs = prepareInputs(setup = setup, anchorSource = gatedAnchor(anchorGate))
            )
        try {
            runBlocking {
                val emissions = async(Dispatchers.Default) { synchronizer.accountsFlow.take(2).toList() }
                /*
                 * Settles past DbReady with the anchor still held: a second emission would be the empty read.
                 */
                verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)
                assertTrue(emissions.isActive)

                anchorGate.complete(Unit)

                val rows = withTimeout(TIMEOUT_MS) { emissions.await() }
                assertEquals(2, rows.size)
                assertEquals(null, rows[0])
                assertEquals(accountUuid, rows[1]?.single()?.accountUuid)
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The `DbReady` balance seed: what the wallet database already knows surfaces in the same phase
     * the account row does, so an existing wallet never renders a hard zero while the tail is still
     * blocked on its anchor.
     */
    @Test
    fun balances_are_seeded_from_the_db_at_db_ready_before_the_anchor_resolves() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val anchorGate = CompletableDeferred<Unit>()
        val balances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
        val summary = dbSummary(AccountUuid.new(ByteArray(ACCOUNT_UUID_BYTES)))
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                walletBalancesOverride = balances,
                prepareInputs =
                    prepareInputs(
                        anchorSource = gatedAnchor(anchorGate),
                        dbWalletSummary = { summary }
                    )
            )
        try {
            runBlocking {
                assertEquals(
                    summary.accountBalances,
                    withTimeout(TIMEOUT_MS) { synchronizer.walletBalances.first { it != null } }
                )
                /*
                 * Still short of the anchor, so the seed is genuinely a DbReady-phase publish.
                 */
                verify(engine, after(SETTLE_MS).never()).open(TOTAL_MEMORY_BYTES)
            }
        } finally {
            anchorGate.complete(Unit)
            InstanceGuard.release(key)
        }
    }

    /**
     * The seed is an optimization, never a gate: a never-scanned database throws out of the summary
     * read ("Target height not available"), and the tail must carry on to `Ready` with the balances
     * left null - the shimmer, which is the correct render for a wallet with nothing scanned yet.
     */
    @Test
    fun a_failing_db_balance_seed_is_swallowed_and_leaves_preparation_intact() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val balances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
        val handlerCalls = AtomicInteger(0)
        stubPrepareBackend(backend, null)
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                walletBalancesOverride = balances,
                prepareInputs = prepareInputs(dbWalletSummary = { error(SEED_FAILURE_MESSAGE) })
            )
        try {
            synchronizer.onSetupErrorHandler = {
                handlerCalls.incrementAndGet()
                false
            }
            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).startPolling()
                assertEquals(null, balances.value)
            }
            assertEquals(0, handlerCalls.get())
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * The anchor is a blocking, engine-bounded network call, so a preparation that has already lost
     * its `DbReady` compare-and-set to [SlipstreamSynchronizer.close] must never start one - the
     * shutdown's own `cancelAndJoin` would have to wait it out for a result nothing will read.
     */
    @Test
    fun close_during_the_data_db_step_bails_out_before_the_anchor_call() {
        val engine = mock(SlipstreamEngine::class.java)
        val backend = mock(Backend::class.java)
        val key = newKey()
        val initEntered = CountDownLatch(1)
        val initProceed = CountDownLatch(1)
        val anchorCalls = AtomicInteger(0)
        runBlocking {
            `when`(backend.getAccounts()).thenReturn(emptyList())
            `when`(backend.initDataDb(null)).thenAnswer {
                initEntered.countDown()
                initProceed.await()
                0
            }
        }
        val synchronizer =
            buildSynchronizer(
                engine = engine,
                backend = backend,
                key = key,
                prepareInputs =
                    prepareInputs(
                        anchorSource =
                            SlipstreamAnchorSource { _, _, _ ->
                                anchorCalls.incrementAndGet()
                                SlipstreamRestoreAnchor(ANCHOR_HEIGHT, null)
                            }
                    )
            )
        try {
            assertTrue(initEntered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            synchronizer.close()
            initProceed.countDown()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).shutdown()
                verify(engine, never()).open(TOTAL_MEMORY_BYTES)
            }
            assertEquals(0, anchorCalls.get())
        } finally {
            InstanceGuard.release(key)
        }
    }

    /**
     * kotlinx's stack-trace recovery hands the preparation job a COPY of any exception that crossed
     * the tail's `withContext(Dispatchers.IO)` boundary, so identity comparison is not available -
     * assert on the type and message instead.
     */
    private fun assertSameFailure(
        expected: Throwable,
        actual: Throwable?
    ) {
        assertEquals(expected::class, actual?.let { it::class })
        assertEquals(expected.message, actual?.message)
    }

    private fun gatedAnchor(gate: CompletableDeferred<Unit>) =
        SlipstreamAnchorSource { _, _, _ ->
            gate.await()
            SlipstreamRestoreAnchor(ANCHOR_HEIGHT, null)
        }

    /**
     * Mockito answers `null` for unstubbed suspend calls (their erased return type is `Object`),
     * which the preparation tail would unbox into a crash - stub the two reads it performs.
     */
    private fun stubPrepareBackend(
        backend: Backend,
        seed: ByteArray?
    ) {
        runBlocking {
            `when`(backend.initDataDb(seed)).thenReturn(0)
            `when`(backend.getAccounts()).thenReturn(emptyList())
        }
    }

    /**
     * The wallet database's own summary, shaped as `TypesafeBackendImpl.getWalletSummary` returns
     * it. Only [WalletSummary.accountBalances] feeds the `DbReady` seed; the heights and progress
     * fields are plausible filler, deliberately not seeded anywhere.
     */
    private fun dbSummary(accountUuid: AccountUuid) =
        WalletSummary(
            accountBalances = mapOf(accountUuid to SEEDED_ACCOUNT_BALANCE),
            chainTipHeight = BlockHeight.new(ANCHOR_HEIGHT),
            fullyScannedHeight = BlockHeight.new(ANCHOR_HEIGHT),
            scanProgress = ScanProgress(1, 1),
            recoveryProgress = RecoveryProgress(1, 1),
            nextSaplingSubtreeIndex = 0u,
            nextOrchardSubtreeIndex = 0u,
            nextIronwoodSubtreeIndex = 0u
        )

    private fun newKey() = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")

    private fun jniAccount(accountUuid: AccountUuid): JniAccount =
        JniAccount(
            accountName = ACCOUNT_NAME,
            accountUuid = accountUuid.value,
            hdAccountIndex = NULL_HD_ACCOUNT_INDEX,
            keySource = KEY_SOURCE,
            seedFingerprint = null,
            ufvk = UFVK,
            uivk = null
        )

    /**
     * The `onTick` callback the synchronizer's `init` installs on the engine. A mock records the
     * setter call but its getter answers `null`, so the lambda has to be captured from the call
     * itself rather than read back.
     */
    @Suppress("UNCHECKED_CAST")
    private fun captureOnTick(engine: SlipstreamEngine): suspend (SlipstreamSnapshot) -> Unit {
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(engine).onTick = captor.capture() as? (suspend (SlipstreamSnapshot) -> Unit)
        return captor.value as suspend (SlipstreamSnapshot) -> Unit
    }

    @Suppress("LongParameterList")
    private fun snapshot(
        state: Int,
        tipFresh: Boolean = false,
        chainTip: Long = 0L
    ) = SlipstreamSnapshot(
        chainTip = chainTip,
        fetchedBlocks = 0,
        scannedBlocks = 0,
        enhancedTxs = 0,
        currentRangeEnd = 0,
        state = state,
        passTotalBlocks = 0,
        spendableHint = false,
        rangesCompleted = 0,
        isRecovering = false,
        progressPermille = 0,
        stalledSeconds = 0,
        tipFresh = tipFresh,
        txSetVersion = 0
    )

    /**
     * Builds a [SlipstreamSynchronizer] with every collaborator mocked out, bypassing
     * [SlipstreamSynchronizer.Companion.new]/`newLocked` (and therefore `SlipstreamNative`)
     * entirely. [engine]'s flow-typed properties are stubbed with real [MutableStateFlow]s
     * because they are read once, eagerly, as field initializers when the constructor runs.
     */
    @Suppress("LongParameterList")
    private fun buildSynchronizer(
        engine: SlipstreamEngine = mock(SlipstreamEngine::class.java),
        backend: Backend = mock(Backend::class.java),
        walletClient: CombinedWalletClient = mock(CombinedWalletClient::class.java),
        transactionsController: TransactionsController = mock(TransactionsController::class.java),
        key: SlipstreamKey = newKey(),
        resubmissionTicker: ResubmissionTicker = mock(ResubmissionTicker::class.java),
        startBirthday: BlockHeight = BlockHeight.new(STARTING_BIRTHDAY_VALUE),
        engineStatusOverride: MutableStateFlow<Synchronizer.Status>? = null,
        lastSnapshotOverride: MutableStateFlow<SlipstreamSnapshot?>? = null,
        walletBalancesOverride: MutableStateFlow<Map<AccountUuid, AccountBalance>?>? = null,
        prepareInputs: PrepareInputs? = null
    ): SlipstreamSynchronizer {
        `when`(engine.status).thenReturn(engineStatusOverride ?: MutableStateFlow(Synchronizer.Status.SYNCED))
        `when`(engine.progress).thenReturn(MutableStateFlow(PercentDecimal.ZERO_PERCENT))
        `when`(engine.areFundsSpendable).thenReturn(MutableStateFlow(false))
        `when`(engine.networkHeight).thenReturn(MutableStateFlow<BlockHeight?>(null))
        `when`(engine.fullyScannedHeight).thenReturn(MutableStateFlow<BlockHeight?>(null))
        `when`(engine.walletBalances)
            .thenReturn(walletBalancesOverride ?: MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null))
        `when`(engine.lastSnapshot).thenReturn(lastSnapshotOverride ?: MutableStateFlow<SlipstreamSnapshot?>(null))
        `when`(transactionsController.allTransactions).thenReturn(flowOf(emptyList()))

        return SlipstreamSynchronizer(
            context = mock(Context::class.java),
            network = key.network,
            alias = key.alias,
            key = key,
            engine = engine,
            backend = backend,
            walletClient = walletClient,
            walletClientFactory = mock(WalletClientFactory::class.java),
            defaultEndpoint = LightWalletEndpoint(host = "testnet.lightwalletd.com", port = 9067, isSecure = true),
            engineTorDir = null,
            lazyTorClient = null,
            exchangeRateFetcher = null,
            sdkFlags = SdkFlags(isTorEnabled = false, isExchangeRateEnabled = false),
            fastestServerFetcher = mock(FastestServerFetcher::class.java),
            transactionReader = mock(SlipstreamTransactionReader::class.java),
            transactionsController = transactionsController,
            spendService = mock(SlipstreamSpendService::class.java),
            broadcasterImpl = mock(SlipstreamBroadcaster::class.java),
            resubmissionTicker = resubmissionTicker,
            startBirthday = startBirthday,
            prepareInputs = prepareInputs
        )
    }

    /**
     * A [PrepareInputs] whose every JNI/assets seam is a stub, so the deferred-preparation tail can
     * be driven from a test without `SlipstreamNative` or `CheckpointTool`. Defaults describe a
     * [WalletInitMode.RestoreWallet] provisioning (intent 1 - the only intent that resolves an
     * anchor from a birthday), with no [AccountCreateSetup] so no account row is written.
     */
    @Suppress("LongParameterList")
    private fun prepareInputs(
        walletInitMode: WalletInitMode = WalletInitMode.RestoreWallet,
        requestedBirthday: BlockHeight? = BlockHeight.new(REQUESTED_BIRTHDAY_VALUE),
        setup: AccountCreateSetup? = null,
        ufvk: String? = UFVK,
        anchorSource: SlipstreamAnchorSource =
            SlipstreamAnchorSource { _, _, _ -> SlipstreamRestoreAnchor(ANCHOR_HEIGHT, null) },
        fallbackCheckpointHeight: suspend () -> Long = { FALLBACK_CHECKPOINT },
        treeState: suspend (BlockHeight?) -> TreeState = { TreeState(TREE_STATE) },
        lastCheckpointTreeState: suspend () -> TreeState = { TreeState(TREE_STATE) },
        dbWalletSummary: suspend () -> WalletSummary? = { null },
        totalMemoryBytes: Long = TOTAL_MEMORY_BYTES
    ) = PrepareInputs(
        walletInitMode = walletInitMode,
        requestedBirthday = requestedBirthday,
        setup = setup,
        ufvk = ufvk,
        anchorSource = anchorSource,
        fallbackCheckpointHeight = fallbackCheckpointHeight,
        treeState = treeState,
        lastCheckpointTreeState = lastCheckpointTreeState,
        dbWalletSummary = dbWalletSummary,
        totalMemoryBytes = totalMemoryBytes
    )

    companion object {
        private const val TIMEOUT_MS = 2_000L
        private const val SETTLE_MS = 300L
        private const val LATCH_TIMEOUT_SECONDS = 2L
        private const val STARTING_BIRTHDAY_VALUE = 2_000_000L
        private const val ACCOUNT_UUID_BYTES = 16

        /** Deferred-preparation fixtures: the anchor resolves ABOVE the requested birthday. */
        private const val REQUESTED_BIRTHDAY_VALUE = 2_100_000L
        private const val ANCHOR_HEIGHT = 2_200_000L
        private const val FALLBACK_CHECKPOINT = 1_900_000L
        private const val TOTAL_MEMORY_BYTES = 4_000_000_000L
        private const val UFVK = "uview1testkey"
        private const val ACCOUNT_NAME = "test account"
        private const val KEY_SOURCE = "test key source"
        private const val SEED_BYTES = 32
        private const val BELOW_BIRTHDAY_VALUE = 1_000_000L
        private const val UNIFIED_ADDRESS = "utest1address"

        /** [JniAccount] represents a NULL ZIP 32 account index across JNI as `-1`. */
        private const val NULL_HD_ACCOUNT_INDEX = -1L
        private const val USK_BYTES = 8
        private val TREE_STATE = byteArrayOf(1, 2, 3, 4)

        /** What a never-scanned database answers the summary read with, per the Rust backend. */
        private const val SEED_FAILURE_MESSAGE = "Target height not available"

        /** Non-zero throughout, so a hard-zero balance can never be mistaken for the seed. */
        private val SEEDED_POOL_BALANCE =
            WalletBalance(
                available = Zatoshi(100_000L),
                changePending = Zatoshi(2_000L),
                valuePending = Zatoshi(300L)
            )
        private val SEEDED_ACCOUNT_BALANCE =
            AccountBalance(
                sapling = SEEDED_POOL_BALANCE,
                orchard = SEEDED_POOL_BALANCE,
                ironwood = SEEDED_POOL_BALANCE,
                unshielded = Zatoshi(40L)
            )

        private val TICK = 20.milliseconds
        private val HALF_SECOND = 500.milliseconds
        private val ONE_SECOND = 1.seconds
        private val TWO_SECONDS = 2.seconds

        /** Mirrors [SlipstreamSynchronizer]'s own private `TXID_NOT_RECOGNIZED_STATUS`. */
        private const val TXID_NOT_RECOGNIZED_STATUS = -1L
    }
}
