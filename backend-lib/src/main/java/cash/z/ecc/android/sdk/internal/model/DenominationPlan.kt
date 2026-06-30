package cash.z.ecc.android.sdk.internal.model

/**
 * A plan for splitting a spendable Orchard balance into round-ZEC-denominated
 * outputs ahead of an Orchard -> Ironwood migration transfer.
 *
 * @param migrationOutputs round-ZEC-denominated output values, descending order of denomination.
 * @param orchardChange sub-ZEC residual kept as Orchard change rather than migrated, when it's too
 *   small to be worth a dedicated migration output but still above the minimum output threshold.
 */
data class DenominationPlan(
    val migrationOutputs: List<Long>,
    val orchardChange: Long?,
    val prepFeeZatoshi: Long,
    val migrationFeeZatoshi: Long,
    val totalInputZatoshi: Long,
    val totalMigratableZatoshi: Long
) {
    companion object {
        /**
         * Parses the packed `long[]` returned by the Rust `planOrchardDenominationSplit` JNI call:
         * `[prepFeeZatoshi, migrationFeeZatoshi, totalInputZatoshi, totalMigratableZatoshi,
         *   orchardChange (-1 if none), outputCount, output_0, output_1, ...]`
         */
        fun parse(encoded: LongArray): DenominationPlan {
            require(encoded.size >= 6) { "Denomination plan array must have at least 6 elements" }

            val outputCount = encoded[5].toInt()
            require(encoded.size == 6 + outputCount) {
                "Denomination plan array length does not match declared output count"
            }

            return DenominationPlan(
                prepFeeZatoshi = encoded[0],
                migrationFeeZatoshi = encoded[1],
                totalInputZatoshi = encoded[2],
                totalMigratableZatoshi = encoded[3],
                orchardChange = encoded[4].takeIf { it >= 0 },
                migrationOutputs = encoded.drop(6)
            )
        }
    }
}
