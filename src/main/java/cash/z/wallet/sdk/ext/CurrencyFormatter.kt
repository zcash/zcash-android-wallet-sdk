package cash.z.wallet.sdk.ext

import cash.z.wallet.sdk.ext.Conversions.USD_FORMATTER
import cash.z.wallet.sdk.ext.Conversions.ZEC_FORMATTER
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.*

//TODO: provide a dynamic way to configure this globally for the SDK
// For now, just make these vars so at least they could be modified in one place
object Conversions {
    var ONE_ZEC_IN_ZATOSHI = BigDecimal(ZATOSHI, MathContext.DECIMAL128)
    var ZEC_FORMATTER = NumberFormat.getInstance(Locale.getDefault()).apply {
        roundingMode = RoundingMode.HALF_EVEN
        maximumFractionDigits = 6
        minimumFractionDigits = 0
        minimumIntegerDigits = 1
    }
    var USD_FORMATTER = NumberFormat.getInstance(Locale.getDefault()).apply {
        roundingMode = RoundingMode.HALF_EVEN
        maximumFractionDigits = 2
        minimumFractionDigits = 2
        minimumIntegerDigits = 1
    }
}


/**
 * Format a Zatoshi value into Zec with the given number of digits, represented as a string.
 * Start with Zatoshi -> End with Zec.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because Zec is better than Usd.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 */
