package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.voting.JniDelegationPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundPhase
import cash.z.ecc.android.sdk.internal.model.voting.JniRoundState
import cash.z.ecc.android.sdk.internal.model.voting.JniVoteRecord
import cash.z.ecc.android.sdk.model.voting.VotingRoundPhase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VotingSdkImplTest {
    @Test
    fun isAvailable_returns_true_when_warmProvingCaches_succeeds() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val sdk = VotingSdkImpl(backend)

            assertTrue(sdk.isAvailable())
        }

    @Test
    fun isAvailable_returns_false_on_UnsatisfiedLinkError() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            `when`(backend.warmProvingCaches()).thenThrow(UnsatisfiedLinkError("no symbol"))
            val sdk = VotingSdkImpl(backend)

            assertFalse(sdk.isAvailable())
        }

    @Test
    fun openDb_wraps_the_returned_TypesafeVotingDb() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val sdk = VotingSdkImpl(backend)

            val session = sdk.openDb("path", "wallet-1", 0)

            session.close()
            org.mockito.Mockito
                .verify(votingDb)
                .close()
        }

    @Test
    fun getRoundState_maps_phase_and_fields() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(
                JniRoundState(
                    roundId = "round-1",
                    phase = JniRoundPhase.VOTE_READY.value,
                    snapshotHeight = 100L,
                    hotkeyAddress = "addr",
                    delegatedWeight = 5L,
                    proofGenerated = true
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val state = session.getRoundState("round-1")

            assertEquals(VotingRoundPhase.VOTE_READY, state?.phase)
            assertEquals("round-1", state?.roundId)
            assertEquals(5L, state?.delegatedWeight)
        }

    @Test
    fun getRoundState_returns_null_when_backend_returns_null() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getRoundState("round-1")).thenReturn(null)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            assertEquals(null, session.getRoundState("round-1"))
        }

    @Test
    fun getVotes_maps_every_record() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.getVotes("round-1")).thenReturn(
                listOf(
                    JniVoteRecord(proposalId = 1, bundleIndex = 0, choice = 1, submitted = true),
                    JniVoteRecord(proposalId = 2, bundleIndex = 1, choice = 0, submitted = false)
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val votes = session.getVotes("round-1")

            assertEquals(2, votes.size)
            assertTrue(votes[0].submitted)
            assertFalse(votes[1].submitted)
        }

    @Test
    fun delegationPhases_maps_every_bundle() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            `when`(votingDb.delegationPhases("round-1")).thenReturn(
                listOf(
                    JniDelegationPhase(bundleIndex = 0, phase = "proved"),
                    JniDelegationPhase(bundleIndex = 1, phase = "prepared")
                )
            )
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            val phases = session.delegationPhases("round-1")

            assertEquals(2, phases.size)
            assertEquals("proved", phases[0].phase)
            assertEquals(1, phases[1].bundleIndex)
        }

    @Test
    fun resetVotingSessionState_forwards_to_backend() =
        runBlocking {
            val backend = mock(TypesafeVotingBackend::class.java)
            val votingDb = mock(TypesafeVotingDb::class.java)
            `when`(backend.openVotingDb("path", "wallet-1", 0)).thenReturn(votingDb)
            val session = VotingSdkImpl(backend).openDb("path", "wallet-1", 0)

            session.resetVotingSessionState("round-1")

            org.mockito.Mockito
                .verify(votingDb)
                .resetVotingSessionState("round-1")
        }
}
