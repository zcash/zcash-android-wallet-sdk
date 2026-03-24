package cash.z.ecc.android.sdk.internal.exchange

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.model.FetchFiatCurrencyResult
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.lightwallet.client.util.Disposable
import kotlinx.coroutines.delay
import kotlin.time.Clock

internal class UsdExchangeRateFetcher(
    private val isolatedTorClient: TorClient,
) : Disposable {
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend operator fun invoke(): FetchFiatCurrencyResult {
        return retry {
            val rate =
                try {
                    Twig.info { "[USD] Fetch start" }
                    isolatedTorClient.getExchangeRateUsd()
                } catch (e: Exception) {
                    Twig.error(e) { "[USD] Fetch failed" }
                    return FetchFiatCurrencyResult.Error(e)
                }

            Twig.debug { "[USD] Fetch success: $rate" }

            FetchFiatCurrencyResult.Success(
                currencyConversion =
                    FiatCurrencyConversion(
                        priceOfZec = rate.toDouble(),
                        timestamp = Clock.System.now()
                    )
            )
        }
    }

    /**
     * Retry with geometric order.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")
    private suspend inline fun <T> retry(
        times: Int = 3,
        initialDelay: Long = 1000,
        multiplier: Double = 2.0,
        block: () -> T,
    ): T {
        var currentDelay = initialDelay
        repeat(times - 1) {
            try {
                return block()
            } catch (e: Exception) {
                Twig.debug(e) { "[USD] Fetching attempt failed" }
            }
            delay(currentDelay)
            currentDelay = (currentDelay * multiplier).toLong()
        }
        return block() // last attempt
    }

    override suspend fun dispose() {
        isolatedTorClient.dispose()
    }
}
