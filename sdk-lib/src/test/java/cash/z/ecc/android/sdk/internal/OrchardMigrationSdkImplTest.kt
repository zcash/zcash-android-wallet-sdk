package cash.z.ecc.android.sdk.internal

import android.content.Context
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchDecodeResult
import cash.z.ecc.android.sdk.internal.model.migration.JniKeystoneBatchSignedPczts
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationProgress
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationSchedule
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationState
import cash.z.ecc.android.sdk.internal.model.migration.JniMigrationTransferStates
import cash.z.ecc.android.sdk.internal.model.migration.JniDueTransferResult
import cash.z.ecc.android.sdk.internal.model.migration.JniPreparedTransfer
import cash.z.ecc.android.sdk.internal.model.migration.JniTransferProposal
import cash.z.ecc.android.sdk.internal.model.migration.JniUnsignedTransferPczt
import cash.z.ecc.android.sdk.internal.storage.preference.EncryptedPreferenceProvider
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class OrchardMigrationSdkImplTest {
    @Test
    fun `privacy buffer is 10 minutes on mainnet and 3 on testnet`() {
        assertEquals(10.minutes, privacySyncBufferFor(ZcashNetwork.Mainnet))
        assertEquals(3.minutes, privacySyncBufferFor(ZcashNetwork.Testnet))
    }

    @Test
    fun `sync is blocked while broadcast in flight mark is in the future`() {
        // pure helper: isBroadcastInFlight(nowEpoch, markEpoch) -> Boolean
        assertTrue(isBroadcastInFlight(nowEpochSeconds = 100, inFlightUntilEpochSeconds = 160))
        assertFalse(isBroadcastInFlight(nowEpochSeconds = 200, inFlightUntilEpochSeconds = 160))
    }

    // ── F2: non-gRPC submit-failure classification ──────────────────────────
    // classifyNonGrpcFailure(description, minedHeight) == true means "treat as Success" (our tx is
    // already on-chain / in the mempool); false means "genuinely unknown rejection" (record tag=2).

    @Test
    fun `a mined txid makes a non-gRPC failure a success regardless of text`() {
        assertTrue(classifyNonGrpcFailure(description = null, minedHeight = 0L))
        assertTrue(classifyNonGrpcFailure(description = "some unknown reason", minedHeight = 1_234_567L))
    }

    @Test
    fun `duplicate rejection strings are treated as success even without a mined height`() {
        assertTrue(classifyNonGrpcFailure("tx already in mempool", minedHeight = -1L))
        assertTrue(classifyNonGrpcFailure("Duplicate transaction", minedHeight = -1L))
        assertTrue(classifyNonGrpcFailure("txid ABC already known to node", minedHeight = -1L))
        // Case-insensitive.
        assertTrue(classifyNonGrpcFailure("ALREADY IN MEMPOOL", minedHeight = -1L))
    }

    @Test
    fun `a genuinely unknown rejection with no mined height stays a failure`() {
        assertFalse(classifyNonGrpcFailure("insufficient fee", minedHeight = -1L))
        assertFalse(classifyNonGrpcFailure(null, minedHeight = -1L))
        assertFalse(classifyNonGrpcFailure("", minedHeight = -1L))
    }


    @Test
    fun `proposeImmediateMigration delegates to the send-max native call and returns an ordinary Proposal`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val proposalBytes = fakeProposalBytes()
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    proposeImmediateSendMaxResult = proposalBytes
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.proposeImmediateMigration()

            assertTrue(fakeBackend.proposeImmediateSendMaxCalled)
            assertEquals(account, fakeBackend.lastAccount)
            assertEquals(proposalBytes.toList(), result.toUnsafe().toByteArray().toList())
        }

    /**
     * `migrationDustThresholdZatoshi()` is a pure pass-through to the Rust-side
     * `MIGRATION_DUST_THRESHOLD_ZATOSHI` constant (100,000 zatoshi / 0.001 ZEC) — no account or
     * database state involved. This just pins down that the value round-trips through the
     * typesafe backend unchanged.
     */
    @Test
    fun `migrationDustThresholdZatoshi returns the backend's constant value unchanged`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    migrationDustThresholdZatoshiResult = 100_000L
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.migrationDustThresholdZatoshi()

            assertEquals(100_000L, result)
        }

    /**
     * `getMigrationSummary()` decodes the native `[totalMigratedZatoshi, transferCount,
     * firstMinedEpochSeconds, lastMinedEpochSeconds]` array into a typed [MigrationSummary]. Uses
     * the same crossing-values total / mined-transfer count / block-time bounds the Migration
     * Complete screen shows.
     */
    @Test
    fun `getMigrationSummary decodes the native array into a typed summary`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend =
                FakeTypesafeMigrationBackend(
                    migrationSummaryResult = longArrayOf(9_779_000_000L, 10L, 1_785_281_502L, 1_785_283_542L)
                )
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            val result = sdk.getMigrationSummary()

            assertEquals(9_779_000_000L, result?.totalMigratedZatoshi)
            assertEquals(10, result?.transferCount)
            assertEquals(1_785_281_502L, result?.firstMinedEpochSeconds)
            assertEquals(1_785_283_542L, result?.lastMinedEpochSeconds)
            // No account is needed — the migration tables are wallet-scoped.
            assertTrue(fakeBackend.migrationSummaryDbDataPath != null)
        }

    /**
     * An EMPTY native array (no migration data / no mined transfer yet) maps to `null`, so the
     * Migration Complete screen falls back to zeros rather than showing garbage.
     */
    @Test
    fun `getMigrationSummary maps an empty native array to null`() =
        runBlocking {
            val account = AccountUuid.new(ByteArray(16) { it.toByte() })
            val fakeBackend = FakeTypesafeMigrationBackend(migrationSummaryResult = LongArray(0))
            val sdk =
                OrchardMigrationSdkImpl(
                    context = fakeAndroidContext(),
                    network = ZcashNetwork.Testnet,
                    alias = "OrchardMigrationSdkImplTest",
                    account = account,
                    migrationBackend = fakeBackend,
                    defaultSubmitEndpoint = LightWalletEndpoint("localhost", 9067, true),
                    preferenceProviderHolder = EncryptedPreferenceProvider(fakeAndroidContext()),
                )

            assertEquals(null, sdk.getMigrationSummary())
        }

    @Test
    fun `withBroadcastTimeout passes through a result that completes before the timeout`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }
            val expected = TransactionSubmitResult.Success(FirstClassByteArray(txId))

            val result =
                withBroadcastTimeout(useTor = false, txId = txId, timeout = 200.milliseconds) {
                    expected
                }

            assertEquals(expected, result)
        }

    @Test
    fun `withBroadcastTimeout maps a hang past the timeout to a Tor-tagged failure when useTor is true`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }

            val result =
                withBroadcastTimeout(useTor = true, txId = txId, timeout = 20.milliseconds) {
                    delay(500.milliseconds)
                    error("should never reach here — timeout should win first")
                }

            assertTrue(result is TransactionSubmitResult.Failure)
            assertTrue(result.isTorFailure)
            assertTrue(result.grpcError)
        }

    @Test
    fun `withBroadcastTimeout does not tag the failure as Tor when useTor is false`() =
        runBlocking {
            val txId = ByteArray(32) { it.toByte() }

            val result =
                withBroadcastTimeout(useTor = false, txId = txId, timeout = 20.milliseconds) {
                    delay(500.milliseconds)
                    error("should never reach here — timeout should win first")
                }

            assertTrue(result is TransactionSubmitResult.Failure)
            assertFalse(result.isTorFailure)
        }

    /**
     * A minimal, hand-encoded `cash.z.wallet.sdk.ffi.Proposal` protobuf message (see
     * `backend-lib/src/main/proto/proposal.proto`) — just field 2 (`feeRule`) set to `Zip317` (3),
     * with no steps. `ProposalUnsafe`'s init check only requires a non-default `feeRule`, and
     * `Proposal.check()`'s `totalFeeRequired()` folds over an empty `steps` list to 0, so this
     * round-trips cleanly through `Proposal.fromByteArray`.
     *
     * Built by hand (proto3 varint wire format: tag byte `(fieldNumber shl 3) or wireType`,
     * followed by the varint value) rather than via the generated `ProposalOuterClass` builder,
     * because the protobuf-lite runtime is an `implementation`-scoped dependency of `backend-lib`
     * (not exposed transitively) — `sdk-lib` can call into `ProposalUnsafe`'s own API (as
     * production code already does) but cannot reference `com.google.protobuf.*` supertypes
     * directly at compile time.
     */
    private fun fakeProposalBytes(): ByteArray {
        val fieldNumber = 2
        val wireTypeVarint = 0
        val tag = (fieldNumber shl 3) or wireTypeVarint
        val feeRuleZip317 = 3
        return byteArrayOf(tag.toByte(), feeRuleZip317.toByte())
    }

    private fun fakeAndroidContext(): Context {
        val tempDir =
            kotlin.io.path
                .createTempDirectory("OrchardMigrationSdkImplTest")
                .toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        `when`(context.getDatabasePath(anyString())).thenReturn(File(tempDir, "databases/unused.db"))
        return context
    }

    @Suppress("TooManyFunctions")
    private class FakeTypesafeMigrationBackend(
        private val proposeImmediateSendMaxResult: ByteArray = ByteArray(0),
        private val migrationDustThresholdZatoshiResult: Long = 100_000L,
        private val migrationSummaryResult: LongArray = LongArray(0)
    ) : TypesafeMigrationBackend {
        var proposeImmediateSendMaxCalled = false
        var lastAccount: AccountUuid? = null
        var migrationSummaryDbDataPath: String? = null

        override suspend fun migrationDustThresholdZatoshi(): Long = migrationDustThresholdZatoshiResult

        override suspend fun migrationState(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationState = error("Unused")

        override suspend fun migrationProgress(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationProgress? = error("Unused")

        override suspend fun isNoteSplitNeeded(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun estimateMigrationRunCount(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun lockRemainingOrchardBalance(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun clearMigration(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun hasOverdueTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            estimatedTip: Long
        ): Boolean = error("Unused")

        override suspend fun hasInvalidTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun transactionMinedHeight(
            dbDataPath: String,
            network: ZcashNetwork,
            txId: ByteArray
        ): Long = error("Unused")

        override suspend fun reconcileInvalidatedTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Boolean = error("Unused")

        override suspend fun prepareNoteSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ) = error("Unused")

        override suspend fun signNoteSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long,
            usk: ByteArray
        ): JniPreparedTransfer = error("Unused")

        override suspend fun extractBroadcastTx(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            pcztBytes: ByteArray
        ): ByteArray = error("Unused")

        override suspend fun recordTransferResult(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            transferId: Long,
            resultTag: Int,
            retryable: Boolean,
            txId: ByteArray
        ) = error("Unused")

        override suspend fun proposeMigrationTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            includeResidual: Boolean
        ): JniMigrationSchedule = error("Unused")

        override suspend fun proposeMigrationTransfersFromSplit(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): JniMigrationSchedule = error("Unused")

        override suspend fun proposeImmediateSendMax(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): ByteArray {
            proposeImmediateSendMaxCalled = true
            lastAccount = account
            return proposeImmediateSendMaxResult
        }

        override suspend fun signAndStoreMigrationSchedule(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long,
            usk: ByteArray
        ) = error("Unused")

        override suspend fun finalizeReadyTransfers(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): Int = error("Unused")

        override suspend fun nextDueTransfer(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            estimatedTip: Long
        ): JniDueTransferResult = error("Unused")

        override suspend fun restartCurrentMigrationStep(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            includeResidual: Boolean
        ): JniMigrationSchedule = error("Unused")

        override suspend fun migrationTransferStates(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniMigrationTransferStates? = error("Unused")

        override suspend fun migrationSummary(dbDataPath: String): LongArray {
            migrationSummaryDbDataPath = dbDataPath
            return migrationSummaryResult
        }

        override suspend fun getAccountUuids(
            dbDataPath: String,
            network: ZcashNetwork
        ): List<AccountUuid> = error("Unused")

        override suspend fun pendingTransferProposal(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid
        ): JniTransferProposal? = error("Unused")

        override suspend fun createUnsignedNoteSplitPczt(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): ByteArray = error("Unused")

        override suspend fun storeSignedNoteSplitPczt(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            signedPczt: ByteArray
        ): JniPreparedTransfer = error("Unused")

        override suspend fun createUnsignedTransferPczts(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            proposalHandle: Long
        ): Array<JniUnsignedTransferPczt> = error("Unused")

        override suspend fun storeSignedSchedulePczts(
            dbDataPath: String,
            network: ZcashNetwork,
            account: AccountUuid,
            ids: LongArray,
            pcztBytesList: Array<ByteArray>
        ) = error("Unused")

        override suspend fun buildKeystoneSignBatchQrParts(
            requestId: ByteArray,
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            maxFragmentLen: Int
        ): Array<String> = error("Unused")

        override suspend fun resetKeystoneSignBatchDecoder() = error("Unused")

        override suspend fun decodeKeystoneSignBatchPart(
            part: String,
            expectedRequestId: ByteArray
        ): JniKeystoneBatchDecodeResult = error("Unused")

        override suspend fun applyKeystoneBatchSignatures(
            splitUnsignedPczt: ByteArray?,
            transferUnsignedPczts: Array<ByteArray>,
            batchSignResponse: ByteArray
        ): JniKeystoneBatchSignedPczts = error("Unused")
    }
}
