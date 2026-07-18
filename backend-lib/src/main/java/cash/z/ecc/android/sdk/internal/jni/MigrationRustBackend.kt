package cash.z.ecc.android.sdk.internal.jni

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniNoteSplitProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparedTransfer
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
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
    suspend fun hasOverdueTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            hasOverdueTransfersNative(dbDataPath, networkId, accountUuidBytes)
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

    @Throws(RuntimeException::class)
    suspend fun signNoteSplit(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        outputValuesZatoshi: LongArray,
        feeZatoshi: Long,
        usk: ByteArray
    ): JniPreparedTransfer =
        withContext(SdkDispatchers.DATABASE_IO) {
            signNoteSplitNative(
                dbDataPath,
                networkId,
                accountUuidBytes,
                outputValuesZatoshi,
                feeZatoshi,
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

    @Throws(RuntimeException::class)
    suspend fun proposeImmediateMigrationTransfers(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): JniMigrationSchedule =
        withContext(SdkDispatchers.DATABASE_IO) {
            proposeImmediateMigrationTransfersNative(dbDataPath, networkId, accountUuidBytes)
                ?: error("proposeImmediateMigrationTransfers returned null")
        }

    @Suppress("LongParameterList")
    @Throws(RuntimeException::class)
    suspend fun signAndStoreMigrationSchedule(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray,
        schedule: JniMigrationSchedule,
        usk: ByteArray
    ) = withContext(SdkDispatchers.DATABASE_IO) {
        signAndStoreMigrationScheduleNative(
            dbDataPath,
            networkId,
            accountUuidBytes,
            Array(schedule.transfers.size) { schedule.transfers[it].id },
            LongArray(schedule.transfers.size) { schedule.transfers[it].amountZatoshi },
            LongArray(schedule.transfers.size) { schedule.transfers[it].anchorHeight },
            LongArray(schedule.transfers.size) { schedule.transfers[it].nextExecutableAfterHeight },
            LongArray(schedule.transfers.size) { schedule.transfers[it].expiryHeight },
            schedule.estimatedDurationHours,
            usk
        )
    }

    @Throws(RuntimeException::class)
    suspend fun isSyncRequiredBeforeNextTransfer(
        dbDataPath: String,
        networkId: Int,
        accountUuidBytes: ByteArray
    ): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            isSyncRequiredBeforeNextTransferNative(dbDataPath, networkId, accountUuidBytes)
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
        accountUuidBytes: ByteArray
    ): JniPreparedTransfer? =
        withContext(SdkDispatchers.DATABASE_IO) {
            nextDueTransferNative(dbDataPath, networkId, accountUuidBytes)
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
        private external fun hasOverdueTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
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
            outputValuesZatoshi: LongArray,
            feeZatoshi: Long,
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
        private external fun proposeImmediateMigrationTransfersNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): JniMigrationSchedule?

        @JvmStatic
        @Suppress("LongParameterList")
        @Throws(RuntimeException::class)
        private external fun signAndStoreMigrationScheduleNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray,
            ids: Array<String>,
            amountsZatoshi: LongArray,
            anchorHeights: LongArray,
            nextExecutableAfterHeights: LongArray,
            expiryHeights: LongArray,
            estimatedDurationHours: Int,
            usk: ByteArray
        )

        @JvmStatic
        @Throws(RuntimeException::class)
        private external fun isSyncRequiredBeforeNextTransferNative(
            dbDataPath: String,
            networkId: Int,
            accountUuidBytes: ByteArray
        ): Boolean

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
            accountUuidBytes: ByteArray
        ): JniPreparedTransfer?

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
    }
}
