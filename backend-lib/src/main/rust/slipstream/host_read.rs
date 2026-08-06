//! `host_read` — the 5 typed production host-read exports (FFI_JNI_CONTRACT.md §2/§4.2/§9.3),
//! replacing the JSON `readQuery` lane (`read_query.rs`, now debug-only) as
//! `SlipstreamTransactionReader`'s read path. Each export constructs `com.zodl.slipstream.model`
//! objects field-by-field — the same JNI-constructs-objects rule §4.1 already uses for
//! `snapshot`/`walletSummary` — instead of crossing a JSON string. SQL text below was moved
//! VERBATIM from the Kotlin reader it replaces (no query rewritten in the port), except
//! `listTransactions`' projection, which since 2026-08-03 also selects `zip318_kind` (see
//! [`has_zip318_kind_column`] — the column librustzcash's not-yet-released zip318-classification
//! work adds to `v_transactions`; degrades to a literal `0` when absent) — appended as the LAST
//! column/ctor arg so the JNI binding contract's existing field order stays untouched.
//!
//! Connections are opened via `read_query::open_read_only` — the same bundled rusqlite
//! instance the engine's own writer uses (`SQLITE_OPEN_READ_ONLY`, `busy_timeout` 5 s; see
//! that module's doc comment for why sharing the instance matters). Row loops build their
//! owned Rust values first, then construct the JNI array/objects in a second pass inside
//! `env.with_local_frame` per row (bounds the local-reference table; `summary.rs`'s per-account
//! loop only warns about this — this is the port that actually does it).

use anyhow::anyhow;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jobject, jobjectArray};

use rusqlite::{OptionalExtension, Row};

use super::read_query;
use super::{catch_unwind, java_string_to_rust, unwrap_exc_or};

// JNI class descriptors + constructor signatures for the 4 new host-read models (the JNI
// binding contract §4.2). Ctor arg ORDER matches the `@Keep data class` declarations exactly.
const JNI_TX_ROW: &str = "com/zodl/slipstream/model/SlipstreamTransactionRow";
const JNI_RAW_TX: &str = "com/zodl/slipstream/model/SlipstreamRawTransaction";
const JNI_TX_OUTPUT_ROW: &str = "com/zodl/slipstream/model/SlipstreamTxOutputRow";
const JNI_RESUBMISSION_ROW: &str = "com/zodl/slipstream/model/SlipstreamResubmissionRow";

const TX_ROW_CTOR: &str = "([BLjava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;[BJJJLjava/lang/Long;ZIIILjava/lang/Long;ZLjava/lang/Long;I)V";
const RAW_TX_CTOR: &str = "([BJ)V";
const TX_OUTPUT_ROW_CTOR: &str = "([BIILjava/lang/String;[B)V";
const RESUBMISSION_ROW_CTOR: &str = "([B[B)V";

/// One `v_transactions` row, in `SlipstreamTransactionRow` ctor field order.
struct TxRow {
    tx_id: Vec<u8>,
    mined_height: Option<i64>,
    expiry_height: Option<i64>,
    tx_index: Option<i64>,
    raw: Option<Vec<u8>>,
    account_balance_delta: i64,
    total_spent: i64,
    total_received: i64,
    fee_paid: Option<i64>,
    has_change: bool,
    sent_note_count: i32,
    received_note_count: i32,
    memo_count: i32,
    block_time: Option<i64>,
    is_shielding: bool,
    is_expired_unmined: Option<i64>,
    /// How the transaction classifies against ZIP 318 (the Orchard-to-Ironwood pool migration),
    /// as `transactions.zip318_kind` decodes it — see `Zip318Kind` on the Kotlin side. Appended
    /// as the LAST field (never reordered — the JNI binding contract keys ctor args by position).
    zip318_kind: i32,
}

