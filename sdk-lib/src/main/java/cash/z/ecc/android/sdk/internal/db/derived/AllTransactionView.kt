package cash.z.ecc.android.sdk.internal.db.derived

import androidx.core.database.getBlobOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import cash.z.ecc.android.sdk.internal.db.CursorParser
import cash.z.ecc.android.sdk.internal.db.queryAndMap
import cash.z.ecc.android.sdk.internal.model.DbTransactionOverview
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.util.Locale
import kotlin.math.absoluteValue

internal class AllTransactionView(
    private val sqliteDatabase: SupportSQLiteDatabase
) {
    companion object {
        private const val COLUMN_SORT_HEIGHT = "sort_height" // $NON-NLS

        private const val QUERY_LIMIT = "1" // $NON-NLS

        private const val SENT_TRANSACTION_RECOGNITION_VALUE = "0" // $NON-NLS

        private val COLUMNS =
            arrayOf(
                // $NON-NLS
                "*",
                @Suppress("MaxLineLength")
                // $NON-NLS
                "IFNULL(${AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT}, ${UInt.MAX_VALUE}) AS $COLUMN_SORT_HEIGHT"
            )

        private val ORDER_BY =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s DESC, %s DESC",
                COLUMN_SORT_HEIGHT,
                AllTransactionViewDefinition.COLUMN_INTEGER_TRANSACTION_INDEX
            )

        // SQLite versions prior to 3.30.0 don't inherently support the NULLS LAST clause for ordering, and we support
        // these SQLite versions by supporting older Android API level starting from 27. This means NULL values are
        // typically sorted before non-null values in ascending order. As pointed out in #1536 we need to sort them
        // at the end. Thus, we need to use condition in the query below. We should avoid decision logic based on
        // Android API level as the SQLite version is rather device-specific. The specific SQLite version can vary
        // across different device manufacturers and Android versions, making it unreliable to rely on a fixed value.
        private val ORDER_BY_MINED_HEIGHT =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "CASE WHEN %s IS NULL THEN 1 ELSE 0 END ASC, %s ASC",
                AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT,
                AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT
            )

        /**
         * Get all sent, unmined transactions that are still within the expiry window
         *
         * Requested selection should look like this:
         * mined_height IS NULL AND expiry_height > ? AND account_balance_delta < 0
         */
        private val SELECTION_TRX_RESUBMISSION =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s IS NULL AND %s > ? AND %s < %s",
                AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT,
                AllTransactionViewDefinition.COLUMN_INTEGER_EXPIRY_HEIGHT,
                AllTransactionViewDefinition.COLUMN_LONG_ACCOUNT_BALANCE_DELTA,
                SENT_TRANSACTION_RECOGNITION_VALUE
            )

        /**
         * Get all transactions belonging to the given account UUID
         */
        private val SELECTION_TRX_BY_ACCOUNT_UUID =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s = ?",
                AllTransactionViewDefinition.COLUMN_BLOB_ACCOUNT_UUID,
            )

        private val SELECTION_RAW_IS_NULL =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s IS NULL",
                AllTransactionViewDefinition.COLUMN_BLOB_RAW
            )

        private val PROJECTION_COUNT = arrayOf("COUNT(*)") // $NON-NLS

        private val PROJECTION_MINED_HEIGHT = arrayOf(AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT)
    }

    private val cursorParser: CursorParser<DbTransactionOverview> =
        CursorParser { cursor ->
            val minedHeightColumnIndex =
                cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_MINED_HEIGHT)
            val transactionIndexColumnIndex =
                cursor.getColumnIndex(
                    AllTransactionViewDefinition.COLUMN_INTEGER_TRANSACTION_INDEX
                )
            val rawTransactionIdIndex =
                cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_BLOB_RAW_TRANSACTION_ID)
            val expiryHeightIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_EXPIRY_HEIGHT)
            val rawIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_BLOB_RAW)
            val netValueIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_LONG_ACCOUNT_BALANCE_DELTA)
            val totalSpentIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_LONG_TOTAL_SPENT)
            val totalReceivedIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_LONG_TOTAL_RECEIVED)
            val feePaidIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_LONG_FEE_PAID)
            val isChangeIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_BOOLEAN_IS_CHANGE)
            val receivedNoteCountIndex =
                cursor.getColumnIndex(
                    AllTransactionViewDefinition.COLUMN_INTEGER_RECEIVED_NOTE_COUNT
                )
            val sentNoteCountIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_SENT_NOTE_COUNT)
            val memoCountIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_MEMO_COUNT)
            val blockTimeIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_BLOCK_TIME)
            val isShielding = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_BOOLEAN_IS_SHIELDING)
            val isExpiredUnmined = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_EXPIRED_UNMINED)
            val zip318KindIndex = cursor.getColumnIndex(AllTransactionViewDefinition.COLUMN_INTEGER_ZIP318_KIND)

            val netValueLong = cursor.getLong(netValueIndex)
            val isSent = netValueLong < 0

            DbTransactionOverview(
                rawId = FirstClassByteArray(cursor.getBlob(rawTransactionIdIndex)),
                minedHeight =
                    cursor.getLongOrNull(minedHeightColumnIndex)?.let {
                        BlockHeight.new(it)
                    },
                expiryHeight =
                    cursor.getLongOrNull(expiryHeightIndex)?.let {
                        // TODO [#1251]: Separate "no expiry height" from "expiry height unknown".
                        if (0L == it) {
                            null
                        } else {
                            BlockHeight.new(it)
                        }
                    },
                index = cursor.getLongOrNull(transactionIndexColumnIndex),
                raw = cursor.getBlobOrNull(rawIndex)?.let { FirstClassByteArray(it) },
                isSentTransaction = isSent,
                netValue = Zatoshi(netValueLong.absoluteValue),
                totalSpent = Zatoshi(cursor.getLong(totalSpentIndex)),
                totalReceived = Zatoshi(cursor.getLong(totalReceivedIndex)),
                feePaid = cursor.getLongOrNull(feePaidIndex)?.let { Zatoshi(it) },
                isChange = cursor.getInt(isChangeIndex) != 0,
                receivedNoteCount = cursor.getInt(receivedNoteCountIndex),
                sentNoteCount = cursor.getInt(sentNoteCountIndex),
                memoCount = cursor.getInt(memoCountIndex),
                blockTimeEpochSeconds = cursor.getLongOrNull(blockTimeIndex),
                isShielding = cursor.getIntOrNull(isShielding) == 1,
                isExpiredUnmined =
                    when (cursor.getIntOrNull(isExpiredUnmined)) {
                        1 -> true
                        null -> null
                        else -> false
                    },
                // The column is absent on databases created before librustzcash added it, which
                // tells us as little about the transaction as an unrecognized code does.
                zip318Kind =
                    if (zip318KindIndex < 0) {
                        Zip318Kind.NOT_CLASSIFIED
                    } else {
                        Zip318Kind.new(cursor.getIntOrNull(zip318KindIndex))
                    }
            )
        }

    suspend fun count() =
        sqliteDatabase
            .queryAndMap(
                AllTransactionViewDefinition.VIEW_NAME,
                columns = PROJECTION_COUNT,
                cursorParser = { it.getLong(0) }
            ).first()

    fun getAllTransactions() =
        sqliteDatabase.queryAndMap(
            table = AllTransactionViewDefinition.VIEW_NAME,
            columns = COLUMNS,
            orderBy = ORDER_BY,
            cursorParser = cursorParser
        )

    fun getTransactions(accountUuid: AccountUuid) =
        sqliteDatabase.queryAndMap(
            table = AllTransactionViewDefinition.VIEW_NAME,
            columns = COLUMNS,
            orderBy = ORDER_BY,
            selection = SELECTION_TRX_BY_ACCOUNT_UUID,
            selectionArgs = arrayOf(accountUuid.value),
            cursorParser = cursorParser
        )

    fun getUnminedUnexpiredTransactions(blockHeight: BlockHeight) =
        sqliteDatabase.queryAndMap(
            table = AllTransactionViewDefinition.VIEW_NAME,
            columns = COLUMNS,
            orderBy = ORDER_BY,
            selection = SELECTION_TRX_RESUBMISSION,
            selectionArgs = arrayOf(blockHeight.value),
            cursorParser = cursorParser
        )

    suspend fun getOldestTransaction() =
        sqliteDatabase
            .queryAndMap(
                table = AllTransactionViewDefinition.VIEW_NAME,
                columns = COLUMNS,
                orderBy = ORDER_BY,
                limit = QUERY_LIMIT,
                cursorParser = cursorParser
            ).firstOrNull()

    suspend fun firstUnenhancedHeight(): BlockHeight? {
        val heightLong =
            sqliteDatabase
                .queryAndMap(
                    table = AllTransactionViewDefinition.VIEW_NAME,
                    columns = PROJECTION_MINED_HEIGHT,
                    orderBy = ORDER_BY_MINED_HEIGHT,
                    selection = SELECTION_RAW_IS_NULL,
                    limit = QUERY_LIMIT,
                    cursorParser = { it.getLong(0) }
                ).firstOrNull()

        return if (heightLong != null) {
            BlockHeight.new(heightLong)
        } else {
            null
        }
    }
}

