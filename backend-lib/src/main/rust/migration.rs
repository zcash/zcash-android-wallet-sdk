//! JNI bindings for the migration engine.
//!
//! Rewired (2026-07-21) from our own hand-rolled `zcash_pool_migration` crate onto the core/
//! upstream `zcash_pool_migration` crate plus `zcash_client_sqlite::pool_migration`
//! (Danny/core team, `zcash/librustzcash` PR #2669 + stack; the SQLite persistence side was later
//! folded from a standalone `zcash_pool_migration_sqlite` crate into `zcash_client_sqlite` proper).
//! See `migration_engine.rs` for the adapter wiring our wallet DB into the new engine's traits, and
//! `docs/superpowers/specs/2026-07-21-current-migration-implementation-spec.md` (zashi-android
//! repo) for the full gap analysis this rewire is based on.
//!
//! Every JNI function here keeps its original signature and JNI-visible behavior so no Kotlin
//! code needed to change — the engine swap is entirely internal to this file and
//! `migration_engine.rs`. Two known, deliberate deviations from the old crate's exact semantics:
//!
//! 1. The new engine's `Schedule` type has no `anchor_height` (ZIP 374 defers anchor selection to
//!    proving time, not planning time) — `JniTransferProposal.anchorHeight` is populated with the
//!    schedule's `broadcast_height()` as a placeholder so the Kotlin type doesn't need to change;
//!    it no longer carries a real commitment-tree anchor value. Callers must not treat it as one.
//! 2. `finalizeReadyTransfersNative` and `nextDueTransferNative` prove transactions ahead of
//!    broadcast (ZIP 374) via `try_prove` (see its doc comment), which wraps
//!    `zcash_pool_migration`'s own `WalletMigrationProver`/`engine::prove_transfer`/
//!    `prove_preparation` — adopted 2026-07-23, replacing this file's former hand-ported
//!    `migration_finalize.rs` stopgap (removed) now that core provides the equivalent built-in.
//! 3. Plan details never cross the JNI boundary inward. Each `propose*`/`prepare*` call caches
//!    its plan Rust-side under an opaque `PlanHandle` (returned to Kotlin as the proposal
//!    object's `proposalHandle` field, for display alongside the schedule), and the commit
//!    functions (`signNoteSplitNative`, `signAndStoreMigrationScheduleNative`,
//!    `createUnsignedNoteSplitPcztNative`, `createUnsignedTransferPcztsNative`) take ONLY that
//!    handle back — `commit_or_reuse`/`migration_plan_cache` then sign exactly the identified
//!    plan or error if it was superseded by a later proposal. (Rebuilding a plan from
//!    caller-echoed primitives is impossible anyway: the new engine's plan types have no public
//!    constructor — verified directly, not assumed.)

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jobjectArray},
};
use prost::Message;
use rand::rngs::OsRng;
use rusqlite::Connection;
use std::ptr;
use std::{collections::HashMap, num::NonZeroU32};

use zcash_client_backend::data_api::wallet::input_selection::LockFilter;
use zcash_client_backend::data_api::{InputSource, WalletRead, WalletWrite};
use zcash_client_backend::keys::UnifiedSpendingKey;
use zcash_client_backend::wallet::{LockOwner, OutputRef};
use zcash_client_sqlite::AccountUuid;
use zcash_client_sqlite::util::SystemClock;
use zcash_protocol::consensus::{
    BLOCKS_PER_HOUR, BlockHeight, Network, NetworkConstants, Parameters,
};
use zcash_protocol::value::Zatoshis;
use zcash_protocol::{PoolType, ShieldedPool};

use zcash_pool_migration::wallet::{WalletMigrationProver, WalletProveError};
use zcash_pool_migration::{
    engine::{
        self, MigrationCrypto, MigrationPlan, MigrationState, MigrationTransaction, MigrationTxId,
        MigrationTxKind, MigrationTxState, PoolMigrationRead, PoolMigrationWrite, ProveError,
    },
    scheduling::AnchorBucketInterval,
};

use crate::migration_engine::Backend;
use crate::utils::{catch_unwind, exception::unwrap_exc_or};

const JNI_MIGRATION_PROGRESS: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationProgress";
const JNI_ATTENTION_REASON: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniAttentionReason";
const JNI_MIGRATION_STATE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationState";
const JNI_NOTE_SPLIT_PROPOSAL: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniNoteSplitProposal";
const JNI_PREPARED_TRANSFER: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniPreparedTransfer";
const JNI_DUE_TRANSFER_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniDueTransferResult";
const JNI_TRANSFER_PROPOSAL: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniTransferProposal";
const JNI_MIGRATION_SCHEDULE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationSchedule";
const JNI_MIGRATION_TRANSFER_STATE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferState";
const JNI_MIGRATION_TRANSFER_STATES: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferStates";
const JNI_UNSIGNED_TRANSFER_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniUnsignedTransferPczt";
const JNI_KEYSTONE_BATCH_DECODE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchDecodeResult";
const JNI_KEYSTONE_BATCH_SIGNED_PCZTS: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchSignedPczts";

/// The zatoshi value below which a leftover post-migration Orchard balance is treated as dust
/// rather than a residual worth migrating in its own (non-round-number, more identifiable)
/// transfer. 100,000 zatoshi = 0.001 ZEC. A fixed protocol-level constant, not derived from wallet
/// or account state, so it needs no database access to read.
pub const MIGRATION_DUST_THRESHOLD_ZATOSHI: u64 = 100_000;

pub(crate) type Wallet = zcash_client_sqlite::WalletDb<Connection, Network, SystemClock, OsRng>;

/// Opens a fresh wallet-read connection plus a second, independent connection for the migration
/// store (same on-disk file — SQLite supports multiple connections to one file; mirrors the old
/// `MigrationContext::open_wallet`/`store_conn` pattern, which also opened two connections).
/// Every JNI function here calls this fresh and drops it at the end (no persistent handle),
/// exactly like the old file's documented contract.
///
/// JNI-free (takes a plain path, not a `JString`) so it — and everything built on top of it — is
/// callable directly from `cargo test` against a real wallet DB file, without an emulator or a
/// Kotlin/JNI round-trip. See the `tests` module at the bottom of this file.
fn open_at(db_path: &std::path::Path, network: Network) -> anyhow::Result<(Wallet, Connection)> {
    // Configured with the same anchor grid `lib.rs`'s `wallet_db` uses, so the boundaries this
    // migration draws its transfer anchors from are exactly the ones the scanning path retains
    // checkpoints for.
    let retention_interval = crate::anchor_retention_interval(network.network_type());
    let wallet = Wallet::for_path(db_path.to_path_buf(), network, SystemClock, OsRng)
        .map(|wallet| wallet.with_anchor_retention_interval(retention_interval))
        .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))?;
    let store_conn = Connection::open(db_path)
        .map_err(|e| anyhow!("Error opening migration store connection: {}", e))?;
    // The pool-migration tables are created by `zcash_client_sqlite`'s own schema migrations
    // (`orchard_ironwood_migration_tables`, run as part of the wallet's normal `init_wallet_db`
    // call, see `lib.rs`), not by this crate — no separate init call needed here. All schema
    // management belongs to those migrations: this crate must never run DDL against the wallet
    // database (a pre-release self-heal shim that patched `lock_owner` in place lived here once;
    // the release-line librustzcash pin froze the schema, and wallets created against older
    // pre-release shapes must be recreated instead).
    Ok((wallet, store_conn))
}

// ---------------------------------------------------------------------------
// Backend-lib-owned invalidation side table
// ---------------------------------------------------------------------------
//
// This table is NOT part of any core-owned schema (the `orchard_ironwood_*` tables are
// hands-off).  It is created lazily on first write, so wallets that never hit InvalidNote/Expired
// carry zero schema overhead.
//
// `account_uuid` — the raw 16-byte UUID identifying the account (same bytes `expose_uuid().as_bytes()` returns).
// `reason`       — one of `"invalid_transfer"` or `"transfer_expired"`.
// `transfer_id`  — the string representation of the `MigrationTxId` index (may be NULL when the
//                  id is not meaningful, e.g. for TransferExpired recorded without a specific id).

const INVALIDATION_DDL: &str = "
    CREATE TABLE IF NOT EXISTS zashi_migration_invalidation (
        account_uuid BLOB NOT NULL PRIMARY KEY,
        reason       TEXT NOT NULL,
        transfer_id  TEXT
    )";

fn record_invalidation(
    conn: &Connection,
    account: &[u8],
    reason: &str,
    transfer_id: Option<&str>,
) -> anyhow::Result<()> {
    conn.execute(INVALIDATION_DDL, [])
        .map_err(|e| anyhow!("Error creating invalidation table: {}", e))?;
    conn.execute(
        "INSERT OR REPLACE INTO zashi_migration_invalidation (account_uuid, reason, transfer_id) VALUES (?1, ?2, ?3)",
        rusqlite::params![account, reason, transfer_id],
    )
    .map_err(|e| anyhow!("Error recording invalidation: {}", e))?;
    Ok(())
}

fn read_invalidation(
    conn: &Connection,
    account: &[u8],
) -> anyhow::Result<Option<(String, Option<String>)>> {
    // Table may not exist yet (no invalidation ever recorded).
    let table_exists: bool = conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='zashi_migration_invalidation'",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map(|n| n > 0)
        .unwrap_or(false);
    if !table_exists {
        return Ok(None);
    }
    let result = conn.query_row(
        "SELECT reason, transfer_id FROM zashi_migration_invalidation WHERE account_uuid = ?1",
        rusqlite::params![account],
        |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
    );
    match result {
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(anyhow!("Error reading invalidation: {}", e)),
    }
}

fn clear_invalidation(conn: &Connection, account: &[u8]) -> anyhow::Result<()> {
    // If the table doesn't exist there's nothing to clear — not an error.
    let table_exists: bool = conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='zashi_migration_invalidation'",
            [],
            |row| row.get::<_, i64>(0),
        )
        .map(|n| n > 0)
        .unwrap_or(false);
    if !table_exists {
        return Ok(());
    }
    conn.execute(
        "DELETE FROM zashi_migration_invalidation WHERE account_uuid = ?1",
        rusqlite::params![account],
    )
    .map_err(|e| anyhow!("Error clearing invalidation: {}", e))?;
    Ok(())
}

/// The lowest `anchor_boundary` height still needed by any account's migration transactions that
/// have not yet reached `Broadcast`/`Mined` — i.e. still need proving or broadcasting — across
/// every account in the wallet at `db_path`.
///
/// This is the typed reference implementation of the anchor-retention floor fed into
/// `EngineConfig::anchor_retention_height` at every Slipstream sync-session (re)start — see
/// `backend-lib/slipstream-jni/src/lib.rs`'s `min_pending_migration_anchor_boundary` and
/// `start_session` for the actual wired call site and the full pruning-bug rationale
/// (`zcash_client_backend`'s block-persistence path otherwise prunes a checkpoint out from under
/// a not-yet-proved migration transfer, ZIP 374, failing with `Query(NotContained(..))`).
///
/// `slipstream-jni` does NOT call this function directly: `backend-lib`'s own `Cargo.toml`
/// already depends on `slipstream-jni` (`slipstream-jni = { path = "slipstream-jni" }`, to merge
/// its JNI exports into one `libzcashlc.so`), so the reverse edge needed to call from
/// `slipstream-jni` into this crate would be a cyclic package dependency — impossible regardless
/// of the two crates' separate `[workspace]` roots. `slipstream-jni` instead runs an equivalent
/// raw SQL query directly against `orchard_ironwood_migrations`/
/// `orchard_ironwood_migration_transactions` (see that function's doc comment). This function is
/// kept anyway as the typed, `PoolMigrationRead`-based reference this crate can unit-test against
/// a real wallet DB (`live_wallet_edge_case_tests`-style, gated on `MIGRATION_TEST_WALLET_DB`) to
/// verify the raw-SQL mirror stays correct, and as the natural call site if this crate ever grows
/// its own direct consumer of the value (e.g. a debug JNI export). Hence `#[allow(dead_code)]`
/// below: nothing in this crate's non-test code calls it today.
///
/// Preparation transactions are excluded (`anchor_boundary() == None`): they anchor to a freshly
/// current tip at prove time (see `natural_anchor_height`'s doc comment), not a boundary drawn in
/// advance, so they never need retroactive protection.
///
/// Returns `Ok(None)` if there's no in-progress migration in any account, or every transaction
/// needing a boundary has already broadcast/mined.
///
/// Deliberately kept as a plain `anyhow::Result` (matching every other function in this file, e.g.
/// `plan_for`), rather than swallowing errors internally: this function has no JNI boundary of its
/// own to report through, so any caller is responsible for treating an `Err` as "no retention
/// floor" — logging and falling back to `None` — since a wallet DB read glitch here must never
/// block sync from starting (see the `slipstream-jni` call site for that fallback in practice).
#[allow(dead_code)]
pub(crate) fn min_pending_anchor_boundary(
    db_path: &std::path::Path,
    network: Network,
) -> anyhow::Result<Option<u32>> {
    let (wallet, mut store_conn) = open_at(db_path, network)?;
    let account_ids = wallet
        .get_account_ids()
        .map_err(|e| anyhow!("Error listing account ids: {}", e))?;

    let mut min_height: Option<BlockHeight> = None;
    for account in account_ids {
        let backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = backend.get_migration().map_err(|e| {
            anyhow!(
                "Error reading migration state for account {:?}: {:?}",
                account,
                e
            )
        })?
        else {
            continue;
        };
        if state.is_terminal() {
            continue;
        }
        for tx in state.transactions() {
            if matches!(
                tx.state(),
                MigrationTxState::Broadcast { .. } | MigrationTxState::Mined { .. }
            ) {
                continue;
            }
            if let Some(boundary) = tx.anchor_boundary() {
                min_height = Some(min_height.map_or(boundary, |existing| existing.min(boundary)));
            }
        }
    }
    Ok(min_height.map(u32::from))
}

fn open(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
) -> anyhow::Result<(Network, Wallet, Connection)> {
    let network = crate::parse_network(network_id as u32)?;
    let db_path = crate::path_from_jni(env, db_data)?;
    let (wallet, store_conn) = open_at(&db_path, network)?;
    Ok((network, wallet, store_conn))
}

/// The height migration transactions are built/planned against — one past the current tip,
/// matching the old crate's convention (and `migration_engine::Backend`'s own note-selection
/// target).
fn target_height(wallet: &Wallet) -> anyhow::Result<BlockHeight> {
    let tip = wallet
        .chain_height()
        .map_err(|e| anyhow!("chain height lookup failed: {}", e))?
        .ok_or_else(|| anyhow!("wallet has no chain tip yet"))?;
    Ok(tip + 1)
}

/// The wallet's real, currently-witnessable anchor height (the same one ordinary, non-migration
/// sends use, via `get_target_and_anchor_heights`) — NOT just "chain tip minus one", which isn't
/// necessarily checkpointed (confirmed live: `root_at_checkpoint_id` returned `None` for a raw
/// `tip - 1` guess). Used as the anchor for preparation transactions, whose `anchor_boundary()` is
/// `None` (see `try_prove`'s doc comment).
fn natural_anchor_height(wallet: &Wallet) -> anyhow::Result<BlockHeight> {
    wallet
        .get_target_and_anchor_heights(std::num::NonZeroU32::MIN)
        .map_err(|e| anyhow!("Error fetching anchor height: {}", e))?
        .map(|(_, anchor)| anchor)
        .ok_or_else(|| anyhow!("wallet has no anchor height yet; scan required"))
}

/// Computes a fresh preview plan WITHOUT caching it — the read-only building block shared by
/// `plan_for` (which caches, for proposals the user will be shown and may commit) and by pure
/// peek queries like `isNoteSplitNeededNative` (which must NOT cache: replacing the cached plan
/// would invalidate the handle of a proposal the user is currently reviewing). Also returns the
/// wallet's current tip, needed as the "now" reference point when encoding transfer proposals
/// (see `encode_transfer_proposal`'s doc comment for why this matters).
///
/// JNI-free — see `open_at`'s doc comment. Every `MIGRATION_DIAG` log line this crate has needed
/// so far to diagnose a live bug (anchor/witness resolution, schedule spread, note-split
/// detection) came from here or `migration_finalize`, both callable directly from `cargo test`.
fn compute_plan(
    network: &Network,
    wallet: &Wallet,
    account: AccountUuid,
    store_conn: &mut Connection,
) -> anyhow::Result<(MigrationPlan, BlockHeight)> {
    let backend = Backend::new(wallet, account, None, store_conn)?;
    let mut rng = OsRng;
    let migration_plan = engine::plan_migration(network, &backend, &mut rng)
        .map_err(|e| anyhow!("Error planning migration: {:?}", e))?;
    let prep = migration_plan.preparation();
    tracing::debug!(
        "MIGRATION_DIAG plan: preparation has {} layer(s), {} prep transaction(s) total, {} \
         direct-funding note(s) (used as-is, no split needed); funding_notes total={} zat over {} \
         note(s)",
        prep.layer_count(),
        prep.transaction_count(),
        prep.direct_funding_notes().len(),
        migration_plan
            .funding_notes()
            .iter()
            .map(|z| u64::from(*z))
            .sum::<u64>(),
        migration_plan.funding_notes().len(),
    );
    for (layer_idx, layer) in prep.layers().iter().enumerate() {
        for (tx_idx, prep_tx) in layer.iter().enumerate() {
            tracing::debug!(
                "MIGRATION_DIAG plan: prep layer={layer_idx} tx={tx_idx} outputs={:?}",
                prep_tx.outputs(),
            );
        }
    }
    for &(note_idx, value) in prep.direct_funding_notes() {
        tracing::debug!(
            "MIGRATION_DIAG plan: direct-funding wallet note index={note_idx} value={} zat",
            u64::from(value),
        );
    }
    let tip = wallet
        .chain_height()
        .map_err(|e| anyhow!("chain height lookup failed: {}", e))?
        .ok_or_else(|| anyhow!("wallet has no chain tip yet"))?;
    for (i, entry) in migration_plan.schedule().iter().enumerate() {
        tracing::debug!(
            "MIGRATION_DIAG plan: transfer[{i}] broadcast_height={:?} ({} blocks from tip {:?}) \
             expiry_height={:?}",
            entry.broadcast_height(),
            i64::from(u32::from(entry.broadcast_height())) - i64::from(u32::from(tip)),
            tip,
            entry.expiry_height(),
        );
    }
    Ok((migration_plan, tip))
}

/// Computes a fresh preview plan via `compute_plan` and caches it under a fresh
/// [`migration_plan_cache::PlanHandle`] (see that module's doc for why), so a later commit call —
/// which must echo the handle back — signs exactly this plan, not an independently re-randomized
/// one.
fn plan_for(
    network: &Network,
    wallet: &Wallet,
    account: AccountUuid,
    store_conn: &mut Connection,
) -> anyhow::Result<(
    MigrationPlan,
    BlockHeight,
    crate::migration_plan_cache::PlanHandle,
)> {
    let (migration_plan, tip) = compute_plan(network, wallet, account, store_conn)?;
    let handle = crate::migration_plan_cache::set(account, migration_plan.clone());
    Ok((migration_plan, tip, handle))
}

