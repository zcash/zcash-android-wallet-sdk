package cash.z.ecc.android.sdk.internal.model

import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.FeeRule
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ProposalStep
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ProposedInput
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ReceivedOutput
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.TransactionBalance
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ValuePool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.Proposal as ProposalProto

class ProposalUnsafeTest {
    private fun receivedOutputInput(valuePool: ValuePool) =
        ProposedInput
            .newBuilder()
            .setReceivedOutput(
                ReceivedOutput
                    .newBuilder()
                    .setValuePool(valuePool)
                    .setIndex(0)
                    .setValue(10_000L)
            ).build()

    private fun proposalWithInputs(vararg valuePools: ValuePool): ProposalUnsafe {
        val step =
            ProposalStep
                .newBuilder()
                .addAllInputs(valuePools.map { receivedOutputInput(it) })
                .setBalance(TransactionBalance.newBuilder().setFeeRequired(10_000L))
                .build()
        val proposal =
            ProposalProto
                .newBuilder()
                .setProtoVersion(1)
                .setFeeRule(FeeRule.Zip317)
                .setMinTargetHeight(1)
                .addSteps(step)
                .build()
        return ProposalUnsafe(proposal)
    }

    @Test
    fun sapling_only_inputs_do_not_use_orchard() {
        val proposal = proposalWithInputs(ValuePool.Sapling, ValuePool.Transparent)
        assertFalse(proposal.usesOrchardInputs())
    }

    @Test
    fun any_orchard_input_uses_orchard() {
        val proposal = proposalWithInputs(ValuePool.Sapling, ValuePool.Orchard)
        assertTrue(proposal.usesOrchardInputs())
    }

    @Test
    fun all_orchard_inputs_use_orchard() {
        val proposal = proposalWithInputs(ValuePool.Orchard, ValuePool.Orchard)
        assertTrue(proposal.usesOrchardInputs())
    }

    @Test
    fun no_inputs_do_not_use_orchard() {
        val proposal = proposalWithInputs()
        assertFalse(proposal.usesOrchardInputs())
    }
}
