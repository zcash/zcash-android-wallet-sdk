package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.ProposalUnsafe

/**
 * A transaction proposal created by the Rust backend in response to a Kotlin request.
 *
 * @param inner the type-unsafe Proposal protobuf received across the FFI.
 */
class Proposal(
    private val inner: ProposalUnsafe
) {
    companion object {
        /**
         * @throws IllegalArgumentException if the proposal is invalid.
         */
        @Throws(IllegalArgumentException::class)
        fun fromUnsafe(proposal: ProposalUnsafe) =
            Proposal(proposal).also {
                it.check()
            }

        /**
         * @throws IllegalArgumentException if the given [ByteArray] data could not be parsed and mapped to the new
         * type-safe Proposal class.
         */
        @Throws(IllegalArgumentException::class)
        fun fromByteArray(array: ByteArray) = fromUnsafe(ProposalUnsafe.parse(array))
    }

    // Check for type errors eagerly, to ensure that the caller won't encounter these errors later.
    private fun check() {
        totalFeeRequired()
    }

    /**
     * Exposes the type-unsafe proposal variant for passing across the FFI.
     */
    fun toUnsafe(): ProposalUnsafe = inner

    /**
     * Serializes this proposal type-safe data to [ByteArray] for storing purposes.
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
    fun transactionCount(): Int = inner.transactionCount()

    /**
     * Returns the total fee required by this proposal for its transactions.
     */
    fun totalFeeRequired(): Zatoshi = Zatoshi(inner.totalFeeRequired())

    /**
     * Returns whether this proposal directly spends any Orchard note. See
     * [ProposalUnsafe.usesOrchardInputs] for exactly what's (and isn't) detected.
     */
    fun usesOrchardInputs(): Boolean = inner.usesOrchardInputs()

    fun toPrettyString(): String = "Transaction count: ${transactionCount()}, Total fee required: ${totalFeeRequired()}"
}
