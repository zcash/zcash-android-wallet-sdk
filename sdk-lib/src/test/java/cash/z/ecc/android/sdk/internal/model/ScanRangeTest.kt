package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.fixture.ScanRangeFixture
import cash.z.ecc.android.sdk.internal.ext.isNotEmpty
import cash.z.ecc.android.sdk.internal.ext.length
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanRangeTest {
    @Test
    fun get_suggest_scan_range_priority_test() {
        val scanRange =
            ScanRangeFixture.new(
                priority = SuggestScanRangePriority.Verify.priority
            )
        assertTrue {
            scanRange.getSuggestScanRangePriority() == SuggestScanRangePriority.Verify
        }
    }

    @Test
    fun priority_attribute_within_constraints() {
        val instance =
            ScanRangeFixture.new(
                priority = SuggestScanRangePriority.Verify.priority
            )
        assertIs<ScanRange>(instance)
    }

    @Test
    fun priority_attribute_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            ScanRangeFixture.new(
                priority = Long.MIN_VALUE
            )
        }
    }

    @Test
    fun empty_scan_range_returns_null() {
        val jni = JniScanRange(startHeight = 0L, endHeight = 0L, priority = SuggestScanRangePriority.Verify.priority)
        assertNull(ScanRange.new(jni))
    }

    @Test
    fun non_zero_empty_scan_range_returns_null() {
        val jni = JniScanRange(startHeight = 100L, endHeight = 100L, priority = SuggestScanRangePriority.Verify.priority)
        assertNull(ScanRange.new(jni))
    }

    @Test
    fun inverted_scan_range_returns_null() {
        val jni = JniScanRange(startHeight = 200L, endHeight = 100L, priority = SuggestScanRangePriority.Verify.priority)
        assertNull(ScanRange.new(jni))
    }

    @Test
    fun scan_range_boundaries_test() {
        val scanRange =
            ScanRangeFixture.new(
                range = ZcashNetwork.Testnet.saplingActivationHeight..ZcashNetwork.Testnet.saplingActivationHeight + 9
            )
        assertTrue { scanRange.range.isNotEmpty() }
        assertTrue { scanRange.range.length() == 10L }
    }
}
