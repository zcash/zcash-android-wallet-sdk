package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.model.BlockHeight

internal data class ScanSummary(
    val scannedRange: ClosedRange<BlockHeight>,
    val spentSaplingNoteCount: Long,
    val receivedSaplingNoteCount: Long
) {
    companion object {
        /**
         *  Note that this function subtracts 1 from [JniScanSummary.endHeight]
         *  as the rest of the logic works with [ClosedRange] and the endHeight
         *  is exclusive.
         */
        fun new(jni: JniScanSummary): ScanSummary =
            ScanSummary(
                scannedRange = BlockHeight.new(jni.startHeight)..(BlockHeight.new(jni.endHeight) - 1),
                spentSaplingNoteCount = jni.spentSaplingNoteCount,
                receivedSaplingNoteCount = jni.receivedSaplingNoteCount
            )
    }
}
