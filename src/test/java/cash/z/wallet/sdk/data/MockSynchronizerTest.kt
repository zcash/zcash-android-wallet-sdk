package cash.z.wallet.sdk.data

import cash.z.wallet.sdk.dao.WalletTransaction
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.system.measureTimeMillis

internal class MockSynchronizerTest {

    private val transactionInterval = 200L
    private val activeTransactionInterval = 200L
    private val synchronizer = MockSynchronizer(transactionInterval, activeTransactionInterval)
    private val fastSynchronizer = MockSynchronizer(2L, 2L)
    private val allTransactionChannel = synchronizer.allTransactions()
    private val validAddress = "ztestsapling1yu2zy9aane2pje2qvm4qmn4k6q57y2d9ecs5vz0guthxx3m2aq57qm6hkx0520m9u9635xh6ttd"

    @BeforeEach
    fun setUp() {
        synchronizer.start(CoroutineScope(Dispatchers.IO))
    }

    @AfterEach
    fun tearDown() {
        synchronizer.stop()
    }

    @Test
    fun allTransactions() = runBlocking {
        var total = 0
        val duration = measureTimeMillis {
            repeat(10) {
                val transactions = allTransactionChannel.receive()
                total += transactions.size
                println("received ${transactions.size} transactions")
            }
        }
        assertTrue(total > 0)
        assertTrue(duration > transactionInterval)
    }

    @Test
    fun `never calling send yields zero sent transactions`() = runBlocking {
        val fastChannel = fastSynchronizer.start(fastSynchronizer).allTransactions()
        var transactions = fastChannel.receive()
        repeat(10_000) {
            transactions = fastChannel.receive()
        }
        assertTrue(transactions.size > 0, "no transactions created at all")
        assertTrue(transactions.none { it.isSend })
    }

    @Test
    fun `send - each call to send generates exactly one sent transaction`() = runBlocking {
        val fastChannel = fastSynchronizer.start(fastSynchronizer).allTransactions()
        var transactions = fastChannel.receive()
        repeat(10_000) {
            if (it.rem(2_000) == 0) {
                fastSynchronizer.sendToAddress(10, validAddress); println("yep")
            }
            transactions = fastChannel.receive()
        }
        assertEquals(5, transactions.count { it.isSend })
    }

    @Test
    fun `send - triggers an active transaction`() = runBlocking {
        synchronizer.sendToAddress(10, validAddress)
        delay(500L)
        assertNotNull(synchronizer.activeTransactions().receiveOrNull())
        synchronizer.stop()
    }

    @Test
    fun `send - results in success`() = runBlocking {
        synchronizer.sendToAddress(10, validAddress)
        delay(500L)
        val result = synchronizer.activeTransactions().receive()
        assertTrue(result.isNotEmpty(), "result was empty")
        assertTrue(TransactionState.AwaitingConfirmations(0).order <= result.values.first().order)
        assertTrue((result.keys.first() as ActiveSendTransaction).transactionId.get() != -1L, "transactionId missing")
        assertTrue((result.keys.first() as ActiveSendTransaction).height.get() != -1, "height missing")
        synchronizer.stop()
    }

    @Test
    fun `send - results in mined transaction`() = runBlocking {
        synchronizer.sendToAddress(10, validAddress)
        delay(500L)
        val result = synchronizer.activeTransactions().receive()
        assertTrue(result.isNotEmpty(), "result was empty")
        assertTrue(TransactionState.AwaitingConfirmations(0).order <= result.values.first().order)
        synchronizer.stop()
    }

    @Test
    fun `send - a bad address fails`() = runBlocking {
        synchronizer.sendToAddress(10, "fail")
        delay(500L)
        val result = synchronizer.activeTransactions().receive()
        assertTrue(result.isNotEmpty(), "result was empty")
        assertTrue(0 > result.values.first().order)
        synchronizer.stop()
    }

    @Test
    fun `send - a short address fails`() = runBlocking {
        // one character too short
        val toAddress = "ztestsapling1yu2zy9aane2pje2qvm4qmn4k6q57y2d9ecs5vz0guthxx3m2aq57qm6hkx0520m9u9635xh6tt"
        assertTrue(toAddress.length < 88, "sample address wasn't short enough (${toAddress.length})")

        synchronizer.sendToAddress(10, toAddress)
        delay(500L)
        val result = synchronizer.activeTransactions().receive()
        assertTrue(result.isNotEmpty(), "result was empty")
        assertTrue(0 > result.values.first().order,
            "result should have been a failure but was ${result.values.first()::class.simpleName}")
    }

