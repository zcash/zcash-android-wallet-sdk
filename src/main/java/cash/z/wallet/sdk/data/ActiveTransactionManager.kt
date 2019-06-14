package cash.z.wallet.sdk.data

import cash.z.wallet.sdk.dao.WalletTransaction
import cash.z.wallet.sdk.ext.masked
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.*
import kotlin.coroutines.CoroutineContext
import cash.z.wallet.sdk.data.TransactionState.*
//import cash.z.wallet.sdk.rpc.CompactFormats
import cash.z.wallet.sdk.service.LightWalletService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Manages active send/receive transactions. These are transactions that have been initiated but not completed with
 * sufficient confirmations. All other transactions are stored in a separate [TransactionRepository].
 */
class ActiveTransactionManager(
    private val repository: TransactionRepository,
    private val service: LightWalletService,
    private val wallet: Wallet
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + job
    private lateinit var sentTransactionMonitorJob: Job
//    private lateinit var confirmationMonitorJob: Job

    // mutableMapOf gives the same result but we're explicit about preserving insertion order, since we rely on that
    private val activeTransactions = LinkedHashMap<ActiveTransaction, TransactionState>()
    private val channel = ConflatedBroadcastChannel<Map<ActiveTransaction, TransactionState>>()
    private val transactionSubscription = repository.allTransactions()
//    private val latestHeightSubscription = service.latestHeights()

    fun subscribe(): ReceiveChannel<Map<ActiveTransaction, TransactionState>> {
        return channel.openSubscription()
    }

    fun start() {
        twig("ActiveTransactionManager starting")
        sentTransactionMonitorJob = launchSentTransactionMonitor()
//        confirmationMonitorJob = launchConfirmationMonitor() <- monitoring received transactions is disabled, presently <- TODO: enable confirmation monitor
    }

    fun stop() {
        twig("ActiveTransactionManager stopping")
        channel.cancel()
        job.cancel()
        sentTransactionMonitorJob.cancel()
        transactionSubscription.cancel()
//        confirmationMonitorJob.cancel() <- TODO: enable confirmation monitor
    }

    //
    // State API
    //

    fun create(zatoshi: Long, toAddress: String): ActiveSendTransaction {
        return ActiveSendTransaction(value = zatoshi, toAddress = toAddress).let { setState(it, TransactionState.Creating); it }
    }

    fun failure(transaction: ActiveTransaction, reason: String) {
        setState(transaction, TransactionState.Failure(activeTransactions[transaction], reason))
    }

    fun created(transaction: ActiveSendTransaction, transactionId: Long) {
        transaction.transactionId.set(transactionId)
        setState(transaction, TransactionState.Created(transactionId))
    }

    fun upload(transaction: ActiveSendTransaction) {
        setState(transaction, TransactionState.SendingToNetwork)
    }

    /**
     * Request a cancel for this transaction. Once a transaction has been submitted it cannot be cancelled.
     *
     * @param transaction the send transaction to cancel
     *
     * @return true when the transaction can be cancelled. False when it is already in flight to the network.
     */
    fun cancel(transaction: ActiveSendTransaction): Boolean {
        val currentState = activeTransactions[transaction]
        return if (currentState != null && currentState.order < TransactionState.SendingToNetwork.order) {
            setState(transaction, TransactionState.Cancelled)
            true
        } else {
            false
        }
    }

    fun awaitConfirmation(transaction: ActiveTransaction, confirmationCount: Int = 0) {
        setState(transaction, TransactionState.AwaitingConfirmations(confirmationCount))
    }

    fun isCancelled(transaction: ActiveSendTransaction): Boolean {
        return activeTransactions[transaction] == TransactionState.Cancelled
    }

    /**
     * Sets the state for this transaction and sends an update to subscribers on the main thread. The given transaction
     * will be added if it does not match any existing transactions. If the given transaction was previously cancelled,
     * this method takes no action.
     *
     * @param transaction the transaction to update and manage
     * @param state the state to set for the given transaction
     */
    private fun setState(transaction: ActiveTransaction, state: TransactionState) {
        if (transaction is ActiveSendTransaction && isCancelled(transaction)) {
            twig("state change to $state ignored because this send transaction has been cancelled")
        } else {
            twig("state set to $state for active transaction $transaction on thread ${Thread.currentThread().name}")
            activeTransactions[transaction] = state
            launch {
                channel.send(activeTransactions)
            }
        }
    }

    private fun CoroutineScope.launchSentTransactionMonitor() = launch {
        withContext(Dispatchers.IO) {
            while(isActive && !transactionSubscription.isClosedForReceive) {
                twig("awaiting next modification to transactions...")
                val transactions = transactionSubscription.receive()
                updateSentTransactions(transactions)
            }
        }
    }

//TODO: enable confirmation monitor
//    private fun CoroutineScope.launchConfirmationMonitor() = launch {
//        withContext(Dispatchers.IO) {
//            for (block in blockSubscription) {
//                updateConfirmations(block)
//            }
//        }
//    }

    /**
     * Synchronize our internal list of transactions to match any modifications that have occurred in the database.
     *
     * @param transactions the latest transactions received from our subscription to the transaction repository. That
     * channel only publishes transactions when they have changed in some way.
     */
    private fun updateSentTransactions(transactions: List<WalletTransaction>) {
        twig("transaction modification received! Updating active sent transactions based on new transaction list")
        val sentTransactions = transactions.filter { it.isSend }
        val activeSentTransactions =
            activeTransactions.entries.filter { (it.key is ActiveSendTransaction) &&  it.value.isActive() }
        if(sentTransactions.isEmpty() || activeSentTransactions.isEmpty()) {
            twig("done updating because the new transaction list" +
                    " ${if(sentTransactions.isEmpty()) "did not have any" else "had"} transactions and the active" +
                    " sent transactions is ${if(activeSentTransactions.isEmpty()) "" else "not"} empty.")
            return
        }

        /* for all our active send transactions, see if there is a match in the DB and if so, update the height accordingly */
        activeSentTransactions.forEach { (transaction, _) ->
            val tx = transaction as ActiveSendTransaction
            val transactionId = tx.transactionId.get()

            if (tx.height.get() < 0) {
                twig("checking whether active transaction $transactionId has been mined")
                val matchingDbTransaction = sentTransactions.find { it.txId == transactionId }
                if (matchingDbTransaction?.height != null) {
                    twig("transaction $transactionId HAS BEEN MINED!!! updating the corresponding active transaction.")
                    tx.height.set(matchingDbTransaction.height)
                    twig("active transaction height updated to ${matchingDbTransaction.height} and state updated to AwaitingConfirmations(0)")
                    setState(transaction, AwaitingConfirmations(1))
                } else {
                    twig("transaction $transactionId has still not been mined.")
                }
            }
        }
    }

// TODO: enable confirmation monitor
//    private fun updateConfirmations(block: CompactFormats.CompactBlock) {
//        twig("updating confirmations for all active transactions")
//        val txsAwaitingConfirmation =
//            activeTransactions.entries.filter { it.value is AwaitingConfirmations }
//        for (tx in txsAwaitingConfirmation) {
//
//        }
//    }


    //
    // Active Transaction Management
    //

    suspend fun sendToAddress(zatoshi: Long, toAddress: String, memo: String = "", fromAccountId: Int = 0) = withContext(Dispatchers.IO) {
        twig("creating send transaction for zatoshi value $zatoshi")
        val activeSendTransaction = create(zatoshi, toAddress.masked())
        val transactionId: Long = try {
            // this call takes up to 20 seconds
            wallet.createRawSendTransaction(zatoshi, toAddress, memo, fromAccountId)
        } catch (t: Throwable) {
            val reason = "${t.message}"
            twig("Failed to create transaction due to: $reason")
            failure(activeSendTransaction, reason)
            return@withContext
        }

        // cancellation basically just prevents sending to the network but we cannot cancel after this moment
        // well, technically we could still allow cancellation in the split second between this line of code and the upload request but lets not complicate things
        if(isCancelled(activeSendTransaction)) {
            twig("transaction $transactionId will not be submitted because it has been cancelled")
            revertTransaction(transactionId)
            return@withContext
        }

        if (transactionId < 0) {
            failure(activeSendTransaction, "Failed to create, for unknown reason")
            return@withContext
        }
        val transactionRaw: ByteArray? = repository.findTransactionById(transactionId)?.raw
        if (transactionRaw == null) {
            failure(activeSendTransaction, "Failed to find the transaction that we just attempted to create in the dataDb")
            return@withContext
        }
        created(activeSendTransaction, transactionId)

        uploadRawTransaction(transactionId, activeSendTransaction, transactionRaw)
        //TODO: synchronously await confirmations by checking periodically inside a while loop until confirmations = 10
    }

    private suspend fun uploadRawTransaction(
        transactionId: Long,
        activeSendTransaction: ActiveSendTransaction,
        transactionRaw: ByteArray
    ) {
        try {
            twig("attempting to submit transaction $transactionId")
            upload(activeSendTransaction)
            val response = service.submitTransaction(transactionRaw)
            if (response.errorCode < 0) {
                twig("submit failed with error code: ${response.errorCode} and message ${response.errorMessage}")
                failure(activeSendTransaction, "Send failed due to ${response.errorMessage}")
            } else {
                twig("successfully submitted. error code: ${response.errorCode}")
                awaitConfirmation(activeSendTransaction)
            }
        } catch (t: Throwable) {
            val logMessage = "submit failed due to $t."
            twig(logMessage)
            val revertMessage = revertTransaction(transactionId)
            failure(activeSendTransaction, "$logMessage  $revertMessage Failure caused by: ${t.message}")
        }
    }

    private suspend fun revertTransaction(transactionId: Long): String = withContext(Dispatchers.IO) {
        var revertMessage = "Failed to revert pending send id $transactionId in the dataDb."
        try {
            repository.deleteTransactionById(transactionId)
            revertMessage = "The pending send with id $transactionId has been removed from the DB."
        } catch (t: Throwable) {
        }
        revertMessage
    }

}

