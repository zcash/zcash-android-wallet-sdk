@file:Suppress("TooManyFunctions")

package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/*
 * Kotlin interface for the Orchard → Ironwood migration SDK bridge.
 *
 * Boundary: SDK owns all business logic (split algorithm, anchor height selection,
 * transaction signing, storage, invalidity detection). App owns UI, WorkManager
 * scheduling, permissions, and notifications.
 *
 * Based on: Orchard Migration Flow — Implementation Proposal (Path A)
 * Sync: 2026-06-17
 *
 * Reconciled 2026-07-10 against the real Rust bridge (`zcash_pool_migration` crate,
 * `zcash/librustzcash` branch `michal/ironwood-migration`, PR #2572) — see the "Implementation
 * note (Rust bridge, ...)" comments below for what changed and why:
 * - [proposeMigrationTransfers] / [restartCurrentMigrationStep] gained an `includeResidual`
 *   parameter (pass `false` until the opt-in UI exists).
 * - [reconcileInvalidations] is the replacement for the old non-persisting rescheduleOverdueTransfer
 *   stub that had a units bug — backed by a real Rust call. (The `rescheduleUnprovenTransfer` shift
 *   API that once lived alongside it was deleted 2026-07-28: rescheduling is the ENGINE's semantics
 *   — unexpired transfers prove late and broadcast late; this layer never mutates the plan.)
 * - `initializePostUpgrade()` was removed — unused anywhere in the app, and `MigrationContext`
 *   has no corresponding "post-upgrade init" step (everything is computed live from wallet state).
 * - [submitNoteSplit] / [executeNextPendingTransfer] are each a composition of several Rust calls
 *   (sign/fetch → extract → broadcast via the existing submission path → record result) — the
 *   Kotlin signatures are unchanged, but see their doc comments for the real implementation shape.
 * - [isSyncBlocked] / [privacySyncBufferDuration] are confirmed Kotlin-only (no Rust backing) —
 *   the sync/broadcast de-correlation technique they implement lives entirely in this SDK layer.
 *
 * Reconciled 2026-07-18 while wiring the external-signer (Keystone hardware wallet) path:
 * - New: [createUnsignedNoteSplitPczt] / [storeSignedNoteSplitPczt] and
 *   [createUnsignedTransferPczts] / [storeSignedSchedulePczts] split [submitNoteSplit]'s and
 *   [signAndStoreMigrationSchedule]'s "sign" step into "build the unsigned PCZT" / "accept an
 *   externally-produced signature" halves, for callers with no software spending key at all.
 *   [createUnsignedTransferPczts]'s self-funding transfers use the same sign-now/prove-later
 *   placeholder-witness scheme [signAndStoreMigrationSchedule] does — [finalizeReadyTransfers]
 *   completes them the same way regardless of which path signed them.
 * - New: [buildKeystoneSignBatchQrParts] / [resetKeystoneSignBatchDecoder] /
 *   [decodeKeystoneSignBatchPart] / [applyKeystoneBatchSignatures] — the Keystone-specific
 *   animated-QR batch-signing bridge (encode every unsigned PCZT the caller wants signed as one
 *   multi-part UR QR sequence; decode the device's scanned response back into per-PCZT
 *   signatures). Pure PCZT/UR operations with no wallet-database access, unlike almost everything
 *   else in this interface.
 *
 * Reconciled 2026-07-15 while wiring the real JNI implementation:
 * - [submitNoteSplit] / [signAndStoreMigrationSchedule] gained a `usk: UnifiedSpendingKey`
 *   parameter — sdk-lib never stores/derives a spending key itself (see
 *   [Synchronizer.createProposedTransactions]), and the Rust calls they wrap require one.
 * - [getMigrationState] / [getMigrationProgress] / [isNoteSplitNeeded] / [hasOverdueTransfers] /
 *   [hasInvalidTransfers] are `suspend` — each opens a `MigrationContext` against the wallet's
 *   SQLite database, unlike the mock's free in-memory answers.
 *
 * Reconciled 2026-07-23 removing a dead gate: `isSyncRequiredBeforeNextTransfer()` — a real Rust
 * JNI call that always returned `false` — was deleted entirely. It was never a working check to
 * begin with; [isSyncBlocked]'s `next_broadcastable`-driven overdue detection already provides the
 * genuine ZIP 318 sync/broadcast decoupling in both directions (see that method's doc), so nothing
 * replaces the deleted method — there was no real gap to fill.
 */

// ─── Supporting types ─────────────────────────────────────────────────────────

/**
 * Controls how migration transactions are broadcast.
 *
 * [useTor] — routes the broadcast through Tor when true.
 * [submissionEndpoint] — null means use the same LWD server as sync.
 *   Pass a secondary endpoint to de-correlate sync and submission servers
 *   (privacy improvement: an observer cannot link sync traffic and broadcast traffic
 *   to the same wallet).
 */
data class NetworkPrivacyOptions(
    val useTor: Boolean,
    val submissionEndpoint: String? = null
)

/*
 * SDK-generated note split proposal. The SDK decides the number of output notes,
 * their sizes, and randomisation — the app only shows this to the user for confirmation.
 *
 * Splitting into ~10 notes is the current target heuristic (20k ZEC cap per note).
 */

/**
 * [proposalHandle] is the opaque identifier of the SDK-native cached migration plan this proposal
 * was rendered from. The plan's details never leave the native side: commit calls
 * ([OrchardMigrationSdk.submitNoteSplit], [OrchardMigrationSdk.createUnsignedNoteSplitPczt])
 * pass the handle back, and the native side refuses to sign any plan other than the one it
 * identifies — throwing if a later propose/prepare call superseded it, so what gets signed is
 * always exactly what the user reviewed.
 */
data class NoteSplitProposal(
    val outputNotes: List<Long>, // amounts in zatoshi
    val fee: Long,
    val proposalHandle: Long
)

