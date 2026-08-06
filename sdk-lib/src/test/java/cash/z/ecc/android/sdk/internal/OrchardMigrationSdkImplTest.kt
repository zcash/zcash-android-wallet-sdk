package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.MigrationPeek
import cash.z.ecc.android.sdk.MigrationStepKind
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.PreparationStep
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.model.JniUnifiedSpendingKey
import cash.z.ecc.android.sdk.internal.model.migration.JniDueTransferResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationTransferStates
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparationStep
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparedTransfer
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedPreparationPczt
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedTransferPczt
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.PreferenceHolder
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.storage.preference.model.entry.PreferenceKey
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.BlockHeightUnsafe
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
class OrchardMigrationSdkImplTest {
    private companion object {
        /**
         * How long the read-liveness test waits for a pure read while a broadcast is parked. Long
         * enough that a slow machine cannot fail it, short enough to fail fast if the read is once
         * again queued behind the send's mutex.
         */
        val READ_LIVENESS_TIMEOUT = 5.seconds

        /** How long each fake send holds the broadcast mutex in the serialization test. */
        val BROADCAST_HOLD = 200.milliseconds
    }

    @Test
    fun `privacy buffer is 10 minutes on mainnet and 3 on testnet`() {
        assertEquals(10.minutes, privacySyncBufferFor(ZcashNetwork.Mainnet))
        assertEquals(3.minutes, privacySyncBufferFor(ZcashNetwork.Testnet))
    }

    @Test
    fun `sync is blocked while broadcast in flight mark is in the future`() {
        // pure helper: isBroadcastInFlight(nowEpoch, markEpoch) -> Boolean
        assertTrue(isBroadcastInFlight(nowEpochSeconds = 100, inFlightUntilEpochSeconds = 160))
        assertFalse(isBroadcastInFlight(nowEpochSeconds = 200, inFlightUntilEpochSeconds = 160))
    }

    // ── F2: non-gRPC submit-failure classification ──────────────────────────
    // classifyNonGrpcFailure(description, minedHeight) == true means "treat as Success" (our tx is
    // already on-chain / in the mempool); false means "genuinely unknown rejection" (record tag=2).

    @Test
    fun `a mined txid makes a non-gRPC failure a success regardless of text`() {
        assertTrue(classifyNonGrpcFailure(description = null, minedHeight = 0L))
        assertTrue(classifyNonGrpcFailure(description = "some unknown reason", minedHeight = 1_234_567L))
    }

    @Test
    fun `duplicate rejection strings are treated as success even without a mined height`() {
        assertTrue(classifyNonGrpcFailure("tx already in mempool", minedHeight = -1L))
        assertTrue(classifyNonGrpcFailure("Duplicate transaction", minedHeight = -1L))
        assertTrue(classifyNonGrpcFailure("txid ABC already known to node", minedHeight = -1L))
        // Case-insensitive.
        assertTrue(classifyNonGrpcFailure("ALREADY IN MEMPOOL", minedHeight = -1L))
    }

    @Test
    fun `a genuinely unknown rejection with no mined height stays a failure`() {
        assertFalse(classifyNonGrpcFailure("insufficient fee", minedHeight = -1L))
        assertFalse(classifyNonGrpcFailure(null, minedHeight = -1L))
        assertFalse(classifyNonGrpcFailure("", minedHeight = -1L))
    }

    // ── Task 9: report_broadcast_failure observed_tip plumbing ──────────────
    // recordedResultTag/parseObservedTipResponse are the two pure decision points
    // executeNextPendingTransfer's call site wires together (see commitBroadcastOutcome /
    // fetchObservedTipAfterRejection). Neither broadcast() nor fetchObservedTipAfterRejection()
    // itself is unit-testable end-to-end: both construct a real WalletClientFactory/
    // CombinedWalletClient with no injection seam in this class's constructor, so there is no way
    // to feed executeNextPendingTransfer a controlled non-gRPC TransactionSubmitResult.Failure (a
    // real "genuinely unknown rejection" requires a live/mocked gRPC server actually answering
    // SendResponse with a nonzero code — this suite has no such fixture, see the entry/post-send
    // commit cancellation tests above, whose own comments note "nothing is listening on the
    // endpoint" produces a *gRPC-level* failure, i.e. tag=1, not tag=2/4). These two functions are
    // exactly the reclassification/response-mapping logic pulled out to keep it testable anyway.

    @Test
    fun `recordedResultTag overrides InvalidNote (tag 2) to AwaitingReevaluation (tag 4)`() {
        assertEquals(4, recordedResultTag(2))
    }

    @Test
    fun `recordedResultTag passes every other tag through unchanged`() {
        assertEquals(0, recordedResultTag(0))
        assertEquals(1, recordedResultTag(1))
        assertEquals(3, recordedResultTag(3))
    }

    @Test
    fun `parseObservedTipResponse returns the height on a Success response`() {
        val response: Response<BlockHeightUnsafe> = Response.Success(BlockHeightUnsafe(4_226_123L))
        assertEquals(4_226_123L, parseObservedTipResponse(response))
    }

    @Test
    fun `parseObservedTipResponse returns -1 when the follow-up call itself fails`() {
        val response: Response<BlockHeightUnsafe> =
            Response.Failure.Connection(cause = IllegalStateException("no route to host"))
        assertEquals(-1L, parseObservedTipResponse(response))
    }

