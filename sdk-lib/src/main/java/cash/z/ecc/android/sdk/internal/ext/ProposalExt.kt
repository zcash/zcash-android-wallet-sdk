package cash.z.ecc.android.sdk.internal.ext

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.model.Proposal

/**
 * Guards the PCZT (external signer) flow against proposals it cannot express. A PCZT carries exactly
 * one transaction, so a proposal that needs several steps - which in this wallet only a TEX (ZIP-320)
 * payment produces - can never be fulfilled through an external signer. The Rust layer rejects the
 * same shape, but untyped; the step count is available here, so the failure is detected structurally
 * and reported as [PcztException.MultiStepProposalUnsupportedException] before any FFI call is made.
 *
 * @throws PcztException.MultiStepProposalUnsupportedException if the proposal needs more than one
 * transaction
 */
@Throws(PcztException.MultiStepProposalUnsupportedException::class)
internal fun Proposal.requireSingleStepForPczt() {
    if (transactionCount() > 1) {
        throw PcztException.MultiStepProposalUnsupportedException()
    }
}
