package cash.z.ecc.android.sdk.model

/** Address text validated by the Rust payment URI parser. */
@JvmInline
value class PaymentUriAddress internal constructor(
    val value: String
)

/** Exact non-negative decimal amount without floating-point conversion. */
@JvmInline
value class PaymentUriAmount internal constructor(
    val value: String
)

/** HTTPS transaction-request link validated by the Rust parser. */
@JvmInline
value class PaymentUriLink internal constructor(
    val value: String
)

/** Network encoded by a Bitcoin or Litecoin address. */
enum class PaymentUriNetwork {
    Mainnet,
    Testnet,
    Regtest
}

/** Validated Bitcoin or Litecoin payment request. */
@ConsistentCopyVisibility
data class UtxoPaymentUriRequest internal constructor(
    val address: PaymentUriAddress,
    val network: PaymentUriNetwork,
    val amount: PaymentUriAmount?,
    val label: String?,
    val message: String?
)

/** Validated Solana Pay transfer request. */
@ConsistentCopyVisibility
data class SolanaPayTransferRequest internal constructor(
    val recipient: PaymentUriAddress,
    val amount: PaymentUriAmount?,
    val splToken: PaymentUriAddress?,
    val references: List<PaymentUriAddress>,
    val label: String?,
    val message: String?,
    val memo: String?
)

/** Parsed EIP-681 payment request. */
sealed interface Eip681PaymentRequest {
    /** Native ETH or chain-token transfer. */
    @ConsistentCopyVisibility
    data class Native internal constructor(
        val schemaPrefix: String,
        val hasPay: Boolean,
        val chainId: String?,
        val recipientAddress: PaymentUriAddress,
        val valueHex: String?,
        val gasLimitHex: String?,
        val gasPriceHex: String?
    ) : Eip681PaymentRequest

    /** ERC-20 `transfer(address,uint256)` request. */
    @ConsistentCopyVisibility
    data class Erc20 internal constructor(
        val schemaPrefix: String,
        val hasPay: Boolean,
        val chainId: String?,
        val tokenContractAddress: PaymentUriAddress,
        val recipientAddress: PaymentUriAddress,
        val valueHex: String
    ) : Eip681PaymentRequest

    /** Valid EIP-681 request that is not a recognized transfer. */
    data object Unrecognised : Eip681PaymentRequest
}

/** Parsed and validated cross-chain payment request. */
sealed interface PaymentUriRequest {
    data class Bitcoin(
        val request: UtxoPaymentUriRequest
    ) : PaymentUriRequest

    data class Ethereum(
        val request: Eip681PaymentRequest
    ) : PaymentUriRequest

    data class Litecoin(
        val request: UtxoPaymentUriRequest
    ) : PaymentUriRequest

    data class SolanaTransfer(
        val request: SolanaPayTransferRequest
    ) : PaymentUriRequest

    data class SolanaTransaction(
        val link: PaymentUriLink
    ) : PaymentUriRequest
}

/**
 * Raised when a payment URI is malformed or unsupported. `cause` chains the original failure
 * (a JNI RuntimeException whose message categorizes the kind of rejection without echoing the
 * raw URI content, a JSONException from unexpected schema, etc.) for diagnostics -- but this
 * exception's own message stays fixed and generic, since payment URIs come from untrusted
 * scanned/pasted input and this SDK's convention is to never splice raw caller-supplied content
 * into a message that could end up displayed or logged verbatim.
 */
class InvalidPaymentUriException(
    cause: Throwable? = null
) : IllegalArgumentException("Invalid payment URI", cause)
