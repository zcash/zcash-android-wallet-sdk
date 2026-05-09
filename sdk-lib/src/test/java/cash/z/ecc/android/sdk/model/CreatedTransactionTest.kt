package cash.z.ecc.android.sdk.model

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatedTransactionTest {
    @Test
    fun to_string_does_not_include_raw_transaction_bytes() {
        val transaction =
            CreatedTransaction(
                txId = FirstClassByteArray(byteArrayOf(0x01)),
                raw = FirstClassByteArray(byteArrayOf(0x02, 0x03)),
                expiryHeight = BlockHeight.new(10)
            )

        val string = transaction.toString()

        assertTrue(string.contains(transaction.txIdString()))
        assertTrue(string.contains("expiryHeight=${transaction.expiryHeight}"))
        assertFalse(string.contains(transaction.raw.toString()))
        assertFalse(string.contains("raw"))
    }
}
