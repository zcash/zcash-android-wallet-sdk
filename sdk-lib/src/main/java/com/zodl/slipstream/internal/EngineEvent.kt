package com.zodl.slipstream.internal

import com.zodl.slipstream.model.SlipstreamEvent

/**
 * Pure decode of a raw [SlipstreamEvent] into a typed shape. Forward-compatible by construction:
 * an unrecognized tag decodes to [Unknown] rather than throwing, per `HOSTING.md` section 6
 * ("adapters ignore what they do not know").
 */
internal sealed interface EngineEvent {
    data object SyncStarted : EngineEvent

    data class SyncProgress(
        val scannedBlocks: Long
    ) : EngineEvent

    data class SyncDone(
        val transactionsStored: Long
    ) : EngineEvent

    data class SyncError(
        val panicked: Boolean
    ) : EngineEvent

    data object FoundTransactions : EngineEvent

    data class Unknown(
        val tag: Int,
        val value: Long
    ) : EngineEvent

    companion object {
        fun decode(raw: SlipstreamEvent): EngineEvent =
            when (raw.tag) {
                SlipstreamEvent.TAG_SYNC_STARTED -> SyncStarted
                SlipstreamEvent.TAG_SYNC_PROGRESS -> SyncProgress(raw.value)
                SlipstreamEvent.TAG_SYNC_DONE -> SyncDone(raw.value)
                SlipstreamEvent.TAG_SYNC_ERROR -> SyncError(panicked = raw.value == 2L)
                SlipstreamEvent.TAG_FOUND_TRANSACTIONS -> FoundTransactions
                else -> Unknown(raw.tag, raw.value)
            }
    }
}
