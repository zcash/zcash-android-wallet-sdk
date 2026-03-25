package cash.z.ecc.android.sdk.model

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.fixture.CurrencyConversionFixture
import cash.z.ecc.android.sdk.fixture.LocaleFixture
import cash.z.ecc.android.sdk.fixture.ZatoshiFixture
import org.junit.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FiatCurrencyConversionRateStateTest {
    @Test
    @SmallTest
    fun future_near() {
        val zatoshi = ZatoshiFixture.new()

        val frozenClock =
            FrozenClock(
                CurrencyConversionFixture.TIMESTAMP - FiatCurrencyConversionRateState.FUTURE_CUTOFF_AGE_INCLUSIVE
            )

        val currencyConversion = CurrencyConversionFixture.new()

        val result =
            zatoshi.toFiatCurrencyState(
                currencyConversion,
                LocaleFixture.new(),
                frozenClock
            )

        assertIs<FiatCurrencyConversionRateState.Current>(result)
    }

    @Test
    @SmallTest
    fun future_far() {
        val zatoshi = ZatoshiFixture.new()

        val frozenClock =
            FrozenClock(
                CurrencyConversionFixture.TIMESTAMP -
                    FiatCurrencyConversionRateState.FUTURE_CUTOFF_AGE_INCLUSIVE -
                    1.seconds
            )

        val currencyConversion = CurrencyConversionFixture.new()

        val result =
            zatoshi.toFiatCurrencyState(
                currencyConversion,
                LocaleFixture.new(),
                frozenClock
            )

        assertIs<FiatCurrencyConversionRateState.Unavailable>(result)
    }

    @Test
    @SmallTest
    fun current() {
        val zatoshi = ZatoshiFixture.new()

        val frozenClock = FrozenClock(CurrencyConversionFixture.TIMESTAMP)

        val currencyConversion =
            CurrencyConversionFixture.new(
                timestamp = CurrencyConversionFixture.TIMESTAMP - 1.seconds
            )

        val result =
            zatoshi.toFiatCurrencyState(
                currencyConversion,
                LocaleFixture.new(),
                frozenClock
            )

        assertIs<FiatCurrencyConversionRateState.Current>(result)
    }

    @Test
    @SmallTest
    fun stale() {
        val zatoshi = ZatoshiFixture.new()

        val frozenClock = FrozenClock(CurrencyConversionFixture.TIMESTAMP)

        val currencyConversion =
            CurrencyConversionFixture.new(
                timestamp =
                    CurrencyConversionFixture.TIMESTAMP -
                        FiatCurrencyConversionRateState.CURRENT_CUTOFF_AGE_INCLUSIVE -
                        1.seconds
            )

        val result =
            zatoshi.toFiatCurrencyState(
                currencyConversion,
                LocaleFixture.new(),
                frozenClock
            )

        assertIs<FiatCurrencyConversionRateState.Stale>(result)
    }

    @Test
    @SmallTest
    fun too_stale() {
        val zatoshi = ZatoshiFixture.new()

        val frozenClock = FrozenClock(CurrencyConversionFixture.TIMESTAMP)

        val currencyConversion =
            CurrencyConversionFixture.new(
                timestamp =
                    CurrencyConversionFixture.TIMESTAMP -
                        FiatCurrencyConversionRateState.STALE_CUTOFF_AGE_INCLUSIVE -
                        1.seconds
            )

        val result =
            zatoshi.toFiatCurrencyState(
                currencyConversion,
                LocaleFixture.new(),
                frozenClock
            )

        assertIs<FiatCurrencyConversionRateState.Unavailable>(result)
    }

    @Test
    @SmallTest
    fun null_conversion_rate() {
        val zatoshi = ZatoshiFixture.new()

        val result =
            zatoshi.toFiatCurrencyState(
                null,
                LocaleFixture.new(),
            )

        assertIs<FiatCurrencyConversionRateState.Unavailable>(result)
    }
}

private class FrozenClock(
    private val timestamp: Instant
) : Clock {
    override fun now() = timestamp
}
