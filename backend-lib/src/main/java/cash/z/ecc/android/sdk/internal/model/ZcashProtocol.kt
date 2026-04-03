package cash.z.ecc.android.sdk.internal.model

/**
 * An enumeration of supported Zcash protocols.
 */
@Suppress("MagicNumber")
enum class ZcashProtocol {
    TRANSPARENT {
        override val poolCode = 0
    },
    SAPLING {
        override val poolCode = 2
    },
    ORCHARD {
        override val poolCode = 3
    };

    abstract val poolCode: Int

    fun isShielded() = this == SAPLING || this == ORCHARD

    companion object {
        fun validate(poolTypeCode: Int): Boolean =
            when (poolTypeCode) {
                TRANSPARENT.poolCode,
                SAPLING.poolCode,
                ORCHARD.poolCode -> true

                else -> false
            }

        fun fromPoolType(poolCode: Int): ZcashProtocol =
            when (poolCode) {
                TRANSPARENT.poolCode -> TRANSPARENT
                SAPLING.poolCode -> SAPLING
                ORCHARD.poolCode -> ORCHARD
                else -> error("Unsupported pool type: $poolCode")
            }
    }
}
