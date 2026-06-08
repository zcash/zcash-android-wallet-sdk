package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.Eip681
import cash.z.ecc.android.sdk.internal.model.JniEip681TransactionRequest

class RustEip681Tool private constructor() : Eip681 {
    @Throws(RuntimeException::class)
    override fun parseTransactionRequest(uri: String): JniEip681TransactionRequest =
        parseEip681TransactionRequest(uri)

    @Throws(RuntimeException::class)
    override fun transactionRequestToUri(request: JniEip681TransactionRequest): String =
        eip681TransactionRequestToUri(request)

    companion object {
        suspend fun new(): Eip681 {
            RustBackend.loadLibrary()

            return RustEip681Tool()
        }

        @JvmStatic
        private external fun parseEip681TransactionRequest(input: String): JniEip681TransactionRequest

        @JvmStatic
        private external fun eip681TransactionRequestToUri(
            request: JniEip681TransactionRequest
        ): String
    }
}