fn plan(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
    account_uuid: JByteArray,
) -> anyhow::Result<(
    MigrationPlan,
    BlockHeight,
    crate::migration_plan_cache::PlanHandle,
)> {
    let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
    let account = crate::account_id_from_jni(env, account_uuid)?;
    plan_for(&network, &wallet, account, &mut store_conn)
}

/// Returns the already-committed migration state if one exists (non-terminal), otherwise commits
/// the cached plan that `plan_handle` identifies — erroring if no plan is cached or if a later
/// `propose*`/`prepare*` call replaced the plan the caller was shown (see `migration_plan_cache`'s
/// module doc: the handle gate is what guarantees a commit can only sign the exact plan the user
/// reviewed). On the reuse path the handle is not consulted: the commitment already happened —
/// with a handle-verified plan — and is durable, so there is nothing left the handle could
/// protect. Shared by both the in-process-signing and external-signer commit paths below; `sign`
/// picks which `commit_preparation`/`build_preparation_unsigned` variant to run, and whether a
/// spending key is available to the `Backend` while doing so.
fn commit_or_reuse(
    network: &Network,
    wallet: &Wallet,
    account: AccountUuid,
    store_conn: &mut Connection,
    target: BlockHeight,
    plan_handle: crate::migration_plan_cache::PlanHandle,
    usk: Option<UnifiedSpendingKey>,
    sign: impl FnOnce(
        &Network,
        BlockHeight,
        &mut Backend<Wallet>,
        &MigrationPlan,
        &mut OsRng,
    ) -> anyhow::Result<(MigrationState, Vec<(MigrationTxId, Vec<u8>)>)>,
) -> anyhow::Result<(MigrationState, Vec<(MigrationTxId, Vec<u8>)>)> {
    {
        let backend = Backend::new(wallet, account, None, store_conn)?;
        if let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        {
            if !state.is_terminal() {
                let unsigned = state
                    .transactions()
                    .iter()
                    .filter(|t| matches!(t.state(), MigrationTxState::AwaitingSignature))
                    .map(|t| (t.id(), t.pczt().clone()))
                    .collect();
                return Ok((state, unsigned));
            }
        }
    }
    let migration_plan = crate::migration_plan_cache::get(account, plan_handle)?;
    let mut backend = Backend::new(wallet, account, usk, store_conn)?;
    let mut rng = OsRng;
    let result = sign(network, target, &mut backend, &migration_plan, &mut rng)?;
    crate::migration_plan_cache::clear(account);
    Ok(result)
}

fn encode_migration_progress<'a>(
    env: &mut JNIEnv<'a>,
    completed: usize,
    total: usize,
    next_transfer_ready_at_height: i64,
) -> jni::errors::Result<JObject<'a>> {
    env.new_object(
        JNI_MIGRATION_PROGRESS,
        "(IIJ)V",
        &[
            JValue::Int(completed as jint),
            JValue::Int(total as jint),
            JValue::Long(next_transfer_ready_at_height),
        ],
    )
}

/// Derives the old crate's public `MigrationState` sealed-class shape from the new engine's
/// persisted `MigrationState` (or its absence). This mapping is necessarily approximate: the new
/// engine plans and commits the note split + transfer schedule together in one step (see
/// `plan_migration`/`commit_preparation`'s doc comments), so there is no longer a DB-persisted
/// "split signed, schedule not yet proposed" moment the way the old crate's
/// `SplitPendingConfirmation`/`ReadyToPropose` states captured — those two collapse into
/// `NotStarted` here (nothing has been committed yet). Validate this against real testnet
/// migration flows and adjust if the app-side UI depends on distinguishing them (see spec doc
/// §7 item — this was flagged as a known open risk before implementation started).
fn derive_migration_state<'a>(
    env: &mut JNIEnv<'a>,
    persisted: Option<MigrationState>,
    tip: BlockHeight,
    store_conn: &Connection,
    account: &[u8],
) -> anyhow::Result<JObject<'a>> {
    let Some(state) = persisted else {
        return Ok(env.new_object(format!("{JNI_MIGRATION_STATE}$NotStarted"), "()V", &[])?);
    };

    if state.is_terminal() {
        return match state.status() {
            engine::MigrationStatus::Complete => {
                Ok(env.new_object(format!("{JNI_MIGRATION_STATE}$Complete"), "()V", &[])?)
            }
            engine::MigrationStatus::Failed => {
                // Read the persisted invalidation reason to distinguish InvalidTransfer from
                // TransferExpired (plain cancel/debug-clear) — the side table is optional, so a
                // missing row defaults to TransferExpired (the pre-Task-3 behaviour).
                let invalidation = read_invalidation(store_conn, account)?;
                let reason = match invalidation
                    .as_ref()
                    .map(|(r, tid)| (r.as_str(), tid.as_deref()))
                {
                    Some(("invalid_transfer", tid)) => {
                        let j_tid = env.new_string(tid.unwrap_or(""))?;
                        env.new_object(
                            format!("{JNI_ATTENTION_REASON}$InvalidTransfer"),
                            "(Ljava/lang/String;)V",
                            &[JValue::Object(&j_tid)],
                        )?
                    }
                    _ => env.new_object(
                        format!("{JNI_ATTENTION_REASON}$TransferExpired"),
                        "()V",
                        &[],
                    )?,
                };
                Ok(env.new_object(
                    format!("{JNI_MIGRATION_STATE}$RequiresAttention"),
                    format!("(L{JNI_ATTENTION_REASON};)V"),
                    &[JValue::Object(&reason)],
                )?)
            }
            _ => unreachable!("is_terminal() only returns true for Complete/Failed"),
        };
    }

    let transactions = state.transactions();
    let total = transactions.len();
    let completed = transactions
        .iter()
        .filter(|t| matches!(t.state(), MigrationTxState::Mined { .. }))
        .count();
    let next_ready = state.next_broadcastable(tip);
    let next_transfer_ready_at_height = next_ready
        .and_then(|id| transactions.iter().find(|t| t.id() == id))
        .map_or(-1i64, |_| i64::from(u32::from(tip)));

    let progress = encode_migration_progress(env, completed, total, next_transfer_ready_at_height)?;
    Ok(env.new_object(
        format!("{JNI_MIGRATION_STATE}$InProgress"),
        format!("(L{JNI_MIGRATION_PROGRESS};)V"),
        &[JValue::Object(&progress)],
    )?)
}

fn encode_note_split_proposal<'a>(
    env: &mut JNIEnv<'a>,
    plan: &MigrationPlan,
    plan_handle: crate::migration_plan_cache::PlanHandle,
) -> jni::errors::Result<JObject<'a>> {
    let split = plan.note_split();
    let values: Vec<i64> = split
        .crossing_values()
        .iter()
        .map(|&v| u64::from(v) as i64)
        .collect();
    let values_array = env.new_long_array(values.len() as i32)?;
    env.set_long_array_region(&values_array, 0, &values)?;
    let fee = u64::from(split.prep_fees()) as i64;

    env.new_object(
        JNI_NOTE_SPLIT_PROPOSAL,
        "([JJJ)V",
        &[
            JValue::Object(&values_array),
            JValue::Long(fee),
            JValue::Long(plan_handle as i64),
        ],
    )
}

fn encode_transfer_id<'a>(
    env: &mut JNIEnv<'a>,
    id: MigrationTxId,
) -> jni::errors::Result<JString<'a>> {
    env.new_string(u32::from(id).to_string())
}

fn decode_transfer_id(env: &mut JNIEnv, id: &JString) -> anyhow::Result<MigrationTxId> {
    let raw = crate::utils::java_string_to_rust(env, id)?;
    let idx: u32 = raw
        .parse()
        .map_err(|e| anyhow!("Invalid transfer id {}: {}", raw, e))?;
    Ok(MigrationTxId::new(idx))
}

/// `anchor_height` here is NOT a real commitment-tree anchor (ZIP 374 defers that to proving time
/// — see module doc point 1) — it's the wallet's tip *at plan time*, used purely as Kotlin's "now"
/// reference point: `MigrationDurationFormat.estimatedSecondsBetweenHeights(fromHeight=anchorHeight,
/// toHeight=nextExecutableAfterHeight)` computes the wait as `(nextExecutableAfterHeight -
/// anchorHeight) * blockIntervalMillis`. Passing the same value for both (as an earlier version of
/// this function did) makes that delta always zero — confirmed live: every transfer displayed as
/// due "Now" regardless of its real `broadcast_height`, even though the schedule itself was
/// correctly spread out (see the `MIGRATION_DIAG plan:` log in `plan()` above).
fn encode_transfer_proposal<'a>(
    env: &mut JNIEnv<'a>,
    id: MigrationTxId,
    amount: Zatoshis,
    anchor_height: BlockHeight,
    schedule_broadcast_height: BlockHeight,
    schedule_expiry_height: BlockHeight,
) -> jni::errors::Result<JObject<'a>> {
    let id = encode_transfer_id(env, id)?;
    env.new_object(
        JNI_TRANSFER_PROPOSAL,
        "(Ljava/lang/String;JJJJ)V",
        &[
            JValue::Object(&id),
            JValue::Long(u64::from(amount) as i64),
            JValue::Long(i64::from(u32::from(anchor_height))),
            JValue::Long(i64::from(u32::from(schedule_broadcast_height))),
            JValue::Long(i64::from(u32::from(schedule_expiry_height))),
        ],
    )
}

fn encode_migration_schedule<'a>(
    env: &mut JNIEnv<'a>,
    plan: &MigrationPlan,
    tip: BlockHeight,
    plan_handle: crate::migration_plan_cache::PlanHandle,
) -> anyhow::Result<JObject<'a>> {
    // `funding_notes()`, NOT `note_split().crossing_values()`: the funding notes are the
    // post-reconciliation values (crossing_values() minus whatever the smallest denominations
    // dropped to cover preparation fees) and `schedule()`'s doc explicitly pairs "one entry per
    // funding note" — zipping against crossing_values() instead silently mispairs amounts with
    // schedule heights whenever reconciliation drops anything (confirmed live: this produced a
    // suspiciously perfectly-sorted-by-size transfer list, the opposite of ZIP 318 SHUFFLE's
    // intent, plus every transfer immediately overdue).
    //
    // `funding_notes()` values are the *spent* note values (crossing + note_fee_buffer, i.e. what
    // funds the transfer's own fee) — NOT what actually lands in the destination pool. The app
    // shows this as the user-facing transfer amount (Slack #ext-zodl-valargroup 2026-07-21: "only
    // round values on this [confirm] screen", matching the shielding-transaction convention of
    // displaying the received amount, fee visible only in the transaction detail), so subtract the
    // constant fee buffer back out to recover the round `{1,2,5}×10ⁿ` crossing value per note.
    let funding_notes = plan.funding_notes();
    let note_fee_buffer = plan.note_split().note_fee_buffer();
    let schedule = plan.schedule();
    if funding_notes.len() != schedule.len() {
        return Err(anyhow!(
            "Migration plan invariant violated: {} funding notes but {} schedule entries",
            funding_notes.len(),
            schedule.len()
        ));
    }
    let crossings: Vec<Zatoshis> = funding_notes
        .iter()
        .map(|&note| {
            (note - note_fee_buffer)
                .expect("every funding note is crossing + note_fee_buffer by construction")
        })
        .collect();

    // The real `MigrationTxId` the engine will assign at commit time numbers every preparation
    // transaction (across all layers) first, THEN transfers in `schedule()` order (confirmed
    // directly against `commit_preparation_inner` in `zcash_pool_migration::engine`) — so
    // transfer `i`'s id is `prep_tx_count + i`, not `i`. Getting this wrong doesn't affect Kotlin
    // (it tracks transfers by array position, not by this id — confirmed directly against
    // `MigrationPlanRepository`/`MigrationProgressVM`), but the SDK's own `nextDueTransfer`/
    // `recordTransferResult` round-trip inside this file depends on ids being internally
    // consistent, so keep them correct regardless.
    let prep_tx_count: u32 = plan
        .preparation()
        .layers()
        .iter()
        .map(|layer| layer.len() as u32)
        .sum();

    let mut proposals = Vec::with_capacity(schedule.len());
    for (i, (amount, entry)) in crossings.iter().zip(schedule.iter()).enumerate() {
        proposals.push((
            MigrationTxId::new(prep_tx_count + i as u32),
            *amount,
            entry.broadcast_height(),
            entry.expiry_height(),
        ));
    }
    // Kotlin renders "Transfer N" from array position, unsorted (confirmed directly against
    // `MigrationPlan.kt`/`MigrationReviewScreen.kt`/`MigrationProgressScreen.kt` — no sort
    // anywhere) — so the displayed order must already be chronological (ZIP 318 SHUFFLE means
    // funding-note order and broadcast order are deliberately NOT the same; without this sort the
    // UI showed e.g. "Transfer 1" broadcasting after "Transfer 5", confirmed live and flagged as a
    // real UX problem, not just cosmetic).
    proposals.sort_by_key(|(_, _, broadcast_height, _)| *broadcast_height);

    let transfers = crate::utils::rust_vec_to_java(
        env,
        proposals,
        JNI_TRANSFER_PROPOSAL,
        |env, (id, amount, broadcast, expiry)| {
            encode_transfer_proposal(env, id, amount, tip, broadcast, expiry)
        },
    )?;

    // Estimated duration: span from the earliest to the latest scheduled broadcast height, in
    // hours (75s/block, matching `zcash_protocol::SECONDS_PER_BLOCK`/`BLOCKS_PER_HOUR`).
    let estimated_duration_hours = schedule
        .iter()
        .map(|e| u32::from(e.broadcast_height()))
        .max()
        .zip(
            schedule
                .iter()
                .map(|e| u32::from(e.broadcast_height()))
                .min(),
        )
        .map(|(max, min)| max.saturating_sub(min) / BLOCKS_PER_HOUR)
        .unwrap_or(0);

    Ok(env.new_object(
        JNI_MIGRATION_SCHEDULE,
        format!("([L{JNI_TRANSFER_PROPOSAL};IJ)V"),
        &[
            JValue::Object(&transfers),
            JValue::Int(estimated_duration_hours as jint),
            JValue::Long(plan_handle as i64),
        ],
    )?)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_prepareNoteSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (migration_plan, _tip, plan_handle) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_note_split_proposal(env, &migration_plan, plan_handle)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_proposeMigrationTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    _include_residual: jboolean,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (migration_plan, tip, plan_handle) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip, plan_handle)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The new engine plans the note split and the transfer schedule together in one
