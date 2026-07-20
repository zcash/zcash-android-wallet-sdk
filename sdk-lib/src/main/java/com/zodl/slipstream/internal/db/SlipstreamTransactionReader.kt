package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.TransactionRecipient
import com.zodl.slipstream.SlipstreamNative
import com.zodl.slipstream.db.SlipstreamWalletDb
import com.zodl.slipstream.internal.spend.ResubmissionCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One non-change output of a transaction, as read from `v_tx_outputs`. */
internal data class OutputProperty(
    val index: Int,
    /** The upstream `ZcashProtocol` pool code: 0 = transparent, 2 = sapling, 3 = orchard, 4 = ironwood. */
    val poolCode: Int
)

private fun poolFromCode(poolCode: Int): TransactionPool =
    when (poolCode) {
        0 -> TransactionPool.TRANSPARENT
        2 -> TransactionPool.SAPLING
        3 -> TransactionPool.ORCHARD
        4 -> TransactionPool.IRONWOOD
        else -> error("Unsupported pool code: $poolCode")
    }

/**
 * Typed access over the engine-managed `data.sqlite3` that backs `allTransactions` (R18),
 * `getTransactions(accountUuid)` (R23), and the raw-bytes reads the T8 spend path needs after a
 * store-first `create` (`SDK_ADAPTER_PLAN.md` T8). Every method below (all but [debugQuery]) runs
 * through one of the 5 typed [SlipstreamNative] host-read exports (`FFI_JNI_CONTRACT.md`
 * section 9.3), which construct `com.zodl.slipstream.model` row objects on the engine's own
 * bundled SQLite instance - the JSON `readQuery` lane [SlipstreamWalletDb.query] wrapped is now
 * debug-only (`Synchronizer.debugQuery`, see [debugQuery] and [SlipstreamWalletDb]'s KDoc,
 * especially incident #5, for why no connection ever crosses through the Android framework).
 */
