package cash.z.ecc.android.sdk.internal.model

internal data class ScanProgress(
    val numerator: Long,
    val denominator: Long
) : Progress(numerator, denominator) {
    override fun toString() = "ScanProgress($numerator/$denominator) -> ${getSafeRatio()}"

    companion object {
        fun new(jni: JniWalletSummary): ScanProgress =
            ScanProgress(
                numerator = jni.scanProgressNumerator,
                denominator = jni.scanProgressDenominator
            )

        fun newMin(): ScanProgress = ScanProgress(0, 1)
    }
}
