package com.zodl.slipstream.db

import com.zodl.slipstream.SlipstreamNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Read-only access to the engine-managed data.db, routed through the engine's OWN bundled
 * SQLite instance ([SlipstreamNative.readQuery]) - never `android.database.sqlite.*`. LAW:
 * [query] executes exactly one SQL statement per call; no connection, cursor, or handle from
 * this object is ever memoized, lazily opened, or reused across calls - the native side opens
 * and closes its own read-only connection within the single JNI call.
 *
 * [query] backs `Synchronizer.debugQuery` ONLY; production reads go through the 5 typed
 * `SlipstreamNative` host-read exports instead.
 *
 * THE RULE: the Android framework SQLite (`android.database.sqlite.*`) must never open the
 * engine's `data.sqlite3`, in this object or anywhere else. A second, independent SQLite
 * library instance against the same file corrupts the engine's WAL locks.
 */
object SlipstreamWalletDb {
    /** DEBUG-ONLY: runs [sql] against [dbFile] with at most one of [blobParam]/[textParam] bound as `?1`. */
    suspend fun query(
        dbFile: File,
        sql: String,
        blobParam: ByteArray? = null,
        textParam: String? = null
    ): JSONArray =
        withContext(Dispatchers.IO) {
            val json = SlipstreamNative.readQuery(dbFile.absolutePath, sql, blobParam, textParam)
            JSONArray(json ?: "[]")
        }
}