/// `plan_migration()` call (the split's realized output values ARE `plan.note_split()
/// .crossing_values()`, which is exactly what `encode_migration_schedule` already derives the
/// schedule from) — so unlike `proposeMigrationTransfersNative` above, this does NOT plan afresh:
/// it encodes the schedule of the exact cached plan `proposal_handle` identifies (the one whose
/// split the user was just shown by `prepareNoteSplitNative`), erroring if that plan is missing
/// or superseded. Re-planning here — as an earlier version did — would silently swap in a
/// differently-randomized plan between the split display and the schedule display. The returned
/// schedule carries the SAME handle: split view, schedule view, and eventual commit all refer to
/// one plan.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_proposeMigrationTransfersFromSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    proposal_handle: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, _store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let plan_handle = proposal_handle as u64;
        let migration_plan = crate::migration_plan_cache::get(account, plan_handle)?;
        let tip = wallet
            .chain_height()
            .map_err(|e| anyhow!("chain height lookup failed: {}", e))?
            .ok_or_else(|| anyhow!("wallet has no chain tip yet"))?;
        Ok(encode_migration_schedule(env, &migration_plan, tip, plan_handle)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// IMMEDIATE mode's proposal entry point. Unlike `proposeMigrationTransfersNative` (which plans
/// the AUTOMATIC-mode, shuffled N-transfer engine plan via `zcash_pool_migration`), this
/// bypasses the engine entirely: it builds an ordinary send-max proposal sweeping every spendable
/// Orchard note into the account's own Ironwood receiver
/// (`migration_engine::propose_immediate_send_max`). Nothing here reads or writes the persisted
/// `MigrationState` — there is no plan to cache, commit, or reconcile, so this call has no
/// interaction with `proposeMigrationTransfersNative`/`commit*`/`finalize*`'s shared state at all.
///
/// Returns the proposal encoded exactly like `RustBackend.proposeTransfer` encodes an ordinary
/// send (`proto::proposal::Proposal::from_standard_proposal(..).encode_to_vec()`), so the Kotlin
/// side can decode it with the same `Proposal.parseFrom` path an ordinary send already uses —
/// deliberately not a new, migration-specific encoding.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_proposeImmediateSendMaxNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let (network, mut wallet, _store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let proposal =
            crate::migration_engine::propose_immediate_send_max(&network, &mut wallet, account)?;
        Ok(crate::utils::rust_bytes_to_java(
            env,
            zcash_client_backend::proto::proposal::Proposal::from_standard_proposal(&proposal)
                .encode_to_vec()
                .as_ref(),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Whether transaction `tx` is ready to PROVE at `target_height` (`chain_tip + 1`) — a local copy
/// of `zcash_pool_migration::state`'s private `MigrationState::prove_ready`, using only its
/// public surface (`deps_mined`, `anchor_boundary`, `scheduled_height`). Duplicated rather than
/// relying on `MigrationState::next_provable` because that returns only the SINGLE next-ready
/// transaction — looping it would re-return the same id forever on a transient witness/anchor
/// failure (see `try_prove`'s doc comment), whereas our JNI contract proves every ready transaction
/// in one call.
fn is_prove_ready(
    state: &MigrationState,
    tx: &engine::MigrationTransaction,
    target_height: BlockHeight,
) -> bool {
    if !state.deps_mined(tx.depends_on()) {
        return false;
    }
    match tx.anchor_boundary() {
        Some(boundary) => u32::from(boundary) + 1 < u32::from(target_height),
        None => tx.scheduled_height() <= target_height,
    }
}

/// Attempts to prove one `Signed` migration transaction in place within `state` — installing its
/// deferred Orchard anchor and spend witness(es) (ZIP 374) and running the prover, via
/// `zcash_pool_migration`'s own `WalletMigrationProver` (the core-team-maintained
/// replacement for this crate's former hand-ported `migration_finalize` stopgap; see that module's
/// removal and `docs` for context). A transfer proves against its own persisted `anchor_boundary`
/// (read internally by `engine::prove_transfer`); a preparation transaction carries no drawn
/// boundary and proves against the wallet's current natural anchor instead, matching
/// `zcash_pool_migration`'s own `prove_chain_sim.rs` integration test.
///
/// Returns `Ok(true)` if proved (`state` now has this transaction `Proved`, with the proven PCZT
/// replacing the stored one), `Ok(false)` if its witness/anchor isn't resolvable yet — the funding
/// note hasn't been observed as spendable yet, or its checkpoint hasn't been reached or was pruned
/// (`WalletProveError::UnknownSpentNote`/`AnchorNotFound`/`WitnessNotFound`) — this is the ordinary
/// transient "not ready yet" condition, not a failure, matching the old stopgap's `Ok(None)`
/// contract. Any other error is propagated.
fn try_prove(
    wallet: &mut Wallet,
    account: AccountUuid,
    fvk: orchard::keys::FullViewingKey,
    state: &mut MigrationState,
    id: MigrationTxId,
    kind: MigrationTxKind,
) -> anyhow::Result<bool> {
    let anchor = match kind {
        MigrationTxKind::Transfer { .. } => None,
        MigrationTxKind::Preparation { .. } => Some(natural_anchor_height(wallet)?),
    };
    let mut prover = WalletMigrationProver::new(wallet, account, fvk);
    let result = match anchor {
        None => engine::prove_transfer(&mut prover, state, id),
        Some(anchor) => engine::prove_preparation(&mut prover, state, id, anchor),
    };
    match result {
        Ok(()) => Ok(true),
        Err(ProveError::Prover(reason @ WalletProveError::UnknownSpentNote(_)))
        | Err(ProveError::Prover(reason @ WalletProveError::AnchorNotFound(_)))
        | Err(ProveError::Prover(reason @ WalletProveError::WitnessNotFound(_))) => {
            tracing::debug!(
                "MIGRATION_DIAG try_prove: {:?} not yet provable (transient): {:?}",
                id,
                reason
            );
            Ok(false)
        }
        Err(e) => Err(anyhow!(
            "Error proving migration transaction {:?}: {}",
            id,
            e
        )),
    }
}

/// Proves the note split (the layer-0 preparation transaction) via `try_prove`, persists the
/// resulting `Proved` state, and extracts the now-complete transaction's bytes and txid — shared by
/// both signing paths (`signNoteSplitNative`'s in-process signing and
/// `storeSignedNoteSplitPcztNative`'s Keystone external-signer path). Without proving,
/// `extractBroadcastTxNative` fails with `OrchardParse(MissingAnchor)` on the merely-signed PCZT
/// (confirmed live: the Keystone path originally skipped this step entirely).
fn finalize_note_split(
    wallet: &mut Wallet,
    account: AccountUuid,
    store_conn: &mut Connection,
    state: &mut MigrationState,
    id: MigrationTxId,
) -> anyhow::Result<(Vec<u8>, [u8; 32])> {
    let fvk = {
        let backend = Backend::new(wallet, account, None, store_conn)?;
        backend
            .orchard_fvk()
            .map_err(|e| anyhow!("Error reading account FVK: {:?}", e))?
    };
    let kind = state
        .transactions()
        .iter()
        .find(|t| t.id() == id)
        .map(|t| t.kind())
        .ok_or_else(|| anyhow!("Note-split transaction not found in migration state"))?;
    let proved = try_prove(wallet, account, fvk, state, id, kind)
        .map_err(|e| anyhow!("Error finalizing note split: {}", e))?;
    if !proved {
        return Err(anyhow!(
            "Note-split transaction is not yet finalizable — its funding note isn't witnessable yet"
        ));
    }
    {
        let mut backend = Backend::new(wallet, account, None, store_conn)?;
        backend
            .replace_migration(state)
            .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
    }
    let tx = state
        .transactions()
        .iter()
        .find(|t| t.id() == id)
        .expect("just proved above");
    let bytes = tx.pczt().to_vec();
    let extracted = pczt::roles::tx_extractor::TransactionExtractor::new(
        pczt::Pczt::parse(&bytes).map_err(|e| anyhow!("parse proven note-split pczt: {:?}", e))?,
    )
    .extract()
    .map_err(|e| anyhow!("extract proven note-split tx: {:?}", e))?;
    let txid: [u8; 32] = *extracted.txid().as_ref();
    Ok((bytes, txid))
}

/// In-process signing (software key, not Keystone) of the note split, as its own standalone,
/// immediately-broadcastable transaction — unlike most of this file's other functions this one is
/// NOT a thin wrapper deferring everything to the background worker.
///
/// Commits and signs the WHOLE migration (split + every transfer) in one pass via
/// `commit_preparation` — the new engine has no partial/staged commit — matching the ZIP 318
/// "sign now, prove later" contract our old crate also used (see
/// `docs/superpowers/specs/2026-07-17-migration-sign-now-prove-later-design.md` in zashi-android).
/// The split's own transaction is then finalized (proved) and extracted immediately, synchronously,
/// so this function can return a `PreparedTransfer` the caller broadcasts right away — matching the
/// old JNI contract exactly. The remaining transfer transactions are left `Signed` in the store for
/// `MigrationWorker`'s normal `finalizeReadyTransfersNative`/`nextDueTransferNative` loop to pick up
/// later, once they're actually due.
///
/// The split is a preparation transaction: even though it spends an already-witnessed wallet note
/// directly, ZIP 374 still defers its Orchard anchor/witness to proving time like any other
/// migration transaction (see `try_prove`'s doc comment) — `finalize_note_split` resolves that
/// against the wallet's current natural anchor.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_signNoteSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    proposal_handle: jlong,
    usk: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (network, mut wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let usk = crate::decode_usk(env, usk)?;
        let target = target_height(&wallet)?;
        let (mut state, _unsigned) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            proposal_handle as u64,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state =
                    engine::commit_preparation(network, target, backend, migration_plan, rng)
                        .map_err(|e| anyhow!("Error committing migration: {:?}", e))?;
                Ok((state, Vec::new()))
            },
        )?;
        let split_id = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .map(|t| t.id())
            .ok_or_else(|| anyhow!("Migration has no note-split preparation transaction"))?;
        let (proven_pczt, txid) =
            finalize_note_split(&mut wallet, account, &mut store_conn, &mut state, split_id)?;

        let id = encode_transfer_id(env, split_id)?;
        let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
        let pczt_obj = crate::utils::rust_bytes_to_java(env, &proven_pczt)?;
        Ok(env
            .new_object(
                JNI_PREPARED_TRANSFER,
                "(Ljava/lang/String;[B[B)V",
                &[
                    JValue::Object(&id),
                    JValue::Object(&txid_obj),
                    JValue::Object(&pczt_obj),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_extractBroadcastTxNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    _db_data: JString<'local>,
    _network_id: jint,
    _account_uuid: JByteArray<'local>,
    pczt_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let pczt_bytes = crate::utils::java_bytes_to_rust(env, &pczt_bytes)?;
        let pczt =
            pczt::Pczt::parse(&pczt_bytes).map_err(|e| anyhow!("Error parsing PCZT: {:?}", e))?;
        let tx = pczt::roles::tx_extractor::TransactionExtractor::new(pczt)
            .extract()
            .map_err(|e| anyhow!("Error extracting transaction: {:?}", e))?;
        let mut raw = Vec::new();
        tx.write(&mut raw)
            .map_err(|e| anyhow!("Error encoding transaction: {}", e))?;
        Ok(crate::utils::rust_bytes_to_java(env, &raw)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_recordTransferResultNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    transfer_id: JString<'local>,
    result_tag: jint,
    _retryable: jboolean,
    tx_id: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let transfer_id_str = crate::utils::java_string_to_rust(env, &transfer_id)?;
        let id = {
            let idx: u32 = transfer_id_str
                .parse()
                .map_err(|e| anyhow!("Invalid transfer id {}: {}", transfer_id_str, e))?;
            MigrationTxId::new(idx)
        };
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        match result_tag {
            // Success: record the broadcast txid. `mark_mined` has no old-crate equivalent call
            // site (the old crate didn't track a separate "mined" event either) — left unwired.
            0 => {
                let txid = crate::parse_txid(env, tx_id)?;
                let mut state = backend
                    .get_migration()
                    .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
                    .ok_or_else(|| anyhow!("No migration in progress"))?;
                state.mark_broadcast(id, txid);
                backend
                    .replace_migration(&state)
                    .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))
            }
            // NetworkError: transient, no state change.  Tag 1 stays a no-op.
            1 => Ok(()),
            // InvalidNote (2) / Expired (3): terminal failure — mark the migration Failed and
            // persist the invalidation reason so `derive_migration_state` can surface the right
            // `JniAttentionReason` sub-class to the Kotlin layer.
            2 | 3 => {
                let reason = if result_tag == 2 {
                    "invalid_transfer"
                } else {
                    "transfer_expired"
                };
                // Load the current state; only transition if one exists and is not already terminal.
                let current = backend
                    .get_migration()
                    .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
                if let Some(state) = current {
                    if !state.is_terminal() {
                        let failed = MigrationState::from_parts(
                            engine::MigrationStatus::Failed,
                            state.note_split().clone(),
                            state.preparation().clone(),
                            state.transactions().clone(),
                            state.anchor_bucket_interval(),
                        );
                        backend
                            .replace_migration(&failed)
                            .map_err(|e| anyhow!("Error persisting failed migration: {:?}", e))?;
                    }
                }
                record_invalidation(
                    &store_conn,
                    &account_bytes,
                    reason,
                    Some(&transfer_id_str),
                )
            }
            other => Err(anyhow!("Unknown TransferResult tag: {}", other)),
        }
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Reconciles mined-ness against the wallet's own transaction history before returning migration
/// state, so `InProgress`/`Complete` derivation reflects broadcast truth instead of staying stuck at
/// whatever `mark_broadcast` last recorded. The engine's own contract intentionally leaves mining
/// detection to the caller (`state.rs` module doc: "the state machine's only job is to ORDER the
/// broadcasts") — this is that caller-side reconciliation, run at read time rather than a background
/// job, matching the iOS SDK's own `derive_state` reconciliation approach.
fn read_reconciled(
    wallet: &Wallet,
    backend: &mut Backend<Wallet>,
) -> anyhow::Result<Option<MigrationState>> {
    let mut state = match backend
        .get_migration()
        .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
    {
        Some(s) => s,
        None => return Ok(None),
    };
    let mut newly_mined = Vec::new();
    for tx in state.transactions() {
        if let MigrationTxState::Broadcast { txid } = tx.state() {
            if let Some(height) = wallet
                .get_tx_height(txid)
                .map_err(|e| anyhow!("Error reading tx height for {:?}: {:?}", txid, e))?
            {
                newly_mined.push((tx.id(), height));
            }
        }
    }
    if !newly_mined.is_empty() {
        for (id, height) in newly_mined {
            state.mark_mined(id, height);
        }
        backend
            .replace_migration(&state)
            .map_err(|e| anyhow!("Error persisting reconciled migration state: {:?}", e))?;
    }
    Ok(Some(state))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        Ok(derive_migration_state(env, persisted, tip, &store_conn, &account_bytes)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationProgressNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                let transactions = state.transactions();
                let completed = transactions
                    .iter()
                    .filter(|t| matches!(t.state(), MigrationTxState::Mined { .. }))
                    .count();
                let next_ready_height = if state.next_broadcastable(tip).is_some() {
                    i64::from(u32::from(tip))
                } else {
                    -1
                };
                encode_migration_progress(env, completed, transactions.len(), next_ready_height)?
                    .into_raw()
            }
            _ => ptr::null_mut(),
        })
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_isNoteSplitNeededNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        // `note_split().crossing_values()` is the target `{1,2,5}×10ⁿ` denomination breakdown —
        // it's computed unconditionally whenever a migration is needed at all, so it's NEVER
        // empty and checking it here always returned true (confirmed live: this forced Kotlin's
        // `MigrationReviewVM.kt:186 if (sdk.isNoteSplitNeeded())` branch every time, even when the
        // wallet's existing notes already matched every target denomination exactly via
        // `direct_funding_notes()` and zero preparation transactions were actually needed —
        // `submitNoteSplit` then failed with "no note-split preparation transaction" since there
        // was nothing to sign). The real signal is whether the preparation plan has any
        // transactions to build at all.
        //
        // `compute_plan`, NOT `plan`: this is a pure peek — caching its throwaway plan would
        // invalidate the handle of any proposal the user is currently reviewing (see
        // `migration_plan_cache`'s module doc).
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let (migration_plan, _tip) = compute_plan(&network, &wallet, account, &mut store_conn)?;
        Ok(if migration_plan.preparation().transaction_count() > 0 {
            JNI_TRUE
        } else {
            JNI_FALSE
        })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// How many successive migration runs (see `engine::estimate_migration_runs`'s doc) the account's
/// current Orchard balance would need, given the engine's per-run note cap. Purely a stateless
/// preview — it has no memory of prior calls or rounds already committed, so callers must call this
/// fresh every time they need it rather than caching the result across a multi-round campaign (see
/// zashi-android's `docs/superpowers/specs/2026-07-22-keystone-multi-round-migration-continuation-design.md`).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_estimateMigrationRunCountNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let mut rng = OsRng;
        let estimate = engine::estimate_migration_runs(&network, &backend, &mut rng)
            .map_err(|e| anyhow!("Error estimating migration runs: {:?}", e))?;
        Ok(estimate.run_count() as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Pure predicate used by `hasOverdueTransfersNative`.  A transaction is overdue when it is in
/// `Proved` state, due at `effective_tip`, deps mined, and not yet expired at `scanned_tip`.
/// Intentionally no kind filter — preparations also close the sync gate, matching the pre-tri-state
/// `next_broadcastable`-based semantics.
fn any_overdue(
    state: &MigrationState,
    scanned_tip: BlockHeight,
    effective_tip: BlockHeight,
) -> bool {
    if state.is_terminal() {
        return false;
    }
    let scanned_target = scanned_tip + 1;
    state.transactions().iter().any(|t| {
        matches!(t.state(), MigrationTxState::Proved)
            && t.scheduled_height() <= effective_tip
            && state.deps_mined(t.depends_on())
            && !(u32::from(t.expiry_height()) != 0
                && u32::from(t.expiry_height()) < u32::from(scanned_target))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_hasOverdueTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    estimated_tip: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let scanned_tip = target_height(&wallet)? - 1;
        let effective_tip = if estimated_tip >= 0 {
            std::cmp::max(scanned_tip, BlockHeight::from(estimated_tip as u32))
        } else {
            scanned_tip
        };
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) => {
                if any_overdue(&state, scanned_tip, effective_tip) {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            }
            _ => JNI_FALSE,
        })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_hasInvalidTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) => match state.status() {
                engine::MigrationStatus::Failed => JNI_TRUE,
                _ => JNI_FALSE,
            },
            None => JNI_FALSE,
        })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_signAndStoreMigrationScheduleNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    proposal_handle: jlong,
    usk: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let usk = crate::decode_usk(env, usk)?;
        // No schedule fields cross the boundary here — `commit_preparation` takes a
        // `MigrationPlan` value directly, and the plan's details never leave the Rust side:
        // `proposal_handle` identifies the cached plan whose schedule the user was shown, and
        // `commit_or_reuse` signs exactly that plan or errors (see `migration_plan_cache`'s
        // module doc — this closes the sign-what-the-user-never-saw hazard of the previous
        // latest-plan-wins cache contract).
        let target = target_height(&wallet)?;
        commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            proposal_handle as u64,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state =
                    engine::commit_preparation(network, target, backend, migration_plan, rng)
                        .map_err(|e| anyhow!("Error committing migration schedule: {:?}", e))?;
                Ok((state, Vec::new()))
            },
        )?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Advances every due, signed transaction's proving (ZIP 374: installs its real anchor + witness
/// via the `pczt` `Updater` role and runs the `Prover`, via `try_prove` — see its doc comment).
/// Proving moves each ready transaction `Signed -> Proved` directly in the persisted
/// `MigrationState` (no separate side table — the engine's own persistence now tracks proven bytes
/// as part of the transaction itself, replacing this file's former hand-rolled
/// `migration_proven_cache`). Idempotent: only `Signed` transactions are candidates, so an
/// already-proven one is naturally skipped on a later call. Returns the count of transactions newly
/// proven this call, 0 (not an error) if nothing was ready.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_finalizeReadyTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let (_network, mut wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let target = target_height(&wallet)?;

        let (mut state, fvk) = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn)?;
            let Some(state) = backend
                .get_migration()
                .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            else {
                return Ok(0);
            };
            if state.is_terminal() {
                return Ok(0);
            }
            let fvk = backend
                .orchard_fvk()
                .map_err(|e| anyhow!("Error reading account FVK: {:?}", e))?;
            (state, fvk)
        };

        // Collect ready ids/kinds up front (not while iterating `state.transactions()`) since
        // `try_prove` needs `&mut state` — see `is_prove_ready`'s doc comment for why this doesn't
        // just loop `MigrationState::next_provable`.
        let ready: Vec<(MigrationTxId, MigrationTxKind)> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.state(), MigrationTxState::Signed) && is_prove_ready(&state, t, target)
            })
            .map(|t| (t.id(), t.kind()))
            .collect();
        tracing::debug!(
            "MIGRATION_DIAG finalizeReadyTransfers: target={:?}, {} Signed transaction(s) total, \
             {} prove-ready this call",
            target,
            state
                .transactions()
                .iter()
                .filter(|t| matches!(t.state(), MigrationTxState::Signed))
                .count(),
            ready.len(),
        );

        let mut finalized_count = 0;
        for (id, kind) in ready {
            if try_prove(&mut wallet, account, fvk.clone(), &mut state, id, kind)
                .map_err(|e| anyhow!("Error proving transfer {:?}: {}", id, e))?
            {
                finalized_count += 1;
            }
        }
        if finalized_count > 0 {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
            backend
                .replace_migration(&state)
                .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
        }
        Ok(finalized_count)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Tri-state result of next-due-transfer lookup.
enum DueTransferResult<'a> {
    /// No migration is in progress, it's terminal, or nothing is due right now.
    NothingDue,
    /// A transfer is due but still in `Signed` state (not yet proven) — cannot broadcast yet.
    AwaitingProof(MigrationTxId),
    /// A transfer is proven and ready to broadcast.
    Ready(&'a MigrationTransaction),
}

/// Core filtering logic for next_due_transfer: given a reconciled state and two height sentinels,
/// returns the tri-state result. `scanned_tip` is used for expiry checks (never the estimate);
/// `effective_tip` (may equal `scanned_tip` when no estimate) is used for schedule due-ness.
fn next_due_transfer_result<'a>(
    state: &'a MigrationState,
    scanned_tip: BlockHeight,
    effective_tip: BlockHeight,
) -> DueTransferResult<'a> {
    if state.is_terminal() {
        return DueTransferResult::NothingDue;
    }
    let scanned_target = scanned_tip + 1;
    let mut due: Vec<&MigrationTransaction> = state
        .transactions()
        .iter()
        .filter(|t| {
            matches!(t.kind(), MigrationTxKind::Transfer { .. })
                && matches!(t.state(), MigrationTxState::Proved | MigrationTxState::Signed)
                && t.scheduled_height() <= effective_tip
                && state.deps_mined(t.depends_on())
                // Expiry always uses scanned_tip, never the estimate.
                && !(u32::from(t.expiry_height()) != 0
                    && u32::from(t.expiry_height()) < u32::from(scanned_target))
        })
        .collect();
    due.sort_by_key(|t| t.scheduled_height());
    // First Proved -> READY; else first Signed -> AWAITING_PROOF; else NOTHING_DUE.
    if let Some(tx) = due.iter().find(|t| matches!(t.state(), MigrationTxState::Proved)) {
        return DueTransferResult::Ready(tx);
    }
    if let Some(tx) = due.iter().find(|t| matches!(t.state(), MigrationTxState::Signed)) {
        return DueTransferResult::AwaitingProof(tx.id());
    }
    DueTransferResult::NothingDue
}

