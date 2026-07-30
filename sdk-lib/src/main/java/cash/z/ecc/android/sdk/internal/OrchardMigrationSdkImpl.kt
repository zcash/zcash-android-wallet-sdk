@file:Suppress(
    "LongParameterList",
    "MaxLineLength",
    "SwallowedException",
    "TooGenericExceptionCaught",
    "TooManyFunctions"
)

package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.KeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.KeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.PreparationStep
import cash.z.ecc.android.sdk.MigrationSummary
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.migration.JniAttentionReason
import cash.z.ecc.android.sdk.internal.model.migration.JniDueTransferResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparationStep
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationTransferState
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationTransferStates
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Real, Rust-backed implementation of [OrchardMigrationSdk].
 *
 * Deliberately independent of [cash.z.ecc.android.sdk.Synchronizer]: `WalletCoordinatorFactory`
 * needs [isSyncBlocked] *before* any `Synchronizer` exists (it gates whether `WalletCoordinator`
 * creates one at all) — a `Synchronizer`-scoped factory would be circular. Every method here
 * instead resolves the wallet's db path (via [DatabaseCoordinator], the same helper
 * `getWalletDbPathForVoting()` uses) lazily, per call, independent of any `Synchronizer`.
 *
 * The interface itself has no account parameter on any method, so it already assumes one bound
 * account per instance — [account] is that bound account, resolved by the caller from whichever
 * wallet account the migration flow is actually running against (Zodl/Keystone or Zashi, whichever
 * is currently selected — never auto-picked here). It's nullable only for the one call site that
 * genuinely has no account selection to make yet: `WalletCoordinatorFactory`'s [isSyncBlocked]
 * gate, evaluated before any account is chosen, which checks *every* account in the wallet rather
 * than assuming one. Every other operation requires a non-null [account]: on-launch/background
 * calls degrade to their "nothing to do" answer (`NotStarted`, `null`, `false`) if it's somehow
 * missing rather than throwing; calls that only make sense once a wallet exists (`prepareNoteSplit`,
 * `submitNoteSplit`, the propose/sign/restart family) throw instead.
 */
