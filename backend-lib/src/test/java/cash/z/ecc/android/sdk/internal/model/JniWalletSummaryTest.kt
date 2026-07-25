package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.fixture.JniAccountBalanceFixture
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JniWalletSummaryTest {
    @Test
    fun all_attribute_within_constraints() {
        val instance =
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgressNumerator = 1L,
                scanProgressDenominator = 100L,
                nextSaplingSubtreeIndex = 0L,
                nextOrchardSubtreeIndex = 0L,
                nextIronwoodSubtreeIndex = 0L,
                recoveryProgressNumerator = 1L,
                recoveryProgressDenominator = 100L
            )
        assertIs<JniWalletSummary>(instance)
    }

    @Test
    fun height_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = -1,
                fullyScannedHeight = 0,
                scanProgressNumerator = 1L,
                scanProgressDenominator = 100L,
                nextSaplingSubtreeIndex = 0L,
                nextOrchardSubtreeIndex = 0,
                nextIronwoodSubtreeIndex = 0L,
                recoveryProgressNumerator = 1L,
                recoveryProgressDenominator = 100L
            )
        }
    }

    @Test
    fun numerator_attribute_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgressNumerator = -1L,
                scanProgressDenominator = 100L,
                nextSaplingSubtreeIndex = 0L,
                nextOrchardSubtreeIndex = 0L,
                nextIronwoodSubtreeIndex = 0L,
                recoveryProgressNumerator = 1L,
                recoveryProgressDenominator = 100L
            )
        }
    }

    @Test
    fun denominator_attribute_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgressNumerator = 1L,
                scanProgressDenominator = -1L,
                nextSaplingSubtreeIndex = 0L,
                nextOrchardSubtreeIndex = 0L,
                nextIronwoodSubtreeIndex = 0L,
                recoveryProgressNumerator = 1L,
                recoveryProgressDenominator = 100L
            )
        }
    }

    @Test
    fun ratio_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgressNumerator = 100L,
                scanProgressDenominator = 1L,
                nextSaplingSubtreeIndex = 0L,
                nextOrchardSubtreeIndex = 0L,
                nextIronwoodSubtreeIndex = 0L,
                recoveryProgressNumerator = 100L,
                recoveryProgressDenominator = 1L
            )
        }
    }

    @Test
    fun subtree_index_not_in_constraints() {
        assertFailsWith(IllegalArgumentException::class) {
            JniWalletSummary(
                accountBalances = arrayOf(JniAccountBalanceFixture.new()),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgressNumerator = 1L,
                scanProgressDenominator = 100L,
                nextSaplingSubtreeIndex = -1L,
                nextOrchardSubtreeIndex = -1L,
                nextIronwoodSubtreeIndex = -1L,
                recoveryProgressNumerator = 1L,
                recoveryProgressDenominator = 100L
            )
        }
    }
}
