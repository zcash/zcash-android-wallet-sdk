package cash.z.ecc.android.sdk.util

import android.content.Context
import cash.z.ecc.android.sdk.internal.model.CombinedWalletClientImpl
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.PartialTorWalletClient
import co.electriccoin.lightwallet.client.WalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

/**
 * A factory responsible for creating an instance of [WalletClient].
 */
class WalletClientFactory(
    private val context: Context,
    private val torClient: LazyTorClient?
) {
    /**
     * Creates a [CombinedWalletClientImpl] which will leverage Tor for lightwalletd connection for functions specified
     * in [PartialTorWalletClient].
     * Other functions specified in [WalletClient] will use regular lightwalletd connection using [LightWalletClient].
     *
     * The base Tor client (and its isolated client used here) is not created until the first Tor-mode
     * request is actually made, keeping Tor runtime creation off the cold-start path.
     *
     * @return an instance of [WalletClient] for [endpoint]
     */
    suspend fun create(endpoint: LightWalletEndpoint): CombinedWalletClient =
        CombinedWalletClientImpl.new(
            endpoint = endpoint,
            lightWalletClient = LightWalletClient.new(context, endpoint),
            isolatedTorClient = torClient?.let { holder -> LazyTorClient { holder.getOrCreate().isolatedTorClient() } },
        )
}
