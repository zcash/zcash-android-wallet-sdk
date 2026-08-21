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

/** Rust-backed parser for supported cross-chain payment request URIs. */
class PaymentUriParser private constructor(
    private val paymentUri: PaymentUri
) {
    /** Parses and validates a Bitcoin, Ethereum, Litecoin, or Solana payment URI. */
    fun parse(input: String): PaymentUriRequest =
        try {
            JSONObject(paymentUri.parse(input)).toPaymentRequest()
        } catch (_: Exception) {
            throw InvalidPaymentUriException()
        }

    private fun JSONObject.toPaymentRequest(): PaymentUriRequest {
        if (getInt("version") != ENCODED_VERSION) throw InvalidPaymentUriException()
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
                throw InvalidPaymentUriException()
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
                getJSONArray("references").let { values ->
                    List(values.length()) { PaymentUriAddress(values.getString(it)) }
                },
            label = optionalString("label"),
            message = optionalString("message"),
            memo = optionalString("memo")
        )

    private fun JSONObject.toEthereumNativeRequest() =
        Eip681PaymentRequest.Native(
            schemaPrefix = getString("schema_prefix"),
            hasPay = getBoolean("has_pay"),
            chainId = optionalString("chain_id"),
            recipientAddress = PaymentUriAddress(getString("recipient_address")),
            valueHex = optionalString("value_hex"),
            gasLimitHex = optionalString("gas_limit_hex"),
            gasPriceHex = optionalString("gas_price_hex")
        )

    private fun JSONObject.toEthereumErc20Request() =
        Eip681PaymentRequest.Erc20(
            schemaPrefix = getString("schema_prefix"),
            hasPay = getBoolean("has_pay"),
            chainId = optionalString("chain_id"),
            tokenContractAddress = PaymentUriAddress(getString("token_contract_address")),
            recipientAddress = PaymentUriAddress(getString("recipient_address")),
            valueHex = getString("value_hex")
        )

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun String.toPaymentUriNetwork() =
        when (this) {
            "mainnet" -> PaymentUriNetwork.Mainnet
            "testnet" -> PaymentUriNetwork.Testnet
            "regtest" -> PaymentUriNetwork.Regtest
            else -> throw InvalidPaymentUriException()
        }

    companion object {
        private const val ENCODED_VERSION = 1

        /** Loads the native library and creates a parser. */
        suspend fun new(): PaymentUriParser =
            PaymentUriParser(paymentUri = RustPaymentUriTool.new())
    }
}
