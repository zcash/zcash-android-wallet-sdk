package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.KeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.KeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.MigrationProgress
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.jni.RustBackend
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.migration.JniAttentionReason
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.api.PreferenceProvider
import cash.z.ecc.android.sdk.internal.storage.preference.keys.EncryptedPreferenceKeys
import cash.z.ecc.android.sdk.internal.transaction.submitTransaction
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.SdkFlags
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        Twig.debug { "MIGRATION_DIAG OrchardMigrationSdk: $operation starting" }
        var attempt = 1
        while (true) {
            try {
                val result = block()
                Twig.debug { "MIGRATION_DIAG OrchardMigrationSdk: $operation succeeded" }
                return result
            } catch (e: Throwable) {
                val looksLikeSyncRace = e.message?.contains("InsufficientFunds") == true
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

    override suspend fun getMigrationState(): MigrationState = logged("getMigrationState") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged MigrationState.NotStarted
        migrationBackend.migrationState(dbDataPath, network, account).toPublic()
    }

    override suspend fun getMigrationProgress(): MigrationProgress? = logged("getMigrationProgress") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged null
        migrationBackend.migrationProgress(dbDataPath, network, account)?.toPublic()
    }

    // ── Note splitting ───────────────────────────────────────────────────────

    override suspend fun isNoteSplitNeeded(): Boolean = logged("isNoteSplitNeeded") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged false
        migrationBackend.isNoteSplitNeeded(dbDataPath, network, account)
    }

    override suspend fun prepareNoteSplit(): NoteSplitProposal = logged("prepareNoteSplit") {
        val dbDataPath = dbDataPath()
        val account = account ?: noAccountAvailable()
        val proposal = migrationBackend.prepareNoteSplit(dbDataPath, network, account)
        NoteSplitProposal(
            outputNotes = proposal.outputValuesZatoshi.toList(),
            fee = proposal.feeZatoshi,
        )
    }

    override suspend fun submitNoteSplit(proposal: NoteSplitProposal, usk: UnifiedSpendingKey): TransferResult =
        logged("submitNoteSplit") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            val prepared = migrationBackend.signNoteSplit(
                dbDataPath,
                network,
                account,
                proposal.outputNotes.toLongArray(),
                proposal.fee,
                usk.copyBytes(),
            )
            val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
            val submitResult = broadcast(rawTx, prepared.txid, useTor = false, endpoint = defaultSubmitEndpoint)
            val mapped = mapSubmitResult(submitResult)
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

    override suspend fun createUnsignedNoteSplitPczt(): ByteArray = logged("createUnsignedNoteSplitPczt") {
        val dbDataPath = dbDataPath()
        val account = account ?: noAccountAvailable()
        migrationBackend.createUnsignedNoteSplitPczt(dbDataPath, network, account)
    }

    override suspend fun storeSignedNoteSplitPczt(
        signedPczt: ByteArray,
        options: NetworkPrivacyOptions
    ): TransferResult = logged("storeSignedNoteSplitPczt") {
        val dbDataPath = dbDataPath()
        val account = account ?: noAccountAvailable()
        val prepared = migrationBackend.storeSignedNoteSplitPczt(dbDataPath, network, account, signedPczt)
        val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
        val endpoint = options.submissionEndpoint?.let(::parseSubmissionEndpoint) ?: defaultSubmitEndpoint
        val submitResult = broadcast(rawTx, prepared.txid, useTor = options.useTor, endpoint = endpoint)
        val mapped = mapSubmitResult(submitResult)
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

    override suspend fun createUnsignedTransferPczts(schedule: MigrationSchedule): List<Pair<String, ByteArray>> =
        logged("createUnsignedTransferPczts") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.createUnsignedTransferPczts(dbDataPath, network, account, schedule.toJni())
                .map { it.id to it.pcztBytes }
        }

    override suspend fun storeSignedSchedulePczts(signed: List<Pair<String, ByteArray>>) =
        logged("storeSignedSchedulePczts") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.storeSignedSchedulePczts(
                dbDataPath,
                network,
                account,
                Array(signed.size) { signed[it].first },
                Array(signed.size) { signed[it].second },
            )
        }

    override suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        maxFragmentLen: Int
    ): List<String> = logged("buildKeystoneSignBatchQrParts") {
        migrationBackend.buildKeystoneSignBatchQrParts(
            requestId,
            splitUnsignedPczt,
            transferUnsignedPczts.toTypedArray(),
            maxFragmentLen,
        ).toList()
    }

    override suspend fun resetKeystoneSignBatchDecoder() = logged("resetKeystoneSignBatchDecoder") {
        migrationBackend.resetKeystoneSignBatchDecoder()
    }

    override suspend fun decodeKeystoneSignBatchPart(
        part: String,
        expectedRequestId: ByteArray
    ): KeystoneBatchDecodeResult = logged("decodeKeystoneSignBatchPart") {
        migrationBackend.decodeKeystoneSignBatchPart(part, expectedRequestId).toPublic()
    }

    override suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        batchSignResponse: ByteArray
    ): KeystoneBatchSignedPczts = logged("applyKeystoneBatchSignatures") {
        migrationBackend.applyKeystoneBatchSignatures(
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
            migrationBackend.proposeMigrationTransfersFromSplit(
                dbDataPath,
                network,
                account,
                splitProposal.outputNotes.toLongArray(),
                splitProposal.fee,
            ).toPublic()
        }

    override suspend fun proposeImmediateMigration(): MigrationSchedule = logged("proposeImmediateMigration") {
        val dbDataPath = dbDataPath()
        val account = account ?: noAccountAvailable()
        migrationBackend.proposeImmediateMigrationTransfers(dbDataPath, network, account).toPublic()
    }

    override suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule, usk: UnifiedSpendingKey) =
        logged("signAndStoreMigrationSchedule") {
            val dbDataPath = dbDataPath()
            val account = account ?: noAccountAvailable()
            migrationBackend.signAndStoreMigrationSchedule(
                dbDataPath,
                network,
                account,
                schedule.toJni(),
                usk.copyBytes(),
            )
        }

    // ── Background execution ─────────────────────────────────────────────────

    override suspend fun isSyncRequiredBeforeNextTransfer(): Boolean = logged("isSyncRequiredBeforeNextTransfer") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged false
        migrationBackend.isSyncRequiredBeforeNextTransfer(dbDataPath, network, account)
    }

    override suspend fun finalizeReadyTransfers(): Int = logged("finalizeReadyTransfers") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged 0
        migrationBackend.finalizeReadyTransfers(dbDataPath, network, account)
    }

    override suspend fun executeNextPendingTransfer(options: NetworkPrivacyOptions): TransferResult? =
        logged("executeNextPendingTransfer") {
            val dbDataPath = dbDataPath()
            val account = account ?: return@logged null
            // Checked before broadcasting: this is the "was this call itself an out-of-band 'send
            // now' resume" signal for the post-broadcast privacy buffer below. next_due_transfer()'s
            // PreparedTransfer carries no schedule window of its own to check per-transfer, so this
            // uses the aggregate hasOverdueTransfers() signal as the best available proxy.
            val wasOverdue = migrationBackend.hasOverdueTransfers(dbDataPath, network, account)
            val prepared = migrationBackend.nextDueTransfer(dbDataPath, network, account) ?: return@logged null
            val rawTx = migrationBackend.extractBroadcastTx(dbDataPath, network, account, prepared.pcztBytes)
            val endpoint = options.submissionEndpoint?.let(::parseSubmissionEndpoint) ?: defaultSubmitEndpoint
            val submitResult = broadcast(rawTx, prepared.txid, useTor = options.useTor, endpoint = endpoint)
            val mapped = mapSubmitResult(submitResult)
            migrationBackend.recordTransferResult(
                dbDataPath,
                network,
                account,
                prepared.id,
                mapped.tag,
                mapped.retryable,
                mapped.txIdBytes,
            )
            if (wasOverdue && mapped.transferResult is TransferResult.Success) {
                preferenceProviderHolder().putString(
                    EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key,
                    (Clock.System.now().epochSeconds + privacySyncBufferDuration().inWholeSeconds).toString(),
                )
            }
            mapped.transferResult
        }

    // ── Sync coordination ────────────────────────────────────────────────────

    override fun isSyncBlocked(): Flow<Boolean> =
        flow {
            val preferenceProvider = preferenceProviderHolder()
            emitAll(
                combine(
                    tickerFlow(SYNC_BLOCK_TICK),
                    preferenceProvider.observe(EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key),
                ) { _, _ -> }
                    .map { isSyncBlockedNow(preferenceProvider) }
                    .distinctUntilChanged()
            )
        }

    override fun privacySyncBufferDuration(): Duration = PRIVACY_SYNC_BUFFER

    // ── On-launch reconciliation ─────────────────────────────────────────────

    override suspend fun hasOverdueTransfers(): Boolean = logged("hasOverdueTransfers") {
        val dbDataPath = dbDataPath()
        val account = account ?: return@logged false
        migrationBackend.hasOverdueTransfers(dbDataPath, network, account)
    }

    override suspend fun rescheduleOverdueTransfer(): TransferProposal = logged("rescheduleOverdueTransfer") {
        // No Rust call backs the reschedule decision itself (see the interface doc) — but the
        // pending transfer's own fields (amount/anchorHeight/expiryHeight) come from
        // pendingTransferProposal(), a dedicated MigrationContext accessor added specifically for
        // this: next_due_transfer() only returns an opaque, already-signed PreparedTransfer
        // (id/txid/pcztBytes), not the proposal it was signed from.
        val dbDataPath = dbDataPath()
        val account = account ?: noAccountAvailable()
        val pending = migrationBackend.pendingTransferProposal(dbDataPath, network, account)?.toPublic()
            ?: error("OrchardMigrationSdk: no pending transfer to reschedule")
        val nowSeconds = Clock.System.now().epochSeconds
        // Target the same natural cadence the engine schedules by default; if that would land at
        // or past the transfer's own expiry, target just short of it instead — pushing past
        // expiry isn't a valid reschedule (hasInvalidTransfers/restartCurrentMigrationStep is the
        // recovery path once even that isn't possible).
        val newNextExecutableAfterHeight =
            minOf(nowSeconds + RESCHEDULE_INTERVAL_SECONDS, pending.expiryHeight - 1)
        pending.copy(nextExecutableAfterHeight = newNextExecutableAfterHeight)
    }

    override suspend fun hasInvalidTransfers(): Boolean = logged("hasInvalidTransfers") {
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

    private suspend fun isSyncBlockedNow(preferenceProvider: PreferenceProvider): Boolean {
        val dbDataPath = dbDataPath()
        // Same mutex as logged() — this poll must never read the wallet DB at the same moment a
        // real migration operation (propose/sign/execute) does; see logged()'s doc comment.
        val overdue = MIGRATION_DB_ACCESS_MUTEX.withLock {
            // No account was bound at construction (the WalletCoordinatorFactory gate case,
            // evaluated before any account is chosen) — check every account in the wallet rather
            // than assuming one, so sync stays blocked if *any* of them has an overdue migration
            // transfer.
            if (account != null) {
                migrationBackend.hasOverdueTransfers(dbDataPath, network, account)
            } else {
                migrationBackend.getAccountUuids(dbDataPath, network)
                    .any { migrationBackend.hasOverdueTransfers(dbDataPath, network, it) }
            }
        }
        val resumeAtEpochSeconds =
            preferenceProvider.getString(EncryptedPreferenceKeys.MIGRATION_SYNC_RESUME_AT.key)?.toLongOrNull()
        val bufferActive = resumeAtEpochSeconds != null && resumeAtEpochSeconds > Clock.System.now().epochSeconds
        return overdue || bufferActive
    }

    // Time passing alone can flip "overdue"/"buffer elapsed" even with no data change, so
    // isSyncBlocked() needs to re-evaluate periodically, not just when the resume-at timestamp
    // itself changes.
    private fun tickerFlow(interval: Duration): Flow<Unit> = flow {
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
            client.submitTransaction(
                FirstClassByteArray(rawTx),
                FirstClassByteArray(txId),
                SdkFlags(isTorEnabled = useTor && torClient != null, isExchangeRateEnabled = false),
            )
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

        // How many extra attempts logged() makes for an InsufficientFunds-shaped failure before
        // giving up and reporting it — observed sync-cycle write windows are a few seconds, so two
        // retries at RACE_RETRY_DELAY apart comfortably rides out one.
        const val RACE_RETRY_MAX_ATTEMPTS = 2
        val RACE_RETRY_DELAY = 2.seconds

        // Post-broadcast privacy buffer for the "send now" resume path — a real, fixed value
        // (unlike the app-side mock's debug-shrunk one): this decouples broadcast timing from
        // sync-resume timing for privacy, so it should not vary by build type in production code.
        val PRIVACY_SYNC_BUFFER = 10.minutes

        // Matches the engine's own default target cadence between scheduled transfers (~6h) —
        // rescheduling to roughly one more natural interval out, same as the cadence a fresh
        // schedule would already have used.
        const val RESCHEDULE_INTERVAL_SECONDS = 6 * 60 * 60L

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
 * Maps a raw submission outcome to the engine's [TransferResult], both as the public value and
 * as the scalar params `record_transfer_result` needs. Used by both [OrchardMigrationSdkImpl.submitNoteSplit]
 * and [OrchardMigrationSdkImpl.executeNextPendingTransfer].
 *
 * No expiry-height signal is threaded through here — `next_due_transfer()` returns a
 * `PreparedTransfer`, which (unlike `TransferProposal`) carries no `expiryHeight`, so a
 * non-network rejection can't yet be told apart from an expired anchor and is treated as
 * [TransferResult.InvalidNote] (the Rust `MigrationError`/lightwalletd rejection reasons don't
 * distinguish these either). Disambiguating needs either extending `PreparedTransfer` or a
 * separate chain-tip lookup — flagged as a follow-up, not a blocker.
 */
private fun mapSubmitResult(result: TransactionSubmitResult): MappedTransferResult =
    when (result) {
        is TransactionSubmitResult.Success ->
            MappedTransferResult(
                transferResult = TransferResult.Success(result.txIdString()),
                tag = 0,
                retryable = false,
                txIdBytes = result.txId.byteArray,
            )
        is TransactionSubmitResult.Failure ->
            if (result.grpcError) {
                MappedTransferResult(TransferResult.NetworkError(retryable = true), tag = 1, retryable = true, txIdBytes = ByteArray(0))
            } else {
                MappedTransferResult(TransferResult.InvalidNote, tag = 2, retryable = false, txIdBytes = ByteArray(0))
            }
        is TransactionSubmitResult.NotAttempted ->
            MappedTransferResult(TransferResult.NetworkError(retryable = true), tag = 1, retryable = true, txIdBytes = ByteArray(0))
    }

private fun JniMigrationProgress.toPublic(): MigrationProgress =
    MigrationProgress(
        completedTransfers = completedTransfers,
        totalTransfers = totalTransfers,
        remainingOrchardZatoshi = remainingOrchardValueZatoshi,
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

private fun JniMigrationSchedule.toPublic(): MigrationSchedule =
    MigrationSchedule(
        transfers = transfers.map { it.toPublic() },
        estimatedDurationHours = estimatedDurationHours,
    )

private fun TransferProposal.toJni(): JniTransferProposal =
    JniTransferProposal(
        id = id,
        amountZatoshi = amountZatoshi,
        anchorHeight = anchorHeight,
        nextExecutableAfterHeight = nextExecutableAfterHeight,
        expiryHeight = expiryHeight,
    )

private fun MigrationSchedule.toJni(): JniMigrationSchedule =
    JniMigrationSchedule(
        transfers = transfers.map { it.toJni() }.toTypedArray(),
        estimatedDurationHours = estimatedDurationHours,
    )

private fun JniKeystoneBatchDecodeResult.toPublic(): KeystoneBatchDecodeResult =
    KeystoneBatchDecodeResult(complete = complete, progress = progress, data = data)

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
