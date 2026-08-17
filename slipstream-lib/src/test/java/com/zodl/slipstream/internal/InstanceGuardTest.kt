package com.zodl.slipstream.internal

import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class InstanceGuardTest {
    @Test
    fun double_register_same_key_throws() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")
            InstanceGuard.acquire(key)
            try {
                assertFailsWith<IllegalStateException> { InstanceGuard.acquire(key) }
            } finally {
                InstanceGuard.release(key)
            }
        }
    }

    @Test
    fun register_then_close_then_register_succeeds() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")
            InstanceGuard.acquire(key)
            val job = CompletableDeferred<Unit>()
            InstanceGuard.markShuttingDown(key, GlobalScope.launch { job.await() })
            job.complete(Unit)
            InstanceGuard.release(key)

            InstanceGuard.acquire(key)
            InstanceGuard.release(key)
        }
    }

    @Test
    fun two_aliases_same_network_both_succeed() {
        runBlocking {
            val suffix = System.nanoTime()
            val keyA = SlipstreamKey(ZcashNetwork.Testnet, "alias_a_$suffix")
            val keyB = SlipstreamKey(ZcashNetwork.Testnet, "alias_b_$suffix")
            InstanceGuard.acquire(keyA)
            InstanceGuard.acquire(keyB)
            InstanceGuard.release(keyA)
            InstanceGuard.release(keyB)
        }
    }

    @Test
    fun is_active_reflects_registration_state() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Mainnet, "alias_${System.nanoTime()}")
            assertFalse(InstanceGuard.isActive(key))
            InstanceGuard.acquire(key)
            assertTrue(InstanceGuard.isActive(key))
            InstanceGuard.release(key)
            assertFalse(InstanceGuard.isActive(key))
        }
    }

    @Test
    fun with_key_inactive_runs_immediately_for_an_unknown_key() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")
            assertEquals("ran", InstanceGuard.withKeyInactive(key) { "ran" })
        }
    }

    @Test
    fun with_key_inactive_throws_while_active() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")
            InstanceGuard.acquire(key)
            try {
                assertFailsWith<IllegalStateException> {
                    InstanceGuard.withKeyInactive(key) { fail("block must not run while active") }
                }
            } finally {
                InstanceGuard.release(key)
            }
        }
    }

    /**
     * The teardown gate the reset path relies on: `close()` marks the key shutting down long before
     * the engine has let go of the database files, so the block must not run until that job ends.
     */
    @Test
    fun with_key_inactive_awaits_a_pending_shutdown() {
        runBlocking {
            val key = SlipstreamKey(ZcashNetwork.Testnet, "alias_${System.nanoTime()}")
            InstanceGuard.acquire(key)
            val shutdownGate = CompletableDeferred<Unit>()
            val shutdownFinished = AtomicBoolean(false)
            val shutdownJob =
                GlobalScope.launch {
                    shutdownGate.await()
                    shutdownFinished.set(true)
                }
            InstanceGuard.markShuttingDown(key, shutdownJob)
            shutdownJob.invokeOnCompletion { InstanceGuard.release(key) }

            val erase = GlobalScope.async { InstanceGuard.withKeyInactive(key) { shutdownFinished.get() } }
            assertTrue(erase.isActive)

            shutdownGate.complete(Unit)
            assertTrue(erase.await(), "the block ran before the shutdown job completed")
        }
    }
}
