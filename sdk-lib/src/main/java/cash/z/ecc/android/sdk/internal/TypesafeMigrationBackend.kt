package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniNoteSplitProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparedTransfer
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
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

    suspend fun debugRescheduleTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int

    suspend fun hasOverdueTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    suspend fun hasInvalidTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean

    suspend fun prepareNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniNoteSplitProposal

    suspend fun signNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        outputValuesZatoshi: LongArray,
        feeZatoshi: Long,
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
     * 2 = InvalidNote, 3 = Expired. [txId] is ignored except for tag 0 — pass an empty array
     * otherwise.
     */
    suspend fun recordTransferResult(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        transferId: String,
        resultTag: Int,
        retryable: Boolean,
        txId: ByteArray
    )

    suspend fun proposeMigrationTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule

    suspend fun proposeMigrationTransfersFromSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        outputValuesZatoshi: LongArray,
        feeZatoshi: Long
    ): JniMigrationSchedule

    suspend fun proposeImmediateMigrationTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationSchedule

    suspend fun signAndStoreMigrationSchedule(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        schedule: JniMigrationSchedule,
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
        account: AccountUuid
    ): JniPreparedTransfer?

    suspend fun restartCurrentMigrationStep(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule

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

    // ----- External signer (Keystone hardware wallet) -----

    suspend fun createUnsignedNoteSplitPczt(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
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
        schedule: JniMigrationSchedule
    ): Array<JniUnsignedTransferPczt>

    /**
     * [ids]/[pcztBytesList] are parallel arrays — signed PCZTs matched back to their staged
     * unsigned originals by id, not by array position (all-or-nothing across whatever set of ids
     * is provided here).
     */
    suspend fun storeSignedSchedulePczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        ids: Array<String>,
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