impl TxRow {
    fn from_row(row: &Row) -> anyhow::Result<Self> {
        Ok(Self {
            tx_id: row.get(0)?,
            mined_height: row.get(1)?,
            expiry_height: row.get(2)?,
            tx_index: row.get(3)?,
            raw: row.get(4)?,
            account_balance_delta: row.get(5)?,
            total_spent: row.get(6)?,
            total_received: row.get(7)?,
            fee_paid: row.get(8)?,
            has_change: row.get(9)?,
            sent_note_count: row.get(10)?,
            received_note_count: row.get(11)?,
            memo_count: row.get(12)?,
            block_time: row.get(13)?,
            is_shielding: row.get(14)?,
            is_expired_unmined: row.get(15)?,
            zip318_kind: row.get(16)?,
        })
    }
}

/// One `v_tx_outputs` row, in `SlipstreamTxOutputRow` ctor field order.
struct TxOutputRow {
    tx_id: Vec<u8>,
    output_index: i32,
    output_pool: i32,
    to_address: Option<String>,
    to_account_uuid: Option<Vec<u8>>,
}

impl TxOutputRow {
    fn from_row(row: &Row) -> anyhow::Result<Self> {
        Ok(Self {
            tx_id: row.get(0)?,
            output_index: row.get(1)?,
            output_pool: row.get(2)?,
            to_address: row.get(3)?,
            to_account_uuid: row.get(4)?,
        })
    }
}

/// Boxes an optional `i64` as `java/lang/Long`, or `JObject::null()` for `None` — the
/// nullable-scalar idiom for row fields with no primitive "absent" sentinel (SQL NULL).
fn boxed_long<'local>(
    env: &mut JNIEnv<'local>,
    value: Option<i64>,
) -> anyhow::Result<JObject<'local>> {
    match value {
        Some(v) => Ok(env.new_object("java/lang/Long", "(J)V", &[JValue::Long(v)])?),
        None => Ok(JObject::null()),
    }
}

/// `Option<&[u8]> -> [B` or `null`.
fn optional_bytes<'local>(
    env: &mut JNIEnv<'local>,
    value: &Option<Vec<u8>>,
) -> anyhow::Result<JObject<'local>> {
    match value {
        Some(bytes) => Ok(env.byte_array_from_slice(bytes)?.into()),
        None => Ok(JObject::null()),
    }
}

/// `Option<&str> -> java/lang/String` or `null`.
fn optional_string<'local>(
    env: &mut JNIEnv<'local>,
    value: &Option<String>,
) -> anyhow::Result<JObject<'local>> {
    match value {
        Some(s) => Ok(env.new_string(s)?.into()),
        None => Ok(JObject::null()),
    }
}

fn tx_row_object<'local>(env: &mut JNIEnv<'local>, row: &TxRow) -> anyhow::Result<JObject<'local>> {
    let tx_id = env.byte_array_from_slice(&row.tx_id)?;
    let mined_height = boxed_long(env, row.mined_height)?;
    let expiry_height = boxed_long(env, row.expiry_height)?;
    let tx_index = boxed_long(env, row.tx_index)?;
    let raw = optional_bytes(env, &row.raw)?;
    let fee_paid = boxed_long(env, row.fee_paid)?;
    let block_time = boxed_long(env, row.block_time)?;
    let is_expired_unmined = boxed_long(env, row.is_expired_unmined)?;
    Ok(env.new_object(
        JNI_TX_ROW,
        TX_ROW_CTOR,
        &[
            (&tx_id).into(),
            JValue::Object(&mined_height),
            JValue::Object(&expiry_height),
            JValue::Object(&tx_index),
            JValue::Object(&raw),
            JValue::Long(row.account_balance_delta),
            JValue::Long(row.total_spent),
            JValue::Long(row.total_received),
            JValue::Object(&fee_paid),
            JValue::Bool(u8::from(row.has_change)),
            JValue::Int(row.sent_note_count),
            JValue::Int(row.received_note_count),
            JValue::Int(row.memo_count),
            JValue::Object(&block_time),
            JValue::Bool(u8::from(row.is_shielding)),
            JValue::Object(&is_expired_unmined),
            JValue::Int(row.zip318_kind),
        ],
    )?)
}

