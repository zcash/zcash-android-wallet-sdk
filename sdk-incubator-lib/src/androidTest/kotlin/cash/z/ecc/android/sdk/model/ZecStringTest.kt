package cash.z.ecc.android.sdk.model

import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.util.Locale
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZecStringTest {
    companion object {
        private val EN_US_LOCALE = Locale.US
    }

    @Test
    fun empty_string() {
        val actual = Zatoshi.fromZecString("", EN_US_LOCALE)
        val expected = null

        assertEquals(expected, actual)
    }

    @Test
    fun decimal_monetary_separator() {
        val actual = Zatoshi.fromZecString("1.13", EN_US_LOCALE)
        val expected = Zatoshi(113000000L)

        assertEquals(expected, actual)
    }

    @Test
    fun comma_grouping_separator() {
        val actual = Zatoshi.fromZecString("1,130", EN_US_LOCALE)
        val expected = Zatoshi(113000000000L)

        assertEquals(expected, actual)
    }

    @Test
    fun decimal_monetary_and() {
        val actual = Zatoshi.fromZecString("1,130", EN_US_LOCALE)
        val expected = Zatoshi(113000000000L)

        assertEquals(expected, actual)
    }

    @Test
    @Ignore("https://github.com/zcash/zcash-android-wallet-sdk/issues/412")
    fun toZecString() {
        val expected = "1.13000000"
        val actual = Zatoshi(113000000).toZecString(EN_US_LOCALE)

        assertEquals(expected, actual)
    }

    @Test
    @Ignore("https://github.com/zcash/zcash-android-wallet-sdk/issues/412")
    fun round_trip() {
        val expected = Zatoshi(113000000L)
        val actual = Zatoshi.fromZecString(expected.toZecString(EN_US_LOCALE), EN_US_LOCALE)

        assertEquals(expected, actual)
    }

    @Test
    fun parse_bad_string() {
        assertNull(Zatoshi.fromZecString("", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("+@#$~^&*=", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("asdf", EN_US_LOCALE))
    }

    @Test
    fun parse_invalid_numbers() {
        assertNull(Zatoshi.fromZecString("", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("1,2", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("1,23,", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("1,234,", EN_US_LOCALE))
    }

    @Test
    @SmallTest
    fun overflow_number_test() {
        assertNotNull(Zatoshi.fromZecString("21,000,000", EN_US_LOCALE))
        assertNull(Zatoshi.fromZecString("21,000,001", EN_US_LOCALE))
    }
}
