//! `readQuery` — the DEBUG-ONLY host read lane, kept for `Synchronizer.debugQuery` after the
//! production read path moved to the 5 typed `host_read` exports (`host_read.rs`), which
//! return constructed model objects instead of JSON. Added while the engine owner was away to
//! close a dual-SQLite-instance hazard (worklog `08-engine-sigbus-android.md`): the app ran
//! TWO independent SQLite library instances against one `data.sqlite3` in one process — the
//! engine's rusqlite (bundled SQLite; bionic's system SQLite isn't NDK-linkable) and the
//! Android-framework SQLite the host reader used to open directly. SQLite's same-process lock
//! coordination (`unixInodeInfo` fd tracking) only works within ONE library instance; across
//! two instances, every `close()` by the framework reader drops ALL of the process's fcntl
//! locks on the file — including the engine's WAL locks — so the engine's WAL/`-shm` index
//! (the one thing SQLite always mmaps) gets modified/reset out from under it. `readQuery`
//! routes host reads through THIS crate's own bundled rusqlite instead, so the host and the
//! engine share ONE SQLite library and its lock coordination works as designed. `host_read.rs`
//! shares that same fix via [`open_read_only`], which this module also uses.
//!
//! NOT engine-contract surface: no C-ABI twin, no handle, no engine state — a plain
//! read-only SELECT over the same DB file the handle already owns. Not versioned with
//! HOSTING.md; not documented in the JNI binding contract. Behavior is otherwise unchanged
//! from the emergency original — only its role (debug-only, not the production reader) moved.

use anyhow::anyhow;
use std::time::Duration;

/// [SlipstreamWalletDb]'s own busy_timeout law (`SlipstreamWalletDb.kt`), mirrored here since
/// this replaces it as the host's sole DB read path.
const BUSY_TIMEOUT_MS: u64 = 5_000;

/// Opens `db_path` read-only against THIS crate's own bundled SQLite instance (same
/// instance/library as the engine's own writer — see this module's doc comment for why that
/// matters), with the shared host-read connection law: `SQLITE_OPEN_READ_ONLY` and
/// `busy_timeout` 5 s (HOSTING.md/FFI_JNI_CONTRACT.md §7.3). Used by both `readQuery` below
/// and every `host_read.rs` export.
pub(crate) fn open_read_only(db_path: &str) -> anyhow::Result<rusqlite::Connection> {
    let conn = rusqlite::Connection::open_with_flags(
        db_path,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|e| anyhow!("read-only open: {e}"))?;
    conn.busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS))
        .map_err(|e| anyhow!("read-only busy_timeout: {e}"))?;
    Ok(conn)
}

/// Executes `sql` as a single read-only query against `db_path`'s bundled SQLite instance,
/// with at most one of `blob_param`/`text_param` bound as the statement's sole `?1`
/// parameter, and returns the rows as a JSON array of arrays: INTEGER/REAL → JSON number,
/// TEXT → JSON string, BLOB → lowercase-hex JSON string, NULL → JSON null. The connection is
/// read-only at the SQLite level (`SQLITE_OPEN_READ_ONLY`) — this utility can only ever
/// SELECT, regardless of what `sql` a caller passes.
pub(crate) fn read_query(
    db_path: &str,
    sql: &str,
    blob_param: Option<&[u8]>,
    text_param: Option<&str>,
) -> anyhow::Result<String> {
    if blob_param.is_some() && text_param.is_some() {
        return Err(anyhow!(
            "readQuery: blobParam and textParam are mutually exclusive"
        ));
    }

    let conn = open_read_only(db_path)?;

    let mut stmt = conn
        .prepare(sql)
        .map_err(|e| anyhow!("readQuery prepare: {e}"))?;
    let column_count = stmt.column_count();
    let mut rows = match (blob_param, text_param) {
        (Some(b), None) => stmt.query(rusqlite::params![b]),
        (None, Some(t)) => stmt.query(rusqlite::params![t]),
        (None, None) => stmt.query([]),
        (Some(_), Some(_)) => unreachable!("checked above"),
    }
    .map_err(|e| anyhow!("readQuery query: {e}"))?;

    let mut rows_json: Vec<serde_json::Value> = Vec::new();
    while let Some(row) = rows.next().map_err(|e| anyhow!("readQuery row: {e}"))? {
        let mut cols: Vec<serde_json::Value> = Vec::with_capacity(column_count);
        for i in 0..column_count {
            let value = match row
                .get_ref(i)
                .map_err(|e| anyhow!("readQuery column {i}: {e}"))?
            {
                rusqlite::types::ValueRef::Null => serde_json::Value::Null,
                rusqlite::types::ValueRef::Integer(n) => serde_json::Value::from(n),
                rusqlite::types::ValueRef::Real(f) => serde_json::Number::from_f64(f)
                    .map(serde_json::Value::Number)
                    .unwrap_or(serde_json::Value::Null),
                rusqlite::types::ValueRef::Text(t) => serde_json::Value::String(
                    std::str::from_utf8(t)
                        .map_err(|e| anyhow!("readQuery column {i} is not valid UTF-8: {e}"))?
                        .to_string(),
                ),
                rusqlite::types::ValueRef::Blob(b) => {
                    serde_json::Value::String(b.iter().map(|byte| format!("{byte:02x}")).collect())
                }
            };
            cols.push(value);
        }
        rows_json.push(serde_json::Value::Array(cols));
    }

    serde_json::to_string(&rows_json).map_err(|e| anyhow!("readQuery serialize: {e}"))
}
