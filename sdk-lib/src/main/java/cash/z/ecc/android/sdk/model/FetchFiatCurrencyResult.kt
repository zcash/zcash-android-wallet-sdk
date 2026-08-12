package cash.z.ecc.android.sdk.model

internal sealed interface FetchFiatCurrencyResult {
    val fiatCurrency: FiatCurrency
        get() = FiatCurrency.USD

    data class Success(
        val currencyConversion: FiatCurrencyConversion
    ) : FetchFiatCurrencyResult

    data class Error(
        val exception: Exception
    ) : FetchFiatCurrencyResult
}
