package cash.z.ecc.android.sdk

import cash.z.ecc.android.sdk.internal.PaymentUri
import cash.z.ecc.android.sdk.internal.jni.RustPaymentUriTool
import cash.z.ecc.android.sdk.model.Eip681PaymentRequest
import cash.z.ecc.android.sdk.model.InvalidPaymentUriException
import cash.z.ecc.android.sdk.model.PaymentUriAddress
import cash.z.ecc.android.sdk.model.PaymentUriAmount
import cash.z.ecc.android.sdk.model.PaymentUriLink
import cash.z.ecc.android.sdk.model.PaymentUriNetwork
import cash.z.ecc.android.sdk.model.PaymentUriRequest
import cash.z.ecc.android.sdk.model.SolanaPayTransferRequest
import cash.z.ecc.android.sdk.model.UtxoPaymentUriRequest
import org.json.JSONObject

/**
 * Rust-backed parser for supported cross-chain payment request URIs, backed by librustzcash's
 * `payment_uri` crate. Covers Bitcoin/Litecoin on-chain transfers (the on-chain subset of
 * [BIP 321](https://github.com/bitcoin/bips/blob/master/bip-0321.mediawiki) / legacy
 * [BIP 21](https://github.com/bitcoin/bips/blob/master/bip-0021.mediawiki)), EIP-681 Ethereum
 * requests (native and ERC-20 transfers; other ABI calls decode as
 * [Eip681PaymentRequest.Unrecognised]), and
 * [Solana Pay](https://github.com/solana-foundation/solana-pay/blob/master/SPEC.md)
 * native/SPL-token transfers and interactive transaction-request links. All actual protocol
 * parsing and validation happens in the Rust crate; this class only decodes its versioned JSON
 * result into these Kotlin types.
 */
class PaymentUriParser private constructor(
    private val paymentUri: PaymentUri
) {
    /** Parses and validates a Bitcoin, Ethereum, Litecoin, or Solana payment URI. */
    @Suppress("TooGenericExceptionCaught")
    fun parse(input: String): PaymentUriRequest =
        try {
            JSONObject(paymentUri.parse(input)).toPaymentRequest()
        } catch (e: InvalidPaymentUriException) {
            // toPaymentRequest() below throws this directly for a version mismatch, an
            // unrecognized result type, or an unrecognized network -- rethrow as-is rather than
            // letting the broader catch below wrap it a second time, which would otherwise bury
            // the real cause one level deeper for no benefit.
            throw e
        } catch (e: Exception) {
            // Deliberately broad: a JNI RuntimeException whose message already categorizes the
            // rejection without echoing raw URI content, a JSONException from unexpected schema,
            // or a genuine bug in the decode helpers below can all reach here, and all of them
            // should be chained as `cause` for diagnostics rather than discarded -- but this
            // exception's own message stays fixed and generic, since the input is untrusted and
            // may not be safe to display or log verbatim. See InvalidPaymentUriException's doc
            // for the full rationale.
            throw InvalidPaymentUriException(cause = e)
        }

    private fun JSONObject.toPaymentRequest(): PaymentUriRequest {
        val version = getInt("version")
        if (version != ENCODED_VERSION) {
            val message = "unsupported payment URI envelope version: $version (expected $ENCODED_VERSION)"
            throw InvalidPaymentUriException(cause = IllegalStateException(message))
        }
        return when (getString("type")) {
            "bitcoin" -> {
                PaymentUriRequest.Bitcoin(toUtxoRequest())
            }

            "ethereum_native" -> {
                PaymentUriRequest.Ethereum(toEthereumNativeRequest())
            }

            "ethereum_erc20" -> {
                PaymentUriRequest.Ethereum(toEthereumErc20Request())
            }

            "ethereum_unrecognised" -> {
                PaymentUriRequest.Ethereum(Eip681PaymentRequest.Unrecognised)
            }

            "litecoin" -> {
                PaymentUriRequest.Litecoin(toUtxoRequest())
            }

            "solana_transfer" -> {
                PaymentUriRequest.SolanaTransfer(toSolanaTransfer())
            }

            "solana_transaction" -> {
                PaymentUriRequest.SolanaTransaction(
                    PaymentUriLink(getString("link"))
                )
            }

            else -> {
                throw InvalidPaymentUriException(
                    cause = IllegalStateException("unrecognized payment URI result type: ${getString("type")}")
                )
            }
        }
    }

    private fun JSONObject.toUtxoRequest() =
        UtxoPaymentUriRequest(
            address = PaymentUriAddress(getString("address")),
            network = getString("network").toPaymentUriNetwork(),
            amount = optionalString("amount")?.let(::PaymentUriAmount),
            label = optionalString("label"),
            message = optionalString("message")
        )

    private fun JSONObject.toSolanaTransfer() =
        SolanaPayTransferRequest(
            recipient = PaymentUriAddress(getString("recipient")),
            amount = optionalString("amount")?.let(::PaymentUriAmount),
            splToken = optionalString("spl_token")?.let(::PaymentUriAddress),
            references =
                optJSONArray("references")
                    ?.let { values ->
                        List(values.length()) { PaymentUriAddress(values.getString(it)) }
                    }.orEmpty(),
            label = optionalString("label"),
            message = optionalString("message"),
            memo = optionalString("memo")
        )

    /** Fields common to both EIP-681 transfer variants. */
    private data class CommonEip681Fields(
        val schemaPrefix: String,
        val hasPay: Boolean,
        val chainId: String?
    )

    private fun JSONObject.commonEip681Fields() =
        CommonEip681Fields(
            schemaPrefix = getString("schema_prefix"),
            hasPay = getBoolean("has_pay"),
            chainId = optionalString("chain_id")
        )

    private fun JSONObject.toEthereumNativeRequest(): Eip681PaymentRequest.Native {
        val common = commonEip681Fields()
        return Eip681PaymentRequest.Native(
            schemaPrefix = common.schemaPrefix,
            hasPay = common.hasPay,
            chainId = common.chainId,
            recipientAddress = PaymentUriAddress(getString("recipient_address")),
            valueHex = optionalString("value_hex"),
            gasLimitHex = optionalString("gas_limit_hex"),
            gasPriceHex = optionalString("gas_price_hex")
        )
    }

    private fun JSONObject.toEthereumErc20Request(): Eip681PaymentRequest.Erc20 {
        val common = commonEip681Fields()
        return Eip681PaymentRequest.Erc20(
            schemaPrefix = common.schemaPrefix,
            hasPay = common.hasPay,
            chainId = common.chainId,
            tokenContractAddress = PaymentUriAddress(getString("token_contract_address")),
            recipientAddress = PaymentUriAddress(getString("recipient_address")),
            valueHex = getString("value_hex")
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun String.toPaymentUriNetwork() =
        when (this) {
            "mainnet" -> PaymentUriNetwork.Mainnet

            "testnet" -> PaymentUriNetwork.Testnet

            "regtest" -> PaymentUriNetwork.Regtest

            else -> throw InvalidPaymentUriException(
                cause = IllegalStateException("unrecognized payment URI network: $this")
            )
        }

    companion object {
        // Must match `payment_uri::JSON_VERSION` in
        // https://github.com/zcash/librustzcash/blob/main/components/payment_uri/src/lib.rs --
        // that `pub const` is the actual source of truth for this JSON envelope's version; bump
        // both together whenever the crate's emitted schema changes.
        private const val ENCODED_VERSION = 1

        /** Loads the native library and creates a parser. */
        suspend fun new(): PaymentUriParser =
            PaymentUriParser(paymentUri = RustPaymentUriTool.new())
    }
}