/**
 * A single scheduled migration transfer.
 *
 * [anchorHeight] is chosen from a shared network-wide 288-block bucket
 * (block height divisible by 288, ≈ 6-hour intervals). This hides the wallet's
 * last sync time from an observer. The anchor and the broadcast time are independent —
 * the transaction can be broadcast at any point after [nextExecutableAfterHeight].
 *
 * [nextExecutableAfterHeight] is what the app uses to schedule the WorkManager task.
 * The SDK sets this; the app does not compute it.
 *
 * [expiryHeight] — if the transaction is not broadcast before this height it becomes
 * invalid and [OrchardMigrationSdk.restartCurrentMigrationStep] must be called.
 */
data class TransferProposal(
    val id: Long,
    val amountZatoshi: Long,
    val anchorHeight: Long,
    val nextExecutableAfterHeight: Long,
    val expiryHeight: Long
)

/**
 * One note-split (preparation) transaction in the migration schedule: its stable [id], which
 * [layer] and [index] within that layer it occupies, the [broadcastHeight] at which to broadcast
 * it, and the ids of earlier preparation transactions whose outputs it spends ([dependsOn], empty
 * for layer-0 transactions).
 */
data class PreparationStep(
    val id: Long,
    val layer: Int,
    val index: Int,
    val broadcastHeight: Long,
    val dependsOn: List<Long>,
)

/**
 * Full migration schedule returned by [OrchardMigrationSdk.proposeMigrationTransfers].
 * Shown to the user for review before [OrchardMigrationSdk.signAndStoreMigrationSchedule]
 * is called. After sign+store, individual transfers do not require per-send confirmation.
 *
 * [proposalHandle] identifies the SDK-native cached plan this schedule was rendered from — see
 * [NoteSplitProposal.proposalHandle] for the contract. The transfer fields here are for display;
 * signing passes only the handle back, so the native side signs exactly the identified plan.
 */
data class MigrationSchedule(
    val transfers: List<TransferProposal>,
    val estimatedDurationHours: Int,
    val proposalHandle: Long,
    val preparations: List<PreparationStep> = emptyList(),
)

/**
 * The result of feeding one scanned QR frame to the Keystone batch-signing UR decoder —
 * see [OrchardMigrationSdk.decodeKeystoneSignBatchPart].
 *
 * [complete] is `false` while more frames are still expected — [progress] (0–100) tracks how far
 * the multi-part scan has gotten. Once `complete` is `true`, [data] carries the decoded batch
 * signing response bytes to pass to [OrchardMigrationSdk.applyKeystoneBatchSignatures]; `null`
 * otherwise.
 *
 * [firmwareVersion] is the signing device's raw `[major, minor, build]` firmware version — also
 * non-null exactly when [complete]. This comes straight from the `zcash-batch-sig-result` UR
 * envelope, not from any signed PCZT: the batch protocol returns signatures only and never echoes
 * PCZT bytes back, so this field is the only way to learn the device's firmware version for a
 * batch-signed migration. Callers should check this **before** relying on any PCZT-embedded
 * firmware stamp (that mechanism belongs to the single-transaction Keystone sign flow and will
 * never be present on a batch-reconstructed PCZT).
 */
data class KeystoneBatchDecodeResult(
    val complete: Boolean,
    val progress: Int,
    val data: ByteArray?,
    val firmwareVersion: ByteArray?
)

/**
 * The signed-but-unproven PCZT bytes produced by [OrchardMigrationSdk.applyKeystoneBatchSignatures]
 * — [splitSignedPczt] is `null` iff no split PCZT was included in the batch;
 * [transferSignedPczts] is in the same order the unsigned PCZTs were passed to
 * [OrchardMigrationSdk.buildKeystoneSignBatchQrParts]. Pass [splitSignedPczt] to
 * [OrchardMigrationSdk.storeSignedNoteSplitPczt] and [transferSignedPczts] (paired back up with
 * their transfer ids) to [OrchardMigrationSdk.storeSignedSchedulePczts].
 */
data class KeystoneBatchSignedPczts(
    val splitSignedPczt: ByteArray?,
    val transferSignedPczts: List<ByteArray>
)

/**
 * The live, persisted status of one committed migration transaction (transfer or preparation) —
 * [id] matches [TransferProposal.id] (the real, stable `MigrationTransferId`), NOT its position in
 * [MigrationSchedule.transfers]: the engine assigns real transfer ids in its own funding-note/
 * crossing order, while array position there is sorted by broadcast height — ZIP 318 deliberately
 * shuffles those two orderings apart, so a caller must correlate by [id], never by array index.
 *
 * [isTransfer] is false for preparation (note-split layer) transactions — display-facing
 * consumers filter on it or correlate by id (prep ids simply match no display row); scheduling
 * consumers stay kind-agnostic, because the engine serves due preparations for broadcast exactly
 * like transfers. [isProved] is true once the engine holds a proof (Proved/Broadcast/Mined).
 */
data class MigrationTransferState(
    val id: Long,
    val isTransfer: Boolean,
    val isSent: Boolean,
    val isProved: Boolean,
    val scheduledHeight: Long,
    /** Committed ZIP 318 anchor bucket boundary; null for preparations (natural anchor). */
    val anchorBoundaryHeight: Long?,
    /** Actionable RIGHT NOW per the engine's `transaction_statuses` ([action] says how). */
    val ready: Boolean = false,
    /** The action a ready transaction takes next, or null when waiting. */
    val action: MigrationNextAction? = null,
    /** Why a waiting transaction is not yet actionable, or null when none/ready. */
    val blocker: MigrationBlocker? = null,
    /** The engine-persisted crossing value in zatoshi; null for preparations. */
    val amountZatoshi: Long? = null,
    /** Preparation layer/index within the note-split tree; null for transfers. */
    val prepLayer: Int? = null,
    val prepIndex: Int? = null,
    /** Ids of the transactions that must mine before this one can build/broadcast. */
    val dependsOn: List<Long> = emptyList(),
    /** ZIP 203 expiry height; null = never expires. */
    val expiryHeight: Long? = null,
    /** Height this transaction mined at; null while unmined. */
    val minedHeight: Long? = null,
)

