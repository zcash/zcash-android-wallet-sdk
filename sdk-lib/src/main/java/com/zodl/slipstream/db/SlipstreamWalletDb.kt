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
 * and closes its own connection within the single JNI call.
 *
 * This is the FOURTH connection strategy tried for the Slipstream reader, after three prior
 * designs each failed on-device:
 *
 * Round-2 incident (the reason attempt 3, below, no longer went through
 * `ReadOnlySupportSqliteOpenHelper`): that helper needs `android_metadata`, which Android's
 * framework open only creates via a hidden *writable* attempt. Against the engine's always-on
 * WAL writer that attempt is a race, not a one-off - a lost race corrupted `android_metadata`
 * (invalid rootpage), and on the *next* open Android's `DefaultDatabaseErrorHandler` treated
 * that corruption as license to delete the database file outright, taking the live wallet DB
 * with it. The engine then failed to open, self-revived, re-created the DB from scratch, and
 * re-synced. `ReadOnlySupportSqliteOpenHelper` is safe only for short-lived-writer databases
 * (the classic engine); it is not safe against Slipstream's always-on writer.
 *
 * Attempt 1, persistent `OPEN_READONLY` (WAL read-mark pinning): a memoized connection, opened
 * once and reused for every subsequent call, was tried first. A WAL reader's snapshot is pinned
 * at open time by the read-mark it takes in the `-shm` index, and only a connection that can
 * advance that read-mark ever sees a newer one. SQLite maps the `-shm` file read-only for a
 * connection opened `OPEN_READONLY`, so that connection can never advance its own read-mark -
 * it is wedged on whatever snapshot existed at open time for as long as the connection lives,
 * even as the engine's writer keeps appending frames. That is exactly what was observed
 * on-device: `queryVisible` returned a frozen transaction set mid-sync while the underlying DB
 * kept growing, and the diagnostic clue was that killing/restarting the app - a fresh
 * connection, a fresh read-mark - revealed everything immediately. That restart behavior
 * proved the staleness was connection-level, not data-level: the data was always there, only
 * this one pinned connection couldn't see it.
 *
 * Attempt 2, persistent `OPEN_READWRITE` (framework journal-mode collision): switching the same
 * memoized connection to `OPEN_READWRITE` let it map `-shm` read-write and advance its own
 * read-mark, fixing the staleness above - but Android's SQLite framework enforces its own
 * journal-mode handling on any connection it considers read-write (it skips this entirely for
 * connections it considers read-only). That framework-driven journal-mode management fought
 * the engine's own WAL writer for exclusive locks. On-device this wedged the engine's writer
 * outright: the stall watchdog fired 111 times and sync got stuck, confirmed by the user on a
 * real device.
 *
 * Attempt 3, short-lived framework `OPEN_READONLY` per call: a connection was opened fresh for
 * every reader operation and closed before that operation returned. This fixed both attempt-1
 * staleness (a fresh open means a fresh read-mark every time) and attempt-2's lock war (staying
 * `OPEN_READONLY` means Android never touches journal mode on the connection at all) - but it
 * was still a SECOND, independent SQLite library instance against the same file (incident #5,
 * below), which no per-call discipline can fix from the framework side.
 *
 * Round-4 incident (attempt 3's own leak-proofing, kept here as a design record even though
 * attempt 3 is gone): a prior shape had `openForReading` open the connection and apply the
 * `busy_timeout` pragma with `.also { }` before returning it, leaving the caller to query and
 * close it separately. That shape had two leak windows StrictMode caught as
 * `LeakedClosableViolation`s pointing at the open site. First, if the pragma step itself threw,
 * the already-opened connection was never reached by any `close()`. Second, and more commonly
 * given the flatMapLatest-driven cancellation in the owning repo flow: the connection was
 * returned *out of* the `withContext` block that opened it, so a cancellation landing exactly
 * at that boundary made `withContext` throw instead of returning normally, and the connection
 * it had already opened was discarded unclosed. Both windows shared one property: a connection
 * crossed a suspension/cancellation boundary while still owned by nobody in particular. Attempt
 * 3's fix - open, use, and close inside a single `try`/`finally` in one `withContext` - closed
 * that gap structurally, and [query] keeps the same discipline (the native side does its own
 * open/use/close in one JNI call, so there is no Kotlin-visible connection to leak at all).
 *
 * Incident #5 (SIGBUS, the reason attempt 3 - and `android.database.sqlite.*` in this object
 * at all - is retired): three deterministic `SIGBUS BUS_ADRERR` crashes on-device, identical
 * top frames on both `tokio-rt-worker` (a pure engine thread) and `slipstream-io` (the host
 * JNI-call thread), including during idle tip-following with no host call in flight.
 * Symbolization (`worklog/08-engine-sigbus-android.md`) resolved the shared frames into the
 * engine's OWN bundled SQLite: `walFindFrame` / `sqlite3WalFindFrame` / `readDbPage` /
 * `getPageNormal` / `sqlite3PagerGet` / `getAndInitPage` - the WAL/pager code that reads the
 * `-shm` index SQLite always mmaps. Root cause: attempt 3's framework `SQLiteDatabase` was a
 * SECOND, independent SQLite library instance against the SAME `data.sqlite3` the engine's
 * bundled rusqlite writes (bionic has no NDK-linkable system SQLite, so the engine links its
 * own bundled copy - a documented SQLite hazard: never open one database file with two SQLite
 * libraries in one process). SQLite's same-process lock coordination (`unixInodeInfo` fd
 * tracking) only works WITHIN one library instance; across two instances, every framework
 * `close()` - happening on every single per-call connection attempt 3 opened - drops ALL of
 * the process's fcntl locks on the file, including the engine's own WAL locks, corrupting the
 * engine's WAL/`-shm` index out from under it while it was mmapped. THE RULE GOING FORWARD:
 * the Android framework SQLite (`android.database.sqlite.*`) must NEVER open the engine's
 * `data.sqlite3` again, in this object or anywhere else. Every host read now goes through
 * [SlipstreamNative.readQuery] - the engine's OWN bundled SQLite instance, so host and engine
 * share one library and SQLite's lock coordination works as designed.
 */
object SlipstreamWalletDb {
    /**
     * Runs [sql] as a single read-only query against [dbFile] on the engine's bundled SQLite
     * instance, with at most one of [blobParam]/[textParam] non-null bound as the statement's
     * sole `?1` parameter - see this object's KDoc, especially incident #5, for why no
     * connection is ever opened through the Android framework. Returns the rows as a
     * [JSONArray] of arrays (columns per [SlipstreamNative.readQuery]'s encoding).
     */
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
