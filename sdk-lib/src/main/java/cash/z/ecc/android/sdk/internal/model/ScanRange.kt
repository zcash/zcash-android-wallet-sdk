package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.model.BlockHeight

internal data class ScanRange(
    val range: ClosedRange<BlockHeight>,
    val priority: Long
) {
    override fun toString() = "ScanRange(range=$range, priority=${getSuggestScanRangePriority()})"

    internal fun getSuggestScanRangePriority(): SuggestScanRangePriority =
        SuggestScanRangePriority.entries.first { it.priority == priority }

    init {
        require(SuggestScanRangePriority.entries.map { it.priority }.contains(priority)) {
            "Unsupported priority $priority used"
        }
    }

    companion object {
        /**
         * Note that this function subtracts 1 from [JniScanRange.endHeight] as the rest of the logic works with
         * [ClosedRange] and the endHeight is exclusive.
         *
         * Returns null for empty or invalid ranges (where [JniScanRange.endHeight] <= [JniScanRange.startHeight]),
         * which can occur after a resync reset when the Rust layer returns a zero-height range. Such ranges contain
         * no blocks and can be safely skipped.
         */
        fun new(jni: JniScanRange): ScanRange? {
            if (jni.endHeight <= jni.startHeight) {
                Twig.warn { "Skipping empty scan range returned by Rust: startHeight=${jni.startHeight}, endHeight=${jni.endHeight}" }
                return null
            }
            return ScanRange(
                range = BlockHeight.new(jni.startHeight)..(BlockHeight.new(jni.endHeight) - 1),
                priority = jni.priority
            )
        }
    }
}

@Suppress("MagicNumber")
internal enum class SuggestScanRangePriority(
    val priority: Long
) {
    Ignored(0),
    Scanned(10),
    Historic(20),
    OpenAdjacent(30),
    FoundNote(40),
    ChainTip(50),
    Verify(60)
}
