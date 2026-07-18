package com.zodl.slipstream.internal.db

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.TransactionRecipient
import com.zodl.slipstream.db.SlipstreamWalletDb
import com.zodl.slipstream.internal.spend.ResubmissionCandidate
import com.zodl.slipstream.internal.spend.ResubmissionQuery
import org.json.JSONArray
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
 * Decodes a [com.zodl.slipstream.SlipstreamNative.readQuery] BLOB column (lowercase hex) back
 * into bytes. `internal` (not `private`) so the row-decoding unit tests can pin it directly
 * without a loaded native library.
 */
internal fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
    }

/**
 * [JSONArray] column accessors matching [com.zodl.slipstream.SlipstreamNative.readQuery]'s
 * encoding (INTEGER/REAL as a JSON number, TEXT as a JSON string, BLOB as hex, NULL as JSON
 * null). `internal` (not `private`) for the same reason as [hexToBytes].
 */
internal fun JSONArray.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

internal fun JSONArray.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

internal fun JSONArray.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

internal fun JSONArray.blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else hexToBytes(getString(index))

internal fun JSONArray.blob(index: Int): ByteArray = hexToBytes(getString(index))

/**
 * Read-only access over the engine-managed `data.sqlite3` that backs `allTransactions` (R18),
 * `getTransactions(accountUuid)` (R23), and the raw-bytes reads the T8 spend path needs after a
 * store-first `create` (`SDK_ADAPTER_PLAN.md` T8). Wraps [VisibleTransactionsQuery] +
 * [TransactionOverviewCursor] (T6) with the actual query execution T6 deliberately left unwired
 * (`worklog/03-read-side.md`). Every method runs its query through [SlipstreamWalletDb.query],
 * which executes on the engine's own bundled SQLite instance
 * ([com.zodl.slipstream.SlipstreamNative.readQuery]) rather than the Android framework - see
 * [SlipstreamWalletDb]'s KDoc, especially incident #5, for why. Rows come back as a JSON array
 * of arrays; BLOB columns are hex-decoded back into [ByteArray] via [hexToBytes].
 */