fn tx_output_row_object<'local>(
    env: &mut JNIEnv<'local>,
    row: &TxOutputRow,
) -> anyhow::Result<JObject<'local>> {
    let tx_id = env.byte_array_from_slice(&row.tx_id)?;
    let to_address = optional_string(env, &row.to_address)?;
    let to_account_uuid = optional_bytes(env, &row.to_account_uuid)?;
    Ok(env.new_object(
        JNI_TX_OUTPUT_ROW,
        TX_OUTPUT_ROW_CTOR,
        &[
            (&tx_id).into(),
            JValue::Int(row.output_index),
            JValue::Int(row.output_pool),
            JValue::Object(&to_address),
            JValue::Object(&to_account_uuid),
        ],
    )?)
}

/// Whether the given table/view exposes `zip318_kind` — the column the librustzcash
/// zip318-classification patch adds (not yet in the crates.io release this crate otherwise
/// depends on). Checked at runtime, once per `listTransactions` call (scoped to the specific
/// table that will be queried), rather than assumed from a compile-time feature flag, so this
/// code is safe to ship independent of whether that patch happens to be pinned in `Cargo.toml`
/// right now — the same "column may be absent" posture `AllTransactionView.kt` already takes
/// on the Kotlin side of the ordinary (non-Slipstream) reader. Crucially, this checks the
/// ACTUAL table being queried, not a hardcoded one — e.g. if a future schema state has
/// `zip318_kind` on `v_transactions` but not on `v_transactions_with_pending_migrations`,
/// this will return `false` for the latter and prevent a no-fallback prepare failure (see
/// [`has_pending_migrations_view`] for the defense-in-depth pattern this implements).
fn has_zip318_kind_column(conn: &rusqlite::Connection, table_name: &str) -> bool {
    conn.prepare(&format!("SELECT zip318_kind FROM {table_name} LIMIT 0"))
        .is_ok()
}

/// Whether the open connection's schema has the pool-migration extension's
/// `v_transactions_with_pending_migrations` view (added by `zcash_pool_migration`'s schema
/// migration; absent on a DB that hasn't run it yet, or on a librustzcash pin from before the
/// view existed). Same runtime-checked, not compile-time-assumed, posture as
/// [`has_zip318_kind_column`] just above — safe to ship independent of exactly which
/// pin/migration-state a given wallet DB is in. When present, `listTransactions` reads through
/// it instead of the plain `v_transactions` so a migration transaction becomes visible in
/// Activity as soon as it's built (`AwaitingSignature`/`Signed`/`Proved` — i.e. its inputs are
/// already effectively spent), not only once actually broadcast. See
/// `z/wt/migration_fixes/spec/2026-08-06-overnight-review-report.md` finding #2 and the
/// 2026-08-05 core-sync-call spec §6 for the product context.
fn has_pending_migrations_view(conn: &rusqlite::Connection) -> bool {
    // Checks the FULL column shape `list_transactions_sql` actually projects, not just that a
    // view with this name exists — a future upstream schema change that renames/drops one of
    // these columns must make this return false (falling back to the plain `v_transactions`
    // path) rather than let the real query fail to `prepare()` at call time, which would empty
    // out the whole Activity list with no fallback. Mirrors `has_zip318_kind_column`'s posture
    // one level more defensively, since that one only ever gates a single trailing column.
    conn.prepare(
        "SELECT txid, mined_height, expiry_height, tx_index, raw, account_balance_delta, \
         total_spent, total_received, fee_paid, has_change, sent_note_count, \
         received_note_count, memo_count, block_time, is_shielding, expired_unmined, \
         account_uuid FROM v_transactions_with_pending_migrations LIMIT 0",
    )
    .is_ok()
}

