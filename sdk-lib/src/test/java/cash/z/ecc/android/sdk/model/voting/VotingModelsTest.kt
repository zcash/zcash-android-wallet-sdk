package cash.z.ecc.android.sdk.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VotingModelsTest {
    @Test
    fun votingRoundPhase_ordinal_order_matches_JniRoundPhase() {
        val expectedOrder =
            listOf(
                VotingRoundPhase.INITIALIZED,
                VotingRoundPhase.HOTKEY_GENERATED,
                VotingRoundPhase.DELEGATION_CONSTRUCTED,
                VotingRoundPhase.DELEGATION_PROVED,
                VotingRoundPhase.VOTE_READY
            )
        assertEquals(expectedOrder, VotingRoundPhase.entries)
    }

    @Test
    fun votingNoteInfo_to_string_omits_note_secrets() {
        val text =
            VotingNoteInfo(
                commitment = byteArrayOf(1),
                nullifier = byteArrayOf(2),
                value = 3,
                position = 4,
                diversifier = byteArrayOf(5),
                rho = byteArrayOf(6),
                rseed = byteArrayOf(7),
                scope = VotingNoteScope.EXTERNAL,
                ufvk = "ufvk-fixture"
            ).toString()

        assertEquals("VotingNoteInfo(redacted)", text)
        assertFalse(text.contains("ufvk-fixture"))
    }

    @Test
    fun votingSharePayload_to_string_omits_primary_blind() {
        val text =
            VotingSharePayload(
                sharesHash = byteArrayOf(1),
                proposalId = 2,
                voteDecision = 3,
                encShare = VotingEncryptedShare(c1 = byteArrayOf(4), c2 = byteArrayOf(5), shareIndex = 0),
                treePosition = 6,
                allEncShares =
                    listOf(VotingEncryptedShare(c1 = byteArrayOf(4), c2 = byteArrayOf(5), shareIndex = 0)),
                shareComms = listOf(byteArrayOf(7)),
                primaryBlind = byteArrayOf(101)
            ).toString()

        assertEquals("VotingSharePayload(redacted)", text)
        assertFalse(text.contains("primaryBlind"))
        assertFalse(text.contains("101"))
    }

    @Test
    fun votingShareDelegationRecord_to_string_omits_nullifier() {
        val text =
            VotingShareDelegationRecord(
                roundId = "round-recovery",
                bundleIndex = 1,
                proposalId = 2,
                shareIndex = 3,
                sentToUrls = listOf("https://helper.example"),
                nullifier = byteArrayOf(101),
                confirmed = false,
                submitAt = 4,
                createdAt = 5
            ).toString()

        assertEquals("VotingShareDelegationRecord(redacted)", text)
        assertFalse(text.contains("nullifier"))
        assertFalse(text.contains("101"))
    }
}
