package com.zodl.slipstream.internal

import com.zodl.slipstream.model.SlipstreamEvent
import com.zodl.slipstream.model.SlipstreamSnapshot
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `QA_MATRIX.md` F4, JVM half: "flood events, assert ... that tx re-query keys off
 * `tx_set_version` only". The ring/overflow-eviction half of F4 needs the real engine
 * (instrumented tier, unreachable without `libslipstream.so` - see the worklog); this covers the
 * pure-logic guarantee that [PollGate] never keys its decision off event tags or event counts,
 * however many events are decoded in between two snapshots (DECISIONS.md D11: no event-sniffing
 * heuristics beside the version counter).
 */
private fun snap(
    version: Long = 0,
    recovering: Boolean = false
) = SlipstreamSnapshot(0, 0, 0, 0, 0, 1, 0, false, 0, recovering, 0, 0, false, version)

class PollGateEventFloodTest {
    @Test
    fun flooding_droppable_events_between_ticks_never_triggers_a_re_query_on_its_own() {
        val flood = (1..10_000).map { SlipstreamEvent(SlipstreamEvent.TAG_SYNC_PROGRESS, it.toLong()) }
        val decoded = flood.map { EngineEvent.decode(it) }
        assertTrue(decoded.all { it is EngineEvent.SyncProgress })

        val gate = PollGate.INITIAL.reduce(snap(version = 1)).next
        // A flood of 10 000 tag-2 events happened "between ticks" (irrelevant to PollGate, which
        // never sees events at all) - the version is unchanged, so no re-query fires.
        assertFalse(gate.reduce(snap(version = 1)).requeryTransactions)
    }

    @Test
    fun re_query_fires_exactly_once_per_version_bump_regardless_of_event_volume_in_between() {
        var gate = PollGate.INITIAL
        var requeryCount = 0
        // Simulate 500 ticks: version increments only every 10th tick, "flooded" every tick with
        // decoded events of every known tag - the flood must never move the decision.
        for (tick in 1..500) {
            val version = (tick / 10).toLong()
            repeat(50) { EngineEvent.decode(SlipstreamEvent((it % 5) + 1, it.toLong())) }
            val decision = gate.reduce(snap(version = version))
            if (decision.requeryTransactions) requeryCount++
            gate = decision.next
        }
        // First tick (version 0 -> gate.INITIAL has no lastTxSetVersion) always counts, then one
        // more re-query per version bump (50 bumps total across 500 ticks / 10).
        assertTrue(requeryCount in 1..51)
    }

    @Test
    fun unknown_future_tags_decode_without_affecting_the_gate() {
        val flood = (1..1_000).map { SlipstreamEvent(tag = 99, value = it.toLong()) }
        flood.forEach { assertTrue(EngineEvent.decode(it) is EngineEvent.Unknown) }

        val gate = PollGate.INITIAL.reduce(snap(version = 3)).next
        assertFalse(gate.reduce(snap(version = 3)).requeryTransactions)
    }
}
