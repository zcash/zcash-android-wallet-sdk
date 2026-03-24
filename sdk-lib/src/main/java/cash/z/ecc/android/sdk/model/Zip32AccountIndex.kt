package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.ext.isInUIntRange

/**
 * A typesafe wrapper class for ZIP 32 account index
 *
 * @param index The account ZIP 32 account identifier
 */
@ConsistentCopyVisibility
data class Zip32AccountIndex internal constructor(
    val index: Long
) {
    init {
        require(index.isInUIntRange()) {
            "Account index $index is outside of allowed UInt range"
        }
    }

    companion object {
        fun new(index: Long): Zip32AccountIndex = Zip32AccountIndex(index)
    }
}
