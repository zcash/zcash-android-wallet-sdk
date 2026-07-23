package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.Synchronizer.Status
import cash.z.ecc.android.sdk.model.AccountUuid
import com.zodl.slipstream.model.SlipstreamAccountBalance
import com.zodl.slipstream.model.SlipstreamPoolBalance
import com.zodl.slipstream.model.SlipstreamWalletSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineMappingTest {
    @Test
    fun engine_states_map_to_the_four_steady_statuses() {
        assertEquals(Status.DISCONNECTED, mapEngineState(0)) // idle while running (section 2.3)
        assertEquals(Status.SYNCING, mapEngineState(1))
        assertEquals(Status.DISCONNECTED, mapEngineState(2)) // error => "offer retry" (their enum has no ERROR)
        assertEquals(Status.SYNCED, mapEngineState(3))
    }

    @Test
    fun unknown_engine_state_degrades_to_disconnected_never_throws() = assertEquals(Status.DISCONNECTED, mapEngineState(99))

    @Test
    fun permille_endpoints_and_midpoint() {
        assertEquals(0.0f, permilleToPercentDecimal(0).decimal)
        assertEquals(0.5f, permilleToPercentDecimal(500).decimal)
        assertEquals(1.0f, permilleToPercentDecimal(1000).decimal)
    }

    @Test
    fun out_of_contract_permille_clamps_instead_of_crashing() {
        assertEquals(1.0f, permilleToPercentDecimal(1001).decimal)
        assertEquals(0.0f, permilleToPercentDecimal(-1).decimal)
    }

    @Test
    fun tip_stale_and_not_recovering_masks_shielded_spendable_into_pending() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(0L, balance.sapling.available.value)
        assertEquals(10L, balance.sapling.changePending.value)
        assertEquals(105L, balance.sapling.valuePending.value) // 5 (pending) + 100 (masked spendable)
    }

    @Test
    fun tip_fresh_never_masks() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5))

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(100L, balance.sapling.available.value)
        assertEquals(10L, balance.sapling.changePending.value)
        assertEquals(5L, balance.sapling.valuePending.value)
    }

    @Test
    fun recovery_never_masks_even_when_tip_is_stale() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(100, 10, 5))

        val balances = summary.toAccountBalances(isRecovering = true, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(100L, balance.sapling.available.value)
    }

    @Test
    fun unshielded_is_never_masked() {
        val uuid = ByteArray(16) { it.toByte() }
        val summary = summaryWith(uuid, sapling = SlipstreamPoolBalance(0, 0, 0), unshielded = 42)

        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = false)
        val balance = balances.getValue(AccountUuid.new(uuid))

        assertEquals(42L, balance.unshielded.value)
    }

    @Test
    fun multiple_accounts_all_map() {
        val uuidA = ByteArray(16) { 1 }
        val uuidB = ByteArray(16) { 2 }
        val summary =
            SlipstreamWalletSummary(
                accountBalances =
                    arrayOf(
                        accountBalance(uuidA, SlipstreamPoolBalance(1, 0, 0), SlipstreamPoolBalance(0, 0, 0), 0),
                        accountBalance(uuidB, SlipstreamPoolBalance(0, 0, 0), SlipstreamPoolBalance(2, 0, 0), 0)
                    ),
                chainTipHeight = 0,
                fullyScannedHeight = 0,
                scanProgress = null,
                recoveryProgress = null,
                nextSaplingSubtreeIndex = 0,
                nextOrchardSubtreeIndex = 0,
                nextIronwoodSubtreeIndex = null
            )
        val balances = summary.toAccountBalances(isRecovering = false, tipFresh = true)
        assertTrue(balances.containsKey(AccountUuid.new(uuidA)))
        assertTrue(balances.containsKey(AccountUuid.new(uuidB)))
    }

    private fun accountBalance(
        uuid: ByteArray,
        sapling: SlipstreamPoolBalance,
        orchard: SlipstreamPoolBalance,
        unshielded: Long
    ) = SlipstreamAccountBalance(uuid, sapling, orchard, ironwood = null, unshielded = unshielded)

    private fun summaryWith(
        uuid: ByteArray,
        sapling: SlipstreamPoolBalance,
        orchard: SlipstreamPoolBalance = SlipstreamPoolBalance(0, 0, 0),
        unshielded: Long = 0
    ) = SlipstreamWalletSummary(
        accountBalances = arrayOf(accountBalance(uuid, sapling, orchard, unshielded)),
        chainTipHeight = 0,
        fullyScannedHeight = 0,
        scanProgress = null,
        recoveryProgress = null,
        nextSaplingSubtreeIndex = 0,
        nextOrchardSubtreeIndex = 0,
        nextIronwoodSubtreeIndex = null
    )
}
