package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.ext.isInUIntRange

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * @param startHeight the minimum height in the range (inclusive) - although it's type Long, it needs to be a UInt
 * @param endHeight the maximum height in the range (exclusive) - although it's type Long, it needs to be a UInt
 * @param priority the priority of the range for scanning
 */
@Keep
class JniScanRange(
    val startHeight: Long,
    val endHeight: Long,
    val priority: Long
) {
    init {
        // We require some of the parameters below to be in the range of unsigned integer, because of the Rust layer
        // implementation.
        require(startHeight.isInUIntRange()) {
            "Height $startHeight is outside of allowed UInt range"
        }
        require(endHeight.isInUIntRange()) {
            "Height $endHeight is outside of allowed UInt range"
        }
        // Note: endHeight < startHeight is intentionally allowed here. The Rust layer can return
        // empty or inverted ranges (e.g. after a resync reset), and ScanRange.new() handles this
        // case by returning null. Rejecting such ranges at this layer would cause the crashes that
        // MOB-1100 was intended to fix.
    }
}
