package cash.z.wallet.sdk.data

import cash.z.wallet.sdk.block.CompactBlockProcessor
import cash.z.wallet.sdk.dao.WalletTransaction
import cash.z.wallet.sdk.exception.SynchronizerException
import cash.z.wallet.sdk.exception.WalletException
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.distinct
import kotlin.coroutines.CoroutineContext

/**
 * The glue. Downloads compact blocks to the database and then scans them for transactions. In order to serve that
 * purpose, this class glues together a variety of key components. Each component contributes to the team effort of
 * providing a simple source of truth to interact with.
 *
 * Another way of thinking about this class is the reference that demonstrates how all the pieces can be tied
 * together.
 *
 * @param processor the component that saves the downloaded compact blocks to the cache and then scans those blocks for
 * data related to this wallet.
 * @param repository the component that exposes streams of wallet transaction information.
 * @param activeTransactionManager the component that manages the lifecycle of active transactions. This includes sent
 * transactions that have not been mined.
 * @param wallet the component that wraps the JNI layer that interacts with librustzcash and manages wallet config.
 * @param batchSize the number of compact blocks to download at a time.
 * @param staleTolerance the number of blocks to allow before considering our data to be stale
 * @param blockPollFrequency how often to poll for compact blocks. Once all missing blocks have been downloaded, this
 * number represents the number of milliseconds the synchronizer will wait before checking for newly mined blocks.
 */
