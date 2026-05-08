package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.ext.toHexReversed

/**
 * A transaction created and stored locally by the SDK, with raw bytes available
 * for later submission to one or more lightwalletd endpoints.
 */
data class CreatedTransaction(
    val txId: FirstClassByteArray,
    val raw: FirstClassByteArray,
    val expiryHeight: BlockHeight?
) {
    fun txIdString() = txId.byteArray.toHexReversed()
}
