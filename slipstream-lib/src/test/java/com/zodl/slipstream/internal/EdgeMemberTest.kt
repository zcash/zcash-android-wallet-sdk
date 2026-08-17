package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import com.zodl.slipstream.SlipstreamSyncException
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T10's pinning test: the D5-critical path formula (R57/`getWalletDbPathForVoting`/C3 `erase`), the
 * `processorInfo` degraded-null-fields contract (R5), and the error-episode transition table
 * (R63/R64, `KOTLIN_ROSETTA.md` section 2.3: enter/stay/leave/panic).
 */
class EdgeMemberTest {
    @Test
    fun data_db_path_matches_the_formula_exactly() {
        val root = File("/no_backup")
        val dbFile = DataDbPath.dataDbFile(root, alias = "zcash_sdk", network = ZcashNetwork.Mainnet)
        assertEquals("zcash_sdk_mainnet_data.sqlite3", dbFile.name)
        assertEquals(File(root, "co.electricoin.zcash").path, dbFile.parentFile?.path)
    }

    /**
     * "custom_" already ends with `_`, so `aliasPrefix` stays "custom_" verbatim (no double
     * underscore); "custom" gets exactly one `_` appended - both resolve to the identical file name.
     */
    @Test
    fun data_db_path_does_not_double_up_a_trailing_alias_underscore() {
        val root = File("/no_backup")
        assertEquals(
            "custom_testnet_data.sqlite3",
            DataDbPath.dataDbFile(root, alias = "custom_", network = ZcashNetwork.Testnet).name
        )
        assertEquals(
            "custom_testnet_data.sqlite3",
            DataDbPath.dataDbFile(root, alias = "custom", network = ZcashNetwork.Testnet).name
        )
    }

    @Test
    fun processor_info_carries_the_real_network_height_and_documented_nulls() {
        val info = toProcessorInfo(BlockHeight.new(123_456L))
        assertEquals(BlockHeight.new(123_456L), info.networkBlockHeight)
        assertNull(info.overallSyncRange)
        assertNull(info.firstUnenhancedHeight)
    }

    @Test
    fun processor_info_survives_an_unknown_network_height() {
        val info = toProcessorInfo(null)
        assertNull(info.networkBlockHeight)
        assertNull(info.overallSyncRange)
        assertNull(info.firstUnenhancedHeight)
    }

    @Test
    fun error_episode_enters_once_on_first_state_two() {
        val (transition, next) = ErrorEpisodeGate.INITIAL.reduce(2)
        assertEquals(ErrorEpisodeTransition.ENTER, transition)
        assertEquals(true, next.active)
    }

    @Test
    fun error_episode_stays_on_repeated_state_two() {
        val active = ErrorEpisodeGate(active = true)
        val (transition, next) = active.reduce(2)
        assertEquals(ErrorEpisodeTransition.STAY, transition)
        assertEquals(true, next.active)
    }

    @Test
    fun error_episode_leaves_on_the_first_healthy_tick_after_an_episode() {
        val active = ErrorEpisodeGate(active = true)
        listOf(0, 1, 3).forEach { healthyState ->
            val (transition, next) = active.reduce(healthyState)
            assertEquals(ErrorEpisodeTransition.LEAVE, transition, "state=$healthyState")
            assertEquals(false, next.active, "state=$healthyState")
        }
    }

    @Test
    fun error_episode_none_when_never_entered_and_state_is_healthy() {
        val (transition, next) = ErrorEpisodeGate.INITIAL.reduce(1)
        assertEquals(ErrorEpisodeTransition.NONE, transition)
        assertEquals(false, next.active)
    }

    @Test
    fun error_episode_panic_payload_is_the_chain_tip_not_a_code() {
        val exception = SlipstreamSyncException(chainTip = 987_654L)
        assertEquals(987_654L, exception.chainTip)
    }
}