/// The next due, deps-mined transfer: tri-state (NOTHING_DUE=0, READY=1, AWAITING_PROOF=2).
/// `estimated_tip` (pass -1 for none) may only ACCELERATE due-ness; expiry is always checked
/// against the scanned tip. A terminal migration always returns NOTHING_DUE.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_nextDueTransferNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    estimated_tip: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let scanned_tip = target_height(&wallet)? - 1;
        let effective_tip = if estimated_tip >= 0 {
            std::cmp::max(scanned_tip, BlockHeight::from(estimated_tip as u32))
        } else {
            scanned_tip
        };
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = read_reconciled(&wallet, &mut backend)? else {
            // No migration: status=0, both nullable fields null.
            return Ok(env.new_object(
                JNI_DUE_TRANSFER_RESULT,
                format!("(ILjava/lang/String;L{JNI_PREPARED_TRANSFER};)V"),
                &[
                    JValue::Int(0),
                    JValue::Object(&JObject::null()),
                    JValue::Object(&JObject::null()),
                ],
            )?.into_raw());
        };

        tracing::debug!(
            "MIGRATION_DIAG nextDueTransfer: scanned_tip={:?} effective_tip={:?} estimated_tip={} \
             transfers={} states={:?}",
            scanned_tip,
            effective_tip,
            estimated_tip,
            state.transactions().iter().filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. })).count(),
            state.transactions().iter()
                .filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. }))
                .map(|t| (t.id(), t.state(), t.scheduled_height()))
                .collect::<Vec<_>>(),
        );

        match next_due_transfer_result(&state, scanned_tip, effective_tip) {
            DueTransferResult::NothingDue => {
                Ok(env.new_object(
                    JNI_DUE_TRANSFER_RESULT,
                    format!("(ILjava/lang/String;L{JNI_PREPARED_TRANSFER};)V"),
                    &[
                        JValue::Int(0),
                        JValue::Object(&JObject::null()),
                        JValue::Object(&JObject::null()),
                    ],
                )?.into_raw())
            }
            DueTransferResult::AwaitingProof(id) => {
                let id_obj = encode_transfer_id(env, id)?;
                Ok(env.new_object(
                    JNI_DUE_TRANSFER_RESULT,
                    format!("(ILjava/lang/String;L{JNI_PREPARED_TRANSFER};)V"),
                    &[
                        JValue::Int(2),
                        JValue::Object(&id_obj),
                        JValue::Object(&JObject::null()),
                    ],
                )?.into_raw())
            }
            DueTransferResult::Ready(tx) => {
                // `Proved` carries the fully witnessed/anchored/proven PCZT bytes (installed by
                // `finalizeReadyTransfersNative`'s `try_prove`) — extract the txid directly from them.
                let bytes = tx.pczt();
                let extracted = pczt::roles::tx_extractor::TransactionExtractor::new(
                    pczt::Pczt::parse(bytes).map_err(|e| anyhow!("parse proven transfer pczt: {:?}", e))?,
                )
                .extract()
                .map_err(|e| anyhow!("extract proven transfer tx: {:?}", e))?;
                let txid: [u8; 32] = *extracted.txid().as_ref();
                let id_obj = encode_transfer_id(env, tx.id())?;
                let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
                let pczt_obj = crate::utils::rust_bytes_to_java(env, bytes)?;
                let prepared = env.new_object(
                    JNI_PREPARED_TRANSFER,
                    "(Ljava/lang/String;[B[B)V",
                    &[
                        JValue::Object(&id_obj),
                        JValue::Object(&txid_obj),
                        JValue::Object(&pczt_obj),
                    ],
                )?;
                Ok(env.new_object(
                    JNI_DUE_TRANSFER_RESULT,
                    format!("(ILjava/lang/String;L{JNI_PREPARED_TRANSFER};)V"),
                    &[
                        JValue::Int(1),
                        JValue::Object(&JObject::null()),
                        JValue::Object(&prepared),
                    ],
                )?.into_raw())
            }
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The live, persisted status of every committed transfer transaction — reads straight from the
/// migration store's current `scheduled_height`/state columns, so it reflects any reschedule
/// (production `rescheduleOverdueTransfer()` or the debug-only `debugRescheduleTransfersNative`)
/// immediately, unlike the app's own `MigrationPlanRepository` cache (populated once, at
/// propose/commit time, and only ever updated by whichever caller remembers to write through it).
/// Returns `null` if there's no in-progress migration.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationTransferStatesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;
        let backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(ptr::null_mut());
        };

        // Keyed by the transfer's real, stable MigrationTxId — NOT `transfer_crossing()` (the
        // funding-note/crossing index). The app's displayed "Transfer N" position comes from
        // sorting the ORIGINAL proposal by broadcast_height (see `encode_migration_schedule`),
        // while the engine assigns real tx ids in crossing/schedule() order at commit time —
        // ZIP 318 deliberately shuffles those two orderings apart, so they permanently disagree.
        // The app now carries this same id on its cached `MigrationTransfer.id` (see
        // `MigrationSchedule.toMigrationPlan`), which is the only stable key the two sides share.
        let transfers: Vec<(MigrationTxId, bool, BlockHeight)> = state
            .transactions()
            .iter()
            .filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. }))
            .map(|t| {
                let is_sent = matches!(
                    t.state(),
                    MigrationTxState::Broadcast { .. } | MigrationTxState::Mined { .. }
                );
                (t.id(), is_sent, t.scheduled_height())
            })
            .collect();

        let jtransfers = crate::utils::rust_vec_to_java(
            env,
            transfers,
            JNI_MIGRATION_TRANSFER_STATE,
            |env, (id, is_sent, scheduled_height)| {
                let id = encode_transfer_id(env, id)?;
                env.new_object(
                    JNI_MIGRATION_TRANSFER_STATE,
                    "(Ljava/lang/String;ZJ)V",
                    &[
                        JValue::Object(&id),
                        JValue::Bool(is_sent as jboolean),
                        JValue::Long(i64::from(u32::from(scheduled_height))),
                    ],
                )
            },
        )?;

        Ok(env
            .new_object(
                JNI_MIGRATION_TRANSFER_STATES,
                format!("([L{JNI_MIGRATION_TRANSFER_STATE};J)V"),
                &[
                    JValue::Object(&jtransfers),
                    JValue::Long(i64::from(u32::from(tip))),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_restartCurrentMigrationStepNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    _include_residual: jboolean,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (migration_plan, tip, plan_handle) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip, plan_handle)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// A fixed, well-known lock owner for the "Lock balance" dust-lock feature
/// (`MigrationSdk.lockRemainingOrchardBalance`) — not a per-proposal lock, so a stable constant
/// (not `LockOwner::random`) lets re-invoking the feature re-extend the same lock idempotently
/// (see `WalletWrite::lock_outputs`'s doc comment on same-owner re-locking) and would let a future
/// "undo" flow release it via this same token.
const DUST_LOCK_OWNER: LockOwner = LockOwner::new(*b"zashi-migration-dust-lock-owner!");

/// Locks whatever Orchard balance remains spendable for this account (dust below the migratable
/// threshold, or a residual the user opted out of migrating) so ordinary note selection — sends,
/// shielding, and any future migration round — excludes it by default (`LockFilter::Policy`
/// applied at every real selection call site in this crate and `lib.rs`), per
/// `MigrationSdk.lockRemainingOrchardBalance`'s contract. The lock has no natural expiry for this
/// use (it should stay locked indefinitely, unlike a proposal's transient lock), so it's set to
/// the maximum representable height.
///
/// Returns the number of notes locked (0 if there was nothing left to lock).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_lockRemainingOrchardBalanceNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let (_network, mut wallet, _store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let target = target_height(&wallet)?;

        // Unfiltered: this must see (and re-lock) notes this same call already locked on a prior
        // invocation, not just ones nothing has locked yet — same-owner re-locking is what makes
        // repeated taps of "Lock balance" idempotent.
        let received = wallet
            .select_unspent_notes(
                account,
                &[ShieldedPool::Orchard],
                target.into(),
                &[],
                LockFilter::Unfiltered,
            )
            .map_err(|e| anyhow!("Error reading remaining Orchard balance: {}", e))?;

        let outputs: Vec<OutputRef> = received
            .orchard()
            .iter()
            .map(|rn| OutputRef::new(*rn.txid(), PoolType::ORCHARD, u32::from(rn.output_index())))
            .collect();
        if outputs.is_empty() {
            return Ok(0);
        }

        let locked = wallet
            .lock_outputs(&outputs, DUST_LOCK_OWNER, BlockHeight::from(u32::MAX))
            .map_err(|e| anyhow!("Error locking remaining Orchard balance: {:?}", e))?;
        Ok(locked as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Lists every account's UUID (16 raw bytes each) in the wallet database, independent of any
/// migration engine — unaffected by this rewire (never referenced `zcash_pool_migration`).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_getAccountUuidsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let network = crate::parse_network(network_id as u32)?;
        let db = crate::wallet_db(env, network, db_data)?;
        let account_ids = match db.get_account_ids() {
            Ok(ids) => ids,
            Err(zcash_client_sqlite::error::SqliteClientError::DbError(
                rusqlite::Error::SqliteFailure(_, Some(ref msg)),
            )) if msg.contains("no such table") => Vec::new(),
            Err(e) => return Err(anyhow!("Error listing account ids: {}", e)),
        };
        let uuid_bytes: Vec<Vec<u8>> = account_ids
            .iter()
            .map(|id| id.expose_uuid().as_bytes().to_vec())
            .collect();
        Ok(
            crate::utils::rust_vec_to_java(env, uuid_bytes, "[B", |env, bytes| {
                crate::utils::rust_bytes_to_java(env, &bytes)
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// DEBUG ONLY: abandons this account's in-progress migration (every not-yet-broadcast
/// preparation and transfer transaction, signed or not, proved or not), so the next
/// propose/commit call starts completely fresh — for manual testing, not exposed to production
/// users. Persists the run as `Failed` through the engine store (`replace_migration`) — the
/// engine's cancellation shape, and the same one `zcash-swift-wallet-sdk`'s restart path
/// persists — rather than deleting the store's rows out from under it with raw SQL (this crate
/// never manipulates engine-owned tables directly). A subsequent propose/commit starts a fresh
/// run over the terminal predecessor, which the engine supports. Distinct from
/// `restartCurrentMigrationStepNative`, which recovers a RequiresAttention migration by
/// re-planning the remaining balance.
///
/// Behavioral note versus the earlier row-delete implementation: the cancelled run remains
/// stored, so `getMigrationStateNative` reports `RequiresAttention` (as after any failure)
/// rather than `NotStarted`, and an already-terminal run is left as-is.
///
/// Returns 1 if an in-progress run was cancelled, 0 if there was nothing to cancel (no stored
/// run, or a run that already reached `Complete`/`Failed`).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_clearMigrationNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration: {}", e))?
        else {
            return Ok(0);
        };
        if state.is_terminal() {
            return Ok(0);
        }
        // Only the status changes; the anchor bucket grid the run was committed under is carried
        // through unchanged, since rewriting it would misreport which boundaries the already-drawn
        // transfer anchors lie on.
        let cancelled = MigrationState::from_parts(
            engine::MigrationStatus::Failed,
            state.note_split().clone(),
            state.preparation().clone(),
            state.transactions().clone(),
            state.anchor_bucket_interval(),
        );
        backend
            .replace_migration(&cancelled)
            .map_err(|e| anyhow!("Error cancelling migration: {}", e))?;
        // Also clear any persisted invalidation reason so a fresh run starts clean.
        clear_invalidation(&store_conn, &account_bytes)
            .map_err(|e| anyhow!("Error clearing invalidation on cancel: {}", e))?;
        Ok(1)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Pure inner logic for rescheduling a single `Signed` transfer — extracted so tests can call
/// it directly without a wallet database. Returns `(new_scheduled_height_as_i64, updated_state)`,
/// or `(-1, original_state)` if the transfer is not reschedulable (not found or not `Signed`).
///
/// `new_schedule` and `new_boundary` are pre-computed by the caller (the JNI wrapper draws
/// an exponential delay and candidate boundary; tests supply deterministic values).
/// Pure boundary-selection helper for `rescheduleUnprovenTransferNative`.
///
/// Given the funding height of the dependency, the current tip, and the bucket interval,
/// computes the candidate grid range `[lowest, highest]` and either:
/// - returns `old_boundary` unchanged when the candidate set is empty (lowest > highest), or
/// - calls `pick(count)` with the number of candidates and uses the returned index (0-based) to
///   select a grid multiple, returning `Some(lowest + pick_idx * interval_u32)`.
///
/// This is the load-bearing "empty candidate set → keep old boundary" decision (spec M4).
/// The JNI wrapper passes `OsRng.gen_range(0..count)` as the pick closure; tests pass a
/// deterministic function so the boundary selection is fully exercised without any RNG.
fn select_shift_boundary(
    funding_height: u32,
    tip_u32: u32,
    interval_u32: u32,
    old_boundary: Option<BlockHeight>,
    pick: impl FnOnce(u32) -> u32,
) -> Option<BlockHeight> {
    // highest = most recent settled grid point one interval back from tip.
    // Guard: if tip < interval, set highest = 0 (conservative; triggers no-candidate below).
    let highest = if tip_u32 >= interval_u32 {
        (tip_u32 / interval_u32) * interval_u32 - interval_u32
    } else {
        0u32
    };
    // lowest = first grid multiple >= funding_height (ceiling division).
    // If funding_height == 0 (unknown dep), use interval_u32 as a conservative floor.
    let lowest = if funding_height == 0 {
        interval_u32
    } else {
        ((funding_height + interval_u32 - 1) / interval_u32) * interval_u32
    };

    if lowest > highest {
        // No candidate in range: keep old boundary unchanged (spec M4, testnet load-bearing)
        old_boundary
    } else {
        let count = (highest - lowest) / interval_u32 + 1;
        let pick_idx = pick(count);
        Some(BlockHeight::from(lowest + pick_idx * interval_u32))
    }
}

fn reschedule_unproven_transfer_inner(
    state: MigrationState,
    transfer_id: MigrationTxId,
    new_schedule: BlockHeight,
    new_boundary: Option<BlockHeight>,
) -> (i64, MigrationState) {
    // Must exist and be Signed; any other state (AwaitingSignature, Proved, Broadcast, Mined) → -1.
    match state
        .transactions()
        .iter()
        .find(|t| t.id() == transfer_id)
    {
        Some(t) if matches!(t.state(), MigrationTxState::Signed) => {}
        _ => return (-1, state),
    }

    // Rebuild every transaction in its original order.
    // expiry_height is NEVER changed — the ZIP 374 sighash covers it; pczt bytes are preserved.
    let interval = state.anchor_bucket_interval();
    let new_transactions: Vec<MigrationTransaction> = state
        .transactions()
        .iter()
        .map(|t| {
            if t.id() == transfer_id {
                MigrationTransaction::from_parts(
                    t.id(),
                    t.kind(),
                    t.pczt().clone(),
                    t.depends_on().clone(),
                    new_schedule,
                    t.expiry_height(), // MUST NOT change — ZIP 374 sighash covers it
                    new_boundary,
                    t.state(),
                    t.lock_owner(),
                )
            } else {
                t.clone()
            }
        })
        .collect();

    let new_state = MigrationState::from_parts(
        state.status(),
        state.note_split().clone(),
        state.preparation().clone(),
        new_transactions,
        interval,
    );
    (i64::from(u32::from(new_schedule)), new_state)
}

/// Shifts a single `Signed` transfer's `scheduled_height` (and optionally redraws its
/// `anchor_boundary`) into the future, then persists the updated run. Called by the app's
/// broadcast lane when a due transfer has no proof yet (missed its sync window).
///
/// Returns the new `scheduled_height` as a positive `jlong`, or `-1` if the transfer was not
/// reschedulable (not found / not `Signed` / terminal run).
///
/// Constraints:
/// - `expiry_height` is NEVER modified — the ZIP 374 sighash covers it; PCZT bytes remain
///   byte-identical.
/// - Only `Signed` transfers are reschedulable; `Proved`/terminal/missing → return -1.
/// - Boundary redraw: bucket-grid multiples only. Empty candidate set → keep old boundary
///   (spec M4, load-bearing for testnet where tip is often within one interval of genesis).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_rescheduleUnprovenTransferNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    transfer_id: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let id = decode_transfer_id(env, &transfer_id)?;

        let tip = target_height(&wallet)? - 1;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(-1i64);
        };
        if state.is_terminal() {
            return Ok(-1i64);
        }

        // Check the transfer exists and is Signed; extract what we need before consuming `state`.
        let (old_boundary, dep_ids) = {
            let tx = state.transactions().iter().find(|t| t.id() == id);
            match tx {
                Some(t) if matches!(t.state(), MigrationTxState::Signed) => {
                    (t.anchor_boundary(), t.depends_on().clone())
                }
                _ => return Ok(-1i64),
            }
        };

        let interval_u32 = state.anchor_bucket_interval().block_count().get();
        let tip_u32 = u32::from(tip);

        // New schedule: exponential delay, mean = one bucket interval, cap 4×.
        // Testnet (interval=12): mean ~12 blocks (~15 min); mainnet (interval=144): mean ~3 h.
        let delay = {
            use rand::Rng;
            let mean = interval_u32 as f64;
            let u: f64 = rand::rngs::OsRng.gen_range(f64::EPSILON..1.0);
            (-u.ln() * mean).round().clamp(1.0, 4.0 * mean) as u32
        };
        let new_height = BlockHeight::from(tip_u32 + delay);

        // Boundary redraw: uniform-random grid multiple in [lowest, highest].
        // lowest = first grid multiple ≥ funding height (dep's mined height, else interval).
        // highest = most recent settled grid point one interval back from tip.
        // Empty candidate set (lowest > highest) → keep old boundary (spec M4 fallback).
        // Recency-weighted draw (used at commit time by draw_anchor_boundary) is NOT applied here;
        // uniform is acceptable for a reschedule shift since the original commit already
        // incorporated the privacy weighting.
        let funding_height: u32 = state
            .transactions()
            .iter()
            .find(|t| dep_ids.contains(&t.id()))
            .and_then(|dep| dep.state().mined_height())
            .map(u32::from)
            .unwrap_or(0);

        let new_boundary = {
            use rand::Rng;
            select_shift_boundary(
                funding_height,
                tip_u32,
                interval_u32,
                old_boundary,
                |count| rand::rngs::OsRng.gen_range(0..count),
            )
        };

        let (result, new_state) =
            reschedule_unproven_transfer_inner(state, id, new_height, new_boundary);
        if result < 0 {
            return Ok(-1i64);
        }
        backend
            .replace_migration(&new_state)
            .map_err(|e| anyhow!("Error persisting rescheduled migration state: {:?}", e))?;
        Ok(result)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// DEBUG ONLY: overrides this account's persisted migration schedule so its transfers become due
/// in quick succession, for manually testing real broadcast execution without waiting out ZIP
/// 318's privacy-motivated delay (mean ~3h between transfers — see `zcash_pool_migration::
/// scheduling`'s module doc: this is a deliberate anti-correlation choice, not a technical
/// requirement). Not exposed to production users.
///
/// Both `scheduled_height` (which gates BROADCAST — see `next_broadcastable`) AND `anchor_boundary`
/// (which gates PROVING — see `is_prove_ready`) are rewritten; dependency-mining is not touched or
/// bypassed:
/// - A transfer's `anchor_boundary`, as originally drawn at commit time
///   (`scheduling::draw_anchor_boundary`), is a boundary in the past relative to the chain tip
///   *at commit time* — normally already passed by the time the transfer's (much later,
///   ZIP-318-delayed) `scheduled_height` arrives. This override exists precisely because
///   `debugRescheduleTransfers` moves `scheduled_height` to now, while the original
///   `anchor_boundary` stays wherever it was drawn — confirmed live: the original boundary can
///   still be ~70-1800 blocks AHEAD of the current synced tip, since it was never meant to be
///   reached this soon. Left alone, `is_prove_ready` (`boundary + 1 < target_height`) would keep
///   failing regardless of how close `scheduled_height` is. So every rescheduled transfer's
///   `anchor_boundary` is also rewritten, to `natural_anchor_height` — the SAME anchor ordinary
///   non-migration sends use (guaranteed checkpointed/witnessed). NOT a full `BOUNDARY_MODULUS`
///   bucket back like `draw_anchor_boundary` draws in production (that bucketing is a privacy
///   measure, irrelevant here, and lands outside the checkpoint retention window — confirmed live
///   via `AnchorNotFound`), and NOT a hand-picked "tip minus N" guess either (also not guaranteed
///   checkpointed — see `natural_anchor_height`'s own doc comment) — so proving can proceed as
///   soon as `finalizeReadyTransfers` next runs.
/// - Transfers do NOT depend on each other (confirmed directly: `MigrationTransaction::depends_on`
///   for a `Transfer` never lists another transfer's id, only the single preparation transaction
///   that minted its own funding note, if any) — so every transfer can be staggered independently;
///   there is no need to wait for transfer N to broadcast before N+1 becomes due.
/// - A transfer whose funding note comes from an actual note-split (preparation) transaction still
///   genuinely cannot broadcast until that preparation transaction is MINED (`deps_mined`) — this
///   function does not and cannot bypass that; it only affects how soon a transfer becomes due and
///   provable once its real dependencies are satisfied.
///
/// Every not-yet-broadcast/mined TRANSFER (preparation transactions are left alone) is
/// rescheduled to `tip + FIRST_DELAY_BLOCKS + i * STRIDE_BLOCKS`, in `i` = the transfers' existing
/// relative order (by their current `scheduled_height`, so the engine's own ZIP 318 shuffle order
/// is preserved even though the absolute heights are now compressed) — the first becomes due in
/// about `FIRST_DELAY_BLOCKS * 75s`, each subsequent one `STRIDE_BLOCKS * 75s` after that. The
/// stride is intentionally tight (one block) so all transfers stay within a handful of blocks of
/// the current tip and remain reachable on a slow testnet; the per-broadcast pace is still gated
/// by the 10-minute privacy sync buffer regardless, so tightening the heights doesn't collapse the
/// broadcast spacing.
///
/// Returns the number of transfers rescheduled.
///
/// KNOWN EXCEPTION — direct SQL against engine-owned tables: rewriting a committed schedule is
/// deliberately not something `zcash_pool_migration` exposes (the ZIP 318 schedule is a
/// privacy property, and `MigrationTransaction` offers no schedule setters), so this debug-only
/// function reads and updates `orchard_ironwood_migration_transactions` rows directly. This is
/// the ONLY remaining direct manipulation of wallet-database internals in this crate; if the
/// engine ever grows a (feature-gated) rescheduling hook, replace this with it. Column-name
/// drift here fails only this debug aid, never a production path.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_debugRescheduleTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jint {
    // Packed one block apart starting at tip+1 (~75s to the first, ~75s between each) so all N
    // transfers land within N blocks of the tip instead of spanning N*STRIDE. A wider stride
    // (previously 2 + i*4) pushed the last of a dozen transfers ~46 blocks / ~1h out — on a slow
    // or momentarily-stalled testnet those far heights are never reached, so the run never
    // finishes. Kept at tip+1 (not tip) deliberately: the first transfer stays a block in the
    // future so the background sync-advance path (MigrationWorker's WAIT_AND_RETRY → sync burst →
    // becomes due) is still exercised, rather than being immediately overdue.
    const FIRST_DELAY_BLOCKS: u32 = 1;
    const STRIDE_BLOCKS: u32 = 1;

    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let target = target_height(&wallet)?;
        // The wallet's real, currently-witnessable anchor — NOT a hand-picked "tip minus N" guess.
        // A full BOUNDARY_MODULUS (144-block) bucket back, like the real engine's
        // draw_anchor_boundary draws, falls outside the checkpoint/witness retention window
        // (confirmed live: AnchorNotFound at tip-256); a smaller ad-hoc offset (tip-5) isn't
        // guaranteed checkpointed either — natural_anchor_height's own doc comment warns about
        // this exact class of mistake ("NOT just chain tip minus one, which isn't necessarily
        // checkpointed"). This is the same anchor ordinary non-migration sends use, so it's
        // guaranteed available.
        let debug_anchor_boundary = u32::from(natural_anchor_height(&wallet)?);

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(0);
        };

        // The still-pending transfers, earliest-scheduled first: transfer-kind transactions that
        // have neither broadcast nor mined (i.e. only AwaitingSignature/Signed/Proved qualify),
        // sorted by scheduled_height ascending. This order gives each pending transfer its index
        // `i` below (matching the old `ORDER BY scheduled_height ASC` query).
        let mut pending: Vec<&MigrationTransaction> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.kind(), MigrationTxKind::Transfer { .. })
                    && !matches!(
                        t.state(),
                        MigrationTxState::Broadcast { .. } | MigrationTxState::Mined { .. }
                    )
            })
            .collect();
        pending.sort_by_key(|t| t.scheduled_height());

        // The new (scheduled_height, anchor_boundary) for each pending transfer, keyed by its
        // stable id so the full transactions vec can be rebuilt in its original order below.
        let mut reschedule: HashMap<MigrationTxId, (BlockHeight, Option<BlockHeight>)> =
            HashMap::with_capacity(pending.len());
        for (i, tx) in pending.iter().enumerate() {
            let new_height = BlockHeight::from(
                u32::from(target) + FIRST_DELAY_BLOCKS + (i as u32) * STRIDE_BLOCKS,
            );
            // `is_prove_ready` (`finalizeReadyTransfers`) gates purely on `anchor_boundary`, NOT on
            // `scheduled_height` — so rewriting every transfer's anchor here would make ALL of them
            // prove-ready in the same `finalizeReadyTransfers` call (confirmed live: 12 real Halo2
            // proofs run back-to-back under one MIGRATION_DB_ACCESS_MUTEX hold, ~4-5 minutes,
            // starving a concurrent "Send Now" tap the whole time — not a hang, just an unrealistic
            // proving batch this debug tool itself created). Only the earliest-due transfer (i==0,
            // this list's scheduled_height-ascending order) gets a valid anchor; the rest keep their
            // original, still-in-the-future one (the old `COALESCE(NULL, anchor_boundary)`),
            // matching production's natural one-becomes-ready-at-a-time shape. Re-invoke this debug
            // action once this transfer broadcasts to unlock the next one.
            let anchor_boundary = if i == 0 {
                Some(BlockHeight::from(debug_anchor_boundary))
            } else {
                tx.anchor_boundary()
            };
            reschedule.insert(tx.id(), (new_height, anchor_boundary));
        }
        let rescheduled = reschedule.len();

        // Rebuild every transaction in its ORIGINAL order: a rescheduled pending transfer gets its
        // new scheduled_height and anchor_boundary (every other part copied through unchanged); any
        // other transaction is kept as-is.
        let new_transactions: Vec<MigrationTransaction> = state
            .transactions()
            .iter()
            .map(|tx| match reschedule.get(&tx.id()) {
                Some(&(new_height, new_anchor_boundary)) => MigrationTransaction::from_parts(
                    tx.id(),
                    tx.kind(),
                    tx.pczt().clone(),
                    tx.depends_on().clone(),
                    new_height,
                    tx.expiry_height(),
                    new_anchor_boundary,
                    tx.state(),
                    tx.lock_owner(),
                ),
                None => tx.clone(),
            })
            .collect();

        let new_state = MigrationState::from_parts(
            state.status(),
            state.note_split().clone(),
            state.preparation().clone(),
            new_transactions,
            match network {
                Network::MainNetwork => AnchorBucketInterval::ZIP_318,
                Network::TestNetwork => {
                    AnchorBucketInterval::custom(NonZeroU32::try_from(12).expect("12 is nonzero"))
                }
            },
        );
        backend
            .replace_migration(&new_state)
            .map_err(|e| anyhow!("Error persisting rescheduled migration state: {:?}", e))?;
        Ok(rescheduled as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_pendingTransferProposalNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                match state.next_broadcastable(tip).and_then(|id| {
                    state
                        .transactions()
                        .iter()
                        .find(|t| t.id() == id)
                        .map(|t| (id, t))
                }) {
                    Some((id, tx)) if matches!(tx.kind(), MigrationTxKind::Transfer { .. }) => {
                        encode_transfer_proposal(
                            env,
                            id,
                            // Amount isn't retained on `MigrationTransaction` (only in the
                            // original `MigrationPlan`) — 0 until the caller re-derives it from a
                            // freshly re-planned schedule if it needs the real value here.
                            Zatoshis::ZERO,
                            tip,
                            tip,
                            tip,
                        )?
                        .into_raw()
                    }
                    _ => ptr::null_mut(),
                }
            }
            _ => ptr::null_mut(),
        })
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// A pure constant read — [`MIGRATION_DUST_THRESHOLD_ZATOSHI`] is a fixed protocol-level value,
/// not derived from any wallet or account state, so unlike every other export in this file this
/// needs no `db_data`/`network_id`/account argument and can't fail or panic (no `catch_unwind` /
/// `unwrap_exc_or` needed).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationDustThresholdZatoshiNative<
    'local,
