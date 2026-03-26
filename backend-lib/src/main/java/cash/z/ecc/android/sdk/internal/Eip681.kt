package cash.z.ecc.android.sdk.internal

import cash.z.ecc.android.sdk.internal.model.JniEip681TransactionRequest

/**
 * Interface for EIP-681 transaction request parsing operations.
 */
interface Eip681 {
    /**
     * Parse an EIP-681 URI string into a [JniEip681TransactionRequest].
     *
     * @param uri a valid EIP-681 URI string (e.g. `"ethereum:0xAbC...?value=1e18"`).
     * @return a [JniEip681TransactionRequest] representing the parsed URI.
     * @throws RuntimeException if the input is not a valid EIP-681 URI.
     */
    @Throws(RuntimeException::class)
    fun parseTransactionRequest(uri: String): JniEip681TransactionRequest

    /**
     * Serialize a parsed EIP-681 transaction request to a normalized URI string.
     *
     * @param request a parsed [JniEip681TransactionRequest] (Native or Erc20).
     * @return the normalized URI string.
     * @throws RuntimeException if the request cannot be serialized (e.g. Unrecognised variant).
     */
    @Throws(RuntimeException::class)
    fun transactionRequestToUri(request: JniEip681TransactionRequest): String
}
