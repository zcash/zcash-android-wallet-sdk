package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.JniSingleUseTransparentAddress

/**
 * An ephemeral transparent address, along with metadata about the address's position within the
 * wallet's ephemeral gap limit.
 *
 * This address is for one-time use, such as when receiving a swap from a decentralized exchange.
 */
@ConsistentCopyVisibility
data class SingleUseTransparentAddress private constructor(
    /**
     * The ephemeral transparent address.
     */
    val address: String,
    val gapPosition: UInt,
    val gapLimit: UInt,
) {
    override fun toString() = "SingleUseTransparentAddress(..)"

    companion object {
        fun new(jni: JniSingleUseTransparentAddress): SingleUseTransparentAddress =
            SingleUseTransparentAddress(
                address = jni.address,
                gapPosition = jni.gapPosition.toUInt(),
                gapLimit = jni.gapLimit.toUInt(),
            )
    }
}
