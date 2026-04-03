package cash.z.ecc.android.sdk.demoapp.util

import android.content.Context
import androidx.compose.ui.text.intl.Locale
import cash.z.ecc.android.sdk.demoapp.R
import cash.z.ecc.android.sdk.demoapp.ui.screen.transactions.view.TransactionState
import cash.z.ecc.android.sdk.model.FiatCurrencyConversionRateState
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.toFiatCurrencyState
import kotlin.time.Instant

internal fun TransactionOverview.toTransactionState(
    context: Context,
    onClick: () -> Unit
) = TransactionState(
    time =
        (
            minedHeight
                ?.let {
                    blockTimeEpochSeconds?.let { Instant.fromEpochSeconds(it) }
                        ?: context.getString(R.string.unknown)
                } ?: context.getString(R.string.pending)
        ).toString(),
    value =
        if (isSentTransaction) {
            -netValue.value
        } else {
            netValue.value
        }.toString(),
    fee =
        feePaid
            ?.toFiatCurrencyState(
                currencyConversion = null,
                locale = Locale.current.toKotlinLocale(),
            )?.toFiatCurrencyRateValue(context)
            .orEmpty(),
    status = transactionState.name,
    onClick = onClick
)

private fun Locale.toKotlinLocale() =
    cash.z.ecc.android.sdk.model
        .Locale(language, region, script)

private fun FiatCurrencyConversionRateState.toFiatCurrencyRateValue(context: Context): String =
    when (this) {
        is FiatCurrencyConversionRateState.Current -> formattedFiatValue
        is FiatCurrencyConversionRateState.Stale -> formattedFiatValue
        is FiatCurrencyConversionRateState.Unavailable -> context.getString(R.string.transaction_fee_unavailable)
    }
