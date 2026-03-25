@file:Suppress("ktlint:standard:filename")

package cash.z.ecc.android.sdk.model

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.Currency
import cash.z.ecc.android.sdk.ext.Conversions
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration

fun Zatoshi.toFiatCurrencyState(
    currencyConversion: FiatCurrencyConversion?,
    locale: Locale,
    clock: Clock = Clock.System
): FiatCurrencyConversionRateState {
    if (currencyConversion == null) {
        return FiatCurrencyConversionRateState.Unavailable
    }

    val fiatCurrencyConversionRate = toFiatString(currencyConversion, locale)

    val currentSystemTime = clock.now()

    val age = currentSystemTime - currencyConversion.timestamp

    return if (age < Duration.ZERO && age.absoluteValue > FiatCurrencyConversionRateState.FUTURE_CUTOFF_AGE_INCLUSIVE) {
        // Special case if the device's clock is set to the future.
        // TODO [#535]: Consider using NTP requests to get the correct time instead of relying on the device's clock.
        FiatCurrencyConversionRateState.Unavailable
    } else if (age <= FiatCurrencyConversionRateState.CURRENT_CUTOFF_AGE_INCLUSIVE) {
        FiatCurrencyConversionRateState.Current(fiatCurrencyConversionRate)
    } else if (age <= FiatCurrencyConversionRateState.STALE_CUTOFF_AGE_INCLUSIVE) {
        FiatCurrencyConversionRateState.Stale(fiatCurrencyConversionRate)
    } else {
        FiatCurrencyConversionRateState.Unavailable
    }
}

fun Zatoshi.toFiatString(
    currencyConversion: FiatCurrencyConversion,
    locale: Locale,
) = convertZatoshiToZecDecimal()
    .convertZecDecimalToFiatDecimal(BigDecimal(currencyConversion.priceOfZec))
    .convertFiatDecimalToFiatString(
        Currency.getInstance(currencyConversion.fiatCurrency.code),
        locale,
    )

private fun Zatoshi.convertZatoshiToZecDecimal(): BigDecimal =
    BigDecimal(value, MathContext.DECIMAL128)
        .divide(
            Conversions.ONE_ZEC_IN_ZATOSHI,
            MathContext.DECIMAL128
        ).setScale(Conversions.ZEC_FORMATTER.maximumFractionDigits, RoundingMode.HALF_EVEN)

private fun BigDecimal.convertZecDecimalToFiatDecimal(zecPrice: BigDecimal): BigDecimal =
    multiply(zecPrice, MathContext.DECIMAL128)

fun BigDecimal.convertFiatDecimalToFiatString(
    fiatCurrency: Currency,
    locale: Locale = Locale.getDefault(),
): String =
    DecimalFormat
        .getInstance(locale.toJavaLocale(), NumberFormat.NUMBERSTYLE)
        .apply {
            this.currency = fiatCurrency
            // TODO [#343]: https://github.com/zcash/secant-android-wallet/issues/343
            roundingMode = android.icu.math.BigDecimal.ROUND_HALF_EVEN // aka Bankers rounding
            this.minimumFractionDigits = FRACTION_DIGITS
            this.maximumFractionDigits = FRACTION_DIGITS
        }.format(this.toDouble())
        .replace(fiatCurrency.symbol, "")
        .trim()
