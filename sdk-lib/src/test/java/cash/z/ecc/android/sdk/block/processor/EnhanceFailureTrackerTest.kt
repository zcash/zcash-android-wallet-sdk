package cash.z.ecc.android.sdk.block.processor

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnhanceFailureTrackerTest {
    private val txid1 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val txid2 = byteArrayOf(0x05, 0x06, 0x07, 0x08)

    @Test
    fun should_skip_returns_false_for_unknown_txid() =
        runBlocking {
            val tracker = EnhanceFailureTracker(clock = { 100 })

            assertFalse(tracker.shouldSkipDueToBackoff(txid1))
        }

    @Test
    fun should_skip_returns_true_within_first_backoff_window() =
        runBlocking {
            var now = 100L
            val tracker = EnhanceFailureTracker(clock = { now })

            tracker.recordFailure(txid1)
            now = 30_100L // 30 seconds later

            assertTrue(tracker.shouldSkipDueToBackoff(txid1))
        }

    @Test
    fun should_skip_returns_false_after_first_backoff_window() =
        runBlocking {
            var now = 100L
            val tracker = EnhanceFailureTracker(clock = { now })

            tracker.recordFailure(txid1)
            now = 100_000L // 100 seconds later, past the 60-second base backoff

            assertFalse(tracker.shouldSkipDueToBackoff(txid1))
        }

    @Test
    fun backoff_window_doubles_after_each_failure() =
        runBlocking {
            var now = 0L
            val tracker = EnhanceFailureTracker(clock = { now })

            tracker.recordFailure(txid1)
            now += 65_000L // 65 seconds — past the 60-second first window
            assertFalse(tracker.shouldSkipDueToBackoff(txid1))

            tracker.recordFailure(txid1)
            now += 65_000L // 65 seconds — within the 120-second second window
            assertTrue(tracker.shouldSkipDueToBackoff(txid1))
            now += 60_000L
            assertFalse(tracker.shouldSkipDueToBackoff(txid1))

            tracker.recordFailure(txid1)
            now += 200_000L // 200 seconds — within the 240-second third window
            assertTrue(tracker.shouldSkipDueToBackoff(txid1))
            now += 100_000L
            assertFalse(tracker.shouldSkipDueToBackoff(txid1))
        }

    @Test
    fun record_success_clears_backoff() =
        runBlocking {
            var now = 100L
            val tracker = EnhanceFailureTracker(clock = { now })

            tracker.recordFailure(txid1)
            assertTrue(tracker.shouldSkipDueToBackoff(txid1))

            tracker.recordSuccess(txid1)
            now += 1
            assertFalse(tracker.shouldSkipDueToBackoff(txid1))
        }

    @Test
    fun tracks_multiple_txids_independently() =
        runBlocking {
            var now = 100L
            val tracker = EnhanceFailureTracker(clock = { now })

            tracker.recordFailure(txid1)
            now = 100_000L
            tracker.recordFailure(txid2)
            now = 120_000L

            assertFalse(tracker.shouldSkipDueToBackoff(txid1))
            assertTrue(tracker.shouldSkipDueToBackoff(txid2))
        }
}