internal class OrchardMigrationSdkImpl(
    private val context: Context,
    private val network: ZcashNetwork,
    private val alias: String,
    private val account: AccountUuid?,
    private val migrationBackend: TypesafeMigrationBackend,
    private val chainTipEstimator: ChainTipEstimator = NoOpChainTipEstimator,
    private val defaultSubmitEndpoint: LightWalletEndpoint,
    private val preferenceProviderHolder: EncryptedPreferenceProvider,
) : OrchardMigrationSdk {
    /**
     * [NetworkPrivacyOptions.useTor] is a per-migration setting, independent of the app's global
     * Tor toggle (`IsTorEnabledStorageProvider`) — this uses its own [TorClient], in its own
     * on-disk directory ([migrationTorDir]), rather than sharing the main `Synchronizer`'s. Built
     * lazily (a Tor runtime is nontrivial to spin up) and only if a broadcast actually asks for it.
     */
    private val torClientLazy =
        SuspendingLazy<Unit, TorClient?> {
            try {
                val coordinator = DatabaseCoordinator.getInstance(context)
                val saplingParamTool = SaplingParamTool.new(context)
                val backend =
                    RustBackend.new(
                        coordinator.fsBlockDbRoot(network, alias),
                        coordinator.dataDbFile(network, alias),
                        saplingOutputFile = saplingParamTool.outputParamsFile,
                        saplingSpendFile = saplingParamTool.spendParamsFile,
                        zcashNetworkId = network.id,
                    )
                TorClient.new(migrationTorDir(context), backend)
            } catch (e: Exception) {
                Twig.error(e) { "OrchardMigrationSdk: error instantiating migration Tor client" }
                null
            }
        }

    private suspend fun dbDataPath(): String =
        withContext(Dispatchers.IO) {
            DatabaseCoordinator.getInstance(context).dataDbFile(network, alias).absolutePath
        }

    private fun noAccountAvailable(): Nothing =
        error("OrchardMigrationSdk: no wallet account available yet")

    /**
     * Logs entry/success/failure around every call into [migrationBackend] (the JNI boundary into
     * `zcash_pool_migration`), so a failure deep in the Rust layer is attributable to a specific
     * SDK operation in logcat instead of surfacing only as an opaque, unlabeled exception higher
     * up the call stack (e.g. inside a ViewModel's generic error handling).
     *
     * Serializes against [MIGRATION_DB_ACCESS_MUTEX] — shared across every [OrchardMigrationSdkImpl]
     * instance via the companion object, since a fresh instance is constructed per call site (see
     * the class doc) — so this crate's own [isSyncBlocked] background poll (a separate instance,
     * ticking every [SYNC_BLOCK_TICK] regardless of whether any migration screen is open) can never
     * run concurrently with a real migration operation and read/write the shared wallet database at
     * the same moment. This does not coordinate with
     * [cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor]'s own sync cycles (no public
     * hook exists for that — see [logged]'s retry note below) but removes one whole source of
     * self-inflicted contention entirely.
     *
     * Also retries a bounded number of times on an `InsufficientFunds`-shaped failure: this
     * crate opens its own SQLite connection to the same wallet database
     * [cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor] periodically writes to, with
     * no coordination between the two. A sync cycle's write window overlapping a migration read
     * has been observed in practice to transiently make the spendable balance/notes read back as
     * empty, resolving itself within a second or two once that cycle finishes — retrying rides out
     * that window instead of surfacing a spurious failure. A genuine insufficient balance fails the
     * same way on every attempt and is still reported once retries are exhausted.
     */
    private suspend fun <T> logged(operation: String, block: suspend () -> T): T =
        MIGRATION_DB_ACCESS_MUTEX.withLock { loggedRetryLoop(operation, block) }

    private suspend fun <T> loggedRetryLoop(operation: String, block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                val result = block()
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is not a failure — the caller's scope went away (typically UI
                // recomposition churn cancelling a scoped-flow child). Logging it at error level
                // produced a steady stream of scary-but-benign "operation failed:
                // ChildCancelledException" lines, and retrying a cancelled block would outlive
                // the caller. Propagate immediately, silently.
                throw e
            } catch (e: Throwable) {
                // "database is locked": rusqlite's busy_timeout (5 s, set in open_at) rides out
                // short contention, but a sync cycle's long write transaction can exceed it —
                // observed live as a main-thread crash from hasOverdueTransfers while the
                // foreground synchronizer was mid-sync. Transient by nature: the lock clears when
                // that write transaction commits, so it gets the same bounded retry as the
                // InsufficientFunds sync race.
                val looksLikeSyncRace = e.message?.contains("InsufficientFunds") == true ||
                    e.message?.contains("database is locked") == true
                if (looksLikeSyncRace && attempt <= RACE_RETRY_MAX_ATTEMPTS) {
                    Twig.error(e) {
                        "MIGRATION_DIAG OrchardMigrationSdk: $operation failed (attempt $attempt/" +
                            "$RACE_RETRY_MAX_ATTEMPTS, looks like a sync race) — retrying in $RACE_RETRY_DELAY"
                    }
                    delay(RACE_RETRY_DELAY)
                    attempt++
                    continue
                }
                Twig.error(e) { "MIGRATION_DIAG OrchardMigrationSdk: $operation failed" }
                throw e
            }
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    override suspend fun getMigrationState(): MigrationState =
        logged("getMigrationState") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged MigrationState.NotStarted
            migrationBackend.migrationState(dbDataPath, network, account).toPublic()
        }

    override suspend fun getMigrationProgress(): MigrationProgress? =
        logged("getMigrationProgress") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged null
            migrationBackend.migrationProgress(dbDataPath, network, account)?.toPublic()
        }

    override suspend fun estimateMigrationRunCount(): Int? =
        logged("estimateMigrationRunCount") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged null
            migrationBackend.estimateMigrationRunCount(dbDataPath, network, account)
        }

    // ── Note splitting ───────────────────────────────────────────────────────

    override suspend fun isNoteSplitNeeded(): Boolean =
        logged("isNoteSplitNeeded") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged false
            migrationBackend.isNoteSplitNeeded(dbDataPath, network, account)
        }

    override suspend fun prepareNoteSplit(): NoteSplitProposal =
        logged("prepareNoteSplit") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            val proposal = migrationBackend.prepareNoteSplit(dbDataPath, network, account)
            NoteSplitProposal(
                outputNotes = proposal.outputValuesZatoshi.toList(),
                fee = proposal.feeZatoshi,
                proposalHandle = proposal.proposalHandle,
            )
        }

    override suspend fun submitNoteSplit(proposal: NoteSplitProposal, usk: UnifiedSpendingKey): TransferResult =
        logged("submitNoteSplit") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            val prepared =
                migrationBackend.signNoteSplit(
                    dbDataPath,
                    network,
                    account,
                    proposal.proposalHandle,
                    usk.copyBytes(),
                )
            val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
            val submitResult = broadcast(rawTx, prepared.txid, useTor = false, endpoint = defaultSubmitEndpoint)
            // F2: probe for a duplicate/already-on-chain rejection before mapping (see mapSubmitResult).
            val minedHeight: Long =
                if (submitResult is TransactionSubmitResult.Failure && !submitResult.grpcError) {
                    migrationBackend.transactionMinedHeight(dbDataPath, network, prepared.txid)
                } else {
                    -1L
                }
            val mapped = mapSubmitResult(submitResult, prepared.txid, minedHeight)
            migrationBackend.recordTransferResult(
                dbDataPath,
                network,
                account,
                prepared.id,
                mapped.tag,
                mapped.retryable,
                mapped.txIdBytes,
            )
            mapped.transferResult
        }

    // ── External signer (Keystone hardware wallet) ──────────────────────────

    override suspend fun createUnsignedNoteSplitPczt(proposal: NoteSplitProposal): ByteArray =
        logged("createUnsignedNoteSplitPczt") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.createUnsignedNoteSplitPczt(dbDataPath, network, account, proposal.proposalHandle)
        }

    override suspend fun storeSignedNoteSplitPczt(
        signedPczt: ByteArray,
        options: NetworkPrivacyOptions
    ): TransferResult =
        logged("storeSignedNoteSplitPczt") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            val prepared = migrationBackend.storeSignedNoteSplitPczt(dbDataPath, network, account, signedPczt)
            val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
            val endpoint = options.submissionEndpoint?.let(::parseSubmissionEndpoint) ?: defaultSubmitEndpoint
            val submitResult = broadcast(rawTx, prepared.txid, useTor = options.useTor, endpoint = endpoint)
            // F2: probe for a duplicate/already-on-chain rejection before mapping (see mapSubmitResult).
            val minedHeight: Long =
                if (submitResult is TransactionSubmitResult.Failure && !submitResult.grpcError) {
                    migrationBackend.transactionMinedHeight(dbDataPath, network, prepared.txid)
                } else {
                    -1L
                }
            val mapped = mapSubmitResult(submitResult, prepared.txid, minedHeight)
            migrationBackend.recordTransferResult(
                dbDataPath,
                network,
                account,
                prepared.id,
                mapped.tag,
                mapped.retryable,
                mapped.txIdBytes,
            )
            mapped.transferResult
        }

    override suspend fun createUnsignedTransferPczts(schedule: MigrationSchedule): List<Pair<Long, ByteArray>> =
        logged("createUnsignedTransferPczts") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend
                .createUnsignedTransferPczts(dbDataPath, network, account, schedule.proposalHandle)
                .map { it.id to it.pcztBytes }
        }

    override suspend fun storeSignedSchedulePczts(signed: List<Pair<Long, ByteArray>>) =
        logged("storeSignedSchedulePczts") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.storeSignedSchedulePczts(
                dbDataPath,
                network,
                account,
                LongArray(signed.size) { signed[it].first },
                Array(signed.size) { signed[it].second },
            )
        }

    override suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        maxFragmentLen: Int
    ): List<String> =
        logged("buildKeystoneSignBatchQrParts") {
            migrationBackend
                .buildKeystoneSignBatchQrParts(
                    requestId,
                    splitUnsignedPczt,
                    transferUnsignedPczts.toTypedArray(),
                    maxFragmentLen,
                ).toList()
        }

    override suspend fun resetKeystoneSignBatchDecoder() =
        logged("resetKeystoneSignBatchDecoder") {
            migrationBackend.resetKeystoneSignBatchDecoder()
        }

    override suspend fun decodeKeystoneSignBatchPart(
        part: String,
        expectedRequestId: ByteArray
    ): KeystoneBatchDecodeResult =
        logged("decodeKeystoneSignBatchPart") {
            migrationBackend.decodeKeystoneSignBatchPart(part, expectedRequestId).toPublic()
        }

    override suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        batchSignResponse: ByteArray
    ): KeystoneBatchSignedPczts =
        logged("applyKeystoneBatchSignatures") {
            migrationBackend
                .applyKeystoneBatchSignatures(
                    splitUnsignedPczt,
                    transferUnsignedPczts.toTypedArray(),
                    batchSignResponse,
                ).toPublic()
        }

    // ── Migration proposal ───────────────────────────────────────────────────

    override suspend fun proposeMigrationTransfers(includeResidual: Boolean): MigrationSchedule =
        logged("proposeMigrationTransfers") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.proposeMigrationTransfers(dbDataPath, network, account, includeResidual).toPublic()
        }

    override suspend fun proposeMigrationTransfersFromSplit(splitProposal: NoteSplitProposal): MigrationSchedule =
        logged("proposeMigrationTransfersFromSplit") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend
                .proposeMigrationTransfersFromSplit(
                    dbDataPath,
                    network,
                    account,
                    splitProposal.proposalHandle,
                ).toPublic()
        }

    override suspend fun proposeImmediateMigration(): Proposal =
        logged("proposeImmediateMigration") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            Proposal.fromByteArray(migrationBackend.proposeImmediateSendMax(dbDataPath, network, account))
        }

    override suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule, usk: UnifiedSpendingKey) =
        logged("signAndStoreMigrationSchedule") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.signAndStoreMigrationSchedule(
                dbDataPath,
                network,
                account,
                schedule.proposalHandle,
                usk.copyBytes(),
            )
        }

    // ── Background execution ─────────────────────────────────────────────────

    override suspend fun finalizeReadyTransfers(): Int =
        logged("finalizeReadyTransfers") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged 0
            migrationBackend.finalizeReadyTransfers(dbDataPath, network, account)
        }

    override suspend fun executeNextPendingTransfer(
        options: NetworkPrivacyOptions,
        useEstimatedTip: Boolean,
    ): TransferAttemptOutcome =
        logged("executeNextPendingTransfer") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged TransferAttemptOutcome.NothingDue
            val est = if (useEstimatedTip) chainTipEstimator.estimatedTip() else -1L
            // Checked before broadcasting: this is the "was this call itself an out-of-band 'send
            // now' resume" signal for the post-broadcast privacy buffer below. next_due_transfer()'s
            // PreparedTransfer carries no schedule window of its own to check per-transfer, so this
            // uses the aggregate hasOverdueTransfers() signal as the best available proxy.
            val wasOverdue = migrationBackend.hasOverdueTransfers(dbDataPath, network, account, est)
            // nextDueTransfer returns a tri-state: NOTHING_DUE (0), READY (1), or AWAITING_PROOF (2).
            val dueResult = migrationBackend.nextDueTransfer(dbDataPath, network, account, est)
            when (dueResult.status) {
                0 -> return@logged TransferAttemptOutcome.NothingDue
                2 -> return@logged TransferAttemptOutcome.AwaitingProof(
                    dueResult.awaitingProofTransferId
                        ?: error("nextDueTransfer returned status=2 (AwaitingProof) with null transferId — Rust contract violation")
                )
                else -> Unit // status 1: fall through to broadcast
            }
            val prepared = dueResult.prepared ?: return@logged TransferAttemptOutcome.NothingDue
            val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
            val endpoint = options.submissionEndpoint?.let(::parseSubmissionEndpoint) ?: defaultSubmitEndpoint
            // Mark the broadcast as in-flight before attempting the network call so the sync engine
            // is gated for the duration. A stale mark from a crash self-expires in BROADCAST_IN_FLIGHT_WINDOW_SECONDS.
            val prefs = preferenceProviderHolder()
            prefs.putString(
                EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key,
                (Clock.System.now().epochSeconds + BROADCAST_IN_FLIGHT_WINDOW_SECONDS).toString(),
            )
            val submitResult = broadcast(rawTx, prepared.txid, useTor = options.useTor, endpoint = endpoint)
            // F2: a non-gRPC submit Failure must NOT be terminally recorded as InvalidNote (tag=2)
            // until we rule out "our transaction is already on-chain / already in the mempool" —
            // otherwise a duplicate rejection after a submit-then-crash kills the whole pre-signed
            // plan (and, for Keystone, forces a fresh signing ceremony). Probe the prepared txid's
            // mined height before mapping; the rejection text is the mempool-duplicate fallback.
            val minedHeight: Long =
                if (submitResult is TransactionSubmitResult.Failure && !submitResult.grpcError) {
                    migrationBackend.transactionMinedHeight(dbDataPath, network, prepared.txid)
                } else {
                    -1L
                }
            val mapped = mapSubmitResult(submitResult, prepared.txid, minedHeight)
            migrationBackend.recordTransferResult(
                dbDataPath,
                network,
                account,
                prepared.id,
                mapped.tag,
                mapped.retryable,
                mapped.txIdBytes,
            )
            // Clear the in-flight mark now that the result is recorded.
            prefs.putString(EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key, "0")
            if (wasOverdue && mapped.transferResult is TransferResult.Success) {
                prefs.putString(
                    EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key,
                    (Clock.System.now().epochSeconds + privacySyncBufferDuration().inWholeSeconds).toString(),
                )
            }
            TransferAttemptOutcome.Executed(mapped.transferResult)
        }

    // ── Sync coordination ────────────────────────────────────────────────────

    override fun isSyncBlocked(): Flow<Boolean> =
        flow {
            val preferenceProvider = preferenceProviderHolder()
            emitAll(
                combine(
                    tickerFlow(SYNC_BLOCK_TICK),
                    preferenceProvider.observe(EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key),
                    preferenceProvider.observe(EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key),
                ) { _, _, _ -> }
                    .mapNotNull {
                        // Resilient per-tick read: a transient SQLite lock (racing the sync
                        // engine's block writes) must skip this tick, not crash the collecting
                        // scope — crashed live 2026-07-28 during a testnet min-difficulty burst.
                        runCatching { isSyncBlockedNow(preferenceProvider) }
                            .onFailure { Twig.warn(it) { "isSyncBlocked tick failed (transient) — skipping" } }
                            .getOrNull()
                    }
                    .distinctUntilChanged()
            )
        }

    override fun privacySyncBufferDuration(): Duration = privacySyncBufferFor(network)

    // ── On-launch reconciliation ─────────────────────────────────────────────

    override suspend fun hasOverdueTransfers(useEstimatedTip: Boolean): Boolean =
        logged("hasOverdueTransfers") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged false
            val est = if (useEstimatedTip) chainTipEstimator.estimatedTip() else -1L
            migrationBackend.hasOverdueTransfers(dbDataPath, network, account, est)
        }

    override suspend fun reconcileInvalidations(): Boolean =
        logged("reconcileInvalidations") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged false
            migrationBackend.reconcileInvalidatedTransfers(dbDataPath, network, account)
        }

    override suspend fun estimatedChainTip(): Long = chainTipEstimator.estimatedTip()

    override suspend fun estimatedSecondsPerBlock(): Long = chainTipEstimator.estimatedSecondsPerBlock()

    override suspend fun hasInvalidTransfers(): Boolean =
        logged("hasInvalidTransfers") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged false
            migrationBackend.hasInvalidTransfers(dbDataPath, network, account)
        }

    // ── Invalidity recovery ──────────────────────────────────────────────────

    override suspend fun restartCurrentMigrationStep(includeResidual: Boolean): MigrationSchedule =
        logged("restartCurrentMigrationStep") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.restartCurrentMigrationStep(dbDataPath, network, account, includeResidual).toPublic()
        }

    override suspend fun getMigrationTransferStates(): MigrationTransferStates? =
        logged("getMigrationTransferStates") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.migrationTransferStates(dbDataPath, network, account)?.toPublic()
        }

    override suspend fun getMigrationSummary(): MigrationSummary? =
        logged("getMigrationSummary") {
            val dbDataPath = dbDataPath()
            // No account needed — the migration tables are wallet-scoped. An EMPTY array means no
            // migration data / no mined transfer yet; map it to null so the screen zero-fills.
            val summary = migrationBackend.migrationSummary(dbDataPath)
            if (summary.size < SUMMARY_ARRAY_SIZE) {
                null
            } else {
                MigrationSummary(
                    totalMigratedZatoshi = summary[0],
                    transferCount = summary[1].toInt(),
                    firstMinedEpochSeconds = summary[2],
                    lastMinedEpochSeconds = summary[3],
                )
            }
        }

    // ── Dust locking ─────────────────────────────────────────────────────────

    override suspend fun migrationDustThresholdZatoshi(): Long =
        logged("migrationDustThresholdZatoshi") {
            migrationBackend.migrationDustThresholdZatoshi()
        }

    override suspend fun lockRemainingOrchardBalance() =
        logged("lockRemainingOrchardBalance") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.lockRemainingOrchardBalance(dbDataPath, network, account)
            Unit
        }

    // ── Debug ─────────────────────────────────────────────────────────────────

    override suspend fun clearMigration() =
        logged("clearMigration") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.clearMigration(dbDataPath, network, account)
            Unit
        }

    private suspend fun isSyncBlockedNow(preferenceProvider: PreferenceProvider): Boolean {
        val dbDataPath = dbDataPath()
        // Same mutex as logged() — this poll must never read the wallet DB at the same moment a
        // real migration operation (propose/sign/execute) does; see logged()'s doc comment.
        val overdue =
            MIGRATION_DB_ACCESS_MUTEX.withLock {
                // No account was bound at construction (the WalletCoordinatorFactory gate case,
                // evaluated before any account is chosen) — check every account in the wallet rather
                // than assuming one, so sync stays blocked if *any* of them has an overdue migration
                // transfer.
                if (account != null) {
                    migrationBackend.hasOverdueTransfers(dbDataPath, network, account)
                } else {
                    migrationBackend
                        .getAccountUuids(dbDataPath, network)
                        .any { migrationBackend.hasOverdueTransfers(dbDataPath, network, it) }
                }
            }
        val nowEpochSeconds = Clock.System.now().epochSeconds
        val resumeAtEpochSeconds =
            preferenceProvider.getString(EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key)?.toLongOrNull()
        val bufferActive = resumeAtEpochSeconds != null && resumeAtEpochSeconds > nowEpochSeconds
        val inFlightUntilEpochSeconds =
            preferenceProvider.getString(EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL.key)?.toLongOrNull()
                ?: 0L
        val broadcastInFlight = isBroadcastInFlight(nowEpochSeconds, inFlightUntilEpochSeconds)
        return overdue || bufferActive || broadcastInFlight
    }

    // Time passing alone can flip "overdue"/"buffer elapsed" even with no data change, so
    // isSyncBlocked() needs to re-evaluate periodically, not just when the resume-at timestamp
    // itself changes.
    private fun tickerFlow(interval: Duration): Flow<Unit> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(interval)
            }
        }

    /**
     * Submits raw consensus transaction bytes directly via [WalletClientFactory], deliberately
     * bypassing `Broadcaster`/`SdkBroadcaster`: those assume a [cash.z.ecc.android.sdk.model.CreatedTransaction]
     * that gets stored into the wallet's own transaction repository, which migration transfers
     * have no equivalent of (the engine tracks its own state via `record_transfer_result`/
     * `next_due_transfer`) — routing through them would silently register an untracked entry in
     * `PendingSubmitPlanStore` that the ordinary resubmit loop could never find.
     */
    private suspend fun broadcast(
        rawTx: ByteArray,
        txId: ByteArray,
        useTor: Boolean,
        endpoint: LightWalletEndpoint,
    ): TransactionSubmitResult {
        val torClient = if (useTor) torClientLazy.getInstance(Unit) else null
        val client = WalletClientFactory(context, torClient?.let { resolved -> LazyTorClient { resolved } }).create(endpoint)
        return try {
            withBroadcastTimeout(useTor = useTor, txId = txId) {
                client.submitTransaction(
                    FirstClassByteArray(rawTx),
                    FirstClassByteArray(txId),
                    SdkFlags(isTorEnabled = useTor && torClient != null, isExchangeRateEnabled = false),
                )
            }
        } finally {
            withContext(NonCancellable) { client.dispose() }
        }
    }

    private suspend fun migrationTorDir(context: Context): File =
        File(Files.getZcashNoBackupSubdirectory(context), MIGRATION_TOR_SUBDIR)

    private companion object {
        // Shared across every OrchardMigrationSdkImpl instance (a fresh one is constructed per
        // call site — see the class doc), so the isSyncBlocked() background poll and any real
        // migration operation never touch the wallet database at the same moment. See logged()'s
        // doc comment for the full rationale.
        val MIGRATION_DB_ACCESS_MUTEX = Mutex()

        val SYNC_BLOCK_TICK = 15.seconds

        // Fields in the migrationSummary() native array:
        // [totalMigratedZatoshi, transferCount, firstMinedEpochSeconds, lastMinedEpochSeconds].
        // A shorter (empty) array means "no migration data / no mined transfer" → null.
        const val SUMMARY_ARRAY_SIZE = 4

        // How many extra attempts logged() makes for an InsufficientFunds-shaped failure before
        // giving up and reporting it — observed sync-cycle write windows are a few seconds, so two
        // retries at RACE_RETRY_DELAY apart comfortably rides out one.
        const val RACE_RETRY_MAX_ATTEMPTS = 3
        val RACE_RETRY_DELAY = 2.seconds

        // How long before a broadcast is considered no longer in-flight (seconds). Written to
        // preferences immediately before calling broadcast(); cleared (written as "0") right after
        // recordTransferResult(). A stale mark from a crash self-expires within this window.
        const val BROADCAST_IN_FLIGHT_WINDOW_SECONDS = 120L

        // Separate from Files.TOR_SUBDIR (the main Synchronizer's shared Tor directory) — a
        // distinct on-disk Tor client/circuit state for migration broadcasts, per NetworkPrivacyOptions.useTor
        // being an independent, per-migration setting rather than the app's global Tor toggle.
        const val MIGRATION_TOR_SUBDIR = "tor_migration"
    }
}

