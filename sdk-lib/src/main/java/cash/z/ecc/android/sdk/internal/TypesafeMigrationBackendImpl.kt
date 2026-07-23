package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.jni.MigrationRustBackend
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
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.ZcashNetwork

@Suppress("TooManyFunctions")
internal class TypesafeMigrationBackendImpl(
    private val rustBackendFactory: suspend () -> MigrationRustBackend = { MigrationRustBackend.new() }
) : TypesafeMigrationBackend {
    private val rustBackendLazy =
        SuspendingLazy<Unit, MigrationRustBackend> {
            rustBackendFactory()
        }

    override suspend fun migrationState(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationState = rustBackend().migrationState(dbDataPath, network.id, account.value)

    override suspend fun migrationProgress(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationProgress? = rustBackend().migrationProgress(dbDataPath, network.id, account.value)

    override suspend fun isNoteSplitNeeded(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean = rustBackend().isNoteSplitNeeded(dbDataPath, network.id, account.value)

    override suspend fun estimateMigrationRunCount(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int = rustBackend().estimateMigrationRunCount(dbDataPath, network.id, account.value)

    override suspend fun lockRemainingOrchardBalance(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int = rustBackend().lockRemainingOrchardBalance(dbDataPath, network.id, account.value)

    override suspend fun clearMigration(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int = rustBackend().clearMigration(dbDataPath, network.id, account.value)

    override suspend fun debugRescheduleTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int = rustBackend().debugRescheduleTransfers(dbDataPath, network.id, account.value)

    override suspend fun hasOverdueTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean = rustBackend().hasOverdueTransfers(dbDataPath, network.id, account.value)

    override suspend fun hasInvalidTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Boolean = rustBackend().hasInvalidTransfers(dbDataPath, network.id, account.value)

    override suspend fun prepareNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniNoteSplitProposal = rustBackend().prepareNoteSplit(dbDataPath, network.id, account.value)

    override suspend fun signNoteSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        outputValuesZatoshi: LongArray,
        feeZatoshi: Long,
        usk: ByteArray
    ): JniPreparedTransfer =
        rustBackend().signNoteSplit(dbDataPath, network.id, account.value, outputValuesZatoshi, feeZatoshi, usk)

    override suspend fun extractBroadcastTx(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        pcztBytes: ByteArray
    ): ByteArray = rustBackend().extractBroadcastTx(dbDataPath, network.id, account.value, pcztBytes)

    override suspend fun recordTransferResult(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        transferId: String,
        resultTag: Int,
        retryable: Boolean,
        txId: ByteArray
    ) = rustBackend().recordTransferResult(
        dbDataPath,
        network.id,
        account.value,
        transferId,
        resultTag,
        retryable,
        txId
    )

    override suspend fun proposeMigrationTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule =
        rustBackend().proposeMigrationTransfers(dbDataPath, network.id, account.value, includeResidual)

    override suspend fun proposeMigrationTransfersFromSplit(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        outputValuesZatoshi: LongArray,
        feeZatoshi: Long
    ): JniMigrationSchedule =
        rustBackend().proposeMigrationTransfersFromSplit(
            dbDataPath,
            network.id,
            account.value,
            outputValuesZatoshi,
            feeZatoshi
        )

    override suspend fun proposeImmediateSendMax(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): ByteArray = rustBackend().proposeImmediateSendMax(dbDataPath, network.id, account.value)

    override suspend fun signAndStoreMigrationSchedule(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        schedule: JniMigrationSchedule,
        usk: ByteArray
    ) = rustBackend().signAndStoreMigrationSchedule(dbDataPath, network.id, account.value, schedule, usk)

    override suspend fun finalizeReadyTransfers(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): Int = rustBackend().finalizeReadyTransfers(dbDataPath, network.id, account.value)

    override suspend fun nextDueTransfer(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniPreparedTransfer? = rustBackend().nextDueTransfer(dbDataPath, network.id, account.value)

    override suspend fun restartCurrentMigrationStep(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        includeResidual: Boolean
    ): JniMigrationSchedule =
        rustBackend().restartCurrentMigrationStep(dbDataPath, network.id, account.value, includeResidual)

    override suspend fun migrationTransferStates(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniMigrationTransferStates? = rustBackend().migrationTransferStates(dbDataPath, network.id, account.value)

    override suspend fun getAccountUuids(
        dbDataPath: String,
        network: ZcashNetwork
    ): List<AccountUuid> =
        rustBackend().getAccountUuids(dbDataPath, network.id).map { AccountUuid.new(it) }

    override suspend fun pendingTransferProposal(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): JniTransferProposal? = rustBackend().pendingTransferProposal(dbDataPath, network.id, account.value)

    override suspend fun persistRescheduledTransfer(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        newScheduledHeight: Long
    ): Boolean = rustBackend().persistRescheduledTransfer(dbDataPath, network.id, account.value, newScheduledHeight)

    override suspend fun createUnsignedNoteSplitPczt(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid
    ): ByteArray = rustBackend().createUnsignedNoteSplitPczt(dbDataPath, network.id, account.value)

    override suspend fun storeSignedNoteSplitPczt(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        signedPczt: ByteArray
    ): JniPreparedTransfer =
        rustBackend().storeSignedNoteSplitPczt(dbDataPath, network.id, account.value, signedPczt)

    override suspend fun createUnsignedTransferPczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        schedule: JniMigrationSchedule
    ): Array<JniUnsignedTransferPczt> =
        rustBackend().createUnsignedTransferPczts(dbDataPath, network.id, account.value, schedule)

    override suspend fun storeSignedSchedulePczts(
        dbDataPath: String,
        network: ZcashNetwork,
        account: AccountUuid,
        ids: Array<String>,
        pcztBytesList: Array<ByteArray>
    ) = rustBackend().storeSignedSchedulePczts(dbDataPath, network.id, account.value, ids, pcztBytesList)

    override suspend fun buildKeystoneSignBatchQrParts(
        requestId: ByteArray,
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        maxFragmentLen: Int
    ): Array<String> =
        rustBackend().buildKeystoneSignBatchQrParts(requestId, splitUnsignedPczt, transferUnsignedPczts, maxFragmentLen)

    override suspend fun resetKeystoneSignBatchDecoder() = rustBackend().resetKeystoneSignBatchDecoder()

    override suspend fun decodeKeystoneSignBatchPart(
        part: String,
        expectedRequestId: ByteArray
    ): JniKeystoneBatchDecodeResult = rustBackend().decodeKeystoneSignBatchPart(part, expectedRequestId)

    override suspend fun applyKeystoneBatchSignatures(
        splitUnsignedPczt: ByteArray?,
        transferUnsignedPczts: Array<ByteArray>,
        batchSignResponse: ByteArray
    ): JniKeystoneBatchSignedPczts =
        rustBackend().applyKeystoneBatchSignatures(splitUnsignedPczt, transferUnsignedPczts, batchSignResponse)

    private suspend fun rustBackend() = rustBackendLazy.getInstance(Unit)
}