#[cfg(test)]
mod view_existence_tests {
    use super::*;
    use rusqlite::Connection;

    #[test]
    fn has_zip318_kind_column_is_false_on_a_bare_connection() {
        let conn = Connection::open_in_memory().unwrap();
        assert!(!has_zip318_kind_column(&conn, "v_transactions"));
    }

    #[test]
    fn has_pending_migrations_view_is_false_on_a_bare_connection() {
        let conn = Connection::open_in_memory().unwrap();
        assert!(!has_pending_migrations_view(&conn));
    }

    #[test]
    fn has_pending_migrations_view_is_true_once_the_full_column_shape_exists() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE VIEW v_transactions_with_pending_migrations AS
             SELECT NULL AS txid, NULL AS mined_height, NULL AS expiry_height,
                    NULL AS tx_index, NULL AS raw, 0 AS account_balance_delta,
                    0 AS total_spent, 0 AS total_received, NULL AS fee_paid,
                    0 AS has_change, 0 AS sent_note_count, 0 AS received_note_count,
                    0 AS memo_count, NULL AS block_time, 0 AS is_shielding,
                    NULL AS expired_unmined, NULL AS account_uuid",
        )
        .unwrap();
        assert!(has_pending_migrations_view(&conn));
    }

    #[test]
    fn has_pending_migrations_view_is_false_when_a_projected_column_is_missing() {
        // Regression guard for the failure mode the full-projection check exists to catch: a
        // same-named view with a DIFFERENT shape (e.g. missing `account_uuid`, as if a future
        // upstream change dropped/renamed it) must degrade to false, not silently pass.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE VIEW v_transactions_with_pending_migrations AS SELECT 1 AS txid",
        )
        .unwrap();
        assert!(!has_pending_migrations_view(&conn));
    }

    #[test]
    fn has_zip318_kind_column_correctly_checks_the_target_view_not_hardcoded_table() {
        // Regression guard: if v_transactions has zip318_kind but
        // v_transactions_with_pending_migrations does not (or vice versa), the existence check
        // must evaluate each view independently. This is the failure mode the scoped check exists
        // to prevent: if has_zip318_kind_column blindly checked v_transactions, it could return
        // true and list_transactions_sql could emit SQL with a column that doesn't exist on the
        // actually-queried v_transactions_with_pending_migrations view, causing prepare() to
        // fail and emptying the Activity list with no fallback.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE VIEW v_transactions AS SELECT 1 AS txid, 3 AS zip318_kind;
             CREATE VIEW v_transactions_with_pending_migrations AS SELECT 1 AS txid;",
        )
        .unwrap();
        // v_transactions has zip318_kind, but v_transactions_with_pending_migrations does not
        assert!(has_zip318_kind_column(&conn, "v_transactions"));
        assert!(!has_zip318_kind_column(
            &conn,
            "v_transactions_with_pending_migrations"
        ));
    }
}

#[cfg(test)]
mod list_transactions_sql_tests {
    use super::*;

    #[test]
    fn uses_pending_migrations_view_when_present() {
        let sql = list_transactions_sql(false, false, true, true);
        assert!(sql.contains("FROM v_transactions_with_pending_migrations AS tx"));
    }

    #[test]
    fn falls_back_to_plain_view_when_pending_migrations_view_absent() {
        let sql = list_transactions_sql(false, false, true, false);
        assert!(sql.contains("FROM v_transactions AS tx"));
        assert!(!sql.contains("v_transactions_with_pending_migrations"));
    }

    #[test]
    fn zip318_kind_and_pending_migrations_flags_are_independent() {
        let sql = list_transactions_sql(false, false, false, true);
        assert!(sql.contains(", 0 FROM v_transactions_with_pending_migrations AS tx"));
    }