/**
 * No caller sets [NetworkPrivacyOptions.submissionEndpoint] today (the secondary-server UI this
 * would back doesn't exist yet), so there's no established wire format for it — this assumes the
 * conventional `host:port` shape and standard TLS, the same as every current lightwalletd
 * endpoint in this app.
 */
private fun parseSubmissionEndpoint(endpoint: String): LightWalletEndpoint {
    val (host, port) = endpoint.substringBeforeLast(':') to endpoint.substringAfterLast(':').toInt()
    return LightWalletEndpoint(host, port, isSecure = true)
}

private class MappedTransferResult(
    val transferResult: TransferResult,
    val tag: Int,
    val retryable: Boolean,
    val txIdBytes: ByteArray,
)

/**
 * Case-insensitive substrings that identify a submit rejection as a DUPLICATE of a transaction
 * already known to the network (already broadcast, already in the mempool, already mined). Such a
 * rejection is NOT an invalidation — it is a success that our own crashed/retried broadcast already
 * achieved. See [classifyNonGrpcFailure].
 */
private val DUPLICATE_REJECTION_MARKERS =
    listOf("already in mempool", "duplicate", "already known", "txid already", "already exists")

/**
 * F2 pure decision core: given a non-gRPC submit-`Failure` description and the mined height the
 * txid probe returned (`-1` = wallet knows no height for the prepared txid), decide whether this
 * "failure" is really a success (our transaction is already on-chain / already in the mempool).
 *
 * A non-gRPC rejection is treated as a SUCCESS iff either:
 *   - the wallet already knows a mined height for the prepared txid ([minedHeight] `>= 0`), i.e.
 *     our broadcast landed and we simply never recorded it (submit-then-crash), or
 *   - the rejection text matches a known duplicate-rejection marker (already in mempool /
 *     duplicate / already known txid) — accepted even without a mined height, because a mempool
 *     duplicate has no height yet but is still our transaction, in flight.
 *
 * Only genuinely-unknown non-gRPC rejections return `false` (→ real invalidation, tag=2). This is
 * the single most important behavioural fix in F2: since Task 3 made tag=2 terminally Fail the
 * whole pre-signed plan, a false positive here forces a Keystone re-sign ceremony.
 */
