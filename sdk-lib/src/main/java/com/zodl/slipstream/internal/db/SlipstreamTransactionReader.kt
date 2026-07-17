package com.zodl.slipstream.internal.db

import android.content.Context
import androidx.core.database.getBlobOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.TransactionRecipient
import com.zodl.slipstream.db.SlipstreamWalletDb
import com.zodl.slipstream.internal.spend.ResubmissionCandidate
import com.zodl.slipstream.internal.spend.ResubmissionQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One non-change output of a transaction, as read from `v_tx_outputs`. */
internal data class OutputProperty(
    val index: Int,
    /** The upstream `ZcashProtocol` pool code: 0 = transparent, 2 = sapling, 3 = orchard. */
    val poolCode: Int
)

private fun poolFromCode(poolCode: Int): TransactionPool =
    when (poolCode) {
        0 -> TransactionPool.TRANSPARENT
        2 -> TransactionPool.SAPLING
        3 -> TransactionPool.ORCHARD
        else -> error("Unsupported pool code: $poolCode")
    }

/**
 * The single read-only connection over the engine-managed `data.sqlite3` that backs `allTransactions`
 * (R18), `getTransactions(accountUuid)` (R23), and the raw-bytes reads the T8 spend path needs after a
 * store-first `create` (`SDK_ADAPTER_PLAN.md` T8). Wraps [VisibleTransactionsQuery] +
 * [TransactionOverviewCursor] (T6) with the actual `SupportSQLiteDatabase` execution T6 deliberately
 * left unwired (`worklog/03-read-side.md`). One connection per synchronizer instance, opened lazily,
 * closed exactly once by `close()` (the C3 `erase` law: every adapter read connection must be closed
 * before an erase).
 */
