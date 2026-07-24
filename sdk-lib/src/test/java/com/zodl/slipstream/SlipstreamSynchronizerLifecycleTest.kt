package com.zodl.slipstream

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.FastestServerFetcher
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import co.electriccoin.lightwallet.client.model.Response
import com.zodl.slipstream.internal.InstanceGuard
import com.zodl.slipstream.internal.SlipstreamEngine
import com.zodl.slipstream.internal.SlipstreamKey
import com.zodl.slipstream.internal.db.SlipstreamTransactionReader
import com.zodl.slipstream.internal.db.TransactionsController
import com.zodl.slipstream.internal.spend.ResubmissionTicker
import com.zodl.slipstream.internal.spend.SlipstreamBroadcaster
import com.zodl.slipstream.internal.spend.SlipstreamSpendService
import com.zodl.slipstream.model.SlipstreamSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
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
 */
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
            runBlocking { synchronizer.accountsFlow.first() }
        }
    }

    @Test
    fun accounts_flow_propagates_cancellation_unwrapped() {
        val backend = mock(Backend::class.java)
        val synchronizer = buildSynchronizer(backend = backend)
        runBlocking { `when`(backend.getAccounts()).thenThrow(CancellationException("cancelled")) }

        assertFailsWith<CancellationException> {
            runBlocking { synchronizer.accountsFlow.first() }
        }
    }

    @Test
    fun pause_stops_polling_without_tearing_the_engine_down() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.pause()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).stopPolling()
                verify(engine, after(SETTLE_MS).never()).stop()
                verify(engine, after(SETTLE_MS).never()).free()
                verify(engine, after(SETTLE_MS).never()).shutdown()
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
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.resume()

            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun status_reports_synced_while_paused_then_reverts_after_resume() {
        val engine = mock(SlipstreamEngine::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.SYNCING)
        val key = newKey()
        // Override the default SYNCED stub so we can prove the wrap, not the stub.
        val synchronizer = buildSynchronizer(engine = engine, key = key, engineStatusOverride = engineStatus)
        try {
            runBlocking {
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
                synchronizer.pause()
                assertEquals(Synchronizer.Status.SYNCED, synchronizer.status.first())
                synchronizer.resume()
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_while_paused_does_not_restart_polling() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.onForeground()

            runBlocking { verify(engine, after(SETTLE_MS).never()).startPolling() }
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
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
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

    private fun newKey() = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")

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
        startBirthday: BlockHeight = BlockHeight.new(STARTING_BIRTHDAY_VALUE),
        engineStatusOverride: MutableStateFlow<Synchronizer.Status>? = null,
        lastSnapshotOverride: MutableStateFlow<SlipstreamSnapshot?>? = null
    ): SlipstreamSynchronizer {
        `when`(engine.status).thenReturn(engineStatusOverride ?: MutableStateFlow(Synchronizer.Status.SYNCED))
        `when`(engine.progress).thenReturn(MutableStateFlow(PercentDecimal.ZERO_PERCENT))
        `when`(engine.areFundsSpendable).thenReturn(MutableStateFlow(false))
        `when`(engine.networkHeight).thenReturn(MutableStateFlow<BlockHeight?>(null))
        `when`(engine.fullyScannedHeight).thenReturn(MutableStateFlow<BlockHeight?>(null))
        `when`(engine.walletBalances).thenReturn(MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null))
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
            resubmissionTicker = mock(ResubmissionTicker::class.java),
            startBirthday = startBirthday
        )
    }

    companion object {
        private const val TIMEOUT_MS = 2_000L
        private const val SETTLE_MS = 300L
        private const val LATCH_TIMEOUT_SECONDS = 2L
        private const val STARTING_BIRTHDAY_VALUE = 2_000_000L
        private const val ACCOUNT_UUID_BYTES = 16

        private val TICK = 20.milliseconds
        private val HALF_SECOND = 500.milliseconds
        private val ONE_SECOND = 1.seconds
        private val TWO_SECONDS = 2.seconds

        /** Mirrors [SlipstreamSynchronizer]'s own private `TXID_NOT_RECOGNIZED_STATUS`. */
        private const val TXID_NOT_RECOGNIZED_STATUS = -1L
    }
}