/**
 * The live schedule/status of every committed migration transaction — transfers AND preparations,
 * distinguished by [MigrationTransferState.isTransfer] — read straight from the engine's
 * persisted migration store (the single source of truth for the plan); see
 * [OrchardMigrationSdk.getMigrationTransferStates]. [tipHeight] is the wallet's current tip at
 * the time of the read, for converting [MigrationTransferState.scheduledHeight] into a wall-clock
 * estimate.
 */
data class MigrationTransferStates(
    val transfers: List<MigrationTransferState>,
    val tipHeight: Long
)

/** The action a READY migration transaction takes next — see [MigrationTransferState.action]. */
enum class MigrationNextAction { PROVE, BROADCAST }

/** Why a waiting migration transaction is not yet actionable — see [MigrationTransferState.blocker]. */
enum class MigrationBlocker {
    /** Waiting for its dependency transactions (earlier preparation layers) to mine. */
    DEPENDENCIES,

    /** Built and due only at a later height (the privacy broadcast schedule). */
    SCHEDULE,

    /** A transfer whose drawn anchor boundary has not yet settled below the tip. */
    ANCHOR_BOUNDARY,

    /** Awaiting an EXTERNAL signature — apply it via [OrchardMigrationSdk.applySignature]. */
    SIGNATURE,

    /** Its expiry height passed unmined — the rebuild path must reconstruct it. */
    EXPIRED,

    /**
     * SYNTHETIC (the backend's own guard veto, reported here only until the engine surfaces the
     * condition natively): a dependency mined PAST this transfer's drawn anchor boundary, so no
     * witness can ever be built against it. Needs a plan-level reschedule/rebuild; more syncing
     * can never help.
     */
    UNPROVABLE_ANCHOR,
}

/**
 * One decision of the engine state machine — what the app's migration worker does next.
 * The worker performs the corresponding I/O and asks again; it never chooses on its own.
 */
sealed class MigrationAdvanceStep {
    /** Sync + prove now (the given transaction's anchor is resolvable). */
    data class Prove(val transferId: Long) : MigrationAdvanceStep()

    /** Broadcast the given already-proven transaction (its scheduled height arrived). */
    data class Broadcast(val transferId: Long) : MigrationAdvanceStep()

    /** The given transfer expired unmined — reconstruct + re-sign it (needs spend authority). */
    data class Rebuild(val transferId: Long) : MigrationAdvanceStep()

    /** Nothing actionable right now — re-arm from [OrchardMigrationSdk.syncWakeupSchedule]. */
    object Waiting : MigrationAdvanceStep() {
        override fun toString() = "Waiting"
    }

    /** Every transaction is mined. */
    object Complete : MigrationAdvanceStep() {
        override fun toString() = "Complete"
    }
}

/** One sync/prove wake-up of the engine's schedule: wake at [height], prove [covers]. */
data class MigrationSyncWakeup(
    val height: Long,
    val covers: List<Long>,
)

/**
 * The signer's per-ROUND action budget and per-transaction-kind weights, from the engine's
 * `signing_rounds` module: one signing round (one QR interaction) may cover at most [maxActions]
 * TOTAL Orchard actions summed across all its transactions — a note-split preparation weighs
 * [preparationActions] (16), a migration transfer weighs [transferActions] (3). Today's Keystone
 * budget is 96 → 26 transfers alongside a split, 32 without.
 */
data class KeystoneSigningRoundBudget(
    val maxActions: Int,
    val preparationActions: Int,
    val transferActions: Int,
)

/** One preparation transaction's unsigned PCZT with its position in the note-split tree. */
data class UnsignedPreparationPczt(
    val id: Long,
    val layer: Int,
    val index: Int,
    val pcztBytes: ByteArray,
)

/**
 * The completed migration's summary, read straight from the ENGINE's persisted migration data (the
 * single source of truth), for the Migration Complete screen — see
 * [OrchardMigrationSdk.getMigrationSummary]. Unlike the app-side plan (cleared on completion), this
 * survives the migration finishing.
 *
 * [totalMigratedZatoshi] is the sum of every per-transfer CROSSING value — what actually arrived in
 * Ironwood. It is LESS than the balance that left Orchard, by the migration fees. [transferCount] is
 * the number of MINED transfers. [firstMinedEpochSeconds]/[lastMinedEpochSeconds] bound their mined
 * blocks' timestamps, for the elapsed-duration display.
 */
data class MigrationSummary(
    val totalMigratedZatoshi: Long,
    val transferCount: Int,
    val firstMinedEpochSeconds: Long,
    val lastMinedEpochSeconds: Long,
)

/**
 * Live migration progress used by the progress UI.
 * [nextTransferReadyAtHeight] is null when all transfers are complete or migration
 * has not started yet.
 */
data class MigrationProgress(
    val completedTransfers: Int,
    val totalTransfers: Int,
    val nextTransferReadyAtHeight: Long?
)

/**
 * Top-level migration state machine.
 *
 * NotStarted
 *   → SplitPendingConfirmation  (if split needed, split tx submitted)
 *   → ReadyToPropose            (split confirmed, or split not needed)
 *   → InProgress                (schedule signed and stored)
 *   → RequiresAttention         (transfer invalid/expired, user must act)
 *   → Complete
 */
sealed class MigrationState {
    /** No migration has been initiated. Show migration entry point. */
    object NotStarted : MigrationState()

    /** Note split transaction submitted, waiting for on-chain confirmation (~1 block). */
    object SplitPendingConfirmation : MigrationState()

    /** Split confirmed (or was not needed). Ready to call proposeMigrationTransfers(). */
    object ReadyToPropose : MigrationState()

    /** Migration schedule is committed and transfers are executing. */
    data class InProgress(
        val progress: MigrationProgress
    ) : MigrationState()

    /**
     * A transfer cannot proceed automatically. App must surface a non-error prompt
     * and call restartCurrentMigrationStep() after user acknowledges.
     */
    data class RequiresAttention(
        val reason: AttentionReason
    ) : MigrationState()

    /** All transfers confirmed on-chain. Orchard balance is zero. */
    object Complete : MigrationState()
}

sealed class AttentionReason {
    /** Input note was spent externally before the migration transfer was broadcast. */
    data class InvalidTransfer(
        val transferId: Long
    ) : AttentionReason()

