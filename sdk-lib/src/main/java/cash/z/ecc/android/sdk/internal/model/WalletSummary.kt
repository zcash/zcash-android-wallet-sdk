package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight

internal data class WalletSummary(
    val accountBalances: Map<AccountUuid, AccountBalance>,
    val chainTipHeight: BlockHeight,
    val fullyScannedHeight: BlockHeight,
    val scanProgress: ScanProgress,
    val recoveryProgress: RecoveryProgress?,
    val nextSaplingSubtreeIndex: UInt,
    val nextOrchardSubtreeIndex: UInt,
    val nextIronwoodSubtreeIndex: UInt
) {
    companion object {
        fun new(jni: JniWalletSummary): WalletSummary =
            WalletSummary(
                accountBalances =
                    jni.accountBalances.associateBy(
                        { AccountUuid.new(it.accountUuid) },
                        { AccountBalance.new(it) }
                    ),
                chainTipHeight = BlockHeight(jni.chainTipHeight),
                fullyScannedHeight = BlockHeight(jni.fullyScannedHeight),
                scanProgress = ScanProgress.new(jni),
                recoveryProgress = RecoveryProgress.new(jni),
                nextSaplingSubtreeIndex = jni.nextSaplingSubtreeIndex.toUInt(),
                nextOrchardSubtreeIndex = jni.nextOrchardSubtreeIndex.toUInt(),
                nextIronwoodSubtreeIndex = jni.nextIronwoodSubtreeIndex.toUInt()
            )
    }
}
