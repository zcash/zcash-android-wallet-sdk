package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.ext.ZcashDecimalFormatSymbols
import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.ext.currencyFormatter
import java.math.BigDecimal
import java.text.ParsePosition
import java.util.Locale

object ZecString {
    fun allowedCharacters(monetarySeparators: MonetarySeparators) =
        buildSet<Char> {
            add('0')
            add('1')
            add('2')
            add('3')
            add('4')
            add('5')
            add('6')
            add('7')
            add('8')
            add('9')
            add(monetarySeparators.decimal)
            if (monetarySeparators.isGroupingValid()) {
                add(monetarySeparators.grouping)
            }
        }
}

data class MonetarySeparators(
    val grouping: Char,
    val decimal: Char
) {
    companion object {
        /**
         * @param locale Preferred Locale for the returned monetary separators. If Locale is not provided, the
         * default one will be used.
         *
         * @return The current localized monetary separators.  Do not cache this value, as it
         * can change if the system Locale changes.
         */
        fun current(locale: Locale = Locale.getDefault()): MonetarySeparators {
            val decimalFormatSymbols = ZcashDecimalFormatSymbols(locale)

            return MonetarySeparators(
                grouping = decimalFormatSymbols.groupingSeparator,
                decimal = decimalFormatSymbols.decimalSeparator
            )
        }
    }

    fun isGroupingValid() = this.grouping.isDefined() && this.grouping != this.decimal
}

private const val DECIMALS = 8

// TODO [#412]: https://github.com/zcash/zcash-android-wallet-sdk/issues/412
// The SDK needs to fix the API for currency conversion
fun Zatoshi.toZecString(locale: Locale) = convertZatoshiToZecString(locale, DECIMALS, DECIMALS)

const val FRACTION_DIGITS = 2

/*
 * ZEC is our own currency, so there's not going to be an existing localization that matches it perfectly.
 *
 * To ensure consistent behavior regardless of user Locale, use US localization except that we swap out the
 * separator characters based on the user's current Locale.  This should avoid unexpected surprises
 * while also localizing the separator format.
 */

/**
 * @return [zecString] parsed into Zatoshi or null if parsing failed.
 */
fun Zatoshi.Companion.fromZecString(zecString: String, locale: Locale): Zatoshi? {
    if (zecString.isEmpty()) return null

    val decimalFormat =
        currencyFormatter(
            locale = locale,
            maximumFractionDigits = FRACTION_DIGITS,
            minimumFractionDigits = FRACTION_DIGITS
        ).apply {
            this.isParseBigDecimal = true
        }

    // Use ParsePosition so we can verify the entire string was consumed. DecimalFormat.parse will
    // otherwise accept partial input (e.g. "1,2", "1,23,", or "asdf" -> 0), which we need to reject.
    val parsePosition = ParsePosition(0)
    val parsed = decimalFormat.parse(zecString, parsePosition) as? BigDecimal
    if (parsed == null || parsePosition.index != zecString.length) {
        return null
    }

    @Suppress("SwallowedException")
    return try {
        parsed.convertZecToZatoshi()
    } catch (_: IllegalArgumentException) {
        null
    }
}