    @Test
    fun `proposeImmediateMigration delegates to the send-max native call and returns an ordinary Proposal`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val proposalBytes = fakeProposalBytes()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    proposeImmediateSendMaxResult = proposalBytes
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.proposeImmediateMigration()

            assertTrue(fakeBackend.proposeImmediateSendMaxCalled)
            assertEquals(account, fakeBackend.lastAccount)
            assertEquals(proposalBytes.toList(), result.toUnsafe().toByteArray().toList())
        }

    /**
     * `migrationDustThresholdZatoshi()` is a pure pass-through to the Rust-side
     * `MIGRATION_DUST_THRESHOLD_ZATOSHI` constant (100,000 zatoshi / 0.001 ZEC) — no account or
     * database state involved. This just pins down that the value round-trips through the
     * typesafe backend unchanged.
     */
    @Test
    fun `migrationDustThresholdZatoshi returns the backend's constant value unchanged`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    migrationDustThresholdZatoshiResult = 100_000L
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.migrationDustThresholdZatoshi()

            assertEquals(100_000L, result)
        }

    /**
     * `getMigrationSummary()` decodes the native `[totalMigratedZatoshi, transferCount,
     * firstMinedEpochSeconds, lastMinedEpochSeconds]` array into a typed [MigrationSummary]. Uses
     * the same crossing-values total / mined-transfer count / block-time bounds the Migration
     * Complete screen shows.
     */
    @Test
    fun `getMigrationSummary decodes the native array into a typed summary`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    migrationSummaryResult = longArrayOf(9_779_000_000L, 10L, 1_785_281_502L, 1_785_283_542L)
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.getMigrationSummary()

            assertEquals(9_779_000_000L, result?.totalMigratedZatoshi)
            assertEquals(10, result?.transferCount)
            assertEquals(1_785_281_502L, result?.firstMinedEpochSeconds)
            assertEquals(1_785_283_542L, result?.lastMinedEpochSeconds)
            // No account is needed — the migration tables are wallet-scoped.
            assertTrue(fakeBackend.migrationSummaryDbDataPath != null)
        }

    /**
     * An EMPTY native array (no migration data / no mined transfer yet) maps to `null`, so the
     * Migration Complete screen falls back to zeros rather than showing garbage.
     */
    @Test
    fun `getMigrationSummary maps an empty native array to null`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend = FakeTypesafeMigrationBackend(migrationSummaryResult = LongArray(0))
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            assertEquals(null, sdk.getMigrationSummary())
        }

    @Test
    fun `withBroadcastTimeout passes through a result that completes before the timeout`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }
            val expected = TransactionSubmitResult.Success(FirstClassByteArray(txId))

            val result =
                withBroadcastTimeout(useTor = false, txId = txId, timeout = 200.milliseconds) {
                    expected
                }

            assertEquals(expected, result)
        }

    @Test
    fun `withBroadcastTimeout maps a hang past the timeout to a Tor-tagged failure when useTor is true`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }

            val result =
                withBroadcastTimeout(useTor = true, txId = txId, timeout = 20.milliseconds) {
                    delay(500.milliseconds)
                    error("should never reach here — timeout should win first")
                }

            assertTrue(result is TransactionSubmitResult.Failure)
            assertTrue(result.isTorFailure)
            assertTrue(result.grpcError)
        }

    @Test
    fun `withBroadcastTimeout does not tag the failure as Tor when useTor is false`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }

            val result =
                withBroadcastTimeout(useTor = false, txId = txId, timeout = 20.milliseconds) {
                    delay(500.milliseconds)
                    error("should never reach here — timeout should win first")
                }

            assertTrue(result is TransactionSubmitResult.Failure)
            assertFalse(result.isTorFailure)
        }

    @Test
    fun `toPublic maps preparations`() {
        val jni =
            JniMigrationSchedule(
                transfers = emptyArray(),
                preparations =
                    arrayOf(
                        JniPreparationStep(
                            id = 2,
                            layer = 1,
                            index = 0,
                            broadcastHeight = 4219055,
                            dependsOn = longArrayOf(0, 1)
                        )
                    ),
                estimatedDurationHours = 1,
                proposalHandle = 7,
            )
        val pub = jni.toPublic()
        assertEquals(1, pub.preparations.size)
        assertEquals(PreparationStep(2, 1, 0, 4219055, listOf(0L, 1L)), pub.preparations.first())
    }

    // ── Task 1 (spec §2a): cancellation-safe broadcast mark ──────────────────
    // executeNextPendingTransfer's entry guard must skip a re-send of an in-flight tx that is
    // already mined (a prior send whose mark never persisted); the guard's own record segment
    // must survive cancellation of the caller.

    /**
     * Pure-function coverage of the entry-guard predicate itself — both branches, no SDK object,
     * no fake backend, no prefs/coroutine plumbing. This is what actually pins down the guard's
     * *condition*; the SDK-level tests below only pin down that the condition is wired to the
     * right production effects (skip broadcast, uncancellable record).
     */
    @Test
    fun `shouldSkipReSendAlreadyMined is true only when in-flight and mined`() {
        assertTrue(
            shouldSkipReSendAlreadyMined(inFlightUntilEpochSeconds = 200, nowEpochSeconds = 100, minedHeight = 0L)
        )
        assertTrue(
            shouldSkipReSendAlreadyMined(
                inFlightUntilEpochSeconds = 200,
                nowEpochSeconds = 100,
                minedHeight = 4_226_000L
            )
        )
    }

    @Test
    fun `shouldSkipReSendAlreadyMined is false when not in-flight or not mined`() {
        // In-flight mark already expired/cleared, even though the txid happens to be mined.
        assertFalse(
            shouldSkipReSendAlreadyMined(inFlightUntilEpochSeconds = 50, nowEpochSeconds = 100, minedHeight = 0L)
        )
        assertFalse(
            shouldSkipReSendAlreadyMined(inFlightUntilEpochSeconds = 0, nowEpochSeconds = 100, minedHeight = 0L)
        )
        // Still in-flight, but the txid probe found no mined height.
        assertFalse(
            shouldSkipReSendAlreadyMined(inFlightUntilEpochSeconds = 200, nowEpochSeconds = 100, minedHeight = -1L)
        )
    }

    /**
     * The in-flight flag is still set from a prior send whose mark never persisted (outer timeout
     * / cancellation between send and record), but the exact prepared txid is already mined. The
     * entry guard must record success directly (with the same txid, hex-reversed, as
     * `mapSubmitResult`'s duplicate-rejection branch produces) and must NOT call back into
     * `extractBroadcastTx`/`broadcast` for the identical transaction.
     */
    @Test
    fun executeNextPendingTransfer_skips_resend_when_inflight_tx_already_mined() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val inFlightUntil = (Clock.System.now().epochSeconds + 60).toString()
            val prepared = preparedTransfer()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(status = 1, awaitingProofTransferId = null, prepared = prepared),
                    transactionMinedHeightResult = 4_226_000L, // already mined
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder =
                        FakePreferenceHolder(
                            FakePreferenceProvider(
                                mapOf(EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key to inFlightUntil)
                            )
                        ),
                )

            val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)

            assertEquals(0, fakeBackend.broadcastCallCount, "must NOT re-broadcast an already-mined in-flight tx")
            check(outcome is TransferAttemptOutcome.Executed) { "expected Executed, got $outcome" }
            val result = outcome.result
            check(result is TransferResult.Success) { "expected Success, got $result" }
            assertEquals(prepared.txid.toHexReversed(), result.txId)
        }

    /**
     * Proves the entry guard's record segment is genuinely uncancellable, not just untested: the
     * fake's `recordTransferResult` signals [enteredRecord] on entry and then suspends on
     * [recordGate] until the test releases it. The caller (`executeNextPendingTransfer`, launched
     * in a child [Job]) is cancelled while suspended inside that record segment; only afterwards
     * does the test let it proceed. If `recordTransferResult` still runs to completion
     * (`recordTransferResultCompleted == true`) despite the cancellation, the mark survives —
     * which is exactly the property `withContext(NonCancellable)` exists for.
     *
     * Manually verified this test fails when `withContext(NonCancellable)` is temporarily removed
     * from the entry guard's record segment: `recordTransferResultCompleted` stays `false` because
     * cancelling the job while suspended in `recordGate.await()` throws `CancellationException`
     * out of `recordTransferResult` instead of letting it finish — see task-1-report.md, Fix round 1.
     */
    @Test
    fun executeNextPendingTransfer_entry_guard_record_survives_caller_cancellation() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val inFlightUntil = (Clock.System.now().epochSeconds + 60).toString()
            val enteredRecord = CompletableDeferred<Unit>()
            val recordGate = CompletableDeferred<Unit>()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                    transactionMinedHeightResult = 4_226_000L, // already mined -> takes the entry-guard path
                    onRecordTransferResultEntered = enteredRecord,
                    recordTransferResultGate = recordGate,
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder =
                        FakePreferenceHolder(
                            FakePreferenceProvider(
                                mapOf(EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key to inFlightUntil)
                            )
                        ),
                )

            val job =
                launch {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)
                }
            enteredRecord.await() // wait until execution is suspended inside the NonCancellable record segment
            job.cancel() // cancel the caller while the mark write is mid-flight
            recordGate.complete(Unit) // now let the (uncancellable) record segment proceed
            job.join()

            assertTrue(
                fakeBackend.recordTransferResultCompleted,
                "recordTransferResult must run to completion even after the caller is cancelled"
            )
            assertEquals(0, fakeBackend.broadcastCallCount, "must NOT re-broadcast an already-mined in-flight tx")
        }

    /**
     * The sibling of [executeNextPendingTransfer_entry_guard_record_survives_caller_cancellation]
     * for the NORMAL path, which had no cancellation coverage at all. The post-send commit is the
     * more consequential of the two `NonCancellable` boundaries: the entry guard's exists to clean
     * up after a send whose result was never recorded, and this one is what stops that state from
     * arising in the first place.
     *
     * No in-flight mark is seeded, so the entry guard falls through and a real broadcast is
     * attempted. It fails (nothing is listening on the endpoint), which is immaterial — a recorded
     * failure exercises the same commit segment as a recorded success. The caller is then cancelled
     * while suspended inside `recordTransferResult`; if it still runs to completion, the result is
     * durable and the in-flight mark cannot be orphaned.
     */
    @Test
    fun executeNextPendingTransfer_post_send_commit_survives_caller_cancellation() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val enteredRecord = CompletableDeferred<Unit>()
            val recordGate = CompletableDeferred<Unit>()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                    onRecordTransferResultEntered = enteredRecord,
                    recordTransferResultGate = recordGate,
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    // No in-flight mark: the entry guard must fall through to a real send.
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider(emptyMap())),
                )

            val job =
                launch {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)
                }
            enteredRecord.await() // suspended inside the post-send NonCancellable commit
            job.cancel() // cancel the caller mid-commit
            recordGate.complete(Unit)
            job.join()

            assertTrue(
                fakeBackend.recordTransferResultCompleted,
                "the post-send commit must run to completion even after the caller is cancelled"
            )
            assertEquals(1, fakeBackend.broadcastCallCount, "the entry guard must fall through to a send here")
        }

    /**
     * Regression test for the double-sweep collapse itself: `executeNextPendingTransfer` used to
     * call `hasOverdueTransfers()` unconditionally (a second, redundant `advance_step` sweep) on
     * every attempt before deriving `wasOverdue` from it. Now `wasOverdue` is derived directly from
     * `nextDueTransfer()`'s own `DUE_READY` status (see the collapse at `OrchardMigrationSdkImpl.kt`),
     * so `hasOverdueTransfers` must never be called from this path at all — it stays reachable only
     * from the public `hasOverdueTransfers()` method and `isSyncBlockedNow` (unaffected here).
     *
     * A real network broadcast is attempted (nothing is listening on the test endpoint, so it fails
     * with a transport-level error) — immaterial to what this test asserts, matching the sibling
     * cancellation-safety tests above, which document the same real-broadcast constraint.
     */
    @Test
    fun executeNextPendingTransfer_no_longer_calls_hasOverdueTransfers() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val prepared = preparedTransfer()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(status = 1, awaitingProofTransferId = null, prepared = prepared),
                    // If the old double-call ever regresses, this would flip wasOverdue's would-be
                    // source to false and the call count assertion below would fail either way.
                    hasOverdueTransfersResult = true,
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)

            check(outcome is TransferAttemptOutcome.Executed) { "expected Executed, got $outcome" }
            assertEquals(
                0,
                fakeBackend.hasOverdueTransfersCallCount,
                "executeNextPendingTransfer must derive wasOverdue from nextDueTransfer's own result, " +
                    "not from a second hasOverdueTransfers sweep"
            )
        }

    // ── isSyncBlocked: nextStep-driven, not a blanket plan-wide overdue scan ─

    /**
     * `isSyncBlockedNow`'s `readyToBroadcast` check is driven by the same guarded `nextStep` read
     * the worker loop itself uses — `STEP_BROADCAST` (code 2) means "a transfer is ready to
     * broadcast right now", so sync should back off.
     */
    @Test
    fun isSyncBlocked_emits_true_when_nextStep_says_broadcast_is_ready() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    nextStepResult = longArrayOf(2L, 5L), // STEP_BROADCAST, transferId=5
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            assertTrue(sdk.isSyncBlocked().first())
        }

    /**
     * Regression test for the fix itself: before this fix, `isSyncBlockedNow` used
     * `hasOverdueTransfers()` (no estimated tip) — a blanket "does anything exist overdue
     * anywhere in the plan" scan that stays true for the plan's whole duration on a fast chain
     * (e.g. testnet), permanently starving `syncRun()`'s `syncToTip()` call. `nextStep()` reporting
     * `Waiting` (code 0) — even while some transfer elsewhere in the plan is nominally overdue —
     * must NOT block sync: only an imminent broadcast should.
     */
    @Test
    fun isSyncBlocked_emits_false_when_nextStep_says_waiting_even_though_something_is_overdue_elsewhere_in_plan() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    hasOverdueTransfersResult = true, // old signal — must no longer gate sync
                    nextStepResult = longArrayOf(0L, -1L), // STEP_WAITING
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            assertFalse(sdk.isSyncBlocked().first())
        }

    /** The privacy-buffer and broadcast-in-flight signals must still gate sync independently of nextStep. */
    @Test
    fun isSyncBlocked_emits_true_when_privacy_buffer_is_active_even_though_nextStep_says_waiting() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val resumeAt = (Clock.System.now().epochSeconds + 60).toString()
            val fakeBackend = FakeTypesafeMigrationBackend(nextStepResult = longArrayOf(0L, -1L))
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder =
                        FakePreferenceHolder(
                            FakePreferenceProvider(
                                mapOf(EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key to resumeAt)
                            )
                        ),
                )

            assertTrue(sdk.isSyncBlocked().first())
        }

    // ── mark_superseded write-through (Task 7): nextStep's Replan branch ─────

    /**
     * `nextStep()`'s `STEP_REPLAN` branch must call `markMigrationSuperseded` before returning
     * `MigrationAdvanceStep.Replan`, so every consumer of `nextStep()` gets a superseded
     * migration automatically (not just the app worker's own Replan handler) — see
     * `OrchardMigrationSdkImpl.kt`'s `nextStep()`.
     */
    @Test
    fun nextStep_marks_the_migration_superseded_when_the_engine_reports_replan() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend = FakeTypesafeMigrationBackend(nextStepResult = longArrayOf(5L, -1L)) // STEP_REPLAN
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            val result = sdk.nextStep()

            assertEquals(MigrationAdvanceStep.Replan, result?.step)
            assertTrue(fakeBackend.markMigrationSupersededCalled)
        }

    // ── nextStep peek-ahead: arr[2]/arr[3] parsing (MigrationPeek) ────────────

    /**
     * `nextStep()` must parse `arr[2]`/`arr[3]` (the engine's own peek-ahead, new on the
     * librustzcash main pin adopted 2026-08-05) into `MigrationAdvanceResult.next` — a regression
     * guard against a sign-flip or index-swap in that parsing, since every other test above only
     * ever supplies a 2-long array and would not catch one.
     */
    @Test
    fun nextStep_parses_the_peek_height_and_kind_from_arr_2_and_arr_3() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    // STEP_PROVE (id=5), peek: height=4200000, kind=STEP_BROADCAST (2).
                    nextStepResult = longArrayOf(1L, 5L, 4_200_000L, 2L),
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            val result = sdk.nextStep()

            assertEquals(MigrationAdvanceStep.Prove(5L), result?.step)
            assertEquals(MigrationPeek(height = 4_200_000L, kind = MigrationStepKind.BROADCAST), result?.next)
        }

    /** A `-1`/`-1` pair (nothing height-schedulable) must parse to `next = null`, not a bogus peek. */
    @Test
    fun nextStep_parses_the_sentinel_pair_as_a_null_peek() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    nextStepResult = longArrayOf(0L, -1L, -1L, -1L), // STEP_WAITING, no peek
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            val result = sdk.nextStep()

            assertEquals(MigrationAdvanceStep.Waiting, result?.step)
            assertNull(result?.next)
        }

    /** A bare 2-long array (a fake/backend that hasn't been updated yet) must still parse cleanly. */
    @Test
    fun nextStep_treats_a_missing_peek_pair_as_a_null_peek() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend = FakeTypesafeMigrationBackend(nextStepResult = longArrayOf(0L, -1L))
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider()),
                )

            val result = sdk.nextStep()

            assertNull(result?.next)
        }
    // ── MOB-1623: lock scoping (pure reads out of the DB mutex, the send out of it entirely) ──

    /**
     * A pure read must not queue behind a broadcast. Before the split, `executeNextPendingTransfer`
     * held MIGRATION_DB_ACCESS_MUTEX across the whole send (up to the 60 s broadcast timeout) and
     * `getMigrationTransferStates` — the Progress screen's first paint — waited for the same mutex;
     * this test would hang until the parked broadcast was released.
     */
    @Test
    fun getMigrationTransferStates_completes_while_a_broadcast_is_parked() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val gate = CompletableDeferred<Unit>()
            val broadcaster = GatedBroadcaster(gate = gate)
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                    migrationTransferStatesResult =
                        JniMigrationTransferStates(transfers = emptyArray(), tipHeight = 4_226_000L),
                )
            val sdk = sdkWith(account, fakeBackend, broadcaster)

            val broadcasting =
                launch {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)
                }
            broadcaster.entered.await()

            val states = withTimeout(READ_LIVENESS_TIMEOUT) { sdk.getMigrationTransferStates() }

            assertEquals(4_226_000L, states?.tipHeight)
            gate.complete(Unit)
            broadcasting.join()
            assertEquals(1, broadcaster.callCount)
        }

    /**
     * A locked database thrown by the record step used to re-run the whole `logged` block —
     * including a SECOND network send of the identical transaction, absorbed only by the
     * duplicate-rejection classification. With the phases split, only the record retries.
     */
    @Test
    fun executeNextPendingTransfer_retries_the_record_without_re_broadcasting() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val broadcaster = GatedBroadcaster()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                    recordTransferResultLockedFailures = 1,
                )
            val sdk = sdkWith(account, fakeBackend, broadcaster)

            val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)

            assertEquals(1, broadcaster.callCount, "a failed record must NOT re-send the transaction")
            assertEquals(2, fakeBackend.recordTransferResultCallCount, "the record step itself must retry")
            check(outcome is TransferAttemptOutcome.Executed) { "expected Executed, got $outcome" }
            val result = outcome.result
            check(result is TransferResult.Success) { "expected Success, got $result" }
        }

    /**
     * Two concurrent entrants (the Progress screen's foreground pass, the Sending screen and
     * MigrationWorker are all real ones) served the same next-due transfer: exactly one may send it.
     * The DB mutex used to provide that by covering the whole method; now the in-flight mark and its
     * phase-1 guard do, and the second caller must back off rather than re-send.
     */
    @Test
    fun a_second_caller_does_not_re_broadcast_the_transfer_already_in_flight() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val gate = CompletableDeferred<Unit>()
            val broadcaster = GatedBroadcaster(gate = gate)
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                )
            val sdk = sdkWith(account, fakeBackend, broadcaster)

            val first =
                async {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)
                }
            broadcaster.entered.await()
            val second = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)

            assertTrue(
                second is TransferAttemptOutcome.NothingDue,
                "the second caller must back off while the first send is in flight, got $second"
            )
            gate.complete(Unit)
            val firstOutcome = first.await()
            assertEquals(1, broadcaster.callCount, "the same transfer must be sent exactly once")
            check(firstOutcome is TransferAttemptOutcome.Executed) { "expected Executed, got $firstOutcome" }
            assertTrue(firstOutcome.result is TransferResult.Success)
        }

    /**
     * Broadcasts of DIFFERENT operations still serialize — against BROADCAST_MUTEX now, not the DB
     * mutex. Each fake send holds for [BROADCAST_HOLD]; if the two overlapped, `maxConcurrent` would
     * be 2. Both outcomes must still commit.
     */
    @Test
    fun a_transfer_send_and_a_note_split_send_never_overlap() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val broadcaster = GatedBroadcaster(holdFor = BROADCAST_HOLD)
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    dueTransferResult =
                        JniDueTransferResult(
                            status = 1,
                            awaitingProofTransferId = null,
                            prepared = preparedTransfer()
                        ),
                    signNoteSplitResult =
                        JniPreparedTransfer(
                            id = 2L,
                            txid = ByteArray(32) { (it + 1).toByte() },
                            pcztBytes = ByteArray(4) { it.toByte() },
                        ),
                )
            val sdk = sdkWith(account, fakeBackend, broadcaster)

            val transfer =
                async {
                    sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = false), useEstimatedTip = false)
                }
            val noteSplit =
                async {
                    sdk.submitNoteSplit(
                        NoteSplitProposal(outputNotes = listOf(1_000_000L), fee = 10_000L, proposalHandle = 7L),
                        UnifiedSpendingKey(JniUnifiedSpendingKey(ByteArray(32) { 1 })),
                    )
                }

            val transferOutcome = transfer.await()
            val noteSplitResult = noteSplit.await()

            assertEquals(2, broadcaster.callCount)
            assertEquals(1, broadcaster.maxConcurrent, "broadcasts must not overlap")
            check(transferOutcome is TransferAttemptOutcome.Executed) { "expected Executed, got $transferOutcome" }
            assertTrue(transferOutcome.result is TransferResult.Success)
            assertTrue(noteSplitResult is TransferResult.Success)
            assertEquals(2, fakeBackend.recordTransferResultCallCount, "both outcomes must be recorded")
        }

    private fun sdkWith(
        account: AccountUuid,
        fakeBackend: FakeTypesafeMigrationBackend,
        broadcaster: MigrationTransactionBroadcaster,
    ): OrchardMigrationSdkImpl =
        OrchardMigrationSdkImpl(
            context = fakeAndroidContext(),
            network = ZcashNetwork.Testnet,
            alias = "OrchardMigrationSdkImplTest",
            account = account,
            migrationBackend = fakeBackend,
            defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
            preferenceProviderHolder = FakePreferenceHolder(FakePreferenceProvider(emptyMap())),
            broadcaster = broadcaster,
        )

    /**
     * Stands in for the whole network phase (see [OrchardMigrationSdkImpl]'s `broadcaster`
     * parameter). Signals [entered] on the first call, optionally parks on [gate] and/or holds for
     * [holdFor], and always reports success for the txid it was handed. [maxConcurrent] records how
     * many sends were in flight at once, which is how the BROADCAST_MUTEX tests tell serialization
     * from luck.
     */
    private class GatedBroadcaster(
        private val gate: CompletableDeferred<Unit>? = null,
        private val holdFor: Duration = Duration.ZERO,
    ) : MigrationTransactionBroadcaster {
        val entered = CompletableDeferred<Unit>()
        var callCount = 0
        var maxConcurrent = 0

        private var inFlight = 0

        override suspend fun submit(
            rawTx: ByteArray,
            txId: ByteArray,
            useTor: Boolean,
            endpoint: LightWalletEndpoint
        ): TransactionSubmitResult {
            callCount++
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            entered.complete(Unit)
            return try {
                gate?.await()
                if (holdFor > Duration.ZERO) delay(holdFor)
                TransactionSubmitResult.Success(FirstClassByteArray(txId))
            } finally {
                inFlight--
            }
        }
    }

    private fun preparedTransfer(): JniPreparedTransfer =
        JniPreparedTransfer(
            id = 1L,
            txid = ByteArray(32) { it.toByte() },
            pcztBytes = ByteArray(4) { it.toByte() },
        )

    /** In-memory [PreferenceProvider] — mirrors `PendingSubmitPlanStoreTest.FakePreferenceProvider`. */
    private class FakePreferenceProvider(
        initial: Map<PreferenceKey, String> = emptyMap()
    ) : PreferenceProvider {
        private val values = mutableMapOf<String, String?>().apply { putAll(initial.mapKeys { it.key.key }) }

        override suspend fun hasKey(key: PreferenceKey): Boolean = values.containsKey(key.key)

        override suspend fun putString(
            key: PreferenceKey,
            value: String?
        ) {
            values[key.key] = value
        }

        override suspend fun getString(key: PreferenceKey): String? = values[key.key]

        // A real PreferenceProvider.observe() emits on subscription — combine() in
        // isSyncBlocked() depends on that to produce its first value. One-shot emission is
        // enough for tests, which set up preference state before collecting; they don't rely on
        // observing a live change mid-collection.
        override fun observe(key: PreferenceKey): Flow<Unit> = flowOf(Unit)

        override suspend fun clearPreferences(): Boolean {
            values.clear()
            return true
        }
    }

    /**
     * Substitutes for [EncryptedPreferenceProvider] in tests: that concrete class backs onto real
     * EncryptedSharedPreferences / AndroidX Security Crypto, which needs a real Android Keystore
     * and cannot run in this plain JVM unit test. [OrchardMigrationSdkImpl] takes the [PreferenceHolder]
     * base type for exactly this reason (see Task 1, spec §2a).
     */
    private class FakePreferenceHolder(
        private val provider: PreferenceProvider
    ) : PreferenceHolder() {
        override suspend fun create(): PreferenceProvider = provider

        override suspend fun clear(): Boolean = provider.clearPreferences()
    }

    /**
     * A minimal, hand-encoded `cash.z.wallet.sdk.ffi.Proposal` protobuf message (see
     * `backend-lib/src/main/proto/proposal.proto`) — just field 2 (`feeRule`) set to `Zip317` (3),
     * with no steps. `ProposalUnsafe`'s init check only requires a non-default `feeRule`, and
     * `Proposal.check()`'s `totalFeeRequired()` folds over an empty `steps` list to 0, so this
     * round-trips cleanly through `Proposal.fromByteArray`.
     *
     * Built by hand (proto3 varint wire format: tag byte `(fieldNumber shl 3) or wireType`,
     * followed by the varint value) rather than via the generated `ProposalOuterClass` builder,
     * because the protobuf-lite runtime is an `implementation`-scoped dependency of `backend-lib`
     * (not exposed transitively) — `sdk-lib` can call into `ProposalUnsafe`'s own API (as
     * production code already does) but cannot reference `com.google.protobuf.*` supertypes
     * directly at compile time.
     */
    private fun fakeProposalBytes(): ByteArray {
        val fieldNumber = 2
        val wireTypeVarint = 0
        val tag = (fieldNumber shl 3) or wireTypeVarint
        val feeRuleZip317 = 3
        return byteArrayOf(tag.toByte(), feeRuleZip317.toByte())
    }

    private fun fakeAndroidContext(): Context {
        val tempDir =
            kotlin.io.path
                .createTempDirectory("OrchardMigrationSdkImplTest")
                .toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        `when`(context.getDatabasePath(anyString())).thenReturn(File(tempDir, "databases/unused.db"))
        return context
    }

    // A hand-rolled fake standing in for the whole typesafe backend surface, so both its width and
    // its function count track that interface rather than any design choice of its own.
    @Suppress("TooManyFunctions", "LongParameterList")
    private class FakeTypesafeMigrationBackend(
        private val proposeImmediateSendMaxResult: ByteArray = ByteArray(0),
        private val migrationDustThresholdZatoshiResult: Long = 100_000L,
        private val migrationSummaryResult: LongArray = LongArray(0),
        private val hasOverdueTransfersResult: Boolean = false,
        private val nextStepResult: LongArray? = null,
        private val dueTransferResult: JniDueTransferResult =
            JniDueTransferResult(status = 0, awaitingProofTransferId = null, prepared = null),
        private val transactionMinedHeightResult: Long = -1L,
        private val migrationTransferStatesResult: JniMigrationTransferStates? = null,
        private val signNoteSplitResult: JniPreparedTransfer? = null,
        // How many times recordTransferResult throws a "database is locked" failure before
        // succeeding — the transient sync-race shape loggedRetryLoop is built to ride out.
        private val recordTransferResultLockedFailures: Int = 0,
        // Task 1 cancellation-safety test hooks: when set, recordTransferResult signals
        // [onRecordTransferResultEntered] on entry, then suspends on [recordTransferResultGate]
        // until the test releases it, so a test can cancel the caller while this call is
        // mid-flight and assert it still ran to completion (see
        // executeNextPendingTransfer_entry_guard_record_survives_caller_cancellation).
        private val onRecordTransferResultEntered: CompletableDeferred<Unit>? = null,
        private val recordTransferResultGate: CompletableDeferred<Unit>? = null
    ) : TypesafeMigrationBackend {
        var proposeImmediateSendMaxCalled = false
        var lastAccount: AccountUuid? = null
        var migrationSummaryDbDataPath: String? = null

        // Set only after recordTransferResult has run to completion (i.e. survived any
        // cancellation of the caller while suspended on recordTransferResultGate above).
        var recordTransferResultCompleted = false

        // Captures the args of the LAST recordTransferResult call — task 9's observed_tip plumbing
        // coverage (resultTag == 4, not 2, for a genuinely-unknown rejection; observedTip carried
        // through from fetchObservedTipAfterRejection).
        var lastResultTag: Int? = null
        var lastObservedTip: Long? = null

        // Every entry into recordTransferResult, including the ones that throw — so a test can tell
        // "the record step retried" from "the whole broadcast block retried".
        var recordTransferResultCallCount = 0

        // Counts calls that fetch the raw tx to broadcast — the call the Task 1 entry guard is
        // specifically there to skip on an already-mined in-flight resend.
        var broadcastCallCount = 0

        // Counts calls to hasOverdueTransfers — must stay 0 across executeNextPendingTransfer now
        // that wasOverdue is derived from nextDueTransfer's own result (Task 4's collapse).
        var hasOverdueTransfersCallCount = 0

        override suspend fun migrationDustThresholdZatoshi(): Long = migrationDustThresholdZatoshiResult

        override suspend fun migrationState(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationState = error("Unused")

        override suspend fun migrationProgress(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationProgress? = error("Unused")

        override suspend fun isNoteSplitNeeded(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun estimateMigrationRunCount(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun lockRemainingOrchardBalance(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun clearMigration(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun hasOverdueTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            estimatedTip: Long
        ): Boolean {
            hasOverdueTransfersCallCount++
            return hasOverdueTransfersResult
        }

        override suspend fun hasInvalidTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun transactionMinedHeight(
            dbDataPath: String,
            network: ZcashNetwork,
            txId: ByteArray
        ): Long = transactionMinedHeightResult

        override suspend fun reconcileInvalidatedTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun prepareNoteSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ) = error("Unused")

        override suspend fun signNoteSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long,
            usk: ByteArray
        ): JniPreparedTransfer = signNoteSplitResult ?: error("Unused")

        override suspend fun extractBroadcastTx(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            pcztBytes: ByteArray
        ): ByteArray {
            broadcastCallCount++
            return ByteArray(4) { it.toByte() }
        }

        override suspend fun recordTransferResult(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            transferId: Long,
            resultTag: Int,
            retryable: Boolean,
            txId: ByteArray,
            observedTip: Long
        ) {
            recordTransferResultCallCount++
            if (recordTransferResultCallCount <= recordTransferResultLockedFailures) {
                error("database is locked")
            }
            lastResultTag = resultTag
            lastObservedTip = observedTip
            onRecordTransferResultEntered?.complete(Unit)
            recordTransferResultGate?.await()
            recordTransferResultCompleted = true
        }

        override suspend fun proposeMigrationTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            includeResidual: Boolean
        ): JniMigrationSchedule = error("Unused")

        override suspend fun proposeMigrationTransfersFromSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): JniMigrationSchedule = error("Unused")

        override suspend fun proposeImmediateSendMax(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): ByteArray {
            proposeImmediateSendMaxCalled = true
            lastAccount = account
            return proposeImmediateSendMaxResult
        }

        override suspend fun signAndStoreMigrationSchedule(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long,
            usk: ByteArray
        ) = error("Unused")

        override suspend fun finalizeReadyTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun nextDueTransfer(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            estimatedTip: Long
        ): JniDueTransferResult = dueTransferResult

        override suspend fun restartCurrentMigrationStep(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            includeResidual: Boolean
        ): JniMigrationSchedule = error("Unused")

        override suspend fun migrationTransferStates(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationTransferStates? = migrationTransferStatesResult

        override suspend fun migrationSummary(dbDataPath: String): LongArray {
            migrationSummaryDbDataPath = dbDataPath
            return migrationSummaryResult
        }

        override suspend fun nextStep(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            estimatedTip: Long
        ): LongArray? = nextStepResult

        override suspend fun syncWakeupSchedule(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Array<LongArray>? = error("Unused")

        override suspend fun applySignature(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            transferId: Long,
            signedPczt: ByteArray
        ): Boolean = error("Unused")

        // Set true when markMigrationSuperseded is called — proves nextStep()'s STEP_REPLAN
        // branch wires the write through (see
        // nextStep_marks_the_migration_superseded_when_the_engine_reports_replan).
        var markMigrationSupersededCalled = false

        override suspend fun markMigrationSuperseded(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean {
            markMigrationSupersededCalled = true
            return true
        }

        override suspend fun keystoneSigningRoundBudget(): IntArray = error("Unused")

        override suspend fun createUnsignedPreparationPczts(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): List<JniUnsignedPreparationPczt> = error("Unused")

        override suspend fun getAccountUuids(
            dbDataPath: String,
            network: ZcashNetwork
        ): List<AccountUuid> = error("Unused")

        override suspend fun pendingTransferProposal(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniTransferProposal? = error("Unused")

        override suspend fun createUnsignedNoteSplitPczt(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): ByteArray = error("Unused")

        override suspend fun storeSignedNoteSplitPczt(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            signedPczt: ByteArray
        ): JniPreparedTransfer = error("Unused")

        override suspend fun createUnsignedTransferPczts(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): Array<JniUnsignedTransferPczt> = error("Unused")

        override suspend fun storeSignedSchedulePczts(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            ids: LongArray,
            pcztBytesList: Array<ByteArray>
        ) = error("Unused")

        override suspend fun buildKeystoneSignBatchQrParts(
            requestId: ByteArray,
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            maxFragmentLen: Int
        ): Array<String> = error("Unused")

        override suspend fun resetKeystoneSignBatchDecoder() = error("Unused")

        override suspend fun decodeKeystoneSignBatchPart(
            part: String,
            expectedRequestId: ByteArray
        ): JniKeystoneBatchDecodeResult = error("Unused")

        override suspend fun applyKeystoneBatchSignatures(
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            batchSignResponse: ByteArray
        ): JniKeystoneBatchSignedPczts = error("Unused")
    }
}
