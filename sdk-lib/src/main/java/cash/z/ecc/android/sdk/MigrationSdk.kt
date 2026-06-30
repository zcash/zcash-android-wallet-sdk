/*
 * Kotlin interface for the Orchard → Ironwood migration SDK bridge.
 *
 * Boundary: SDK owns all business logic (split algorithm, anchor height selection,
 * transaction signing, storage, invalidity detection). App owns UI, WorkManager
 * scheduling, permissions, and notifications.
 *
 * Based on: Orchard Migration Flow — Implementation Proposal (Path A)
 * Sync: 2026-06-17
 */
package cash.z.ecc.android.sdk

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
    val outputNotes: List<Long>, // amounts in zatoshi
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
        val transferId: String
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

    /** Transient network failure. Retry in the next WorkManager window. */
    data class NetworkError(
        val retryable: Boolean
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
     */
    suspend fun proposeMigrationTransfers(): MigrationSchedule

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
     */
    suspend fun executeNextPendingTransfer(options: NetworkPrivacyOptions): TransferResult?

    // ── On-launch reconciliation ─────────────────────────────────────────────

    /**
     * True if one or more scheduled transfers are past their nextExecutableAfterHeight
     * but have not been broadcast yet. App shows the fallback prompt on launch.
     *
     * This is the primary catch-up mechanism — do not rely on notification delivery.
     */
    fun hasOverdueTransfers(): Boolean

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
     */
    suspend fun restartCurrentMigrationStep(): MigrationSchedule

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Call on the first app open after the Ironwood network upgrade activates.
     * Sets the minimum anchor height for all migration transactions. Must be called
     * before any proposal or execution methods.
     */
    fun initializePostUpgrade()
}
