package cash.z.ecc.android.sdk.ext

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.internal.ext.ADDITIONAL_CHANGE_OUTPUT_REQUIRED
import cash.z.ecc.android.sdk.internal.ext.BLOCK_HEIGHT_DISCONTINUITY
import cash.z.ecc.android.sdk.internal.ext.INSUFFICIENT_BALANCE
import cash.z.ecc.android.sdk.internal.ext.PREV_HASH_MISMATCH
import cash.z.ecc.android.sdk.internal.ext.TREE_SIZE_MISMATCH
import cash.z.ecc.android.sdk.internal.ext.indicatesInsufficientFunds
import cash.z.ecc.android.sdk.internal.ext.isScanContinuityError
import cash.z.ecc.android.sdk.internal.ext.toProposalException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExceptionExtTest {
    @Test
    fun is_scan_continuity_error() {
        assertTrue { RuntimeException(PREV_HASH_MISMATCH).isScanContinuityError() }
        assertTrue { RuntimeException(TREE_SIZE_MISMATCH).isScanContinuityError() }
        assertTrue { RuntimeException(BLOCK_HEIGHT_DISCONTINUITY).isScanContinuityError() }

        assertTrue { RuntimeException(PREV_HASH_MISMATCH.lowercase()).isScanContinuityError() }

        assertTrue { RuntimeException(PREV_HASH_MISMATCH.plus("Text")).isScanContinuityError() }
    }

    @Test
    fun is_not_scan_continuity_error() {
        assertFalse { RuntimeException("Text").isScanContinuityError() }
        assertFalse { RuntimeException("").isScanContinuityError() }
        assertFalse { RuntimeException(PREV_HASH_MISMATCH.drop(1)).isScanContinuityError() }
    }

    @Test
    fun indicates_insufficient_funds() {
        assertTrue {
            RuntimeException("Insufficient balance (have 0, need 1510000 including fee)")
                .indicatesInsufficientFunds()
        }
        assertTrue { RuntimeException(INSUFFICIENT_BALANCE.lowercase()).indicatesInsufficientFunds() }
        assertTrue { RuntimeException(INSUFFICIENT_BALANCE.uppercase()).indicatesInsufficientFunds() }
        assertTrue {
            RuntimeException("$ADDITIONAL_CHANGE_OUTPUT_REQUIRED 10000 zatoshis").indicatesInsufficientFunds()
        }
    }

    @Test
    fun indicates_insufficient_funds_through_a_nested_cause() {
        val root = RuntimeException(INSUFFICIENT_BALANCE)
        val wrapped = IllegalStateException("proposal failed", RuntimeException("backend failed", root))

        assertTrue { wrapped.indicatesInsufficientFunds() }
    }

    @Test
    fun does_not_indicate_insufficient_funds() {
        assertFalse { RuntimeException("the database is locked").indicatesInsufficientFunds() }
        assertFalse { RuntimeException().indicatesInsufficientFunds() }
        assertFalse { RuntimeException(INSUFFICIENT_BALANCE.drop(1)).indicatesInsufficientFunds() }
    }

    @Test
    fun insufficient_funds_detection_terminates_on_a_cause_cycle() {
        val first = SelfCausedException("first")
        val second = SelfCausedException("second")
        first.initCauseTo(second)
        second.initCauseTo(first)

        assertFalse { first.indicatesInsufficientFunds() }
    }

    @Test
    fun insufficient_funds_detection_is_bounded_in_depth() {
        var deepest: Throwable = RuntimeException(INSUFFICIENT_BALANCE)
        repeat(20) { deepest = RuntimeException("wrapper", deepest) }

        assertFalse { deepest.indicatesInsufficientFunds() }
    }

    @Test
    fun to_proposal_exception_prefers_the_insufficient_funds_type() {
        val cause = RuntimeException(INSUFFICIENT_BALANCE)

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromParametersException)

        assertIs<TransactionEncoderException.InsufficientFundsException>(exception)
        assertSame(cause, exception.rootCause)
    }

    @Test
    fun to_proposal_exception_falls_back_to_the_per_operation_wrapper() {
        val cause = RuntimeException("the database is locked")

        val exception = cause.toProposalException(TransactionEncoderException::ProposalFromUriException)

        assertIs<TransactionEncoderException.ProposalFromUriException>(exception)
        assertSame(cause, exception.rootCause)
    }

    /** A [Throwable] whose cause can be pointed at another instance, so a cycle can be constructed. */
    private class SelfCausedException(
        message: String
    ) : RuntimeException(message) {
        private var selfCause: Throwable? = null

        fun initCauseTo(other: Throwable) {
            selfCause = other
        }

        override val cause: Throwable?
            get() = selfCause
    }
}
