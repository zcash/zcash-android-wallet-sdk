package cash.z.ecc.android.sdk.internal.transaction

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmitResultClassificationTest {
    @Test
    fun recognises_already_in_mempool() {
        assertTrue(isAlreadyKnownToNetwork("transaction already exists in mempool"))
        assertTrue(isAlreadyKnownToNetwork("send failed: transaction already exists in mempool"))
        assertTrue(isAlreadyKnownToNetwork("Transaction Already Exists In Mempool"))
    }

    @Test
    fun recognises_already_queued_for_download() {
        assertTrue(isAlreadyKnownToNetwork("transaction dropped because it is already queued for download"))
        assertTrue(isAlreadyKnownToNetwork("ALREADY QUEUED FOR DOWNLOAD"))
    }

    @Test
    fun does_not_match_unrelated_failures() {
        assertFalse(isAlreadyKnownToNetwork(null))
        assertFalse(isAlreadyKnownToNetwork(""))
        assertFalse(isAlreadyKnownToNetwork("insufficient funds"))
        assertFalse(isAlreadyKnownToNetwork("transaction rejected: bad signature"))
        assertFalse(isAlreadyKnownToNetwork("connection refused"))
    }
}
