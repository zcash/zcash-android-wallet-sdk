package cash.z.ecc.android.sdk.block.processor.model

import cash.z.ecc.android.sdk.internal.model.SubtreeRoot

/**
 * Internal class for get subtree roots action result.
 */
internal sealed class GetSubtreeRootsResult {
    data class SpendBeforeSync(
        val saplingStartIndex: UInt,
        val saplingSubtreeRootList: List<SubtreeRoot>,
        val orchardStartIndex: UInt,
        val orchardSubtreeRootList: List<SubtreeRoot>,
        val ironwoodStartIndex: UInt,
        val ironwoodSubtreeRootList: List<SubtreeRoot>
    ) : GetSubtreeRootsResult()

    data object Linear : GetSubtreeRootsResult()

    data object FailureConnection : GetSubtreeRootsResult()

    data class OtherFailure(
        val exception: Throwable
    ) : GetSubtreeRootsResult()
}
