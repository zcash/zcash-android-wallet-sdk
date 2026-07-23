package com.zodl.slipstream.internal

import com.zodl.slipstream.model.SlipstreamEvent
import org.junit.Test
import kotlin.test.assertEquals

class EngineEventTest {
    @Test
    fun tag_1_is_sync_started() =
        assertEquals(EngineEvent.SyncStarted, EngineEvent.decode(SlipstreamEvent(1, 0)))

    @Test
    fun tag_2_carries_scanned_blocks() =
        assertEquals(EngineEvent.SyncProgress(1234), EngineEvent.decode(SlipstreamEvent(2, 1234)))

    @Test
    fun tag_3_carries_stored_tx_count() =
        assertEquals(EngineEvent.SyncDone(7), EngineEvent.decode(SlipstreamEvent(3, 7)))

    @Test
    fun tag_4_value_1_is_pass_failure_value_2_is_panic() {
        assertEquals(EngineEvent.SyncError(panicked = false), EngineEvent.decode(SlipstreamEvent(4, 1)))
        assertEquals(EngineEvent.SyncError(panicked = true), EngineEvent.decode(SlipstreamEvent(4, 2)))
    }

    @Test
    fun tag_5_is_found_transactions() =
        assertEquals(EngineEvent.FoundTransactions, EngineEvent.decode(SlipstreamEvent(5, 0)))

    @Test
    fun unknown_tags_decode_without_crashing() =
        assertEquals(EngineEvent.Unknown(9, 42), EngineEvent.decode(SlipstreamEvent(9, 42)))
}