    #[test]
    fn recovering_and_account_filter_still_compose_with_pending_migrations_view() {
        let sql = list_transactions_sql(true, true, true, true);
        assert!(sql.starts_with(
            "SELECT tx.txid, tx.mined_height, tx.expiry_height, tx.tx_index, tx.raw, \
             tx.account_balance_delta, tx.total_spent, tx.total_received, tx.fee_paid, \
             tx.has_change, tx.sent_note_count, tx.received_note_count, tx.memo_count, \
             tx.block_time, tx.is_shielding, tx.expired_unmined, tx.zip318_kind \
             FROM v_transactions_with_pending_migrations AS tx"
        ));
        assert!(sql.contains("LEFT JOIN"));
        assert!(sql.contains("COALESCE(r.reconciled, 1) = 1"));
        assert!(sql.contains("tx.account_uuid = ?"));
    }
}

#[cfg(test)]
mod list_transactions_execution_tests {
    use super::*;
    use rusqlite::Connection;

    /// Minimal DDL mirroring the real pinned schema's relevant columns only — enough to run
    /// `list_transactions_sql`'s query end to end, not a full replica of the upstream schema.
    fn seeded_connection() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE VIEW v_transactions AS
             SELECT X'aa' AS txid, 100 AS mined_height, 0 AS expiry_height, 0 AS tx_index,
                    X'bb' AS raw, -100 AS account_balance_delta, 100 AS total_spent,
                    0 AS total_received, 100 AS fee_paid, 0 AS has_change,
                    1 AS sent_note_count, 0 AS received_note_count, 0 AS memo_count,
                    1_700_000_000 AS block_time, 0 AS is_shielding, 0 AS expired_unmined,
                    3 AS zip318_kind, X'cc' AS account_uuid;
             CREATE VIEW v_transactions_with_pending_migrations AS
             SELECT * FROM v_transactions
             UNION ALL
             SELECT X'dd' AS txid, NULL AS mined_height, 500 AS expiry_height,
                    NULL AS tx_index, NULL AS raw, -50 AS account_balance_delta,
                    5000 AS total_spent, 4950 AS total_received, 50 AS fee_paid,
                    0 AS has_change, 0 AS sent_note_count, 1 AS received_note_count,
                    0 AS memo_count, NULL AS block_time, 0 AS is_shielding,
                    0 AS expired_unmined, 3 AS zip318_kind, X'cc' AS account_uuid;",
        )
        .unwrap();
        conn
    }

    #[test]
    fn plain_view_query_prepares_and_returns_the_one_ordinary_row() {
        let conn = seeded_connection();
        let sql = list_transactions_sql(false, false, true, false);
        let mut stmt = conn.prepare(&sql).unwrap();
        let rows: Vec<TxRow> = stmt
            .query_map([], |row| Ok(TxRow::from_row(row).unwrap()))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].mined_height, Some(100));
    }

    #[test]
    fn merged_view_query_prepares_and_returns_both_rows_with_nulls_intact() {
        let conn = seeded_connection();
        let sql = list_transactions_sql(false, false, true, true);
        let mut stmt = conn.prepare(&sql).unwrap();
        let rows: Vec<TxRow> = stmt
            .query_map([], |row| Ok(TxRow::from_row(row).unwrap()))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        assert_eq!(rows.len(), 2);
        let pending = rows.iter().find(|r| r.mined_height.is_none()).unwrap();
        assert_eq!(pending.raw, None);
        assert_eq!(pending.tx_index, None);
        assert_eq!(pending.zip318_kind, 3);
        let mined = rows.iter().find(|r| r.mined_height.is_some()).unwrap();
        assert_eq!(mined.mined_height, Some(100));
        assert_eq!(mined.raw, Some(vec![0xbb]));
    }
}

