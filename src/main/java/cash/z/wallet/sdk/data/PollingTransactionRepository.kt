package cash.z.wallet.sdk.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import cash.z.wallet.sdk.dao.BlockDao
import cash.z.wallet.sdk.dao.TransactionDao
import cash.z.wallet.sdk.dao.WalletTransaction
import cash.z.wallet.sdk.db.DerivedDataDb
import cash.z.wallet.sdk.entity.Transaction
import cash.z.wallet.sdk.exception.RepositoryException
import cash.z.wallet.sdk.jni.RustBackendWelding
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Repository that does polling for simplicity. We will implement an alternative version that uses live data as well as
 * one that creates triggers and then reference them here. For now this is the most basic example of keeping track of
 * changes.
 */
open class PollingTransactionRepository(
    private val dataDbPath: String,
    private val derivedDataDb: DerivedDataDb,
    private val rustBackend: RustBackendWelding,
    private val pollFrequencyMillis: Long = 2000L
) : TransactionRepository {

    /**
     * Constructor that creates the database and then executes a callback on it.
     */
    constructor(
        context: Context,
        dataDbName: String,
        rustBackend: RustBackendWelding,
        pollFrequencyMillis: Long = 2000L,
        dbCallback: (DerivedDataDb) -> Unit = {}
    ) : this(
        context.getDatabasePath(dataDbName).absolutePath,
        Room.databaseBuilder(context, DerivedDataDb::class.java, dataDbName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build(),
        rustBackend,
        pollFrequencyMillis
    ) {
        dbCallback(derivedDataDb)
    }

    internal val blocks: BlockDao = derivedDataDb.blockDao()
    private val transactions: TransactionDao = derivedDataDb.transactionDao()
    private lateinit var pollingJob: Job
    private val allTransactionsChannel = ConflatedBroadcastChannel<List<WalletTransaction>>()
    private val existingTransactions = listOf<WalletTransaction>()
    private val wasPreviouslyStarted
        get() = !existingTransactions.isEmpty() || allTransactionsChannel.isClosedForSend

    override fun start(parentScope: CoroutineScope) {
        //  prevent restarts so the behavior of this class is easier to reason about
        if (wasPreviouslyStarted) throw RepositoryException.FalseStart

        twig("starting")

        pollingJob = parentScope.launch {
            poll()
        }
    }

    override fun stop() {
        twig("stopping but doing nothing")
        pollingJob.cancel()
        derivedDataDb.close()
        // TODO: verify that the channels behave as expected in this scenario
    }

    override fun allTransactions(): ReceiveChannel<List<WalletTransaction>> {
        return allTransactionsChannel.openSubscription()
    }

    override fun lastScannedHeight(): Int {
        return blocks.lastScannedHeight()
    }

    override fun isInitialized(): Boolean {
        return blocks.count() > 0
    }

    override suspend fun findTransactionById(txId: Long): Transaction? = withContext(IO) {
        twig("finding transaction with id $txId on thread ${Thread.currentThread().name}")
        val transaction = transactions.findById(txId)
        twig("found ${transaction?.id}")
        transaction
    }

    override suspend fun deleteTransactionById(txId: Long) = withContext(IO) {
        twigTask("deleting transaction with id $txId") {
            transactions.deleteById(txId)
        }
    }

    private suspend fun poll() = withContext(IO) {
        var previousTransactions: List<WalletTransaction>? = null
        while (isActive && !allTransactionsChannel.isClosedForSend) {
            twigTask("polling for transactions every ${pollFrequencyMillis}ms") {
                val newTransactions = transactions.getAll()

                if (hasChanged(previousTransactions, newTransactions)) {
                    twig("loaded ${newTransactions.count()} transactions and changes were detected!")
                    allTransactionsChannel.send(addMemos(newTransactions))
                    previousTransactions = newTransactions
                } else {
                    twig("loaded ${newTransactions.count()} transactions but no changes detected.")
                }
            }
            delay(pollFrequencyMillis)
        }
        twig("Done polling for transactions")
    }

    private suspend fun addMemos(newTransactions: List<WalletTransaction>): List<WalletTransaction> = withContext(IO){
        for (tx in newTransactions) {
            if (tx.rawMemoExists) {
                tx.memo = if(tx.isSend) {
                    rustBackend.getSentMemoAsUtf8(dataDbPath, tx.noteId)
                } else {
                    rustBackend.getReceivedMemoAsUtf8(dataDbPath, tx.noteId)
                }
            }
        }
        newTransactions
    }


    private fun hasChanged(oldTxs: List<WalletTransaction>?, newTxs: List<WalletTransaction>): Boolean {
        fun pr(t: List<WalletTransaction>?): String {
            if(t == null) return "none"
            val str = StringBuilder()
            for (tx in t) {
                str.append("\n@TWIG: ").append(tx.toString())
            }
            return str.toString()
        }
        val sends = newTxs.filter { it.isSend }
        if(sends.isNotEmpty()) twig("SENDS hasChanged: old-txs: ${pr(oldTxs?.filter { it.isSend })}\n@TWIG: new-txs: ${pr(sends)}")

        // shortcuts first
        if (newTxs.isEmpty() && oldTxs == null) return false.also { twig("detected nothing happened yet") } // if nothing has happened, that doesn't count as a change
        if (oldTxs == null) return true.also { twig("detected first set of txs!") } // the first set of transactions is automatically a change
        if (oldTxs.size != newTxs.size) return true.also { twig("detected size difference") } // can't be the same and have different sizes, duh

        for (note in newTxs) {
            if (!oldTxs.contains(note)) return true.also { twig("detected change for $note") }
        }
        return false.also { twig("detected no changes in all new txs") }
    }

}