internal fun classifyNonGrpcFailure(description: String?, minedHeight: Long): Boolean {
    if (minedHeight >= 0) return true
    val text = description?.lowercase() ?: return false
    return DUPLICATE_REJECTION_MARKERS.any { text.contains(it) }
}

/**
 * Maps a raw submission outcome to the engine's [TransferResult], both as the public value and
 * as the scalar params `record_transfer_result` needs. Used by [OrchardMigrationSdkImpl.submitNoteSplit],
 * [OrchardMigrationSdkImpl.executeNextPendingTransfer], and the immediate send-max path.
 *
 * [preparedTxid] is the internal-byte-order txid of the transaction just submitted (used to record
 * a duplicate/on-chain rejection as a Success). [minedHeight] is the height the txid probe returned
 * for a non-gRPC failure (`-1` when not probed or unknown). Together with the rejection text these
 * feed [classifyNonGrpcFailure] so a duplicate/already-on-chain rejection records tag=0 (Success)
 * instead of the plan-killing tag=2 (InvalidNote). Only a genuinely-unknown non-gRPC rejection
 * still records tag=2.
 *
 * No expiry-height signal is threaded through here — `next_due_transfer()` returns a
 * `PreparedTransfer`, which (unlike `TransferProposal`) carries no `expiryHeight`, so a
 * genuinely-unknown non-network rejection can't yet be told apart from an expired anchor and is
 * treated as [TransferResult.InvalidNote]. Disambiguating those two needs either extending
 * `PreparedTransfer` or the scanned-tip expiry filter — flagged as a follow-up, not a blocker.
 */