    /** Transaction anchor expired before broadcast (e.g. extended offline period). */
    object TransferExpired : AttentionReason()

    /**
     * A transfer produced change back to Orchard. That change must be synced before
     * the next transfer can spend it. App should trigger sync, then resume execution.
     */
    object SyncRequiredBeforeNext : AttentionReason()
}

/**
 * Result of a broadcast attempt. The app maps each case to a specific UI/retry action —
 * do not collapse these into a generic error.
 */
sealed class TransferResult {
    data class Success(
        val txId: String
    ) : TransferResult()

    /**
     * Transient network failure. Retry in the next WorkManager window while [retryable] and the
     * caller's own attempt budget allows it.
     *
     * @param isTorFailure true when this failure specifically originated from Tor circuit
     * setup/bootstrap rather than a generic network/gRPC problem — callers use this to route to
     * Tor-specific recovery UI (e.g. "continue without Tor") instead of the generic failure path.
     */
    data class NetworkError(
        val retryable: Boolean,
        val isTorFailure: Boolean = false
    ) : TransferResult()

    /**
     * Input note is already spent. Sets MigrationState to RequiresAttention.
     * App must call restartCurrentMigrationStep() for the remaining balance.
     */
    object InvalidNote : TransferResult()

    /**
     * Transaction anchor height has expired. Same handling as InvalidNote.
     * Call restartCurrentMigrationStep().
     */
    object Expired : TransferResult()
}

sealed class TransferAttemptOutcome {
    object NothingDue : TransferAttemptOutcome()
    data class AwaitingProof(val transferId: Long) : TransferAttemptOutcome()
    data class Executed(val result: TransferResult) : TransferAttemptOutcome()
}

// ─── Main interface ───────────────────────────────────────────────────────────

interface OrchardMigrationSdk {
    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Current state of the migration. App calls this on every launch and after
     * every SDK operation to decide which screen to show.
     *
     * Consider exposing as Flow<MigrationState> if the Rust bridge supports it —
     * that removes the need for manual polling.
     *
     * Implementation note (Rust bridge, 2026-07-15): this and the other state-query methods below
     * (`getMigrationProgress`, `isNoteSplitNeeded`, `hasOverdueTransfers`, `hasInvalidTransfers`)
     * are `suspend` — not the synchronous calls their original signatures implied — because the
     * real implementation opens a `MigrationContext` against the wallet's SQLite database on every
     * call. The mock's answers were free (in-memory state), so the original interface didn't need
     * `suspend` here; the real bridge does.
     *
     * Implementation note (2026-07-23): `isSyncRequiredBeforeNextTransfer()` used to be listed
     * alongside these — it was removed as dead code (a Rust JNI call that always returned `false`,
     * with no other logic behind it). [isSyncBlocked]'s `next_broadcastable`-driven overdue check
     * already provides the real ZIP 318 sync/broadcast decoupling; there was nothing this method
     * contributed that isn't already covered there.
     */
    suspend fun getMigrationState(): MigrationState

    /** Convenience accessor for progress details when state is InProgress. */
    suspend fun getMigrationProgress(): MigrationProgress?

    /**
     * How many successive migration runs the account's current Orchard balance would need, given
     * the engine's per-run note cap — a read-only, stateless preview with no memory of prior
     * calls or rounds already committed. Callers must call this fresh every time they need it
     * (e.g. on every entry to the migration Review screen); the answer reflects whatever balance
     * remains right now, not a running count from when a multi-round campaign started. `null` when
     * no account is bound yet.
     */
    suspend fun estimateMigrationRunCount(): Int?

    // ── Note splitting ───────────────────────────────────────────────────────

    /**
     * Returns true if the current Orchard notes must be split before migration.
     * Note splitting is mandatory — do not proceed to proposeMigrationTransfers()
     * without splitting first if this returns true.
     */
    suspend fun isNoteSplitNeeded(): Boolean

    /**
     * SDK computes the optimal split (number of notes, sizes, randomisation).
     * Returns a proposal for user review. Nothing is broadcast until submitNoteSplit().
     */
    suspend fun prepareNoteSplit(): NoteSplitProposal

    /**
     * Broadcasts the note-split transaction. State transitions to SplitPendingConfirmation.
     * The split is a wallet-internal send (no external receiver).
     *
     * Implementation note (Rust bridge, 2026-07-10): the Rust `MigrationContext` splits this
     * into three separate steps — `sign_note_split(proposal, usk)` (returns a `PreparedTransfer`:
     * txid + PCZT bytes, nothing broadcast yet), `extract_broadcast_tx(pczt_bytes)` (PCZT →
     * consensus tx bytes), then the SDK's *existing* transaction-submission path (the same one
     * ordinary sends use) to actually broadcast, and finally `record_transfer_result(id, result)`
     * to tell the engine the outcome. This Kotlin method's real implementation composes all four
     * calls and maps the outcome to [TransferResult] — the app only ever sees the single
     * broadcast-and-record round trip.
     *
     * Open question: should this accept NetworkPrivacyOptions?
     * The split is on-chain visible, so routing through Tor may be desirable for
     * large balances. Defaulting to no Tor for simplicity for now.
     *
     * Implementation note (Rust bridge, 2026-07-15): [usk] is required because `sign_note_split`
     * signs the split transaction — sdk-lib never stores or derives a spending key itself, it only
     * ever receives one per-call from the caller, the same boundary
     * [Synchronizer.createProposedTransactions] already establishes for ordinary sends.
     */
    suspend fun submitNoteSplit(proposal: NoteSplitProposal, usk: UnifiedSpendingKey): TransferResult

    // ── External signer (Keystone hardware wallet) ───────────────────────────

    /**
     * Builds the note-split transaction of the plan [proposal] identifies as an unsigned PCZT
     * for an external signer — the [submitNoteSplit] equivalent for callers with no software
     * spending key. Nothing is broadcast; pass the device's returned signature to
     * [storeSignedNoteSplitPczt]. Throws if [proposal]'s plan has been superseded by a later
     * propose/prepare call (see [NoteSplitProposal.proposalHandle]).
     */
    suspend fun createUnsignedNoteSplitPczt(proposal: NoteSplitProposal): ByteArray

