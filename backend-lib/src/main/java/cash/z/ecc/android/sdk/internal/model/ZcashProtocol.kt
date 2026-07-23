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
    },
    // Ironwood (NU6.3): must match zcash_client_sqlite's own pool-type encoding exactly
    // (PoolType::Shielded(ShieldedPool::Ironwood) => 4i64 in wallet/encoding.rs) — this enum has
    // no other way to stay in sync with that Rust-side mapping.
    IRONWOOD {
        override val poolCode = 4
    };

    abstract val poolCode: Int

    fun isShielded() = this == SAPLING || this == ORCHARD || this == IRONWOOD

    companion object {
        fun validate(poolTypeCode: Int): Boolean =
            when (poolTypeCode) {
                TRANSPARENT.poolCode,
                SAPLING.poolCode,
                ORCHARD.poolCode,
                IRONWOOD.poolCode -> true

                else -> false
            }

        fun fromPoolType(poolCode: Int): ZcashProtocol =
            when (poolCode) {
                TRANSPARENT.poolCode -> TRANSPARENT
                SAPLING.poolCode -> SAPLING
                ORCHARD.poolCode -> ORCHARD
                IRONWOOD.poolCode -> IRONWOOD
                else -> error("Unsupported pool type: $poolCode")
            }
    }
}