    @Test
    fun `send - a non-z prefix address fails`() = runBlocking {
        // one character too short
        val toAddress = "atestsapling1yu2zy9aane2pje2qvm4qmn4k6q57y2d9ecs5vz0guthxx3m2aq57qm6hkx0520m9u9635xh6ttd"
        assertTrue(toAddress.length == 88,
            "sample address was not the proper length (${toAddress.length}")
        assertFalse(toAddress.startsWith('z'),
            "sample address should not start with z")

        synchronizer.sendToAddress(10, toAddress)
        delay(500L)
        val result = synchronizer.activeTransactions().receive()
        assertTrue(result.isNotEmpty(), "result was empty")
        assertTrue(0 > result.values.first().order,
            "result should have been a failure but was ${result.values.first()::class.simpleName}")
    }

    @Test
    fun `balance matches transactions without sends`() = runBlocking {
        val balances = fastSynchronizer.start(fastSynchronizer).balances()
        var transactions = listOf<WalletTransaction>()
        while (transactions.count() < 10) {
            transactions = fastSynchronizer.allTransactions().receive()
            println("got ${transactions.count()} transaction(s)")
        }
        assertEquals(transactions.fold(0L) { acc, tx -> acc + tx.value }, balances.receive())
    }

    @Test
    fun `balance matches transactions with sends`() = runBlocking {
        var transactions = listOf<WalletTransaction>()
        val balances = fastSynchronizer.start(fastSynchronizer).balances()
        val transactionChannel = fastSynchronizer.allTransactions()
        while (transactions.count() < 10) {
            fastSynchronizer.sendToAddress(Random.nextLong(1L..10_000_000_000), validAddress)
            transactions = transactionChannel.receive()
            println("got ${transactions.count()} transaction(s)")
        }
        val transactionsSnapshot = transactionChannel.receive()
        val balanceSnapshot = balances.receive()

        val positiveValue = transactionsSnapshot.fold(0L) { acc, tx -> acc + (if (tx.isSend) 0 else tx.value) }
        val negativeValue = transactionsSnapshot.fold(0L) { acc, tx -> acc + (if(!tx.isSend) 0 else tx.value) }
        assertEquals(positiveValue - negativeValue, balanceSnapshot, "incorrect balance. negative balance: $negativeValue  positive balance: $positiveValue")
    }

    @Test
    fun `progress hits 100`() = runBlocking {
        var channel = synchronizer.progress()
        var now = System.currentTimeMillis()
        var delta = 0L
        val expectedUpperBounds = transactionInterval * 10
        while (channel.receive() < 100) {
            delta = now - System.currentTimeMillis()
            if (delta > expectedUpperBounds) break
        }
        assertTrue(delta < expectedUpperBounds, "progress did not hit 100 within the expected time of $expectedUpperBounds")
    }

    @Test
    fun `is out of sync about 10% of the time`() = runBlocking {
        var count = 0
        repeat(100_000) {
            if (synchronizer.isStale()) count++
        }
        assertTrue(count < 11_000, "a count of $count is too frequent")
        assertTrue(count > 9_000, "a count of $count is too infrequent")
    }

    @Test
    fun isFirstRun() {
    }

    @Test
    fun cancelSend() = runBlocking {
        val activeTransactions = synchronizer.activeTransactions()

        // verify that send creates one transaction
        launch {
            synchronizer.sendToAddress(10, validAddress)
        }
        println("done sending to address")
        delay(300L)
        var actives = activeTransactions.receiveOrNull()
        assertEquals(1, actives?.size)
        assertTrue((actives?.values?.first()?.order ?: 0) > -1, "expected positive order but was ${actives?.values?.first()?.order}")
        val transaction = actives?.keys?.first() as? ActiveSendTransaction
        assertNotNull(transaction)

        // and then verify that cancel changes its status
        synchronizer.cancelSend(transaction!!)
        delay(100L) // look for ignored state change
        actives = activeTransactions.receiveOrNull()
        assertNotNull(actives, "cancel changed nothing in 100ms")
        assertEquals(1, actives!!.size, "unexpected number of active transactions ${actives.size}")
        val finalState = actives!!.values.first()
        assertNotNull(finalState as? TransactionState.Cancelled, "transaction was ${finalState::class.simpleName} instead of cancelled for ${actives.keys.first()}")
        println("donso")
        synchronizer.stop()
    }
}