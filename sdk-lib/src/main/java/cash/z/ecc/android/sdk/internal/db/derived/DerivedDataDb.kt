package cash.z.ecc.android.sdk.internal.db.derived

import android.content.Context
import android.database.Cursor.FIELD_TYPE_BLOB
import android.database.Cursor.FIELD_TYPE_FLOAT
import android.database.Cursor.FIELD_TYPE_INTEGER
import android.database.Cursor.FIELD_TYPE_NULL
import android.database.Cursor.FIELD_TYPE_STRING
import androidx.core.database.getBlobOrNull
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.internal.NoBackupContextWrapper
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.Twig
import cash.z.ecc.android.sdk.internal.TypesafeBackend
import cash.z.ecc.android.sdk.internal.db.ReadOnlySupportSqliteOpenHelper
import cash.z.ecc.android.sdk.internal.model.Checkpoint
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.MemoContent
import cash.z.ecc.android.sdk.model.TransactionId
import kotlinx.coroutines.withContext
import java.io.File

internal class DerivedDataDb private constructor(
    private val sqliteDatabase: SupportSQLiteDatabase
) {
    val transactionTable = TransactionTable(sqliteDatabase)

    val blockTable = BlockTable(sqliteDatabase)

    val allTransactionView = AllTransactionView(sqliteDatabase)

    val txOutputsView = TxOutputsView(sqliteDatabase)

    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    suspend fun debugQuery(query: String): String =
        withContext(SdkDispatchers.DATABASE_IO) {
            val cursor = sqliteDatabase.query(query)
            buildString {
                cursor.use { cursor ->
                    val columnCount = cursor.columnCount

                    // Append column names as header
                    val columnNames =
                        (0 until columnCount)
                            .map { index -> cursor.getColumnName(index) }
                    appendLine(columnNames.joinToString(" | "))
                    appendLine("-".repeat(columnNames.sumOf { name -> name.length + 3 }))

                    // Append rows
                    while (cursor.moveToNext()) {
                        val row =
                            (0 until columnCount).map { index ->
                                when (cursor.getType(index)) {
                                    FIELD_TYPE_NULL -> {
                                        "null"
                                    }

                                    FIELD_TYPE_INTEGER -> {
                                        cursor.getLongOrNull(index).toString()
                                    }

                                    FIELD_TYPE_FLOAT -> {
                                        cursor.getDoubleOrNull(index).toString()
                                    }

                                    FIELD_TYPE_STRING -> {
                                        cursor.getStringOrNull(index)
                                    }

                                    FIELD_TYPE_BLOB -> {
                                        val columnName = cursor.getColumnName(index)
                                        val blob = cursor.getBlobOrNull(index)
                                        when (columnName.lowercase()) {
                                            "txid" -> {
                                                blob?.let {
                                                    TransactionId(FirstClassByteArray(it)).txIdString()
                                                }
                                            }

                                            "memo" -> {
                                                blob
                                                    ?.let { MemoContent.fromBytes(it) }
                                                    ?.toStringOrNull() ?: blob?.toHex() ?: "null"
                                            }

                                            else -> {
                                                blob?.toHex()
                                            }
                                        }
                                    }

                                    else -> {
                                        "unknown"
                                    }
                                }
                            }
                        appendLine(row.joinToString(" | "))
                    }
                }
            }
        }

    suspend fun close() =
        withContext(SdkDispatchers.DATABASE_IO) {
            sqliteDatabase.close()
        }

    suspend fun isClosed(): Boolean =
        withContext(SdkDispatchers.DATABASE_IO) {
            !sqliteDatabase.isOpen
        }

    companion object {
        // Database migrations are managed by librustzcash.  This is a hard-coded value to ensure that Android's
        // SqliteOpenHelper is happy
        private const val DATABASE_VERSION = 8

        @Suppress("LongParameterList")
        suspend fun new(
            context: Context,
            backend: TypesafeBackend,
            databaseFile: File,
            checkpoint: Checkpoint,
            recoverUntil: BlockHeight?,
            setup: AccountCreateSetup?,
        ): DerivedDataDb {
            backend.initDataDb(setup?.seed)

            val database =
                ReadOnlySupportSqliteOpenHelper.openExistingDatabaseAsReadOnly(
                    NoBackupContextWrapper(
                        context,
                        databaseFile.parentFile!!
                    ),
                    databaseFile,
                    DATABASE_VERSION
                )

            val dataDb = DerivedDataDb(database)

            // If a seed is provided, and the wallet database does not contain the primary seed account, we approach
            // adding it. Note that this is subject to refactoring once we support a fully multi-account wallet.
            if (setup != null && backend.getAccounts().isEmpty()) {
                runCatching {
                    backend.createAccountAndGetSpendingKey(
                        accountName = setup.accountName,
                        keySource = setup.keySource,
                        recoverUntil = recoverUntil,
                        seed = setup.seed,
                        treeState = checkpoint.treeState()
                    )
                }.onFailure {
                    Twig.error(it) { "Create account failed." }
                    throw InitializeException.CreateAccountException(it)
                }.onSuccess {
                    Twig.debug { "The creation of account: $it was successful." }
                }
            }

            return dataDb
        }
    }
}