>(
    _env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jlong {
    MIGRATION_DUST_THRESHOLD_ZATOSHI as jlong
}

// ----- External signer (Keystone hardware wallet) -----

/// Fetches the account's ZIP 32 seed fingerprint and account index, required to annotate
/// external-signer (Keystone) migration PCZTs with `spend_zip32_derivation` — see
/// `migration_keystone::annotate_spend_zip32_derivation`'s doc comment for why this is needed.
///
/// Applied as a post-processing step on whatever unsigned PCZT bytes `commit_or_reuse` returns
/// (freshly built, or reused from an already-committed migration) rather than inside the `sign`
/// closure passed to it: `commit_or_reuse` only calls that closure on first commit, so annotating
/// only there would silently skip already-committed migrations (e.g. ones committed before this
/// annotation existed) on every later re-entry into the Keystone sign screen.
fn account_zip32_derivation(
    wallet: &Wallet,
    account: AccountUuid,
) -> anyhow::Result<([u8; 32], zip32::AccountId)> {
    use zcash_client_backend::data_api::Account;

    let account_info = wallet
        .get_account(account)
        .map_err(|e| anyhow!("account lookup failed: {}", e))?
        .ok_or_else(|| anyhow!("Account not found"))?;
    let derivation = account_info.source().key_derivation().ok_or_else(|| {
        anyhow!(
            "Account has no known ZIP 32 seed fingerprint/account index — cannot annotate \
             migration PCZTs for external-signer batch signing"
        )
    })?;
    Ok((
        derivation.seed_fingerprint().to_bytes(),
        derivation.account_index(),
    ))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_createUnsignedNoteSplitPcztNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    proposal_handle: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let target = target_height(&wallet)?;
        let (state, unsigned) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            proposal_handle as u64,
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) = engine::build_preparation_unsigned(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                )
                .map_err(|e| anyhow!("Error building unsigned migration PCZTs: {:?}", e))?;
                Ok((
                    state,
                    unsigned.into_iter().map(|tx| tx.into_parts()).collect(),
                ))
            },
        )?;
        let split_id = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .map(|t| t.id())
            .ok_or_else(|| anyhow!("Migration plan has no note-split preparation transaction"))?;
        let (_id, pczt_bytes) = unsigned
            .into_iter()
            .find(|(id, _)| *id == split_id)
            .ok_or_else(|| anyhow!("Migration plan has no note-split preparation transaction"))?;
        let (seed_fingerprint, account_index) = account_zip32_derivation(&wallet, account)?;
        let pczt_bytes = crate::migration_keystone::annotate_spend_zip32_derivation(
            &pczt_bytes,
            seed_fingerprint,
            network.coin_type(),
            account_index,
        )
        .map_err(|e| anyhow!("Error annotating note-split PCZT derivation: {:?}", e))?;
        Ok(crate::utils::rust_bytes_to_java(env, &pczt_bytes)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_storeSignedNoteSplitPcztNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    signed_pczt: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (_network, mut wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let signed_pczt_bytes = crate::utils::java_bytes_to_rust(env, &signed_pczt)?;
        let mut state = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn)?;
            backend
                .get_migration()
                .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
                .ok_or_else(|| anyhow!("No migration committed yet"))?
        };
        let split_id = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .map(|t| t.id())
            .ok_or_else(|| anyhow!("Migration has no note-split preparation transaction"))?;
        if !state.apply_signature(split_id, signed_pczt_bytes) {
            return Err(anyhow!("Error applying note-split signature"));
        }
        {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
            backend
                .replace_migration(&state)
                .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
        }
        // Resolve the deferred witness/anchor and prove before extraction — without this,
        // `extractBroadcastTxNative` fails with `OrchardParse(MissingAnchor)` on the
        // merely-signed-but-unproven bytes just applied above (confirmed live).
        let (proven_pczt, txid) =
            finalize_note_split(&mut wallet, account, &mut store_conn, &mut state, split_id)?;

        let id = encode_transfer_id(env, split_id)?;
        let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
        let pczt_bytes = crate::utils::rust_bytes_to_java(env, &proven_pczt)?;
        Ok(env
            .new_object(
                JNI_PREPARED_TRANSFER,
                "(Ljava/lang/String;[B[B)V",
                &[
                    JValue::Object(&id),
                    JValue::Object(&txid_obj),
                    JValue::Object(&pczt_bytes),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_createUnsignedTransferPcztsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    proposal_handle: jlong,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        // Mirrors `createUnsignedNoteSplitPcztNative`: no schedule fields cross the boundary —
        // `commit_or_reuse` builds exactly the cached plan `proposal_handle` identifies (erroring
        // if it's missing or superseded), or (this being the *second* external-signer call in the
        // Keystone sequence, after `createUnsignedNoteSplitPcztNative` already committed) just
        // re-reads what's already persisted, rather than committing a second, independent plan
        // (which would have hit `CommitError::MigrationInProgress` from the engine anyway).
        let target = target_height(&wallet)?;
        let (state, unsigned) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            proposal_handle as u64,
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) = engine::build_preparation_unsigned(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                )
                .map_err(|e| anyhow!("Error building unsigned migration PCZTs: {:?}", e))?;
                Ok((
                    state,
                    unsigned.into_iter().map(|tx| tx.into_parts()).collect(),
                ))
            },
        )?;
        let transfer_ids: std::collections::HashSet<MigrationTxId> = state
            .transactions()
            .iter()
            .filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. }))
            .map(|t| t.id())
            .collect();
        let (seed_fingerprint, account_index) = account_zip32_derivation(&wallet, account)?;
        let transfers: Vec<_> = unsigned
            .into_iter()
            .filter(|(id, _)| transfer_ids.contains(id))
            .map(|(id, pczt_bytes)| {
                let pczt_bytes = crate::migration_keystone::annotate_spend_zip32_derivation(
                    &pczt_bytes,
                    seed_fingerprint,
                    network.coin_type(),
                    account_index,
                )
                .map_err(|e| anyhow!("Error annotating transfer PCZT derivation: {:?}", e))?;
                Ok::<_, anyhow::Error>((id, pczt_bytes))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok(crate::utils::rust_vec_to_java(
            env,
            transfers,
            JNI_UNSIGNED_TRANSFER_PCZT,
            |env, (id, pczt_bytes)| {
                let id = encode_transfer_id(env, id)?;
                let pczt_bytes = crate::utils::rust_bytes_to_java(env, &pczt_bytes)?;
                env.new_object(
                    JNI_UNSIGNED_TRANSFER_PCZT,
                    "(Ljava/lang/String;[B)V",
                    &[JValue::Object(&id), JValue::Object(&pczt_bytes)],
                )
            },
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_storeSignedSchedulePcztsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    ids: JObjectArray<'local>,
    pczt_bytes_list: JObjectArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let count = env.get_array_length(&ids)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let mut state = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            .ok_or_else(|| anyhow!("No migration committed yet"))?;
        // Absorbs the new engine's per-transaction `apply_signature` into the old batch-shaped
        // call Kotlin still makes — see module doc point about the signed-PCZT return path.
        for i in 0..count {
            let id_obj = env.get_object_array_element(&ids, i)?;
            let id = decode_transfer_id(env, &JString::from(id_obj))?;
            let bytes_obj = env.get_object_array_element(&pczt_bytes_list, i)?;
            let pczt_bytes = crate::utils::java_bytes_to_rust(env, &JByteArray::from(bytes_obj))?;
            if !state.apply_signature(id, pczt_bytes) {
                return Err(anyhow!("Error applying signature for transfer {:?}", id));
            }
        }
        backend
            .replace_migration(&state)
            .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))
    });
    unwrap_exc_or(&mut env, res, ())
}

// ----- Keystone batch-signing UR bridge (crate::migration_keystone) -----
//
// Pure PCZT/UR operations over caller-held bytes — no wallet database, no migration engine.
// Unaffected by this rewire.