    /**
     * Accepts the externally-signed note-split PCZT, finalizes it, and broadcasts it — the
     * back half of [createUnsignedNoteSplitPczt], mirroring [submitNoteSplit]'s composition
     * (extract → broadcast via the existing submission path → record result) exactly.
     */
    suspend fun storeSignedNoteSplitPczt(signedPczt: ByteArray, options: NetworkPrivacyOptions): TransferResult

    /**
     * Builds one unsigned PCZT per transfer of `schedule` for an external signer — the
     * [signAndStoreMigrationSchedule] equivalent for callers with no software spending key.
     * Returns `(transfer id, unsigned PCZT bytes)` pairs; the pairing must survive to
     * [storeSignedSchedulePczts], which matches signed PCZTs back to these by id.
     *
     * Self-funding transfers (the common case) use the same sign-now/prove-later
     * placeholder-witness scheme [signAndStoreMigrationSchedule] does — callers do not need to
     * wait for the note-split to confirm on-chain before calling this either.
     */
    suspend fun createUnsignedTransferPczts(schedule: MigrationSchedule): List<Pair<Long, ByteArray>>

    /**
     * Every PREPARATION transaction's unsigned, ZIP32-annotated PCZT — the WHOLE note-split tree.
     * The engine builds all layers at commit (a layer's spends resolve against the previous
     * layer's just-built outputs), so sign-now/prove-later covers the entire tree and ONE
     * external-signer ceremony can pre-sign everything. [createUnsignedNoteSplitPczt] still
     * returns only the first layer-0 split (the immediate-broadcast special case) — preparations
     * beyond it come from here and their signed results go back through the kind-agnostic
     * [storeSignedSchedulePczts]. Same proposal-handle contract as [createUnsignedTransferPczts].
     */
    suspend fun createUnsignedPreparationPczts(schedule: MigrationSchedule): List<UnsignedPreparationPczt>

    /**
     * Accepts the full set of externally-signed transfer PCZTs — **all-or-nothing**, matched back
     * to their staged unsigned originals by id (from [createUnsignedTransferPczts]) — and persists
     * the committed schedule. No broadcast happens here (mirrors [signAndStoreMigrationSchedule]'s
     * role): [finalizeReadyTransfers] later completes any transfer that was staged awaiting proof,
     * exactly as it already does for the software-signing path.
     */
    suspend fun storeSignedSchedulePczts(signed: List<Pair<Long, ByteArray>>)

