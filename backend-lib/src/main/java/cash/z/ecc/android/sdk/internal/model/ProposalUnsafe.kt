package cash.z.ecc.android.sdk.internal.model

import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.FeeRule
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.Proposal
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ValuePool

/**
 * A transaction proposal created by the Rust backend in response to a Kotlin request.
 *
 * @param inner the parsed Proposal protobuf received across the FFI.
 */
class ProposalUnsafe(
    private val inner: Proposal
) {
    init {
        require(inner.feeRule != FeeRule.FeeRuleNotSpecified) {
            "Fee rule must be specified"
        }
    }

    companion object {
        /**
         * Parses a Proposal protobuf received across the FFI.
         *
         * @throws com.google.protobuf.InvalidProtocolBufferException
         */
        @Throws(com.google.protobuf.InvalidProtocolBufferException::class)
        fun parse(encoded: ByteArray): ProposalUnsafe {
            val inner = Proposal.parseFrom(encoded)
            return ProposalUnsafe(inner)
        }
    }

    /**
     * Serializes this proposal for passing back across the FFI.
     */
    fun toByteArray(): ByteArray = inner.toByteArray()

    /**
     * Returns the number of transactions that this proposal will create.
     *
     * This is equal to the number of `TransactionSubmitResult`s that will be returned
     * from `Synchronizer.createProposedTransactions`.
     *
     * Proposals always create at least one transaction.
     */
    fun transactionCount(): Int = inner.stepsCount

    /**
     * Returns the total fee required by this proposal for its transactions.
     */
    fun totalFeeRequired(): Long = inner.stepsList.fold(0) { acc, step -> acc + step.balance.feeRequired }

    /**
     * Returns whether any step of this proposal directly spends an Orchard note — i.e. an input
     * whose [ProposedInput] carries a [ReceivedOutput] with [ValuePool.Orchard], not a
     * back-reference to a prior step's own output ([PriorStepOutput]/[PriorStepChange]). Used to
     * warn the user before an ordinary (non-migration) send that would spend Orchard funds, since
     * Orchard's current shielded-pool composition can leak the transaction amount on-chain in a
     * way Sapling-only spends don't (see the app's Orchard Privacy Warning). A multi-step proposal
     * whose only Orchard involvement is via a prior-step back-reference is not detected here — that
     * shape doesn't arise from this wallet's own ordinary send construction.
     */
    fun usesOrchardInputs(): Boolean =
        inner.stepsList.any { step ->
            step.inputsList.any { input ->
                input.hasReceivedOutput() && input.receivedOutput.valuePool == ValuePool.Orchard
            }
        }
}
