package com.zodl.slipstream.internal

import com.zodl.slipstream.model.SlipstreamSnapshot
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun snap(
    version: Long = 0,
    recovering: Boolean = false,
    state: Int = 1
) = SlipstreamSnapshot(0, 0, 0, 0, 0, state, 0, false, 0, recovering, 0, 0, false, version)

class PollGateTest {
    @Test
    fun first_tick_always_re_queries() = assertTrue(PollGate.INITIAL.reduce(snap()).requeryTransactions)

    @Test
    fun unchanged_version_and_scope_do_not_re_query() {
        val g1 = PollGate.INITIAL.reduce(snap(version = 5)).next
        assertFalse(g1.reduce(snap(version = 5)).requeryTransactions)
    }

    @Test
    fun version_bump_re_queries() {
        val g1 = PollGate.INITIAL.reduce(snap(version = 5)).next
        assertTrue(g1.reduce(snap(version = 6)).requeryTransactions)
    }

    @Test
    fun recovery_flip_re_queries_even_with_same_version() {
        val g1 = PollGate.INITIAL.reduce(snap(version = 5, recovering = true)).next
        assertTrue(g1.reduce(snap(version = 5, recovering = false)).requeryTransactions)
    }

    @Test
    fun gate_remembers_both_version_and_scope() {
        val g = PollGate.INITIAL.reduce(snap(version = 9, recovering = true)).next
        assertFalse(g.reduce(snap(version = 9, recovering = true)).requeryTransactions)
    }
}
