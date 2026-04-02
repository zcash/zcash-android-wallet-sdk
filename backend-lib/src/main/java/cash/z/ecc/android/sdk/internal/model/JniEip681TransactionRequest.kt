package cash.z.ecc.android.sdk.internal.model

import androidx.annotation.Keep

/**
 * Serves as cross layer (Kotlin, Rust) communication class for parsed EIP-681 transaction requests.
 *
 * EIP-681 defines a standard URI format for Ethereum transaction requests, commonly used in
 * QR codes and deep links. This sealed class represents the three recognized forms: native
 * ETH transfers, ERC-20 token transfers, and unrecognised (but syntactically valid) requests.
 */
@Keep
sealed class JniEip681TransactionRequest {
    /**
     * A native ETH/chain token transfer (no function call).
     *
     * @param schemaPrefix the URI schema prefix (e.g. "ethereum").
     * @param hasPay whether the URI uses the "pay-" prefix after the schema (e.g. "ethereum:pay-...").
     * @param chainId the chain ID as a decimal string, or null if not specified in the URI.
     * @param recipientAddress the recipient address (ERC-55 checksummed hex or ENS name).
     * @param valueHex the transfer value as a `0x`-prefixed hex string, or null if not specified.
     * @param gasLimitHex the gas limit as a `0x`-prefixed hex string, or null if not specified.
     * @param gasPriceHex the gas price as a `0x`-prefixed hex string, or null if not specified.
     */
    @Keep
    @Suppress("LongParameterList")
    class Native(
        val schemaPrefix: String,
        val hasPay: Boolean,
        val chainId: String?,
        val recipientAddress: String,
        val valueHex: String?,
        val gasLimitHex: String?,
        val gasPriceHex: String?
    ) : JniEip681TransactionRequest()

    /**
     * An ERC-20 token transfer via `transfer(address,uint256)`.
     *
     * @param schemaPrefix the URI schema prefix (e.g. "ethereum").
     * @param hasPay whether the URI uses the "pay-" prefix after the schema (e.g. "ethereum:pay-...").
     * @param chainId the chain ID as a decimal string, or null if not specified in the URI.
     * @param tokenContractAddress the ERC-20 token contract address (ERC-55 checksummed hex or ENS name).
     * @param recipientAddress the transfer recipient address (ERC-55 checksummed hex or ENS name).
     * @param valueHex the transfer value in atomic units as a `0x`-prefixed hex string.
     */
    @Keep
    class Erc20(
        val schemaPrefix: String,
        val hasPay: Boolean,
        val chainId: String?,
        val tokenContractAddress: String,
        val recipientAddress: String,
        val valueHex: String
    ) : JniEip681TransactionRequest()

    /**
     * A valid EIP-681 request that is not a recognized transfer pattern.
     */
    @Keep
    class Unrecognised : JniEip681TransactionRequest()
}
