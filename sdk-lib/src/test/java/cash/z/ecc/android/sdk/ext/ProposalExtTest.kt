package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.internal.ext.requireSingleStepForPczt
import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe
import cash.z.ecc.android.sdk.model.Proposal
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A PCZT carries exactly one transaction, so the step count of a proposal - not the wording of a Rust
 * error - decides whether an external signer can fulfill it. Only TEX (ZIP-320) payments produce more
 * than one step in this wallet.
 */
class ProposalExtTest {
    @Test
    fun single_step_proposal_passes_the_pczt_guard() {
        proposal(transactionCount = 1).requireSingleStepForPczt()
    }

    @Test
    fun multi_step_proposal_is_rejected_by_the_pczt_guard() {
        assertFailsWith<PcztException.MultiStepProposalUnsupportedException> {
            proposal(transactionCount = 2).requireSingleStepForPczt()
        }
    }

    private fun proposal(transactionCount: Int): Proposal {
        val proposalUnsafe = mock(ProposalUnsafe::class.java)
        `when`(proposalUnsafe.totalFeeRequired()).thenReturn(FEE)
        `when`(proposalUnsafe.transactionCount()).thenReturn(transactionCount)
        return Proposal.fromUnsafe(proposalUnsafe)
    }

    private companion object {
        const val FEE = 1_000L
    }
}
