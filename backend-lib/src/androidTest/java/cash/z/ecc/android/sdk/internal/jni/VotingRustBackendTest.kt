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
@Suppress("MagicNumber")
class VotingRustBackendTest {
    companion object {
        private const val FIELD_BYTES = 32
        private const val SHARE_INDEX = 5
        private const val OUT_OF_RANGE_SHARE_INDEX = 16
        private const val DIVERSIFIER_BYTES = 11
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

                assertContentEquals(first.secretKey.value, second.secretKey.value)
                assertContentEquals(first.publicKey.value, second.publicKey.value)
                assertEquals(first.address, second.address)
                assertFalse(first.secretKey.value.contentEquals(other.secretKey.value))
                assertFalse(first.publicKey.value.contentEquals(other.publicKey.value))
                assertEquals(FIELD_BYTES, first.secretKey.value.size)
                assertEquals(FIELD_BYTES, first.publicKey.value.size)
                assertTrue(first.address.startsWith("sv1"))
                assertEquals(
                    JniRoundPhase.HOTKEY_GENERATED,
                    assertNotNull(db.getRoundState(ROUND_ID)).roundPhase
                )

                first.secretKey.clear()
                assertTrue(first.secretKey.value.all { it == 0.toByte() })

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
                    FfiRoundPhase.INITIALIZED,
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
        roundId: String = PCZT_ROUND_ID
    ) {
        initRound(
            roundId = roundId,
            snapshotHeight = SNAPSHOT_HEIGHT,
            eaPK = EA_PK,
            ncRoot = NC_ROOT,
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

    private fun repeatedHex(
        byteValue: Int,
        size: Int = FIELD_BYTES
    ) = ByteArray(size) { byteValue.toByte() }.toHexString()
}
