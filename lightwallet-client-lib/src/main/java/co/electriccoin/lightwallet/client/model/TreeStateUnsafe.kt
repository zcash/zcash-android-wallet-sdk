package co.electriccoin.lightwallet.client.model

import cash.z.wallet.sdk.internal.rpc.Service.TreeState

class TreeStateUnsafe(
    val encoded: ByteArray
) {
    companion object {
        fun new(treeState: TreeState): TreeStateUnsafe = TreeStateUnsafe(treeState.toByteArray())

        @Suppress("LongParameterList")
        fun fromParts(
            height: Long,
            hash: String,
            time: Int,
            saplingTree: String,
            orchardTree: String,
            ironwoodTree: String?
        ): TreeStateUnsafe {
            val treeState =
                TreeState
                    .newBuilder()
                    .setHeight(height)
                    .setHash(hash)
                    .setTime(time)
                    .setSaplingTree(saplingTree)
                    .setOrchardTree(orchardTree)
                    .apply { ironwoodTree?.let { setIronwoodTree(it) } }
                    .build()
            return new(treeState)
        }
    }
}