internal class SlipstreamTransactionReader(
    private val dbFile: File
) {
    /** R18 (`filterByAccount = false`) and the account-scoped half of R23. */
    suspend fun queryVisible(
        isRecovering: Boolean,
        latestHeight: BlockHeight?,
        accountUuid: AccountUuid? = null
    ): List<TransactionOverview> =
        withContext(Dispatchers.IO) {
            SlipstreamNative.listTransactions(dbFile.absolutePath, isRecovering, accountUuid?.value).map { row ->
                TransactionOverviewCursor.fromRow(row, latestHeight)
            }
        }

    /** T8's "T6 SQL: SELECT raw FROM transactions WHERE txid = ?" - post-`create` raw-bytes read-back. */
    suspend fun readRawTransaction(txId: FirstClassByteArray): FirstClassByteArray =
        withContext(Dispatchers.IO) {
            val row = SlipstreamNative.getTransactionRaw(dbFile.absolutePath, txId.byteArray)
            checkNotNull(row) { "No stored transaction found for the given txid" }
            FirstClassByteArray(row.raw)
        }

    /** Same read, plus `expiry_height` - what the R29 [cash.z.ecc.android.sdk.Broadcaster] needs to build a [CreatedTransaction]. */
    suspend fun readCreatedTransaction(txId: FirstClassByteArray): CreatedTransaction =
        withContext(Dispatchers.IO) {
            val row = SlipstreamNative.getTransactionRaw(dbFile.absolutePath, txId.byteArray)
            checkNotNull(row) { "No stored transaction found for the given txid" }
            CreatedTransaction(
                txId = txId,
                raw = FirstClassByteArray(row.raw),
                expiryHeight = row.expiryHeight.takeIf { it != 0L }?.let(BlockHeight::new)
            )
        }

    /** R19/R22: non-change output properties for [txId], oldest-first - `v_tx_outputs` (`TxOutputsViewDefinition`). */
    suspend fun getOutputProperties(txId: FirstClassByteArray): List<OutputProperty> =
        withContext(Dispatchers.IO) {
            SlipstreamNative.listTransactionOutputs(dbFile.absolutePath, txId.byteArray).map {
                OutputProperty(index = it.outputIndex, poolCode = it.outputPool)
            }
        }

    /** R22: [getOutputProperties] mapped to the public `TransactionOutput` pool enum. */
    suspend fun getTransactionOutputs(txId: FirstClassByteArray): List<TransactionOutput> =
        getOutputProperties(txId).map { TransactionOutput(poolFromCode(it.poolCode)) }

    /**
     * Batched alternative to [getOutputProperties] that returns the non-change output properties
     * for ALL transactions in a single query, grouped by txid. Mirrors the upstream SDK's
     * `TxOutputsView.getAllOutputProperties`/`DbDerivedDataRepository.getAllOutputProperties`
     * filter (`is_change = 0`) and ordering (`txid ASC, output_index ASC`); a transaction with
     * only change outputs (or no outputs) is absent from the map rather than present with an
     * empty list.
     */
    suspend fun getAllOutputProperties(): Map<FirstClassByteArray, List<OutputProperty>> =
        withContext(Dispatchers.IO) {
            val result = LinkedHashMap<FirstClassByteArray, MutableList<OutputProperty>>()
            for (row in SlipstreamNative.listTransactionOutputs(dbFile.absolutePath, null)) {
                result.getOrPut(FirstClassByteArray(row.txId)) { mutableListOf() }
                    .add(OutputProperty(index = row.outputIndex, poolCode = row.outputPool))
            }
            result
        }

    /** Batched alternative to [getTransactionOutputs]; see [getAllOutputProperties] for the grouping semantics. */
    suspend fun getAllTransactionOutputs(): Map<FirstClassByteArray, List<TransactionOutput>> =
        getAllOutputProperties().mapValues { (_, properties) ->
            properties.map { TransactionOutput(poolFromCode(it.poolCode)) }
        }

    /** R20: `v_tx_outputs.memo LIKE '%query%'`, case-insensitive. */
    suspend fun getTransactionsByMemoSubstring(substring: String): List<FirstClassByteArray> =
        withContext(Dispatchers.IO) {
            SlipstreamNative.findTransactionsByMemo(dbFile.absolutePath, substring).map(::FirstClassByteArray)
        }

    /** R21: non-change recipients for [txId] - either an address or an internal account, never both. */
    suspend fun getRecipients(txId: FirstClassByteArray): List<TransactionRecipient> =
        withContext(Dispatchers.IO) {
            SlipstreamNative.listTransactionOutputs(dbFile.absolutePath, txId.byteArray).map {
                TransactionRecipient(
                    addressValue = it.toAddress,
                    accountUuid = it.toAccountUuid?.let(AccountUuid::new)
                )
            }
        }

    /**
     * Batched alternative to [getRecipients] that returns the non-change recipients for ALL
     * transactions in a single query, grouped by txid; see [getAllOutputProperties] for the
     * grouping semantics.
     */
    suspend fun getAllRecipients(): Map<FirstClassByteArray, List<TransactionRecipient>> =
        withContext(Dispatchers.IO) {
            val result = LinkedHashMap<FirstClassByteArray, MutableList<TransactionRecipient>>()
            for (row in SlipstreamNative.listTransactionOutputs(dbFile.absolutePath, null)) {
                result.getOrPut(FirstClassByteArray(row.txId)) { mutableListOf() }
                    .add(
                        TransactionRecipient(
                            addressValue = row.toAddress,
                            accountUuid = row.toAccountUuid?.let(AccountUuid::new)
                        )
                    )
            }
            result
        }

    /**
     * T8 section 3.7: the resubmission scan itself, bound to [chainTip] as a typed INTEGER
     * parameter (`host_read.rs`'s `listResubmissionCandidates` SQL - no TEXT-affinity
     * workaround needed now that the native binds it typed).
     */
    suspend fun findResubmissionCandidates(chainTip: Long): List<ResubmissionCandidate> =
        withContext(Dispatchers.IO) {
            SlipstreamNative.listResubmissionCandidates(dbFile.absolutePath, chainTip).map {
                ResubmissionCandidate(txId = it.txId, raw = it.raw)
            }
        }

    /**
     * R59 `debugQuery`: free-form SQL over the engine's bundled SQLite instance, via the
     * debug-only [SlipstreamNative.readQuery] lane [SlipstreamWalletDb.query] wraps. Column
     * NAMES are not available here - `readQuery` returns row values only, not result-set
     * metadata - so columns render positionally (`column0=... column1=...`) rather than by their
     * real name.
     */
    suspend fun debugQuery(sql: String): String {
        val rows = SlipstreamWalletDb.query(dbFile, sql)
        return buildString {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                for (column in 0 until row.length()) {
                    val value = if (row.isNull(column)) "null" else row.get(column).toString()
                    append("column").append(column).append('=').append(value).append(' ')
                }
                append('\n')
            }
        }
    }
}