private fun mapSubmitResult(
    result: TransactionSubmitResult,
    preparedTxid: ByteArray,
    minedHeight: Long,
): MappedTransferResult =
    when (result) {
        is TransactionSubmitResult.Success -> {
            MappedTransferResult(
                transferResult = TransferResult.Success(result.txIdString()),
                tag = 0,
                retryable = false,
                txIdBytes = result.txId.byteArray,
            )
        }

        is TransactionSubmitResult.Failure -> {
            when {
                result.grpcError ->
                    MappedTransferResult(
                        TransferResult.NetworkError(retryable = true, isTorFailure = result.isTorFailure),
                        tag = 1,
                        retryable = true,
                        txIdBytes = ByteArray(0),
                    )

                // F2: duplicate / already-on-chain rejection → this is our own transaction, treat
                // as Success (tag=0) so the pre-signed plan is not terminally failed.
                classifyNonGrpcFailure(result.description, minedHeight) ->
                    MappedTransferResult(
                        TransferResult.Success(preparedTxid.toHexReversed()),
                        tag = 0,
                        retryable = false,
                        txIdBytes = preparedTxid,
                    )

                else ->
                    MappedTransferResult(
                        TransferResult.InvalidNote,
                        tag = 2,
                        retryable = false,
                        txIdBytes = ByteArray(0),
                    )
            }
        }

        is TransactionSubmitResult.NotAttempted -> {
            MappedTransferResult(
                TransferResult.NetworkError(retryable = true, isTorFailure = false),
                tag = 1,
                retryable = true,
                txIdBytes = ByteArray(0),
            )
        }
    }

