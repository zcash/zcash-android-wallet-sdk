package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.test.assertEquals

class ConversionsTest {
    private val locale = Locale.US

    @Test
    fun `default right padding is 6`() {
        assertEquals(1.13.toZec(6), Zatoshi(113000000L).convertZatoshiToZec())
        assertEquals(1.13.toZec(6), 1.13.toZec())
    }

    @Test
    fun `toZec uses banker's rounding`() {
        assertEquals("1.004", 1.0035.toZecString(locale, 3))
        assertEquals("1.004", 1.0045.toZecString(locale, 3))
    }

    @Test
    fun `toZecString defaults to 6 digits`() {
        assertEquals("1.123457", Zatoshi(112345678L).convertZatoshiToZecString(locale))
    }

    @Test
    fun `toZecString uses banker's rounding`() {
        assertEquals("1.123456", Zatoshi(112345650L).convertZatoshiToZecString(locale))
    }

    @Test
    fun `toZecString honors minimum digits`() {
        assertEquals("1.1000", 1.1.toZecString(locale, 6, 4))
    }

    @Test
    fun `toZecString drops trailing zeros`() {
        assertEquals("1.1", 1.10000000.toZecString(locale, 6, 0))
    }

    @Test
    fun `toZecString limits trailing zeros`() {
        assertEquals("1.10", 1.10000000.toZecString(locale, 6, 2))
    }

    @Test
    fun `toZecString hides decimal when min is zero`() {
        assertEquals("1", 1.0.toZecString(locale, 6, 0))
    }

    @Test
    fun `toZecString defaults are reasonable`() {
        // basically check for no extra zeros and banker's rounding
        assertEquals("1", 1.0000000.toZecString(locale))
        assertEquals("0", 0.0000000.toZecString(locale))
        assertEquals("1.01", 1.0100000.toZecString(locale))
        assertEquals("1.000004", 1.0000035.toZecString(locale))
        assertEquals("1.000004", 1.0000045.toZecString(locale))
        assertEquals("1.000006", 1.0000055.toZecString(locale))
    }

    @Test
    fun `toUsdString defaults are reasonable`() {
        // basically check for no extra zeros and banker's rounding
        assertEquals("1.00", 1.0000000.toUsdString(locale))
        assertEquals("0", 0.0000000.toUsdString(locale))
        assertEquals("1.01", 1.0100000.toUsdString(locale))
        assertEquals("0.01", .0100000.toUsdString(locale))
        assertEquals("1.02", 1.025.toUsdString(locale))
    }

    @Test
    fun `toZecString zatoshi converts`() {
        assertEquals("1.123456", Zatoshi(112345650L).convertZatoshiToZecString(locale, 6, 0))
    }

    @Test
    fun `toZecString big decimal formats`() {
        assertEquals("1.123", BigDecimal(1.123456789).toZecString(locale, 3, 0))
    }

    @Test
    fun `toZec reduces precision`() {
        val amount = "20.37905033625433054819645404524149".safelyConvertToBigDecimal('.')
        val expected = "20.379050".safelyConvertToBigDecimal('.')
        assertEquals(expected, amount.toZec(6))
        assertEquals("20.37905", amount.toZecString(locale, 6))
    }

    @Test
    fun `convert usd to zec`() {
        val price = BigDecimal("49.07", MathContext.DECIMAL128)
        val usdValue = "1000".safelyConvertToBigDecimal('.')
        val zecValue = usdValue.convertUsdToZec(price)
        assertEquals("20.379050".safelyConvertToBigDecimal('.'), zecValue.toZec(6))
    }
}
