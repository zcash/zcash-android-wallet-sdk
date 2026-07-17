package com.zodl.slipstream.internal.spend

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class ResubmissionTickerTest {
    @Test
    fun does_nothing_before_the_tick_threshold() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = { findCalls++; emptyList() },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, findCalls)
        }

    @Test
    fun scans_exactly_every_resubmit_every_ticks_while_synced() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = { findCalls++; emptyList() },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(1, findCalls)
        }

    @Test
    fun not_synced_resets_the_counter_instead_of_scanning() =
        runBlocking {
            var findCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = { findCalls++; emptyList() },
                    resubmit = {},
                    notifyTxChange = {}
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            ticker.onTick(isSynced = false, chainTip = 100L)
            repeat(ResubmissionTicker.RESUBMIT_EVERY - 1) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, findCalls)
        }

    @Test
    fun resubmits_every_candidate_and_pokes_exactly_once() =
        runBlocking {
            val resubmitted = mutableListOf<ByteArray>()
            var notifyCalls = 0
            val candidates = listOf(ResubmissionCandidate(txId = byteArrayOf(1), raw = byteArrayOf(9)), ResubmissionCandidate(txId = byteArrayOf(2), raw = byteArrayOf(8)))
            val ticker =
                ResubmissionTicker(
                    findCandidates = { candidates },
                    resubmit = { resubmitted.add(it.txId) },
                    notifyTxChange = { notifyCalls++ }
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(2, resubmitted.size)
            assertEquals(1, notifyCalls)
        }

    @Test
    fun no_candidates_means_no_poke() =
        runBlocking {
            var notifyCalls = 0
            val ticker =
                ResubmissionTicker(
                    findCandidates = { emptyList() },
                    resubmit = {},
                    notifyTxChange = { notifyCalls++ }
                )
            repeat(ResubmissionTicker.RESUBMIT_EVERY) { ticker.onTick(isSynced = true, chainTip = 100L) }
            assertEquals(0, notifyCalls)
        }
}