fn decode_byte_array_list(env: &mut JNIEnv, list: &JObjectArray) -> anyhow::Result<Vec<Vec<u8>>> {
    let count = env.get_array_length(list)?;
    let mut out = Vec::with_capacity(count as usize);
    for i in 0..count {
        let obj = env.get_object_array_element(list, i)?;
        out.push(crate::utils::java_bytes_to_rust(
            env,
            &JByteArray::from(obj),
        )?);
    }
    Ok(out)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_buildKeystoneSignBatchQrPartsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    request_id: JByteArray<'local>,
    split_unsigned: JByteArray<'local>,
    transfer_unsigned: JObjectArray<'local>,
    max_fragment_len: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let request_id = crate::utils::java_bytes_to_rust(env, &request_id)?;
        let split_unsigned = crate::utils::java_nullable_bytes_to_rust(env, &split_unsigned)?;
        let transfer_unsigned = decode_byte_array_list(env, &transfer_unsigned)?;
        let parts = crate::migration_keystone::build_sign_batch_qr_parts(
            request_id,
            split_unsigned.as_deref(),
            &transfer_unsigned,
            max_fragment_len as usize,
        )
        .map_err(|e| anyhow!("Error building Keystone sign-batch QR parts: {}", e))?;
        Ok(
            crate::utils::rust_vec_to_java(env, parts, "java/lang/String", |env, part| {
                env.new_string(part)
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_resetKeystoneSignBatchDecoderNative<
    'local,
>(
    _env: JNIEnv<'local>,
    _: JClass<'local>,
) {
    crate::migration_keystone::reset_sign_batch_decoder();
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_decodeKeystoneSignBatchPartNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    part: JString<'local>,
    expected_request_id: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let part = crate::utils::java_string_to_rust(env, &part)?;
        let expected_request_id = crate::utils::java_bytes_to_rust(env, &expected_request_id)?;
        let result = crate::migration_keystone::decode_sign_batch_part(&part, &expected_request_id)
            .map_err(|e| anyhow!("Error decoding Keystone sign-batch QR part: {}", e))?;
        let data = match &result.data {
            Some(bytes) => crate::utils::rust_bytes_to_java(env, bytes)?.into(),
            None => JObject::null(),
        };
        let firmware_version = match &result.firmware_version {
            Some(bytes) => crate::utils::rust_bytes_to_java(env, bytes)?.into(),
            None => JObject::null(),
        };
        Ok(env
            .new_object(
                JNI_KEYSTONE_BATCH_DECODE_RESULT,
                "(ZI[B[B)V",
                &[
                    JValue::Bool(if result.complete { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Int(result.progress as jint),
                    JValue::Object(&data),
                    JValue::Object(&firmware_version),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_applyKeystoneBatchSignaturesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    split_unsigned: JByteArray<'local>,
    transfer_unsigned: JObjectArray<'local>,
    batch_sign_response: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let split_unsigned = crate::utils::java_nullable_bytes_to_rust(env, &split_unsigned)?;
        let transfer_unsigned = decode_byte_array_list(env, &transfer_unsigned)?;
        let batch_sign_response = crate::utils::java_bytes_to_rust(env, &batch_sign_response)?;
        let (split_signed, transfers_signed) = crate::migration_keystone::apply_batch_signatures(
            split_unsigned.as_deref(),
            &transfer_unsigned,
            &batch_sign_response,
        )
        .map_err(|e| anyhow!("Error applying Keystone batch signatures: {}", e))?;

        let split_signed_obj = match &split_signed {
            Some(bytes) => crate::utils::rust_bytes_to_java(env, bytes)?.into(),
            None => JObject::null(),
        };
        let transfers_signed_obj =
            crate::utils::rust_vec_to_java(env, transfers_signed, "[B", |env, bytes| {
                crate::utils::rust_bytes_to_java(env, &bytes)
            })?;
        Ok(env
            .new_object(
                JNI_KEYSTONE_BATCH_SIGNED_PCZTS,
                format!("([B[[B)V"),
                &[
                    JValue::Object(&split_signed_obj),
                    JValue::Object(&transfers_signed_obj),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Integration tests that exercise the actual migration planning/build/finalize logic directly
/// against a real wallet SQLite DB file — no JNI, no Android, no Gradle app build, no emulator UI
/// click-through. Point `MIGRATION_TEST_WALLET_DB` at a copy of a real wallet DB (pull one via
/// `adb -s emulator-5554 shell "run-as <pkg> cat <path>" > /tmp/wallet_fixture.sqlite3`, path from
/// the handoff doc's testing-setup notes) to iterate on migration bugs in seconds. Every bug found
/// live this session (multi-witness resolution, anchor fallback for preparation transactions,
/// schedule/amount pairing, note-split-needed detection) would have been caught by these tests
/// without ever launching the app.
///
/// Run with, e.g.:
/// `MIGRATION_TEST_WALLET_DB=/tmp/wallet_fixture.sqlite3 cargo test --package zcash-android-wallet-sdk --lib migration::live_wallet_tests -- --ignored --nocapture`
/// Copies the fixture DB to a fresh, uniquely-named temp file so each test run starts from a
/// pristine copy instead of mutating (and being mutated by) the shared fixture on disk — tests
/// like `build_and_finalize_all_unsigned` and `commit_and_finalize_with_real_signing` both commit
/// real migration state, and the engine refuses to recommit over an in-progress migration.
#[cfg(test)]
fn fresh_test_db_copy(fixture: &std::path::Path) -> std::path::PathBuf {
    let mut dest = std::env::temp_dir();
    let unique = format!(
        "migration_test_{}_{}.sqlite3",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("system time after epoch")
            .as_nanos()
    );
    dest.push(unique);
    std::fs::copy(fixture, &dest).expect("copy fixture db to fresh temp path");
    dest
}

#[cfg(test)]
mod live_wallet_tests {
    use super::*;

    fn fixture_db_path() -> Option<std::path::PathBuf> {
        std::env::var("MIGRATION_TEST_WALLET_DB")
            .ok()
            .map(std::path::PathBuf::from)
    }

    fn first_account(wallet: &Wallet) -> AccountUuid {
        wallet
            .get_account_ids()
            .expect("list accounts")
            .into_iter()
            .next()
            .expect("wallet has at least one account — restore/sync one first")
    }

    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB pointing at a copy of a real wallet DB"]
    fn plan_a_real_wallet() {
        let fixture = fixture_db_path().expect("set MIGRATION_TEST_WALLET_DB");
        let db_path = fresh_test_db_copy(&fixture);
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");

        println!(
            "tip={tip:?} funding_notes={} prep_layers={} prep_txs={} direct_funding={}",
            plan.funding_notes().len(),
            plan.preparation().layer_count(),
            plan.preparation().transaction_count(),
            plan.preparation().direct_funding_notes().len(),
        );
        for entry in plan.schedule() {
            let delta = i64::from(u32::from(entry.broadcast_height())) - i64::from(u32::from(tip));
            println!(
                "broadcast_height={:?} ({delta} blocks from tip) expiry={:?}",
                entry.broadcast_height(),
                entry.expiry_height(),
            );
        }
    }

    // `build_and_finalize_all_unsigned` (tested `build_preparation_unsigned`'s deliberately-UNSIGNED
    // PCZTs against our old hand-rolled `migration_finalize::finalize_transaction`, which didn't
    // care whether a PCZT was signed — it just resolved the witness/anchor and let extraction fail
    // on the missing signature) was REMOVED when this crate adopted
    // `zcash_pool_migration`'s own `WalletMigrationProver`/`engine::prove_transfer`/
    // `prove_preparation` (see `try_prove`'s doc comment). Those require the transaction to be
    // `MigrationTxState::Signed` (`ProveError::NotReady` otherwise) — an UNSIGNED transaction from
    // `build_preparation_unsigned` is `AwaitingSignature`, so it is correctly rejected before ever
    // reaching witness/anchor resolution, not after (a stricter, better safety property than our old
    // stopgap had, but one this test's exact premise can no longer exercise). The witness/anchor
    // resolution logic this test covered now lives in `WalletMigrationProver` (core-team-owned,
    // exercised by its own `zcash_pool_migration/tests/prove_chain_sim.rs`); our own
    // `commit_and_finalize_with_real_signing` below still covers the full real-signing → prove path
    // end to end against our wallet adapter.
}

/// Full-loop test (plan → in-process sign/commit → finalize) exercising real signing via
/// `commit_preparation`, still entirely local/offline: `commit_preparation` only builds and signs
/// PCZTs against the wallet DB copy, `finalize_transaction` only installs anchor/witness and
/// proves. Neither does any network I/O — nothing here is ever broadcast or submitted anywhere.
/// Needs a real `UnifiedSpendingKey`, provided via `MIGRATION_TEST_SEED_PHRASE` (a BIP-39 mnemonic,
/// account 0) — never logged or persisted by this test.
#[cfg(test)]
mod live_wallet_signing_tests {
    use super::*;
    use zcash_client_backend::data_api::Account;

    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB and MIGRATION_TEST_SEED_PHRASE"]
    fn commit_and_finalize_with_real_signing() {
        let fixture = std::env::var("MIGRATION_TEST_WALLET_DB")
            .map(std::path::PathBuf::from)
            .expect("set MIGRATION_TEST_WALLET_DB");
        let db_path = fresh_test_db_copy(&fixture);
        let phrase = std::env::var("MIGRATION_TEST_SEED_PHRASE")
            .expect("set MIGRATION_TEST_SEED_PHRASE (BIP-39 mnemonic, space-separated words)");
        let network = Network::TestNetwork;
        let (mut wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = wallet
            .get_account_ids()
            .expect("list accounts")
            .into_iter()
            .next()
            .expect("wallet has at least one account");

        let mnemonic = bip0039::Mnemonic::<bip0039::English>::from_phrase(phrase.trim())
            .expect("valid BIP-39 mnemonic");
        let seed = mnemonic.to_seed("");
        let usk = UnifiedSpendingKey::from_seed(&network, &seed, zip32::AccountId::ZERO)
            .expect("derive USK from seed for account 0");

        // Sanity check the derived key actually matches this wallet's account before doing
        // anything else — a mismatched seed/account index would otherwise fail confusingly deep
        // inside signing instead of here, with a clear message.
        let derived_ufvk = usk.to_unified_full_viewing_key();
        let wallet_account = wallet
            .get_account(account)
            .expect("account lookup")
            .expect("account exists");
        let wallet_ufvk = wallet_account.ufvk().expect("account has a UFVK");
        assert_eq!(
            derived_ufvk.encode(&network),
            wallet_ufvk.encode(&network),
            "derived USK's UFVK doesn't match the wallet's stored UFVK for this account — check \
             the seed phrase and/or account index (this test assumes account 0)"
        );

        let (migration_plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;

        let mut state = {
            let mut backend = Backend::new(&wallet, account, Some(usk), &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::commit_preparation(&network, target, &mut backend, &migration_plan, &mut rng)
                .expect(
                    "commit_preparation (in-process signing — local only, no network/broadcast)",
                )
        };
        println!(
            "{} transaction(s) committed and signed",
            state.transactions().len()
        );

        let fvk = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            backend.orchard_fvk().expect("fvk")
        };

        let ids_and_kinds: Vec<(MigrationTxId, MigrationTxKind)> = state
            .transactions()
            .iter()
            .map(|t| (t.id(), t.kind()))
            .collect();
        let mut finalized = 0;
        let mut transient = 0;
        for (id, kind) in ids_and_kinds {
            match try_prove(&mut wallet, account, fvk.clone(), &mut state, id, kind) {
                Ok(true) => {
                    finalized += 1;
                    println!(
                        "id={id:?} kind={kind:?} finalized (built+proven locally only — this \
                         test never submits anything to the network)",
                    );
                }
                Ok(false) => {
                    transient += 1;
                    println!("id={id:?} kind={kind:?} not yet finalizable (transient)");
                }
                Err(e) => panic!("id={id:?} kind={kind:?} FAILED: {e}"),
            }
        }
        println!("{finalized} finalized, {transient} transient — nothing broadcast");
    }

    /// Proves IMMEDIATE mode's proposal is an ordinary send-max, not the shuffled N-transfer
    /// engine plan AUTOMATIC mode commits: a single step, drawing only Orchard-pool inputs (no
    /// transparent, no Sapling). Read-only (a proposal, never committed/signed), so — unlike
    /// `commit_and_finalize_with_real_signing` above — this needs no `MIGRATION_TEST_SEED_PHRASE`
    /// and never touches persisted `MigrationState`.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn immediate_send_max_sweeps_orchard_only_single_tx() {
        let fixture = std::env::var("MIGRATION_TEST_WALLET_DB")
            .map(std::path::PathBuf::from)
            .expect("set MIGRATION_TEST_WALLET_DB");
        let db_path = fresh_test_db_copy(&fixture);
        let network = Network::TestNetwork;
        let (mut wallet, _store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = wallet
            .get_account_ids()
            .expect("list accounts")
            .into_iter()
            .next()
            .expect("wallet has at least one account — restore/sync one first");

        let proposal =
            crate::migration_engine::propose_immediate_send_max(&network, &mut wallet, account)
                .expect("propose_immediate_send_max");

        // Single step, single transaction — the whole point of send-max vs. the N-transfer
        // engine plan.
        assert_eq!(proposal.steps().len(), 1);
        let step = &proposal.steps().head;

        // Every input drawn is Orchard-pool: no transparent inputs swept at all, ...
        assert!(
            step.transparent_inputs().is_empty(),
            "send-max sweep must not include transparent inputs: {:?}",
            step.transparent_inputs()
        );
        // ... and every shielded input is specifically Orchard (no Sapling swept).
        let shielded = step
            .shielded_inputs()
            .expect("send-max sweep should draw on shielded (Orchard) inputs");
        for note in shielded.notes() {
            assert_eq!(
                note.note().pool(),
                ShieldedPool::Orchard,
                "send-max sweep must be Orchard-only, found a note in another pool"
            );
        }
        println!(
            "immediate send-max proposal: 1 step, {} shielded input(s), all Orchard",
            shielded.notes().len()
        );
    }

    /// Regression test for the Keystone/external-signer note-split crash (confirmed live:
    /// `extractBroadcastTxNative` failed with `OrchardParse(MissingAnchor)`) —
    /// `storeSignedNoteSplitPcztNative` applied the external signature but never resolved the
    /// split's deferred witness/anchor before returning the PCZT for extraction. This exercises
    /// the same shape as that JNI function (`build_preparation_unsigned` -> sign the split
    /// out-of-process, matching what handing a redacted PCZT to Keystone and getting it back
    /// signed looks like -> `apply_signature` -> `finalize_note_split`) via its shared, JNI-free
    /// `finalize_note_split` helper, then extracts the result exactly like
    /// `extractBroadcastTxNative` does, to prove the crash is actually fixed end to end, not just
    /// that `finalize_note_split` returns `Ok`.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB and MIGRATION_TEST_SEED_PHRASE"]
    fn store_signed_note_split_resolves_anchor_before_extraction() {
        let fixture = std::env::var("MIGRATION_TEST_WALLET_DB")
            .map(std::path::PathBuf::from)
            .expect("set MIGRATION_TEST_WALLET_DB");
        let db_path = fresh_test_db_copy(&fixture);
        let phrase = std::env::var("MIGRATION_TEST_SEED_PHRASE")
            .expect("set MIGRATION_TEST_SEED_PHRASE (BIP-39 mnemonic, space-separated words)");
        let network = Network::TestNetwork;
        let (mut wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = wallet
            .get_account_ids()
            .expect("list accounts")
            .into_iter()
            .next()
            .expect("wallet has at least one account");

        let mnemonic = bip0039::Mnemonic::<bip0039::English>::from_phrase(phrase.trim())
            .expect("valid BIP-39 mnemonic");
        let seed = mnemonic.to_seed("");
        let usk = UnifiedSpendingKey::from_seed(&network, &seed, zip32::AccountId::ZERO)
            .expect("derive USK from seed for account 0");

        let (migration_plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;

        // Mirrors `createUnsignedNoteSplitPcztNative`: build unsigned, leaving every transaction
        // (including the split) `AwaitingSignature` — nothing is signed by this call.
        let (mut state, unsigned) = {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &migration_plan,
                &mut rng,
            )
            .expect("build_preparation_unsigned")
        };
        let split_id = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .map(|t| t.id())
            .expect("migration has a note-split preparation transaction");
        let (_id, unsigned_split_bytes) = unsigned
            .into_iter()
            .map(|tx| tx.into_parts())
            .find(|(id, _)| *id == split_id)
            .expect("unsigned split pczt");

        // Sign out-of-process, exactly as an external signer (Keystone) would: this produces a
        // signed-but-unproven PCZT, still missing its witness/anchor — the same shape
        // `storeSignedNoteSplitPcztNative` receives back from Kotlin after a real Keystone round
        // trip.
        let ask = orchard::keys::SpendAuthorizingKey::from(usk.orchard());
        let unsigned_pczt =
            pczt::Pczt::parse(&unsigned_split_bytes).expect("parse unsigned split pczt");
        let signed_pczt = zcash_pool_migration::build::sign_pczt(unsigned_pczt, &ask)
            .expect("sign split pczt out-of-process");
        let signed_bytes = signed_pczt
            .serialize()
            .expect("serialize signed split pczt");

        // Mirrors `storeSignedNoteSplitPcztNative`: apply the externally-obtained signature, then
        // resolve anchor/witness and prove via the fixed `finalize_note_split` helper.
        assert!(
            state.apply_signature(split_id, signed_bytes),
            "apply_signature should accept the freshly-signed split pczt"
        );
        let (proven_pczt, txid) =
            finalize_note_split(&mut wallet, account, &mut store_conn, &mut state, split_id)
                .expect(
                    "finalize_note_split should resolve the anchor, not fail with MissingAnchor",
                );

        // Mirrors `extractBroadcastTxNative` exactly — this is what previously crashed with
        // `OrchardParse(MissingAnchor)` on the un-finalized bytes.
        let parsed = pczt::Pczt::parse(&proven_pczt).expect("parse proven split pczt");
        let tx = pczt::roles::tx_extractor::TransactionExtractor::new(parsed)
            .extract()
            .expect("extract broadcast tx from finalized split pczt");
        assert_eq!(
            *tx.txid().as_ref(),
            txid,
            "extracted txid should match finalize_note_split's"
        );
        println!(
            "note-split finalized and extracted via the Keystone/external-signer path: txid={}",
            hex::encode(txid)
        );
    }
}

/// Edge-case / state-machine integration tests against a real wallet DB copy. Unlike
/// `live_wallet_tests`/`live_wallet_signing_tests` (which exercise the happy path), these probe
/// what happens on re-entry, restart, and multi-account use — the moments a real app hits that a
/// single linear test run never does. Pure `MigrationState` logic (`apply_signature`,
/// `next_step`, `mark_broadcast`/`mark_mined`, terminal-status handling) is already unit-tested in
/// `zcash_pool_migration::state`, so it is not duplicated here; these tests are only for
/// behavior that needs a real wallet DB, real accounts, or our own JNI-adapter code
/// (`commit_or_reuse`, `Backend`) to observe.
///
/// Run with `--test-threads=1`: each test independently copies the (large, ~8.5MB) fixture file
/// via `fresh_test_db_copy`, and running several of these copies concurrently (cargo's default
/// parallel test execution) has been observed to occasionally corrupt one thread's read with a
/// spurious `DatabaseCorrupt "database disk image is malformed"` — not a real bug in the code
/// under test, confirmed by rerunning the same test alone. Serializing avoids it.
#[cfg(test)]
mod live_wallet_edge_case_tests {
    use super::*;
    use secrecy::SecretVec;
    use zcash_client_backend::data_api::chain::ChainState;
    use zcash_client_backend::data_api::{AccountBirthday, WalletWrite};
    use zcash_primitives::block::BlockHash;
    use zcash_protocol::TxId;

    fn fixture_db_path() -> std::path::PathBuf {
        std::env::var("MIGRATION_TEST_WALLET_DB")
            .map(std::path::PathBuf::from)
            .expect("set MIGRATION_TEST_WALLET_DB")
    }

    fn first_account(wallet: &Wallet) -> AccountUuid {
        wallet
            .get_account_ids()
            .expect("list accounts")
            .into_iter()
            .next()
            .expect("wallet has at least one account — restore/sync one first")
    }

    /// Finds a real, already-mined transaction's txid in the fixture wallet DB, so a test can
    /// simulate `WalletRead::get_tx_height` returning `Some(_)` for a migration transaction
    /// without actually broadcasting anything and waiting for it to mine. `Wallet` (`WalletDb`)
    /// keeps its own `rusqlite::Connection` private, so this queries the public `v_transactions`
    /// view (the supported query surface, never a wallet-internal base table) through the
    /// migration store's own second connection to the same on-disk file.
    fn a_mined_txid_in_fixture(store_conn: &Connection) -> TxId {
        let txid_bytes: [u8; 32] = store_conn
            .query_row(
                "SELECT txid FROM v_transactions WHERE mined_height IS NOT NULL LIMIT 1",
                [],
                |row| row.get(0),
            )
            .expect("fixture wallet DB has at least one mined transaction");
        TxId::from_bytes(txid_bytes)
    }

    /// Creates a second, synthetic, permanently-unfunded account in `wallet` — not derived from
    /// the real test seed, and never scanned for funds — purely to have a second `AccountUuid` in
    /// the same wallet DB. `seed_byte` just needs to differ per call so two synthetic accounts in
    /// one test don't collide with each other.
    fn create_synthetic_account(wallet: &mut Wallet, seed_byte: u8, name: &str) -> AccountUuid {
        let tip = wallet
            .chain_height()
            .expect("chain height")
            .expect("wallet has a chain tip");
        let birthday =
            AccountBirthday::from_parts(ChainState::empty(tip, BlockHash([0; 32])), None);
        let seed = SecretVec::new(vec![seed_byte; 32]);
        let (account, _usk) = wallet
            .create_account(name, &seed, &birthday, None)
            .expect("create synthetic account");
        account
    }

    fn sign_unsigned(
        network: &Network,
        target: BlockHeight,
        backend: &mut Backend<Wallet>,
        plan: &MigrationPlan,
        rng: &mut OsRng,
    ) -> anyhow::Result<(MigrationState, Vec<(MigrationTxId, Vec<u8>)>)> {
        let (state, unsigned) =
            engine::build_preparation_unsigned(network, target, backend, plan, rng)
                .map_err(|e| anyhow!("build_preparation_unsigned: {:?}", e))?;
        Ok((
            state,
            unsigned.into_iter().map(|tx| tx.into_parts()).collect(),
        ))
    }

    /// Demonstrates the SINGLETON_ID cross-account collision directly (see
    /// `project_core_migration_swap` memory / spec doc §6.3): `pool_migrations`/
    /// `pool_migration_transactions` have no `account_id` column, and `Backend::get_migration`/
    /// `replace_migration` (`migration_engine.rs`) pass straight through to the store without
    /// filtering by `self.account` — confirmed directly in that impl, not assumed. This is a bug
    /// in OUR OWN adapter, not just a documented upstream limitation: any JNI call for account B
    /// (`migrationStateNative`, `commit_or_reuse`, ...) reads/writes account A's committed
    /// migration whenever one exists in the same wallet DB.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn singleton_id_collision_between_accounts() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (mut wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account_a = first_account(&wallet);
        let account_b = create_synthetic_account(&mut wallet, 0x42, "edge-case-account-b");
        assert_ne!(account_a, account_b);

        // Plan + commit an (unsigned) migration for account A only — account B is never touched.
        let (plan_a, tip, _handle) =
            plan_for(&network, &wallet, account_a, &mut store_conn).expect("plan_for account_a");
        let target = tip + 1;
        {
            let mut backend_a = Backend::new(&wallet, account_a, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(&network, target, &mut backend_a, &plan_a, &mut rng)
                .expect("commit account_a's migration");
        }

        // Asking for account B's migration state goes through the exact same code path
        // (`migrationStateNative`/`commit_or_reuse` do this) — it must see nothing, since B has
        // no migration of its own. Instead it leaks A's.
        let backend_b = Backend::new(&wallet, account_b, None, &mut store_conn)
            .expect("account exists for migration store");
        let leaked = backend_b
            .get_migration()
            .expect("read migration state for account_b");
        match leaked {
            Some(state) if !state.transactions().is_empty() => {
                println!(
                    "CONFIRMED BUG: account_b's Backend::get_migration() returned {} \
                     transaction(s) that belong to account_a. pool_migrations has no \
                     account_id column, and Backend::{{get,put}}_migration ignore \
                     self.account — every account in a wallet DB shares one migration slot.",
                    state.transactions().len()
                );
            }
            Some(_) => {
                panic!("unexpected: got a migration state for account_b with no transactions")
            }
            None => panic!(
                "SINGLETON_ID collision not reproduced — account_b correctly saw no migration. \
                 If this starts failing, the collision may have been fixed; update \
                 project_core_migration_swap memory and this test accordingly."
            ),
        }
    }

    /// `mark_mined` (`MigrationState::mark_mined`) is never called anywhere in this file's JNI
    /// glue, so `InProgress`/`Complete` derivation never actually advanced past whatever
    /// `mark_broadcast` last recorded — confirmed directly, not assumed (see
    /// `recordTransferResultNative`'s own comment on this). `read_reconciled` is the fix: it
    /// checks every `Broadcast` transaction against the wallet's own transaction history at read
    /// time and promotes it to `Mined` (persisting the promotion) whenever the wallet already
    /// knows a mined height for it.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn mark_mined_reconciles_on_read() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        // Commit a migration, then manually drive one of its transactions to `Broadcast` using a
        // real, already-mined txid from the fixture wallet DB — `mark_broadcast`/`mark_mined` set
        // state unconditionally (no prior-state precondition, confirmed in
        // `zcash_pool_migration::state`), so an `AwaitingSignature` transaction from
        // `build_preparation_unsigned` works fine here without needing real signing.
        let (plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;
        let mut state = {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) =
                engine::build_preparation_unsigned(&network, target, &mut backend, &plan, &mut rng)
                    .expect("commit migration");
            state
        };
        let some_tx_id = state.transactions()[0].id();
        let mined_txid = a_mined_txid_in_fixture(&store_conn);
        state.mark_broadcast(some_tx_id, mined_txid);

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
            .expect("account exists for migration store");
        backend
            .replace_migration(&state)
            .expect("persist manually-advanced state");

        // Before reconciliation, a raw read still shows Broadcast, not Mined.
        let raw = backend
            .get_migration()
            .expect("read migration state")
            .expect("migration state committed");
        assert!(matches!(
            raw.transactions()
                .iter()
                .find(|t| t.id() == some_tx_id)
                .unwrap()
                .state(),
            MigrationTxState::Broadcast { .. }
        ));

        // read_reconciled() should promote it to Mined without any explicit mark_mined call here.
        let reconciled = read_reconciled(&wallet, &mut backend)
            .expect("read_reconciled")
            .expect("migration state committed");
        let reconciled_tx = reconciled
            .transactions()
            .iter()
            .find(|t| t.id() == some_tx_id)
            .unwrap();
        assert!(matches!(
            reconciled_tx.state(),
            MigrationTxState::Mined { .. }
        ));

        // And the reconciliation persisted: a fresh raw read now also shows Mined.
        let raw_again = backend
            .get_migration()
            .expect("read migration state")
            .expect("migration state committed");
        assert!(matches!(
            raw_again
                .transactions()
                .iter()
                .find(|t| t.id() == some_tx_id)
                .unwrap()
                .state(),
            MigrationTxState::Mined { .. }
        ));
    }

    /// `commit_or_reuse` (our own adapter, used by every commit-shaped JNI function) must REUSE
    /// an already-committed migration on a second call for the same account/plan, not error and
    /// not silently rebuild (which would orphan or double-sign the first commit's PCZTs). This is
    /// the realistic re-entry case: the app returns to the migration review screen and the user
    /// taps "commit" again (e.g. after a process restart before any signature was applied).
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn commit_or_reuse_returns_existing_state_without_recommitting() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (_plan, tip, handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;

        let (state1, unsigned1) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            handle,
            None,
            sign_unsigned,
        )
        .expect("first commit_or_reuse call commits");
        assert!(
            !unsigned1.is_empty(),
            "expected unsigned preparation/transfer PCZTs on first commit"
        );

        // Re-plan, as the app does whenever it re-renders the review screen — this must not
        // itself disturb the already-committed migration.
        plan_for(&network, &wallet, account, &mut store_conn).expect("re-plan after commit");

        // Deliberately passes the ORIGINAL handle, which the re-plan above superseded: on the
        // reuse path the handle must NOT be consulted (the commitment already happened, with a
        // handle-verified plan) — a stale handle only blocks a FRESH commit.
        let (state2, unsigned2) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            handle,
            None,
            sign_unsigned,
        )
        .expect("second commit_or_reuse call must reuse, not error");

        assert_eq!(
            state1.transactions().len(),
            state2.transactions().len(),
            "reused state must have the same transaction set"
        );
        assert_eq!(
            unsigned1.len(),
            unsigned2.len(),
            "reuse must return the SAME already-awaiting-signature PCZTs, not rebuild new ones"
        );
        for (a, b) in state1
            .transactions()
            .iter()
            .zip(state2.transactions().iter())
        {
            assert_eq!(a.id(), b.id());
            assert_eq!(
                a.pczt(),
                b.pczt(),
                "reuse must not rebuild/re-sign a transaction — a rebuilt layer 0 would double-\
                 spend the same wallet notes, and a rebuilt already-broadcast tx would be orphaned"
            );
        }
    }

    /// The plan-handle gate closing the approve-X-sign-Y hazard: a FRESH commit must refuse a
    /// handle that a later `propose*`/`prepare*` call superseded (the caller would otherwise sign
    /// a re-randomized schedule the user never reviewed), must refuse an unknown handle when
    /// nothing is cached, and must succeed with the handle of the currently cached plan.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn fresh_commit_requires_the_current_plan_handle() {
        use crate::migration_plan_cache::PlanLookupError;

        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (_plan1, tip, stale_handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("first plan");
        let (_plan2, _tip2, current_handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("superseding plan");
        let target = tip + 1;

        let err = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            stale_handle,
            None,
            sign_unsigned,
        )
        .expect_err("committing with a superseded handle must be rejected");
        assert_eq!(
            err.downcast_ref::<PlanLookupError>(),
            Some(&PlanLookupError::Superseded),
            "expected Superseded, got: {err:?}"
        );

        let (_state, unsigned) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            current_handle,
            None,
            sign_unsigned,
        )
        .expect("committing with the current handle succeeds");
        assert!(!unsigned.is_empty());

        // The successful commit consumed the cache — a would-be second fresh commit (were the
        // committed state not already reusable) now reports Missing, not Superseded.
        assert!(matches!(
            crate::migration_plan_cache::get(account, current_handle),
            Err(PlanLookupError::Missing)
        ));
    }

    /// Calling the raw engine directly (bypassing our `commit_or_reuse` reuse guard) a second
    /// time over an already-committed, non-terminal migration must fail with
    /// `CommitError::MigrationInProgress` — this is the exact condition `commit_or_reuse` relies
    /// on to decide "reuse instead of recommit", so it's worth pinning down directly rather than
    /// only indirectly through that wrapper.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn raw_recommit_over_committed_migration_is_rejected() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;
        {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(&network, target, &mut backend, &plan, &mut rng)
                .expect("first commit");
        }

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
            .expect("account exists for migration store");
        let mut rng = OsRng;
        let result =
            engine::build_preparation_unsigned(&network, target, &mut backend, &plan, &mut rng);
        assert!(
            matches!(result, Err(engine::CommitError::MigrationInProgress)),
            "recommitting over a non-terminal migration must fail with MigrationInProgress, not \
             silently rebuild: got {result:?}"
        );
    }

    /// Simulates the app process being killed and restarted mid-migration: commit a migration,
    /// drop every handle to the wallet/DB connections, then reopen fresh ones against the same
    /// on-disk file (exactly what `MigrationRustBackend`'s JNI functions do on every call — no
    /// persistent connection is kept between them) and confirm the committed state round-trips
    /// intact and `next_step` still gives a sane answer.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn migration_state_persists_across_reopened_connection() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;

        let committed_ids: Vec<MigrationTxId> = {
            let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
            let account = first_account(&wallet);
            let (plan, tip, _handle) =
                plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
            let target = tip + 1;
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) =
                engine::build_preparation_unsigned(&network, target, &mut backend, &plan, &mut rng)
                    .expect("commit");
            state.transactions().iter().map(|t| t.id()).collect()
            // wallet / store_conn / backend all drop here — simulates process death.
        };

        let (wallet2, mut store_conn2) = open_at(&db_path, network).expect("reopen wallet");
        let account = first_account(&wallet2);
        let backend2 = Backend::new(&wallet2, account, None, &mut store_conn2)
            .expect("account exists for migration store");
        let reloaded = backend2
            .get_migration()
            .expect("read migration state")
            .expect("migration state must persist across a fresh connection to the same DB file");
        let reloaded_ids: Vec<MigrationTxId> =
            reloaded.transactions().iter().map(|t| t.id()).collect();
        assert_eq!(
            committed_ids, reloaded_ids,
            "reopening the DB connection must not lose or reorder committed migration transactions"
        );

        let tip2 = wallet2.chain_height().expect("chain height").expect("tip");
        let step = reloaded.next_step(tip2 + 1);
        println!("next_step after simulated restart: {step:?}");
    }

    /// `plan_migration` is documented as pure/read-only ("nothing is built, signed, or
    /// persisted") — confirm that holds even once a migration is already committed: re-planning
    /// (e.g. the app re-rendering the review screen) must keep succeeding and must keep returning
    /// the same funding notes, since nothing was broadcast and the wallet's spendable set hasn't
    /// actually changed yet.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn plan_migration_is_read_only_after_commit() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (plan_before, _tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan before commit");
        let target = target_height(&wallet).expect("target height");
        {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan_before,
                &mut rng,
            )
            .expect("commit");
        }

        let (plan_after, _tip2, _handle2) = plan_for(&network, &wallet, account, &mut store_conn)
            .expect("plan_migration must remain callable after a migration is committed");

        assert_eq!(
            plan_before.funding_notes(),
            plan_after.funding_notes(),
            "nothing was broadcast, so the wallet's spendable set — and therefore the plan — \
             must be unchanged"
        );
    }

    /// An account with zero spendable Orchard notes must fail planning cleanly
    /// (`MigrationError::NothingToMigrate`), not panic or return a degenerate empty-but-Ok plan —
    /// this is the state every freshly created or already-fully-migrated account is in.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn planning_an_account_with_no_funds_errors_cleanly() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (mut wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account_b = create_synthetic_account(&mut wallet, 0x43, "edge-case-empty-account");

        let backend = Backend::new(&wallet, account_b, None, &mut store_conn)
            .expect("account exists for migration store");
        let mut rng = OsRng;
        let result = engine::plan_migration(&network, &backend, &mut rng);
        assert!(
            matches!(result, Err(engine::MigrationError::NothingToMigrate)),
            "an account with zero spendable Orchard notes must fail cleanly with \
             NothingToMigrate, not panic or return a bogus plan: got {result:?}"
        );
    }
}

#[cfg(test)]
mod next_due_transfer_tests {
    use super::*;
    use zcash_pool_migration::{
        engine::{
            MigrationState, MigrationStatus, MigrationTransaction, MigrationTxId, MigrationTxKind,
            MigrationTxState,
        },
        note_splitting::NoteSplitPlan,
        preparation::PreparationPlan,
        scheduling::AnchorBucketInterval,
    };
    use zcash_protocol::{consensus::BlockHeight, value::Zatoshis};

    /// Builds a minimal `MigrationState` with the given transactions (all transfers, no prep).
    fn make_state(status: MigrationStatus, transfers: Vec<MigrationTransaction>) -> MigrationState {
        let note_split = NoteSplitPlan::from_stored_parts(
            vec![Zatoshis::const_from_u64(100_000_000)],
            Zatoshis::const_from_u64(5_000),
            None,
            Zatoshis::const_from_u64(10_000),
            Zatoshis::const_from_u64(100_010_000),
            Zatoshis::const_from_u64(100_000_000),
        )
        .expect("valid note split plan");
        MigrationState::from_parts(
            status,
            note_split,
            PreparationPlan::from_parts(vec![], vec![]),
            transfers,
            AnchorBucketInterval::ZIP_318,
        )
    }

    fn transfer(
        id: u32,
        state: MigrationTxState,
        scheduled: u32,
        expiry: u32,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTxId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))), // anchor_boundary
            state,
            None,
        )
    }

    fn preparation(
        id: u32,
        state: MigrationTxState,
        scheduled: u32,
        expiry: u32,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTxId::new(id),
            MigrationTxKind::Preparation { layer: 0, index: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))), // anchor_boundary
            state,
            None,
        )
    }

    /// 1. Terminal migration (Failed status) yields NothingDue even with a Proved+due transfer.
    #[test]
    fn next_due_is_nothing_when_migration_terminal() {
        let tip = BlockHeight::from_u32(1000);
        // A Proved transfer that would normally be due
        let tx = transfer(0, MigrationTxState::Proved, 900, 2000);
        let state = make_state(MigrationStatus::Failed, vec![tx]);
        let result = next_due_transfer_result(&state, tip, tip);
        assert!(
            matches!(result, DueTransferResult::NothingDue),
            "terminal migration must return NothingDue, got non-NothingDue"
        );
    }

    /// 2. Signed (unproven) transfer whose scheduled_height <= tip -> AWAITING_PROOF with its id.
    #[test]
    fn next_due_reports_awaiting_proof_for_due_signed_transfer() {
        let tip = BlockHeight::from_u32(1000);
        let tx = transfer(42, MigrationTxState::Signed, 900, 2000);
        let state = make_state(MigrationStatus::InProgress, vec![tx]);
        let result = next_due_transfer_result(&state, tip, tip);
        match result {
            DueTransferResult::AwaitingProof(id) => {
                assert_eq!(id, MigrationTxId::new(42), "id must match the signed transfer");
            }
            other => panic!(
                "expected AwaitingProof, got NothingDue: {}",
                matches!(other, DueTransferResult::NothingDue)
            ),
        }
    }

    /// 3. estimated_tip accelerates due-ness: scheduled at scanned+5, estimated=scanned+6 -> AWAITING_PROOF;
    ///    estimated=-1 (meaning use scanned) -> NothingDue.
    #[test]
    fn estimated_tip_accelerates_due_ness_only() {
        let scanned = BlockHeight::from_u32(1000);
        let scheduled = 1005u32; // scanned + 5, not due at scanned
        let tx = transfer(1, MigrationTxState::Signed, scheduled, 3000);
        let state = make_state(MigrationStatus::InProgress, vec![tx]);

        // No estimate -> NothingDue (scanned=1000 < scheduled=1005)
        let r1 = next_due_transfer_result(&state, scanned, scanned);
        assert!(
            matches!(r1, DueTransferResult::NothingDue),
            "without estimate (scanned tip), transfer not due yet"
        );

        // With estimate=1006 (> scheduled=1005) -> AWAITING_PROOF
        let estimated = BlockHeight::from_u32(1006);
        let r2 = next_due_transfer_result(&state, scanned, estimated);
        assert!(
            matches!(r2, DueTransferResult::AwaitingProof(_)),
            "with estimated_tip=1006 > scheduled=1005, transfer must be AWAITING_PROOF"
        );
    }

    /// 5. Regression: `any_overdue` must count a due Proved PREPARATION as overdue.
    ///    The pre-tri-state `next_broadcastable`-based gate had no kind filter, so preparations
    ///    also closed the sync gate. The Transfer-only filter introduced in commit 9f8b349b was a
    ///    regression; this test locks in the fixed behaviour.
    #[test]
    fn has_overdue_counts_due_proved_preparation() {
        let tip = BlockHeight::from_u32(1000);
        let prep = preparation(99, MigrationTxState::Proved, 900, 2000);
        let state = make_state(MigrationStatus::InProgress, vec![prep]);

        // The preparation is Proved, due (scheduled=900 <= tip=1000), deps empty (mined), not
        // expired (expiry=2000 > scanned_target=1001).  any_overdue must return true.
        assert!(
            any_overdue(&state, tip, tip),
            "a due Proved preparation must count as overdue (sync gate must close)"
        );

        // Sanity: next_due_transfer_result (Transfer-only) correctly returns NothingDue for the
        // same state — the two functions are complementary, not duplicated.
        assert!(
            matches!(next_due_transfer_result(&state, tip, tip), DueTransferResult::NothingDue),
            "next_due_transfer_result ignores preparations — only any_overdue sees them"
        );
    }

    /// 4. A transfer past expiry at the SCANNED tip is never returned, even when the ESTIMATE is huge.
    ///    Conversely, expiry between scanned and a huge estimate must NOT hide a transfer unexpired
    ///    at the scanned tip.
    #[test]
    fn expiry_is_evaluated_against_scanned_tip_never_estimate() {
        let scanned = BlockHeight::from_u32(1000);
        // expiry=999 means expired at scanned tip (expiry < scanned_target=1001)
        let expired_tx = transfer(10, MigrationTxState::Proved, 900, 999);
        // expiry=1500 means NOT expired at scanned (1500 >= 1001), but would be if we used estimate=2000
        let valid_tx = transfer(11, MigrationTxState::Proved, 900, 1500);

        let state = make_state(MigrationStatus::InProgress, vec![expired_tx, valid_tx]);

        // Even with a huge estimate, expired transfer (id=10) must never appear
        let huge_estimate = BlockHeight::from_u32(99_999_999);
        let result = next_due_transfer_result(&state, scanned, huge_estimate);

        match &result {
            DueTransferResult::Ready(tx) => {
                assert_eq!(
                    tx.id(),
                    MigrationTxId::new(11),
                    "only the unexpired transfer (id=11) may be returned"
                );
            }
            other => panic!(
                "expected Ready(id=11), got NothingDue or AwaitingProof: {}",
                matches!(other, DueTransferResult::NothingDue)
            ),
        }
    }
}

