package cash.z.wallet.sdk.data

import cash.z.wallet.sdk.dao.WalletTransaction
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Primary interface for interacting with the SDK. Defines the contract that specific implementations like
 * [MockSynchronizer] and [SdkSynchronizer] fulfill. Given the language-level support for coroutines, we favor their use
 * in the SDK and incorporate that choice into this contract.
 */
interface Synchronizer {

    /* Lifecycle */
    /**
     * Starts this synchronizer within the given scope.
     *
     * @param parentScope the scope to use for this synchronizer, typically something with a lifecycle such as an
     * Activity. Implementations should leverage structured concurrency and cancel all jobs when this scope completes.
     */
    fun start(parentScope: CoroutineScope): Synchronizer

    /**
     * Stop this synchronizer.
     */
    fun stop()


    /* Channels */
    // NOTE: each of these are expected to be a broadcast channel, such that [receive] always returns the latest value

    /**
     * A stream of all the active transactions.
     */
    fun activeTransactions(): ReceiveChannel<Map<ActiveTransaction, TransactionState>>

    /**
     * A stream of all the wallet transactions.
     */
    fun allTransactions(): ReceiveChannel<List<WalletTransaction>>

    /**
     * A stream of balance values.
     */
    fun balances(): ReceiveChannel<Wallet.WalletBalance>

    /**
     * A stream of progress values, typically corresponding to this Synchronizer downloading blocks. Typically, any non-
     * zero value below 100 indicates that progress indicators can be shown and a value of 100 signals that progress is
     * complete and any progress indicators can be hidden.
     */
    fun progress(): ReceiveChannel<Int>


    /* Status */

    /**
     * A flag to indicate that this Synchronizer is significantly out of sync with it's server. Typically, this means
     * that the balance and other data cannot be completely trusted because a significant amount of data has not been
     * processed. This is intended for showing progress indicators when the user returns to the app after having not
     * used it for days. Typically, this means minor sync issues should be ignored and this should be leveraged in order
     * to alert a user that the balance information is stale.
     *
     * @return true when the local data is significantly out of sync with the remote server and the app data is stale.
     */
    suspend fun isStale(): Boolean

    /**
     * Gets or sets a global error listener. This is a useful hook for handling unexpected critical errors.
     *
     * @return true when the error has been handled and the Synchronizer should continue. False when the error is
     * unrecoverable and the Synchronizer should [stop].
     */
    var onSynchronizerErrorListener: ((Throwable?) -> Boolean)?


    /* Operations */

    /**
     * Gets the address for the given account.
     *
     * @param accountId the optional accountId whose address is of interest. By default, the first account is used.
     */
    fun getAddress(accountId: Int = 0): String

    /**
     * Gets the balance info for the given account. In most cases, the stream of balances provided by [balances]
     * should be used instead of this function.
     *
     * @param accountId the optional accountId whose balance is of interest. By default, the first account is used.
     * @return a wrapper around the available and total balances.
     */
    suspend fun getBalance(accountId: Int = 0): Wallet.WalletBalance

    /**
     * Sends zatoshi.
     *
     * @param zatoshi the amount of zatoshi to send.
     * @param toAddress the recipient's address.
     * @param memo the optional memo to include as part of the transaction.
     * @param fromAccountId the optional account id to use. By default, the first account is used.
     */
    suspend fun sendToAddress(zatoshi: Long, toAddress: String, memo: String = "", fromAccountId: Int = 0)

    /**
     * Attempts to cancel a previously sent transaction. Typically, cancellation is only an option if the transaction
     * has not yet been submitted to the server.
     *
     * @param transaction the transaction to cancel.
     * @return true when the cancellation request was successful. False when it is too late to cancel.
     */
    fun cancelSend(transaction: ActiveSendTransaction): Boolean
}