    /**
     * Builds the animated multi-part QR frames for one combined Keystone batch-signing request
     * covering the optional note-split PCZT (pass `null` when [isNoteSplitNeeded] is `false`) and
     * every schedule transfer's unsigned PCZT, in that order — so split and schedule sign in a
     * single device round trip rather than two. [requestId] is an opaque correlation token (e.g. a
     * UUID's bytes) the device round-trips, checked by [decodeKeystoneSignBatchPart].
     *
     * Pure PCZT/UR encoding — no wallet-database access, unlike almost every other method here.
     */
    suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        maxFragmentLen: Int
    ): List<String>

    /**
     * Discards any in-flight multi-part Keystone batch-signing scan session. Call on scan-screen
     * entry so a new attempt always starts from a clean slate.
     */
    suspend fun resetKeystoneSignBatchDecoder()

    /**
     * Feeds one scanned QR frame into the active (or a freshly started) Keystone batch-signing
     * decode session. [expectedRequestId] must match [buildKeystoneSignBatchQrParts]'s
     * `requestId` — a mismatch (or any other decode error) resets the session; call
     * [resetKeystoneSignBatchDecoder] before retrying.
     */
    suspend fun decodeKeystoneSignBatchPart(part: String, expectedRequestId: ByteArray): KeystoneBatchDecodeResult

    /**
     * Applies a completed Keystone batch-signing response ([KeystoneBatchDecodeResult.data] once
     * `complete`) back to the retained unsigned PCZTs — in the exact split-then-transfers order
     * they were passed to [buildKeystoneSignBatchQrParts] — producing signed-but-unproven PCZT
     * bytes for each, ready for [storeSignedNoteSplitPczt]/[storeSignedSchedulePczts].
     */
    suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: List<ByteArray>,
        batchSignResponse: ByteArray
    ): KeystoneBatchSignedPczts

    // ── Migration proposal ───────────────────────────────────────────────────

    /**
     * Generates the full migration schedule. Call only when state is ReadyToPropose.
     *
     * SDK decides: number of transfers, randomised amounts, anchor heights
     * (from 288-block buckets), and nextExecutableAfterHeight per transfer.
     * App shows this to the user for one-time confirmation before signing.
     *
     * [includeResidual] — when the spendable balance leaves a leftover too small to form a whole
     * migratable note but too large to be dust (see the Rust bridge's `residual_after_migration()`
     * threshold, ~0.001 ZEC), that leftover is excluded from the schedule by default: migrating it
     * requires one extra, non-round-number transfer, which is more identifiable on-chain than the
     * power-of-ten crossings (a privacy/completeness trade-off — see ProposalA.md's "sub-1-ZEC"
     * open point). Pass `true` only once the app exposes this as an explicit user choice; **for
     * now, always pass `false`** — the opt-in UI for this does not exist yet.
     */
    suspend fun proposeMigrationTransfers(includeResidual: Boolean = false): MigrationSchedule

    /**
     * Proposes the migration schedule directly from a note-split's own output plan
     * ([NoteSplitProposal] returned by [prepareNoteSplit]) — use this instead of
     * [proposeMigrationTransfers] whenever a split is about to run or just ran (i.e. whenever
     * [isNoteSplitNeeded] returned `true`), so every crossing value is guaranteed to match a note
     * the split actually produces.
     *
     * [proposeMigrationTransfers] and [prepareNoteSplit] each compute their own denomination plan
     * independently over the same balance, and are not guaranteed to agree — a schedule built from
     * [proposeMigrationTransfers] before the split has run can end up needing a note the split
     * never actually mints, which then silently (and incorrectly) falls back to an unrelated
     * already-existing note — one the split's own "sweep every spendable note" construction may
     * already be consuming as one of its own inputs, a double-spend once the split mines. Deriving
     * the schedule from the split's own plan instead makes this class of mismatch structurally
     * impossible.
     *
     * This never re-plans: it renders the schedule of the exact SDK-native cached plan
     * [splitProposal] identifies (via [NoteSplitProposal.proposalHandle]) — the same plan whose
     * split the user was just shown — and throws if that plan is missing or superseded. The
     * returned [MigrationSchedule] carries the SAME handle, so split view, schedule view, and the
     * eventual [submitNoteSplit]/[signAndStoreMigrationSchedule] all refer to one plan.
     */
    suspend fun proposeMigrationTransfersFromSplit(splitProposal: NoteSplitProposal): MigrationSchedule

    /**
     * Proposes an ordinary send-max transaction sweeping all spendable Orchard funds into this
     * account's own Ironwood receiver — bypassing the migration engine entirely. Call only when
     * state is ReadyToPropose, as an alternative to [proposeMigrationTransfers] for users who
     * explicitly opt out of the privacy-preserving schedule.
     *
     * Unlike [proposeMigrationTransfers]/[proposeMigrationTransfersFromSplit], the result is never
     * persisted as migration state (no `MigrationState` row is read or written): sign and submit
     * it exactly like an ordinary send (see the app's `SubmitProposalUseCase`/proposal repository
     * machinery), not through [signAndStoreMigrationSchedule].
     *
     * Implementation note (Rust bridge, 2026-07-23): backed by
     * `migration_engine::propose_immediate_send_max`, proto-encoded exactly like an ordinary
     * send's proposal (`RustBackend.proposeTransfer`'s encoding) rather than a migration-specific
     * type — this replaces the old, engine-routed `MigrationSchedule`-returning version.
     */
    suspend fun proposeImmediateMigration(): Proposal

    /**
     * User has confirmed the schedule. SDK signs all transactions and stores them
     * in the database via the existing transaction-resubmission infrastructure.
     * State transitions to InProgress. Individual transfers no longer need per-send
     * confirmation.
     *
     * After this call, app reads nextExecutableAfterHeight from each TransferProposal
     * to schedule WorkManager tasks.
     *
     * Implementation note (Rust bridge, 2026-07-15): [usk] is required for the same reason as
     * [submitNoteSplit]'s — `sign_and_store_migration_schedule` signs every transfer in the
     * schedule.
     *
     * Implementation note (Rust bridge, 2026-07-18, sign-now/prove-later): this signs every
     * transfer immediately, even one whose funding note (a not-yet-mined note-split output) isn't
     * witnessed yet — it defers the proof for such transfers rather than requiring them to wait.
     * Callers do not need to wait for note-split to confirm on-chain before calling this. See
     * [finalizeReadyTransfers] for how those deferred-proof transfers are later completed.
     *
     * Only [schedule]'s [MigrationSchedule.proposalHandle] crosses to the native side — the
     * schedule's display fields are never echoed back. The native side signs exactly the cached
     * plan the handle identifies, and throws if a later propose/prepare call superseded it (in
     * which case re-propose and show the user the new schedule before retrying).
     */
    suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule, usk: UnifiedSpendingKey)

    // ── Background execution ─────────────────────────────────────────────────

    /**
     * Completes every pre-signed transfer that is awaiting a proof (its funding note — a
     * not-yet-mined note-split output — was not yet witnessed at signing time) and whose funding
     * note has since become witnessed: attaches the note's real witness and anchor, runs the
     * prover, and makes the transfer eligible for [executeNextPendingTransfer] from then on.
     *
     * Idempotent and cheap to call redundantly — returns `0`, **not** an error, whenever there is
     * nothing awaiting a proof yet or every awaiting transfer's funding note is still unwitnessed;
     * that is the ordinary, expected steady state while a note-preparation output is still mining,
     * not a failure. Callers do not need to guard calls to this with [isNoteSplitNeeded] or any
     * other state check first.
     *
     * WorkManager task should call this before [executeNextPendingTransfer] on every run, so a
     * funding note that became witnessed since the last run gets finalized to broadcastable in the
     * same session that might then immediately find and broadcast it. There is no separate
     * pre-transfer sync gate to check first — [isSyncBlocked]'s `next_broadcastable`-driven overdue
     * check (see that method's doc) already keeps sync and broadcast decoupled in both directions,
     * so this task's three-step sequence runs unconditionally.
     *
     * Implementation note (Rust bridge, 2026-07-18): backed by
     * `MigrationContext::finalize_ready_transfers`, added alongside the sign-now/prove-later
     * pipeline change to `signAndStoreMigrationSchedule` (see that method's implementation note) —
     * that change lets signing succeed immediately even when a transfer's funding note isn't
     * witnessed yet; this method is what later completes such a transfer once its note is.
     */
    suspend fun finalizeReadyTransfers(): Int

    /**
     * Broadcasts the next pending transfer. App does not need to track which transfer
     * is next — the SDK manages internal ordering.
     *
     * Returns null if there is nothing to execute (all done or none stored yet).
     *
     * Called from: WorkManager Worker (Android) / BGTaskScheduler task (iOS).
     * Sync must NOT be triggered inside the same background session as this call.
     *
     * [options] — Tor and submission endpoint settings chosen by the user during
     * the confirmation flow. Store and pass through from the committed schedule.
     *
     * Implementation note (Rust bridge, 2026-07-10): same three-call composition as
     * [submitNoteSplit] — `next_due_transfer()` (returns `None` when nothing is due, mapped to
     * this method's `null`), `extract_broadcast_tx(pczt_bytes)`, submit via the existing
     * transaction-submission path honoring [options], then `record_transfer_result(id, result)`.
     * A transfer that is due but not yet broadcast is returned by `next_due_transfer()` again on
     * every call — there is no separate "claim" step, so a retried/interrupted call is safe to
     * simply call again.
     */
    suspend fun executeNextPendingTransfer(
        options: NetworkPrivacyOptions,
        useEstimatedTip: Boolean
    ): TransferAttemptOutcome

    // ── Sync coordination ────────────────────────────────────────────────────

    /**
     * True whenever wallet sync must not run: a transfer is overdue (its
     * nextExecutableAfterHeight has passed but it hasn't broadcast), or a transfer was just
     * broadcast via the immediate ("send now") path and is still inside its post-broadcast
     * privacy buffer (see [privacySyncBufferDuration]).
     *
     * The SDK owns this decision entirely and re-derives it from its own persisted migration
     * state — the app does not toggle this, it only observes it (typically by feeding it
     * straight into the synchronizer's own construction/gating, the same way isTorEnabled
     * already works). This guarantees blocking can never get "stuck" from a forgotten resume
     * call, since there is no imperative resume call to forget.
     *
     * Implementation note (Rust bridge, 2026-07-10): "SDK owns this" means the **Kotlin**
     * implementation of [OrchardMigrationSdk], not the Rust `MigrationContext` — the Rust engine
     * has no concept of sync-blocking or a resume buffer at all (that's a network/timing-privacy
     * technique for de-correlating sync traffic from broadcast traffic, layered on top of the
     * migration engine, not part of it). The Kotlin implementation derives the "overdue" half from
     * `has_overdue_transfers()` (a real Rust call) and owns the "post-broadcast buffer" half
     * itself (e.g. a persisted resume-at timestamp, as the current mock already does via
     * `MigrationSyncResumeAtStorageProvider`) — no Rust call backs that half.
     */
    fun isSyncBlocked(): Flow<Boolean>

    /**
     * How long sync must stay blocked after a transfer is broadcast via the immediate
     * ("send now") path, to decouple broadcast timing from sync-resume timing for privacy.
     * SDK-owned so this stays consistent with whatever cadence the SDK actually schedules
     * transfers at — the app only displays this value, it does not compute it.
     *
     * Implementation note (Rust bridge, 2026-07-10): a Kotlin-side constant/config, same as
     * [isSyncBlocked]'s buffer half — not backed by any Rust call.
     */
    fun privacySyncBufferDuration(): Duration

    // ── On-launch reconciliation ─────────────────────────────────────────────

    /**
     * True if one or more scheduled transfers are past their nextExecutableAfterHeight
     * but have not been broadcast yet. App shows the fallback prompt on launch.
     *
     * This is the primary catch-up mechanism — do not rely on notification delivery.
     *
     * [useEstimatedTip] — when true, supplies an estimated current chain tip height computed from
     * the latest scanned block + elapsed wall-clock time (75 s/block), so a transfer whose
     * `nextExecutableAfterHeight` was crossed after the last sync is correctly detected as overdue
     * even before the next sync updates the tip. Pass `false` to use only the synced tip (-1L),
     * which avoids false positives but may miss transfers that became due since the last sync.
     */
    suspend fun hasOverdueTransfers(useEstimatedTip: Boolean = false): Boolean

    /**
     * Reconciles any transfers whose input notes have been invalidated (double-spent or expired)
     * against the engine's persisted state. Returns `true` if any transfer was invalidated and
     * the migration state transitioned to [MigrationState.RequiresAttention].
     */
    suspend fun reconcileInvalidations(): Boolean

    /**
     * Returns the estimated current chain tip height, computed from the latest scanned block
     * plus elapsed wall-clock time at a 75-second average block interval. Returns -1 when no
     * block has been scanned yet.
     */
    suspend fun estimatedChainTip(): Long

    /**
     * Measured average seconds-per-block over the recently scanned window (clamped; 75s fallback).
     * Use for every height-to-wall-clock projection — testnet's minimum-difficulty bursts make
     * the 75s constant a large overestimate there.
     */
    suspend fun estimatedSecondsPerBlock(): Long

    /**
     * True if any stored transfer is in an invalid state (spent note or expired anchor).
     * App shows the RequiresAttention screen. Call restartCurrentMigrationStep() after
     * user acknowledges.
     *
     * Detection condition: orchardBalance > 0 AND no valid queued migration transaction.
     */
    suspend fun hasInvalidTransfers(): Boolean

    // ── Invalidity recovery ──────────────────────────────────────────────────

    /**
     * Called when state is RequiresAttention. SDK invalidates the broken transfer,
     * re-evaluates the remaining unspent Orchard notes, and returns a new schedule
     * for the remainder of the balance.
     *
     * The returned MigrationSchedule must go through the normal user confirmation
     * flow → signAndStoreMigrationSchedule() — same as the first time.
     *
     * [includeResidual] — should match whatever choice the user made for
     * [proposeMigrationTransfers] on the schedule being recovered (**pass `false`** until the
     * opt-in UI for that exists — see [proposeMigrationTransfers]'s doc).
     */
    suspend fun restartCurrentMigrationStep(includeResidual: Boolean = false): MigrationSchedule

    /**
     * The live, persisted status of EVERY committed migration transaction — transfers AND
     * preparations, distinguished by [MigrationTransferState.isTransfer] — read straight from the
     * engine's migration store, the single source of truth for the plan (unlike an app-side cache
     * populated once at propose/commit time). Display-facing consumers filter on `isTransfer` or
     * correlate by stable id; scheduling consumers stay kind-agnostic, since the engine serves due
     * preparations for broadcast exactly like transfers. Returns `null` if there's no in-progress
     * migration.
     */
    suspend fun getMigrationTransferStates(): MigrationTransferStates?

    /**
     * The engine state machine's single "what now?" answer (guarded `next_step`): the app worker
     * performs exactly this and asks again. `null` when no migration is in progress. Broadcast
     * timing uses the estimated (real) chain tip — a proved, due transfer returns
     * [MigrationAdvanceStep.Broadcast] directly, broadcast-first over a ready prove (retention-
     * justified); proving stays on the scanned tip (a witness needs a real settled checkpoint).
     * Broadcast execution itself stays [executeNextPendingTransfer].
     */
    suspend fun nextStep(): MigrationAdvanceStep?

    /**
     * The engine's minimal sync/prove wake-up schedule (windows, minimality, jitter,
     * immediate-overdue) — the ONLY source of sync wake heights for the app worker. `null` when
     * no migration is in progress; empty when nothing needs proving.
     */
    suspend fun syncWakeupSchedule(): List<MigrationSyncWakeup>?

    /**
     * Stores an externally signed PCZT for one transaction (`AwaitingSignature → Signed`) via the
     * engine's `apply_signature` — the state-machine contract for the Keystone flow. Returns
     * whether the state changed.
     */
    suspend fun applySignature(transferId: Long, signedPczt: ByteArray): Boolean

    /**
     * The engine's Keystone signing-round budget ([KeystoneSigningRoundBudget]) — how many TOTAL
     * Orchard actions one QR signing round (one device interaction) may cover, plus the per-kind
     * action weights. Sourced from `zcash_pool_migration::signing_rounds` so the app never
     * hardcodes device limits.
     */
    suspend fun keystoneSigningRoundBudget(): KeystoneSigningRoundBudget

    /**
     * The completed migration's real summary — total migrated (crossing values), mined-transfer
     * count, and first/last mined block times — read straight from the ENGINE's persisted migration
     * data, the single source of truth that survives completion (unlike the app-side plan, which is
     * cleared once migration finishes). Used by the Migration Complete screen. Returns `null` when
     * there is no migration data or no mined transfer yet (the screen then falls back to zeros).
     */
    suspend fun getMigrationSummary(): MigrationSummary?

    // ── Dust locking ─────────────────────────────────────────────────────────

    /**
     * The zatoshi value below which a remaining post-migration Orchard balance counts as dust
     * (as opposed to a residual too large to ignore) — e.g. for the Migration Complete screen's
     * "is the leftover balance negligible" gate. 100,000 zatoshi (0.001 ZEC) as of this writing.
     * A fixed protocol-level constant (`MIGRATION_DUST_THRESHOLD_ZATOSHI` in `migration.rs`), not
     * derived from any wallet/account state — callers should still call this rather than hardcode
     * the value, so the app and the Rust engine can't drift apart on what counts as dust.
     */
    suspend fun migrationDustThresholdZatoshi(): Long

    /**
     * Marks whatever Orchard balance remains after migration (dust below the migratable
     * threshold, or an opted-out residual) as unspendable, so it can't later be swept into a
     * transaction that reveals its specific — and therefore identifying — amount.
     *
     * Backed by `zcash_client_backend`'s note-locking (`WalletWrite::lock_outputs`, librustzcash
     * PR #2716): the remaining spendable Orchard notes for this account are locked under a fixed,
     * well-known owner token with no practical expiry, so ordinary note selection (sends,
     * shielding, any future migration round) excludes them by default. Calling this again is
     * idempotent (same-owner re-locking just extends the existing lock).
     */
    suspend fun lockRemainingOrchardBalance()

    // ── Debug ─────────────────────────────────────────────────────────────────

    /**
     * DEBUG ONLY: abandons this account's in-progress migration (every preparation and transfer
     * transaction not yet broadcast, regardless of signing/proving state), so the next
     * propose/commit call starts completely fresh. The run is persisted as failed through the
     * engine store — the same cancellation shape any real failure leaves — so [getMigrationState]
     * reports RequiresAttention (not NotStarted) until a new run is committed over it. Not for
     * production use — exists purely as a debug-settings testing aid (e.g. re-running a migration
     * proposal with a shorter test schedule without waiting out or resuming the previous one).
     */
    suspend fun clearMigration()

    companion object {
        /**
         * Constructs the real, Rust-backed [OrchardMigrationSdk].
         *
         * Deliberately independent of [Synchronizer] — [WalletCoordinator]'s `isSyncBlocked` input
         * needs this *before* any `Synchronizer` exists (a `Synchronizer`-scoped factory would be
         * circular), so this only needs the same inputs [Synchronizer.new] itself takes.
         *
         * [lightWalletEndpoint] should be the same endpoint the app passes to [Synchronizer.new] —
         * there is no independent way for this factory to discover it (wallet/endpoint persistence
         * is an app-layer concern, not an SDK one).
         *
         * [NetworkPrivacyOptions.useTor] uses its own dedicated [cash.z.ecc.android.sdk.internal.model.TorClient]
         * (own on-disk directory, separate from the main `Synchronizer`'s), built lazily on first
         * use — this is a genuinely per-migration setting, independent of the app's global Tor
         * toggle.
         *
         * [account] is the wallet account this instance operates on — whichever account the
         * migration flow is actually running for (the app resolves this, e.g. from the currently
         * selected account; it is never auto-picked here). Pass `null` only for the one legitimate
         * case that has no account selection to make yet: gating [Synchronizer]-independent sync
         * (e.g. [WalletCoordinator]'s `isSyncBlocked` input) before any account is chosen — with a
         * `null` account, [OrchardMigrationSdk.isSyncBlocked] checks every account in the wallet
         * instead of assuming one, and every other method degrades to its "nothing to do" answer
         * or throws (see [cash.z.ecc.android.sdk.internal.OrchardMigrationSdkImpl]'s doc).
         */
        fun new(
            appContext: android.content.Context,
            zcashNetwork: cash.z.ecc.android.sdk.model.ZcashNetwork,
            lightWalletEndpoint: co.electriccoin.lightwallet.client.model.LightWalletEndpoint,
            account: cash.z.ecc.android.sdk.model.AccountUuid?,
            alias: String = cash.z.ecc.android.sdk.ext.ZcashSdk.DEFAULT_ALIAS
        ): OrchardMigrationSdk {
            val ctx = appContext.applicationContext
            return cash.z.ecc.android.sdk.internal.OrchardMigrationSdkImpl(
                context = ctx,
                network = zcashNetwork,
                alias = alias,
                account = account,
                migrationBackend =
                    cash.z.ecc.android.sdk.internal
                        .TypesafeMigrationBackendImpl(),
                chainTipEstimator =
                    cash.z.ecc.android.sdk.internal.LazyDataDbChainTipEstimator(
                        context = ctx,
                        network = zcashNetwork,
                        alias = alias,
                    ),
                defaultSubmitEndpoint = lightWalletEndpoint,
                preferenceProviderHolder =
                    cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider(
                        ctx
                    )
            )
        }
    }
}