/// Builds `listTransactions`' SQL: base projection, an optional reconciliation LEFT JOIN +
/// filter when `is_recovering`, an optional account filter, ORDER BY. Verbatim from the
/// Kotlin `VisibleTransactionsQuery` this replaces — see FFI_JNI_CONTRACT.md §9.3 — except the
/// trailing `zip318_kind` projection (see [`has_zip318_kind_column`]), added 2026-08-03: a
/// literal `0` (== `Zip318Kind.NOT_CLASSIFIED`) stands in when the column doesn't exist yet.
/// Also selects FROM `v_transactions_with_pending_migrations` instead of the plain
/// `v_transactions` when that view exists (see [`has_pending_migrations_view`], added
/// 2026-08-06) — this is the ONLY difference between the two source views' column shapes that
/// matters here: the migration-pending branch supplies `NULL` for `raw`/`mined_height`/
/// `tx_index`/`block_time`/`trust_status` and real (never-null) values for everything else,
/// including `zip318_kind`. Every column this SELECT projects is already read through a
/// nullable accessor on both the Rust (`TxRow::from_row`, `Option<...>` fields) and Kotlin
/// (`TransactionOverviewCursor.fromRow`) sides, so no downstream code changes were needed.
fn list_transactions_sql(
    is_recovering: bool,
    has_account_filter: bool,
    has_zip318_kind: bool,
    has_pending_migrations_view: bool,
) -> String {
    let zip318_kind_projection = if has_zip318_kind {
        "tx.zip318_kind"
    } else {
        "0"
    };
    let table_name = if has_pending_migrations_view {
        "v_transactions_with_pending_migrations"
    } else {
        "v_transactions"
    };
    let mut sql = format!(
        "SELECT tx.txid, tx.mined_height, tx.expiry_height, tx.tx_index, tx.raw, \
         tx.account_balance_delta, tx.total_spent, tx.total_received, tx.fee_paid, \
         tx.has_change, tx.sent_note_count, tx.received_note_count, tx.memo_count, \
         tx.block_time, tx.is_shielding, tx.expired_unmined, {zip318_kind_projection} FROM {table_name} AS tx",
    );
    if is_recovering {
        sql.push_str(&format!(
            " LEFT JOIN {} AS r ON r.txid = tx.txid",
            slipstream_core::reconcile::RECONCILE_VIEW_NAME
        ));
    }
    let mut conditions: Vec<&str> = Vec::new();
    if is_recovering {
        conditions.push("COALESCE(r.reconciled, 1) = 1");
    }
    if has_account_filter {
        conditions.push("tx.account_uuid = ?");
    }
    if !conditions.is_empty() {
        sql.push_str(" WHERE ");
        sql.push_str(&conditions.join(" AND "));
    }
    sql.push_str(" ORDER BY IFNULL(tx.mined_height, 4294967295) DESC, tx.tx_index DESC");
    sql
}

