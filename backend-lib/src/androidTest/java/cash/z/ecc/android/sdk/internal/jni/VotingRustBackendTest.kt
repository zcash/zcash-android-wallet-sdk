package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
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
        private const val ORCHARD_WITNESS_PATH_DEPTH = 32
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
        private const val ADDRESS_INDEX = 1
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
    fun warm_proving_caches_smoke() =
        runTest {
            VotingRustBackend.new().warmProvingCaches()
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
            val setup = VotingRustBackend.new().computeBundleSetup(notesJson(noteCount = 6))

            assertEquals(2, setup.bundleCount)
            assertEquals(TWO_BUNDLE_ELIGIBLE_WEIGHT, setup.eligibleWeight)
            assertEquals(listOf(LARGE_BUNDLE_WEIGHT, SMALL_BUNDLE_WEIGHT), setup.bundleWeights)
            assertEquals(setup.eligibleWeight, setup.bundleWeights.sum())
        }

    @Test
    fun compute_bundle_setup_rejects_unknown_note_scope() =
        runTest {
            val notesJson =
                JSONArray()
                    .put(noteJson(value = NOTE_VALUE, position = 0, byteValue = 1, scope = 2))
                    .toString()

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notesJson)
            }
        }

    @Test
    fun compute_bundle_setup_rejects_malformed_diversifier() =
        runTest {
            val notesJson =
                JSONArray()
                    .put(
                        noteJson(value = NOTE_VALUE, position = 0, byteValue = 1)
                            .put("diversifier", repeatedHex(0, DIVERSIFIER_BYTES - 1))
                    ).toString()

            assertFailsWith<RuntimeException> {
                VotingRustBackend.new().computeBundleSetup(notesJson)
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

                val setup = db.setupBundles(ROUND_ID, notesJson(noteCount = 6))

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
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                val mismatchedNotesJson = notesJson(noteCount = 1, value = PCZT_NOTE_VALUE)
                val mismatchedSameIndexNotesJson =
                    notesJson(noteCount = 6, value = PCZT_NOTE_VALUE, positionOffset = 10)
                val mismatchedSamePositionNotesJson =
                    notesJson(noteCount = 6, value = PCZT_NOTE_VALUE, ufvkString = "different")
                val ufvk = deriveTestUfvk()
                val mismatchedUfvk = deriveTestUfvk(seed = OTHER_HOTKEY_SEED)
                db.initPcztRoundWithBundles(notesJson)

                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztJson(ufvk, mismatchedNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztJson(ufvk, mismatchedSameIndexNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztJson(ufvk, mismatchedSamePositionNotesJson)
                }
                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztJson(mismatchedUfvk, notesJson)
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
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initRound(
                    roundId = PCZT_ROUND_ID,
                    snapshotHeight = SNAPSHOT_HEIGHT,
                    eaPK = EA_PK,
                    ncRoot = NC_ROOT,
                    nullifierIMTRoot = NULLIFIER_IMT_ROOT,
                    sessionJson = null
                )
                db.setupBundles(PCZT_ROUND_ID, notesJson)

                assertFailsWith<RuntimeException> {
                    db.buildTestGovernancePcztJson(ufvk, notesJson)
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
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                db.initPcztRoundWithBundles(notesJson)

                val pcztJson =
                    JSONObject(db.buildTestGovernancePcztJson(ufvk, notesJson))
                val pcztBytes = pcztJson.getString("pczt_bytes").hexToByteArray()
                val sighash = pcztJson.getString("pczt_sighash").hexToByteArray()
                val extractedSighash = backend.extractPcztSighash(pcztBytes)

                assertTrue(pcztBytes.isNotEmpty())
                assertEquals(FIELD_BYTES, pcztJson.getString("rk").hexToByteArray().size)
                assertEquals(FIELD_BYTES, sighash.size)
                assertTrue(pcztJson.getInt("action_index") >= 0)
                assertContentEquals(sighash, extractedSighash)
                assertEquals(
                    JniRoundPhase.DELEGATION_CONSTRUCTED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
                assertFailsWith<RuntimeException> {
                    db.generateHotkey(PCZT_ROUND_ID, HOTKEY_SEED)
                }
                assertFailsWith<RuntimeException> {
                    backend.extractSpendAuthSig(pcztBytes, pcztJson.getInt("action_index"))
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun build_governance_pczt_accepts_mainnet_network_id() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk(networkId = MAINNET_NETWORK_ID)
                db.initPcztRoundWithBundles(notesJson)

                val pcztJson =
                    JSONObject(
                        db.buildTestGovernancePcztJson(
                            ufvk = ufvk,
                            notesJson = notesJson,
                            networkId = MAINNET_NETWORK_ID
                        )
                    )

                assertTrue(pcztJson.getString("pczt_bytes").hexToByteArray().isNotEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun store_witnesses_accepts_valid_witness_json() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notesJson = witnessNotesJson()
                db.initPcztRoundWithBundles(
                    notesJson,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray()
                )

                db.storeWitnesses(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 0,
                    notesJson = notesJson,
                    witnessesJson = witnessesJson()
                )

                assertEquals(
                    JniRoundPhase.HOTKEY_GENERATED,
                    assertNotNull(db.getRoundState(PCZT_ROUND_ID)).roundPhase
                )
                db.storeWitnesses(
                    roundId = PCZT_ROUND_ID,
                    bundleIndex = 0,
                    notesJson = notesJson,
                    witnessesJson = witnessesJson()
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
                val notesJson = witnessNotesJson()
                db.initPcztRoundWithBundles(notesJson)

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        notesJson = notesJson,
                        witnessesJson = witnessesJson()
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
                val notesJson = witnessNotesJson()
                db.initPcztRoundWithBundles(
                    notesJson,
                    ncRoot = EMPTY_ORCHARD_WITNESS_ROOT.hexToByteArray()
                )

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 0,
                        notesJson = notesJson,
                        witnessesJson = witnessesJson(noteCommitment = repeatedHex(9))
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
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                db.initPcztRoundWithBundles(notesJson)

                assertFailsWith<RuntimeException> {
                    db.storeWitnesses(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        notesJson = notesJson,
                        witnessesJson =
                            witnessesJson(
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
                        notesJson = notesJson
                    )
                }
                assertFailsWith<RuntimeException> {
                    db.buildAndProveDelegation(
                        roundId = PCZT_ROUND_ID,
                        bundleIndex = 1,
                        pirServerUrl = "http://127.0.0.1:1",
                        networkId = TESTNET_NETWORK_ID,
                        notesJson = notesJson,
                        walletSeed = SHORT_FIELD,
                        accountIndex = ACCOUNT_INDEX,
                        addressIndex = ADDRESS_INDEX,
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
    fun delegation_proof_result_fixture_crosses_rust_to_kotlin_object_construction() =
        runTest {
            val result = VotingRustBackend.new().delegationProofResultFixtureForTesting()

            assertEquals(96, result.proof.size)
            assertEquals(14, result.publicInputs.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x10 }, result.publicInputs.first())
            assertEquals(5, result.govNullifiers.size)
            assertContentEquals(ByteArray(FIELD_BYTES) { 0x42 }, result.rk)
        }

    @Test
    fun get_delegation_submission_returns_success_result_through_jni() =
        runTest {
            val db = VotingRustBackend.new().openVotingDb(newDbPath(), WALLET_ID)
            try {
                val notesJson = notesJson(noteCount = 6, value = PCZT_NOTE_VALUE)
                val ufvk = deriveTestUfvk()
                val proof = ByteArray(96) { 0x7A }
                db.initPcztRoundWithBundles(notesJson)
                db.buildTestGovernancePcztJson(ufvk, notesJson)
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

    private suspend fun deriveTestUfvk(
        seed: ByteArray = HOTKEY_SEED,
        networkId: Int = TESTNET_NETWORK_ID
    ): String =
        RustDerivationTool
            .new()
            .deriveUnifiedFullViewingKeys(seed, networkId, 1)
            .first()

    private suspend fun VotingRustBackend.VotingDb.initPcztRoundWithBundles(
        notesJson: String,
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
        setupBundles(roundId, notesJson)
        generateHotkey(roundId, HOTKEY_SEED)
    }

    private suspend fun VotingRustBackend.VotingDb.buildTestGovernancePcztJson(
        ufvk: String,
        notesJson: String,
        walletSeed: ByteArray = HOTKEY_SEED,
        networkId: Int = TESTNET_NETWORK_ID,
        roundId: String = PCZT_ROUND_ID
    ) = buildGovernancePcztJson(
        roundId = roundId,
        bundleIndex = 1,
        ufvk = ufvk,
        networkId = networkId,
        accountIndex = ACCOUNT_INDEX,
        notesJson = notesJson,
        walletSeed = walletSeed,
        seedFingerprint = SEED_FINGERPRINT,
        roundName = ROUND_NAME,
        addressIndex = ADDRESS_INDEX
    )

    private fun notesJson(
        noteCount: Int,
        value: Long = NOTE_VALUE,
        positionOffset: Long = 0,
        ufvkString: String = ""
    ): String =
        JSONArray()
            .apply {
                repeat(noteCount) { index ->
                    put(
                        noteJson(
                            value = value,
                            position = positionOffset + index.toLong(),
                            byteValue = index + 1,
                            ufvkString = ufvkString
                        )
                    )
                }
            }.toString()

    private fun noteJson(
        value: Long,
        position: Long,
        byteValue: Int,
        scope: Int = 0,
        ufvkString: String = ""
    ) = JSONObject()
        .put("commitment", repeatedHex(byteValue))
        .put("nullifier", repeatedHex(byteValue + 1))
        .put("value", value)
        .put("position", position)
        .put("diversifier", repeatedHex(0, DIVERSIFIER_BYTES))
        .put("rho", repeatedHex(0))
        .put("rseed", repeatedHex(0))
        .put("scope", scope)
        .put("ufvk_str", ufvkString)

    private fun witnessNotesJson() =
        JSONArray()
            .put(
                noteJson(
                    value = PCZT_NOTE_VALUE,
                    position = 0,
                    byteValue = 1
                ).put("commitment", EMPTY_ORCHARD_NOTE_COMMITMENT)
            ).toString()

    private fun witnessesJson(
        authPathEntries: Int = ORCHARD_WITNESS_PATH_DEPTH,
        noteCommitment: String = EMPTY_ORCHARD_NOTE_COMMITMENT,
        position: Long = 0
    ) =
        JSONArray()
            .put(
                JSONObject()
                    .put("note_commitment", noteCommitment)
                    .put("position", position)
                    .put("root", EMPTY_ORCHARD_WITNESS_ROOT)
                    .put(
                        "auth_path",
                        JSONArray().apply {
                            repeat(authPathEntries) { index ->
                                put(EMPTY_ORCHARD_AUTH_PATH[index])
                            }
                        }
                    )
            ).toString()

    private fun repeatedHex(
        byteValue: Int,
        size: Int = FIELD_BYTES
    ) = ByteArray(size) { byteValue.toByte() }.toHexString()
}
