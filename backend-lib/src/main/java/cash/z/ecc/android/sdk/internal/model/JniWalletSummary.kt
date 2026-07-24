package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep
import cash.z.ecc.android.sdk.internal.ext.isInUIntRange

/**
 * Serves as cross layer (Kotlin, Rust) communication class.
 *
 * @param accountBalances the balances of the wallet accounts
 * @param chainTipHeight the wallet's view of the current chain tip
 * @param fullyScannedHeight the height below which all blocks have been scanned
 *        by the wallet, ignoring blocks below the wallet birthday.
 * @param scanProgressNumerator the numerator of the scan progress ratio
 * @param scanProgressDenominator the denominator of the scan progress ratio. It might be 0 which means the overall
 * scan progress is 100%.
 * @param recoveryProgressNumerator the numerator of the recovery progress ratio
 * @param recoveryProgressDenominator the denominator of the recovery progress ratio. It might be 0 which means the
 * overall recovery progress is 100%.
 * @param nextSaplingSubtreeIndex the Sapling subtree index that should start
 *        the next range of subtree roots passed to `Backend.putSaplingSubtreeRoots`.
 * @param nextOrchardSubtreeIndex the Orchard subtree index that should start
 *        the next range of subtree roots passed to `Backend.putOrchardSubtreeRoots`.
 * @param nextIronwoodSubtreeIndex the Ironwood subtree index that should start
 *        the next range of subtree roots passed to `Backend.putSubtreeRoots`.
 * @throws IllegalArgumentException unless (progressNumerator is nonnegative,
 *         progressDenominator is positive, and the represented ratio is in the
 *         range 0.0 to 1.0 inclusive).
 */
@Keep
@Suppress("LongParameterList")
class JniWalletSummary(
    val accountBalances: Array<JniAccountBalance>,
    val chainTipHeight: Long,
    val fullyScannedHeight: Long,
    val scanProgressNumerator: Long,
    val scanProgressDenominator: Long,
    val recoveryProgressNumerator: Long?,
    val recoveryProgressDenominator: Long?,
    val nextSaplingSubtreeIndex: Long,
    val nextOrchardSubtreeIndex: Long,
    val nextIronwoodSubtreeIndex: Long,
) {
    init {
        require(chainTipHeight.isInUIntRange()) {
            "Height $chainTipHeight is outside of allowed UInt range"
        }
        require(fullyScannedHeight.isInUIntRange()) {
            "Height $fullyScannedHeight is outside of allowed UInt range"
        }
        require(scanProgressNumerator >= 0L) {
            "Numerator $scanProgressNumerator is outside of allowed range [0, Long.MAX_VALUE]"
        }
        require(scanProgressDenominator >= 0L) {
            "Denominator $scanProgressDenominator is outside of allowed range [0, Long.MAX_VALUE]"
        }
        scanProgressDenominator.takeIf { it > 0L }?.run {
            require(scanProgressNumerator.toFloat().div(scanProgressDenominator) >= 0f) {
                "Result of ${scanProgressNumerator.toFloat()}/$scanProgressDenominator is outside of allowed range"
            }
            require(scanProgressNumerator.toFloat().div(scanProgressDenominator) <= 1f) {
                "Result of ${scanProgressNumerator.toFloat()}/$scanProgressDenominator is outside of allowed range"
            }
        }
        if (recoveryProgressNumerator != null && recoveryProgressDenominator != null) {
            require(recoveryProgressNumerator >= 0L) {
                "Numerator $recoveryProgressNumerator is outside of allowed range [0, Long.MAX_VALUE]"
            }
            require(recoveryProgressDenominator >= 0L) {
                "Denominator $recoveryProgressDenominator is outside of allowed range [0, Long.MAX_VALUE]"
            }
            recoveryProgressDenominator.takeIf { it > 0L }?.run {
                require(recoveryProgressNumerator.toFloat().div(recoveryProgressDenominator) >= 0f) {
                    "Result of ${recoveryProgressNumerator.toFloat()}/$recoveryProgressDenominator is outside of " +
                        "allowed range"
                }
                require(recoveryProgressNumerator.toFloat().div(recoveryProgressDenominator) <= 1f) {
                    "Result of ${recoveryProgressNumerator.toFloat()}/$recoveryProgressDenominator is outside of " +
                        "allowed range"
                }
            }
        } else {
            require(recoveryProgressNumerator == null) {
                "Recovery numerator must be null when denominator is null"
            }
            require(recoveryProgressDenominator == null) {
                "Recovery denominator must be null when numerator is null"
            }
        }
        require(nextSaplingSubtreeIndex.isInUIntRange()) {
            "Height $nextSaplingSubtreeIndex is outside of allowed UInt range"
        }
        require(nextOrchardSubtreeIndex.isInUIntRange()) {
            "Height $nextOrchardSubtreeIndex is outside of allowed UInt range"
        }
        require(nextIronwoodSubtreeIndex.isInUIntRange()) {
            "Height $nextIronwoodSubtreeIndex is outside of allowed UInt range"
        }
    }
}