internal class SlipstreamTransactionReader(
    private val dbFile: File
) {
    /** R18 (`filterByAccount = false`) and the account-scoped half of R23. */
    suspend fun queryVisible(
        isRecovering: Boolean,
        latestHeight: BlockHeight?,
        accountUuid: AccountUuid? = null
    ): List<TransactionOverview> {
        val sql = VisibleTransactionsQuery.forScope(isRecovering, filterByAccount = accountUuid != null)
        val rows = SlipstreamWalletDb.query(dbFile, sql, blobParam = accountUuid?.value)
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                add(
                    TransactionOverviewCursor.fromRow(
                        txid = row.blob(0),
                        minedHeight = row.longOrNull(1),
                        expiryHeight = row.longOrNull(2),
                        txIndex = row.longOrNull(3),
                        raw = row.blobOrNull(4),
                        accountBalanceDelta = row.getLong(5),
                        totalSpent = row.getLong(6),
                        totalReceived = row.getLong(7),
                        feePaid = row.longOrNull(8),
                        hasChange = row.getInt(9) != 0,
                        sentNoteCount = row.getInt(10),
                        receivedNoteCount = row.getInt(11),
                        memoCount = row.getInt(12),
                        blockTimeEpochSeconds = row.longOrNull(13),
                        isShielding = row.getInt(14) != 0,
                        isExpiredUnmined = row.intOrNull(15)?.let { it != 0 },
                        latestHeight = latestHeight
                    )
                )
            }
        }
    }

    /** T8's "T6 SQL: SELECT raw FROM transactions WHERE txid = ?" - post-`create` raw-bytes read-back. */
    suspend fun readRawTransaction(txId: FirstClassByteArray): FirstClassByteArray {
        val rows = SlipstreamWalletDb.query(dbFile, RAW_TRANSACTION_SQL, blobParam = txId.byteArray)
        check(rows.length() > 0) { "No stored transaction found for the given txid" }
        return FirstClassByteArray(rows.getJSONArray(0).blob(0))
    }

    /** Same read, plus `expiry_height` - what the R29 [cash.z.ecc.android.sdk.Broadcaster] needs to build a [CreatedTransaction]. */
    suspend fun readCreatedTransaction(txId: FirstClassByteArray): CreatedTransaction {
        val rows = SlipstreamWalletDb.query(dbFile, RAW_TRANSACTION_WITH_EXPIRY_SQL, blobParam = txId.byteArray)
        check(rows.length() > 0) { "No stored transaction found for the given txid" }
        val row = rows.getJSONArray(0)
        return CreatedTransaction(
            txId = txId,
            raw = FirstClassByteArray(row.blob(0)),
            expiryHeight = row.longOrNull(1)?.takeIf { it != 0L }?.let(BlockHeight::new)
        )
    }

    /** R19/R22: non-change output properties for [txId], oldest-first - `v_tx_outputs` (`TxOutputsViewDefinition`). */
    suspend fun getOutputProperties(txId: FirstClassByteArray): List<OutputProperty> {
        val rows = SlipstreamWalletDb.query(dbFile, OUTPUT_PROPERTIES_SQL, blobParam = txId.byteArray)
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                add(OutputProperty(index = row.getInt(0), poolCode = row.getInt(1)))
            }
        }
    }

    /** R22: [getOutputProperties] mapped to the public `TransactionOutput` pool enum. */
    suspend fun getTransactionOutputs(txId: FirstClassByteArray): List<TransactionOutput> =
        getOutputProperties(txId).map { TransactionOutput(poolFromCode(it.poolCode)) }

    /** R20: `v_tx_outputs.memo LIKE '%query%'`, case-insensitive. */
    suspend fun getTransactionsByMemoSubstring(substring: String): List<FirstClassByteArray> {
        val rows = SlipstreamWalletDb.query(dbFile, MEMO_SEARCH_SQL, textParam = "%$substring%")
        return buildList {
            for (i in 0 until rows.length()) add(FirstClassByteArray(rows.getJSONArray(i).blob(0)))
        }
    }

    /** R21: non-change recipients for [txId] - either an address or an internal account, never both. */
    suspend fun getRecipients(txId: FirstClassByteArray): List<TransactionRecipient> {
        val rows = SlipstreamWalletDb.query(dbFile, RECIPIENTS_SQL, blobParam = txId.byteArray)
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                add(
                    TransactionRecipient(
                        addressValue = row.stringOrNull(0),
                        accountUuid = row.blobOrNull(1)?.let(AccountUuid::new)
                    )
                )
            }
        }
    }

    /**
     * T8 section 3.7: the resubmission scan itself, `ResubmissionQuery.SQL` bound to
     * [chainTip]. Bound as [SlipstreamWalletDb.query]'s `textParam` (not `blobParam`) - the
     * same string-bound comparison the framework `rawQuery` used before (SQLite's INTEGER
     * column affinity coerces the bound text back for the comparison).
     */
    suspend fun findResubmissionCandidates(chainTip: Long): List<ResubmissionCandidate> {
        val rows = SlipstreamWalletDb.query(dbFile, ResubmissionQuery.SQL, textParam = chainTip.toString())
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                add(ResubmissionCandidate(txId = row.blob(0), raw = row.blob(1)))
            }
        }
    }

    /**
     * R59 `debugQuery`: free-form SQL over the engine's bundled SQLite instance. Column NAMES
     * are not available here - [com.zodl.slipstream.SlipstreamNative.readQuery] returns row
     * values only, not result-set metadata - so columns render positionally (`column0=...
     * column1=...`) rather than by their real name.
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

    companion object {
        private const val RAW_TRANSACTION_SQL = "SELECT raw FROM transactions WHERE txid = ?"
        private const val RAW_TRANSACTION_WITH_EXPIRY_SQL = "SELECT raw, expiry_height FROM transactions WHERE txid = ?"
        private const val NON_CHANGE_CONDITION = "txid = ? AND is_change = 0"
        private const val OUTPUT_PROPERTIES_SQL = "SELECT output_index, output_pool FROM v_tx_outputs WHERE $NON_CHANGE_CONDITION"
        private const val MEMO_SEARCH_SQL = "SELECT txid FROM v_tx_outputs WHERE LOWER(memo) LIKE LOWER(?)"
        private const val RECIPIENTS_SQL = "SELECT to_address, to_account_uuid FROM v_tx_outputs WHERE $NON_CHANGE_CONDITION"
    }
}
