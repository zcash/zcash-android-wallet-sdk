package cash.z.ecc.android.sdk.model

sealed interface UnifiedAddressRequest {
    val flags: Int

    /**
     * The requested address can receive transparent p2pkh outputs.
     */
    @Suppress("MagicNumber")
    data object P2PKH : UnifiedAddressRequest {
        override val flags: Int = 1
    } // 0b00000001

    /**
     * The requested address can receive Sapling outputs.
     */
    @Suppress("MagicNumber")
    data object Sapling : UnifiedAddressRequest {
        override val flags: Int = 4
    } // 0b00000100

    /**
     * The requested address can receive Orchard outputs.
     */
    @Suppress("MagicNumber")
    data object Orchard : UnifiedAddressRequest {
        override val flags: Int = 8
    } // 0b00001000

    private data class Custom(
        override val flags: Int
    ) : UnifiedAddressRequest

    infix fun and(other: UnifiedAddressRequest): UnifiedAddressRequest = Custom(flags or other.flags)

    companion object {
        val all get() = P2PKH and Sapling and Orchard

        val shielded get() = Sapling and Orchard
    }
}
