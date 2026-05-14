package cash.z.ecc.android.sdk.internal.jni

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import cash.z.ecc.android.sdk.internal.model.voting.JniGovernancePczt
import cash.z.ecc.android.sdk.internal.model.voting.JniNoteInfo
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniVanWitness
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteCommitmentResult
import cash.z.ecc.android.sdk.internal.model.voting.JniWireEncryptedShare
import cash.z.ecc.android.sdk.internal.model.voting.JniWitnessData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStdlibApi::class)
@Suppress("LargeClass", "MagicNumber")
class VotingRustBackendTest {
    companion object {
        private const val FIELD_BYTES = 32
        private const val SHARE_INDEX = 5
        private const val OUT_OF_RANGE_SHARE_INDEX = 16
        private const val DIVERSIFIER_BYTES = 11
        private const val ORCHARD_FVK_BYTES = 96
        private const val ORCHARD_WITNESS_PATH_DEPTH = 32
        private const val SCANNED_PRIORITY = 10
        private val VOTE_COMMITMENT = ByteArray(FIELD_BYTES) { 1 }
        private val BLIND = ByteArray(FIELD_BYTES) { 2 }
        private val SHORT_FIELD = ByteArray(FIELD_BYTES - 1)
        private val EXPECTED_NULLIFIER =
            "8d6d97caa19a20e5e67e7cc24aaaa7beb72b4a513863f6adbe7b62ba1b1b0010".hexToByteArray()

        private const val WALLET_ID = "wallet-1"
        private const val OTHER_WALLET_ID = "wallet-2"
        private const val ROUND_ID = "round-1"
        private const val SNAPSHOT_HEIGHT = 123_456L
        private const val SESSION_JSON = "{\"round\":\"one\"}"
        private const val TESTNET_NETWORK_ID = JNI_VOTING_NETWORK_ID_TESTNET
        private const val ACCOUNT_INDEX = 0
        private const val MAINNET_NETWORK_ID = JNI_VOTING_NETWORK_ID_MAINNET
        private const val SECOND_ROUND_ID = "round-2"
        private const val PCZT_ROUND_ID =
            "0101010101010101010101010101010101010101010101010101010101010101"
        private const val ROUND_NAME = "Test Round"
        private const val NOTE_VALUE = 13_000_000L
        private const val PCZT_NOTE_VALUE = 15_000_000L
        private const val LARGE_BUNDLE_WEIGHT = 62_500_000L
        private const val SMALL_BUNDLE_WEIGHT = 12_500_000L
        private const val TWO_BUNDLE_ELIGIBLE_WEIGHT = 75_000_000L
        private val EA_PK = ByteArray(FIELD_BYTES) { 3 }
        private val NC_ROOT = ByteArray(FIELD_BYTES) { 4 }
        private val NULLIFIER_IMT_ROOT = ByteArray(FIELD_BYTES) { 5 }
        private val HOTKEY_SEED = ByteArray(64) { 0x42 }
        private val OTHER_HOTKEY_SEED = ByteArray(64) { 0x43 }
        private val SEED_FINGERPRINT = ByteArray(FIELD_BYTES) { 6 }
        private const val EMPTY_ORCHARD_NOTE_COMMITMENT =
            "0200000000000000000000000000000000000000000000000000000000000000"
        private const val EMPTY_ORCHARD_WITNESS_ROOT =
            "ae2935f1dfd8a24aed7c70df7de3a668eb7a49b1319880dde2bbd9031ae5d82f"
        private val EMPTY_ORCHARD_AUTH_PATH =
            listOf(
                "0200000000000000000000000000000000000000000000000000000000000000",
                "d1ab2507c809c2713c000f525e9fbdcb06c958384e51b9cc7f792dde6c97f411",
                "c7413f4614cd64043abbab7cc1095c9bb104231cea89e2c3e0df83769556d030",
                "2111fc397753e5fd50ec74816df27d6ada7ed2a9ac3816aab2573c8fac794204",
                "806afbfeb45c64d4f2384c51eff30764b84599ae56a7ab3d4a46d9ce3aeab431",
                "873e4157f2c0f0c645e899360069fcc9d2ed9bc11bf59827af0230ed52edab18",
                "27ab1320953ae1ad70c8c15a1253a0a86fbc8a0aa36a84207293f8a495ffc402",
                "4e14563df191a2a65b4b37113b5230680555051b22d74a8e1f1d706f90f3133b",
                "b3bbe4f993d18a0f4eb7f4174b1d8555ce3396855d04676f1ce4f06dda07371f",
                "4ef5bde9c6f0d76aeb9e27e93fba28c679dfcb991cbcb8395a2b57924cbd170e",
                "a3c02568acebf5ca1ec30d6a7d7cd217a47d6a1b8311bf9462a5f939c6b74307",
                "3ef9b30bae6122da1605bad6ec5d49b41d4d40caa96c1cf6302b66c5d2d10d39",
                "22ae2800cb93abe63b70c172de70362d9830e53800398884a7a64ff68ed99e0b",
                "187110d92672c24cedb0979cdfc917a6053b310d145c031c7292bb1d65b7661b",
                "3f98adbe364f148b0cc2042cafc6be1166fae39090ab4b354bfb6217b964453b",
                "63f8dbd10df936f1734973e0b3bd25f4ed440566c923085903f696bc6347ec0f",
                "2182163eac4061885a313568148dfae564e478066dcbe389a0ddb1ecb7f5dc34",
                "bd9dc0681918a3f3f9cd1f9e06aa1ad68927da63acc13b92a2578b2738a6d331",
                "ca2ced953b7fb95e3ba986333da9e69cd355223c929731094b6c2174c7638d2e",
                "55354b96b56f9e45aae1e0094d71ee248dabf668117778bdc3c19ca5331a4e1a",
                "7097b04c2aa045a0deffcaca41c5ac92e694466578f5909e72bb78d33310f705",
                "e81d6821ff813bd410867a3f22e8e5cb7ac5599a610af5c354eb392877362e01",
                "157de8567f7c4996b8c4fdc94938fd808c3b2a5ccb79d1a63858adaa9a6dd824",
                "fe1fce51cd6120c12c124695c4f98b275918fceae6eb209873ed73fe73775d0b",
                "1f91982912012669f74d0cfa1030ff37b152324e5b8346b3335a0aaeb63a0a2d",
                "5dec15f52af17da3931396183cbbbfbea7ed950714540aec06c645c754975522",
                "e8ae2ad91d463bab75ee941d33cc5817b613c63cda943a4c07f600591b088a25",
                "d53fdee371cef596766823f4a518a583b1158243afe89700f0da76da46d0060f",
                "15d2444cefe7914c9a61e829c730eceb216288fee825f6b3b6298f6f6b6bd62e",
                "4c57a617a0aa10ea7a83aa6b6b0ed685b6a3d9e5b8fd14f56cdc18021b12253f",
                "3fd4915c19bd831a7920be55d969b2ac23359e2559da77de2373f06ca014ba27",
                "87d063cd07ee4944222b7762840eb94c688bec743fa8bdf7715c8fe29f104c2a"
            )
    }