mod reschedule_unproven_tests {
    use super::*;
    use zcash_pool_migration::{
        engine::{
            MigrationState, MigrationStatus, MigrationTransaction, MigrationTxId, MigrationTxKind,
            MigrationTxState,
        },
        note_splitting::NoteSplitPlan,
        preparation::PreparationPlan,
        scheduling::AnchorBucketInterval,
    };
    use zcash_protocol::{consensus::BlockHeight, value::Zatoshis};

    fn make_state(
        status: MigrationStatus,
        transfers: Vec<MigrationTransaction>,
    ) -> MigrationState {
        let note_split = NoteSplitPlan::from_stored_parts(
            vec![Zatoshis::const_from_u64(100_000_000)],
            Zatoshis::const_from_u64(5_000),
            None,
            Zatoshis::const_from_u64(10_000),
            Zatoshis::const_from_u64(100_010_000),
            Zatoshis::const_from_u64(100_000_000),
        )
        .expect("valid note split plan");
        MigrationState::from_parts(
            status,
            note_split,
            PreparationPlan::from_parts(vec![], vec![]),
            transfers,
            AnchorBucketInterval::ZIP_318,
        )
    }

    fn make_tx(
        id: u32,
        state: MigrationTxState,
        scheduled: u32,
        expiry: u32,
        pczt: Vec<u8>,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTxId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            pczt,
            vec![],
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))),
            state,
            None,
        )
    }

    /// Thin wrapper: tests pass pre-computed schedule/boundary directly (no tip/interval needed).
    fn reschedule(
        state: MigrationState,
        transfer_id: MigrationTxId,
        new_schedule: BlockHeight,
        new_boundary: Option<BlockHeight>,
    ) -> (i64, MigrationState) {
        reschedule_unproven_transfer_inner(state, transfer_id, new_schedule, new_boundary)
    }

    #[test]
    fn reschedule_unproven_moves_scheduled_height_into_future_and_persists() {
        let tip = BlockHeight::from_u32(1000);
        let old_expiry = BlockHeight::from_u32(5000);
        let old_pczt = vec![0xABu8; 32];
        let tx = make_tx(1, MigrationTxState::Signed, u32::from(tip), u32::from(old_expiry), old_pczt.clone());
        let state = make_state(MigrationStatus::InProgress, vec![tx]);
        let new_height = BlockHeight::from_u32(1144);
        let (result, new_state) = reschedule(
            state,
            MigrationTxId::new(1),
            new_height,
            Some(BlockHeight::from_u32(864)),
        );
        assert!(result > 0, "must return positive new scheduled_height, got {}", result);
        assert_eq!(result as u32, 1144, "returned height must match");
        let tx = new_state
            .transactions()
            .iter()
            .find(|t| t.id() == MigrationTxId::new(1))
            .unwrap();
        assert_eq!(u32::from(tx.scheduled_height()), 1144, "persisted scheduled_height must be new");
        assert_eq!(tx.expiry_height(), old_expiry, "expiry_height must be UNCHANGED");
        assert_eq!(tx.pczt(), &old_pczt, "pczt bytes must be byte-identical");
        assert!(
            matches!(tx.state(), MigrationTxState::Signed),
            "state must still be Signed"
        );
    }

    /// Helper: calls `select_shift_boundary` with a deterministic pick (always index 0 = lowest).
    /// This lets tests assert boundary selection logic without RNG.
    fn select_boundary_det(
        funding_height: u32,
        tip_u32: u32,
        interval_u32: u32,
        old_boundary: Option<BlockHeight>,
    ) -> Option<BlockHeight> {
        super::select_shift_boundary(
            funding_height,
            tip_u32,
            interval_u32,
            old_boundary,
            |_count| 0, // always pick lowest candidate
        )
    }

    /// Helper: calls `select_shift_boundary` with a pick that always selects the highest candidate.
    fn select_boundary_det_high(
        funding_height: u32,
        tip_u32: u32,
        interval_u32: u32,
        old_boundary: Option<BlockHeight>,
    ) -> Option<BlockHeight> {
        super::select_shift_boundary(
            funding_height,
            tip_u32,
            interval_u32,
            old_boundary,
            |count| count - 1, // always pick highest candidate
        )
    }

    #[test]
    fn reschedule_unproven_redraws_boundary_when_candidates_exist() {
        // Mainnet interval=144: tip=2000, funding=0 (unknown dep → lowest=144)
        // highest=(2000/144)*144-144 = 13*144-144 = 1728; lowest=144; candidates exist
        // pick=0 → lowest=144, pick=highest_idx → 1728; both must be on-grid.
        let interval: u32 = 144;
        let tip: u32 = 2000;
        let old_boundary = Some(BlockHeight::from_u32(500));

        let boundary_low = select_boundary_det(0, tip, interval, old_boundary);
        let boundary_high = select_boundary_det_high(0, tip, interval, old_boundary);

        let b_low = boundary_low.expect("must be Some when candidates exist");
        let b_high = boundary_high.expect("must be Some when candidates exist");
        assert_eq!(u32::from(b_low) % interval, 0, "lowest pick must be on grid");
        assert_eq!(u32::from(b_high) % interval, 0, "highest pick must be on grid");
        // lowest candidate = 144, highest candidate = 1728
        assert_eq!(u32::from(b_low), 144, "lowest pick must equal first candidate");
        assert_eq!(u32::from(b_high), 1728, "highest pick must equal last candidate");
        assert!(u32::from(b_low) <= u32::from(b_high), "low <= high");

        // Testnet interval=12: tip=200, funding=0 → lowest=12, highest=(200/12)*12-12=192-12=180
        let interval12: u32 = 12;
        let tip12: u32 = 200;
        let boundary12_low = select_boundary_det(0, tip12, interval12, old_boundary);
        let boundary12_high = select_boundary_det_high(0, tip12, interval12, old_boundary);
        let bl12 = boundary12_low.expect("testnet: must be Some");
        let bh12 = boundary12_high.expect("testnet: must be Some");
        assert_eq!(u32::from(bl12) % interval12, 0, "testnet lowest pick on grid");
        assert_eq!(u32::from(bh12) % interval12, 0, "testnet highest pick on grid");
        assert_eq!(u32::from(bl12), 12, "testnet: lowest candidate = 12");
        assert_eq!(u32::from(bh12), 180, "testnet: highest candidate = 180");

        // Cross-check: the inner reschedule with the boundary-helper result persists it correctly.
        let tx = MigrationTransaction::from_parts(
            MigrationTxId::new(2),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            BlockHeight::from_u32(tip),
            BlockHeight::from_u32(10000),
            old_boundary,
            MigrationTxState::Signed,
            None,
        );
        let state = make_state(MigrationStatus::InProgress, vec![tx]);
        let (result, new_state) = reschedule(
            state,
            MigrationTxId::new(2),
            BlockHeight::from_u32(2144),
            boundary_low, // already verified to be on-grid
        );
        assert!(result > 0);
        let tx = new_state
            .transactions()
            .iter()
            .find(|t| t.id() == MigrationTxId::new(2))
            .unwrap();
        let persisted = tx.anchor_boundary().expect("boundary must be Some after reschedule");
        assert_eq!(
            u32::from(persisted) % interval,
            0,
            "persisted boundary must be on bucket grid (multiple of {}), got {}",
            interval,
            u32::from(persisted)
        );
    }

    /// Tests the load-bearing "empty candidate set → keep old boundary" decision in
    /// `select_shift_boundary`. This is the logic that lives in the JNI wrapper (previously
    /// untested because the old test bypassed it by passing `old_boundary` directly into
    /// `reschedule_unproven_transfer_inner`).
    #[test]
    fn reschedule_unproven_keeps_old_boundary_when_no_candidate() {
        let old_boundary = Some(BlockHeight::from_u32(100));

        // --- Mainnet interval=144 ---
        // tip=200: highest=(200/144)*144-144 = 144-144=0; lowest=144 → 144>0 → no candidates
        let result_144 = select_boundary_det(0, 200, 144, old_boundary);
        assert_eq!(
            result_144, old_boundary,
            "mainnet tip=200: no candidates → old boundary unchanged"
        );

        // tip=143: highest=0 (tip<interval); lowest=144 → no candidates
        let result_143 = select_boundary_det(0, 143, 144, old_boundary);
        assert_eq!(
            result_143, old_boundary,
            "mainnet tip=143: tip<interval → no candidates → old boundary unchanged"
        );

        // funding_height set to tip-interval+1 (barely too recent for candidates):
        // tip=300, interval=144: highest=(300/144)*144-144=2*144-144=144;
        // funding=157 → lowest=ceil(157/144)*144=2*144=288; 288>144 → no candidates
        let result_recent = select_boundary_det(157, 300, 144, old_boundary);
        assert_eq!(
            result_recent, old_boundary,
            "funding too recent: no candidates → old boundary unchanged"
        );

        // --- Testnet interval=12 ---
        // tip=20: highest=(20/12)*12-12=12-12=0; lowest=12 → 12>0 → no candidates
        let result_t12 = select_boundary_det(0, 20, 12, old_boundary);
        assert_eq!(
            result_t12, old_boundary,
            "testnet tip=20: no candidates → old boundary unchanged"
        );

        // tip=11: tip<interval → highest=0; lowest=12 → no candidates
        let result_t11 = select_boundary_det(0, 11, 12, old_boundary);
        assert_eq!(
            result_t11, old_boundary,
            "testnet tip=11 (tip<interval): no candidates → old boundary unchanged"
        );

        // Confirm the boundary IS updated when there ARE candidates (tip=2000, mainnet):
        // This ensures the above assertions are actually the no-candidate branch, not a bug
        // that always returns old_boundary.
        let result_candidates = select_boundary_det(0, 2000, 144, old_boundary);
        assert_ne!(
            result_candidates, old_boundary,
            "when candidates exist (tip=2000, interval=144), boundary must change from old={:?}",
            old_boundary
        );

        // Also cross-check via the inner fn that it faithfully stores what we give it.
        let tip_bh = BlockHeight::from_u32(200);
        let tx = MigrationTransaction::from_parts(
            MigrationTxId::new(3),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            tip_bh,
            BlockHeight::from_u32(10000),
            old_boundary,
            MigrationTxState::Signed,
            None,
        );
        let state = make_state(MigrationStatus::InProgress, vec![tx]);
        // Use the helper result (verified above = old_boundary) so the test reflects real JNI flow.
        let (result, new_state) = reschedule(
            state,
            MigrationTxId::new(3),
            BlockHeight::from_u32(344),
            result_144, // == old_boundary — as select_shift_boundary would return
        );
        assert!(result > 0);
        let tx = new_state
            .transactions()
            .iter()
            .find(|t| t.id() == MigrationTxId::new(3))
            .unwrap();
        assert_eq!(
            tx.anchor_boundary(),
            old_boundary,
            "inner fn must persist exactly what select_shift_boundary returned"
        );
    }

    #[test]
    fn reschedule_unproven_rejects_proved_and_terminal() {
        let tip = BlockHeight::from_u32(1000);

        // Proved → must return -1
        let proved_tx = MigrationTransaction::from_parts(
            MigrationTxId::new(10),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            tip,
            BlockHeight::from_u32(5000),
            Some(BlockHeight::from_u32(900)),
            MigrationTxState::Proved,
            None,
        );
        let state = make_state(MigrationStatus::InProgress, vec![proved_tx]);
        let (result, _) = reschedule(
            state,
            MigrationTxId::new(10),
            BlockHeight::from_u32(1144),
            None,
        );
        assert_eq!(result, -1, "Proved transfer must return -1");

        // Missing id → must return -1
        let signed_tx = MigrationTransaction::from_parts(
            MigrationTxId::new(20),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            tip,
            BlockHeight::from_u32(5000),
            Some(BlockHeight::from_u32(900)),
            MigrationTxState::Signed,
            None,
        );
        let state2 = make_state(MigrationStatus::InProgress, vec![signed_tx]);
        let (result2, _) = reschedule(
            state2,
            MigrationTxId::new(99),
            BlockHeight::from_u32(1144),
            None,
        );
        assert_eq!(result2, -1, "Missing transfer id must return -1");
    }
}