internal class SlipstreamTransactionReader(
    private val context: Context,
    private val dbFile: File
) {
    private val mutex = Mutex()
    private var database: SupportSQLiteDatabase? = null

    private suspend fun openDatabase(): SupportSQLiteDatabase =
        mutex.withLock {
            database ?: SlipstreamWalletDb.openReadOnly(context, dbFile).also { database = it }
        }

    suspend fun close() =
        mutex.withLock {
            database?.close()
            database = null
        }

    /** R18 (`filterByAccount = false`) and the account-scoped half of R23. */
    suspend fun queryVisible(
        isRecovering: Boolean,
        latestHeight: BlockHeight?,
        accountUuid: AccountUuid? = null
    ): List<TransactionOverview> {
        val sql = VisibleTransactionsQuery.forScope(isRecovering, filterByAccount = accountUuid != null)
        val query =
            if (accountUuid != null) {
                SimpleSQLiteQuery(sql, arrayOf(accountUuid.value))
            } else {
                SimpleSQLiteQuery(sql)
            }
        return openDatabase().query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TransactionOverviewCursor.fromRow(
                            txid = cursor.getBlob(0),
                            minedHeight = cursor.getLongOrNull(1),
                            expiryHeight = cursor.getLongOrNull(2),
                            txIndex = cursor.getLongOrNull(3),
                            raw = cursor.getBlobOrNull(4),
                            accountBalanceDelta = cursor.getLong(5),
                            totalSpent = cursor.getLong(6),
                            totalReceived = cursor.getLong(7),
                            feePaid = cursor.getLongOrNull(8),
                            hasChange = cursor.getInt(9) != 0,
                            sentNoteCount = cursor.getInt(10),
                            receivedNoteCount = cursor.getInt(11),
                            memoCount = cursor.getInt(12),
                            blockTimeEpochSeconds = cursor.getLongOrNull(13),
                            isShielding = cursor.getInt(14) != 0,
                            isExpiredUnmined = cursor.getIntOrNull(15)?.let { it != 0 },
                            latestHeight = latestHeight
                        )
                    )
                }
            }
        }
    }

    /** T8's "T6 SQL: SELECT raw FROM transactions WHERE txid = ?" - post-`create` raw-bytes read-back. */
    suspend fun readRawTransaction(txId: FirstClassByteArray): FirstClassByteArray {
        val query = SimpleSQLiteQuery(RAW_TRANSACTION_SQL, arrayOf(txId.byteArray))
        return openDatabase().query(query).use { cursor ->
            check(cursor.moveToFirst()) { "No stored transaction found for the given txid" }
            FirstClassByteArray(cursor.getBlob(0))
        }
    }

    /** Same read, plus `expiry_height` - what the R29 [cash.z.ecc.android.sdk.Broadcaster] needs to build a [CreatedTransaction]. */
    suspend fun readCreatedTransaction(txId: FirstClassByteArray): CreatedTransaction {
        val query = SimpleSQLiteQuery(RAW_TRANSACTION_WITH_EXPIRY_SQL, arrayOf(txId.byteArray))
        return openDatabase().query(query).use { cursor ->
            check(cursor.moveToFirst()) { "No stored transaction found for the given txid" }
            CreatedTransaction(
                txId = txId,
                raw = FirstClassByteArray(cursor.getBlob(0)),
                expiryHeight = cursor.getLongOrNull(1)?.takeIf { it != 0L }?.let(BlockHeight::new)
            )
        }
    }

    /** R19/R22: non-change output properties for [txId], oldest-first - `v_tx_outputs` (`TxOutputsViewDefinition`). */
    suspend fun getOutputProperties(txId: FirstClassByteArray): List<OutputProperty> {
        val query = SimpleSQLiteQuery(OUTPUT_PROPERTIES_SQL, arrayOf(txId.byteArray))
        return openDatabase().query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(OutputProperty(index = cursor.getInt(0), poolCode = cursor.getInt(1)))
                }
            }
        }
    }

    /** R22: [getOutputProperties] mapped to the public `TransactionOutput` pool enum. */
    suspend fun getTransactionOutputs(txId: FirstClassByteArray): List<TransactionOutput> =
        getOutputProperties(txId).map { TransactionOutput(poolFromCode(it.poolCode)) }

    /** R20: `v_tx_outputs.memo LIKE '%query%'`, case-insensitive. */
    suspend fun getTransactionsByMemoSubstring(substring: String): List<FirstClassByteArray> {
        val query = SimpleSQLiteQuery(MEMO_SEARCH_SQL, arrayOf("%$substring%"))
        return openDatabase().query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(FirstClassByteArray(cursor.getBlob(0)))
            }
        }
    }

    /** R21: non-change recipients for [txId] - either an address or an internal account, never both. */
    suspend fun getRecipients(txId: FirstClassByteArray): List<TransactionRecipient> {
        val query = SimpleSQLiteQuery(RECIPIENTS_SQL, arrayOf(txId.byteArray))
        return openDatabase().query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TransactionRecipient(
                            addressValue = cursor.getStringOrNull(0),
                            accountUuid = cursor.getBlobOrNull(1)?.let(AccountUuid::new)
                        )
                    )
                }
            }
        }
    }

    /** T8 section 3.7: the resubmission scan itself, `ResubmissionQuery.SQL` bound to [chainTip]. */
    suspend fun findResubmissionCandidates(chainTip: Long): List<ResubmissionCandidate> {
        val query = SimpleSQLiteQuery(ResubmissionQuery.SQL, arrayOf(chainTip))
        return openDatabase().query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ResubmissionCandidate(txId = cursor.getBlob(0), raw = cursor.getBlob(1)))
                }
            }
        }
    }

    /** R59 `debugQuery`: free-form SQL over the same read-only connection, rendered as their exact row/column format. */
    suspend fun debugQuery(sql: String): String {
        val query = SimpleSQLiteQuery(sql)
        return openDatabase().query(query).use { cursor ->
            buildString {
                val columnNames = cursor.columnNames
                while (cursor.moveToNext()) {
                    columnNames.indices.forEach { column ->
                        append(columnNames[column]).append('=').append(cursor.getStringOrNull(column)).append(' ')
                    }
                    append('\n')
                }
            }
        }
    }

    companion object {
        private const val RAW_TRANSACTION_SQL = "SELECT raw FROM transactions WHERE txid = ?"
        private const val RAW_TRANSACTION_WITH_EXPIRY_SQL = "SELECT raw, expiry_height FROM transactions WHERE txid = ?"
        private const val NON_CHANGE_CONDITION = "txid = ? AND is_change = 0"
        private const val OUTPUT_PROPERTIES_SQL = "SELECT output_index, output_pool FROM v_tx_outputs WHERE $NON_CHANGE_CONDITION"
        private const val MEMO_SEARCH_SQL = "SELECT txid FROM v_tx_outputs WHERE LOWER(memo) LIKE LOWER(?)"
        private const val RECIPIENTS_SQL = "SELECT to_address, to_account_uuid FROM v_tx_outputs WHERE $NON_CHANGE_CONDITION"
    }
}
