package cash.z.ecc.android.sdk.model.voting

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
