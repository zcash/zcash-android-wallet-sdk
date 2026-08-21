package cash.z.ecc.android.sdk.internal.jni

import cash.z.ecc.android.sdk.internal.PaymentUri

class RustPaymentUriTool private constructor() : PaymentUri {
    @Throws(RuntimeException::class)
    override fun parse(uri: String): String = parsePaymentUri(uri)

    companion object {
        suspend fun new(): PaymentUri {
            RustBackend.loadLibrary()
            return RustPaymentUriTool()
        }

        @JvmStatic
        private external fun parsePaymentUri(input: String): String
    }
}
