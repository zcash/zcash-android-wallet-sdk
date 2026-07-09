package cash.z.ecc.android.sdk.internal.transaction

/**
 * Controls how outgoing transactions are submitted to the Zcash network.
 *
 * Choose [DirectP2P] to enable Dandelion++ Component A: the SDK connects directly
 * to a Zcash full node via the P2P wire protocol instead of routing through a
 * lightwalletd/Zaino server.  This prevents any intermediary from observing the
 * IP↔transaction correlation before the transaction enters the P2P network's
 * Dandelion++ stem phase.
 *
 * [LightWalletD] is the legacy default.  Chain synchronisation always uses lwd/Zaino
 * regardless of this setting.
 */
sealed class DandelionSubmitConfig {

    /**
     * Default: submit transactions via lightwalletd's `SendTransaction` gRPC RPC.
     * The lightwalletd operator sees your IP + transaction simultaneously.
     */
    object LightWalletD : DandelionSubmitConfig()

    /**
     * Direct P2P submission.  The SDK performs:
     * 1. DNS seeder lookup to find a live Zcash full node.
     * 2. TCP connect + Zcash P2P version/verack handshake.
     * 3. Sends the raw `tx` message without a prior `inv` (the "unadvertised tx"
     *    convention from ZIP 327), signalling to the receiving node that this is a
     *    direct wallet submission and should enter Dandelion++ stem phase immediately.
     * 4. Disconnects.
     *
     * No intermediary server observes IP + transaction together.  Pairing this with
     * Tor (if available) adds IP-level anonymity on top.
     *
     * Falls back to [LightWalletD] if all P2P peers are unreachable.
     *
     * @param network The Zcash network to connect to (mainnet or testnet).
     * @param fallbackToLightWalletD If true (default), fall back to lwd submission when
     *   P2P fails.  Set to false to surface the P2P failure to the caller instead.
     */
    data class DirectP2P(
        val network: ZcashP2PNetwork = ZcashP2PNetwork.MAINNET,
        val fallbackToLightWalletD: Boolean = true
    ) : DandelionSubmitConfig()
}
