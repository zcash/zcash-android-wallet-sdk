@file:Suppress("LongParameterList")

package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.SdkDispatchers
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
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedTransferPczt
import kotlinx.coroutines.withContext

/**
 * JNI bridge to the `zcash_pool_migration` crate.
 *
 * Unlike [VotingRustBackend], this holds no handle/registry state: `MigrationContext::new` is
 * cheap and every Rust-side call opens its own connection internally, so every method here is a
 * self-contained call keyed by [dbDataPath]/[networkId]/[accountUuidBytes].
 */
@Keep
@Suppress("TooManyFunctions")
class MigrationRustBackend private constructor() {
    @Throws(RuntimeException::class)
    suspend fun migrationState(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniMigrationState =
        withContext(SdkDispatchers.DATABASE_IO) {
            migrationStateNative(dbDataPath, networkId, accountUuidBytes)
                ?: error("migrationState returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun migrationProgress(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniMigrationProgress? =
        withContext(SdkDispatchers.DATABASE_IO) {
            migrationProgressNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun isNoteSplitNeeded(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            isNoteSplitNeededNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun estimateMigrationRunCount(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Int =
        withContext(SdkDispatchers.DATABASE_IO) {
            estimateMigrationRunCountNative(dbDataPath, networkId, accountUuidBytes)
        }

    /**
     * Locks whatever Orchard balance remains spendable for this account (dust below the
     * migratable threshold, or a residual the user opted out of migrating) so ordinary note
     * selection excludes it going forward. Returns the number of notes locked.
     */
    @Throws(RuntimeException::class)
    suspend fun lockRemainingOrchardBalance(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Int =
        withContext(SdkDispatchers.DATABASE_IO) {
            lockRemainingOrchardBalanceNative(dbDataPath, networkId, accountUuidBytes)
        }

    /**
     * DEBUG ONLY: abandons this account's in-progress migration (persisting it as failed through
     * the engine store), so the next propose/commit call starts completely fresh. The cancelled
     * run remains stored, so the migration state reads as RequiresAttention (not NotStarted)
     * until a new run is committed. Not exposed to production users. Returns 1 if an in-progress
     * run was cancelled, 0 if there was nothing to cancel.
     */
    @Throws(RuntimeException::class)
    suspend fun clearMigration(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Int =
        withContext(SdkDispatchers.DATABASE_IO) {
            clearMigrationNative(dbDataPath, networkId, accountUuidBytes)
        }

    /**
     * DEBUG ONLY: reschedules every not-yet-broadcast transfer in this account's migration to
     * become due in quick succession (first ~2.5 min out, then ~5 min apart), for manually testing
     * real broadcast execution without waiting out ZIP 318's privacy delay. Not exposed to
     * production users. Returns the number of transfers rescheduled.
     */
    @Throws(RuntimeException::class)
    suspend fun debugRescheduleTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Int =
        withContext(SdkDispatchers.DATABASE_IO) {
            debugRescheduleTransfersNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun rescheduleUnprovenTransfer(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        transferId: String
    ): Long =
        withContext(SdkDispatchers.DATABASE_IO) {
            rescheduleUnprovenTransferNative(dbDataPath, networkId, accountUuidBytes, transferId)
        }

    @Throws(RuntimeException::class)
    suspend fun hasOverdueTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        estimatedTip: Long
    ): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            hasOverdueTransfersNative(dbDataPath, networkId, accountUuidBytes, estimatedTip)
        }

    @Throws(RuntimeException::class)
    suspend fun hasInvalidTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            hasInvalidTransfersNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun prepareNoteSplit(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniNoteSplitProposal =
        withContext(SdkDispatchers.DATABASE_IO) {
            prepareNoteSplitNative(dbDataPath, networkId, accountUuidBytes)
                ?: error("prepareNoteSplit returned null")
        }

    /**
     * [proposalHandle] identifies the Rust-side cached plan to commit and sign — the one whose
     * proposal ([JniNoteSplitProposal.proposalHandle]) the user reviewed. Throws if that plan is
     * missing or has been superseded by a later propose/prepare call; the Rust side never signs
     * anything the handle doesn't identify.
     */
    @Throws(RuntimeException::class)
    suspend fun signNoteSplit(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        proposalHandle: Long,
        usk: ByteArray
    ): JniPreparedTransfer =
        withContext(SdkDispatchers.DATABASE_IO) {
            signNoteSplitNative(
                dbDataPath,
                networkId,
                accountUuidBytes,
                proposalHandle,
                usk
            ) ?: error("signNoteSplit returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun extractBroadcastTx(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        pcztBytes: ByteArray
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            extractBroadcastTxNative(dbDataPath, networkId, accountUuidBytes, pcztBytes)
                ?: error("extractBroadcastTx returned null")
        }

    /**
     * [resultTag]: 0 = Success (requires [txId]), 1 = NetworkError (requires [retryable]),
     * 2 = InvalidNote, 3 = Expired. [txId] is ignored except for tag 0 — pass an empty array
     * otherwise.
     */
    @Throws(RuntimeException::class)
    suspend fun recordTransferResult(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        transferId: String,
        resultTag: Int,
        retryable: Boolean,
        txId: ByteArray
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        recordTransferResultNative(
            dbDataPath,
            networkId,
            accountUuidBytes,
            transferId,
            resultTag,
            retryable,
            txId
        )
    }

    @Throws(RuntimeException::class)
    suspend fun proposeMigrationTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        includeResidual: Boolean
    ): JniMigrationSchedule =
        withContext(SdkDispatchers.DATABASE_IO) {
            proposeMigrationTransfersNative(dbDataPath, networkId, accountUuidBytes, includeResidual)
                ?: error("proposeMigrationTransfers returned null")
        }

    /**
     * Renders the transfer schedule of the exact cached plan [proposalHandle] identifies (the one
     * whose note split the user was just shown by [prepareNoteSplit]) — use this instead of
     * [proposeMigrationTransfers] whenever a split is about to run or just ran, so the schedule
     * shown is guaranteed to belong to the same plan as the split. Unlike
     * [proposeMigrationTransfers] this never re-plans; it throws if the identified plan is
     * missing or superseded. The returned schedule carries the SAME handle.
     */
    @Throws(RuntimeException::class)
    suspend fun proposeMigrationTransfersFromSplit(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        proposalHandle: Long
    ): JniMigrationSchedule =
        withContext(SdkDispatchers.DATABASE_IO) {
            proposeMigrationTransfersFromSplitNative(
                dbDataPath,
                networkId,
                accountUuidBytes,
                proposalHandle
            ) ?: error("proposeMigrationTransfersFromSplit returned null")
        }

    /**
     * Proposes an ordinary send-max transaction sweeping all spendable Orchard funds into this
     * account's own Ironwood receiver — bypasses the migration engine entirely (no `MigrationState`
     * is read or written). Returns the proposal proto-encoded exactly like an ordinary send's
     * `RustBackend.proposeTransfer`, for decoding via the same
     * `Proposal.fromByteArray`/`ProposalUnsafe.parse` path.
     */
    @Throws(RuntimeException::class)
    suspend fun proposeImmediateSendMax(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            proposeImmediateSendMaxNative(dbDataPath, networkId, accountUuidBytes)
        }

    /**
     * Commits and signs the migration plan [proposalHandle] identifies. No schedule fields cross
     * the boundary — the plan's details live only on the Rust side, and this throws if the
     * identified plan is missing or has been superseded by a later propose/prepare call, so it
     * can only ever sign the exact schedule the user was shown.
     */
    @Throws(RuntimeException::class)
    suspend fun signAndStoreMigrationSchedule(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        proposalHandle: Long,
        usk: ByteArray
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        signAndStoreMigrationScheduleNative(
            dbDataPath,
            networkId,
            accountUuidBytes,
            proposalHandle,
            usk
        )
    }

    @Throws(RuntimeException::class)
    suspend fun finalizeReadyTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Int =
        withContext(SdkDispatchers.DATABASE_IO) {
            finalizeReadyTransfersNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun nextDueTransfer(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        estimatedTip: Long
    ): JniDueTransferResult =
        withContext(SdkDispatchers.DATABASE_IO) {
            nextDueTransferNative(dbDataPath, networkId, accountUuidBytes, estimatedTip)
        }

    /**
     * The live, persisted status (broadcast/mined vs. still pending, plus current
     * `scheduled_height`) of every committed transfer transaction, read straight from the
     * migration store — reflects any reschedule immediately, unlike the app's own cached plan.
     * Returns `null` if there's no in-progress migration.
     */
    @Throws(RuntimeException::class)
    suspend fun migrationTransferStates(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniMigrationTransferStates? =
        withContext(SdkDispatchers.DATABASE_IO) {
            migrationTransferStatesNative(dbDataPath, networkId, accountUuidBytes)
        }

    @Throws(RuntimeException::class)
    suspend fun restartCurrentMigrationStep(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        includeResidual: Boolean
    ): JniMigrationSchedule =
        withContext(SdkDispatchers.DATABASE_IO) {
            restartCurrentMigrationStepNative(dbDataPath, networkId, accountUuidBytes, includeResidual)
                ?: error("restartCurrentMigrationStep returned null")
        }

    /**
     * The pending (due-or-not-yet-due) scheduled transfer's full proposal fields, or `null` if
     * nothing is scheduled yet (or only the note-split prep transaction is pending).
     */
    @Throws(RuntimeException::class)
    suspend fun pendingTransferProposal(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniTransferProposal? =
        withContext(SdkDispatchers.DATABASE_IO) {
            pendingTransferProposalNative(dbDataPath, networkId, accountUuidBytes)
        }

    /**
     * The zatoshi value below which a leftover post-migration Orchard balance is treated as dust
     * rather than a residual worth migrating in its own transfer — see
     * `MIGRATION_DUST_THRESHOLD_ZATOSHI` in `migration.rs`. A fixed protocol-level constant, not
     * derived from any wallet/account state — still routed through `SdkDispatchers.DATABASE_IO`
     * only for consistency with every other call in this class, not because it touches a
     * database.
     */
    @Throws(RuntimeException::class)
    suspend fun migrationDustThresholdZatoshi(): Long =
        withContext(SdkDispatchers.DATABASE_IO) {
            migrationDustThresholdZatoshiNative()
        }

    /**
     * Lists every account's UUID in the wallet database, independent of any live `Synchronizer`.
     */
    @Throws(RuntimeException::class)
    suspend fun getAccountUuids(
        dbDataPath: String,
        networkId: Int
    ): List<ByteArray> =
        withContext(SdkDispatchers.DATABASE_IO) {
            getAccountUuidsNative(dbDataPath, networkId).asList()
        }

    // ----- External signer (Keystone hardware wallet) -----

    /**
     * Commits the migration plan [proposalHandle] identifies (unsigned — external-signer path)
     * and returns the note split's unsigned PCZT. Same handle contract as [signNoteSplit]: throws
     * if the identified plan is missing or superseded.
     */
    @Throws(RuntimeException::class)
    suspend fun createUnsignedNoteSplitPczt(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        proposalHandle: Long
    ): ByteArray =
        withContext(SdkDispatchers.DATABASE_IO) {
            createUnsignedNoteSplitPcztNative(dbDataPath, networkId, accountUuidBytes, proposalHandle)
                ?: error("createUnsignedNoteSplitPczt returned null")
        }

    @Throws(RuntimeException::class)
    suspend fun storeSignedNoteSplitPczt(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        signedPczt: ByteArray
    ): JniPreparedTransfer =
        withContext(SdkDispatchers.DATABASE_IO) {
            storeSignedNoteSplitPcztNative(dbDataPath, networkId, accountUuidBytes, signedPczt)
                ?: error("storeSignedNoteSplitPczt returned null")
        }

    /**
     * Builds the unsigned transfer PCZTs of the migration plan [proposalHandle] identifies
     * (committing it first if [createUnsignedNoteSplitPczt] hasn't already). Same handle contract
     * as [signAndStoreMigrationSchedule].
     */
    @Throws(RuntimeException::class)
    suspend fun createUnsignedTransferPczts(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        proposalHandle: Long
    ): Array<JniUnsignedTransferPczt> =
        withContext(SdkDispatchers.DATABASE_IO) {
            createUnsignedTransferPcztsNative(
                dbDataPath,
                networkId,
                accountUuidBytes,
                proposalHandle
            ) ?: error("createUnsignedTransferPczts returned null")
        }

    /**
     * [ids]/[pcztBytesList] are parallel arrays — signed PCZTs matched back to their staged
     * unsigned originals by id, not by array position (`store_signed_schedule_pczts` is
     * all-or-nothing across whatever set of ids is provided here).
     */
    @Throws(RuntimeException::class)
    suspend fun storeSignedSchedulePczts(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        ids: Array<String>,
        pcztBytesList: Array<ByteArray>
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        storeSignedSchedulePcztsNative(dbDataPath, networkId, accountUuidBytes, ids, pcztBytesList)
    }

    // ----- Keystone batch-signing UR bridge (no wallet database access) -----

    /**
     * Builds the animated multi-part QR frames for a Keystone batch-signing request covering the
     * optional note-split PCZT (pass `null` when no split is needed) and every schedule
     * transfer's unsigned PCZT, in that order. [requestId] is an opaque correlation token (e.g. a
     * UUID's bytes) the device round-trips, checked by [decodeKeystoneSignBatchPart].
     */
    @Throws(RuntimeException::class)
    suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        maxFragmentLen: Int
    ): Array<String> =
        withContext(SdkDispatchers.DATABASE_IO) {
            buildKeystoneSignBatchQrPartsNative(
                requestId,
                splitUnsignedPczt,
                transferUnsignedPczts,
                maxFragmentLen
            ) ?: error("buildKeystoneSignBatchQrParts returned null")
        }

    /**
     * Discards any in-flight multi-part scan session. Call on scan-screen entry so a new attempt
     * always starts from a clean slate.
     */
    @Throws(RuntimeException::class)
    suspend fun resetKeystoneSignBatchDecoder() =
        withContext(SdkDispatchers.DATABASE_IO) {
            resetKeystoneSignBatchDecoderNative()
        }

    /**
     * Feeds one scanned QR frame into the active (or a freshly started) decode session. Errors
     * (including a decoded [JniKeystoneBatchDecodeResult.data] whose request id doesn't match
     * [expectedRequestId]) reset the session; call [resetKeystoneSignBatchDecoder] before retrying.
     */
    @Throws(RuntimeException::class)
    suspend fun decodeKeystoneSignBatchPart(
        part: String,
        expectedRequestId: ByteArray
    ): JniKeystoneBatchDecodeResult =
        withContext(SdkDispatchers.DATABASE_IO) {
            decodeKeystoneSignBatchPartNative(part, expectedRequestId)
                ?: error("decodeKeystoneSignBatchPart returned null")
        }

    /**
     * Applies a completed batch-signing response back to the retained unsigned PCZTs — in the
     * exact split-then-transfers order they were passed to [buildKeystoneSignBatchQrParts] —
     * producing signed-but-unproven PCZT bytes for each, ready for
     * [storeSignedNoteSplitPczt]/[storeSignedSchedulePczts].
     */
    @Throws(RuntimeException::class)
    suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        batchSignResponse: ByteArray
    ): JniKeystoneBatchSignedPczts =
        withContext(SdkDispatchers.DATABASE_IO) {
            applyKeystoneBatchSignaturesNative(
                splitUnsignedPczt,
                transferUnsignedPczts,
                batchSignResponse
            ) ?: error("applyKeystoneBatchSignatures returned null")
        }

    companion object {
        suspend fun new(): MigrationRustBackend {
            RustBackend.loadLibrary()

            return MigrationRustBackend()
        }

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun migrationStateNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniMigrationState?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun migrationProgressNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniMigrationProgress?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun isNoteSplitNeededNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun estimateMigrationRunCountNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun lockRemainingOrchardBalanceNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun clearMigrationNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun debugRescheduleTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun rescheduleUnprovenTransferNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            transferId: String
        ): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun hasOverdueTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            estimatedTip: Long
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun hasInvalidTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Boolean

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun prepareNoteSplitNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniNoteSplitProposal?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun signNoteSplitNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            proposalHandle: Long,
            usk: ByteArray
        ): JniPreparedTransfer?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun extractBroadcastTxNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            pcztBytes: ByteArray
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun recordTransferResultNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            transferId: String,
            resultTag: Int,
            retryable: Boolean,
            txId: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun proposeMigrationTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            includeResidual: Boolean
        ): JniMigrationSchedule?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun proposeMigrationTransfersFromSplitNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            proposalHandle: Long
        ): JniMigrationSchedule?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun proposeImmediateSendMaxNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): ByteArray

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun signAndStoreMigrationScheduleNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            proposalHandle: Long,
            usk: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun finalizeReadyTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Int

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun nextDueTransferNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            estimatedTip: Long
        ): JniDueTransferResult

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun migrationTransferStatesNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniMigrationTransferStates?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun restartCurrentMigrationStepNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            includeResidual: Boolean
        ): JniMigrationSchedule?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun migrationDustThresholdZatoshiNative(): Long

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun getAccountUuidsNative(
            dbDataPath: String,
            networkId: Int
        ): Array<ByteArray>

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun pendingTransferProposalNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniTransferProposal?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun createUnsignedNoteSplitPcztNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            proposalHandle: Long
        ): ByteArray?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeSignedNoteSplitPcztNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            signedPczt: ByteArray
        ): JniPreparedTransfer?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun createUnsignedTransferPcztsNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            proposalHandle: Long
        ): Array<JniUnsignedTransferPczt>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun storeSignedSchedulePcztsNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            ids: Array<String>,
            pcztBytesList: Array<ByteArray>
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun buildKeystoneSignBatchQrPartsNative(
            requestId: ByteArray,
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            maxFragmentLen: Int
        ): Array<String>?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun resetKeystoneSignBatchDecoderNative()

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun decodeKeystoneSignBatchPartNative(
            part: String,
            expectedRequestId: ByteArray
        ): JniKeystoneBatchDecodeResult?

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun applyKeystoneBatchSignaturesNative(
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            batchSignResponse: ByteArray
        ): JniKeystoneBatchSignedPczts?
    }
}
