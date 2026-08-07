package cash.z.ecc.android.sdk.internal.transaction

import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.SendResponseUnsafe
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmitTransactionTest {
    private val txId = FirstClassByteArray(ByteArray(32) { it.toByte() })

    @Test
    fun `OverTor failure maps to isTorFailure true`() {
        val response: Response<SendResponseUnsafe> =
            Response.Failure.OverTor(cause = RuntimeException("circuit build failed"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertTrue(result.isTorFailure)
        assertTrue(result.grpcError)
    }

    @Test
    fun `a generic Connection failure maps to isTorFailure false`() {
        val response: Response<SendResponseUnsafe> =
            Response.Failure.Connection<SendResponseUnsafe>(cause = RuntimeException("no network"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertFalse(result.isTorFailure)
        assertTrue(result.grpcError)
    }

    @Test
    fun `a mempool-rejection success-envelope failure maps to isTorFailure false`() {
        val response: Response<SendResponseUnsafe> =
            Response.Success(SendResponseUnsafe(code = 1, message = "rejected"))

        val result = response.toSubmitResult(txId)

        require(result is TransactionSubmitResult.Failure)
        assertFalse(result.isTorFailure)
        assertFalse(result.grpcError)
        assertEquals(1, result.code)
    }
}