data class ActiveSendTransaction(
    /** height where the transaction was minded. -1 when unmined */
    val height: AtomicInteger = AtomicInteger(-1),
    /** Transaction row that corresponds with this send. -1 when the transaction hasn't been created yet. */
    val transactionId: AtomicLong = AtomicLong(-1L),
    override val value: Long = 0,
    override val internalId: UUID = UUID.randomUUID(),
    val toAddress: String = ""
) : ActiveTransaction

data class ActiveReceiveTransaction(
    val height: Int = -1,
    override val value: Long = 0,
    override val internalId: UUID = UUID.randomUUID()
) : ActiveTransaction

interface ActiveTransaction {
    val value: Long
    /** only used by this class for purposes of managing unique transactions */
    val internalId: UUID
}

sealed class TransactionState(val order: Int) {
    val timestamp: Long = System.currentTimeMillis()

    object Creating : TransactionState(0)

    /** @param txId row in the database where the raw transaction has been stored, temporarily, by the rust lib */
    class Created(val txId: Long) : TransactionState(10)

    object SendingToNetwork : TransactionState(20)

    class AwaitingConfirmations(val confirmationCount: Int) : TransactionState(30) {
        override fun toString(): String {
            return "${super.toString()}($confirmationCount)"
        }
    }

    object Cancelled : TransactionState(-1)
    /** @param failedStep the state of this transaction at the time, prior to failure */
    class Failure(val failedStep: TransactionState?, val reason: String = "") : TransactionState(-2) {
        override fun toString(): String {
            return "${super.toString()}($failedStep) : $reason"
        }
    }

    fun isActive(): Boolean {
        return order > 0
    }

    override fun toString(): String {
        return javaClass.simpleName
    }
}
