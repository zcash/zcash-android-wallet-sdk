package cash.z.ecc.android.sdk.internal.db.derived

import androidx.sqlite.db.SupportSQLiteDatabase
import cash.z.ecc.android.sdk.internal.db.queryAndMap
import cash.z.ecc.android.sdk.internal.model.DbBlock
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import kotlinx.coroutines.flow.firstOrNull
import java.util.Locale

internal class BlockTable(
    private val sqliteDatabase: SupportSQLiteDatabase
) {
    companion object {
        private val PROJECTION_BLOCK_SIMPLE =
            arrayOf(
                BlockTableDefinition.COLUMN_INTEGER_HEIGHT,
                BlockTableDefinition.COLUMN_BLOB_HASH,
                BlockTableDefinition.COLUMN_INTEGER_TIME,
            )

        private val SELECTION_BLOCK_BY_HEIGHT =
            String.format(
                Locale.ROOT,
                // $NON-NLS
                "%s = ?",
                BlockTableDefinition.COLUMN_INTEGER_HEIGHT
            )
    }

    suspend fun findBlockByExpiryHeight(expiryHeight: BlockHeight): DbBlock? =
        sqliteDatabase
            .queryAndMap(
                table = BlockTableDefinition.TABLE_NAME,
                columns = PROJECTION_BLOCK_SIMPLE,
                selection = SELECTION_BLOCK_BY_HEIGHT,
                selectionArgs = arrayOf(expiryHeight.value)
            ) { cursor ->
                val heightIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_INTEGER_HEIGHT)
                val hashIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_BLOB_HASH)
                val timeIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_INTEGER_TIME)

                val height = cursor.getLong(heightIndex)
                val hash = cursor.getBlob(hashIndex)
                val time = cursor.getLong(timeIndex)

                DbBlock(
                    height = BlockHeight(height),
                    hash = FirstClassByteArray(hash),
                    blockTimeEpochSeconds = time
                )
            }.firstOrNull()

    suspend fun findLatestBlock(): DbBlock? =
        sqliteDatabase
            .queryAndMap(
                table = BlockTableDefinition.TABLE_NAME,
                columns = PROJECTION_BLOCK_SIMPLE,
                selection = null,
                selectionArgs = null,
                orderBy = "${BlockTableDefinition.COLUMN_INTEGER_HEIGHT} DESC",
                limit = "1"
            ) { cursor ->
                val heightIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_INTEGER_HEIGHT)
                val hashIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_BLOB_HASH)
                val timeIndex = cursor.getColumnIndexOrThrow(BlockTableDefinition.COLUMN_INTEGER_TIME)

                val height = cursor.getLong(heightIndex)
                val hash = cursor.getBlob(hashIndex)
                val time = cursor.getLong(timeIndex)

                DbBlock(
                    height = BlockHeight(height),
                    hash = FirstClassByteArray(hash),
                    blockTimeEpochSeconds = time
                )
            }.firstOrNull()
}

internal object BlockTableDefinition {
    const val TABLE_NAME = "blocks" // $NON-NLS

    const val COLUMN_INTEGER_HEIGHT = "height" // $NON-NLS

    const val COLUMN_BLOB_HASH = "hash" // $NON-NLS

    const val COLUMN_INTEGER_TIME = "time" // $NON-NLS

    const val COLUMN_BLOB_SAPLING_TREE = "sapling_tree" // $NON-NLS

    const val COLUMN_INTEGER_SAPLING_COMMITMENT_TREE_SIZE = "sapling_commitment_tree_size" // $NON-NLS

    const val COLUMN_INTEGER_ORCHARD_COMMITMENT_TREE_SIZE = "orchard_commitment_tree_size" // $NON-NLS

    const val COLUMN_INTEGER_SAPLING_OUTPUT_COUNT = "sapling_output_count" // $NON-NLS

    const val COLUMN_INTEGER_ORCHARD_OUTPUT_COUNT = "orchard_output_count" // $NON-NLS
}
