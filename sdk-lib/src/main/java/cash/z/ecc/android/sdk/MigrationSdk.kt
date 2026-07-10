package cash.z.ecc.android.sdk

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
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
 * - [rescheduleOverdueTransfer] needs no Rust call at all — a pre-signed transfer stays due until
 *   broadcast or expiry, so "rescheduling" is a purely local decision; its old doc incorrectly
 *   implied SDK-side persistence.
 * - `initializePostUpgrade()` was removed — unused anywhere in the app, and `MigrationContext`
 *   has no corresponding "post-upgrade init" step (everything is computed live from wallet state).
 * - [submitNoteSplit] / [executeNextPendingTransfer] are each a composition of several Rust calls
 *   (sign/fetch → extract → broadcast via the existing submission path → record result) — the
 *   Kotlin signatures are unchanged, but see their doc comments for the real implementation shape.
 * - [isSyncBlocked] / [privacySyncBufferDuration] are confirmed Kotlin-only (no Rust backing) —
 *   the sync/broadcast de-correlation technique they implement lives entirely in this SDK layer.
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

/**
 * SDK-generated note split proposal. The SDK decides the number of output notes,
 * their sizes, and randomisation — the app only shows this to the user for confirmation.
 *
 * Splitting into ~10 notes is the current target heuristic (20k ZEC cap per note).
 */
data class NoteSplitProposal(
    val outputNotes: List<Long>,  // amounts in zatoshi
    val fee: Long
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
    val id: String,
    val amountZatoshi: Long,
    val anchorHeight: Long,
    val nextExecutableAfterHeight: Long,
    val expiryHeight: Long
)

/**
 * Full migration schedule returned by [OrchardMigrationSdk.proposeMigrationTransfers].
 * Shown to the user for review before [OrchardMigrationSdk.signAndStoreMigrationSchedule]
 * is called. After sign+store, individual transfers do not require per-send confirmation.
 */
data class MigrationSchedule(
    val transfers: List<TransferProposal>,
    val estimatedDurationHours: Int
)

/**
 * Live migration progress used by the progress UI.
 * [nextTransferReadyAtHeight] is null when all transfers are complete or migration
 * has not started yet.
 */
data class MigrationProgress(
    val completedTransfers: Int,
    val totalTransfers: Int,
    val remainingOrchardZatoshi: Long,
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
    data class InProgress(val progress: MigrationProgress) : MigrationState()

    /**
     * A transfer cannot proceed automatically. App must surface a non-error prompt
     * and call restartCurrentMigrationStep() after user acknowledges.
     */
    data class RequiresAttention(val reason: AttentionReason) : MigrationState()

    /** All transfers confirmed on-chain. Orchard balance is zero. */
    object Complete : MigrationState()
}

sealed class AttentionReason {
    /** Input note was spent externally before the migration transfer was broadcast. */
    data class InvalidTransfer(val transferId: String) : AttentionReason()

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
    data class Success(val txId: String) : TransferResult()

    /** Transient network failure. Retry in the next WorkManager window. */
    data class NetworkError(val retryable: Boolean) : TransferResult()

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

// ─── Main interface ───────────────────────────────────────────────────────────

interface OrchardMigrationSdk {

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Current state of the migration. App calls this on every launch and after
     * every SDK operation to decide which screen to show.
     *
     * Consider exposing as Flow<MigrationState> if the Rust bridge supports it —
     * that removes the need for manual polling.
     */
    fun getMigrationState(): MigrationState

    /** Convenience accessor for progress details when state is InProgress. */
    fun getMigrationProgress(): MigrationProgress?

    // ── Note splitting ───────────────────────────────────────────────────────

    /**
     * Returns true if the current Orchard notes must be split before migration.
     * Note splitting is mandatory — do not proceed to proposeMigrationTransfers()
     * without splitting first if this returns true.
     */
    fun isNoteSplitNeeded(): Boolean

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
     */
    suspend fun submitNoteSplit(proposal: NoteSplitProposal): TransferResult

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
     * Proposes a single, unsplit, full-balance migration transfer for immediate broadcast —
     * no privacy-preserving multi-transfer split. The returned [MigrationSchedule] always
     * contains exactly one [TransferProposal] whose [TransferProposal.nextExecutableAfterHeight]
     * is immediately executable, so the caller broadcasts it right away via
     * [executeNextPendingTransfer] after [signAndStoreMigrationSchedule] — no WorkManager /
     * BGTaskScheduler background scheduling is needed for this path.
     *
     * Call only when state is ReadyToPropose, as an alternative to [proposeMigrationTransfers]
     * for users who explicitly opt out of the privacy-preserving schedule.
     */
    suspend fun proposeImmediateMigration(): MigrationSchedule

    /**
     * User has confirmed the schedule. SDK signs all transactions and stores them
     * in the database via the existing transaction-resubmission infrastructure.
     * State transitions to InProgress. Individual transfers no longer need per-send
     * confirmation.
     *
     * After this call, app reads nextExecutableAfterHeight from each TransferProposal
     * to schedule WorkManager tasks.
     */
    suspend fun signAndStoreMigrationSchedule(schedule: MigrationSchedule)

    // ── Background execution ─────────────────────────────────────────────────

    /**
     * Returns true if a sync is needed before the next transfer can be executed.
     * Happens when the previous transfer produced change back to Orchard — that
     * change must be confirmed and synced before it can be spent.
     *
     * WorkManager task should check this before calling executeNextPendingTransfer().
     * If true, task should exit and trigger a separate sync (not in the same session —
     * sync and broadcast must be decoupled in time).
     */
    fun isSyncRequiredBeforeNextTransfer(): Boolean

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
    suspend fun executeNextPendingTransfer(options: NetworkPrivacyOptions): TransferResult?

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
     */
    fun hasOverdueTransfers(): Boolean

    /**
     * Defers the current overdue transfer to a later execution window, instead of broadcasting it
     * right now via [executeNextPendingTransfer]. Unlike [restartCurrentMigrationStep], the
     * transfer itself is still validly signed (its note hasn't been spent, its anchor hasn't
     * expired) — only its window was missed — so this does not invalidate or re-sign anything.
     *
     * Implementation note (Rust bridge, 2026-07-10): there is **no Rust-side call** for this —
     * `MigrationContext` has no "reschedule" operation, because it doesn't need one. A pre-signed
     * transfer is due-and-broadcastable for as long as `TransferProposal.expiryHeight` allows;
     * `next_due_transfer()`/`executeNextPendingTransfer` will simply keep returning the *same*
     * transfer on every call until it's either broadcast or its expiry passes. "Rescheduling" is
     * therefore purely a local decision not to call [executeNextPendingTransfer] yet — the SDK
     * persists nothing new, it only computes and returns a later target time (e.g. the next
     * natural anchor-bucket boundary) for the app's WorkManager job. **The chosen time must still
     * be before the transfer's `expiryHeight`** — pushing past it isn't a valid reschedule; that
     * case is [hasInvalidTransfers]/[restartCurrentMigrationStep]'s job instead. (The previous
     * revision of this doc said the SDK "persists the new schedule" — that described the mock's
     * behavior, not the real bridge; callers relying on that persistence will need updating.)
     */
    suspend fun rescheduleOverdueTransfer(): TransferProposal

    /**
     * True if any stored transfer is in an invalid state (spent note or expired anchor).
     * App shows the RequiresAttention screen. Call restartCurrentMigrationStep() after
     * user acknowledges.
     *
     * Detection condition: orchardBalance > 0 AND no valid queued migration transaction.
     */
    fun hasInvalidTransfers(): Boolean

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
}
