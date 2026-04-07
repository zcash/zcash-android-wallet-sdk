@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package cash.z.ecc.android.sdk.ext

import android.os.Build
import cash.z.ecc.android.sdk.ext.Conversions.USD_FORMATTER
import cash.z.ecc.android.sdk.ext.Conversions.ZEC_FORMATTER
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.Zatoshi
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/*
 * Convenience functions for converting currency values for display in user interfaces. The
 * calculations done here are not intended for financial purposes, because all such transactions
 * are done using Zatoshis in the Rust layer. Instead, these functions are focused on displaying
 * accurately rounded values to the user.
 */

// TODO [#678]: provide a dynamic way to configure this globally for the SDK
//  For now, just make these vars so at least they could be modified in one place
// TODO [#678]: https://github.com/zcash/zcash-android-wallet-sdk/issues/678
@Suppress("MagicNumber", "ktlint:standard:property-naming")
object Conversions {
    var ONE_ZEC_IN_ZATOSHI = BigDecimal(Zatoshi.ZATOSHI_PER_ZEC, MathContext.DECIMAL128)
    var ZEC_FORMATTER =
        NumberFormat.getInstance(Locale.getDefault()).apply {
            roundingMode = RoundingMode.HALF_EVEN
            maximumFractionDigits = 6
            minimumFractionDigits = 0
            minimumIntegerDigits = 1
        }
    var USD_FORMATTER =
        NumberFormat.getInstance(Locale.getDefault()).apply {
            roundingMode = RoundingMode.HALF_EVEN
            maximumFractionDigits = 2
            minimumFractionDigits = 2
            minimumIntegerDigits = 1
        }
}

/**
 * Format a Zatoshi value into ZEC with the given number of digits, represented as a string.
 * Start with Zatoshi -> End with ZEC.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because ZEC is
 * better than USD.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 *
 * @return this Zatoshi value represented as ZEC, in a string with at least [minDecimals] and at
 * most [maxDecimals]
 */