/**
 * How long [broadcast] waits for a submit call (including Tor circuit bootstrap, when [useTor])
 * before giving up. Without this, a stuck Tor circuit or a gRPC call with no server-side deadline
 * can hang the whole call indefinitely — previously observed hanging a background MigrationWorker
 * invocation for the full ~10-minute WorkManager execution ceiling, repeatedly, since nothing ever
 * threw or returned. A generous value relative to normal RPC latency, but small relative to that
 * 10-minute ceiling so up to 3 attempts (see MigrationWorker/MigrationSendingVM) still fit.
 */
internal val BROADCAST_TIMEOUT = 60.seconds

// Sentinel gRPC-shaped code for a client-side timeout — never returned by a real server, so it's
// distinguishable in logs/telemetry from an actual server response code.
private const val BROADCAST_TIMEOUT_CODE = -2

/**
 * Runs [block] (a submit attempt) under a [timeout], mapping a timeout into the same
 * [TransactionSubmitResult.Failure] shape a real gRPC failure would produce instead of letting
 * [kotlinx.coroutines.TimeoutCancellationException] propagate — callers (just [broadcast] today)
 * don't need a separate catch clause. Top-level and `internal` (rather than a private method on
 * [OrchardMigrationSdkImpl]) specifically so it's unit-testable without needing a real
 * WalletClientFactory/CombinedWalletClient/Tor stack.
 */