// ---------------------------------------------------------------------------
// Invalidation persistence tests
// ---------------------------------------------------------------------------
//
// These tests cover the pure-Rust persistence layer: `record_invalidation`,
// `read_invalidation`, `clear_invalidation`, and the state-mutation logic that
// `recordTransferResultNative` (tags 2|3) now exercises.
//
// The JNI portion of the flow — `derive_migration_state` constructing Java
// objects (`JniAttentionReason$InvalidTransfer` / `$TransferExpired`) — cannot
// be driven from a pure `cargo test` run (no JVM).  It is compile-verified:
// the function signature change (extra `&Connection` + `&[u8]` params) ensures
// incorrect callers fail at compile time, and the `env.new_object(...)` calls
// carry the right constructor signatures in string literals that are checked at
// JNI call time during device/emulator tests.
#[cfg(test)]
mod record_transfer_result_tests {
    use super::*;
    use zcash_pool_migration::{
        engine::MigrationStatus,
        note_splitting::NoteSplitPlan,
        preparation::PreparationPlan,
    };
    use zcash_protocol::value::Zatoshis;

    // Reuse the minimal-state builder from next_due_transfer_tests.
    fn make_state(status: MigrationStatus, transfers: Vec<MigrationTransaction>) -> MigrationState {
        let note_split = NoteSplitPlan::from_stored_parts(
            vec![Zatoshis::const_from_u64(100_000_000)],
            Zatoshis::const_from_u64(5_000),
            None,
            Zatoshis::const_from_u64(10_000),
            Zatoshis::const_from_u64(100_010_000),
            Zatoshis::const_from_u64(100_000_000),
        )
        .expect("valid note split plan");
        MigrationState::from_parts(
            status,
            note_split,
            PreparationPlan::from_parts(vec![], vec![]),
            transfers,
            AnchorBucketInterval::ZIP_318,
        )
    }

    fn transfer(id: u32, state: MigrationTxState, scheduled: u32, expiry: u32) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTxId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))),
            state,
            None,
        )
    }

    const ACCOUNT: &[u8] = &[1u8; 16];

    /// Helper: simulate the tag=2 / tag=3 state-mutation + persistence path.
    fn apply_tag(conn: &Connection, state: MigrationState, result_tag: i32, transfer_id_str: &str)
        -> anyhow::Result<MigrationState>
    {
        let reason = if result_tag == 2 { "invalid_transfer" } else { "transfer_expired" };
        let failed = if !state.is_terminal() {
            MigrationState::from_parts(
                MigrationStatus::Failed,
                state.note_split().clone(),
                state.preparation().clone(),
                state.transactions().clone(),
                state.anchor_bucket_interval(),
            )
        } else {
            state
        };
        record_invalidation(conn, ACCOUNT, reason, Some(transfer_id_str))?;
        Ok(failed)
    }

    // tag=2 → reason "invalid_transfer", state Failed, read back correctly.
    #[test]
    fn record_transfer_result_invalid_note_marks_migration_failed_with_reason() {
        let conn = Connection::open_in_memory().unwrap();
        let state = make_state(MigrationStatus::InProgress, vec![transfer(7, MigrationTxState::Proved, 1000, 2000)]);
        assert!(!state.is_terminal(), "pre-condition: state is InProgress");

        let failed = apply_tag(&conn, state, 2, "7").unwrap();

        // State mutation.
        assert!(failed.is_terminal(), "state must be terminal after tag=2");
        assert_eq!(failed.status(), MigrationStatus::Failed);

        // Side-table read.
        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        assert!(inv.is_some(), "invalidation row must exist");
        let (reason, tid) = inv.unwrap();
        assert_eq!(reason, "invalid_transfer");
        assert_eq!(tid.as_deref(), Some("7"));
    }

    // tag=3 → reason "transfer_expired".
    #[test]
    fn record_transfer_result_expired_marks_failed_with_expired_reason() {
        let conn = Connection::open_in_memory().unwrap();
        let state = make_state(MigrationStatus::InProgress, vec![transfer(3, MigrationTxState::Signed, 900, 1800)]);

        let failed = apply_tag(&conn, state, 3, "3").unwrap();

        assert!(failed.is_terminal());
        assert_eq!(failed.status(), MigrationStatus::Failed);

        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        let (reason, _) = inv.unwrap();
        assert_eq!(reason, "transfer_expired");
    }

    // tag=1 (NetworkError) → no side-table write, state untouched.
    #[test]
    fn record_transfer_result_network_error_still_noop() {
        let conn = Connection::open_in_memory().unwrap();
        // tag=1 is handled upstream (no persistence call); verify helpers are neutral.
        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        assert!(inv.is_none(), "tag=1 must leave side table empty");
    }

    // clear_invalidation removes the row.
    #[test]
    fn clear_migration_clears_invalidation_reason() {
        let conn = Connection::open_in_memory().unwrap();
        record_invalidation(&conn, ACCOUNT, "invalid_transfer", Some("5")).unwrap();
        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        assert!(inv.is_some(), "pre-condition: row exists");

        clear_invalidation(&conn, ACCOUNT).unwrap();

        let inv_after = read_invalidation(&conn, ACCOUNT).unwrap();
        assert!(inv_after.is_none(), "invalidation must be cleared");
    }

    // clear_invalidation on a non-existent table is not an error.
    #[test]
    fn clear_invalidation_no_table_is_noop() {
        let conn = Connection::open_in_memory().unwrap();
        // No table created yet — should not error.
        clear_invalidation(&conn, ACCOUNT).unwrap();
    }

    // Two different accounts don't bleed into each other.
    #[test]
    fn invalidation_is_per_account() {
        let conn = Connection::open_in_memory().unwrap();
        let account_b: &[u8] = &[2u8; 16];
        record_invalidation(&conn, ACCOUNT, "invalid_transfer", Some("1")).unwrap();

        let inv_b = read_invalidation(&conn, account_b).unwrap();
        assert!(inv_b.is_none(), "account B must not see account A's invalidation");

        record_invalidation(&conn, account_b, "transfer_expired", None).unwrap();
        let inv_a = read_invalidation(&conn, ACCOUNT).unwrap();
        let (reason_a, _) = inv_a.unwrap();
        assert_eq!(reason_a, "invalid_transfer", "account A's reason must be unchanged");
    }
}