inline fun Long?.convertZatoshiToZecString(
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String {
    return currencyFormatter(maxDecimals, minDecimals).format(this.convertZatoshiToZec(maxDecimals))
}

/**
 * Format a Zec value into Zec with the given number of digits, represented as a string.
 * Start with ZeC -> End with Zec.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because Zec is better when right.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 */
inline fun Double?.toZecString(
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String {
    return currencyFormatter(maxDecimals, minDecimals).format(this.toZec(maxDecimals))
}

/**
 * Format a Zatoshi value into Zec with the given number of decimal places, represented as a string.
 * Start with ZeC -> End with Zec.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because Zec is better than bread.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 */
inline fun BigDecimal?.toZecString(
    maxDecimals: Int = ZEC_FORMATTER.maximumFractionDigits,
    minDecimals: Int = ZEC_FORMATTER.minimumFractionDigits
): String {
    return currencyFormatter(maxDecimals, minDecimals).format(this.toZec(maxDecimals))
}

/**
 * Format a Usd value into Usd with the given number of digits, represented as a string.
 *
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because Zec is better than pennies
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 */
inline fun Double?.toUsdString(
    maxDecimals: Int = USD_FORMATTER.maximumFractionDigits,
    minDecimals: Int = USD_FORMATTER.minimumFractionDigits
): String {
    return if(this == 0.0) {
        "0"
    } else {
        currencyFormatter(maxDecimals, minDecimals).format(this.toUsd(maxDecimals))
    }
}

/**
 * Format a Zatoshi value into Usd with the given number of decimal places, represented as a string.
 * @param maxDecimals the number of decimal places to use in the format. Default is 6 because Zec is glorious.
 * @param minDecimals the minimum number of digits to allow to the right of the decimal.
 */
inline fun BigDecimal?.toUsdString(
    maxDecimals: Int = USD_FORMATTER.maximumFractionDigits,
    minDecimals: Int = USD_FORMATTER.minimumFractionDigits
): String {
    return currencyFormatter(maxDecimals, minDecimals).format(this.toUsd(maxDecimals))
}

/**
 * Create a number formatter for use with converting currency to strings. This probably isn't needed externally since
 * the other formatting functions leverage this, instead. Leverages the default rounding mode for zec found in
 * ZEC_FORMATTER.
 */
inline fun currencyFormatter(maxDecimals: Int, minDecimals: Int): NumberFormat {
    return NumberFormat.getInstance(Locale.getDefault()).apply {
        roundingMode = ZEC_FORMATTER.roundingMode
        maximumFractionDigits = maxDecimals
        minimumFractionDigits = minDecimals
        minimumIntegerDigits = 1
    }
}

/**
 * Convert a Zatoshi value into Zec, right-padded to the given number of fraction digits, represented as a BigDecimal in
 * order to preserve rounding that minimizes cumulative error when applied repeatedly over a sequence of calculations.
 * Start with Zatoshi -> End with Zec.
 *
 * @param scale the number of digits to the right of the decimal place. Right-padding will be added, if necessary.
 */
inline fun Long?.convertZatoshiToZec(scale: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal {
    return BigDecimal(this ?: 0L, MathContext.DECIMAL128).divide(Conversions.ONE_ZEC_IN_ZATOSHI, MathContext.DECIMAL128).setScale(scale, ZEC_FORMATTER.roundingMode)
}

/**
 * Convert a Zec value into Zatoshi.
 */
inline fun BigDecimal?.convertZecToZatoshi(): Long {
    if (this == null) return 0L
    if (this < BigDecimal.ZERO) throw IllegalArgumentException("Invalid ZEC value: $this. ZEC is represented by notes and cannot be negative")
    return this.multiply(Conversions.ONE_ZEC_IN_ZATOSHI, MathContext.DECIMAL128).toLong()
}

/**
 * Format a Double Zec value as a BigDecimal Zec value, right-padded to the given number of fraction digits.
 * Start with Zec -> End with Zec.
 */
inline fun Double?.toZec(decimals: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal {
    return BigDecimal(this?.toString() ?: "0.0", MathContext.DECIMAL128).setScale(decimals, ZEC_FORMATTER.roundingMode)
}

/**
 * Format a BigDecimal Zec value as a BigDecimal Zec value, right-padded to the given number of fraction digits.
 * Start with Zec -> End with Zec.
 */
inline fun BigDecimal?.toZec(decimals: Int = ZEC_FORMATTER.maximumFractionDigits): BigDecimal {
    return (this ?: BigDecimal.ZERO).setScale(decimals, ZEC_FORMATTER.roundingMode)
}

/**
 * Format a Double Usd value as a BigDecimal Usd value, right-padded to the given number of fraction digits.
 */
inline fun Double?.toUsd(decimals: Int = USD_FORMATTER.maximumFractionDigits): BigDecimal {
    return BigDecimal(this?.toString() ?: "0.0", MathContext.DECIMAL128).setScale(decimals, USD_FORMATTER.roundingMode)
}

/**
 * Format a BigDecimal Usd value as a BigDecimal Usd value, right-padded to the given number of fraction digits.
 */
inline fun BigDecimal?.toUsd(decimals: Int = USD_FORMATTER.maximumFractionDigits): BigDecimal {
    return (this ?: BigDecimal.ZERO).setScale(decimals, USD_FORMATTER.roundingMode)
}

/**
 * Convert this ZEC value to USD, using the given price per ZEC.
 *
 * @param zecPrice the current price of ZEC represented as USD per ZEC
 */
inline fun BigDecimal?.convertZecToUsd(zecPrice: BigDecimal): BigDecimal {
    if(this == null) return BigDecimal.ZERO
    if(this < BigDecimal.ZERO) throw IllegalArgumentException("Invalid ZEC value: ${zecPrice.toDouble()}. ZEC is represented by notes and cannot be negative")
    return this.multiply(zecPrice, MathContext.DECIMAL128)
}

/**
 * Convert this USD value to ZEC, using the given price per ZEC.
 *
 * @param zecPrice the current price of ZEC represented as USD per ZEC
 */
inline fun BigDecimal?.convertUsdToZec(zecPrice: BigDecimal): BigDecimal {
    if(this == null) return BigDecimal.ZERO
    if(this < BigDecimal.ZERO) throw IllegalArgumentException("Invalid USD value: ${zecPrice.toDouble()}. Converting this would result in negative ZEC and ZEC is represented by notes and cannot be negative")
    return this.divide(zecPrice, MathContext.DECIMAL128)
}

/**
 * Convert this value from one currency to the other, based on given price and whether this value is USD.
 *
 * @param isUsd whether this value represents USD or not (ZEC)
 */
inline fun BigDecimal.convertCurrency(zecPrice: BigDecimal, isUsd: Boolean): BigDecimal {
    return if (isUsd) {
        this.convertUsdToZec(zecPrice)
    } else {
        this.convertZecToUsd(zecPrice)
    }
}

/**
 * Parse this string into a BigDecimal, ignoring all non numeric characters.
 *
 * @return null when parsing fails
 */
inline fun String?.safelyConvertToBigDecimal(): BigDecimal? {
    if (this.isNullOrEmpty()) return BigDecimal.ZERO
    return try {
        // ignore commas and whitespace
        var sanitizedInput = this.filter { it.isDigit() or (it == '.') }
        BigDecimal.ZERO.max(BigDecimal(sanitizedInput, MathContext.DECIMAL128))
    } catch (t: Throwable) {
        return null
    }
}

internal inline fun String.masked(): String = if (startsWith("ztest")) "****${takeLast(4)}" else "***masked***"