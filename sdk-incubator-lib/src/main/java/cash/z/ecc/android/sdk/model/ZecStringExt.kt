package cash.z.ecc.android.sdk.model

import android.content.Context
import cash.z.ecc.android.sdk.incubator.R

object ZecStringExt {
    private const val DIGITS_BETWEEN_GROUP_SEPARATORS = 3

    /**
     * Builds filter with current local monetary separators for continuous input checking. The
     * solution is built upon regex validation and other common string validation checks.
     *
     * Regex example: ^([0-9]*([0-9]+([,]$|[,][0-9]+))*([.]$|[.][0-9]+)?)?$
     * Inputs may differ according to user locale.
     *
     * Valid amounts: "" . | .123 | 123, | 123. | 123,456 | 123.456 | 123,456.789 | 123,456,789 | 123,456,789.123 | etc.
     * Invalid amounts: 123,, | 123,. | 123.. | ,123 | 123.456.789 | etc.
     *
     * Note that this validation handles locales with the same grouping and decimal separators, which might be
     * required in some rare cases.
     *
     * @param context used for loading localized pattern from strings.xml
     * @param separators which consist of localized monetary separators
     * @param zecString to be validated
     *
     * @return true in case of validation success, false otherwise
     */
    fun filterContinuous(
        context: Context,
        separators: MonetarySeparators,
        zecString: String
    ): Boolean =
        if (separators.isGroupingValid()) {
            context
                .getString(
                    R.string.co_electriccoin_zcash_zec_amount_regex_continuous_filter,
                    separators.grouping,
                    separators.decimal
                ).toRegex()
                .matches(zecString) &&
                checkFor3Digits(separators, zecString)
        } else {
            context
                .getString(
                    R.string.co_electriccoin_zcash_zec_amount_regex_continuous_no_grouping_filter,
                    separators.decimal
                ).toRegex()
                .matches(zecString)
        }

    /**
     * Checks for at least 3 digits between grouping separators.
     *
     * @param separators which consist of localized monetary separators
     * @param zecString to be validated
     *
     * @return true in case of validation success, false otherwise
     */
    fun checkFor3Digits(
        separators: MonetarySeparators,
        zecString: String
    ): Boolean {
        if (zecString.count { it == separators.grouping } >= 2) {
            val groups = zecString.split(separators.grouping)
            for (i in 1 until (groups.size - 1)) {
                if (groups[i].length != DIGITS_BETWEEN_GROUP_SEPARATORS) {
                    return false
                }
            }
        }
        return true
    }
}
