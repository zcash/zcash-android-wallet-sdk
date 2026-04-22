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

private const val GROUP_SIZE = 3

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
    val decimalFormat =
        currencyFormatter(
            locale = locale,
            maximumFractionDigits = FRACTION_DIGITS,
            minimumFractionDigits = FRACTION_DIGITS
        ).apply {
            this.isParseBigDecimal = true
        }

    // Use ParsePosition so we can verify the entire string was consumed. DecimalFormat.parse may
    // otherwise accept only a leading numeric portion (e.g. "1.13 trailing" -> 1.13), which we need
    // to reject. Completely invalid input such as "asdf" fails parsing at index 0 and results in
    // null.
    val parsePosition = ParsePosition(0)
    val parsed =
        if (zecString.isEmpty()) {
            null
        } else {
            decimalFormat.parse(zecString, parsePosition) as? BigDecimal
        }

    val separators = MonetarySeparators.current(locale)
    if (parsed == null ||
        parsePosition.index != zecString.length ||
        !hasValidGrouping(zecString, separators)
    ) {
        return null
    }

    @Suppress("SwallowedException")
    return try {
        parsed.convertZecToZatoshi()
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Validates the grouping structure of [input] using [separators].
 *
 * Android's [java.text.DecimalFormat] is lenient with grouping: it happily parses "1,2" as 12,
 * "1,23," as 123, and "1,234," as 1234 in en-US. Strict ParsePosition checking alone doesn't help
 * because the parser consumes the whole input. This helper enforces the locale's grouping contract
 * on the integer part of [input]:
 *
 * - If the locale has no distinct grouping separator, any input is accepted here.
 * - If [input] contains no grouping separator in its integer part, it is accepted here.
 * - Otherwise, the integer part must consist of a leading group of 1-[GROUP_SIZE] digits followed
 *   by one or more groups of exactly [GROUP_SIZE] digits. Empty groups (leading or trailing
 *   grouping separator, or consecutive grouping separators) are rejected.
 */
private fun hasValidGrouping(
    input: String,
    separators: MonetarySeparators
): Boolean {
    // When the locale has no distinct grouping separator, or the input's integer part contains no
    // grouping separator, there is nothing to validate -- treat as valid.
    val integerPart = input.substringBefore(separators.decimal)
    val groups = integerPart.split(separators.grouping)
    val hasNoGrouping = !separators.isGroupingValid() || groups.size == 1
    val first = groups.first()
    val firstIsValid = first.isNotEmpty() && first.length <= GROUP_SIZE && first.all { it.isDigit() }
    val restAreValid =
        groups.drop(1).all { group ->
            group.length == GROUP_SIZE && group.all { it.isDigit() }
        }
    return hasNoGrouping || (firstIsValid && restAreValid)
}
