package cash.z.ecc.android.sdk.fixture

import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import kotlin.time.Instant

object CurrencyConversionFixture {
    val TIMESTAMP = Instant.parse("2022-07-08T11:51:44Z")
    const val PRICE_OF_ZEC = 54.98

    fun new(
        timestamp: Instant = TIMESTAMP,
        priceOfZec: Double = PRICE_OF_ZEC
    ) = FiatCurrencyConversion(timestamp, priceOfZec)
}