    @Test
    fun compute_share_nullifier_returns_known_vector() =
        runTest {
            val backend = VotingRustBackend.new()
            val nullifier = backend.computeShareNullifier(VOTE_COMMITMENT, SHARE_INDEX, BLIND)
            val swappedNullifier = backend.computeShareNullifier(BLIND, SHARE_INDEX, VOTE_COMMITMENT)

            assertContentEquals(EXPECTED_NULLIFIER, nullifier)
            assertFalse(EXPECTED_NULLIFIER.contentEquals(swappedNullifier))
        }

    @Test
    fun compute_share_nullifier_rejects_malformed_inputs() =
        runTest {
            val backend = VotingRustBackend.new()

            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(SHORT_FIELD, SHARE_INDEX, BLIND)
            }
            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(VOTE_COMMITMENT, SHARE_INDEX, SHORT_FIELD)
            }
            assertFailsWith<RuntimeException> {
                backend.computeShareNullifier(VOTE_COMMITMENT, OUT_OF_RANGE_SHARE_INDEX, BLIND)
            }
        }

    @Test
    fun decompose_weight_returns_exact_shares() =
        runTest {
            val shares = VotingRustBackend.new().decomposeWeight(65_535).toList()

            assertEquals((0 until JNI_VOTE_SHARE_COUNT).map { 1L shl it }, shares)
        }

    @Test
    fun warm_proving_caches_smoke() =
        runTest {
            VotingRustBackend.new().warmProvingCaches()
        }

    @Test
    fun extract_orchard_fvk_from_ufvk_is_deterministic() =
        runTest {
            val backend = VotingRustBackend.new()
            val ufvk = deriveTestUfvk()

            val first = backend.extractOrchardFvkFromUfvk(ufvk, TESTNET_NETWORK_ID)
            val second = backend.extractOrchardFvkFromUfvk(ufvk, TESTNET_NETWORK_ID)

            assertEquals(ORCHARD_FVK_BYTES, first.size)
            assertContentEquals(first, second)
            assertFailsWith<RuntimeException> {
                backend.extractOrchardFvkFromUfvk("not-a-ufvk", TESTNET_NETWORK_ID)
            }
        }

    @Test
    fun derive_hotkey_raw_address_is_deterministic_and_rejects_short_seed() =
        runTest {
            val backend = VotingRustBackend.new()

            val first = backend.deriveHotkeyRawAddress(HOTKEY_SEED, TESTNET_NETWORK_ID)
            val second = backend.deriveHotkeyRawAddress(HOTKEY_SEED, TESTNET_NETWORK_ID)
            val otherSeed = backend.deriveHotkeyRawAddress(OTHER_HOTKEY_SEED, TESTNET_NETWORK_ID)
            val mainnet = backend.deriveHotkeyRawAddress(HOTKEY_SEED, MAINNET_NETWORK_ID)

            assertEquals(DIVERSIFIER_BYTES + FIELD_BYTES, first.size)
            assertContentEquals(first, second)
            assertFalse(first.contentEquals(otherSeed))
            assertFalse(first.contentEquals(mainnet))
            assertFailsWith<RuntimeException> {
                backend.deriveHotkeyRawAddress(SHORT_FIELD, TESTNET_NETWORK_ID)
            }
        }

    @Test
    fun extract_nc_root_decodes_tree_state() =
        runTest {
            val backend = VotingRustBackend.new()

            val root = backend.extractNcRoot(backend.treeStateFixtureForTesting())

            assertContentEquals(EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(), root)
            assertFailsWith<RuntimeException> {
                backend.extractNcRoot(byteArrayOf(1, 2, 3))
            }
        }

    @Test
    fun verify_witness_returns_boolean() =
        runTest {
            val backend = VotingRustBackend.new()
            val validWitness = witnesses().single()

            assertTrue(backend.verifyWitness(validWitness))
            assertFalse(backend.verifyWitness(validWitness.copy(root = NC_ROOT)))
            assertFailsWith<RuntimeException> {
                backend.verifyWitness(
                    validWitness.copy(
                        authPath = validWitness.authPath.dropLast(1)
                    )
                )
            }
        }

    @Test
    fun voting_db_round_state_round_trips() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                assertNull(db.getRoundState(ROUND_ID))

                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = SESSION_JSON
                )

                val state = assertNotNull(db.getRoundState(ROUND_ID))
                assertEquals(ROUND_ID, state.roundId)
                assertEquals(JniRoundPhase.INITIALIZED.value, state.phase)
                assertEquals(JniRoundPhase.INITIALIZED, state.roundPhase)
                assertEquals(SNAPSHOT_HEIGHT, state.snapshotHeight)
                assertNull(state.hotkeyAddress)
                assertNull(state.delegatedWeight)
                assertFalse(state.proofGenerated)

                val rounds = db.listRounds()
                assertEquals(1, rounds.size)
                val round = rounds.single()
                assertEquals(ROUND_ID, round.roundId)
                assertEquals(JniRoundPhase.INITIALIZED.value, round.phase)
                assertEquals(JniRoundPhase.INITIALIZED, round.roundPhase)
                assertEquals(SNAPSHOT_HEIGHT, round.snapshotHeight)

                assertEquals(emptyList(), db.getVotes(ROUND_ID).asList())

                db.clearRound(ROUND_ID)
                assertNull(db.getRoundState(ROUND_ID))
            } finally {
                db.close()
            }
        }

    @Test
    fun voting_db_keeps_wallet_state_isolated() =
        runTest {
            val dbPath = newDbPath()
            val firstWallet = VotingRustBackend.new().openVotingDb(dbPath, WALLET_ID)
            val secondWallet = VotingRustBackend.new().openVotingDb(dbPath, OTHER_WALLET_ID)
            try {
                firstWallet.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                assertNotNull(firstWallet.getRoundState(ROUND_ID))
                assertNull(secondWallet.getRoundState(ROUND_ID))
            } finally {
                firstWallet.close()
                secondWallet.close()
            }
        }

    @Test
    fun list_rounds_returns_all_rounds_for_current_wallet_only() =
        runTest {
            val dbPath = newDbPath()
            val firstWallet = VotingRustBackend.new().openVotingDb(dbPath, WALLET_ID)
            val secondWallet = VotingRustBackend.new().openVotingDb(dbPath, OTHER_WALLET_ID)
            try {
                firstWallet.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
                firstWallet.initRound(
                    roundId = SECOND_ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                val firstWalletRounds = firstWallet.listRounds().map { it.roundId }.toSet()
                val secondWalletRounds = secondWallet.listRounds()

                assertEquals(setOf(ROUND_ID, SECOND_ROUND_ID), firstWalletRounds)
                assertEquals(0, secondWalletRounds.size)
            } finally {
                firstWallet.close()
                secondWallet.close()
            }
        }

    @Test
    fun voting_db_rejects_malformed_inputs_and_closed_handle() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)

            assertFailsWith<RuntimeException> {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = -1,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
            }
            assertFailsWith<RuntimeException> {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = SHORT_FIELD,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
            }

            db.close()
            db.close()
            assertFailsWith<IllegalStateException> {
                db.getRoundState(ROUND_ID)
            }
        }

    @Test
    fun compute_bundle_setup_returns_exact_weights() =
        runTest {
            val setup = VotingRustBackend.new().computeBundleSetup(notes(noteCount = 6))

            assertEquals(2, setup.bundleCount)
            assertEquals(TWO_BUNDLE_ELIGIBLE_WEIGHT, setup.eligibleWeight)
            assertEquals(listOf(LARGE_BUNDLE_WEIGHT, SMALL_BUNDLE_WEIGHT), setup.bundleWeights)
            assertEquals(setup.eligibleWeight, setup.bundleWeights.sum())
        }

    @Test
    fun compute_bundle_setup_rejects_unknown_note_scope() =
        runTest {
            val notes =
                listOf(note(value = NOTE_VALUE, position = 0, byteValue = 1, scope = 2))

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notes)
            }
        }

    @Test
    fun compute_bundle_setup_rejects_malformed_diversifier() =
        runTest {
            val notes =
                listOf(
                    note(value = NOTE_VALUE, position = 0, byteValue = 1)
                        .copy(diversifier = ByteArray(DIVERSIFIER_BYTES - 1))
                )

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notes)
            }
        }

    @Test
    fun setup_bundles_round_trips_bundle_count() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                val setup = db.setupBundles(ROUND_ID, notes(noteCount = 6))

                assertEquals(2, setup.bundleCount)
                assertEquals(TWO_BUNDLE_ELIGIBLE_WEIGHT, setup.eligibleWeight)
                assertEquals(listOf(LARGE_BUNDLE_WEIGHT, SMALL_BUNDLE_WEIGHT), setup.bundleWeights)
                assertEquals(setup.eligibleWeight, setup.bundleWeights.sum())
                assertEquals(2, db.getBundleCount(ROUND_ID))

                val deletedRows = db.deleteSkippedBundles(ROUND_ID, keepCount = 1)
                assertEquals(1L, deletedRows)
                assertEquals(1, db.getBundleCount(ROUND_ID))
            } finally {
                db.close()
            }
        }

    @Test
    fun store_tree_state_rejects_invalid_cached_bytes() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                assertFailsWith<RuntimeException> {
                    db.storeTreeState(ROUND_ID, byteArrayOf(1, 2, 3))
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun store_tree_state_rejects_snapshot_height_mismatch() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(),
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                assertFailsWith<RuntimeException> {
                    db.storeTreeState(ROUND_ID, backend.treeStateFixtureForTesting())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun store_tree_state_rejects_nc_root_mismatch() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = 1,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                assertFailsWith<RuntimeException> {
                    db.storeTreeState(ROUND_ID, backend.treeStateFixtureForTesting())
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun store_tree_state_accepts_matching_snapshot_fixture() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = 1,
                    eaPK = EA_PK,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(),
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                db.storeTreeState(ROUND_ID, backend.treeStateFixtureForTesting())
            } finally {
                db.close()
            }
        }

    @Test
    fun get_wallet_notes_rejects_unknown_account() =
        runTest {
            val wallet = newWalletDbWithAccount()
            markWalletScannedThrough(wallet.path, walletBirthdayHeight(wallet.path))

            assertRuntimeExceptionContains("account not found in wallet DB") {
                VotingRustBackend.new().getWalletNotes(
                    walletDbPath = wallet.path,
                    snapshotHeight = walletBirthdayHeight(wallet.path),
                    networkId = TESTNET_NETWORK_ID,
                    accountUuidBytes = ByteArray(16) { 9 }
                )
            }
        }

    @Test
    fun get_wallet_notes_rejects_account_without_ufvk() =
        runTest {
            val wallet = newWalletDbWithAccount()
            markWalletScannedThrough(wallet.path, walletBirthdayHeight(wallet.path))
            downgradeAccountToUivkOnly(wallet.path, wallet.accountUuid)

            assertRuntimeExceptionContains("account has no UFVK") {
                VotingRustBackend.new().getWalletNotes(
                    walletDbPath = wallet.path,
                    snapshotHeight = walletBirthdayHeight(wallet.path),
                    networkId = TESTNET_NETWORK_ID,
                    accountUuidBytes = wallet.accountUuid
                )
            }
        }

    @Test
    fun get_wallet_notes_rejects_snapshot_above_fully_scanned_height() =
        runTest {
            val wallet = newWalletDbWithAccount()
            val fullyScannedHeight = walletBirthdayHeight(wallet.path)
            markWalletScannedThrough(wallet.path, fullyScannedHeight)

            assertRuntimeExceptionContains(
                "wallet DB fully scanned height $fullyScannedHeight is below snapshot_height " +
                    "${fullyScannedHeight + 1}"
            ) {
                VotingRustBackend.new().getWalletNotes(
                    walletDbPath = wallet.path,
                    snapshotHeight = fullyScannedHeight + 1,
                    networkId = TESTNET_NETWORK_ID,
                    accountUuidBytes = wallet.accountUuid
                )
            }
        }

    @Test
    fun generate_note_witnesses_rejects_missing_wallet_db_path() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = witnessNotes()
                val treeState = backend.nonEmptyTreeStateFixtureForTesting()
                db.initRound(
                    roundId = PCZT_ROUND_ID,
                    snapshotHeight = 1,
                    eaPK = EA_PK,
                    ncRoot = backend.extractNcRoot(treeState),
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
                db.setupBundles(PCZT_ROUND_ID, notes)
                db.storeTreeState(PCZT_ROUND_ID, treeState)

                val missingWalletDbPath =
                    createTempDirectory("wallet-db-")
                        .resolve("missing-wallet.db")
                        .toFile()
                        .absolutePath

                assertRuntimeExceptionContains("open wallet DB read-only") {
                    db.generateNoteWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        walletDbPath = missingWalletDbPath,
                        networkId = TESTNET_NETWORK_ID,
                        notes = notes
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun generate_note_witnesses_rejects_empty_snapshot_frontier() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = witnessNotes()
                db.initRound(
                    roundId = PCZT_ROUND_ID,
                    snapshotHeight = 1,
                    eaPK = EA_PK,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(),
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
                db.setupBundles(PCZT_ROUND_ID, notes)
                db.storeTreeState(PCZT_ROUND_ID, backend.treeStateFixtureForTesting())

                assertRuntimeExceptionContains(
                    "empty orchard frontier - no Orchard activity at snapshot"
                ) {
                    db.generateNoteWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        walletDbPath = newDbPath(),
                        networkId = TESTNET_NETWORK_ID,
                        notes = notes
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun generate_hotkey_is_deterministic_and_rejects_short_seed() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initRound(
                    roundId = ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )

                val first = db.generateHotkey(ROUND_ID, HOTKEY_SEED)
                val second = db.generateHotkey(ROUND_ID, HOTKEY_SEED)
                val other = db.generateHotkey(ROUND_ID, OTHER_HOTKEY_SEED)

                assertContentEquals(first.publicKey.value, second.publicKey.value)
                assertEquals(first.address, second.address)
                assertFalse(first.publicKey.value.contentEquals(other.publicKey.value))
                assertEquals(FIELD_BYTES, first.publicKey.value.size)
                assertTrue(first.address.startsWith("sv1"))
                assertEquals(
                    JniRoundPhase.HOTKEY_GENERATED,
                    assertNotNull(db.getRoundState(ROUND_ID)).roundPhase
                )

                assertFailsWith<RuntimeException> {
                    db.generateHotkey(ROUND_ID, SHORT_FIELD)
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_rejects_mismatched_bundle_inputs_and_seed() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val mismatchedNotesJson = notes(noteCount = 1, value = PCZT_NOTE_VALUE)
                val mismatchedSameIndexNotesJson =
                    notes(noteCount = 6, value = PCZT_NOTE_VALUE, positionOffset = 10)
                val mismatchedSamePositionNotesJson =
                    notes(noteCount = 6, value = PCZT_NOTE_VALUE, ufvkString = "different")
                val ufvk = deriveTestUfvk()
                val mismatchedUfvk = deriveTestUfvk(seed = OTHER_HOTKEY_SEED)
                db.initPcztRoundWithBundles(notes)

                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePczt(ufvk, mismatchedNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePczt(ufvk, mismatchedSameIndexNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePczt(ufvk, mismatchedSamePositionNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztFromSeed(mismatchedUfvk, notes)
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_requires_hotkey_generated_phase() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initRound(
                    roundId = PCZT_ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
                db.setupBundles(PCZT_ROUND_ID, notes)

                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePczt(ufvk, notes)
                }
                assertEquals(
                    JniRoundPhase.INITIALIZED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_returns_parseable_pczt_and_extractable_sighash() =
        runTest {
            val backend = VotingRustBackend.new()
            val db = backend.openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initPcztRoundWithBundles(notes)

                val pczt = db.buildTestGovernancePczt(ufvk, notes)
                val extractedSighash = backend.extractPcztSighash(pczt.pcztBytes)

                assertTrue(pczt.pcztBytes.isNotEmpty())
                assertEquals(FIELD_BYTES, pczt.rk.size)
                assertEquals(FIELD_BYTES, pczt.sighash.size)
                assertTrue(pczt.actionIndex >= 0)
                assertContentEquals(pczt.sighash, extractedSighash)
                assertEquals(
                    JniRoundPhase.DELEGATION_CONSTRUCTED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
                assertFailsWith<RuntimeException> {
                    db.generateHotkey(PCZT_ROUND_ID, HOTKEY_SEED)
                }
                assertFailsWith<RuntimeException> {
                    backend.extractSpendAuthSig(pczt.pcztBytes, pczt.actionIndex)
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_explicit_and_seed_paths_produce_valid_pczts() =
        runTest {
            val backend = VotingRustBackend.new()
            val explicitDb = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            val seedDb = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                explicitDb.initPcztRoundWithBundles(notes)
                seedDb.initPcztRoundWithBundles(notes)

                val explicitPczt = explicitDb.buildTestGovernancePczt(ufvk, notes)
                val seedPczt = seedDb.buildTestGovernancePcztFromSeed(ufvk, notes)

                assertValidGovernancePczt(backend, explicitDb, explicitPczt)
                assertValidGovernancePczt(backend, seedDb, seedPczt)
            } finally {
                explicitDb.close()
                seedDb.close()
            }
        }

    @Test
    fun build_governance_pczt_from_seed_uses_wallet_account_but_hotkey_account_zero() =
        runTest {
            val explicitDb = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            val seedDb = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val accountIndex = 1
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk(accountIndex = accountIndex)
                explicitDb.initPcztRoundWithBundles(notes)
                seedDb.initPcztRoundWithBundles(notes)

                val explicitPczt =
                    explicitDb.buildTestGovernancePczt(
                        ufvk = ufvk,
                        notes = notes,
                        options = GovernancePcztOptions(accountIndex = accountIndex)
                    )
                val seedPczt =
                    seedDb.buildTestGovernancePcztFromSeed(
                        ufvk = ufvk,
                        notes = notes,
                        options = GovernancePcztOptions(accountIndex = accountIndex)
                    )

                val backend = VotingRustBackend.new()
                assertValidGovernancePczt(backend, explicitDb, explicitPczt)
                assertValidGovernancePczt(backend, seedDb, seedPczt)
            } finally {
                explicitDb.close()
                seedDb.close()
            }
        }

    @Test
    fun build_governance_pczt_from_seed_rejects_wallet_seed_that_does_not_match_ufvk() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initPcztRoundWithBundles(notes)

                val error =
                    assertFailsWith<RuntimeException> {
                        db.buildTestGovernancePcztFromSeed(
                            ufvk = ufvk,
                            notes = notes,
                            options = GovernancePcztOptions(walletSeed = OTHER_HOTKEY_SEED)
                        )
                    }

                assertTrue(error.message.orEmpty().contains("ufvk does not match walletSeed"))
                assertEquals(
                    JniRoundPhase.HOTKEY_GENERATED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_accepts_mainnet_network_id() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk(networkId = MAINNET_NETWORK_ID)
                db.initPcztRoundWithBundles(notes)

                val pczt =
                    db.buildTestGovernancePczt(
                        ufvk = ufvk,
                        notes = notes,
                        options = GovernancePcztOptions(networkId = MAINNET_NETWORK_ID)
                    )

                assertTrue(pczt.pcztBytes.isNotEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun store_witnesses_accepts_valid_witness_json() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = witnessNotes()
                db.initPcztRoundWithBundles(
                    notes,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray()
                )

                db.storeWitnesses(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 0,
                    notes = notes,
                    witnesses = witnesses()
                )

                assertEquals(
                    JniRoundPhase.HOTKEY_GENERATED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
                db.storeWitnesses(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 0,
                    notes = notes,
                    witnesses = witnesses()
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun store_witnesses_rejects_root_that_does_not_match_round() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = witnessNotes()
                db.initPcztRoundWithBundles(notes)

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        notes = notes,
                        witnesses = witnesses()
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun store_witnesses_rejects_witness_that_does_not_match_selected_note() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = witnessNotes()
                db.initPcztRoundWithBundles(
                    notes,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray()
                )

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        notes = notes,
                        witnesses = witnesses(noteCommitment = repeatedHex(9))
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun delegation_bridge_methods_reject_malformed_inputs_before_side_effects() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                db.initPcztRoundWithBundles(notes)

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        notes = notes,
                        witnesses =
                            witnesses(
                                authPathEntries = ORCHARD_WITNESS_PATH_DEPTH - 1
                            )
                    )
                }
                assertFailsWith<RuntimeException> {
                    db.precomputeDelegationPir(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        pirServerUrl = "http://127.0.0.1:1",
                        networkId = -1,
                        notes = notes
                    )
                }
                assertFailsWith<RuntimeException> {
                    db.buildAndProveDelegation(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        pirServerUrl = "http://127.0.0.1:1",
                        networkId = TESTNET_NETWORK_ID,
                        notes = notes,
                        hotkeyRawAddress = SHORT_FIELD,
                        proofProgress = null
                    )
                }
                assertFailsWith<RuntimeException> {
                    db.getDelegationSubmission(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        senderSeed = SHORT_FIELD,
                        networkId = TESTNET_NETWORK_ID,
                        accountIndex = ACCOUNT_INDEX
                    )
                }
                assertFailsWith<RuntimeException> {
                    db.getDelegationSubmissionWithKeystoneSig(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        keystoneSig = ByteArray(FIELD_BYTES),
                        keystoneSighash = ByteArray(FIELD_BYTES)
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_share_payloads_round_trips_commitment_fields() =
        runTest {
            val backend = VotingRustBackend.new()
            val commitment = jniVoteCommitmentResult()

            val payloads =
                backend.buildSharePayloads(
                    commitment = commitment,
                    voteDecision = 1,
                    numOptions = 2,
                    vcTreePosition = 42,
                    singleShareMode = false
                )

            assertEquals(JNI_VOTE_SHARE_COUNT, payloads.size)
            assertContentEquals(commitment.sharesHash, payloads.first().sharesHash)
            assertEquals(commitment.proposalId, payloads.first().proposalId)
            assertEquals(1, payloads.first().voteDecision)
            assertEquals(42, payloads.first().treePosition)
            assertEquals(commitment.encShares.first(), payloads.first().encShare)
            assertEquals(commitment.encShares, payloads.first().allEncShares)
            assertContentEquals(commitment.shareComms.first(), payloads.first().shareComms.first())
            assertContentEquals(commitment.shareBlinds.first(), payloads.first().primaryBlind)
        }

    @Test
    fun build_share_payloads_single_share_mode_keeps_commitment_context() =
        runTest {
            val backend = VotingRustBackend.new()
            val commitment = jniVoteCommitmentResult()

            val payloads =
                backend.buildSharePayloads(
                    commitment = commitment,
                    voteDecision = 1,
                    numOptions = 2,
                    vcTreePosition = 42,
                    singleShareMode = true
                )

            assertEquals(1, payloads.size)
            val payload = payloads.single()
            assertContentEquals(commitment.sharesHash, payload.sharesHash)
            assertEquals(commitment.proposalId, payload.proposalId)
            assertEquals(1, payload.voteDecision)
            assertEquals(42, payload.treePosition)
            assertEquals(0, payload.encShare.shareIndex)
            assertEquals(commitment.encShares.first(), payload.encShare)
            assertEquals(commitment.encShares, payload.allEncShares)
            assertEquals(JNI_VOTE_SHARE_COUNT, payload.allEncShares.size)
            assertContentEquals(commitment.shareComms.first(), payload.shareComms.first())
            assertContentEquals(commitment.shareBlinds.first(), payload.primaryBlind)
        }

    @Test
    fun build_share_payloads_rejects_malformed_share_counts() =
        runTest {
            val backend = VotingRustBackend.new()

            assertFailsWith<RuntimeException> {
                backend.buildSharePayloads(
                    commitment = jniVoteCommitmentResult(encShares = emptyList()),
                    voteDecision = 1,
                    numOptions = 2,
                    vcTreePosition = 42,
                    singleShareMode = false
                )
            }
        }

    @Test
    fun sign_cast_vote_returns_signature_for_account_zero() =
        runTest {
            val backend = VotingRustBackend.new()

            val signature =
                backend.signCastVote(
                    hotkeySeed = HOTKEY_SEED,
                    networkId = TESTNET_NETWORK_ID,
                    accountIndex = ACCOUNT_INDEX,
                    commitment = jniVoteCommitmentResult(alphaV = ByteArray(FIELD_BYTES))
                )

            assertEquals(JNI_SPEND_AUTH_SIG_BYTES_SIZE, signature.size)
        }

    @Test
    fun sign_cast_vote_rejects_unsupported_account_index() =
        runTest {
            val backend = VotingRustBackend.new()

            assertFailsWith<RuntimeException> {
                backend.signCastVote(
                    hotkeySeed = HOTKEY_SEED,
                    networkId = TESTNET_NETWORK_ID,
                    accountIndex = 1,
                    commitment = jniVoteCommitmentResult()
                )
            }
        }

    @Test
    fun sync_vote_tree_reaches_native_boundary() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initPcztRoundWithBundles(notes(noteCount = 6, value = PCZT_NOTE_VALUE))

                assertFailsWith<RuntimeException> {
                    db.syncVoteTree(PCZT_ROUND_ID, "not-a-url")
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun store_van_position_reaches_native_boundary() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initPcztRoundWithBundles(notes(noteCount = 6, value = PCZT_NOTE_VALUE))

                db.storeVanPosition(PCZT_ROUND_ID, bundleIndex = 1, position = 42)
            } finally {
                db.close()
            }
        }

    @Test
    fun generate_van_witness_reaches_native_boundary() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initPcztRoundWithBundles(notes(noteCount = 6, value = PCZT_NOTE_VALUE))
                db.storeVanPosition(PCZT_ROUND_ID, bundleIndex = 1, position = 42)

                assertFailsWith<RuntimeException> {
                    db.generateVanWitness(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        anchorHeight = SNAPSHOT_HEIGHT
                    )
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_vote_commitment_requires_delegation_ready() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initPcztRoundWithBundles(notes)
                db.buildTestGovernancePczt(ufvk, notes)

                assertFailsWith<RuntimeException> {
                    db.buildVoteCommitment(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        hotkeySeed = HOTKEY_SEED,
                        proposalId = 1,
                        choice = 0,
                        numOptions = 2,
                        witness = jniVanWitness(),
                        networkId = TESTNET_NETWORK_ID,
                        accountIndex = ACCOUNT_INDEX,
                        singleShare = false,
                        proofProgress = null
                    )
                }

                assertEquals(emptyList(), db.getVotes(PCZT_ROUND_ID).asList())
                assertEquals(
                    JniRoundPhase.DELEGATION_CONSTRUCTED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun recovery_state_round_trips_through_native_jni() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                db.initPcztRoundWithBundles(notes(noteCount = 6, value = PCZT_NOTE_VALUE))
                db.storeVoteFixtureForTesting(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 1,
                    proposalId = 1,
                    choice = 0
                )

                db.assertStoredTxHashesRoundTrip()
                db.assertStoredCommitmentBundleRoundTrips()
                db.assertShareDelegationRecoveryStateRoundTrips()
                db.clearRecoveryState(PCZT_ROUND_ID)
                db.assertRecoveryStateCleared()
            } finally {
                db.close()
            }
        }

    @Test
    fun delegation_proof_result_fixture_crosses_rust_to_kotlin_object_construction() =
        runTest {
            val result =
                VotingRustBackend
                    .new()
                    .delegationProofResultFixtureForTesting()

            assertEquals(96, result.proof.size)
            assertEquals(14, result.publicInputs.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x10 }, result.publicInputs.first())
            assertEquals(5, result.govNullifiers.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x42 }, result.rk)
        }

    @Test
    fun note_and_witness_array_fixtures_cross_rust_to_kotlin_construction() =
        runTest {
            val backend = VotingRustBackend.new()

            val note = backend.noteInfoArrayFixtureForTesting().single()
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x01 }, note.commitment)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x02 }, note.nullifier)
            assertEquals(123_456L, note.value)
            assertEquals(7L, note.position)
            assertContentEquals(ByteArray(DIVERSIFIER_BYTES) { 0x03 }, note.diversifier)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x04 }, note.rho)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x05 }, note.rseed)
            assertEquals(1, note.scope)
            assertEquals("ufvk-fixture", note.ufvk)

            val witness = backend.witnessDataArrayFixtureForTesting().single()
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x11 }, witness.noteCommitment)
            assertEquals(9L, witness.position)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x12 }, witness.root)
            assertEquals(ORCHARD_WITNESS_PATH_DEPTH, witness.authPath.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x20 }, witness.authPath.first())
        }

    @Test
    fun get_delegation_submission_returns_success_result_through_jni() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notes = notes(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                val proof = ByteArray(96) { 0x7A }
                db.initPcztRoundWithBundles(notes)
                db.buildTestGovernancePczt(ufvk, notes)
                db.storeDelegationProofFixtureForTesting(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 1,
                    proof = proof
                )

                val submission =
                    db.getDelegationSubmission(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        senderSeed = HOTKEY_SEED,
                        networkId = TESTNET_NETWORK_ID,
                        accountIndex = ACCOUNT_INDEX
                    )

                assertContentEquals(proof, submission.proof)
                assertEquals(FIELD_BYTES, submission.rk.size)
                assertEquals(64, submission.spendAuthSig.size)
                assertEquals(FIELD_BYTES, submission.sighash.size)
                assertEquals(5, submission.govNullifiers.size)
                assertEquals(PCZT_ROUND_ID, submission.voteRoundId)

                assertFailsWith<RuntimeException> {
                    db.getDelegationSubmission(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        senderSeed = OTHER_HOTKEY_SEED,
                        networkId = TESTNET_NETWORK_ID,
                        accountIndex = ACCOUNT_INDEX
                    )
                }

                val keystoneSubmission =
                    db.getDelegationSubmissionWithKeystoneSig(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        keystoneSig = submission.spendAuthSig,
                        keystoneSighash = submission.sighash
                    )
                assertContentEquals(submission.proof, keystoneSubmission.proof)
                assertContentEquals(submission.spendAuthSig, keystoneSubmission.spendAuthSig)
                assertContentEquals(submission.sighash, keystoneSubmission.sighash)
                assertEquals(PCZT_ROUND_ID, keystoneSubmission.voteRoundId)
            } finally {
                db.close()
            }
        }

    private fun newDbPath() =
        createTempDirectory("voting-db-").resolve("voting.db").toFile().absolutePath

    private suspend fun VotingRustBackend.VotingDb.assertStoredTxHashesRoundTrip() {
        assertNull(getDelegationTxHash(PCZT_ROUND_ID, bundleIndex = 1))
        assertNull(getVoteTxHash(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1))

        storeDelegationTxHash(PCZT_ROUND_ID, bundleIndex = 1, txHash = "delegation-tx")
        assertEquals("delegation-tx", getDelegationTxHash(PCZT_ROUND_ID, bundleIndex = 1))

        storeVoteTxHash(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1, txHash = "vote-tx")
        assertEquals("vote-tx", getVoteTxHash(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1))

        markVoteSubmitted(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1)
        assertTrue(
            getVotes(PCZT_ROUND_ID)
                .single { vote ->
                    vote.bundleIndex == 1 && vote.proposalId == 1
                }.submitted
        )
    }

    private suspend fun VotingRustBackend.VotingDb.assertStoredCommitmentBundleRoundTrips() {
        assertNull(getCommitmentBundle(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1))

        val commitment = jniVoteCommitmentResult()
        storeCommitmentBundle(
            roundId = PCZT_ROUND_ID,
            bundleIndex = 1,
            proposalId = 1,
            commitment = commitment,
            vcTreePosition = 42
        )
        val recoveredCommitment =
            assertNotNull(
                getCommitmentBundle(
                    PCZT_ROUND_ID,
                    bundleIndex = 1,
                    proposalId = 1
                )
            )
        assertEquals(commitment, recoveredCommitment.commitment)
        assertEquals(42, recoveredCommitment.vcTreePosition)
    }

    private suspend fun VotingRustBackend.VotingDb.assertShareDelegationRecoveryStateRoundTrips() {
        val nullifier = ByteArray(FIELD_BYTES) { 0x55 }
        recordShareDelegation(
            roundId = PCZT_ROUND_ID,
            bundleIndex = 1,
            proposalId = 1,
            shareIndex = SHARE_INDEX,
            sentToUrls = listOf("https://helper-1.example"),
            nullifier = nullifier,
            submitAt = 123
        )
        assertRecordedShareDelegation(nullifier)

        addSentServers(
            roundId = PCZT_ROUND_ID,
            bundleIndex = 1,
            proposalId = 1,
            shareIndex = SHARE_INDEX,
            newUrls = listOf("https://helper-1.example", "https://helper-2.example")
        )
        assertEquals(
            listOf("https://helper-1.example", "https://helper-2.example"),
            getShareDelegations(PCZT_ROUND_ID).single().sentToUrls
        )

        markShareConfirmed(
            PCZT_ROUND_ID,
            bundleIndex = 1,
            proposalId = 1,
            shareIndex = SHARE_INDEX
        )
        assertTrue(getShareDelegations(PCZT_ROUND_ID).single().confirmed)
        assertEquals(emptyList(), getUnconfirmedDelegations(PCZT_ROUND_ID).asList())
    }

    private suspend fun VotingRustBackend.VotingDb.assertRecordedShareDelegation(
        nullifier: ByteArray
    ) {
        val recordedShare = getShareDelegations(PCZT_ROUND_ID).single()
        assertEquals(SHARE_INDEX, recordedShare.shareIndex)
        assertEquals(listOf("https://helper-1.example"), recordedShare.sentToUrls)
        assertContentEquals(nullifier, recordedShare.nullifier)
        assertFalse(recordedShare.confirmed)
        assertEquals(123, recordedShare.submitAt)
        assertEquals(1, getUnconfirmedDelegations(PCZT_ROUND_ID).size)
    }

    private suspend fun VotingRustBackend.VotingDb.assertRecoveryStateCleared() {
        assertNull(getDelegationTxHash(PCZT_ROUND_ID, bundleIndex = 1))
        assertNull(getVoteTxHash(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1))
        assertNull(getCommitmentBundle(PCZT_ROUND_ID, bundleIndex = 1, proposalId = 1))
        assertEquals(emptyList(), getShareDelegations(PCZT_ROUND_ID).asList())
    }

    private suspend fun newWalletDbWithAccount(): WalletDbFixture {
        val walletDbFile =
            createTempDirectory("wallet-db-")
                .resolve("data.db")
                .toFile()
        val rustBackend =
            RustBackend.new(
                fsBlockDbRoot = createTempDirectory("fs-block-db-").toFile(),
                dataDbFile = walletDbFile,
                saplingSpendFile =
                    createTempDirectory("sapling-spend-")
                        .resolve("sapling-spend.params")
                        .toFile(),
                saplingOutputFile =
                    createTempDirectory("sapling-output-")
                        .resolve("sapling-output.params")
                        .toFile(),
                zcashNetworkId = TESTNET_NETWORK_ID
            )
        assertEquals(0, rustBackend.initDataDb(HOTKEY_SEED))

        val account =
            rustBackend.createAccount(
                accountName = "account",
                keySource = null,
                seed = HOTKEY_SEED,
                treeState = VotingRustBackend.new().treeStateFixtureForTesting(),
                recoverUntil = null
            )

        return WalletDbFixture(walletDbFile.absolutePath, account.accountUuid)
    }

    private fun walletBirthdayHeight(walletDbPath: String): Long =
        SQLiteDatabase
            .openDatabase(walletDbPath, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery("SELECT MIN(birthday_height) FROM accounts", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }

    private fun markWalletScannedThrough(walletDbPath: String, fullyScannedHeight: Long) {
        val birthdayHeight = walletBirthdayHeight(walletDbPath)
        require(fullyScannedHeight >= birthdayHeight)

        SQLiteDatabase
            .openDatabase(walletDbPath, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                val blockValues =
                    ContentValues().apply {
                        put("height", fullyScannedHeight)
                        put("hash", ByteArray(FIELD_BYTES) { fullyScannedHeight.toByte() })
                        put("time", 0L)
                        put("sapling_tree", byteArrayOf(0))
                        put("sapling_commitment_tree_size", 0)
                        put("orchard_commitment_tree_size", 0)
                        put("sapling_output_count", 0)
                        put("orchard_action_count", 0)
                    }
                db.insertWithOnConflict(
                    "blocks",
                    null,
                    blockValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                db.delete("scan_queue", null, null)
                val scanValues =
                    ContentValues().apply {
                        put("block_range_start", birthdayHeight)
                        put("block_range_end", fullyScannedHeight + 1)
                        put("priority", SCANNED_PRIORITY)
                    }
                db.insert("scan_queue", null, scanValues)
            }
    }

    private fun downgradeAccountToUivkOnly(walletDbPath: String, accountUuid: ByteArray) {
        SQLiteDatabase
            .openDatabase(walletDbPath, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                db.execSQL(
                    """
                    UPDATE accounts
                    SET account_kind = 1,
                        has_spend_key = 0,
                        ufvk = NULL
                    WHERE uuid = ?
                    """.trimIndent(),
                    arrayOf(accountUuid)
                )
            }
    }

    private suspend fun assertRuntimeExceptionContains(
        expectedMessage: String,
        block: suspend () -> Unit
    ) {
        val error =
            try {
                block()
                null
            } catch (e: RuntimeException) {
                e
            }
        assertNotNull(error)
        assertTrue(
            error.message.orEmpty().contains(expectedMessage),
            "Expected '${error.message}' to contain '$expectedMessage'"
        )
    }

    private suspend fun deriveTestUfvk(
        seed: ByteArray = HOTKEY_SEED,
        networkId: Int = TESTNET_NETWORK_ID,
        accountIndex: Int = ACCOUNT_INDEX
    ): String =
        RustDerivationTool
            .new()
            .deriveUnifiedFullViewingKeys(seed, networkId, accountIndex + 1)
            .last()

    private suspend fun VotingRustBackend.VotingDb.initPcztRoundWithBundles(
        notes: List<JniNoteInfo>,
        roundId: String = PCZT_ROUND_ID,
        ncRoot: ByteArray = NC_ROOT
    ) {
        initRound(
            roundId = roundId,
            snapshotHeight = SNAPSHOT_HEIGHT,
            eaPK = EA_PK,
            ncRoot = ncRoot,
            nullifierIMTRoot = NULLIFIER_IMT_ROOT,
            sessionJson = null
        )
        setupBundles(roundId, notes)
        generateHotkey(roundId, HOTKEY_SEED)
    }

    private suspend fun VotingRustBackend.VotingDb.buildTestGovernancePczt(
        ufvk: String,
        notes: List<JniNoteInfo>,
        options: GovernancePcztOptions = GovernancePcztOptions()
    ): JniGovernancePczt {
        val backend = VotingRustBackend.new()
        return buildGovernancePczt(
            roundId = options.roundId,
            bundleIndex = 1,
            fvkBytes = backend.extractOrchardFvkFromUfvk(ufvk, options.networkId),
            hotkeyRawAddress = backend.deriveHotkeyRawAddress(options.hotkeySeed, options.networkId),
            networkId = options.networkId,
            accountIndex = options.accountIndex,
            notes = notes,
            seedFingerprint = SEED_FINGERPRINT,
            roundName = ROUND_NAME
        )
    }

    private suspend fun VotingRustBackend.VotingDb.buildTestGovernancePcztFromSeed(
        ufvk: String,
        notes: List<JniNoteInfo>,
        options: GovernancePcztOptions = GovernancePcztOptions()
    ) = buildGovernancePcztFromSeed(
        roundId = options.roundId,
        bundleIndex = 1,
        ufvk = ufvk,
        networkId = options.networkId,
        accountIndex = options.accountIndex,
        notes = notes,
        walletSeed = options.walletSeed,
        hotkeySeed = options.hotkeySeed,
        seedFingerprint = SEED_FINGERPRINT,
        roundName = ROUND_NAME
    )

    private suspend fun assertValidGovernancePczt(
        backend: VotingRustBackend,
        db: VotingRustBackend.VotingDb,
        pczt: JniGovernancePczt
    ) {
        assertTrue(pczt.pcztBytes.isNotEmpty())
        assertEquals(FIELD_BYTES, pczt.rk.size)
        assertEquals(FIELD_BYTES, pczt.sighash.size)
        assertTrue(pczt.actionIndex >= 0)
        assertContentEquals(pczt.sighash, backend.extractPcztSighash(pczt.pcztBytes))
        assertEquals(
            JniRoundPhase.DELEGATION_CONSTRUCTED,
            assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
        )
    }

    private class GovernancePcztOptions(
        val hotkeySeed: ByteArray = HOTKEY_SEED,
        val walletSeed: ByteArray = HOTKEY_SEED,
        val networkId: Int = TESTNET_NETWORK_ID,
        val roundId: String = PCZT_ROUND_ID,
        val accountIndex: Int = ACCOUNT_INDEX
    )

    private fun notes(
        noteCount: Int,
        value: Long = NOTE_VALUE,
        positionOffset: Long = 0,
        ufvkString: String = ""
    ): List<JniNoteInfo> =
        List(noteCount) { index ->
            note(
                value = value,
                position = positionOffset + index.toLong(),
                byteValue = index + 1,
                ufvkString = ufvkString
            )
        }

    private fun note(
        value: Long,
        position: Long,
        byteValue: Int,
        scope: Int = 0,
        ufvkString: String = ""
    ) = JniNoteInfo(
        commitment = ByteArray(FIELD_BYTES) { byteValue.toByte() },
        nullifier = ByteArray(FIELD_BYTES) { (byteValue + 1).toByte() },
        value = value,
        position = position,
        diversifier = ByteArray(DIVERSIFIER_BYTES),
        rho = ByteArray(FIELD_BYTES),
        rseed = ByteArray(FIELD_BYTES),
        scope = scope,
        ufvk = ufvkString
    )

    private fun witnessNotes() =
        listOf(
            note(
                value = PCZT_NOTE_VALUE,
                position = 0,
                byteValue = 1
            ).copy(commitment = EMPTY_ORCHARD_NOTE_COMMITMENT.hexToByteArray())
        )

    private fun witnesses(
        authPathEntries: Int = ORCHARD_WITNESS_PATH_DEPTH,
        noteCommitment: String = EMPTY_ORCHARD_NOTE_COMMITMENT,
        position: Long = 0
    ) = listOf(
        JniWitnessData(
            noteCommitment = noteCommitment.hexToByteArray(),
            position = position,
            root = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray(),
            authPath =
                List(authPathEntries) { index ->
                    EMPTY_ORCHARD_AUTH_PATH[index].hexToByteArray()
                }
        )
    )

    private fun jniVanWitness(
        position: Long = 1,
        anchorHeight: Long = SNAPSHOT_HEIGHT
    ) = JniVanWitness(
        authPath = List(JNI_VAN_WITNESS_PATH_DEPTH) { ByteArray(FIELD_BYTES) },
        position = position,
        anchorHeight = anchorHeight
    )

    private fun jniVoteCommitmentResult(
        encShares: List<JniWireEncryptedShare> = wireShares(),
        shareBlinds: List<ByteArray> = fieldElements(JNI_VOTE_SHARE_COUNT, 5),
        shareComms: List<ByteArray> = fieldElements(JNI_VOTE_SHARE_COUNT, 6),
        alphaV: ByteArray = ByteArray(FIELD_BYTES) { 8 }
    ) = JniVoteCommitmentResult(
        vanNullifier = ByteArray(FIELD_BYTES) { 1 },
        voteAuthorityNoteNew = ByteArray(FIELD_BYTES) { 2 },
        voteCommitment = ByteArray(FIELD_BYTES) { 3 },
        proposalId = 1,
        proof = byteArrayOf(4),
        encShares = encShares,
        anchorHeight = SNAPSHOT_HEIGHT,
        voteRoundId = PCZT_ROUND_ID,
        sharesHash = ByteArray(FIELD_BYTES) { 4 },
        shareBlinds = shareBlinds,
        shareComms = shareComms,
        rVpk = ByteArray(FIELD_BYTES) { 7 },
        alphaV = alphaV
    )

    private fun wireShares(
        count: Int = JNI_VOTE_SHARE_COUNT
    ) = List(count) { index ->
        JniWireEncryptedShare(
            c1 = ByteArray(FIELD_BYTES) { (index + 1).toByte() },
            c2 = ByteArray(FIELD_BYTES) { (index + 2).toByte() },
            shareIndex = index
        )
    }

    private fun fieldElements(
        count: Int,
        byteValue: Int
    ) = List(count) { ByteArray(FIELD_BYTES) { byteValue.toByte() } }

    private fun repeatedHex(
        byteValue: Int,
        size: Int = FIELD_BYTES
    ) = ByteArray(size) { byteValue.toByte() }.toHexString()

    private data class WalletDbFixture(
        val path: String,
        val accountUuid: ByteArray
    )
}