internal suspend fun withBroadcastTimeout(
    useTor: Boolean,
    txId: ByteArray,
    timeout: Duration = BROADCAST_TIMEOUT,
    block: suspend () -> TransactionSubmitResult,
): TransactionSubmitResult =
    try {
        withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        TransactionSubmitResult.Failure(
            txId = FirstClassByteArray(txId),
            grpcError = true,
            code = BROADCAST_TIMEOUT_CODE,
            description = "Broadcast timed out after $timeout",
            isTorFailure = useTor,
        )
    }

/**
 * Post-broadcast privacy sync buffer for Mainnet — 10 minutes to decouple broadcast timing from
 * sync-resume timing. Never build-type-scaled; a debug build on Mainnet still applies the full
 * buffer. See [privacySyncBufferFor].
 */
internal val PRIVACY_SYNC_BUFFER_MAINNET = 10.minutes

/**
 * Post-broadcast privacy sync buffer for Testnet — 3 minutes for faster development cycles.
 * See [privacySyncBufferFor].
 */
internal val PRIVACY_SYNC_BUFFER_TESTNET = 3.minutes

/**
 * Returns the post-broadcast privacy sync buffer duration for [network]. Mainnet uses
 * [PRIVACY_SYNC_BUFFER_MAINNET] (10 min, full timing-privacy decoupling); testnet uses
 * [PRIVACY_SYNC_BUFFER_TESTNET] (3 min, faster development cycles without compromising production
 * privacy). Never varied by build type — a debug build on Mainnet should apply the full buffer.
 *
 * Top-level and `internal` so it is unit-testable without constructing an
 * [OrchardMigrationSdkImpl]; [OrchardMigrationSdkImpl.privacySyncBufferDuration] delegates here.
 */
