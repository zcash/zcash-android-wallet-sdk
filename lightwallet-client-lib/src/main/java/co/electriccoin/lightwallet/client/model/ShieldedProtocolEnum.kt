package co.electriccoin.lightwallet.client.model

import cash.z.wallet.sdk.internal.rpc.Service.ShieldedProtocol

enum class ShieldedProtocolEnum {
    SAPLING,
    ORCHARD,
    IRONWOOD;

    fun toProtocol() =
        when (this) {
            SAPLING -> ShieldedProtocol.sapling
            ORCHARD -> ShieldedProtocol.orchard
            IRONWOOD -> ShieldedProtocol.ironwood
        }
}
