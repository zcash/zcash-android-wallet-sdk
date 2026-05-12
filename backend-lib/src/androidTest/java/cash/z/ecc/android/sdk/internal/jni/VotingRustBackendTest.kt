package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalStdlibApi::class)
@Suppress("MagicNumber")
class VotingRustBackendTest {
    companion object {
        private const val FIELD_BYTES = 32
        private const val SHARE_INDEX = 5
        private const val OUT_OF_RANGE_SHARE_INDEX = 16
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
        private val EA_PK = ByteArray(FIELD_BYTES) { 3 }
        private val NC_ROOT = ByteArray(FIELD_BYTES) { 4 }
        private val NULLIFIER_IMT_ROOT = ByteArray(FIELD_BYTES) { 5 }
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

    private fun newDbPath() =
        createTempDirectory("voting-db-").resolve("voting.db").toFile().absolutePath
}
