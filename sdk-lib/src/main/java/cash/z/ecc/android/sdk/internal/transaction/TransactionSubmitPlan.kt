package cash.z.ecc.android.sdk.internal.transaction

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

internal data class TransactionSubmitPlan(
    val endpoints: List<LightWalletEndpoint>
) {
    init {
        require(endpoints.isNotEmpty()) {
            "Transaction submit plan must include at least one endpoint."
        }
    }
}
