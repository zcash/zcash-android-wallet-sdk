package cash.z.ecc.android.sdk.internal

import org.junit.Test
import kotlin.test.assertEquals

class ChainTipEstimatorTest {
    @Test
    fun `estimateTip floors elapsed over 75s`() {
        // 74*5 = 370 seconds elapsed; 370/75 = 4 (floor); 1000 + 4 = 1004
        assertEquals(1004, estimateTip(1000, 0, 74 * 5))
    }

    @Test
    fun `estimateTip clamps negative elapsed`() {
        // nowEpoch < scannedBlockTime: negative elapsed clamped to 0
        assertEquals(1000, estimateTip(1000, 500, 400))
    }
}
