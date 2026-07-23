package com.zodl.slipstream

import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.internal.ImportAccountErrors
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportAccountErrorMappingTest {
    @Test
    fun marker_prefixed_message_is_recognized_as_checkpoints_not_ready() {
        val t =
            RuntimeException(
                "ImportAccountCheckpointsNotReady: the wallet's checkpoints are not yet in sync " +
                    "across shielded pools. Underlying error: Sapling and Ironwood should have the same checkpoints"
            )

        assertTrue(ImportAccountErrors.isCheckpointsNotReady(t))
    }

    @Test
    fun message_without_the_marker_prefix_is_not_recognized() {
        val t = RuntimeException("Error while initializing accounts: some other database corruption")

        assertFalse(ImportAccountErrors.isCheckpointsNotReady(t))
    }

    @Test
    fun marker_appearing_after_the_start_of_the_message_is_not_recognized() {
        // The marker must be a *prefix*, mirroring `str::ends_with`/`starts_with` matching on the
        // Rust side - a message that merely mentions it elsewhere must not match.
        val t = RuntimeException("see also ImportAccountCheckpointsNotReady for details")

        assertFalse(ImportAccountErrors.isCheckpointsNotReady(t))
    }

    @Test
    fun throwable_with_no_message_is_not_recognized() {
        val t = RuntimeException()

        assertFalse(ImportAccountErrors.isCheckpointsNotReady(t))
    }

    @Test
    fun import_account_checkpoints_not_ready_exception_wraps_its_cause() {
        val cause = RuntimeException("ImportAccountCheckpointsNotReady: transient")

        val exception = InitializeException.ImportAccountCheckpointsNotReadyException(cause)

        assertEquals(cause, exception.cause)
        assertTrue(exception.message!!.contains("checkpoints"))
    }

    @Test
    fun import_account_checkpoints_not_ready_exception_is_a_distinct_type_from_import_account_exception() {
        val cause = RuntimeException("ImportAccountCheckpointsNotReady: transient")

        val exception: InitializeException = InitializeException.ImportAccountCheckpointsNotReadyException(cause)

        assertFalse(exception is InitializeException.ImportAccountException)
    }
}
