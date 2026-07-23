package cash.z.ecc.android.sdk.internal.db.derived

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import cash.z.ecc.android.sdk.internal.db.queryAndMap
import cash.z.ecc.android.sdk.internal.model.OutputProperties
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionRecipient
import kotlinx.coroutines.flow.Flow
import java.util.Locale

internal class TxOutputsView(
    private val sqliteDatabase: SupportSQLiteDatabase
) {
    companion object {
        private val ORDER_BY =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s ASC",
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID
            )

        private val PROJECTION_OUTPUT_PROPERTIES =
            arrayOf(
                TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_INDEX,
                TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_POOL,
            )

        private val PROJECTION_ALL_OUTPUT_PROPERTIES =
            arrayOf(
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID,
                TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_INDEX,
                TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_POOL,
            )

        private val PROJECTION_MEMOS =
            arrayOf(
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID
            )

        private val PROJECTION_RECIPIENT =
            arrayOf(
                TxOutputsViewDefinition.COLUMN_STRING_TO_ADDRESS,
                TxOutputsViewDefinition.COLUMN_BLOB_TO_ACCOUNT
            )

        private val PROJECTION_ALL_RECIPIENT =
            arrayOf(
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID,
                TxOutputsViewDefinition.COLUMN_STRING_TO_ADDRESS,
                TxOutputsViewDefinition.COLUMN_BLOB_TO_ACCOUNT
            )

        private val SELECT_BY_TRANSACTION_ID_AND_NOT_CHANGE =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s = ? AND %s == 0",
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID,
                TxOutputsViewDefinition.COLUMN_INTEGER_IS_CHANGE
            )

        private val SELECT_BY_MEMO_QUERY =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "LOWER(%s) LIKE LOWER(?)",
                TxOutputsViewDefinition.COLUMN_BLOB_MEMO,
            )

        private val SELECT_NOT_CHANGE =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s == 0",
                TxOutputsViewDefinition.COLUMN_INTEGER_IS_CHANGE
            )

        private val ORDER_BY_TRANSACTION_ID_AND_OUTPUT_INDEX =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s ASC, %s ASC",
                TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID,
                TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_INDEX
            )
    }

    fun getOutputProperties(transactionId: FirstClassByteArray) =
        sqliteDatabase.queryAndMap(
            table = TxOutputsViewDefinition.VIEW_NAME,
            columns = PROJECTION_OUTPUT_PROPERTIES,
            selection = SELECT_BY_TRANSACTION_ID_AND_NOT_CHANGE,
            selectionArgs = arrayOf(transactionId.byteArray),
            orderBy = ORDER_BY,
            cursorParser = { it.parseOutputProperties() }
        )

    /**
     * Returns the non-change output properties for ALL transactions in a single query, grouped by transaction ID
     * via the emitted [Pair]. This is a batched alternative to [getOutputProperties] intended for callers that
     * would otherwise need to query per-transaction in a loop. A transaction with only change outputs (or no
     * outputs) does not emit any [Pair].
     */
    fun getAllOutputProperties(): Flow<Pair<FirstClassByteArray, OutputProperties>> =
        sqliteDatabase.queryAndMap(
            table = TxOutputsViewDefinition.VIEW_NAME,
            columns = PROJECTION_ALL_OUTPUT_PROPERTIES,
            selection = SELECT_NOT_CHANGE,
            selectionArgs = null,
            orderBy = ORDER_BY_TRANSACTION_ID_AND_OUTPUT_INDEX,
            cursorParser = {
                val transactionId = it.parseTransactionId()
                transactionId to it.parseOutputProperties()
            }
        )

    fun getTransactionsByMemoSubstring(query: String): Flow<FirstClassByteArray> =
        // This query could be optimized by joining with v_transactions and querying only those transactions whose
        // memo_count is greater than 0
        sqliteDatabase.queryAndMap(
            table = TxOutputsViewDefinition.VIEW_NAME,
            columns = PROJECTION_MEMOS,
            selection = SELECT_BY_MEMO_QUERY,
            selectionArgs = arrayOf("%$query%"),
            orderBy = ORDER_BY,
            cursorParser = { it.parseTransactionId() }
        )

    fun getRecipients(transactionId: FirstClassByteArray) =
        sqliteDatabase.queryAndMap(
            table = TxOutputsViewDefinition.VIEW_NAME,
            columns = PROJECTION_RECIPIENT,
            selection = SELECT_BY_TRANSACTION_ID_AND_NOT_CHANGE,
            selectionArgs = arrayOf(transactionId.byteArray),
            orderBy = ORDER_BY,
            cursorParser = { it.parseRecipient() }
        )

    /**
     * Returns the non-change recipients for ALL transactions in a single query, grouped by transaction ID via the
     * emitted [Pair]. This is a batched alternative to [getRecipients] intended for callers that would otherwise
     * need to query per-transaction in a loop. A transaction with only change outputs (or no outputs) does not
     * emit any [Pair].
     */
    fun getAllRecipients(): Flow<Pair<FirstClassByteArray, TransactionRecipient>> =
        sqliteDatabase.queryAndMap(
            table = TxOutputsViewDefinition.VIEW_NAME,
            columns = PROJECTION_ALL_RECIPIENT,
            selection = SELECT_NOT_CHANGE,
            selectionArgs = null,
            orderBy = ORDER_BY_TRANSACTION_ID_AND_OUTPUT_INDEX,
            cursorParser = {
                val transactionId = it.parseTransactionId()
                transactionId to it.parseRecipient()
            }
        )

    private fun Cursor.parseTransactionId(): FirstClassByteArray {
        val idColumnTrxIdIndex = getColumnIndex(TxOutputsViewDefinition.COLUMN_BLOB_TRANSACTION_ID)
        return FirstClassByteArray(getBlob(idColumnTrxIdIndex))
    }

    private fun Cursor.parseOutputProperties(): OutputProperties {
        val idColumnOutputIndex = getColumnIndex(TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_INDEX)
        val idColumnOutputPoolIndex = getColumnIndex(TxOutputsViewDefinition.COLUMN_INTEGER_OUTPUT_POOL)

        return OutputProperties.new(
            index = getInt(idColumnOutputIndex),
            poolType = getInt(idColumnOutputPoolIndex)
        )
    }

    private fun Cursor.parseRecipient(): TransactionRecipient {
        val toAccountIndex = getColumnIndex(TxOutputsViewDefinition.COLUMN_BLOB_TO_ACCOUNT)
        val toAddressIndex = getColumnIndex(TxOutputsViewDefinition.COLUMN_STRING_TO_ADDRESS)

        return TransactionRecipient(
            addressValue = if (!isNull(toAddressIndex)) getString(toAddressIndex) else null,
            accountUuid = if (!isNull(toAccountIndex)) AccountUuid(getBlob(toAccountIndex)) else null
        )
    }
}

internal object TxOutputsViewDefinition {
    const val VIEW_NAME = "v_tx_outputs" // $NON-NLS

    const val COLUMN_BLOB_TRANSACTION_ID = "txid" // $NON-NLS

    const val COLUMN_INTEGER_OUTPUT_POOL = "output_pool" // $NON-NLS

    const val COLUMN_INTEGER_OUTPUT_INDEX = "output_index" // $NON-NLS

    const val COLUMN_STRING_TO_ADDRESS = "to_address" // $NON-NLS

    const val COLUMN_BLOB_TO_ACCOUNT = "to_account_uuid" // $NON-NLS

    const val COLUMN_INTEGER_VALUE = "value" // $NON-NLS

    const val COLUMN_INTEGER_IS_CHANGE = "is_change" // $NON-NLS

    const val COLUMN_BLOB_MEMO = "memo" // $NON-NLS
}