class SdkSynchronizer(
    private val processor: CompactBlockProcessor,
    private val repository: PollingTransactionRepository,
    private val activeTransactionManager: ActiveTransactionManager,
    private val wallet: Wallet,
    private val staleTolerance: Int = 10
) : Synchronizer {

    /**
     * The primary job for this Synchronizer. It leverages structured concurrency to cancel all work when the
     * `parentScope` provided to the [start] method ends.
     */
    private lateinit var blockJob: Job

    /**
     * The state this Synchronizer was in when it started. This is helpful because the conditions that lead to FirstRun
     * or isStale being detected can change quickly so retaining the initial state is useful for walkthroughs or other
     * elements of an app that need to rely on this information later, rather than in realtime.
     */
    private lateinit var initialState: SyncState

    /**
     * Returns true when `start` has been called on this synchronizer.
     */
    private val wasPreviouslyStarted
        get() = ::blockJob.isInitialized

    /**
     * Retains the error that caused this synchronizer to fail for future error handling or reporting.
     */
    private var failure: Throwable? = null

    /**
     * The default exception handler for the block job. Calls [onException].
     */
    private val exceptionHandler: (c: CoroutineContext, t: Throwable) -> Unit = { _, t -> onException(t) }

    /**
     * Sets a listener to be notified of uncaught Synchronizer errors. When null, errors will only be logged.
     */
    override var onSynchronizerErrorListener: ((Throwable?) -> Boolean)? = null
        set(value) {
            field = value
            if (failure != null) value?.invoke(failure)
        }

    /**
     * Channel of transactions from the repository.
     */
    private val transactionChannel = ConflatedBroadcastChannel<List<WalletTransaction>>()

    /**
     * Channel of balance information.
     */
    private val balanceChannel = ConflatedBroadcastChannel<Wallet.WalletBalance>()

    //
    // Public API
    //

    /* Lifecycle */

    /**
     * Starts this synchronizer within the given scope. For simplicity, attempting to start an instance that has already
     * been started will throw a [SynchronizerException.FalseStart] exception. This reduces the complexity of managing
     * resources that must be recycled. Instead, each synchronizer is designed to have a long lifespan and should be
     * started from an activity, application or session.
     *
     * @param parentScope the scope to use for this synchronizer, typically something with a lifecycle such as an
     * Activity for single-activity apps or a logged in user session. This scope is only used for launching this
     * synchronzer's job as a child.
     */
    override fun start(parentScope: CoroutineScope): Synchronizer {
        //  prevent restarts so the behavior of this class is easier to reason about
        if (wasPreviouslyStarted) throw SynchronizerException.FalseStart
        twig("starting")
        failure = null
        blockJob = parentScope.launch(CoroutineExceptionHandler(exceptionHandler)) {
            supervisorScope {
                try {
                    wallet.initialize()
                } catch (e: WalletException.AlreadyInitializedException) {
                    twig("Warning: wallet already initialized but this is safe to ignore " +
                            "because the SDK now automatically detects where to start downloading.")
                }
                onReady()
            }
        }
        return this
    }

    /**
     * Stops this synchronizer by stopping the downloader, repository, and activeTransactionManager, then cancelling the
     * parent job. Note that we do not cancel the parent scope that was passed into [start] because the synchronizer
     * does not own that scope, it just uses it for launching children.
     */
    override fun stop() {
        twig("stopping")
        (repository as? PollingTransactionRepository)?.stop().also { twig("repository stopped") }
        activeTransactionManager.stop().also { twig("activeTransactionManager stopped") }
        // TODO: investigate whether this is necessary and remove or improve, accordingly
        Thread.sleep(5000L)
        blockJob.cancel().also { twig("blockJob cancelled") }
    }


    /* Channels */

    /**
     * A stream of all the wallet transactions, delegated to the [activeTransactionManager].
     */
    override fun activeTransactions() = activeTransactionManager.subscribe()

    /**
     * A stream of all the wallet transactions, delegated to the [repository].
     */
    override fun allTransactions(): ReceiveChannel<List<WalletTransaction>> {
        return transactionChannel.openSubscription()
    }

    /**
     * A stream of progress values, corresponding to this Synchronizer downloading blocks, delegated to the
     * [downloader]. Any non-zero value below 100 indicates that progress indicators can be shown and a value of 100
     * signals that progress is complete and any progress indicators can be hidden. At that point, the synchronizer
     * switches from catching up on missed blocks to periodically monitoring for newly mined blocks.
     */
    override fun progress(): ReceiveChannel<Int> {
        return processor.progress()
    }

    /**
     * A stream of balance values, delegated to the [wallet].
     */
    override fun balances(): ReceiveChannel<Wallet.WalletBalance> {
        return balanceChannel.openSubscription()
    }


    /* Status */

    /**
     * A flag to indicate that this Synchronizer is significantly out of sync with it's server. This is determined by
     * the delta between the current block height reported by the server and the latest block we have stored in cache.
     * Whenever this delta is greater than the [staleTolerance], this function returns true. This is intended for
     * showing progress indicators when the user returns to the app after having not used it for a long period.
     * Typically, this means the user may have to wait for downloading to occur and the current balance and transaction
     * information cannot be trusted as 100% accurate.
     *
     * @return true when the local data is significantly out of sync with the remote server and the app data is stale.
     */
    override suspend fun isStale(): Boolean = withContext(IO) {
        val latestBlockHeight = processor.downloader.getLatestBlockHeight()
        val ourHeight = processor.downloader.getLastDownloadedHeight()
        val tolerance = staleTolerance
        val delta = latestBlockHeight - ourHeight
        twig("checking whether out of sync. " +
                "LatestHeight: $latestBlockHeight  ourHeight: $ourHeight  Delta: $delta   tolerance: $tolerance")
        delta > tolerance
    }

    /* Operations */

    /**
     * Gets the address for the given account.
     *
     * @param accountId the optional accountId whose address of interest. Typically, this value is zero.
     */
    override fun getAddress(accountId: Int): String = wallet.getAddress()

    override suspend fun getBalance(accountId: Int): Wallet.WalletBalance = wallet.getBalanceInfo(accountId)

    /**
     * Sends zatoshi.
     *
     * @param zatoshi the amount of zatoshi to send.
     * @param toAddress the recipient's address.
     * @param memo the optional memo to include as part of the transaction.
     * @param fromAccountId the optional account id to use. By default, the first account is used.
     */
    override suspend fun sendToAddress(zatoshi: Long, toAddress: String, memo: String, fromAccountId: Int) =
        activeTransactionManager.sendToAddress(zatoshi, toAddress, memo, fromAccountId)

    /**
     * Attempts to cancel a previously sent transaction. Transactions can only be cancelled during the calculation phase
     * before they've been submitted to the server. This method will return false when it is too late to cancel. This
     * logic is delegated to the activeTransactionManager, which knows the state of the given transaction.
     *
     * @param transaction the transaction to cancel.
     * @return true when the cancellation request was successful. False when it is too late to cancel.
     */
    override fun cancelSend(transaction: ActiveSendTransaction): Boolean = activeTransactionManager.cancel(transaction)


    //
    // Private API
    //


    /**
     * Logic for starting the Synchronizer once it is ready for processing. All starts eventually end with this method.
     */
    private fun CoroutineScope.onReady() = launch {
        twig("synchronization is ready to begin!")
        launch { monitorTransactions(transactionChannel.openSubscription().distinct()) }

        activeTransactionManager.start()
        repository.poll(transactionChannel)
        processor.start()
    }

    /**
     * Monitors transactions and recalculates the balance any time transactions have changed.
     */
    private suspend fun monitorTransactions(transactionChannel: ReceiveChannel<List<WalletTransaction>>) =
        withContext(IO) {
            twig("beginning to monitor transactions in order to update the balance")
            launch {
                for (i in transactionChannel) {
                    twig("triggering a balance update because transactions have changed")
                    balanceChannel.send(wallet.getBalanceInfo())
                    twig("done triggering balance check!")
                }
            }
            twig("done monitoring transactions in order to update the balance")
        }

    /**
     * Wraps exceptions, logs them and then invokes the [onSynchronizerErrorListener], if it exists.
     */
    private fun onException(throwable: Throwable) {
        twig("********")
        twig("********  ERROR: $throwable")
        if (throwable.cause != null) twig("******** caused by ${throwable.cause}")
        if (throwable.cause?.cause != null) twig("******** caused by ${throwable.cause?.cause}")
        twig("********")

        val hasRecovered = onSynchronizerErrorListener?.invoke(throwable)
        if (hasRecovered != true) stop().also { failure = throwable }
    }

    /**
     * Represents the initial state of the Synchronizer.
     */
    sealed class SyncState {
        /**
         * State for the first run of the Synchronizer, when the database has not been initialized.
         */
        object FirstRun : SyncState()

        /**
         * State for when compact blocks have been downloaded but not scanned. This state is typically achieved when the
         * app was previously started but killed before the first scan took place. In this case, we do not need to
         * download compact blocks that we already have.
         *
         * @param startingBlockHeight the last block that has been downloaded into the cache. We do not need to download
         * any blocks before this height because we already have them.
         */
        class CacheOnly(val startingBlockHeight: Int = Int.MAX_VALUE) : SyncState()

        /**
         * The final state of the Synchronizer, when all initialization is complete and the starting block is known.
         *
         * @param startingBlockHeight the height that will be fed to the downloader. In most cases, it will represent
         * either the wallet birthday or the last block that was processed in the previous session.
         */
        class ReadyToProcess(val startingBlockHeight: Int = Int.MAX_VALUE) : SyncState()
    }

}