internal fun privacySyncBufferFor(network: ZcashNetwork): Duration =
    if (network == ZcashNetwork.Mainnet) PRIVACY_SYNC_BUFFER_MAINNET else PRIVACY_SYNC_BUFFER_TESTNET

/**
 * Returns `true` while [inFlightUntilEpochSeconds] is strictly in the future relative to
 * [nowEpochSeconds], meaning a migration broadcast is currently in progress.
 *
 * A zero or past expiry (including the cleared "0" sentinel) returns `false`, so stale marks
 * written before a crash self-expire within [OrchardMigrationSdkImpl.BROADCAST_IN_FLIGHT_WINDOW_SECONDS]
 * of being written. Top-level and `internal` so it is unit-testable as a pure function.
 */
internal fun isBroadcastInFlight(
    nowEpochSeconds: Long,
    inFlightUntilEpochSeconds: Long,
): Boolean = inFlightUntilEpochSeconds > nowEpochSeconds

private fun JniMigrationProgress.toPublic(): MigrationProgress =
    MigrationProgress(
        completedTransfers = completedTransfers,
        totalTransfers = totalTransfers,
        nextTransferReadyAtHeight = nextTransferReadyAtHeight.takeIf { it != -1L },
    )

private fun JniAttentionReason.toPublic(): AttentionReason =
    when (this) {
        is JniAttentionReason.InvalidTransfer -> AttentionReason.InvalidTransfer(transferId)
        is JniAttentionReason.TransferExpired -> AttentionReason.TransferExpired
        is JniAttentionReason.SyncRequiredBeforeNext -> AttentionReason.SyncRequiredBeforeNext
    }

private fun JniTransferProposal.toPublic(): TransferProposal =
    TransferProposal(
        id = id,
        amountZatoshi = amountZatoshi,
        anchorHeight = anchorHeight,
        nextExecutableAfterHeight = nextExecutableAfterHeight,
        expiryHeight = expiryHeight,
    )

internal fun JniPreparationStep.toPublic(): PreparationStep =
    PreparationStep(
        id = id,
        layer = layer,
        index = index,
        broadcastHeight = broadcastHeight,
        dependsOn = dependsOn.toList(),
    )

internal fun JniMigrationSchedule.toPublic(): MigrationSchedule =
    MigrationSchedule(
        transfers = transfers.map { it.toPublic() },
        preparations = preparations.map { it.toPublic() },
        estimatedDurationHours = estimatedDurationHours,
        proposalHandle = proposalHandle,
    )

private fun JniMigrationTransferState.toPublic(): MigrationTransferState =
    MigrationTransferState(
        id = id,
        isTransfer = isTransfer,
        isSent = isSent,
        isProved = isProved,
        scheduledHeight = scheduledHeight,
        // -1 is the JNI sentinel for "no committed boundary" (preparations prove at their
        // natural anchor).
        anchorBoundaryHeight = anchorBoundaryHeight.takeIf { it >= 0L },
    )

private fun JniMigrationTransferStates.toPublic(): MigrationTransferStates =
    MigrationTransferStates(
        transfers = transfers.map { it.toPublic() },
        tipHeight = tipHeight,
    )

private fun JniKeystoneBatchDecodeResult.toPublic(): KeystoneBatchDecodeResult =
    KeystoneBatchDecodeResult(
        complete = complete,
        progress = progress,
        data = data,
        firmwareVersion = firmwareVersion,
    )

private fun JniKeystoneBatchSignedPczts.toPublic(): KeystoneBatchSignedPczts =
    KeystoneBatchSignedPczts(
        splitSignedPczt = splitSignedPczt,
        transferSignedPczts = transferSignedPczts.toList(),
    )

private fun JniMigrationState.toPublic(): MigrationState =
    when (this) {
        is JniMigrationState.NotStarted -> MigrationState.NotStarted
        is JniMigrationState.SplitPendingConfirmation -> MigrationState.SplitPendingConfirmation
        is JniMigrationState.ReadyToPropose -> MigrationState.ReadyToPropose
        is JniMigrationState.InProgress -> MigrationState.InProgress(progress.toPublic())
        is JniMigrationState.RequiresAttention -> MigrationState.RequiresAttention(reason.toPublic())
        is JniMigrationState.Complete -> MigrationState.Complete
    }
