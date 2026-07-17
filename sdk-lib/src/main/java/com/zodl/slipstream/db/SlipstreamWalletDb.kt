package com.zodl.slipstream.db

import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only access to the engine-managed data.db. Twin of the upstream SDK's
 * `ReadOnlySupportSqliteOpenHelper` (internal there; `FFI_JNI_CONTRACT.md` section 7.3). LAW:
 * connections from this helper are read-only and set busy_timeout >= 5000 ms; the engine's
 * writer holds WAL mode.
 */
object SlipstreamWalletDb {
    /** Schema is owned by librustzcash/the engine; this only placates SQLiteOpenHelper. */
    private const val DATABASE_VERSION = 8
    private const val BUSY_TIMEOUT_MS = 5_000

    suspend fun openReadOnly(
        context: Context,
        dbFile: File
    ): SupportSQLiteDatabase =
        withContext(Dispatchers.IO) {
            val dir = requireNotNull(dbFile.parentFile) { "wallet db must have a parent directory" }
            val config =
                SupportSQLiteOpenHelper.Configuration
                    .builder(DatabaseDirContextWrapper(context, dir))
                    .name(dbFile.name)
                    .callback(EngineOwnedSchemaCallback(DATABASE_VERSION))
                    .build()
            FrameworkSQLiteOpenHelperFactory().create(config).readableDatabase.also { db ->
                db.query("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS").use { it.moveToFirst() }
            }
        }

    /**
     * txid (hex, lowercase) -> reconciled. Snapshot read; re-run per the txSetVersion re-query
     * rule (`FFI_JNI_CONTRACT.md` section 7.1).
     */
    fun readReconciled(db: SupportSQLiteDatabase): Map<String, Boolean> =
        buildMap {
            db.query("SELECT txid, reconciled FROM slipstream_v_tx_reconciled").use { cursor ->
                while (cursor.moveToNext()) {
                    val txid = cursor.getBlob(0).joinToString("") { byte -> "%02x".format(byte) }
                    put(txid, cursor.getLong(1) != 0L)
                }
            }
        }
}

private class DatabaseDirContextWrapper(
    context: Context,
    private val dir: File
) : ContextWrapper(context.applicationContext) {
    override fun getDatabasePath(name: String): File = File(dir, name)

    override fun getApplicationContext(): Context = this

    override fun getBaseContext(): Context = this
}

private class EngineOwnedSchemaCallback(
    version: Int
) : SupportSQLiteOpenHelper.Callback(version) {
    override fun onCreate(db: SupportSQLiteDatabase) {
        error("Database ${db.path} is created and migrated by the Slipstream engine")
    }

    override fun onUpgrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        error("Database ${db.path} is upgraded by the Slipstream engine")
    }
}
