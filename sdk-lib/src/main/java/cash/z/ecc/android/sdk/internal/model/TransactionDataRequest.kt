package cash.z.ecc.android.sdk.internal.model

import cash.z.ecc.android.sdk.exception.SdkException
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.model.BlockHeight
import kotlin.time.Instant

/**
 * A request for transaction data enhancement, spentness check, or discovery
 * of spends from a given transparent address within a specific block range.
 */
sealed interface TransactionDataRequest {
    sealed class EnhancementRequired(
        open val txid: ByteArray
    ) : TransactionDataRequest {
        abstract fun txIdString(): String
    }

    /**
     * Information about the chain's view of a transaction is requested.
     *
     * The caller evaluating this request on behalf of the wallet backend should respond to this
     * request by determining the status of the specified transaction with respect to the main
     * chain; if using `lightwalletd` for access to chain data, this may be obtained by
     * interpreting the results of the `GetTransaction` RPC method. It should then call
     * `ZcashRustBackend.setTransactionStatus` to provide the resulting transaction status
     * information to the wallet backend.
     */
    data class GetStatus(
        override val txid: ByteArray
    ) : EnhancementRequired(txid) {
        override fun txIdString() = txid.toHexReversed()
    }

    /**
     * Transaction enhancement (download of complete raw transaction data) is requested.
     *
     * The caller evaluating this request on behalf of the wallet backend should respond to this
     * request by providing complete data for the specified transaction to
     * `ZcashRustBackend.decryptAndStoreTransaction`; if using `lightwalletd` for access to chain
     * state, this may be obtained via the `GetTransaction` RPC method. If no data is available
     * for the specified transaction, this should be reported to the backend using
     * `ZcashRustBackend.setTransactionStatus`. A `TransactionDataRequest.enhancement` request
     * subsumes any previously existing `TransactionDataRequest.getStatus` request.
     */
    data class Enhancement(
        override val txid: ByteArray
    ) : EnhancementRequired(txid) {
        override fun txIdString() = txid.toHexReversed()
    }

    /**
     * Information about transactions that receive or spend funds belonging to the specified
     * transparent address is requested.
     *
     * Fully transparent transactions, and transactions that do not contain either shielded inputs
     * or shielded outputs belonging to the wallet, may not be discovered by the process of chain
     * scanning; as a consequence, the wallet must actively query to find transactions that spend
     * such funds. Ideally we'd be able to query by `OutPoint` but this is not currently
     * functionality that is supported by the light wallet server.
     *
     * The caller evaluating this request on behalf of the wallet backend should respond to this
     * request by detecting transactions involving the specified address within the provided block
     * range; if using `lightwalletd` for access to chain data, this may be performed using the
     * `GetTaddressTxids` RPC method. It should then call `ZcashRustBackend.decryptAndStoreTransaction`
     * for each transaction so detected.
     */
    data class TransactionsInvolvingAddress(
        /**
         * The address to request transactions and/or UTXOs for.
         */
        val address: String,
        /**
         * Only transactions mined at heights greater than or equal to this height should be returned.
         */
        val startHeight: BlockHeight,
        /**
         * If set, only transactions mined at heights less than this height should be returned.
         */
        val endHeight: BlockHeight?,
        /**
         * If `request_at` is set, the caller evaluating this request should attempt to
         * retrieve transaction data related to the specified address at a time that is as close
         * as practical to the specified instant, and in a fashion that decorrelates this request
         * to a light wallet server from other requests made by the same caller.
         *
         * This may be ignored by callers that are able to satisfy the request without exposing
         * correlations between addresses to untrusted parties; for example, a wallet application
         * that uses a private, trusted-for-privacy supplier of chain data can safely ignore this
         * field.
         */
        val requestAt: Instant?,
        /**
         * The caller should respond to this request only with transactions that conform to the
         * specified transaction status filter.
         */
        val txStatusFilter: TransactionStatusFilter,
        /**
         * The caller should respond to this request only with transactions containing outputs
         * that conform to the specified output status filter.
         */
        val outputStatusFilter: OutputStatusFilter,
    ) : TransactionDataRequest {
        init {
            if (endHeight != null) {
                require(endHeight >= startHeight) {
                    "End height $endHeight must not be less than start height $startHeight."
                }
            }
        }
    }

    companion object {
        fun new(jni: JniTransactionDataRequest): TransactionDataRequest =
            when (jni) {
                is JniTransactionDataRequest.GetStatus -> {
                    GetStatus(jni.txid)
                }

                is JniTransactionDataRequest.Enhancement -> {
                    Enhancement(jni.txid)
                }

                is JniTransactionDataRequest.TransactionsInvolvingAddress -> {
                    TransactionsInvolvingAddress(
                        jni.address,
                        BlockHeight.new(jni.startHeight),
                        if (jni.endHeight == -1L) {
                            null
                        } else {
                            BlockHeight.new(jni.endHeight)
                        },
                        if (jni.requestAt == -1L) {
                            null
                        } else {
                            Instant.fromEpochSeconds(jni.requestAt, 0)
                        },
                        when (jni.txStatusFilter) {
                            is JniTransactionStatusFilter.Mined -> TransactionStatusFilter.Mined
                            is JniTransactionStatusFilter.Mempool -> TransactionStatusFilter.Mempool
                            is JniTransactionStatusFilter.All -> TransactionStatusFilter.All
                        },
                        when (jni.outputStatusFilter) {
                            is JniOutputStatusFilter.Unspent -> OutputStatusFilter.Unspent
                            is JniOutputStatusFilter.All -> OutputStatusFilter.All
                        }
                    )
                }
            }
    }
}

/**
 * A type describing the mined-ness of transactions that should be returned in response to a
 * `TransactionDataRequest`.
 */
sealed interface TransactionStatusFilter {
    /**
     * Only mined transactions should be returned.
     */
    data object Mined : TransactionStatusFilter

    /**
     * Only mempool transactions should be returned.
     */
    data object Mempool : TransactionStatusFilter

    /**
     * Both mined transactions and transactions in the mempool should be returned.
     */
    data object All : TransactionStatusFilter
}

/**
 * A type used to filter transactions to be returned in response to a `TransactionDataRequest`,
 * in terms of the spentness of the transaction's transparent outputs.
 */
sealed interface OutputStatusFilter {
    /**
     * Only transactions that have currently-unspent transparent outputs should be returned.
     */
    data object Unspent : OutputStatusFilter

    /**
     * All transactions corresponding to the data request should be returned, irrespective of
     * whether or not those transactions produce transparent outputs that are currently unspent.
     */
    data object All : OutputStatusFilter
}
