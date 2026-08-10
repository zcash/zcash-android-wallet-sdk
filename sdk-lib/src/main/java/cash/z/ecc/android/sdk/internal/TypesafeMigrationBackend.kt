@file:Suppress("LongParameterList")

package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.migration.JniDueTransferResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationTransferStates
import cash.z.ecc.android.sdk.internal.model.migration.JniNoteSplitProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparedTransfer
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedPreparationPczt
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedTransferPczt
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.ZcashNetwork

@Suppress("TooManyFunctions")
internal interface TypesafeMigrationBackend {
    suspend fun migrationState(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationState

    suspend fun migrationStateUnreconciled(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationState

    suspend fun migrationProgress(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationProgress?

    suspend fun isNoteSplitNeeded(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    suspend fun estimateMigrationRunCount(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int

    suspend fun lockRemainingOrchardBalance(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int

    suspend fun clearMigration(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int

    suspend fun hasOverdueTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        estimatedTip: Long = -1L
    ): Boolean

    suspend fun hasInvalidTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    /**
     * The mined block height of [txId] (internal byte order), or `-1` if unknown. See
     * [cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend.transactionMinedHeight]. No account
     * is needed — a txid is globally unique.
     */
    suspend fun transactionMinedHeight(
        dbDataPath: String,
        network: ZcashNetwork,
        txId: ByteArray
    ): Long

    suspend fun reconcileInvalidatedTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    suspend fun prepareNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniNoteSplitProposal

    /**
     * [proposalHandle] identifies the Rust-side cached plan to commit and sign — from the
     * [JniNoteSplitProposal] the user reviewed. Throws if that plan is missing or superseded by a
     * later propose/prepare call.
     */
    suspend fun signNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long,
        usk: ByteArray
    ): JniPreparedTransfer

    suspend fun extractBroadcastTx(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        pcztBytes: ByteArray
    ): ByteArray

    /**
     * [resultTag]: 0 = Success (requires [txId]), 1 = NetworkError (requires [retryable]),
     * 2 = InvalidNote, 3 = Expired, 4 = AwaitingReevaluation (genuinely-unknown broadcast
     * rejection, reported via `report_broadcast_failure` instead of terminally failing the
     * plan). [txId] is ignored except for tag 0 — pass an empty array otherwise. [observedTip]
     * is only meaningful for tag 4: a chain tip obtained from the network right after the
     * rejection; `-1` (the default) means no tip is available.
     */
    suspend fun recordTransferResult(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        transferId: Long,
        resultTag: Int,
        retryable: Boolean,
        txId: ByteArray,
        observedTip: Long = -1L
    )

    suspend fun proposeMigrationTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule

    /**
     * Renders the transfer schedule of the exact cached plan [proposalHandle] identifies (from
     * the [JniNoteSplitProposal] the user was just shown) — never re-plans, so the schedule shown
     * is guaranteed to belong to the same plan as the split. The returned schedule carries the
     * SAME handle.
     */
    suspend fun proposeMigrationTransfersFromSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long
    ): JniMigrationSchedule

    /**
     * Proposes an ordinary send-max transaction sweeping all spendable Orchard funds into this
     * account's own Ironwood receiver — bypasses the migration engine entirely. Returns the raw
     * proposal proto bytes, encoded exactly like an ordinary send's proposal, for decoding via
     * `cash.z.ecc.android.sdk.model.Proposal.fromByteArray`.
     */
    suspend fun proposeImmediateSendMax(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): ByteArray

    /**
     * Commits and signs the migration plan [proposalHandle] identifies (from the
     * [JniMigrationSchedule] the user reviewed). No schedule fields cross the boundary — the Rust
     * side signs exactly the identified plan or throws if it was superseded.
     */
    suspend fun signAndStoreMigrationSchedule(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long,
        usk: ByteArray
    )

    suspend fun finalizeReadyTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int

    suspend fun nextDueTransfer(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        estimatedTip: Long = -1L
    ): JniDueTransferResult

    suspend fun restartCurrentMigrationStep(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule

    /**
     * The live, persisted status of every committed transfer transaction, or `null` if there's
     * no in-progress migration. See [JniMigrationTransferStates].
     */
    suspend fun migrationTransferStates(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationTransferStates?

    /**
     * The guarded `next_step` driver read: `[stepCode, transferId, nextHeight, nextKind]`
     * (0 waiting, 1 prove, 2 broadcast, 3 rebuild, 4 complete, 5 replan, 6 reevaluate;
     * transferId = -1 for waiting/complete; nextHeight/nextKind = -1 when the engine's own
     * peek-ahead has nothing height-schedulable to report), or `null` with no migration.
     * Broadcast timing uses [estimatedTip]; proving, rebuild, and completion use the scanned
     * tip internally.
     */
    suspend fun nextStep(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        estimatedTip: Long
    ): LongArray?

    /** Rows of `[wakeHeight, coveredId...]` from the engine's sync wake-up schedule. */
    suspend fun syncWakeupSchedule(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Array<LongArray>?

    /** Applies an externally signed PCZT (`AwaitingSignature → Signed`); true when applied. */
    suspend fun applySignature(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        transferId: Long,
        signedPczt: ByteArray
    ): Boolean

    /**
     * Marks a Replan-requesting migration Superseded — see [OrchardMigrationSdkImpl.nextStep]'s
     * Replan handling. Returns whether it actually transitioned something (false if already
     * terminal or no migration exists).
     */
    suspend fun markMigrationSuperseded(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    /**
     * The engine's Keystone signing-round budget constants:
     * `[maxActionsPerRound, preparationActions, transferActions]`.
     */
    suspend fun keystoneSigningRoundBudget(): IntArray

    /**
     * The completed migration's summary from the engine's persisted migration data, as
     * `[totalMigratedZatoshi, transferCount, firstMinedEpochSeconds, lastMinedEpochSeconds]`, or an
     * EMPTY array when there is no migration data / no mined transfer yet. No account is needed —
     * the migration tables are wallet-scoped. See
     * [cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend.migrationSummary].
     */
    suspend fun migrationSummary(dbDataPath: String): LongArray

    /**
     * Lists every account's UUID in the wallet database, independent of any live `Synchronizer`.
     */
    suspend fun getAccountUuids(
        dbDataPath: String,
        network: ZcashNetwork
    ): List<AccountUuid>

    /**
     * The pending (due-or-not-yet-due) scheduled transfer's full proposal fields, or `null` if
     * nothing is scheduled yet (or only the note-split prep transaction is pending).
     */
    suspend fun pendingTransferProposal(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniTransferProposal?

    /**
     * The zatoshi value below which a leftover post-migration Orchard balance is treated as dust
     * rather than a residual worth migrating in its own transfer. A fixed protocol-level
     * constant, not derived from any wallet/account state.
     */
    suspend fun migrationDustThresholdZatoshi(): Long

    // ----- External signer (Keystone hardware wallet) -----

    suspend fun createUnsignedNoteSplitPczt(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long
    ): ByteArray

    suspend fun storeSignedNoteSplitPczt(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        signedPczt: ByteArray
    ): JniPreparedTransfer

    suspend fun createUnsignedTransferPczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long
    ): Array<JniUnsignedTransferPczt>

    suspend fun createUnsignedPreparationPczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        proposalHandle: Long
    ): List<JniUnsignedPreparationPczt>

    /**
     * [ids]/[pcztBytesList] are parallel arrays — signed PCZTs matched back to their staged
     * unsigned originals by id, not by array position (all-or-nothing across whatever set of ids
     * is provided here).
     */
    suspend fun storeSignedSchedulePczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        ids: LongArray,
        pcztBytesList: Array<ByteArray>
    )

    // ----- Keystone batch-signing UR bridge (no wallet database access) -----

    suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        maxFragmentLen: Int
    ): Array<String>

    suspend fun resetKeystoneSignBatchDecoder()

    suspend fun decodeKeystoneSignBatchPart(
        part: String,
        expectedRequestId: ByteArray
    ): JniKeystoneBatchDecodeResult

    suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        batchSignResponse: ByteArray
    ): JniKeystoneBatchSignedPczts
}