/// `listTransactions`: the visible-transactions read (FFI_JNI_CONTRACT.md §7.1), optionally
/// scoped to one account. `isRecovering` selects the reconciled-only filter; a null
/// `accountUuid` returns every account's rows.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_listTransactions<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    is_recovering: jboolean,
    account_uuid: JByteArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let account_uuid_bytes: Option<Vec<u8>> = if account_uuid.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&account_uuid)?)
        };
        let conn = read_query::open_read_only(&db_path)?;

        let has_pending_migrations = has_pending_migrations_view(&conn);
        let table_name = if has_pending_migrations {
            "v_transactions_with_pending_migrations"
        } else {
            "v_transactions"
        };
        let has_zip318_kind = has_zip318_kind_column(&conn, table_name);
        let sql = list_transactions_sql(
            is_recovering != 0,
            account_uuid_bytes.is_some(),
            has_zip318_kind,
            has_pending_migrations,
        );
        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| anyhow!("listTransactions prepare: {e}"))?;
        let mut rows = match account_uuid_bytes.as_deref() {
            Some(uuid) => stmt.query(rusqlite::params![uuid]),
            None => stmt.query([]),
        }
        .map_err(|e| anyhow!("listTransactions query: {e}"))?;

        let mut buffered: Vec<TxRow> = Vec::new();
        while let Some(row) = rows
            .next()
            .map_err(|e| anyhow!("listTransactions row: {e}"))?
        {
            buffered.push(TxRow::from_row(row)?);
        }
        drop(rows);
        drop(stmt);
        drop(conn);

        let arr = env.new_object_array(buffered.len() as jint, JNI_TX_ROW, JObject::null())?;
        for (i, row) in buffered.into_iter().enumerate() {
            env.with_local_frame(16, |env| -> anyhow::Result<()> {
                let obj = tx_row_object(env, &row)?;
                env.set_object_array_element(&arr, i as jint, &obj)?;
                Ok(())
            })?;
        }
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `getTransactionRaw`: the raw bytes + expiry height for one txid, or Kotlin `null` if the
/// transaction is not stored.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_getTransactionRaw<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    txid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let txid_bytes = env.convert_byte_array(&txid)?;
        let conn = read_query::open_read_only(&db_path)?;

        // `v_transactions` is the public, versioned query surface (like every other read in this
        // module) — never the wallet-internal `transactions` base table. The view has one row per
        // involved account; `raw`/`expiry_height` are identical across them, so take the first.
        let found: Option<(Vec<u8>, i64)> = conn
            .query_row(
                "SELECT raw, expiry_height FROM v_transactions WHERE txid = ? LIMIT 1",
                rusqlite::params![txid_bytes],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|e| anyhow!("getTransactionRaw query: {e}"))?;
        drop(conn);

        match found {
            Some((raw, expiry_height)) => {
                let raw_arr = env.byte_array_from_slice(&raw)?;
                let obj = env.new_object(
                    JNI_RAW_TX,
                    RAW_TX_CTOR,
                    &[(&raw_arr).into(), JValue::Long(expiry_height)],
                )?;
                Ok(obj.into_raw())
            }
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `listTransactionOutputs`: ALL of a transaction's `v_tx_outputs` rows for one txid, or —
/// when `txid` is null — every account's rows (used by the outputs AND recipients reads).
/// No `is_change` filter, matching iOS's `TransactionDao.getTransactionOutputs(for:)` exactly:
/// change/wallet-internal rows are included, so a self-transfer (pool migration) surfaces its
/// account-tagged rows (`to_account_uuid` set) and, where recorded, the stored receiving
/// address. Recipient selection/preference is the host's concern.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_listTransactionOutputs<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    txid: JByteArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let txid_bytes: Option<Vec<u8>> = if txid.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&txid)?)
        };
        let conn = read_query::open_read_only(&db_path)?;

        let sql = match txid_bytes {
            Some(_) => {
                "SELECT txid, output_index, output_pool, to_address, to_account_uuid \
                 FROM v_tx_outputs WHERE txid = ? ORDER BY output_index ASC"
            }
            None => {
                "SELECT txid, output_index, output_pool, to_address, to_account_uuid \
                 FROM v_tx_outputs ORDER BY txid ASC, output_index ASC"
            }
        };
        let mut stmt = conn
            .prepare(sql)
            .map_err(|e| anyhow!("listTransactionOutputs prepare: {e}"))?;
        let mut rows = match txid_bytes.as_deref() {
            Some(id) => stmt.query(rusqlite::params![id]),
            None => stmt.query([]),
        }
        .map_err(|e| anyhow!("listTransactionOutputs query: {e}"))?;

        let mut buffered: Vec<TxOutputRow> = Vec::new();
        while let Some(row) = rows
            .next()
            .map_err(|e| anyhow!("listTransactionOutputs row: {e}"))?
        {
            buffered.push(TxOutputRow::from_row(row)?);
        }
        drop(rows);
        drop(stmt);
        drop(conn);

        let arr =
            env.new_object_array(buffered.len() as jint, JNI_TX_OUTPUT_ROW, JObject::null())?;
        for (i, row) in buffered.into_iter().enumerate() {
            env.with_local_frame(8, |env| -> anyhow::Result<()> {
                let obj = tx_output_row_object(env, &row)?;
                env.set_object_array_element(&arr, i as jint, &obj)?;
                Ok(())
            })?;
        }
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `findTransactionsByMemo`: txids whose memo contains `substring` (case-insensitive).
/// Wildcarding moves here — the Kotlin reader no longer builds the `%...%` pattern.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_findTransactionsByMemo<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    substring: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let substring = java_string_to_rust(env, &substring)?;
        let pattern = format!("%{substring}%");
        let conn = read_query::open_read_only(&db_path)?;

        let mut stmt = conn
            .prepare("SELECT txid FROM v_tx_outputs WHERE LOWER(memo) LIKE LOWER(?)")
            .map_err(|e| anyhow!("findTransactionsByMemo prepare: {e}"))?;
        let mut rows = stmt
            .query(rusqlite::params![pattern])
            .map_err(|e| anyhow!("findTransactionsByMemo query: {e}"))?;

        let mut buffered: Vec<Vec<u8>> = Vec::new();
        while let Some(row) = rows
            .next()
            .map_err(|e| anyhow!("findTransactionsByMemo row: {e}"))?
        {
            buffered.push(row.get(0)?);
        }
        drop(rows);
        drop(stmt);
        drop(conn);

        let arr = env.new_object_array(buffered.len() as jint, "[B", JObject::null())?;
        for (i, txid) in buffered.into_iter().enumerate() {
            env.with_local_frame(4, |env| -> anyhow::Result<()> {
                let bytes = env.byte_array_from_slice(&txid)?;
                env.set_object_array_element(&arr, i as jint, &bytes)?;
                Ok(())
            })?;
        }
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `listResubmissionCandidates`: unmined, unexpired, outgoing transactions — the resubmission
/// ticker's candidate set. `chainTip` binds as INTEGER (no TEXT-affinity workaround).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_listResubmissionCandidates<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    chain_tip: jlong,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let conn = read_query::open_read_only(&db_path)?;

        let mut stmt = conn
            .prepare(
                "SELECT tx.txid, tx.raw FROM v_transactions AS tx WHERE tx.mined_height IS NULL \
                 AND tx.expiry_height > ? AND tx.account_balance_delta < 0",
            )
            .map_err(|e| anyhow!("listResubmissionCandidates prepare: {e}"))?;
        let mut rows = stmt
            .query(rusqlite::params![chain_tip])
            .map_err(|e| anyhow!("listResubmissionCandidates query: {e}"))?;

        let mut buffered: Vec<(Vec<u8>, Vec<u8>)> = Vec::new();
        while let Some(row) = rows
            .next()
            .map_err(|e| anyhow!("listResubmissionCandidates row: {e}"))?
        {
            buffered.push((row.get(0)?, row.get(1)?));
        }
        drop(rows);
        drop(stmt);
        drop(conn);

        let arr = env.new_object_array(
            buffered.len() as jint,
            JNI_RESUBMISSION_ROW,
            JObject::null(),
        )?;
        for (i, (tx_id, raw)) in buffered.into_iter().enumerate() {
            env.with_local_frame(8, |env| -> anyhow::Result<()> {
                let tx_id_arr = env.byte_array_from_slice(&tx_id)?;
                let raw_arr = env.byte_array_from_slice(&raw)?;
                let obj = env.new_object(
                    JNI_RESUBMISSION_ROW,
                    RESUBMISSION_ROW_CTOR,
                    &[(&tx_id_arr).into(), (&raw_arr).into()],
                )?;
                env.set_object_array_element(&arr, i as jint, &obj)?;
                Ok(())
            })?;
        }
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