internal object AllTransactionViewDefinition {
    const val VIEW_NAME = "v_transactions" // $NON-NLS

    const val COLUMN_INTEGER_MINED_HEIGHT = "mined_height" // $NON-NLS

    const val COLUMN_INTEGER_TRANSACTION_INDEX = "tx_index" // $NON-NLS

    const val COLUMN_BLOB_RAW_TRANSACTION_ID = "txid" // $NON-NLS

    const val COLUMN_INTEGER_EXPIRY_HEIGHT = "expiry_height" // $NON-NLS

    const val COLUMN_BLOB_RAW = "raw" // $NON-NLS

    const val COLUMN_LONG_ACCOUNT_BALANCE_DELTA = "account_balance_delta" // $NON-NLS

    const val COLUMN_LONG_TOTAL_SPENT = "total_spent" // $NON-NLS

    const val COLUMN_LONG_TOTAL_RECEIVED = "total_received" // $NON-NLS

    const val COLUMN_LONG_FEE_PAID = "fee_paid" // $NON-NLS

    const val COLUMN_BOOLEAN_IS_CHANGE = "has_change" // $NON-NLS

    const val COLUMN_INTEGER_SENT_NOTE_COUNT = "sent_note_count" // $NON-NLS

    const val COLUMN_INTEGER_RECEIVED_NOTE_COUNT = "received_note_count" // $NON-NLS

    const val COLUMN_INTEGER_MEMO_COUNT = "memo_count" // $NON-NLS

    const val COLUMN_INTEGER_BLOCK_TIME = "block_time" // $NON-NLS

    const val COLUMN_BOOLEAN_IS_SHIELDING = "is_shielding" // $NON-NLS

    const val COLUMN_BLOB_ACCOUNT_UUID = "account_uuid" // $NON-NLS

    const val COLUMN_EXPIRED_UNMINED = "expired_unmined"

    const val COLUMN_INTEGER_ZIP318_KIND = "zip318_kind" // $NON-NLS
}
