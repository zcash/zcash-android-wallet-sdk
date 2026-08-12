package cash.z.ecc.android.sdk.internal.model

internal data class RecoveryProgress(
    val numerator: Long,
    val denominator: Long
) : Progress(numerator, denominator) {
    override fun toString() = "RecoveryProgress($numerator/$denominator) -> ${getSafeRatio()}"

    companion object {
        fun new(jni: JniWalletSummary): RecoveryProgress? =
            jni.recoveryProgressNumerator?.let { numerator ->
                jni.recoveryProgressDenominator?.let { denominator ->
                    RecoveryProgress(
                        numerator = numerator,
                        denominator = denominator
                    )
                }
            }
    }
}
