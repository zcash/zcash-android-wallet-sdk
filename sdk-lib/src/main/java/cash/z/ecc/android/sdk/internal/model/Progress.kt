package cash.z.ecc.android.sdk.internal.model

internal open class Progress(
    private val numerator: Long,
    private val denominator: Long
) {
    /**
     * Returns progress ratio in [0, 1] range. Any out-of-range value is treated as 0. Denominator equal to 0 is
     * interpreted as 100% progress.
     */
    fun getSafeRatio() =
        takeIf { denominator > 0L }?.run {
            numerator.toFloat().div(denominator).let { ratio ->
                if (ratio < 0f || ratio > 1f) {
                    0f
                } else {
                    ratio
                }
            }
        } ?: 1f
}