fun Zatoshi?.convertZatoshiToZecString(
    locale: Locale,
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String = currencyFormatter(locale, maxDecimals, minDecimals).format(convertZatoshiToZec(maxDecimals))

/**
 * Format a ZEC value into ZEC with the given number of digits, represented as a string.
 * Start with ZEC -> End with ZEC.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because ZEC is
 * better when right.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 *
 * @return this Double ZEC value represented as a string with at least [minDecimals] and at most
 * [maxDecimals].
 */
fun Double?.toZecString(
    locale: Locale,
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String = currencyFormatter(locale, maxDecimals, minDecimals).format(this.toZec(maxDecimals))

/**
 * Format a Zatoshi value into ZEC with the given number of decimal places, represented as a string.
 * Start with ZeC -> End with ZEC.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because ZEC is
 * better than bread.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 *
 * @return this BigDecimal ZEC value represented as a string with at least [minDecimals] and at most
 * [maxDecimals].
 */
fun BigDecimal?.toZecString(
    locale: Locale,
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String = currencyFormatter(locale, maxDecimals, minDecimals).format(this.toZec(maxDecimals))

/**
 * Format a USD value into USD with the given number of digits, represented as a string.
 * Start with USD -> end with USD.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because
 * ZEC is glorious.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 *
 * @return this Double ZEC value represented as a string with at least [minDecimals] and at most
 * [maxDecimals], which is 2 by default. Zero is always represented without any decimals.
 */
fun Double?.toUsdString(
    locale: Locale,
    maxDecimals: Int = USD_FORMATTER.maximumFractionDigits,
    minDecimals: Int = USD_FORMATTER.minimumFractionDigits
): String =
    if (this == 0.0) {
        "0"
    } else {
        currencyFormatter(locale, maxDecimals, minDecimals).format(this.toUsd(maxDecimals))
    }

/**
 * Format a USD value into USD with the given number of decimal places, represented as a string.
 * Start with USD -> end with USD.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because ZEC is
 * glorious.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 *
 * @return this BigDecimal USD value represented as a string with at least [minDecimals] and at most
 * [maxDecimals], which is 2 by default.
 */
fun BigDecimal?.toUsdString(
    locale: Locale,
    maxDecimals: Int = USD_FORMATTER.maximumFractionDigits,
    minDecimals: Int = USD_FORMATTER.minimumFractionDigits
): String = currencyFormatter(locale, maxDecimals, minDecimals).format(this.toUsd(maxDecimals))

/**
 * Create a zaotshi formatter for use with converting currency to strings. This probably isn't needed
 * externally since the other formatting functions leverage this, instead. Leverages the default
 * rounding mode for ZEC found in ZEC_FORMATTER.
 *
 * @return a currency formatter, appropriate for the [locale].
 */
fun zatoshiFormatter(locale: Locale) =
    currencyFormatter(
        locale = locale,
        maximumFractionDigits = 8,
        minimumFractionDigits = 3
    )

/**
 * Create a number formatter for use with converting currency to strings. This probably isn't needed
 * externally since the other formatting functions leverage this, instead. Leverages the default
 * rounding mode for ZEC found in ZEC_FORMATTER.
 *
 * @param maximumFractionDigits the number of decimal places to use in the format. Default is 6 because ZEC is
 * glorious.
 * @param minimumFractionDigits the minimum number of digits to allow to the right of the decimal.
 *
 * @return a currency formatter, appropriate for the [locale].
 */
fun currencyFormatter(
    locale: Locale,
    maximumFractionDigits: Int? = ZEC_FORMATTER.maximumFractionDigits,
    minimumFractionDigits: Int? = ZEC_FORMATTER.minimumFractionDigits
): DecimalFormat {
    val symbols = ZcashDecimalFormatSymbols(locale)
    val pattern = (DecimalFormat.getInstance(locale) as? DecimalFormat)?.toPattern()

    return if (pattern != null) {
        DecimalFormat(pattern, symbols)
    } else {
        DecimalFormat().apply {
            this.decimalFormatSymbols = symbols
        }
    }.apply {
        roundingMode = ZEC_FORMATTER.roundingMode
        maximumFractionDigits?.let { this.maximumFractionDigits = it }
        minimumFractionDigits?.let { this.minimumFractionDigits = it }
    }
}

class ZcashDecimalFormatSymbols(
    locale: Locale
) : DecimalFormatSymbols(locale) {
    override fun getGroupingSeparator(): Char {
        val originalGroupingSeparator = super.getGroupingSeparator()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            monetaryGroupingSeparator
        } else {
            originalGroupingSeparator
        }
    }

    override fun getDecimalSeparator(): Char {
        val originalDecimalSeparator = super.getDecimalSeparator()
        val originalGroupingSeparator = super.getGroupingSeparator()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> monetaryDecimalSeparator
            originalGroupingSeparator == monetaryDecimalSeparator -> originalDecimalSeparator
            else -> monetaryDecimalSeparator
        }
    }
}

/**
 * Convert a Zatoshi value into ZEC, right-padded to the given number of fraction digits,
 * represented as a BigDecimal in order to preserve rounding that minimizes cumulative error when
 * applied repeatedly over a sequence of calculations.
 * Start with Zatoshi -> End with ZEC.
 *
 * @param scale the number of digits to the right of the decimal place. Right-padding will be added,
 * if necessary.
 *
 * @return this Long Zatoshi value represented as ZEC using a BigDecimal with the given scale,
 * rounded accurately out to 128 digits.
 */
fun Zatoshi?.convertZatoshiToZec(scale: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal =
    BigDecimal(this?.value ?: 0L, MathContext.DECIMAL128)
        .divide(
            Conversions.ONE_ZEC_IN_ZATOSHI,
            MathContext.DECIMAL128
        ).setScale(scale, ZEC_FORMATTER.roundingMode)

/**
 * Convert a ZEC value into Zatoshi.
 * Start with ZEC -> End with Zatoshi.
 *
 * @return this ZEC value represented as Zatoshi, rounded accurately out to 128 digits, in order to
 * minimize cumulative errors when applied repeatedly over a sequence of calculations.
 */
fun BigDecimal?.convertZecToZatoshi(): Zatoshi {
    if (this == null) return Zatoshi(0L)
    if (this < BigDecimal.ZERO) {
        throw IllegalArgumentException(
            "Invalid ZEC value: $this. ZEC is represented by notes and" +
                " cannot be negative"
        )
    }
    return Zatoshi(this.multiply(Conversions.ONE_ZEC_IN_ZATOSHI, MathContext.DECIMAL128).toLong())
}

/**
 * Format a Double ZEC value as a BigDecimal ZEC value, right-padded to the given number of fraction
 * digits.
 * Start with ZEC -> End with ZEC.
 *
 * @param decimals the scale to use for the resulting BigDecimal.
 *
 * @return this Double ZEC value converted into a BigDecimal, with the proper rounding mode for use
 * with other formatting functions.
 */
fun Double?.toZec(decimals: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal =
    BigDecimal(this?.toString() ?: "0.0", MathContext.DECIMAL128).setScale(
        decimals,
        ZEC_FORMATTER.roundingMode
    )

/**
 * Format a Double ZEC value as a Long Zatoshi value, by first converting to ZEC with the given
 * precision.
 * Start with ZEC -> End with Zatoshi.
 *
 * @param decimals the scale to use for the intermediate BigDecimal.
 *
 * @return this Double ZEC value converted into Zatoshi, with proper rounding and precision by
 * leveraging an intermediate BigDecimal object.
 */
fun Double?.convertZecToZatoshi(decimals: Int = ZEC_FORMATTER.maximumFractionDigits): Zatoshi =
    this
        .toZec(decimals)
        .convertZecToZatoshi()

/**
 * Format a BigDecimal ZEC value as a BigDecimal ZEC value, right-padded to the given number of
 * fraction digits.
 * Start with ZEC -> End with ZEC.
 *
 * @param decimals the scale to use for the resulting BigDecimal.
 *
 * @return this BigDecimal ZEC adjusted to the default scale and rounding mode.
 */
fun BigDecimal?.toZec(decimals: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal =
    (this ?: BigDecimal.ZERO).setScale(decimals, ZEC_FORMATTER.roundingMode)

/**
 * Format a Double USD value as a BigDecimal USD value, right-padded to the given number of fraction
 * digits.
 * Start with USD -> End with USD.
 *
 * @param decimals the scale to use for the resulting BigDecimal.
 *
 * @return this Double USD value converted into a BigDecimal, with proper rounding and precision.
 */
fun Double?.toUsd(decimals: Int = USD_FORMATTER.maximumFractionDigits): BigDecimal =
    BigDecimal(this?.toString() ?: "0.0", MathContext.DECIMAL128).setScale(
        decimals,
        USD_FORMATTER.roundingMode
    )

/**
 * Format a BigDecimal USD value as a BigDecimal USD value, right-padded to the given number of
 * fraction digits.
 * Start with USD -> End with USD.
 *
 * @param decimals the scale to use for the resulting BigDecimal.
 *
 * @return this BigDecimal USD value converted into USD, with proper rounding and precision.
 */
fun BigDecimal?.toUsd(decimals: Int = USD_FORMATTER.maximumFractionDigits): BigDecimal =
    (this ?: BigDecimal.ZERO).setScale(decimals, USD_FORMATTER.roundingMode)

/**
 * Convert this ZEC value to USD, using the given price per ZEC.
 * Start with ZEC -> End with USD.
 *
 * @param zecPrice the current price of ZEC represented as USD per ZEC
 *
 * @return this BigDecimal USD value converted into USD, with proper rounding and precision.
 */
fun BigDecimal?.convertZecToUsd(zecPrice: BigDecimal): BigDecimal {
    if (this == null) return BigDecimal.ZERO
    if (this < BigDecimal.ZERO) {
        throw IllegalArgumentException(
            "Invalid ZEC value: ${zecPrice.toDouble()}. ZEC is" +
                " represented by notes and cannot be negative"
        )
    }
    return this.multiply(zecPrice, MathContext.DECIMAL128)
}

/**
 * Convert this USD value to ZEC, using the given price per ZEC.
 * Start with USD -> End with ZEC.
 *
 * @param zecPrice the current price of ZEC represented as USD per ZEC.
 *
 * @return this BigDecimal USD value converted into ZEC, with proper rounding and precision.
 */
fun BigDecimal?.convertUsdToZec(zecPrice: BigDecimal): BigDecimal {
    if (this == null) return BigDecimal.ZERO
    if (this < BigDecimal.ZERO) {
        throw IllegalArgumentException(
            "Invalid USD value: ${zecPrice.toDouble()}. Converting" +
                " this would result in negative ZEC and ZEC is represented by notes and cannot be" +
                " negative"
        )
    }
    return this.divide(zecPrice, MathContext.DECIMAL128)
}

/**
 * Convert this USD value to ZEC, using the given price per ZEC.
 * Start with USD -> End with ZEC.
 *
 * @param zecPrice the current price of ZEC represented as USD per ZEC.
 *
 * @return this Double USD value converted into ZEC, with proper rounding and precision.
 */
fun Double?.convertUsdToZec(zecPrice: Double): Double {
    if (this == null) return .0
    if (this < .0) {
        throw IllegalArgumentException(
            "Invalid USD value: $zecPrice. Converting" +
                " this would result in negative ZEC and ZEC is represented by notes and cannot be" +
                " negative"
        )
    }
    return this.div(zecPrice)
}

/**
 * Parse this string into a BigDecimal, ignoring all non numeric characters.
 *
 * @return this string as a BigDecimal or null when parsing fails.
 */
fun String?.safelyConvertToBigDecimal(decimalSeparator: Char): BigDecimal? {
    if (this.isNullOrEmpty()) {
        return BigDecimal.ZERO
    }
    val result =
        try {
            // ignore commas and whitespace
            val sanitizedInput = this.filter { it.isDigit() or (it == decimalSeparator) }
            BigDecimal.ZERO.max(BigDecimal(sanitizedInput, MathContext.DECIMAL128))
        } catch (nfe: NumberFormatException) {
            Twig.debug(nfe) { "Exception while converting String to BigDecimal" }
            null
        }
    return result
}

/**
 * Masks the current string for use in logs. If this string appears to be an address, the last
 * [addressCharsToShow] characters will be visible.
 *
 * @param addressCharsToShow the number of chars to show at the end, if this value appears to be an
 * address.
 *
 * @return the masked version of this string, typically for use in logs.
 */
internal fun String.masked(addressCharsToShow: Int = 4): String =
    if (startsWith("ztest") || startsWith("zs")) {
        "****${takeLast(addressCharsToShow)}"
    } else {
        "***masked***"
    }

/**
 * Convenience function that returns true when this string starts with 'z'.
 *
 * @return true when this function starts with 'z' rather than 't'.
 */
fun String?.isShielded() = this != null && startsWith('z')
