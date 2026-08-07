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
    objects::{JByteArray, JClass, JLongArray, JObject, JObjectArray, JString, JValue},
    sys::{
        JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jintArray, jlong, jlongArray, jobject,
        jobjectArray,
    },
};
use prost::Message;
use rand::rngs::OsRng;
use rusqlite::Connection;
use std::ptr;

use zcash_client_backend::data_api::wallet::input_selection::LockFilter;
use zcash_client_backend::data_api::{InputSource, OutputLockStore, WalletRead};
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
        self, MigrationCrypto, MigrationPlan, MigrationState, MigrationTransaction,
        MigrationTransferId, MigrationTxKind, MigrationTxState, PoolMigrationRead,
        PoolMigrationWrite, ProveError,
    },
    preparation::{PrepInput, PreparationPlan},
    satisfiability::{
        AdvanceConfig, DuenessTargets, ReorgSettleDepth, ReplanThreshold, advance_migration,
    },
    state::{AdvanceStep, NextAction, StepKind},
};

use crate::migration_engine::{Backend, EngineError};
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
const JNI_PREPARATION_STEP: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniPreparationStep";
const JNI_MIGRATION_SCHEDULE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationSchedule";
const JNI_MIGRATION_TRANSFER_STATE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferState";
const JNI_MIGRATION_TRANSFER_STATES: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferStates";
const JNI_UNSIGNED_TRANSFER_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniUnsignedTransferPczt";
const JNI_UNSIGNED_PREPARATION_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniUnsignedPreparationPczt";
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
    // Both connections get a busy_timeout: these JNI entry points race the synchronizer engine's
    // block-write bursts on the same SQLite file, and rusqlite's default (0) turns a transient
    // write lock into an instant "database is locked" error — observed live 2026-07-28 as an
    // app crash from the 15s isSyncBlocked gate tick during a testnet min-difficulty burst.
    let wallet_conn = Connection::open(db_path)
        .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))?;
    rusqlite::vtab::array::load_module(&wallet_conn)
        .map_err(|e| anyhow!("Error loading SQLite array module: {}", e))?;
    wallet_conn
        .busy_timeout(std::time::Duration::from_secs(15))
        .map_err(|e| anyhow!("Error setting wallet busy_timeout: {}", e))?;
    // mmap disabled on BOTH connections: shrinking a file under a live mmap reader is reported
    // by the kernel as SIGBUS (observed live 2026-07-28: BUS_ADRERR read fault on the zc-io
    // thread during a signAndStore retry racing the synchronizer). The canonical producer of
    // that state is a SECOND SQLite library instance on the same file (framework SQLiteDatabase
    // next to the bundled one — POSIX close() drops the whole process's fcntl locks, letting the
    // WAL/-shm index be truncated under the engine's mapping; see Milan's slipstream host-read
    // incident, FFI_JNI_CONTRACT.md). This build graph has exactly one libsqlite3-sys node and
    // no framework access to this file (verify: `cargo tree -i libsqlite3-sys` = one node), so
    // this pragma is defense-in-depth for these short-lived per-call connections, not the
    // primary guarantee. Plain read()/write() I/O is immune,
    // and these short-lived per-call connections gain nothing measurable from mmap.
    wallet_conn
        .pragma_update(None, "mmap_size", 0)
        .map_err(|e| anyhow!("Error disabling wallet mmap: {}", e))?;
    let wallet = Wallet::from_connection(wallet_conn, network, SystemClock, OsRng)
        .with_anchor_retention_interval(retention_interval);
    let store_conn = Connection::open(db_path)
        .map_err(|e| anyhow!("Error opening migration store connection: {}", e))?;
    store_conn
        .busy_timeout(std::time::Duration::from_secs(15))
        .map_err(|e| anyhow!("Error setting store busy_timeout: {}", e))?;
    store_conn
        .pragma_update(None, "mmap_size", 0)
        .map_err(|e| anyhow!("Error disabling store mmap: {}", e))?;
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
// `transfer_id`  — the string representation of the `MigrationTransferId` index (may be NULL when the
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
/// This function iterates every account via `open_at`/`Backend`/typed `MigrationState` to compute
/// the minimum `anchor_boundary` across all non-terminal, non-broadcast/mined transactions. It is
/// called directly by `slipstream/mod.rs`'s `start_session` (via `min_pending_migration_anchor_boundary`,
/// which now delegates here instead of using raw SQL) to determine the anchor-retention floor for
/// checkpoint pruning — see `slipstream/mod.rs`'s doc comment for the full anchor-protection rationale
/// and ZIP 374 reference.
///
/// Preparation transactions are excluded (`anchor_boundary() == None`): they anchor to a freshly
/// current tip at prove time (see `natural_anchor_height`'s doc comment), not a boundary drawn in
/// advance, so they never need retroactive protection.
///
/// Returns `Ok(None)` if there's no in-progress migration in any account, or every transaction
/// needing a boundary has already broadcast/mined.
///
/// Deliberately kept as a plain `anyhow::Result` (matching every other function in this file, e.g.
/// `plan_for`), rather than swallowing errors internally: the caller (`slipstream/mod.rs`) is
/// responsible for treating an `Err` as "no retention floor" — logging and falling back to `None` —
/// since a wallet DB read glitch here must never block sync from starting.
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
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
    let backend = Backend::new(wallet, account, None, store_conn, *wallet.params())?;
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
/// A migration state paired with the transactions it left awaiting signature, each as its
/// id and PCZT bytes. Named because both the commit entry point and every `sign` closure it
/// dispatches to return this same shape.
type MigrationCommitOutcome = (MigrationState, Vec<(MigrationTransferId, Vec<u8>)>);

/// The wallet-side context a commit runs against: which network and account, the store
/// connection it writes through, and the target height the cached plan was built for. Grouped
/// so `commit_or_reuse` takes the plan handle and signing strategy as its own arguments rather
/// than trailing five pieces of ambient context.
struct CommitContext<'a> {
    network: &'a Network,
    wallet: &'a Wallet,
    account: AccountUuid,
    store_conn: &'a mut Connection,
    target: BlockHeight,
}

/// the cached plan that `plan_handle` identifies — erroring if no plan is cached or if a later
/// `propose*`/`prepare*` call replaced the plan the caller was shown (see `migration_plan_cache`'s
/// module doc: the handle gate is what guarantees a commit can only sign the exact plan the user
/// reviewed). On the reuse path the handle is not consulted: the commitment already happened —
/// with a handle-verified plan — and is durable, so there is nothing left the handle could
/// protect. Shared by both the in-process-signing and external-signer commit paths below; `sign`
/// picks which `commit_preparation`/`build_preparation_unsigned` variant to run, and whether a
/// spending key is available to the `Backend` while doing so.
fn commit_or_reuse(
    ctx: CommitContext<'_>,
    plan_handle: crate::migration_plan_cache::PlanHandle,
    usk: Option<UnifiedSpendingKey>,
    sign: impl FnOnce(
        &Network,
        BlockHeight,
        &mut Backend<Wallet>,
        &MigrationPlan,
        &mut OsRng,
    ) -> anyhow::Result<MigrationCommitOutcome>,
) -> anyhow::Result<MigrationCommitOutcome> {
    let CommitContext {
        network,
        wallet,
        account,
        store_conn,
        target,
    } = ctx;
    {
        let backend = Backend::new(wallet, account, None, &mut *store_conn, *wallet.params())?;
        if let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            && !state.is_terminal()
        {
            let unsigned = state
                .transactions()
                .iter()
                .filter(|t| matches!(t.state(), MigrationTxState::AwaitingSignature))
                .map(|t| (t.id(), t.pczt().clone()))
                .collect();
            return Ok((state, unsigned));
        }
    }
    let migration_plan = crate::migration_plan_cache::get(account, plan_handle)?;
    let mut backend = Backend::new(wallet, account, usk, store_conn, *wallet.params())?;
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
            // A migration `mark_superseded()` reaches (Task 7: markMigrationSupersededNative, the
            // consumer's response to `AdvanceStep::Replan`) accepts a replacement commit
            // immediately — `Committer::start`'s guard only checks `is_terminal()`, which
            // `Superseded` satisfies exactly like `Complete`/`Failed`. That is precisely
            // `ReadyToPropose`'s contract ("ready to call proposeMigrationTransfers()"), even
            // though a prior (now-superseded) migration record still exists in the store — so map
            // it there rather than treating a superseded plan as a NEW, uninitiated migration
            // (`NotStarted`) or as a `Complete` one. Without this arm, any `getMigrationState()`
            // call after a Replan (e.g. the home banner) would hit the `unreachable!` below.
            engine::MigrationStatus::Superseded => {
                Ok(env.new_object(format!("{JNI_MIGRATION_STATE}$ReadyToPropose"), "()V", &[])?)
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
            _ => unreachable!("is_terminal() only returns true for Complete/Failed/Superseded"),
        };
    }

    let transactions = state.transactions();
    let total = transactions.len();
    let completed = transactions
        .iter()
        .filter(|t| matches!(t.state(), MigrationTxState::Mined { .. }))
        .count();
    let next_ready = next_broadcastable(&state, DuenessTargets::at(tip));
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
    let split = plan.denominations();
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

/// A transaction id as Kotlin receives it: the engine's `u32` widened to the `Long` this JNI
/// boundary uses for every unsigned 32-bit value (heights included), so the value survives without
/// a sign flip and Kotlin can range-check it.
fn encode_transfer_id(id: MigrationTransferId) -> jlong {
    jlong::from(u32::from(id))
}

/// The inverse of [`encode_transfer_id`]: a `Long` from Kotlin back to the engine's id. Rejects
/// values outside the `u32` range rather than truncating them into a different transaction.
fn decode_transfer_id(id: jlong) -> anyhow::Result<MigrationTransferId> {
    let idx =
        u32::try_from(id).map_err(|_| anyhow!("Transfer id {} is outside the u32 range", id))?;
    Ok(MigrationTransferId::new(idx))
}

/// One preparation (note-split) transaction entry, ready to encode into [`JniPreparationStep`].
///
/// - `id` is the stable [`MigrationTransferId`] the engine assigns at commit time (same ordinal as
///   in [`MigrationPlan::planned_transactions`]).
/// - `layer` / `index` address the transaction within [`PreparationPlan::layers`].
/// - `broadcast_height` is drawn from [`MigrationPlan::prep_schedule`]`[layer][index]`.
/// - `depends_on` lists the ids of every preparation transaction whose output this one spends; a
///   layer-0 transaction's list is always empty.
struct PrepEntry {
    id: u32,
    layer: usize,
    index: usize,
    broadcast_height: BlockHeight,
    depends_on: Vec<u32>,
}

/// Enumerate all note-split (preparation) transactions in a [`MigrationPlan`], in
/// [`MigrationPlan::planned_transactions`] id order (which is also the commit-time assignment
/// order: all preparation transactions before all transfers, layer by layer).
///
/// Dependencies are derived directly from the [`PrepInput::Prior`] inputs of each
/// [`PrepTransaction`]: if a transaction at `(layer, index)` has a `Prior { layer: pl,
/// transaction: pt, .. }` input, then the preparation transaction at `(pl, pt)` — whose id the
/// commit assigns at offset `sum(layer_lengths[0..pl]) + pt` — is a dependency. This mirrors
/// exactly the dependency ids the engine persists to `orchard_ironwood_migration_transaction_deps`
/// at commit time (derived there from the same `PrepInput::Prior` references), so the schedule
/// exposed to Kotlin is always consistent with what the engine later stores.
fn preparation_schedule_entries(
    preparation: &PreparationPlan,
    prep_schedule: &[Vec<BlockHeight>],
) -> Vec<PrepEntry> {
    let layers = preparation.layers();

    // Build the cumulative id offset for each layer: the id of tx (l, t) is
    // `layer_start[l] + t as u32`, matching the commit order in `planned_transactions`.
    let mut layer_start: Vec<u32> = Vec::with_capacity(layers.len());
    let mut cumulative: u32 = 0;
    for layer in layers {
        layer_start.push(cumulative);
        cumulative = cumulative
            .checked_add(layer.len() as u32)
            .expect("preparation tx count fits u32");
    }

    let mut entries = Vec::with_capacity(cumulative as usize);
    for (li, layer_txs) in layers.iter().enumerate() {
        for (ti, prep_tx) in layer_txs.iter().enumerate() {
            let id = layer_start[li] + ti as u32;
            let broadcast_height = prep_schedule[li][ti];

            // Collect unique dependency ids from every Prior input, preserving order then
            // deduplicating (a transaction could theoretically reference the same predecessor's
            // output twice, though valid plans do not).
            let mut depends_on: Vec<u32> = prep_tx
                .inputs()
                .iter()
                .filter_map(|input| {
                    if let PrepInput::Prior {
                        layer: prior_layer,
                        transaction: prior_tx,
                        ..
                    } = input
                    {
                        Some(layer_start[*prior_layer] + *prior_tx as u32)
                    } else {
                        None
                    }
                })
                .collect();
            depends_on.sort_unstable();
            depends_on.dedup();

            entries.push(PrepEntry {
                id,
                layer: li,
                index: ti,
                broadcast_height,
                depends_on,
            });
        }
    }
    entries
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
    id: MigrationTransferId,
    amount: Zatoshis,
    anchor_height: BlockHeight,
    schedule_broadcast_height: BlockHeight,
    schedule_expiry_height: BlockHeight,
) -> jni::errors::Result<JObject<'a>> {
    env.new_object(
        JNI_TRANSFER_PROPOSAL,
        "(JJJJJ)V",
        &[
            JValue::Long(encode_transfer_id(id)),
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
    let note_fee_buffer = plan.denominations().note_fee_buffer();
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

    // The real `MigrationTransferId` the engine will assign at commit time numbers every preparation
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
            MigrationTransferId::new(prep_tx_count + i as u32),
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

    // Build the JniPreparationStep[] from the plan's preparation schedule. Each entry gets a
    // LongArray for its `dependsOn` field (mirrors the pattern used above for JniNoteSplitProposal's
    // `outputValuesZatoshi: LongArray`). The JniPreparationStep constructor signature is:
    //   (long id, int layer, int index, long broadcastHeight, long[] dependsOn)
    //   JNI signature "(JIIJ[J)V" = (id: J, layer: I, index: I, broadcastHeight: J, dependsOn: [J).
    // The exact field order must match the Kotlin constructor declaration:
    //   JniPreparationStep(id: Long, layer: Int, index: Int, broadcastHeight: Long, dependsOn: LongArray)
    let prep_entries = preparation_schedule_entries(plan.preparation(), plan.prep_schedule());
    let preparations = crate::utils::rust_vec_to_java(
        env,
        prep_entries,
        JNI_PREPARATION_STEP,
        |env, entry| -> jni::errors::Result<JObject<'_>> {
            let depends_on_array = env.new_long_array(entry.depends_on.len() as i32)?;
            if !entry.depends_on.is_empty() {
                let longs: Vec<jlong> =
                    entry.depends_on.iter().map(|&id| jlong::from(id)).collect();
                env.set_long_array_region(&depends_on_array, 0, &longs)?;
            }
            env.new_object(
                JNI_PREPARATION_STEP,
                "(JIIJ[J)V",
                &[
                    JValue::Long(jlong::from(entry.id)),
                    JValue::Int(entry.layer as jint),
                    JValue::Int(entry.index as jint),
                    JValue::Long(i64::from(u32::from(entry.broadcast_height))),
                    JValue::Object(&depends_on_array),
                ],
            )
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

    // Constructor parameter order matches the Kotlin field declaration order in JniMigrationSchedule:
    //   (transfers: Array<JniTransferProposal>, preparations: Array<JniPreparationStep>,
    //    estimatedDurationHours: Int, proposalHandle: Long)
    Ok(env.new_object(
        JNI_MIGRATION_SCHEDULE,
        format!("([L{JNI_TRANSFER_PROPOSAL};[L{JNI_PREPARATION_STEP};IJ)V"),
        &[
            JValue::Object(&transfers),
            JValue::Object(&preparations),
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
/// `plan_migration()` call (the split's realized output values ARE `plan.denominations()
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
/// The id of the next transaction ready to BROADCAST.
///
/// `MigrationState::next_broadcastable` became private in `zcash_pool_migration 0.1.0-rc.6`;
/// `transaction_statuses` is the supported read-only surface and answers the same question from
/// the same kernel. It is strictly more conservative than the old free method: it additionally
/// withholds a row carrying a broadcast-failure report, one marked unsatisfiable or stranded
/// behind a dead dependency, and one whose expiry the caller's estimate has probably passed —
/// none of which the wallet could see before, and each of which was a submission known to fail.
fn next_broadcastable(
    state: &MigrationState,
    targets: DuenessTargets,
) -> Option<MigrationTransferId> {
    state
        .transaction_statuses(targets)
        .into_iter()
        .find(|s| s.ready() && s.action() == Some(NextAction::Broadcast))
        .map(|s| s.id())
}

/// Whether transaction `tx` is ready to PROVE at `target_height` (`chain_tip + 1`). A thin wrapper
/// around `state.transaction_statuses`'s prove-readiness classification, kept as its own function
/// rather than looping `MigrationState::next_provable` because that returns only the SINGLE
/// next-ready transaction — looping it would re-return the same id forever on a transient
/// witness/anchor failure (see `try_prove`'s doc comment), whereas our JNI contract proves every
/// ready transaction in one call. `target_height` is the SCANNED target (tip + 1) — proving is
/// always judged on real chain data, never an estimate (see this crate's two-tip doc), so
/// `DuenessTargets::at(target_height)` (scanned == effective) is correct here.
///
/// # No late-dependency guard (resolved upstream in rc.6)
///
/// This function once carried a hand-rolled "LATE-DEPENDENCY GUARD": a transfer whose funding
/// dependency mined PAST its drawn `anchor_boundary` was withheld here, because the note funding it
/// is not in the commitment tree at that anchor and proving would miss with `Query(NotContained)`.
/// That guard was always a documented stopgap (see
/// `spec/2026-07-30-engine-change-request-unprovable-boundary.md`), requested to be upstreamed.
/// `zcash_pool_migration` 0.1.0-rc.6 shipped the fix in `engine::prove_transfer`: at PROVE time it
/// re-validates the persisted boundary against the funding preparations' REAL mined heights and,
/// when a note postdates the drawn boundary, RE-DRAWS the boundary to a fresh grid bucket
/// at-or-past the note's creation (persisting it via `set_transfer_anchor_boundary`) and proves
/// against that — no new signing ceremony, since ZIP 374 defers the anchor/witnesses to proving.
/// When no valid bucket has settled yet it answers `ProveOutcome::NotYetProvable` (retry after
/// further sync), never a wedge.
///
/// So a late-dependency transfer must still be OFFERED as prove-ready: only by entering the prove
/// batch does it reach `prove_transfer`, which heals it. `state.transaction_statuses`'s own
/// `prove_ready` (upstream, `state.rs`) carries the identical gate: deps mined, and a transfer's
/// boundary settled (or a preparation's schedule due) — no late-dependency exclusion — so
/// delegating here preserves the exact behavior this function used to re-derive by hand.
fn is_prove_ready(
    state: &MigrationState,
    tx: &engine::MigrationTransaction,
    target_height: BlockHeight,
) -> bool {
    state
        .transaction_statuses(DuenessTargets::at(target_height))
        .into_iter()
        .find(|s| s.id() == tx.id())
        .is_some_and(|s| s.ready() && matches!(s.action(), Some(NextAction::Prove)))
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
    id: MigrationTransferId,
    kind: MigrationTxKind,
    store_conn: &mut Connection,
) -> anyhow::Result<bool> {
    let anchor = match kind {
        MigrationTxKind::Transfer { .. } => None,
        MigrationTxKind::Preparation { .. } => Some(natural_anchor_height(wallet)?),
    };
    let params = *wallet.params();
    // The tip the wallet has actually observed and can witness at, which is what bounds the
    // engine's anchor re-draw and its dependency-coverage reading of an absent input.
    let scanned_tip = target_height(wallet)? - 1;
    let mut rng = OsRng;
    // Scoped so the prover's mutable borrow of the wallet ends before the store below takes a
    // shared one.
    let result = {
        let mut prover = WalletMigrationProver::new(wallet, account, fvk);
        match anchor {
            None => engine::prove_transfer(&params, &mut prover, state, id, scanned_tip, &mut rng),
            Some(anchor) => engine::prove_preparation(&mut prover, state, id, anchor),
        }
    };
    match result {
        // The proof is not applied to `state` — and so not persisted — by proving alone; handing
        // it to the store is the only way to discharge it. For this wallet-backed store that also
        // finalizes the transaction into the wallet's own tables, in the same database
        // transaction, so its inputs read as spent from here until it is mined.
        Ok(engine::ProveOutcome::Proved(proven)) => {
            let mut backend = Backend::new(&*wallet, account, None, store_conn, params)?;
            backend
                .store_proved_transaction(state, proven)
                .map_err(|e| anyhow!("Error storing proved migration transaction: {:?}", e))?;
            Ok(true)
        }
        Ok(engine::ProveOutcome::NotYetProvable) => Ok(false),
        // The engine wrote an unsatisfiability mark (and its dependency closure) into `state`.
        // That is a determination, not a transient miss, so it is persisted here rather than left
        // to a caller that may return early on the `false`.
        Ok(engine::ProveOutcome::MarkedUnsatisfiable { replan_required }) => {
            tracing::debug!(
                "MIGRATION_DIAG try_prove: {:?} marked unsatisfiable (replan_required={})",
                id,
                replan_required
            );
            let mut backend = Backend::new(&*wallet, account, None, store_conn, params)?;
            backend
                .replace_migration(state)
                .map_err(|e| anyhow!("Error persisting unsatisfiability mark: {:?}", e))?;
            Ok(false)
        }
        Err(ProveError::Prover(reason)) if is_transient_prove_error(&reason) => {
            tracing::debug!(
                "MIGRATION_DIAG try_prove: {:?} not yet provable (transient): {}",
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

/// Whether a `WalletProveError` from the prover is a TRANSIENT "not ready yet" condition — the
/// migration transaction cannot be proved right now, but may become provable once more chain state
/// is observed — as opposed to a genuine, surfacing failure. A transient error makes `try_prove`
/// defer (return `Ok(false)`) instead of propagating an `Err` that would crash and roll back the
/// whole `finalizeReadyTransfers` batch, re-proving forever.
///
/// Transient variants:
/// - `UnknownSpentNote` — the funding note has not been observed as spendable yet.
/// - `AnchorNotFound` / `WitnessNotFound` — the anchor's checkpoint or a spend's witness is not yet
///   resolvable in the wallet's commitment tree at this height.
/// - `Tree(..)` — a commitment-tree query miss, i.e. `Query(NotContained(..))`. This is the exact
///   error the live late-dependency crash carried: transfer tx8 anchored at 4220724 while its
///   funding preparation id1 mined LATE at 4220802, so id1's output note was NOT in the tree at
///   tx8's anchor and the tree query for its position missed. Left un-classified it PROPAGATED,
///   crashed `finalizeReadyTransfers`, rolled back the whole prove batch, and looped forever. It is
///   transient in the same sense as the others: it must defer rather than take down every other
///   ready transfer with it. As of rc.6 the primary cure for a late dependency is upstream —
///   `engine::prove_transfer` re-draws the boundary at prove time — so this classification is now a
///   defence-in-depth backstop for any residual tree-query miss, not the sole recovery path.
///
/// A non-transient prover error (e.g. `IronwoodTreeUnavailable`) is NOT swallowed here — it still
/// surfaces so a real failure is not silently dropped.
fn is_transient_prove_error<TE, NE, RE, LE>(err: &WalletProveError<TE, NE, RE, LE>) -> bool {
    matches!(
        err,
        WalletProveError::UnknownSpentNote(_)
            | WalletProveError::AnchorNotFound(_)
            | WalletProveError::WitnessNotFound(_)
            | WalletProveError::Tree(_)
    )
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
    id: MigrationTransferId,
) -> anyhow::Result<(Vec<u8>, [u8; 32])> {
    let fvk = {
        let backend = Backend::new(wallet, account, None, store_conn, *wallet.params())?;
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
    let proved = try_prove(wallet, account, fvk, state, id, kind, store_conn)
        .map_err(|e| anyhow!("Error finalizing note split: {}", e))?;
    if !proved {
        return Err(anyhow!(
            "Note-split transaction is not yet finalizable — its funding note isn't witnessable yet"
        ));
    }
    {
        let mut backend = Backend::new(wallet, account, None, store_conn, *wallet.params())?;
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
            proposal_handle as u64,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state = engine::commit_preparation(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                    ReplanThreshold::DEFAULT,
                )
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

        let id = encode_transfer_id(split_id);
        let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
        let pczt_obj = crate::utils::rust_bytes_to_java(env, &proven_pczt)?;
        Ok(env
            .new_object(
                JNI_PREPARED_TRANSFER,
                "(J[B[B)V",
                &[
                    JValue::Long(id),
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
    transfer_id: jlong,
    result_tag: jint,
    _retryable: jboolean,
    tx_id: JByteArray<'local>,
    observed_tip: jlong,
) {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let id = decode_transfer_id(transfer_id)?;
        // The invalidation side table stores the id as TEXT (see INVALIDATION_DDL) — render the
        // engine id back to its decimal form for that row only; everywhere else it stays a u32.
        let transfer_id_str = u32::from(id).to_string();
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        match result_tag {
            // Success: record the broadcast txid. `mark_mined` has no old-crate equivalent call
            // site (the old crate didn't track a separate "mined" event either) — left unwired.
            0 => {
                // Still parsed so a malformed value is rejected at the boundary, but no longer
                // forwarded: the engine records the txid it derived when the transaction was
                // built, which a broadcaster cannot contradict.
                let _txid = crate::parse_txid(env, tx_id)?;
                let mut backend =
                    Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
                let mut state = backend
                    .get_migration()
                    .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
                    .ok_or_else(|| anyhow!("No migration in progress"))?;
                state.mark_broadcast(id);
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
                // We scope `backend` here so it releases the `&mut store_conn` borrow before we
                // call `record_invalidation` (which needs `&store_conn`) and before we re-create
                // `backend` for the `replace_migration` write.
                let failed_opt: Option<MigrationState> = {
                    let backend_read =
                        Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
                    let current = backend_read
                        .get_migration()
                        .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
                    current.and_then(|state| {
                        if !state.is_terminal() {
                            // Status-only swap: every sub-state (note split, preparation,
                            // transactions, anchor grid) passes through verbatim — the engine has
                            // no cancel/fail primitive in rc.1, so this is the accepted way to
                            // mark a run Failed without touching the committed plan.
                            Some(MigrationState::from_parts(
                                engine::MigrationStatus::Failed,
                                state.denominations().clone(),
                                state.preparation().clone(),
                                state.transactions().clone(),
                                state.anchor_bucket_interval(),
                                state.replan_threshold(),
                            ))
                        } else {
                            None
                        }
                    })
                    // backend_read dropped here → &mut store_conn borrow released
                };
                if let Some(failed) = failed_opt {
                    // Write the invalidation reason BEFORE persisting the Failed state.
                    //
                    // Ordering rationale (two separate connections, cannot be one transaction):
                    //   reason-first  → worst case: reason row exists but state never became
                    //                   Failed (second write failed).  The orphan row is
                    //                   inert — `derive_migration_state` only reads it in the
                    //                   Failed arm, and `clear_migration` will erase it on the
                    //                   next re-proposal.
                    //   state-first   → worst case: engine is Failed with no reason row →
                    //                   user sees wrong reason (TransferExpired instead of
                    //                   InvalidTransfer).
                    // reason-first is strictly less harmful, so reason is written first.
                    record_invalidation(
                        &store_conn,
                        &account_bytes,
                        reason,
                        Some(&transfer_id_str),
                    )
                    .map_err(|e| anyhow!("Error recording invalidation reason: {:?}", e))?;
                    let mut backend_write =
                        Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
                    backend_write
                        .replace_migration(&failed)
                        .map_err(|e| anyhow!("Error persisting failed migration: {:?}", e))?;
                }
                Ok(())
            }
            // AwaitingReevaluation (4): a node REJECTED the broadcast and we cannot yet say why. Report it
            // to the engine via report_broadcast_failure rather than terminally failing the plan (tag=2's old
            // behavior for this exact case) — see spec/2026-08-05-migration-engine-full-delegation-design.md
            // §5. observed_tip < 0 means the follow-up tip fetch itself failed; in that case fall back to
            // reporting at the wallet's own currently-scanned tip (still evidence the wallet possesses, just
            // not what the rejecting node specifically reported) rather than skipping the report entirely.
            4 => {
                let mut backend =
                    Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
                let mut state = backend
                    .get_migration()
                    .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
                    .ok_or_else(|| anyhow!("No migration in progress"))?;
                let tip = if observed_tip >= 0 {
                    BlockHeight::from_u32(observed_tip as u32)
                } else {
                    target_height(&wallet)? - 1
                };
                state.report_broadcast_failure(id, tip);
                backend
                    .replace_migration(&state)
                    .map_err(|e| anyhow!("Error persisting broadcast-failure report: {:?}", e))
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
        if let MigrationTxState::Broadcast { txid } = tx.state()
            && let Some(height) = wallet
                .get_tx_height(txid)
                .map_err(|e| anyhow!("Error reading tx height for {:?}: {:?}", txid, e))?
        {
            newly_mined.push((tx.id(), height));
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

/// Extracts the transaction id from one migration transaction's stored PCZT, exactly as
/// `nextDueTransferNative` does (`TransactionExtractor::new(Pczt::parse(bytes)).extract()`). Every
/// state carries its PCZT bytes (see `MigrationTransaction::pczt`'s doc), so this works for any
/// transaction regardless of lifecycle state — the txid is deterministic from the (proven, for a
/// `Proved`+ transaction) transaction. Returns `Ok(None)` if the PCZT can't be extracted yet
/// (e.g. an `AwaitingSignature`/`Signed` transfer whose anchor/witness isn't installed) rather than
/// erroring, so an un-extractable transaction is simply omitted from the own-txid set.
fn pczt_txid(bytes: &[u8]) -> Option<[u8; 32]> {
    let parsed = pczt::Pczt::parse(bytes).ok()?;
    let extracted = pczt::roles::tx_extractor::TransactionExtractor::new(parsed)
        .extract()
        .ok()?;
    Some(*extracted.txid().as_ref())
}

/// Reconciles a committed migration against on-chain truth via two mandatory-ordered passes and, if
/// it detects that the plan can no longer complete as built, marks it `Failed` (reason
/// `"invalid_transfer"`, reason-first ordering — the same mechanism `recordTransferResultNative`
/// tag 2 uses). Returns `true` iff the plan is (or already was) invalidated.
///
///   1. `read_reconciled` — existing pass: any `Broadcast` transfer the wallet now knows a height
///      for is promoted to `Mined`.
///   2. Submit-crash probe: for each `Proved` transfer, extract its txid from its proven PCZT and
///      ask the wallet `get_tx_height`; if the wallet already knows a height, our broadcast landed
///      (we just never recorded it, e.g. crashed after broadcast) — `mark_broadcast` + `mark_mined`.
///
/// A third, foreign-spend-detecting pass used to run here (comparing each candidate transfer's
/// funding nullifier against the wallet's unspent set). It was removed — see
/// spec/2026-08-05-migration-engine-full-delegation-design.md §4: its terminal `Failed` action
/// raced `advance_migration`'s own recoverable remedy for the same event (`InputsSpent` ->
/// `Replan` -> `mark_superseded` -> re-propose), and its detection mechanism only ever worked for
/// `Signed` (pre-proof) transfers, never `Proved` ones. `advance_migration`'s own candidate checks
/// now own foreign-spend detection end to end, reached through the ordinary `nextStep` driver loop.
fn reconcile_invalidated(
    wallet: &mut Wallet,
    account: AccountUuid,
    // No longer read: was only used by the deleted Pass 3 to tag `record_invalidation` calls.
    // Kept (unused) to leave `reconcile_invalidated`'s signature unchanged for its one JNI caller.
    _account_bytes: &[u8],
    store_conn: &mut Connection,
) -> anyhow::Result<bool> {
    // --- Pass 1 + load current state (read_reconciled persists any Broadcast→Mined promotions). ---
    let mut state = {
        let mut backend = Backend::new(&*wallet, account, None, store_conn, *wallet.params())?;
        match read_reconciled(wallet, &mut backend)? {
            Some(s) => s,
            None => return Ok(false),
        }
    };
    // Already terminal (Failed/Complete): nothing to reconcile, but report whether it's Failed so
    // callers can treat "already invalidated" and "just invalidated" identically.
    if state.is_terminal() {
        return Ok(matches!(state.status(), engine::MigrationStatus::Failed));
    }

    // --- Pass 2: submit-crash probe. Promote any Proved transfer whose txid is already on chain. ---
    let mut promotions: Vec<(MigrationTransferId, BlockHeight)> = Vec::new();
    for tx in state.transactions() {
        if !matches!(tx.state(), MigrationTxState::Proved) {
            continue;
        }
        let Some(txid_bytes) = pczt_txid(tx.pczt()) else {
            continue;
        };
        let txid = zcash_protocol::TxId::from_bytes(txid_bytes);
        if let Some(height) = wallet
            .get_tx_height(txid)
            .map_err(|e| anyhow!("Error reading tx height for {:?}: {:?}", txid, e))?
        {
            promotions.push((tx.id(), height));
        }
    }
    if !promotions.is_empty() {
        for (id, height) in &promotions {
            state.mark_broadcast(*id);
            state.mark_mined(*id, *height);
        }
        let mut backend = Backend::new(&*wallet, account, None, store_conn, *wallet.params())?;
        backend
            .replace_migration(&state)
            .map_err(|e| anyhow!("Error persisting submit-crash-probe promotions: {:?}", e))?;
    }

    Ok(false)
}

/// Reconciles a committed migration against on-chain truth via two passes: own-broadcast/mined
/// promotion (submit-crash recovery), matching what `advance_migration`'s own in-flight sweep also
/// does on every `nextStep` call. Returns `JNI_TRUE` iff the plan is (or already was) `Failed`.
///
/// Foreign-spend detection (formerly a third pass here) was removed — see
/// spec/2026-08-05-migration-engine-full-delegation-design.md §4: its terminal `Failed` action
/// raced `advance_migration`'s own recoverable remedy for the same event (`InputsSpent` ->
/// `Replan` -> `mark_superseded` -> re-propose), and its detection mechanism only ever worked for
/// `Signed` (pre-proof) transfers, never `Proved` ones. `advance_migration`'s own candidate checks
/// now own foreign-spend detection end to end, reached through the ordinary `nextStep` driver loop.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_reconcileInvalidatedTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let (_network, mut wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        Ok(
            if reconcile_invalidated(&mut wallet, account, &account_bytes, &mut store_conn)? {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Returns the mined block height of the transaction with the given `txid`, or `-1` if the wallet
/// does not (yet) know a height for it.
///
/// Thin passthrough over `Wallet::get_tx_height` (the same read the reconciliation passes use).
/// F2 uses it on the broadcast path: when a submit call fails non-gRPC, we probe the prepared
/// transfer's txid here before recording an invalidation — a hit means our transaction is already
/// on-chain (e.g. a duplicate rejection after a submit-then-crash), so the "failure" is really a
/// success and the pre-signed plan must NOT be terminally failed.
///
/// `txid` is the 32-byte transaction id in the SAME byte order the SDK's `PreparedTransfer.txid`
/// carries it (internal / little-endian byte order, i.e. `TxId::from_bytes`), NOT the display hex.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_transactionMinedHeightNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    txid: JByteArray<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, _store_conn) = open(env, db_data, network_id)?;
        let txid_bytes = crate::utils::java_bytes_to_rust(env, &txid)?;
        let txid_arr: [u8; 32] = txid_bytes
            .as_slice()
            .try_into()
            .map_err(|_| anyhow!("txid must be exactly 32 bytes, got {}", txid_bytes.len()))?;
        let txid = zcash_protocol::TxId::from_bytes(txid_arr);
        let height = wallet
            .get_tx_height(txid)
            .map_err(|e| anyhow!("Error reading tx height for {:?}: {:?}", txid, e))?;
        Ok(match height {
            Some(h) => i64::from(u32::from(h)),
            None => -1,
        })
    });
    unwrap_exc_or(&mut env, res, -1)
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        let account_bytes = account.expose_uuid().as_bytes().to_vec();
        Ok(derive_migration_state(env, persisted, tip, &store_conn, &account_bytes)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Same derivation as [migrationStateNative], but WITHOUT [read_reconciled]'s mark-mined
/// promotion/write-back — a verified-pure single read (2026-08-07 read/write-separation design:
/// `spec/2026-08-07-migration-read-write-separation-design.md`). Whatever `Broadcast`→`Mined`
/// promotions haven't been persisted yet by the drive loop's own [read_reconciled] pass simply
/// aren't reflected here; callers accept staleness up to the next drive-loop publish. Exists so
/// UI-triggered "just show me the current state" reads never need
/// `MIGRATION_DB_ACCESS_MUTEX` — only the drive loop and explicit user mutations do.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationStateUnreconciledNative<
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
        // Deliberately NOT `mut` — unlike every mutating sibling here, this function must never
        // call a &mut-requiring PoolMigrationWrite method. The compiler enforces the purity claim:
        // an accidental write reintroduced here fails to compile without first adding `mut` back,
        // which is a visible, reviewable diff (2026-08-07 read/write-separation design, Fable
        // review M1).
        let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                let transactions = state.transactions();
                let completed = transactions
                    .iter()
                    .filter(|t| matches!(t.state(), MigrationTxState::Mined { .. }))
                    .count();
                let next_ready_height =
                    if next_broadcastable(&state, DuenessTargets::at(tip)).is_some() {
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let mut rng = OsRng;
        let estimate = engine::estimate_migration_runs(&network, &backend, &mut rng)
            .map_err(|e| anyhow!("Error estimating migration runs: {:?}", e))?;
        Ok(estimate.run_count() as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Delegates to advance_step's STEP_BROADCAST determination — the same check
/// isSyncBlockedNow's Kotlin-side isReadyToBroadcast helper already uses (fixed earlier in this
/// session), now also correct at the Rust layer so the public hasOverdueTransfers() API gets the
/// same fix. A plan-wide "does anything exist overdue anywhere" blanket scan is what this
/// replaces — see spec §A.
fn any_overdue(
    backend: &mut impl PoolMigrationWrite<Error = EngineError>,
    state: &mut MigrationState,
    scanned_tip: BlockHeight,
    effective_tip: BlockHeight,
) -> anyhow::Result<bool> {
    if state.is_terminal() {
        return Ok(false);
    }
    let scanned_target = scanned_tip + 1;
    let estimated_target = std::cmp::max(scanned_target, effective_tip + 1);
    let (code, _id, _next_height, _next_kind) =
        advance_step(backend, state, scanned_target, estimated_target)?;
    Ok(code == STEP_BROADCAST)
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(mut state) => {
                if any_overdue(&mut backend, &mut state, scanned_tip, effective_tip)? {
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
            proposal_handle as u64,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state = engine::commit_preparation(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                    ReplanThreshold::DEFAULT,
                )
                .map_err(|e| anyhow!("Error committing migration schedule: {:?}", e))?;
                Ok((state, Vec::new()))
            },
        )?;
        // MIGRATION_DIAG: dump the committed schedule with the REAL drawn anchor boundaries
        // (the proposal's `anchorHeight` shown to the app is only a duration-display reference —
        // the per-transfer bucket boundaries exist first here, post-commit).
        let committed_state = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
            backend
                .get_migration()
                .map_err(|e| anyhow!("Error re-reading committed migration state: {:?}", e))?
        };
        if let Some(state) = committed_state {
            for t in state.transactions() {
                tracing::debug!(
                    "MIGRATION_DIAG committedPlan: {:?} kind={:?} scheduled={:?} boundary={:?} expiry={:?} state={:?}",
                    t.id(),
                    t.kind(),
                    t.scheduled_height(),
                    t.anchor_boundary(),
                    t.expiry_height(),
                    t.state(),
                );
            }
            // Boundary-checkpoint validation. ZIP 318 draws every anchor boundary in the recent
            // PAST (age >= 1 bucket below the observed tip — `draw_anchor_boundary`), on the
            // assumption that the wallet has retained grid checkpoints continuously since NU6.3.
            // A wallet whose scan history predates always-on retention has gaps: a boundary
            // drawn onto a grid height that was scanned WITHOUT retention has no checkpoint,
            // cannot get one retroactively (a backfilled checkpoint would carry the wrong tree
            // position and therefore a consensus-invalid anchor), and its transfer would sit at
            // AnchorNotFound forever. Fail the commit NOW — clearing the just-committed run —
            // so the caller can re-propose: a fresh draw lands on other (typically newer,
            // retained) boundaries. The Kotlin layer surfaces this as a distinct
            // "BoundaryCheckpointMissing" error the confirm paths retry on.
            let scanned_tip = target - 1;
            // Attempt the empty-gap backfill first — only boundaries that remain unprovable
            // (non-empty gap, no preceding checkpoint) fail the commit.
            let missing = ensure_boundary_checkpoints(&store_conn, &state, scanned_tip)?;
            if !missing.is_empty() {
                tracing::warn!(
                    "MIGRATION_DIAG commit validation: {} boundary checkpoint(s) missing — cancelling this run for re-propose: {:?}",
                    missing.len(),
                    missing,
                );
                // Status-only swap, same shape as clearMigrationNative: the run cannot proceed.
                let cancelled = MigrationState::from_parts(
                    engine::MigrationStatus::Failed,
                    state.denominations().clone(),
                    state.preparation().clone(),
                    state.transactions().clone(),
                    state.anchor_bucket_interval(),
                    state.replan_threshold(),
                );
                let mut backend =
                    Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
                backend
                    .replace_migration(&cancelled)
                    .map_err(|e| anyhow!("Error cancelling checkpoint-invalid migration: {}", e))?;
                return Err(anyhow!(
                    "BoundaryCheckpointMissing: {} transfer(s) drew boundaries with no retained checkpoint: {:?}",
                    missing.len(),
                    missing
                ));
            }
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Backfills a missing note-commitment-tree checkpoint at `boundary` for one pool, when — and
/// only when — the gap since the nearest EARLIER checkpoint is provably commitment-free: the
/// pool's `*_commitment_tree_size` recorded on the two endpoint blocks is identical and every
/// block of the gap has been scanned. An empty gap means the tree state (and therefore the
/// anchor root) at `boundary` is byte-identical to the earlier checkpoint's, so copying its
/// position IS the exact checkpoint — not an approximation.
///
/// Why this exists: the sync engine writes tree checkpoints per scan sub-batch, not per block,
/// so an anchor-grid multiple that falls INSIDE a multi-block chunk gets no checkpoint even with
/// anchor retention configured (observed live 2026-07-28: grid height 4212168 skipped by a
/// 4212165..4212170 chunk of empty blocks). The real fix — the engine cutting sub-batches on the
/// retention grid — belongs to slipstream-core; this backfill exactly recovers the common
/// empty-gap case in the meantime, and a NON-empty gap (commitments landed inside the chunk)
/// still reports `false` so callers can reject/re-propose rather than prove a wrong anchor.
fn backfill_boundary_checkpoint_for_pool(
    conn: &Connection,
    cp_table: &str,
    size_col: &str,
    boundary: u32,
) -> anyhow::Result<bool> {
    let exists: bool = conn
        .query_row(
            &format!("SELECT EXISTS(SELECT 1 FROM {cp_table} WHERE checkpoint_id = ?)"),
            [boundary],
            |r| r.get(0),
        )
        .map_err(|e| anyhow!("Error probing {cp_table} at {boundary}: {}", e))?;
    if exists {
        return Ok(true);
    }
    let prev: Option<u32> = conn
        .query_row(
            &format!("SELECT MAX(checkpoint_id) FROM {cp_table} WHERE checkpoint_id < ?"),
            [boundary],
            |r| r.get(0),
        )
        .map_err(|e| anyhow!("Error reading preceding {cp_table} checkpoint: {}", e))?;
    let Some(prev) = prev else {
        return Ok(false);
    };
    let gap_len = i64::from(boundary) - i64::from(prev);
    let (scanned_all, size_prev, size_at): (i64, Option<i64>, Option<i64>) = conn
        .query_row(
            &format!(
                "SELECT (SELECT COUNT(*) FROM blocks WHERE height > ?1 AND height <= ?2),                         (SELECT {size_col} FROM blocks WHERE height = ?1),                         (SELECT {size_col} FROM blocks WHERE height = ?2)"
            ),
            [prev, boundary],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(|e| anyhow!("Error reading gap blocks for {cp_table}: {}", e))?;
    let empty_gap =
        scanned_all == gap_len && matches!((size_prev, size_at), (Some(a), Some(b)) if a == b);
    if !empty_gap {
        return Ok(false);
    }
    conn.execute(
        &format!(
            "INSERT OR IGNORE INTO {cp_table} (checkpoint_id, position)              SELECT ?2, position FROM {cp_table} WHERE checkpoint_id = ?1"
        ),
        [prev, boundary],
    )
    .map_err(|e| anyhow!("Error backfilling {cp_table} checkpoint at {boundary}: {}", e))?;
    tracing::debug!(
        "MIGRATION_DIAG checkpointBackfill: {cp_table} at {} copied from {} (empty gap)",
        boundary,
        prev
    );
    Ok(true)
}

/// Whether Ironwood retention had already started by anchor height `b` — i.e. whether ANY
/// checkpoint at or before `b` exists. A transfer's Ironwood bundle carries its own dummy spend
/// (see `build_transfer_pczt`'s tests: `pczt.ironwood().anchor().is_none()` at build time is ZIP
/// 374 deferral, not absence of a real requirement — the crossing action still needs an anchor at
/// prove time), and that anchor resolves via the well-known empty-tree root when Ironwood was
/// still genuinely empty AT `b`. This must be evaluated per anchor height, not once globally:
/// checking "does Ironwood have any checkpoint right now" instead would falsely require a real
/// checkpoint for an old anchor height that predates Ironwood's very first checkpoint, the moment
/// ANY later transfer in the same plan mines and Ironwood retention starts elsewhere in the plan's
/// own height range (observed live 2026-08-04: 3 transfers with anchor boundaries below Ironwood's
/// first-ever checkpoint became permanently unprovable once sibling transfers with later anchors
/// mined and created that first checkpoint).
fn ironwood_retention_started_by(conn: &Connection, b: u32) -> anyhow::Result<bool> {
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM ironwood_tree_checkpoints WHERE checkpoint_id <= ?1)",
        [b],
        |r| r.get(0),
    )
    .map_err(|e| {
        anyhow!(
            "Error probing ironwood checkpoints at or before {}: {}",
            b,
            e
        )
    })
}

/// Ensures the checkpoints every settled, still-`Signed` transfer's anchor boundary needs exist
/// (backfilling empty gaps per [`backfill_boundary_checkpoint_for_pool`]), and returns the
/// boundaries that remain unprovable. Ironwood is required only once its tree had checkpoints as
/// of THIS transfer's own anchor height (see [`ironwood_retention_started_by`]) — an empty
/// post-activation tree at that height resolves anchors via the empty-tree root.
fn ensure_boundary_checkpoints(
    conn: &Connection,
    state: &MigrationState,
    scanned_tip: BlockHeight,
) -> anyhow::Result<Vec<(MigrationTransferId, BlockHeight)>> {
    let mut missing = Vec::new();
    for t in state.transactions() {
        if !matches!(t.state(), MigrationTxState::Signed) {
            continue;
        }
        if let Some(boundary) = t.anchor_boundary()
            && boundary <= scanned_tip
        {
            let b = u32::from(boundary);
            let orchard_ok = backfill_boundary_checkpoint_for_pool(
                conn,
                "orchard_tree_checkpoints",
                "orchard_commitment_tree_size",
                b,
            )?;
            let ironwood_ok = !ironwood_retention_started_by(conn, b)?
                || backfill_boundary_checkpoint_for_pool(
                    conn,
                    "ironwood_tree_checkpoints",
                    "ironwood_commitment_tree_size",
                    b,
                )?;
            if !orchard_ok || !ironwood_ok {
                missing.push((t.id(), boundary));
            }
        }
    }
    Ok(missing)
}

#[cfg(test)]
mod ironwood_retention_started_by_tests {
    use super::*;

    /// Minimal in-memory fixture: just the one column `ironwood_retention_started_by` reads —
    /// no need for the full wallet schema to exercise this predicate in isolation.
    fn fixture() -> Connection {
        let conn = Connection::open_in_memory().expect("in-memory db");
        conn.execute_batch(
            "CREATE TABLE ironwood_tree_checkpoints (
                 checkpoint_id INTEGER PRIMARY KEY,
                 position INTEGER
             );",
        )
        .expect("create fixture table");
        conn
    }

    #[test]
    fn no_checkpoints_at_all_is_false_at_any_height() {
        let conn = fixture();
        assert!(!ironwood_retention_started_by(&conn, 100).unwrap());
        assert!(!ironwood_retention_started_by(&conn, 4_237_260).unwrap());
    }

    #[test]
    fn height_strictly_before_the_first_checkpoint_is_false() {
        // Regression for the live bug: an anchor boundary drawn before Ironwood's very first
        // checkpoint must read as "retention had not started yet" — the empty-tree-root shortcut
        // — regardless of what checkpoints exist at LATER heights.
        let conn = fixture();
        conn.execute(
            "INSERT INTO ironwood_tree_checkpoints (checkpoint_id, position) VALUES (?1, 0)",
            [4_237_284u32],
        )
        .unwrap();

        assert!(!ironwood_retention_started_by(&conn, 4_237_260).unwrap());
        assert!(!ironwood_retention_started_by(&conn, 4_237_272).unwrap());
    }

    #[test]
    fn height_at_or_after_the_first_checkpoint_is_true() {
        let conn = fixture();
        conn.execute(
            "INSERT INTO ironwood_tree_checkpoints (checkpoint_id, position) VALUES (?1, 0)",
            [4_237_284u32],
        )
        .unwrap();

        assert!(ironwood_retention_started_by(&conn, 4_237_284).unwrap());
        assert!(ironwood_retention_started_by(&conn, 4_237_300).unwrap());
    }

    #[test]
    fn later_checkpoints_do_not_retroactively_affect_earlier_heights() {
        // The exact shape of the live bug: a transfer with anchor 4237248 was checked back when
        // Ironwood had NO checkpoints at all (its own state is fine either way, since 4237248 is
        // still before the first real one). What must NOT happen is a transfer at 4237260 —
        // between 4237248 and Ironwood's real first checkpoint at 4237284 — reading as
        // retention-started just because 4237284 and 4237296 exist by the time it's re-checked.
        let conn = fixture();
        for (id, pos) in [(4_237_284u32, 1u32), (4_237_296, 2)] {
            conn.execute(
                "INSERT INTO ironwood_tree_checkpoints (checkpoint_id, position) VALUES (?1, ?2)",
                [id, pos],
            )
            .unwrap();
        }

        assert!(
            !ironwood_retention_started_by(&conn, 4_237_248).unwrap(),
            "well before the first real checkpoint — pre-retention"
        );
        assert!(
            !ironwood_retention_started_by(&conn, 4_237_260).unwrap(),
            "4237260 sits strictly between no-checkpoint and the first real checkpoint (4237284) \
             — later checkpoints existing must not retroactively require one here"
        );
        assert!(ironwood_retention_started_by(&conn, 4_237_284).unwrap());
        assert!(ironwood_retention_started_by(&conn, 4_237_296).unwrap());
    }
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
            let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
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

        // Make settled boundaries provable before selecting candidates: backfill any grid
        // checkpoint the sync engine's sub-batching skipped over an empty gap (see
        // `backfill_boundary_checkpoint_for_pool`) — without this, a boundary inside a scanned
        // chunk sits at AnchorNotFound forever.
        let still_missing = ensure_boundary_checkpoints(&store_conn, &state, target - 1)?;
        if !still_missing.is_empty() {
            tracing::warn!(
                "MIGRATION_DIAG finalize: {} settled boundary checkpoint(s) unrecoverable (non-empty gap): {:?}",
                still_missing.len(),
                still_missing
            );
        }

        // Collect ready ids/kinds up front (not while iterating `state.transactions()`) since
        // `try_prove` needs `&mut state` — see `is_prove_ready`'s doc comment for why this doesn't
        // just loop `MigrationState::next_provable`.
        let ready: Vec<(MigrationTransferId, MigrationTxKind)> = state
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
            let (boundary, scheduled) = state
                .transactions()
                .iter()
                .find(|t| t.id() == id)
                .map(|t| (t.anchor_boundary(), Some(t.scheduled_height())))
                .unwrap_or((None, None));
            if try_prove(
                &mut wallet,
                account,
                fvk.clone(),
                &mut state,
                id,
                kind,
                &mut store_conn,
            )
            .map_err(|e| anyhow!("Error proving transfer {:?}: {}", id, e))?
            {
                finalized_count += 1;
                tracing::debug!(
                    "MIGRATION_DIAG finalizeReadyTransfers: PROVED {:?} kind={:?} boundary={:?} scheduled={:?}",
                    id,
                    kind,
                    boundary,
                    scheduled,
                );
            }
        }
        if finalized_count > 0 {
            let mut backend =
                Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
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
    AwaitingProof(MigrationTransferId),
    /// A transfer is proven and ready to broadcast.
    Ready(&'a MigrationTransaction),
}

/// Delegates entirely to `advance_step` (the same call `nextStepNative` makes) rather than
/// re-deriving due-ness locally — see spec/2026-08-05-migration-engine-full-delegation-design.md
/// §1. `backend` is threaded through only because `advance_step` requires it (the in-flight sweep
/// it runs may persist determinations); callers already hold one from `open`.
///
/// Replaces the former hand-rolled Proved/Signed filter, which never checked
/// `unsatisfiable`/dead-dependency status at all — a due, Proved transfer whose inputs had
/// already been observed spent (or that was stranded behind such a transaction) was offered as
/// `Ready` regardless. `advance_step`'s `dead_set` closure withholds it correctly; see the
/// `next_due_transfer_delegation_tests` module below for the differential coverage pinning this
/// down.
fn next_due_transfer_result<'a>(
    backend: &mut impl PoolMigrationWrite<Error = EngineError>,
    state: &'a mut MigrationState,
    scanned_tip: BlockHeight,
    effective_tip: BlockHeight,
) -> anyhow::Result<DueTransferResult<'a>> {
    if state.is_terminal() {
        return Ok(DueTransferResult::NothingDue);
    }
    // advance_step's own convention: scanned_target = tip + 1, estimated_target = max(scanned, est
    // + 1) — matches nextStepNative exactly (spec §1's height-convention hazard). Callers of this
    // function already pass scanned_tip/effective_tip as raw tips (not +1), so convert here.
    let scanned_target = scanned_tip + 1;
    let estimated_target = std::cmp::max(scanned_target, effective_tip + 1);
    let (code, id, _next_height, _next_kind) =
        advance_step(backend, state, scanned_target, estimated_target)?;
    Ok(match code {
        STEP_BROADCAST => {
            let tx_id = MigrationTransferId::new(id as u32);
            match state.transactions().iter().find(|t| t.id() == tx_id) {
                Some(tx) => DueTransferResult::Ready(tx),
                None => DueTransferResult::NothingDue,
            }
        }
        STEP_PROVE => DueTransferResult::AwaitingProof(MigrationTransferId::new(id as u32)),
        _ => DueTransferResult::NothingDue,
    })
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let Some(mut state) = read_reconciled(&wallet, &mut backend)? else {
            // No migration: status=0, both nullable fields null.
            return Ok(env
                .new_object(
                    JNI_DUE_TRANSFER_RESULT,
                    format!("(ILjava/lang/Long;L{JNI_PREPARED_TRANSFER};)V"),
                    &[
                        JValue::Int(0),
                        JValue::Object(&JObject::null()),
                        JValue::Object(&JObject::null()),
                    ],
                )?
                .into_raw());
        };

        tracing::debug!(
            "MIGRATION_DIAG nextDueTransfer: scanned_tip={:?} effective_tip={:?} estimated_tip={} \
             transfers={} states={:?}",
            scanned_tip,
            effective_tip,
            estimated_tip,
            state
                .transactions()
                .iter()
                .filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. }))
                .count(),
            state
                .transactions()
                .iter()
                .filter(|t| matches!(t.kind(), MigrationTxKind::Transfer { .. }))
                .map(|t| (t.id(), t.state(), t.scheduled_height()))
                .collect::<Vec<_>>(),
        );

        match next_due_transfer_result(&mut backend, &mut state, scanned_tip, effective_tip)? {
            DueTransferResult::NothingDue => Ok(env
                .new_object(
                    JNI_DUE_TRANSFER_RESULT,
                    format!("(ILjava/lang/Long;L{JNI_PREPARED_TRANSFER};)V"),
                    &[
                        JValue::Int(0),
                        JValue::Object(&JObject::null()),
                        JValue::Object(&JObject::null()),
                    ],
                )?
                .into_raw()),
            DueTransferResult::AwaitingProof(id) => {
                let id_obj = env
                    .call_static_method(
                        "java/lang/Long",
                        "valueOf",
                        "(J)Ljava/lang/Long;",
                        &[JValue::Long(encode_transfer_id(id))],
                    )?
                    .l()?;
                Ok(env
                    .new_object(
                        JNI_DUE_TRANSFER_RESULT,
                        format!("(ILjava/lang/Long;L{JNI_PREPARED_TRANSFER};)V"),
                        &[
                            JValue::Int(2),
                            JValue::Object(&id_obj),
                            JValue::Object(&JObject::null()),
                        ],
                    )?
                    .into_raw())
            }
            DueTransferResult::Ready(tx) => {
                // `Proved` carries the fully witnessed/anchored/proven PCZT bytes (installed by
                // `finalizeReadyTransfersNative`'s `try_prove`) — extract the txid directly from them.
                let bytes = tx.pczt();
                let extracted = pczt::roles::tx_extractor::TransactionExtractor::new(
                    pczt::Pczt::parse(bytes)
                        .map_err(|e| anyhow!("parse proven transfer pczt: {:?}", e))?,
                )
                .extract()
                .map_err(|e| anyhow!("extract proven transfer tx: {:?}", e))?;
                let txid: [u8; 32] = *extracted.txid().as_ref();
                let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
                let pczt_obj = crate::utils::rust_bytes_to_java(env, bytes)?;
                let prepared = env.new_object(
                    JNI_PREPARED_TRANSFER,
                    "(J[B[B)V",
                    &[
                        JValue::Long(encode_transfer_id(tx.id())),
                        JValue::Object(&txid_obj),
                        JValue::Object(&pczt_obj),
                    ],
                )?;
                Ok(env
                    .new_object(
                        JNI_DUE_TRANSFER_RESULT,
                        format!("(ILjava/lang/Long;L{JNI_PREPARED_TRANSFER};)V"),
                        &[
                            JValue::Int(1),
                            JValue::Object(&JObject::null()),
                            JValue::Object(&prepared),
                        ],
                    )?
                    .into_raw())
            }
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The live, persisted status of EVERY committed migration transaction — transfers AND
/// preparations — read straight from the migration store's current state, so it always reflects
/// what the engine committed (the engine is the single source of truth for the plan; this
/// function only SURFACES it). Unlike the app's own `MigrationPlanRepository` cache (populated
/// once, at propose/commit time), this is never stale.
///
/// Per entry: `(id, is_transfer, is_sent, is_proved, scheduled_height, anchor_boundary, ready,
/// action, blocker, amount_zatoshi, prep_layer, prep_index, depends_on, expiry_height,
/// mined_height)` — everything a display or scheduling consumer needs, read live from the
/// engine's persisted state so the app never has to cache plan data (amounts come from
/// `MigrationState::transfer_crossing_value`, the accessor built for exactly this marshaling).
/// - `is_transfer` distinguishes transfers from preparation (note-split layer) transactions —
///   display-facing consumers filter on it or correlate by id (prep ids match no display row);
///   scheduling consumers (Lane B's next-window re-arm) deliberately stay kind-agnostic, since
///   `nextDueTransferNative` serves due preparations too.
/// - `is_proved` is true once the transaction has a proof (`Proved`/`Broadcast`/`Mined`) — the
///   app's sync lane (Lane A) wakes at the anchor-boundary heights of unproved, unsent entries.
/// - `anchor_boundary` is the committed ZIP 318 bucket boundary the transaction proves against,
///   or `-1` when the engine committed none (preparations prove at their natural anchor).
///
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(ptr::null_mut());
        };

        // Keyed by the transaction's real, stable MigrationTransferId — NOT `transfer_crossing()` (the
        // funding-note/crossing index). The app's displayed "Transfer N" position comes from
        // sorting the ORIGINAL proposal by broadcast_height (see `encode_migration_schedule`),
        // while the engine assigns real tx ids in crossing/schedule() order at commit time —
        // ZIP 318 deliberately shuffles those two orderings apart, so they permanently disagree.
        // The app now carries this same id on its cached `MigrationTransfer.id` (see
        // `MigrationSchedule.toMigrationPlan`), which is the only stable key the two sides share.
        //
        // Engine per-tx status view (ready/action/blocker), taken as-is. The former local
        // late-dependency guard veto is gone (resolved upstream in rc.6 — see the driver-surface
        // banner): a transfer whose dependency mined past its anchor boundary is now honestly
        // reported READY-to-Prove, because `engine::prove_transfer` re-draws the boundary at prove
        // time so that Prove genuinely completes.
        let engine_statuses = state.transaction_statuses(DuenessTargets::at(tip + 1));

        // (id, is_transfer, is_sent, is_proved, scheduled_height, anchor_boundary, ready, action,
        //  blocker, amount_zatoshi, prep_layer, prep_index, depends_on, expiry_height, mined_height)
        #[allow(clippy::type_complexity)]
        let transactions: Vec<(
            MigrationTransferId,
            bool,
            bool,
            bool,
            BlockHeight,
            Option<BlockHeight>,
            bool,
            i32,
            i32,
            i64,
            i32,
            i32,
            Vec<i64>,
            i64,
            i64,
        )> = state
            .transactions()
            .iter()
            .zip(engine_statuses.iter())
            .map(|(t, status)| {
                let is_transfer = matches!(t.kind(), MigrationTxKind::Transfer { .. });
                let is_sent = matches!(
                    t.state(),
                    MigrationTxState::Broadcast { .. } | MigrationTxState::Mined { .. }
                );
                let is_proved = matches!(
                    t.state(),
                    MigrationTxState::Proved
                        | MigrationTxState::Broadcast { .. }
                        | MigrationTxState::Mined { .. }
                );
                let (ready, action, blocker) = {
                    let action = match status.action() {
                        Some(zcash_pool_migration::state::NextAction::Prove) => ACTION_PROVE,
                        Some(zcash_pool_migration::state::NextAction::Broadcast) => {
                            ACTION_BROADCAST
                        }
                        None => ACTION_NONE,
                    };
                    let blocker = match status.blocked_on() {
                        Some(zcash_pool_migration::state::Blocker::Dependencies) => {
                            BLOCKER_DEPENDENCIES
                        }
                        Some(zcash_pool_migration::state::Blocker::Schedule) => BLOCKER_SCHEDULE,
                        Some(zcash_pool_migration::state::Blocker::AnchorBoundary) => {
                            BLOCKER_ANCHOR_BOUNDARY
                        }
                        Some(zcash_pool_migration::state::Blocker::Signature) => BLOCKER_SIGNATURE,
                        Some(zcash_pool_migration::state::Blocker::Expired) => BLOCKER_EXPIRED,
                        Some(zcash_pool_migration::state::Blocker::ExpiryImminent) => {
                            BLOCKER_EXPIRY_IMMINENT
                        }
                        Some(zcash_pool_migration::state::Blocker::AwaitingReevaluation) => {
                            BLOCKER_AWAITING_REEVALUATION
                        }
                        Some(zcash_pool_migration::state::Blocker::Unsatisfiable) => {
                            BLOCKER_UNSATISFIABLE
                        }
                        None => BLOCKER_NONE,
                    };
                    (status.ready(), action, blocker)
                };
                // The engine-persisted denomination for a transfer; -1 for preparations (their
                // value is internal plumbing, never displayed).
                let amount_zatoshi = state
                    .transfer_crossing_value(t)
                    .map_or(-1i64, |v| i64::try_from(u64::from(v)).unwrap_or(-1));
                let (prep_layer, prep_index) = match t.kind() {
                    MigrationTxKind::Preparation { layer, index } => (layer as i32, index as i32),
                    MigrationTxKind::Transfer { .. } => (-1, -1),
                };
                let depends_on: Vec<i64> = status
                    .depends_on()
                    .iter()
                    .map(|d| encode_transfer_id(*d))
                    .collect();
                let expiry_height = i64::from(u32::from(status.expiry_height()));
                let mined_height = status
                    .mined_height()
                    .map_or(-1i64, |h| i64::from(u32::from(h)));
                (
                    t.id(),
                    is_transfer,
                    is_sent,
                    is_proved,
                    t.scheduled_height(),
                    t.anchor_boundary(),
                    ready,
                    action,
                    blocker,
                    amount_zatoshi,
                    prep_layer,
                    prep_index,
                    depends_on,
                    expiry_height,
                    mined_height,
                )
            })
            .collect();

        let jtransfers = crate::utils::rust_vec_to_java(
            env,
            transactions,
            JNI_MIGRATION_TRANSFER_STATE,
            |env,
             (
                id,
                is_transfer,
                is_sent,
                is_proved,
                scheduled_height,
                anchor_boundary,
                ready,
                action,
                blocker,
                amount_zatoshi,
                prep_layer,
                prep_index,
                depends_on,
                expiry_height,
                mined_height,
            )| {
                let jdeps = env.new_long_array(depends_on.len() as i32)?;
                env.set_long_array_region(&jdeps, 0, &depends_on)?;
                env.new_object(
                    JNI_MIGRATION_TRANSFER_STATE,
                    "(JZZZJJZIIJII[JJJ)V",
                    &[
                        JValue::Long(encode_transfer_id(id)),
                        JValue::Bool(is_transfer as jboolean),
                        JValue::Bool(is_sent as jboolean),
                        JValue::Bool(is_proved as jboolean),
                        JValue::Long(i64::from(u32::from(scheduled_height))),
                        JValue::Long(anchor_boundary.map_or(-1i64, |b| i64::from(u32::from(b)))),
                        JValue::Bool(ready as jboolean),
                        JValue::Int(action),
                        JValue::Int(blocker),
                        JValue::Long(amount_zatoshi),
                        JValue::Int(prep_layer),
                        JValue::Int(prep_index),
                        JValue::Object(&jdeps),
                        JValue::Long(expiry_height),
                        JValue::Long(mined_height),
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

/// Reads the two `blocks`-table samples the measured-block-rate estimator needs — the latest
/// scanned block and the block `window_blocks` below it — via THIS crate's BUNDLED SQLite
/// (rusqlite), returning `[latest_height, latest_time, older_height, older_time]` (all epoch
/// seconds for the times), or `[latest_height, latest_time]` when no older sample exists, or an
/// EMPTY array when no block has been scanned yet.
///
/// CRITICAL — dual-SQLite-instance hazard: the wallet `data.sqlite3` is engine-owned and written
/// through the bundled SQLite the slipstream/backend engines link. It MUST NOT be opened through a
/// SECOND SQLite library instance in the same process (Android-framework `SQLiteDatabase`): SQLite
/// same-process lock coordination only holds within one library instance, so a framework
/// connection's `close()` drops the engine's fcntl/WAL locks and truncates the `-shm` index under
/// the engine's live mmap → deterministic SIGBUS (Milan's `08-engine-sigbus-android.md`; the
/// production host reads moved to bundled rusqlite for exactly this reason — see
/// `slipstream::read_query`). This reader therefore uses a read-only rusqlite connection (the same
/// bundled library the engine uses), never `ReadOnlySupportSqliteOpenHelper`/framework SQLite,
/// which the estimator previously used and which reintroduced the hazard.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_blockRateSamplesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    window_blocks: jlong,
) -> jlongArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path: String = env.get_string(&db_data)?.into();
        let conn = Connection::open_with_flags(
            &db_path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|e| anyhow!("block-rate read-only open: {}", e))?;
        conn.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| anyhow!("block-rate busy_timeout: {}", e))?;
        // Defense-in-depth, matching open_at: disable mmap so a WAL checkpoint TRUNCATE by the
        // engine's writer can never shrink a mapped region under this read-only reader (the classic
        // SIGBUS victim). Bundled SQLite defaults mmap_size to 0, so this is belt-and-suspenders.
        conn.pragma_update(None, "mmap_size", 0)
            .map_err(|e| anyhow!("block-rate mmap disable: {}", e))?;

        // A failing/absent read (no `blocks` table on a fresh wallet, transient lock, etc.) maps to
        // "no sample" and the Kotlin estimator falls back to the protocol rate — this is a
        // best-effort projection, never load-bearing, so `.ok()` (drop the error to None) is right.
        let latest: Option<(i64, i64)> = conn
            .query_row(
                "SELECT height, time FROM blocks ORDER BY height DESC LIMIT 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .ok();
        let out: Vec<i64> = match latest {
            None => Vec::new(),
            Some((latest_h, latest_t)) => {
                let older_target = (latest_h - window_blocks).max(0);
                // Closest block AT OR BELOW the window target — robust to gaps, unlike an exact
                // `height = target` match (which returned null and fell back whenever that one
                // height happened to be unscanned).
                let older: Option<(i64, i64)> = conn
                    .query_row(
                        "SELECT height, time FROM blocks WHERE height <= ?1 ORDER BY height DESC LIMIT 1",
                        [older_target],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .ok();
                match older {
                    Some((oh, ot)) => vec![latest_h, latest_t, oh, ot],
                    None => vec![latest_h, latest_t],
                }
            }
        };
        let arr = env.new_long_array(out.len() as i32)?;
        env.set_long_array_region(&arr, 0, &out)?;
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Pure-Rust core of `migrationSummaryNative`'s query logic, split out so it's testable without a
/// JNIEnv (see `migration_summary_tests` below) — the JNI wrapper just marshals this into a
/// `jlongArray`. Returns `[totalMigratedZatoshi, transferCount, firstMinedEpochSeconds,
/// lastMinedEpochSeconds]`, or an EMPTY vec when there is no migration data / no mined transfer
/// yet.
///
/// - `totalMigratedZatoshi` = SUM of every per-transfer crossing value (what actually crossed to
///   Ironwood). NOTE: this is LESS than the balance that left Orchard, by the migration fees.
/// - `transferCount` = number of MINED `kind='transfer'` transactions.
/// - `first`/`lastMinedEpochSeconds` = MIN/MAX `blocks.time`, keyed by `mined_height`, over ALL
///   mined migration transactions — transfers AND preparations. Preparations (note-splits) always
///   run first in a migration plan and can account for a large fraction of total elapsed time, so
///   the elapsed-duration display must span the whole plan rather than just the crossing-transfer
///   sub-window.
///
/// Best-effort and never load-bearing: any read failure (missing tables on a fresh/other wallet,
/// transient lock, etc.) `.ok()`-swallows to an empty vec and the screen falls back to zeros.
fn migration_summary(conn: &Connection) -> Vec<i64> {
    // Every fact is `.ok()`-swallowed: a fresh/other wallet lacks these tables entirely, and a
    // migration with no mined transfer yet has no duration — either way this is best-effort and
    // the screen falls back to zeros.
    let total_migrated: Option<i64> = conn
        .query_row(
            "SELECT COALESCE(SUM(value), 0) FROM orchard_ironwood_migration_crossing_values",
            [],
            |r| r.get(0),
        )
        .ok();
    let transfer_count: Option<i64> = conn
        .query_row(
            "SELECT COUNT(*) FROM orchard_ironwood_migration_transactions \
             WHERE kind = 'transfer' AND state = 'mined'",
            [],
            |r| r.get(0),
        )
        .ok();
    // MIN/MAX block time over ALL mined migration transactions (transfers AND preparations), for
    // the elapsed-duration display — preparations run first and can take a large share of total
    // elapsed time, so they must count toward the duration bounds even though they're excluded
    // from transfer_count above.
    let bounds: Option<(i64, i64)> = conn
        .query_row(
            "SELECT MIN(b.time), MAX(b.time) \
             FROM orchard_ironwood_migration_transactions t \
             JOIN blocks b ON b.height = t.mined_height \
             WHERE t.state = 'mined'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok();

    // No mined transfer → nothing meaningful to show; return empty and let the screen zero-fill.
    match (transfer_count, bounds) {
        (Some(count), Some((first, last))) if count > 0 => {
            vec![total_migrated.unwrap_or(0), count, first, last]
        }
        _ => Vec::new(),
    }
}

/// Reads the ENGINE's persisted migration outcome — the single source of truth for the just-
/// finished migration — for the Migration Complete screen's real summary, which the app-side plan
/// (cleared on completion) can no longer supply. See `migration_summary` for the full field
/// semantics and best-effort fallback behavior.
///
/// Uses THIS crate's BUNDLED read-only SQLite (rusqlite), exactly like `blockRateSamplesNative` —
/// see its Rust doc for the dual-SQLite-instance SIGBUS hazard that forbids opening the engine's
/// `data.sqlite3` through Android-framework SQLite.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_migrationSummaryNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
) -> jlongArray {
    let res = catch_unwind(&mut env, |env| {
        let db_path: String = env.get_string(&db_data)?.into();
        let conn = Connection::open_with_flags(
            &db_path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|e| anyhow!("migration-summary read-only open: {}", e))?;
        conn.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| anyhow!("migration-summary busy_timeout: {}", e))?;
        // Defense-in-depth, matching blockRateSamplesNative/open_at: disable mmap so a WAL
        // checkpoint TRUNCATE by the engine's writer can never shrink a mapped region under this
        // read-only reader (the classic SIGBUS victim).
        conn.pragma_update(None, "mmap_size", 0)
            .map_err(|e| anyhow!("migration-summary mmap disable: {}", e))?;

        let out = migration_summary(&conn);
        let arr = env.new_long_array(out.len() as i32)?;
        env.set_long_array_region(&arr, 0, &out)?;
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(test)]
mod migration_summary_tests {
    use super::*;

    /// Minimal in-memory fixture: just the columns `migration_summary` reads — no need for the
    /// full wallet schema to exercise this query in isolation.
    fn fixture() -> Connection {
        let conn = Connection::open_in_memory().expect("in-memory db");
        conn.execute_batch(
            "CREATE TABLE blocks (height INTEGER PRIMARY KEY, time INTEGER);
             CREATE TABLE orchard_ironwood_migration_transactions (
                 id INTEGER PRIMARY KEY,
                 kind TEXT NOT NULL,
                 state TEXT NOT NULL,
                 mined_height INTEGER
             );
             CREATE TABLE orchard_ironwood_migration_crossing_values (value INTEGER);",
        )
        .expect("create fixture tables");
        conn
    }

    fn insert_block(conn: &Connection, height: i64, time: i64) {
        conn.execute(
            "INSERT INTO blocks (height, time) VALUES (?1, ?2)",
            rusqlite::params![height, time],
        )
        .unwrap();
    }

    fn insert_tx(conn: &Connection, kind: &str, state: &str, mined_height: Option<i64>) {
        conn.execute(
            "INSERT INTO orchard_ironwood_migration_transactions (kind, state, mined_height) \
             VALUES (?1, ?2, ?3)",
            rusqlite::params![kind, state, mined_height],
        )
        .unwrap();
    }

    #[test]
    fn no_data_at_all_yields_empty() {
        let conn = fixture();
        assert!(migration_summary(&conn).is_empty());
    }

    #[test]
    fn no_mined_transfer_yields_empty_even_if_a_preparation_is_mined() {
        // transferCount is deliberately transfer-only: a mined preparation alone (no mined
        // transfer yet) must still report nothing, matching the pre-existing "no mined transfer
        // → nothing meaningful yet" gate.
        let conn = fixture();
        insert_block(&conn, 100, 1_000);
        insert_tx(&conn, "preparation", "mined", Some(100));

        assert!(migration_summary(&conn).is_empty());
    }

    #[test]
    fn duration_spans_from_an_earlier_mined_preparation_not_just_the_transfer() {
        // The regression this test guards: a note-split preparation runs FIRST and is mined well
        // before the crossing transfer. The duration bound must reflect the preparation's earlier
        // time, not understate elapsed time by only looking at transfers.
        let conn = fixture();
        insert_block(&conn, 100, 1_000); // preparation mined here — the true start of the plan
        insert_block(&conn, 150, 5_000); // transfer mined here — much later
        insert_tx(&conn, "preparation", "mined", Some(100));
        insert_tx(&conn, "transfer", "mined", Some(150));
        conn.execute(
            "INSERT INTO orchard_ironwood_migration_crossing_values (value) VALUES (42)",
            [],
        )
        .unwrap();

        let out = migration_summary(&conn);
        assert_eq!(
            out,
            vec![42, 1, 1_000, 5_000],
            "firstMinedEpochSeconds must be the preparation's earlier block time (1000), not the \
             transfer's later one (5000); transferCount stays transfer-only (1)"
        );
    }

    #[test]
    fn unmined_transactions_are_excluded_from_bounds() {
        let conn = fixture();
        insert_block(&conn, 100, 1_000);
        insert_tx(&conn, "transfer", "mined", Some(100));
        // A pending preparation with no mined_height must not affect the bounds (JOIN excludes it
        // by construction — the state filter is belt-and-suspenders).
        insert_tx(&conn, "preparation", "pending", None);
        conn.execute(
            "INSERT INTO orchard_ironwood_migration_crossing_values (value) VALUES (7)",
            [],
        )
        .unwrap();

        assert_eq!(migration_summary(&conn), vec![7, 1, 1_000, 1_000]);
    }
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

/// Cancels this account's in-progress migration for the user-facing "Restart Migration" flow, via
/// the real store-level primitive (`PoolMigrations::cancel_migration`, `zcash_client_sqlite` PR
/// #2926 — landed upstream 2026-08-05, ahead of our 2026-08-07 `librustzcash` repin). Releases
/// every never-broadcast transaction's note reservation (so the notes return to DEFAULT note
/// selection immediately, not at lock expiry), then moves the record to the terminal `Cancelled`
/// status — in that order, in one database transaction, so a crash between the two leaves a
/// still-pending migration that a retried call finishes.
///
/// Supersedes this function's earlier manual "status-only swap to `Failed`" implementation — the
/// accepted residual from when the engine had no real cancel primitive. That older version left
/// `getMigrationStateNative` reporting `RequiresAttention` (as after any failure) rather than
/// `NotStarted`, since the record stayed stored under a non-`Cancelled` status. This version's
/// `Cancelled` status is NOT one `derive_migration_state`'s `is_terminal()` match arms names
/// explicitly — falling through its `unreachable!()` would be a real bug — but `get_migration()`
/// itself reports `None` once cancelled (verified in PR #2926's own end-to-end test:
/// `get_migration` reports `None` while `latest_migration` still reads back `Cancelled` for
/// history), so `derive_migration_state`'s `let Some(state) = persisted else { return NotStarted }`
/// short-circuits before ever reaching that match — the same clean `NotStarted` a subsequent
/// propose/commit call plans fresh over the full released balance from, matching what this app's
/// own `RestartMigrationUseCase` doc has always promised ("the home banner returns to a clean
/// 'Migrate now'") but which the old implementation didn't actually deliver.
///
/// Calling with no pending migration performs only the REPAIR half: releasing a stranded lock on
/// the latest retained record (e.g. one an older client left `Failed`) without rewriting its
/// status — see `PoolMigrations::cancel_migration`'s own doc. Also works WITHOUT deserializing the
/// migration state, and is honest about what it cannot undo: an already-broadcast transaction may
/// still mine (the returned `CancelOutcome` reports `in_flight`/`mined` rather than refusing — not
/// yet surfaced to Kotlin by this entry point, which only reports whether anything was released).
///
/// Returns 1 if any note reservation was released (an in-progress run was cancelled, or a stranded
/// terminal-record lock was repaired), 0 if there was nothing to release.
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let outcome = backend
            .cancel_migration()
            .map_err(|e| anyhow!("Error cancelling migration: {}", e))?;
        Ok(
            if outcome.released().is_empty()
                && outcome.in_flight().is_empty()
                && outcome.mined().is_empty()
            {
                0
            } else {
                1
            },
        )
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                match next_broadcastable(&state, DuenessTargets::at(tip)).and_then(|id| {
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
            proposal_handle as u64,
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) = engine::build_preparation_unsigned(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                    ReplanThreshold::DEFAULT,
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
            let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
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
            let mut backend =
                Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
            backend
                .replace_migration(&state)
                .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
        }
        // Resolve the deferred witness/anchor and prove before extraction — without this,
        // `extractBroadcastTxNative` fails with `OrchardParse(MissingAnchor)` on the
        // merely-signed-but-unproven bytes just applied above (confirmed live).
        let (proven_pczt, txid) =
            finalize_note_split(&mut wallet, account, &mut store_conn, &mut state, split_id)?;

        let id = encode_transfer_id(split_id);
        let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
        let pczt_bytes = crate::utils::rust_bytes_to_java(env, &proven_pczt)?;
        Ok(env
            .new_object(
                JNI_PREPARED_TRANSFER,
                "(J[B[B)V",
                &[
                    JValue::Long(id),
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
            proposal_handle as u64,
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) = engine::build_preparation_unsigned(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                    ReplanThreshold::DEFAULT,
                )
                .map_err(|e| anyhow!("Error building unsigned migration PCZTs: {:?}", e))?;
                Ok((
                    state,
                    unsigned.into_iter().map(|tx| tx.into_parts()).collect(),
                ))
            },
        )?;
        let transfer_ids: std::collections::HashSet<MigrationTransferId> = state
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
                let pczt_bytes = crate::utils::rust_bytes_to_java(env, &pczt_bytes)?;
                env.new_object(
                    JNI_UNSIGNED_TRANSFER_PCZT,
                    "(J[B)V",
                    &[
                        JValue::Long(encode_transfer_id(id)),
                        JValue::Object(&pczt_bytes),
                    ],
                )
            },
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// EVERY preparation transaction's unsigned PCZT — the WHOLE note-split tree, not just the first
/// layer-0 split. The engine builds all layers at commit (`build_preparation_layers` resolves a
/// layer's spends from the previous layer's just-built outputs — sign-now/prove-later covers the
/// entire tree), so one external-signer ceremony can pre-sign everything; this surface exists
/// because `createUnsignedNoteSplitPcztNative` deliberately returns only the FIRST layer-0 split
/// (the immediate-broadcast special case) and `createUnsignedTransferPcztsNative` only transfers —
/// without this, every other preparation stayed `AwaitingSignature` forever (found 2026-07-30).
///
/// Per entry: `(id, layer, index, pczt_bytes)`, ZIP32-derivation-annotated for Keystone, in the
/// engine's commit (id) order. Signed results go back through the kind-agnostic
/// `storeSignedSchedulePcztsNative` (`apply_signature` per id). Same opaque-handle contract as the
/// transfer call: builds the cached plan `proposal_handle` identifies, or reuses the committed one.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_createUnsignedPreparationPcztsNative<
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
        let target = target_height(&wallet)?;
        let (state, unsigned) = commit_or_reuse(
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
            proposal_handle as u64,
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) = engine::build_preparation_unsigned(
                    network,
                    target,
                    backend,
                    migration_plan,
                    rng,
                    ReplanThreshold::DEFAULT,
                )
                .map_err(|e| anyhow!("Error building unsigned migration PCZTs: {:?}", e))?;
                Ok((
                    state,
                    unsigned.into_iter().map(|tx| tx.into_parts()).collect(),
                ))
            },
        )?;
        let prep_kinds: std::collections::HashMap<MigrationTransferId, (usize, usize)> = state
            .transactions()
            .iter()
            .filter_map(|t| match t.kind() {
                MigrationTxKind::Preparation { layer, index } => Some((t.id(), (layer, index))),
                MigrationTxKind::Transfer { .. } => None,
            })
            .collect();
        let (seed_fingerprint, account_index) = account_zip32_derivation(&wallet, account)?;
        let preps: Vec<_> = unsigned
            .into_iter()
            .filter_map(|(id, pczt_bytes)| {
                prep_kinds
                    .get(&id)
                    .map(|(layer, index)| (id, *layer, *index, pczt_bytes))
            })
            .map(|(id, layer, index, pczt_bytes)| {
                let pczt_bytes = crate::migration_keystone::annotate_spend_zip32_derivation(
                    &pczt_bytes,
                    seed_fingerprint,
                    network.coin_type(),
                    account_index,
                )
                .map_err(|e| anyhow!("Error annotating preparation PCZT derivation: {:?}", e))?;
                Ok::<_, anyhow::Error>((id, layer, index, pczt_bytes))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok(crate::utils::rust_vec_to_java(
            env,
            preps,
            JNI_UNSIGNED_PREPARATION_PCZT,
            |env, (id, layer, index, pczt_bytes)| {
                let pczt_bytes = crate::utils::rust_bytes_to_java(env, &pczt_bytes)?;
                env.new_object(
                    JNI_UNSIGNED_PREPARATION_PCZT,
                    "(JII[B)V",
                    &[
                        JValue::Long(encode_transfer_id(id)),
                        JValue::Int(layer as jint),
                        JValue::Int(index as jint),
                        JValue::Object(&pczt_bytes),
                    ],
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
    ids: JLongArray<'local>,
    pczt_bytes_list: JObjectArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let count = env.get_array_length(&ids)?;
        // A `long[]` is read as a region rather than element-by-element: the ids are primitives,
        // not objects.
        let mut raw_ids = vec![0i64; count as usize];
        env.get_long_array_region(&ids, 0, &mut raw_ids)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let mut state = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            .ok_or_else(|| anyhow!("No migration committed yet"))?;
        // Absorbs the new engine's per-transaction `apply_signature` into the old batch-shaped
        // call Kotlin still makes — see module doc point about the signed-PCZT return path.
        for i in 0..count {
            let id = decode_transfer_id(raw_ids[i as usize])?;
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
                "([B[[B)V".to_string(),
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
            let mut backend = Backend::new(&wallet, account, Some(usk), &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::commit_preparation(
                &network,
                target,
                &mut backend,
                &migration_plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit_preparation (in-process signing — local only, no network/broadcast)")
        };
        println!(
            "{} transaction(s) committed and signed",
            state.transactions().len()
        );

        let fvk = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            backend.orchard_fvk().expect("fvk")
        };

        let ids_and_kinds: Vec<(MigrationTransferId, MigrationTxKind)> = state
            .transactions()
            .iter()
            .map(|t| (t.id(), t.kind()))
            .collect();
        let mut finalized = 0;
        let mut transient = 0;
        for (id, kind) in ids_and_kinds {
            match try_prove(
                &mut wallet,
                account,
                fvk.clone(),
                &mut state,
                id,
                kind,
                &mut store_conn,
            ) {
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
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &migration_plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
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
    ) -> anyhow::Result<MigrationCommitOutcome> {
        let (state, unsigned) = engine::build_preparation_unsigned(
            network,
            target,
            backend,
            plan,
            rng,
            ReplanThreshold::DEFAULT,
        )
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
            let mut backend_a = Backend::new(&wallet, account_a, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend_a,
                &plan_a,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit account_a's migration");
        }

        // Asking for account B's migration state goes through the exact same code path
        // (`migrationStateNative`/`commit_or_reuse` do this) — it must see nothing, since B has
        // no migration of its own. Instead it leaks A's.
        let backend_b = Backend::new(&wallet, account_b, None, &mut store_conn, network)
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
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) = engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit migration");
            state
        };
        let some_tx_id = state.transactions()[0].id();
        state.mark_broadcast(some_tx_id);

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
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

    /// Inverse of [mark_mined_reconciles_on_read]: `migrationStateUnreconciledNative`'s underlying
    /// read (plain [PoolMigrationRead::get_migration], no [read_reconciled] wrapper) must NEVER
    /// promote `Broadcast`→`Mined` or persist anything, even though the wallet already knows a
    /// mined height for the transaction and repeated reads would otherwise have every opportunity
    /// to (2026-08-07 read/write-separation design, Fable review I1 — this function is trusted by
    /// six UI/gate call sites to be mutex-free specifically because it never writes; this test is
    /// the enforcement `unused_mut` alone doesn't provide against a future edit).
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn unreconciled_read_never_persists_mark_mined() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;
        let mut state = {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) = engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit migration");
            state
        };
        let some_tx_id = state.transactions()[0].id();
        state.mark_broadcast(some_tx_id);

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
            .expect("account exists for migration store");
        backend
            .replace_migration(&state)
            .expect("persist manually-advanced state");

        // Same wallet/txid setup mark_mined_reconciles_on_read uses (a real, already-mined txid),
        // so the wallet DOES know a mined height for this transaction — read_reconciled would
        // promote it. The plain read must not.
        for attempt in 0..3 {
            let raw = backend
                .get_migration()
                .expect("read migration state")
                .expect("migration state committed");
            assert!(
                matches!(
                    raw.transactions()
                        .iter()
                        .find(|t| t.id() == some_tx_id)
                        .unwrap()
                        .state(),
                    MigrationTxState::Broadcast { .. }
                ),
                "attempt {attempt}: plain get_migration() must never promote Broadcast->Mined \
                 (that is read_reconciled's job) — a write here would silently make every UI/gate \
                 call site currently trusting this read to be mutex-free unsafe"
            );
        }
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
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
            CommitContext {
                network: &network,
                wallet: &wallet,
                account,
                store_conn: &mut store_conn,
                target,
            },
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
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("first commit");
        }

        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
            .expect("account exists for migration store");
        let mut rng = OsRng;
        let result = engine::build_preparation_unsigned(
            &network,
            target,
            &mut backend,
            &plan,
            &mut rng,
            ReplanThreshold::DEFAULT,
        );
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

        let committed_ids: Vec<MigrationTransferId> = {
            let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
            let account = first_account(&wallet);
            let (plan, tip, _handle) =
                plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
            let target = tip + 1;
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) = engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit");
            state.transactions().iter().map(|t| t.id()).collect()
            // wallet / store_conn / backend all drop here — simulates process death.
        };

        let (wallet2, mut store_conn2) = open_at(&db_path, network).expect("reopen wallet");
        let account = first_account(&wallet2);
        let backend2 = Backend::new(&wallet2, account, None, &mut store_conn2, network)
            .expect("account exists for migration store");
        let reloaded = backend2
            .get_migration()
            .expect("read migration state")
            .expect("migration state must persist across a fresh connection to the same DB file");
        let reloaded_ids: Vec<MigrationTransferId> =
            reloaded.transactions().iter().map(|t| t.id()).collect();
        assert_eq!(
            committed_ids, reloaded_ids,
            "reopening the DB connection must not lose or reorder committed migration transactions"
        );

        // This module has no in-memory store to drive `advance_migration` against, and the point
        // here is that the RELOAD is intact, not what the engine decides next; the per-transaction
        // status projection is the read-only surface that shows the reloaded plan is coherent.
        let tip2 = wallet2.chain_height().expect("chain height").expect("tip");
        let statuses = reloaded.transaction_statuses(DuenessTargets::at(tip2 + 1));
        assert_eq!(
            statuses.len(),
            reloaded_ids.len(),
            "every reloaded transaction must be accounted for in the status projection"
        );
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
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan_before,
                &mut rng,
                ReplanThreshold::DEFAULT,
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

        let backend = Backend::new(&wallet, account_b, None, &mut store_conn, network)
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
        denomination::DenominationPlan,
        engine::{
            MigrationState, MigrationStatus, MigrationTransaction, MigrationTransferId,
            MigrationTxKind, MigrationTxState,
        },
        preparation::PreparationPlan,
        scheduling::AnchorBucketInterval,
    };
    use zcash_protocol::{consensus::BlockHeight, value::Zatoshis};

    /// Builds a minimal `MigrationState` with the given transactions (all transfers, no prep).
    ///
    /// `pub(super)`: reused by the sibling `next_due_transfer_delegation_tests` module below, so
    /// its differential tests build fixtures exactly the same way as this module's rather than
    /// duplicating the builder.
    pub(super) fn make_state(
        status: MigrationStatus,
        transfers: Vec<MigrationTransaction>,
    ) -> MigrationState {
        let note_split = DenominationPlan::from_stored_parts(
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
            ReplanThreshold::DEFAULT,
        )
    }

    /// `pub(super)`: reused by `next_due_transfer_delegation_tests` (see `make_state`'s doc).
    pub(super) fn transfer(
        id: u32,
        state: MigrationTxState,
        scheduled: u32,
        expiry: u32,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTransferId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))), // anchor_boundary
            zcash_protocol::TxId::from_bytes([id as u8; 32]),
            state,
            None,
            None,
            vec![[id as u8; 32]],
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
            MigrationTransferId::new(id),
            MigrationTxKind::Preparation { layer: 0, index: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))), // anchor_boundary
            zcash_protocol::TxId::from_bytes([id as u8; 32]),
            state,
            None,
            None,
            vec![[id as u8; 32]],
            None,
        )
    }

    /// Drives the real, delegated `next_due_transfer_result` over a fresh in-memory store built
    /// from `state`, for tests that only care about the tri-state `DueTransferResult` and not the
    /// raw `(code, id)` pair `step_at` (below) reports. `pub(super)`: reused by
    /// `next_due_transfer_delegation_tests` (see `make_state`'s doc).
    pub(super) fn due_result(
        state: &mut MigrationState,
        scanned: BlockHeight,
        effective: BlockHeight,
    ) -> DueTransferResult<'_> {
        let mut store = InMemoryStore {
            state: state.clone(),
            as_of: scanned,
        };
        next_due_transfer_result(&mut store, state, scanned, effective)
            .expect("in-memory store never errors")
    }

    /// 1. Terminal migration (Failed status) yields NothingDue even with a Proved+due transfer.
    #[test]
    fn next_due_is_nothing_when_migration_terminal() {
        let tip = BlockHeight::from_u32(1000);
        // A Proved transfer that would normally be due
        let tx = transfer(0, MigrationTxState::Proved, 900, 2000);
        let mut state = make_state(MigrationStatus::Failed, vec![tx]);
        let result = due_result(&mut state, tip, tip);
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
        let mut state = make_state(MigrationStatus::InProgress, vec![tx]);
        let result = due_result(&mut state, tip, tip);
        match result {
            DueTransferResult::AwaitingProof(id) => {
                assert_eq!(
                    id,
                    MigrationTransferId::new(42),
                    "id must match the signed transfer"
                );
            }
            other => panic!(
                "expected AwaitingProof, got NothingDue: {}",
                matches!(other, DueTransferResult::NothingDue)
            ),
        }
    }

    /// 3. `estimated_tip` accelerates due-ness for BROADCAST (a Proved transfer): without an
    ///    estimate a transfer scheduled ahead of the scanned tip is not yet Ready; an estimate
    ///    reaching its scheduled height makes it Ready.
    ///
    ///    UPDATED 2026-08-05 (full delegation, Task 2): this test formerly asserted the identical
    ///    schedule/estimate gate for a SIGNED transfer's AwaitingProof — true of the old hand-rolled
    ///    filter (one `due` list, one schedule check, shared by both `Proved` and `Signed`), false
    ///    of the delegated `advance_step`: proving is intentionally DECOUPLED from the broadcast
    ///    schedule (see `prove_ready`'s doc in `zcash_pool_migration::state` — "decoupled from the
    ///    (later) broadcast schedule... lets the sync-heavy proving work happen at a sync wake-up in
    ///    a different waking session from the broadcast"). A Signed transfer becomes prove-ready as
    ///    soon as its anchor boundary settles at the scanned tip, with no estimate needed at all —
    ///    not a regression, the decoupling is the point of proving ahead of the ZIP 374 broadcast.
    #[test]
    fn estimated_tip_accelerates_due_ness_for_broadcast_not_proof() {
        let scanned = BlockHeight::from_u32(1000);
        let scheduled = 1005u32; // scanned + 5: not yet due on the BROADCAST schedule

        // A Signed transfer whose anchor was drawn well before its (later) broadcast schedule —
        // boundary=980, so `boundary + PROVABLE_ANCHOR_DEPTH (990) < scanned_target (1001)`: the
        // checkpoint is settled — prove-ready immediately, without any estimate, regardless of
        // the broadcast schedule. `transfer()`'s own `scheduled - 10` boundary can never
        // demonstrate this: that boundary settles at exactly `scanned == scheduled`, the same
        // height broadcast due-ness needs, so it can't isolate the two. Built here with
        // `from_parts` directly instead.
        let signed_tx = MigrationTransaction::from_parts(
            MigrationTransferId::new(1),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(3000),
            Some(BlockHeight::from_u32(980)), // anchor_boundary: settled well ahead of `scheduled`
            zcash_protocol::TxId::from_bytes([1u8; 32]),
            MigrationTxState::Signed,
            None,
            None,
            vec![[1u8; 32]],
            None,
        );
        let mut signed_state = make_state(MigrationStatus::InProgress, vec![signed_tx]);
        assert!(
            matches!(
                due_result(&mut signed_state, scanned, scanned),
                DueTransferResult::AwaitingProof(_)
            ),
            "a Signed transfer's settled anchor boundary makes it prove-ready even though its \
             broadcast schedule has not arrived and no estimate was given"
        );

        // A Proved transfer at the SAME schedule still needs BROADCAST due-ness, which
        // `estimated_tip` still accelerates exactly as before.
        let proved_tx = transfer(2, MigrationTxState::Proved, scheduled, 3000);
        let mut proved_state = make_state(MigrationStatus::InProgress, vec![proved_tx]);

        // No estimate -> not due for broadcast (scanned=1000 < scheduled=1005).
        assert!(
            !matches!(
                due_result(&mut proved_state, scanned, scanned),
                DueTransferResult::Ready(_)
            ),
            "without an estimate, a Proved transfer is not offered for broadcast before its \
             scheduled height"
        );

        // With estimate=1006 (> scheduled=1005) -> Ready.
        let estimated = BlockHeight::from_u32(1006);
        assert!(
            matches!(
                due_result(&mut proved_state, scanned, estimated),
                DueTransferResult::Ready(_)
            ),
            "with estimated_tip=1006 > scheduled=1005, the Proved transfer must be offered for \
             broadcast"
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
        let mut state = make_state(MigrationStatus::InProgress, vec![prep]);

        // The preparation is Proved, due (scheduled=900 <= tip=1000), deps empty (mined), not
        // expired (expiry=2000 > scanned_target=1001).  any_overdue must return true.
        let mut store = InMemoryStore {
            state: state.clone(),
            as_of: tip,
        };
        assert!(
            any_overdue(&mut store, &mut state, tip, tip).expect("in-memory store never errors"),
            "a due Proved preparation must count as overdue (sync gate must close)"
        );

        // A due Proved preparation is served as READY too — the driving loop broadcasts
        // multi-transaction preparation layers just like transfers (kind-agnostic, matching
        // the engine's next_broadcastable; a Transfer-only filter deadlocked a live plan).
        assert!(
            matches!(
                due_result(&mut state, tip, tip),
                DueTransferResult::Ready(_)
            ),
            "next_due_transfer_result must serve due Proved preparations"
        );
    }

    /// 4. A transfer past expiry at the SCANNED tip is never Ready or AwaitingProof — its only
    ///    remaining step is a REBUILD, which `next_due_transfer_result` never reports (NothingDue).
    ///
    ///    UPDATED 2026-08-05 (full delegation, Task 2): this test formerly also asserted that a
    ///    transfer NOT expired at the scanned tip stays Ready even under a wildly optimistic
    ///    `estimated_tip` that has passed its expiry — true of the old hand-rolled filter (which
    ///    evaluated expiry only at `scanned_tip`, per its removed doc comment: "Expiry always uses
    ///    scanned_tip, never the estimate"), false of the delegated `advance_step`:
    ///    `MigrationState::next_broadcastable`'s doomed-window guard deliberately judges the
    ///    broadcast WITHHOLD at the estimate too — "what stops a wallet resumed after its broadcast
    ///    windows lapsed from broadcasting a stale, no-longer-includable transaction" (see that
    ///    function's doc in `zcash_pool_migration::state`). This withhold RECORDS NOTHING and
    ///    reverses itself once the scan catches up — the transaction is never marked dead or
    ///    offered for rebuild by it, since THOSE determinations still rest on the scanned tip alone
    ///    (verified below): only the broadcast offer is paused.
    #[test]
    fn expiry_is_evaluated_against_scanned_tip_for_rebuild_never_for_dead() {
        let scanned = BlockHeight::from_u32(1000);

        // expiry=999 means expired at scanned tip (expiry < scanned_target=1001): its only next
        // step is Rebuild, so next_due_transfer_result reports NothingDue, not Ready.
        let expired_tx = transfer(10, MigrationTxState::Proved, 900, 999);
        let mut expired_state = make_state(MigrationStatus::InProgress, vec![expired_tx]);
        assert!(
            matches!(
                due_result(&mut expired_state, scanned, scanned),
                DueTransferResult::NothingDue
            ),
            "a transfer expired at the scanned tip is never Ready or AwaitingProof"
        );

        // expiry=1500 is NOT expired at scanned (1500 >= scanned_target=1001): Ready when the
        // estimate agrees with the scanned tip.
        let valid_tx = transfer(11, MigrationTxState::Proved, 900, 1500);
        let mut valid_state = make_state(MigrationStatus::InProgress, vec![valid_tx]);
        assert!(
            matches!(
                due_result(&mut valid_state, scanned, scanned),
                DueTransferResult::Ready(_)
            ),
            "a transfer unexpired at the scanned tip is Ready when the estimate agrees with it"
        );

        // A wildly optimistic estimate the scan has not confirmed (99,999,999 past a real expiry
        // of 1500) withholds the broadcast (NothingDue from this function) rather than falsely
        // offering it — but does NOT strand the transfer as dead or rebuildable, which stays
        // pinned to the scanned tip regardless of the estimate.
        let mut optimistic_state = make_state(
            MigrationStatus::InProgress,
            vec![transfer(11, MigrationTxState::Proved, 900, 1500)],
        );
        let huge_estimate = BlockHeight::from_u32(99_999_999);
        assert!(
            matches!(
                due_result(&mut optimistic_state, scanned, huge_estimate),
                DueTransferResult::NothingDue
            ),
            "a huge, scan-unconfirmed estimate withholds (never falsely Ready-s) a transfer whose \
             real expiry the scan has not reached"
        );
    }

    // -----------------------------------------------------------------------
    // Two-tip step-selection tests (spec §3): broadcast timing runs at the ESTIMATED tip, proving
    // stays on the SCANNED tip. These now drive the engine's own `advance_migration` — the same
    // call `nextStepNative` makes — over an in-memory store, which is the shape the trait
    // documents for a backend with no wallet-level transaction records. Reuses this module's
    // `make_state`/`transfer` helpers directly rather than duplicating them.
    // -----------------------------------------------------------------------

    /// An in-memory `PoolMigrationRead`/`PoolMigrationWrite` over a single `MigrationState`.
    ///
    /// The oracle answers `Satisfiable` unconditionally: these tests are about step SELECTION —
    /// which transaction is offered, and at which of the two targets — not about the satisfiability
    /// sweep, which needs a real note/nullifier view to exercise and belongs with the wallet-backed
    /// tests.
    ///
    /// `pub(super)`: reused by `next_due_transfer_delegation_tests` (see `make_state`'s doc) to
    /// drive the real, delegated `next_due_transfer_result` (which needs a
    /// `PoolMigrationWrite<Error = EngineError>`, and `EngineError` is `anyhow::Error` — the same
    /// `Error` type this store already uses).
    pub(super) struct InMemoryStore {
        pub(super) state: MigrationState,
        pub(super) as_of: BlockHeight,
    }

    impl PoolMigrationRead for InMemoryStore {
        type Error = anyhow::Error;

        fn get_migration(&self) -> Result<Option<MigrationState>, Self::Error> {
            Ok(Some(self.state.clone()))
        }

        fn check_step_satisfiability(
            &self,
            _tx: &MigrationTransaction,
            _settle: zcash_pool_migration::satisfiability::ReorgSettleDepth,
        ) -> Result<zcash_pool_migration::satisfiability::StepSatisfiability, Self::Error> {
            Ok(
                zcash_pool_migration::satisfiability::StepSatisfiability::Satisfiable {
                    as_of_height: self.as_of,
                },
            )
        }

        fn mined_height(
            &self,
            _txid: zcash_protocol::TxId,
        ) -> Result<Option<BlockHeight>, Self::Error> {
            Ok(None)
        }
    }

    impl PoolMigrationWrite for InMemoryStore {
        fn replace_migration(&mut self, state: &MigrationState) -> Result<(), Self::Error> {
            self.state = state.clone();
            Ok(())
        }

        fn update_transaction(
            &mut self,
            _id: MigrationTransferId,
            _state: MigrationTxState,
        ) -> Result<(), Self::Error> {
            Ok(())
        }

        fn store_proved_transaction(
            &mut self,
            state: &mut MigrationState,
            proven: zcash_pool_migration::engine::ProvedTransaction,
        ) -> Result<(), Self::Error> {
            proven.apply(state);
            self.replace_migration(state)
        }
    }

    /// Drives one `advance_migration` decision, mapped to the same `(code, id)` pair the JNI
    /// surface returns.
    fn step_at(state: &MigrationState, scanned: u32, estimated: u32) -> (i64, i64) {
        let scanned = BlockHeight::from_u32(scanned);
        let mut store = InMemoryStore {
            state: state.clone(),
            as_of: scanned,
        };
        let mut st = state.clone();
        let advance = advance_migration(
            &mut store,
            &mut st,
            DuenessTargets::new(scanned, BlockHeight::from_u32(estimated)),
            &AdvanceConfig::new(SETTLE_DEPTH),
            &mut OsRng,
        )
        .expect("in-memory store never errors");
        match advance.step() {
            AdvanceStep::Prove { transactions } => {
                let first = transactions
                    .first()
                    .expect("Prove's transaction set is never empty");
                (STEP_PROVE, i64::from(u32::from(first.id())))
            }
            AdvanceStep::Broadcast { id } => (STEP_BROADCAST, i64::from(u32::from(*id))),
            AdvanceStep::Rebuild { id } => (STEP_REBUILD, i64::from(u32::from(*id))),
            AdvanceStep::Replan => (STEP_REPLAN, -1),
            AdvanceStep::Reevaluate => (STEP_REEVALUATE, -1),
            AdvanceStep::Waiting => (STEP_WAITING, -1),
            AdvanceStep::Complete => (STEP_COMPLETE, -1),
        }
    }

    #[test]
    fn broadcast_due_at_estimated_but_not_scanned_returns_broadcast() {
        // proved transfer scheduled at 1000; scanned tip behind (target 995), estimated at/after 1000.
        let st = make_state(
            MigrationStatus::InProgress,
            vec![transfer(7, MigrationTxState::Proved, 1000, 0)],
        );
        let (code, id) = step_at(&st, 995, 1001);
        assert_eq!((code, id), (STEP_BROADCAST, 7)); // engine says Broadcast on the estimated tip
    }

    #[test]
    fn broadcast_first_wins_over_a_ready_prove() {
        // one Proved+due-at-estimated transfer AND one Signed prove-ready transfer.
        let st = make_state(
            MigrationStatus::InProgress,
            vec![
                transfer(7, MigrationTxState::Proved, 1000, 0),
                transfer(4, MigrationTxState::Signed, 1000, 0), // prove-ready at scanned (boundary=990<995)
            ],
        );
        let (code, id) = step_at(&st, 995, 1001);
        assert_eq!((code, id), (STEP_BROADCAST, 7)); // spec §3.1 broadcast-first
    }

    #[test]
    fn prove_uses_scanned_tip_when_nothing_broadcastable() {
        // Signed transfer whose anchor boundary (scheduled - 10 = 980) has settled at the scanned
        // tip (995) — prove-ready — while nothing is Proved, so nothing is broadcastable.
        let st = make_state(
            MigrationStatus::InProgress,
            vec![transfer(4, MigrationTxState::Signed, 990, 0)],
        );
        let (code, id) = step_at(&st, 995, 1001);
        assert_eq!((code, id), (STEP_PROVE, 4));
    }
}

// ---------------------------------------------------------------------------
// Differential tests: old hand-rolled `next_due_transfer_result` vs. the delegated
// `advance_step`-backed version above (spec/2026-08-05-migration-engine-full-delegation-design.md
// §1). The intent is NOT blanket agreement — the whole point of delegating is that the new logic
// is more correct in specific ways the old code never checked. Ordinary cases assert agreement;
// the documented divergence is asserted explicitly and separately.
// ---------------------------------------------------------------------------
#[cfg(test)]
mod next_due_transfer_delegation_tests {
    use super::*;
    // Reuse this file's fixture-building helpers exactly as `next_due_transfer_tests` defines them
    // (made `pub(super)` there for this purpose) rather than duplicating them — `make_state`,
    // `transfer`, `InMemoryStore`, and the `due_result` driver that wraps the real, delegated
    // `next_due_transfer_result` in an in-memory store.
    use super::next_due_transfer_tests::{due_result, make_state, transfer};
    use zcash_pool_migration::{
        engine::{
            MigrationState, MigrationStatus, MigrationTransaction, MigrationTransferId,
            MigrationTxKind, MigrationTxState,
        },
        satisfiability::UnsatisfiableKind,
    };
    use zcash_protocol::consensus::BlockHeight;

    /// A minimal-diff copy of `next_due_transfer_tests::transfer` that also sets the
    /// `unsatisfiable` mark (the one field that helper hardcodes to `None`) — needed only by the
    /// divergence test below, so it is not worth widening the shared helper's signature for.
    /// Every other field is built identically to `next_due_transfer_tests::transfer`.
    fn unsatisfiable_transfer(
        id: u32,
        scheduled: u32,
        expiry: u32,
        unsatisfiable: (BlockHeight, UnsatisfiableKind),
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTransferId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32], // dummy pczt
            vec![],        // no deps
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))), // anchor_boundary
            zcash_protocol::TxId::from_bytes([id as u8; 32]),
            MigrationTxState::Proved,
            None,
            Some(unsatisfiable),
            vec![[id as u8; 32]],
            None,
        )
    }

    /// Drives the OLD hand-rolled filter (pre-delegation), preserved here only as the fixed
    /// comparison point for these differential tests — it is not called anywhere else, since the
    /// production `next_due_transfer_result` above is now the delegated version.
    fn old_next_due_transfer_result<'a>(
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
                matches!(
                    t.state(),
                    MigrationTxState::Proved | MigrationTxState::Signed
                ) && t.scheduled_height() <= effective_tip
                    && state.deps_mined(t.depends_on())
                    && !(u32::from(t.expiry_height()) != 0
                        && u32::from(t.expiry_height()) < u32::from(scanned_target))
            })
            .collect();
        due.sort_by_key(|t| t.scheduled_height());
        if let Some(tx) = due
            .iter()
            .find(|t| matches!(t.state(), MigrationTxState::Proved))
        {
            return DueTransferResult::Ready(tx);
        }
        if let Some(tx) = due
            .iter()
            .find(|t| matches!(t.state(), MigrationTxState::Signed))
        {
            return DueTransferResult::AwaitingProof(tx.id());
        }
        DueTransferResult::NothingDue
    }

    /// A due, Proved transfer: both implementations must agree it's Ready.
    #[test]
    fn agrees_on_ready_proved_transfer() {
        let tip = BlockHeight::from_u32(1000);
        let tx = transfer(7, MigrationTxState::Proved, 900, 2000);
        let mut state = make_state(MigrationStatus::InProgress, vec![tx]);

        // Each implementation's result is matched (and its borrow of `state` released) before the
        // other is called: `DueTransferResult<'a>` carries its lifetime parameter on the TYPE even
        // for variants that hold no reference, so binding both concurrently would hold `state`
        // borrowed both ways at once.
        match old_next_due_transfer_result(&state, tip, tip) {
            DueTransferResult::Ready(tx) => {
                assert_eq!(
                    tx.id(),
                    MigrationTransferId::new(7),
                    "old implementation: Ready(7)"
                )
            }
            other => panic!(
                "old implementation expected Ready, got NothingDue={}",
                matches!(other, DueTransferResult::NothingDue)
            ),
        }
        match due_result(&mut state, tip, tip) {
            DueTransferResult::Ready(tx) => assert_eq!(
                tx.id(),
                MigrationTransferId::new(7),
                "delegated implementation must agree: Ready(7)"
            ),
            other => panic!(
                "delegated implementation expected Ready, got NothingDue={}",
                matches!(other, DueTransferResult::NothingDue)
            ),
        }
    }

    /// A due, Signed (not yet proved) transfer: both must agree it's AwaitingProof.
    #[test]
    fn agrees_on_awaiting_proof_signed_transfer() {
        // scheduled == 0 (already due at any tip, and prove-ready immediately: anchor_boundary
        // saturates to 0) keeps this fixture out of the schedule-decoupling nuance the
        // `advance_step`-backed prove queue introduces (see the `estimated_tip_accelerates_due_ness_only`
        // update above) — both implementations agree unconditionally on an always-due transfer.
        let tip = BlockHeight::from_u32(1000);
        let tx = transfer(3, MigrationTxState::Signed, 0, 0);
        let mut state = make_state(MigrationStatus::InProgress, vec![tx]);

        // See the comment in `agrees_on_ready_proved_transfer` for why each result is matched (and
        // its borrow of `state` released) before the other implementation is called.
        assert_eq!(
            match old_next_due_transfer_result(&state, tip, tip) {
                DueTransferResult::AwaitingProof(id) => Some(id),
                _ => None,
            },
            Some(MigrationTransferId::new(3)),
            "old implementation must report AwaitingProof(3)"
        );
        assert_eq!(
            match due_result(&mut state, tip, tip) {
                DueTransferResult::AwaitingProof(id) => Some(id),
                _ => None,
            },
            Some(MigrationTransferId::new(3)),
            "delegated implementation must agree: AwaitingProof(3)"
        );
    }

    /// Nothing due: both must agree NothingDue on an empty state.
    #[test]
    fn agrees_on_nothing_due() {
        let tip = BlockHeight::from_u32(1000);
        let mut state = make_state(MigrationStatus::InProgress, vec![]);

        assert!(matches!(
            old_next_due_transfer_result(&state, tip, tip),
            DueTransferResult::NothingDue
        ));
        assert!(matches!(
            due_result(&mut state, tip, tip),
            DueTransferResult::NothingDue
        ));
    }

    /// DIVERGENCE (intentional, documented on `next_due_transfer_result`'s doc comment above): a
    /// due, Proved transfer marked `unsatisfiable` (its inputs were observed spent) — the OLD
    /// hand-rolled filter never checked this at all and offers it as `Ready`; the delegated
    /// `advance_step` correctly folds it into `dead_set` and withholds it (its only possible next
    /// step, with no other transaction in the plan, is `Replan`, which maps to `NothingDue`). This
    /// is NOT a regression: the old code was wrong here, and this test pins the fix, not agreement.
    #[test]
    fn new_delegated_version_withholds_unsatisfiable_transfer_old_code_did_not_check() {
        let tip = BlockHeight::from_u32(1000);
        let tx = unsatisfiable_transfer(
            13,
            900,
            2000,
            (BlockHeight::from_u32(999), UnsatisfiableKind::InputsSpent),
        );
        let mut state = make_state(MigrationStatus::InProgress, vec![tx]);

        let old_result = old_next_due_transfer_result(&state, tip, tip);
        assert!(
            matches!(old_result, DueTransferResult::Ready(_)),
            "documenting the bug: the old filter offers an unsatisfiable transfer as Ready"
        );

        let new_result = due_result(&mut state, tip, tip);
        assert!(
            matches!(new_result, DueTransferResult::NothingDue),
            "the fix: advance_step's dead_set withholds an unsatisfiable transfer entirely"
        );
    }
}

// ---------------------------------------------------------------------------
// Peek-ahead encoding tests: `advance_step`'s `(code, id, next_height, next_kind)` 4-tuple
// against a ground-truth `advance_migration` call on an equivalent state. Every OTHER call site
// in this file discards `next_height`/`next_kind` (bound to `_next_height, _next_kind`), so
// nothing previously caught a sign-flip or index-swap in the hand-written `StepKind -> STEP_*`
// match arms `advance_step` adds on top of `Advance::next()` (new on this pin, PR #2936).
// ---------------------------------------------------------------------------
#[cfg(test)]
mod advance_step_peek_tests {
    use super::next_due_transfer_tests::{InMemoryStore, make_state, transfer};
    use super::*;
    use zcash_pool_migration::engine::MigrationStatus;

    /// Two due `Signed` transfers so the engine's own outlook, after acting on the first,
    /// genuinely names a second, non-`None` execution point — exercising every field of the
    /// 4-tuple, not just the sentinel (-1, -1) case.
    #[test]
    fn advance_step_encodes_next_height_and_kind_matching_advance_migration() {
        let tip = BlockHeight::from_u32(1000);
        let tx1 = transfer(1, MigrationTxState::Signed, 900, 5000);
        let tx2 = transfer(2, MigrationTxState::Signed, 1200, 5000);
        let state = make_state(MigrationStatus::InProgress, vec![tx1, tx2]);
        let targets = DuenessTargets::new(tip + 1, tip + 1);

        // Ground truth: call advance_migration directly on its own clone of the fixture.
        let mut store_a = InMemoryStore {
            state: state.clone(),
            as_of: tip,
        };
        let mut state_a = state.clone();
        let advance = advance_migration(
            &mut store_a,
            &mut state_a,
            targets,
            &AdvanceConfig::new(SETTLE_DEPTH),
            &mut OsRng,
        )
        .expect("in-memory store never errors");
        let expected_next = advance.next();
        // Sanity: this fixture is only useful if the engine actually reports SOMETHING —
        // otherwise the test would trivially pass on a bug that always returns None/-1/-1.
        assert!(
            expected_next.is_some(),
            "fixture must produce a non-None peek to exercise the encoding"
        );

        // advance_step's own encoding, on an independent clone (advance_migration persists
        // determinations into its store — keep the two calls fully isolated).
        let mut store_b = InMemoryStore {
            state: state.clone(),
            as_of: tip,
        };
        let mut state_b = state.clone();
        let (_code, _id, next_height, next_kind) =
            advance_step(&mut store_b, &mut state_b, tip + 1, tip + 1)
                .expect("advance_step over the in-memory store");

        let (expected_height, expected_kind) = expected_next.expect("checked above");
        assert_eq!(
            next_height,
            i64::from(u32::from(expected_height)),
            "advance_step's next_height must match Advance::next()'s height"
        );
        let expected_code = match expected_kind {
            StepKind::Prove => STEP_PROVE,
            StepKind::Broadcast => STEP_BROADCAST,
            StepKind::Rebuild => STEP_REBUILD,
            StepKind::Replan => STEP_REPLAN,
            StepKind::Reevaluate => STEP_REEVALUATE,
            StepKind::Waiting => STEP_WAITING,
            StepKind::Complete => STEP_COMPLETE,
        };
        assert_eq!(
            next_kind, expected_code,
            "advance_step's next_kind must match Advance::next()'s StepKind"
        );
    }

    /// A migration with nothing pending reports no peek at all — the (-1, -1) sentinel pair, not
    /// a stray height/kind from a previous call or an uninitialized default.
    #[test]
    fn advance_step_reports_sentinel_pair_when_advance_migration_has_no_outlook() {
        let tip = BlockHeight::from_u32(1000);
        let mined = transfer(
            1,
            MigrationTxState::Mined {
                txid: zcash_protocol::TxId::from_bytes([1u8; 32]),
                height: BlockHeight::from_u32(500),
            },
            500,
            5000,
        );
        let state = make_state(MigrationStatus::Complete, vec![mined]);
        let mut store = InMemoryStore {
            state: state.clone(),
            as_of: tip,
        };
        let mut st = state.clone();
        let (code, _id, next_height, next_kind) =
            advance_step(&mut store, &mut st, tip + 1, tip + 1)
                .expect("advance_step over the in-memory store");
        assert_eq!(code, STEP_COMPLETE);
        assert_eq!(next_height, -1);
        assert_eq!(next_kind, -1);
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
        denomination::DenominationPlan, engine::MigrationStatus, preparation::PreparationPlan,
        scheduling::AnchorBucketInterval,
    };
    use zcash_protocol::value::Zatoshis;

    // Reuse the minimal-state builder from next_due_transfer_tests.
    fn make_state(status: MigrationStatus, transfers: Vec<MigrationTransaction>) -> MigrationState {
        let note_split = DenominationPlan::from_stored_parts(
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
            ReplanThreshold::DEFAULT,
        )
    }

    fn transfer(
        id: u32,
        state: MigrationTxState,
        scheduled: u32,
        expiry: u32,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTransferId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![],
            BlockHeight::from_u32(scheduled),
            BlockHeight::from_u32(expiry),
            Some(BlockHeight::from_u32(scheduled.saturating_sub(10))),
            zcash_protocol::TxId::from_bytes([id as u8; 32]),
            state,
            None,
            None,
            vec![[id as u8; 32]],
            None,
        )
    }

    const ACCOUNT: &[u8] = &[1u8; 16];

    /// Helper: simulate the full tag dispatch from `recordTransferResultNative`.
    ///
    /// - tag=1  → no-op: state returned unchanged, no invalidation write.
    /// - tag=2|3 → state set to Failed (if not already terminal) AND invalidation written
    ///   with reason-FIRST ordering, matching production code (see ordering comment
    ///   in `recordTransferResultNative`).
    ///
    /// A dispatch regression (e.g. `1 => Ok(())` removed so tag=1 falls into the `2|3` arm)
    /// will cause the `record_transfer_result_network_error_still_noop` test to fail because
    /// that test calls this helper and then asserts both that the state is NOT terminal and
    /// that `read_invalidation` returns None.
    fn apply_tag(
        conn: &Connection,
        state: MigrationState,
        result_tag: i32,
        transfer_id_str: &str,
    ) -> anyhow::Result<MigrationState> {
        match result_tag {
            1 => {
                // Tag=1 NetworkError: transient, no state mutation and no side-table write.
                Ok(state)
            }
            2 | 3 => {
                let reason = if result_tag == 2 {
                    "invalid_transfer"
                } else {
                    "transfer_expired"
                };
                let failed = if !state.is_terminal() {
                    MigrationState::from_parts(
                        MigrationStatus::Failed,
                        state.denominations().clone(),
                        state.preparation().clone(),
                        state.transactions().clone(),
                        state.anchor_bucket_interval(),
                        ReplanThreshold::DEFAULT,
                    )
                } else {
                    state
                };
                // reason-first ordering mirrors production: inert-orphan worst case is less
                // harmful than wrong-reason worst case (see comment in recordTransferResultNative).
                record_invalidation(conn, ACCOUNT, reason, Some(transfer_id_str))?;
                Ok(failed)
            }
            other => Err(anyhow::anyhow!(
                "Unknown result tag in test helper: {}",
                other
            )),
        }
    }

    // tag=2 → reason "invalid_transfer", state Failed, read back correctly.
    #[test]
    fn record_transfer_result_invalid_note_marks_migration_failed_with_reason() {
        let conn = Connection::open_in_memory().unwrap();
        let state = make_state(
            MigrationStatus::InProgress,
            vec![transfer(7, MigrationTxState::Proved, 1000, 2000)],
        );
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
        let state = make_state(
            MigrationStatus::InProgress,
            vec![transfer(3, MigrationTxState::Signed, 900, 1800)],
        );

        let failed = apply_tag(&conn, state, 3, "3").unwrap();

        assert!(failed.is_terminal());
        assert_eq!(failed.status(), MigrationStatus::Failed);

        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        let (reason, _) = inv.unwrap();
        assert_eq!(reason, "transfer_expired");
    }

    // tag=1 (NetworkError) → no side-table write, state NOT terminal.
    //
    // This test goes through `apply_tag` exactly like the tag=2/3 tests, so it exercises
    // the same dispatch path.  If `1 => Ok(state)` were removed and tag=1 fell into the
    // `2 | 3` arm, `apply_tag` would write an invalidation row AND mark the state Failed,
    // flipping both assertions below from pass to fail.
    #[test]
    fn record_transfer_result_network_error_still_noop() {
        let conn = Connection::open_in_memory().unwrap();
        let state = make_state(
            MigrationStatus::InProgress,
            vec![transfer(9, MigrationTxState::Proved, 500, 1500)],
        );
        assert!(!state.is_terminal(), "pre-condition: state is InProgress");

        let returned = apply_tag(&conn, state, 1, "9").unwrap();

        // (a) state must NOT be terminal — tag=1 is transient, migration stays alive.
        assert!(
            !returned.is_terminal(),
            "tag=1 must not mark state terminal"
        );
        assert_eq!(returned.status(), MigrationStatus::InProgress);

        // (b) no invalidation row must exist.
        let inv = read_invalidation(&conn, ACCOUNT).unwrap();
        assert!(
            inv.is_none(),
            "tag=1 must leave invalidation side table empty"
        );
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
        assert!(
            inv_b.is_none(),
            "account B must not see account A's invalidation"
        );

        record_invalidation(&conn, account_b, "transfer_expired", None).unwrap();
        let inv_a = read_invalidation(&conn, ACCOUNT).unwrap();
        let (reason_a, _) = inv_a.unwrap();
        assert_eq!(
            reason_a, "invalid_transfer",
            "account A's reason must be unchanged"
        );
    }

    /// tag=4 (AwaitingReevaluation) — the differential this task closes: a genuinely-unknown
    /// broadcast rejection must WITHHOLD the transaction (`Blocker::AwaitingReevaluation`) rather
    /// than terminally fail the whole plan, which is exactly what the old tag=2 path did for this
    /// same case (see `record_transfer_result_invalid_note_marks_migration_failed_with_reason`
    /// above — same starting state, opposite outcome). Exercises `report_broadcast_failure`
    /// directly, mirroring `recordTransferResultNative`'s `4 =>` arm (which does nothing more than
    /// this call plus a `replace_migration` persist — persistence itself is covered structurally by
    /// every other tag's test in this module).
    #[test]
    fn record_transfer_result_tag4_reports_broadcast_failure_not_terminal_fail() {
        let id = MigrationTransferId::new(11);
        let tx = transfer(11, MigrationTxState::Proved, 1000, 2000);
        let mut state = make_state(MigrationStatus::InProgress, vec![tx]);
        assert!(!state.is_terminal(), "pre-condition: state is InProgress");

        let observed_tip = BlockHeight::from_u32(1500);
        state.report_broadcast_failure(id, observed_tip);

        // The key behavioural difference from tag=2/3: status stays InProgress, NOT Failed.
        assert!(
            !state.is_terminal(),
            "tag=4 must NOT terminally fail the migration plan (that is tag=2/3's job)"
        );
        assert_eq!(state.status(), MigrationStatus::InProgress);

        // transaction_statuses reports the withheld transaction as AwaitingReevaluation.
        let statuses = state.transaction_statuses(DuenessTargets::at(observed_tip));
        let reported = statuses
            .iter()
            .find(|s| s.id() == id)
            .expect("transfer 11 must still be present in transaction_statuses");
        assert_eq!(
            reported.blocked_on(),
            Some(zcash_pool_migration::state::Blocker::AwaitingReevaluation),
            "a reported broadcast failure must surface as Blocker::AwaitingReevaluation until \
             advance_migration adjudicates it"
        );
        assert!(
            !reported.ready(),
            "a withheld transaction must not be reported ready"
        );
    }
}

/// Regression coverage for `reconcileInvalidatedTransfers`'s two on-chain passes (own-broadcast/
/// mined promotion, submit-crash probe), exercised against a real fixture wallet DB (`#[ignore]`d,
/// like the other `live_wallet_*` tests).
///
/// The third, foreign-spend-detecting pass this module used to also cover (`decide_foreign_spend`
/// and its exhaustive in-memory decision-logic tests) was deleted along with the pass itself — see
/// `reconcile_invalidated`'s doc comment for why.
#[cfg(test)]
mod reconcile_tests {
    use super::*;
    use zcash_pool_migration::engine::MigrationTxState;
    use zcash_protocol::TxId;

    // -------------------------------------------------------------------------
    // `pczt_txid` unit tests (Finding 2: the parser must have a regression lock)
    // -------------------------------------------------------------------------

    /// Negative canary: `pczt_txid` on 32 zero bytes must return `None` because the bytes are not
    /// a valid PCZT. If the PCZT schema changes and the parser silently accepts garbage, this test
    /// would still pass (it's a `None` assertion), but the doc-comment below would catch the drift.
    ///
    /// Positive path: the full extract pipeline requires a built + proven PCZT with real keys and
    /// a real blockchain anchor (the `TransactionExtractor` errors without a complete witness set).
    /// That path is validated end-to-end on the emulator (search for
    /// `reconcile_marks_proved_transfer_broadcast_when_its_txid_is_on_chain` in the fixture-gated
    /// tests below, which relies on the same `pczt_txid` codepath via pass 2 of
    /// `reconcile_invalidated`).
    #[test]
    fn pczt_txid_returns_none_for_garbage_bytes() {
        assert_eq!(
            pczt_txid(&[0u8; 32]),
            None,
            "garbage bytes must not parse as a PCZT"
        );
    }

    /// Empty slice is also not a valid PCZT.
    #[test]
    fn pczt_txid_returns_none_for_empty_bytes() {
        assert_eq!(pczt_txid(&[]), None, "empty slice must not parse as a PCZT");
    }

    // --- Fixture-backed integration test for M6 test 1 (Proved transfer whose txid is on chain →
    // Broadcast+Mined, NOT invalidated) and the full reconcile_invalidated pass ordering. Runs only
    // when MIGRATION_TEST_WALLET_DB is set, like the other live_wallet_* tests. ---
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
            .expect("wallet has at least one account")
    }

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

    /// M6 test 1: a `Proved` transfer whose extracted txid is already on chain (`get_tx_height`
    /// returns `Some`) must be reconciled to `Broadcast`+`Mined` (own crashed broadcast). We can't
    /// easily forge a `Proved` PCZT whose txid matches a real mined tx, so this test drives the
    /// pass-2 mechanism directly and asserts the transfer ends up Mined.
    ///
    /// Rather than build a real proven PCZT (which requires the full commit+prove pipeline), this
    /// asserts the state-machine contract pass 2 relies on: `mark_broadcast`+`mark_mined` on a
    /// transaction promote it out of the `Signed`/`Proved` states (the same predicate the now-deleted
    /// pass 3 used to build its candidate set from), so a reconciled own-broadcast can never be
    /// mistaken for a still-outstanding transfer.
    #[test]
    #[ignore = "requires MIGRATION_TEST_WALLET_DB"]
    fn reconcile_marks_proved_transfer_broadcast_when_its_txid_is_on_chain() {
        let db_path = fresh_test_db_copy(&fixture_db_path());
        let network = Network::TestNetwork;
        let (wallet, mut store_conn) = open_at(&db_path, network).expect("open wallet");
        let account = first_account(&wallet);

        let (plan, tip, _handle) =
            plan_for(&network, &wallet, account, &mut store_conn).expect("plan_for");
        let target = tip + 1;
        let mut state = {
            let mut backend = Backend::new(&wallet, account, None, &mut store_conn, network)
                .expect("account exists for migration store");
            let mut rng = OsRng;
            let (state, _unsigned) = engine::build_preparation_unsigned(
                &network,
                target,
                &mut backend,
                &plan,
                &mut rng,
                ReplanThreshold::DEFAULT,
            )
            .expect("commit migration");
            state
        };
        let some_tx_id = state.transactions()[0].id();
        let mined_txid = a_mined_txid_in_fixture(&store_conn);
        let mined_height = wallet
            .get_tx_height(mined_txid)
            .expect("get_tx_height")
            .expect("fixture txid is mined");

        // Simulate pass 2's promotion of an own crashed broadcast.
        state.mark_broadcast(some_tx_id);
        state.mark_mined(some_tx_id, mined_height);

        // The now-Mined transfer must no longer match the Signed|Proved "still outstanding" filter.
        let still_outstanding: Vec<_> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.kind(), MigrationTxKind::Transfer { .. })
                    && matches!(
                        t.state(),
                        MigrationTxState::Signed | MigrationTxState::Proved
                    )
            })
            .map(|t| t.id())
            .collect();
        assert!(
            !still_outstanding.contains(&some_tx_id),
            "a transfer reconciled to Mined must no longer be Signed|Proved"
        );
    }
}

#[cfg(test)]
mod preparation_schedule_entries_tests {
    use super::*;
    use zcash_pool_migration::preparation::{
        PrepInput, PrepOutput, PrepTransaction, PreparationPlan,
    };
    use zcash_protocol::consensus::BlockHeight;
    use zcash_protocol::value::Zatoshis;

    /// Build a [`MigrationPlan`]-shaped fixture purely from [`PreparationPlan`] parts (no wallet,
    /// no network, no prover): 3 layers, L0 = 2 transactions, L1 = 1, L2 = 1, with the dependency
    /// structure described in the task-1 brief.
    ///
    /// Dependency structure:
    ///  - id 0: L0/0 — spends a wallet note (no Prior deps)
    ///  - id 1: L0/1 — spends a wallet note (no Prior deps)
    ///  - id 2: L1/0 — spends outputs of L0/0 and L0/1 (Prior deps → ids 0 and 1)
    ///  - id 3: L2/0 — spends the output of L1/0 (Prior dep → id 2)
    fn fixture_preparation_plan() -> (PreparationPlan, Vec<Vec<BlockHeight>>) {
        let dummy_value = Zatoshis::const_from_u64(100_000);

        // Layer 0 tx 0: one wallet input → one funding output
        let l0_tx0 = PrepTransaction::from_parts(
            vec![PrepInput::Wallet {
                index: 0,
                value: dummy_value,
            }],
            vec![PrepOutput::Funding(dummy_value)],
        );
        // Layer 0 tx 1: one wallet input → one funding output
        let l0_tx1 = PrepTransaction::from_parts(
            vec![PrepInput::Wallet {
                index: 1,
                value: dummy_value,
            }],
            vec![PrepOutput::Funding(dummy_value)],
        );
        // Layer 1 tx 0: spends outputs of L0/0 AND L0/1 → one funding output
        // (two Prior inputs referencing layer=0, transaction=0 and layer=0, transaction=1)
        let l1_tx0 = PrepTransaction::from_parts(
            vec![
                PrepInput::Prior {
                    layer: 0,
                    transaction: 0,
                    output: 0,
                    value: dummy_value,
                },
                PrepInput::Prior {
                    layer: 0,
                    transaction: 1,
                    output: 0,
                    value: dummy_value,
                },
            ],
            vec![PrepOutput::Funding(dummy_value)],
        );
        // Layer 2 tx 0: spends output of L1/0 → one funding output
        let l2_tx0 = PrepTransaction::from_parts(
            vec![PrepInput::Prior {
                layer: 1,
                transaction: 0,
                output: 0,
                value: dummy_value,
            }],
            vec![PrepOutput::Funding(dummy_value)],
        );

        let preparation = PreparationPlan::from_parts(
            vec![vec![l0_tx0, l0_tx1], vec![l1_tx0], vec![l2_tx0]],
            vec![],
        );

        // Broadcast heights: three layers, each with the right number of entries.
        // Heights chosen so each layer is clearly distinguishable in assertions.
        let prep_schedule = vec![
            vec![BlockHeight::from_u32(1000), BlockHeight::from_u32(1100)], // L0: 2 txs
            vec![BlockHeight::from_u32(1200)],                              // L1: 1 tx
            vec![BlockHeight::from_u32(1300)],                              // L2: 1 tx
        ];

        (preparation, prep_schedule)
    }

    /// The pure helper covers all layers and assigns the correct ids, kinds, heights, and deps.
    #[test]
    fn preparation_schedule_entries_cover_all_layers() {
        let (preparation, prep_schedule) = fixture_preparation_plan();
        let entries = preparation_schedule_entries(&preparation, &prep_schedule);

        // 4 preparation transactions total (2 + 1 + 1).
        assert_eq!(entries.len(), 4);

        // id 0: layer 0, index 0, no deps
        assert_eq!(entries[0].id, 0);
        assert_eq!((entries[0].layer, entries[0].index), (0, 0));
        assert_eq!(entries[0].broadcast_height, prep_schedule[0][0]);
        assert!(entries[0].depends_on.is_empty(), "L0/0 has no deps");

        // id 1: layer 0, index 1, no deps
        assert_eq!(entries[1].id, 1);
        assert_eq!((entries[1].layer, entries[1].index), (0, 1));
        assert_eq!(entries[1].broadcast_height, prep_schedule[0][1]);
        assert!(entries[1].depends_on.is_empty(), "L0/1 has no deps");

        // id 2: layer 1, index 0 — depends on both L0 txs (ids 0 and 1)
        assert_eq!(entries[2].id, 2);
        assert_eq!((entries[2].layer, entries[2].index), (1, 0));
        assert_eq!(entries[2].broadcast_height, prep_schedule[1][0]);
        assert_eq!(entries[2].depends_on, vec![0, 1]);

        // id 3: layer 2, index 0 — depends on L1/0 (id 2)
        assert_eq!(entries[3].id, 3);
        assert_eq!((entries[3].layer, entries[3].index), (2, 0));
        assert_eq!(entries[3].broadcast_height, prep_schedule[2][0]);
        assert_eq!(entries[3].depends_on, vec![2]);
    }

    /// An empty preparation plan (no layers) yields an empty entry list.
    #[test]
    fn empty_preparation_yields_no_entries() {
        let preparation = PreparationPlan::from_parts(vec![], vec![]);
        let entries = preparation_schedule_entries(&preparation, &[]);
        assert!(entries.is_empty());
    }

    /// A single-layer, single-transaction plan with no Prior deps yields one entry with empty deps.
    #[test]
    fn single_layer_single_tx_no_deps() {
        let prep_tx = PrepTransaction::from_parts(
            vec![PrepInput::Wallet {
                index: 0,
                value: Zatoshis::const_from_u64(50_000),
            }],
            vec![PrepOutput::Funding(Zatoshis::const_from_u64(50_000))],
        );
        let preparation = PreparationPlan::from_parts(vec![vec![prep_tx]], vec![]);
        let prep_schedule = vec![vec![BlockHeight::from_u32(500)]];
        let entries = preparation_schedule_entries(&preparation, &prep_schedule);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, 0);
        assert_eq!((entries[0].layer, entries[0].index), (0, 0));
        assert_eq!(entries[0].broadcast_height, BlockHeight::from_u32(500));
        assert!(entries[0].depends_on.is_empty());
    }
}

/// Deterministic exercise of the LATE-DEPENDENCY anchor scenario that once crashed
/// `finalizeReadyTransfers` in a live migration, driven entirely through the
/// `zcash_pool_migration` engine's public state surface (`MigrationState::from_parts`,
/// `MigrationTransaction::from_parts`, `mark_broadcast`, `mark_mined`) — no wallet DB, no real
/// crypto, so it runs in a plain `cargo test`. The relevant logic is the prove-SELECTION filter
/// (`is_prove_ready` / the `finalizeReadyTransfers` filter) and the error CLASSIFICATION
/// (`try_prove`), so a state-level flow simulation exercises it exactly.
///
/// The historical live crash:
/// ```text
/// Error proving transfer MigrationTransferId(8): ... proving the transfer failed:
/// commitment-tree query failed: Query(NotContained(Address { level: Level(0), index: 242174 }))
/// ```
/// Root cause verified against the wallet DB: transfer tx8 committed `anchor_boundary = 4220724`
/// and depends on preparation `id1`; `id1` was mined LATE at height 4220802 (deferred at commit +
/// a foreground stall), i.e. AFTER tx8's anchor. So `id1`'s output note — the note that funds tx8 —
/// is NOT in the commitment tree at tx8's anchor 4220724.
///
/// CURRENT CONTRACT (rc.6): the cure lives upstream. `engine::prove_transfer` re-draws a late
/// transfer's boundary at prove time to cover its funding note's real mined height, so a late
/// transfer is now correctly OFFERED as prove-ready (it must enter the prove batch to be healed).
/// These tests therefore assert that `is_prove_ready` INCLUDES a late-dependency transfer once its
/// boundary has settled, and that `try_prove` still classifies the tree-query miss as transient (a
/// defence-in-depth backstop, so a residual miss defers instead of crashing the batch).
#[cfg(test)]
mod late_dependency_anchor_tests {
    use super::*;
    use incrementalmerkletree::{Address, Level};
    use shardtree::error::{QueryError, ShardTreeError};
    use zcash_pool_migration::denomination::DenominationPlan;
    use zcash_pool_migration::engine::MigrationStatus;
    use zcash_pool_migration::preparation::PreparationPlan;
    use zcash_pool_migration::scheduling::AnchorBucketInterval;
    use zcash_pool_migration::state::Blocker;
    use zcash_protocol::value::Zatoshis;

    // The exact live heights, so the scenario the test encodes is the one the wallet hit.
    pub(super) const ANCHOR: u32 = 4_220_724; // tx8's committed anchor_boundary
    pub(super) const LATE_MINED: u32 = 4_220_802; // id1 actually mined here — AFTER the anchor (the bug)
    pub(super) const ON_TIME_MINED: u32 = 4_220_700; // a well-behaved dep mines at-or-before the anchor
    pub(super) const TARGET: u32 = 4_221_000; // chain_tip + 1, well past the anchor so it has settled

    // --- minimal committed-state builder ---

    pub(super) fn note_split() -> DenominationPlan {
        DenominationPlan::from_stored_parts(
            vec![Zatoshis::const_from_u64(100_000_000)],
            Zatoshis::const_from_u64(5_000),
            None,
            Zatoshis::const_from_u64(10_000),
            Zatoshis::const_from_u64(100_010_000),
            Zatoshis::const_from_u64(100_000_000),
        )
        .expect("valid note split plan")
    }

    /// A preparation transaction (no anchor_boundary; it funds the transfer once mined).
    pub(super) fn preparation(id: u32, state: MigrationTxState) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTransferId::new(id),
            MigrationTxKind::Preparation { layer: 0, index: 0 },
            vec![0u8; 32],
            vec![],
            BlockHeight::from_u32(ANCHOR - 100),
            BlockHeight::from_u32(0), // never expires
            None,
            zcash_protocol::TxId::from_bytes([id as u8; 32]), // preparation carries no drawn boundary
            state,
            None,
            None,
            vec![[id as u8; 32]],
            None,
        )
    }

    /// A transfer with a drawn `anchor_boundary` that `depends_on` the given preparation ids.
    pub(super) fn transfer(
        id: u32,
        state: MigrationTxState,
        anchor: u32,
        depends_on: Vec<u32>,
    ) -> MigrationTransaction {
        MigrationTransaction::from_parts(
            MigrationTransferId::new(id),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            depends_on
                .into_iter()
                .map(MigrationTransferId::new)
                .collect(),
            BlockHeight::from_u32(anchor + 50), // broadcast after the anchor settles
            BlockHeight::from_u32(0),           // never expires (isolate the anchor logic)
            Some(BlockHeight::from_u32(anchor)),
            zcash_protocol::TxId::from_bytes([id as u8; 32]),
            state,
            None,
            None,
            vec![[id as u8; 32]],
            None,
        )
    }

    pub(super) fn state_with(transactions: Vec<MigrationTransaction>) -> MigrationState {
        MigrationState::from_parts(
            MigrationStatus::InProgress,
            note_split(),
            PreparationPlan::from_parts(vec![], vec![]),
            transactions,
            AnchorBucketInterval::ZIP_318,
            ReplanThreshold::DEFAULT,
        )
    }

    pub(super) fn tip1() -> BlockHeight {
        BlockHeight::from_u32(TARGET)
    }

    pub(super) fn find(state: &MigrationState, id: u32) -> &MigrationTransaction {
        state
            .transactions()
            .iter()
            .find(|t| t.id() == MigrationTransferId::new(id))
            .expect("transaction present")
    }

    // --- flow-level: is_prove_ready, driven by advancing mined heights ---

    /// ON-TIME dependency: the prep mines at-or-before the transfer's anchor, so its funding note
    /// IS in the tree at the anchor. `is_prove_ready` must stay TRUE. (Guards against the fix being
    /// too aggressive and deferring provable transfers.)
    #[test]
    fn on_time_dependency_stays_prove_ready() {
        // Start committed: prep Signed, transfer Signed.
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        // Advance the flow: broadcast then mine the prep ON TIME (<= anchor).
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(ON_TIME_MINED),
        );

        let t8 = find(&state, 8);
        assert!(
            is_prove_ready(&state, t8, tip1()),
            "a transfer whose funding dependency mined at-or-before its anchor is provable"
        );
    }

    /// LATE dependency: the prep mines AFTER the transfer's anchor. Its funding note is absent from
    /// the tree at the DRAWN anchor — but as of rc.6 that is healed at prove time by
    /// `engine::prove_transfer`, which re-draws the boundary to cover the note's real mined height.
    /// So `is_prove_ready` MUST be TRUE (boundary settled, deps mined): the transfer has to enter the
    /// prove batch to reach the heal. (Before the guard was removed this asserted FALSE — the local
    /// stopgap withheld it, which re-stranded it forever once `advance_migration` stopped offering a
    /// separate reschedule step.)
    #[test]
    fn late_dependency_is_now_prove_ready() {
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        // Advance the flow: the prep is broadcast/mined LATE (mined_height > anchor).
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(LATE_MINED),
        );

        let t8 = find(&state, 8);
        assert!(
            is_prove_ready(&state, t8, tip1()),
            "a late-dependency transfer with a settled boundary must be offered as prove-ready so \
             it enters the prove batch, where engine::prove_transfer re-draws its boundary and \
             heals it (rc.6); withholding it here would strand it forever"
        );
    }

    /// finalize-level: the late-dep transfer must be INCLUDED in the prove-ready set that
    /// `finalizeReadyTransfers` collects, so the batch attempts the prove and `engine::prove_transfer`
    /// gets the chance to re-draw the boundary and heal it. This mirrors that exact filter.
    #[test]
    fn late_dependency_included_in_finalize_ready_set() {
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(LATE_MINED),
        );

        let target = tip1();
        let ready: Vec<MigrationTransferId> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.state(), MigrationTxState::Signed) && is_prove_ready(&state, t, target)
            })
            .map(|t| t.id())
            .collect();

        assert!(
            ready.contains(&MigrationTransferId::new(8)),
            "the late-dependency transfer must enter the prove batch so prove_transfer can heal it"
        );
    }

    /// Sanity: with the SAME late dep, an anchor already AT-OR-AFTER the dep's mined height is
    /// provable with no redraw needed — confirming it is the anchor-vs-mined ordering that governs
    /// tree membership, and that a covering boundary (exactly what `prove_transfer`'s redraw produces)
    /// is prove-ready.
    #[test]
    fn later_anchor_covering_the_late_dep_is_prove_ready() {
        let covering_anchor = LATE_MINED + 10; // anchor now AFTER the dep's mined height
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, covering_anchor, vec![1]),
        ]);
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(LATE_MINED),
        );

        let t8 = find(&state, 8);
        assert!(
            is_prove_ready(&state, t8, BlockHeight::from_u32(covering_anchor + 100)),
            "if the anchor is at-or-after the dependency's mined height, the note IS in the tree \
             and the transfer is provable"
        );
    }

    // --- differential: is_prove_ready agrees with transaction_statuses (task 3 delegation) ---

    /// The ordinary case: deps mined, boundary settled. The OLD hand-rolled `is_prove_ready` and
    /// the engine's own `transaction_statuses` classification must agree — both TRUE, with the
    /// engine additionally reporting `NextAction::Prove`.
    #[test]
    fn is_prove_ready_agrees_with_transaction_statuses_for_ordinary_signed_transfer() {
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(ON_TIME_MINED),
        );

        let target = tip1();
        let t8 = find(&state, 8);
        assert!(is_prove_ready(&state, t8, target));

        let statuses = state.transaction_statuses(DuenessTargets::at(target));
        let status = statuses
            .iter()
            .find(|s| s.id() == MigrationTransferId::new(8))
            .expect("transfer 8 present in transaction_statuses");
        assert!(status.ready());
        assert_eq!(status.action(), Some(NextAction::Prove));
    }

    /// The boundary has not yet settled (`boundary + 1 >= target_height`). Both must agree FALSE;
    /// the engine additionally reports `Blocker::AnchorBoundary`.
    #[test]
    fn is_prove_ready_agrees_when_boundary_not_settled() {
        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(ON_TIME_MINED),
        );

        // target_height == ANCHOR means boundary + 1 (ANCHOR + 1) >= target_height: not settled.
        let target = BlockHeight::from_u32(ANCHOR);
        let t8 = find(&state, 8);
        assert!(!is_prove_ready(&state, t8, target));

        let statuses = state.transaction_statuses(DuenessTargets::at(target));
        let status = statuses
            .iter()
            .find(|s| s.id() == MigrationTransferId::new(8))
            .expect("transfer 8 present in transaction_statuses");
        assert!(!status.ready());
        assert_eq!(status.blocked_on(), Some(Blocker::AnchorBoundary));
    }

    /// The funding dependency has not mined yet. Both must agree FALSE; the engine additionally
    /// reports `Blocker::Dependencies`.
    #[test]
    fn is_prove_ready_agrees_when_deps_not_mined() {
        let state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            transfer(8, MigrationTxState::Signed, ANCHOR, vec![1]),
        ]);
        // Preparation 1 is left Signed (never broadcast/mined): deps not mined.

        let target = tip1();
        let t8 = find(&state, 8);
        assert!(!is_prove_ready(&state, t8, target));

        let statuses = state.transaction_statuses(DuenessTargets::at(target));
        let status = statuses
            .iter()
            .find(|s| s.id() == MigrationTransferId::new(8))
            .expect("transfer 8 present in transaction_statuses");
        assert!(!status.ready());
        assert_eq!(status.blocked_on(), Some(Blocker::Dependencies));
    }

    // --- unit: try_prove's error classification treats the commitment-tree error as transient ---

    /// The commitment-tree `Query(NotContained(..))` error — the one the live crash carried — must be
    /// classified as TRANSIENT so `try_prove` returns `Ok(false)` (defer) instead of `Err` (which
    /// crashes and rolls back the whole finalize batch). Before the fix, `is_transient_prove_error`
    /// does not exist / does not include the `Tree` arm, so this is RED.
    #[test]
    fn commitment_tree_not_contained_is_transient() {
        // Reconstruct the EXACT live error value: Query(NotContained(Address{level:0, index:242174})).
        let tree_err: WalletProveError<(), (), (), ()> =
            WalletProveError::Tree(ShardTreeError::Query(QueryError::NotContained(
                Address::from_parts(Level::from(0u8), 242_174),
            )));
        assert!(
            is_transient_prove_error(&tree_err),
            "a commitment-tree query miss (NotContained) is transient: the funding note is not yet \
             in the tree at the anchor. It must defer (Ok(false)), never propagate and roll back the \
             whole prove batch"
        );
    }

    /// The three witness/anchor-resolution errors that were ALREADY transient must stay transient.
    #[test]
    fn witness_and_anchor_errors_stay_transient() {
        let unknown: WalletProveError<(), (), (), ()> = WalletProveError::UnknownSpentNote(
            orchard::note::Nullifier::from_bytes(&[0u8; 32])
                .into_option()
                .expect("valid nullifier bytes"),
        );
        let anchor: WalletProveError<(), (), (), ()> =
            WalletProveError::AnchorNotFound(BlockHeight::from_u32(ANCHOR));
        let witness: WalletProveError<(), (), (), ()> =
            WalletProveError::WitnessNotFound(BlockHeight::from_u32(ANCHOR));
        assert!(is_transient_prove_error(&unknown));
        assert!(is_transient_prove_error(&anchor));
        assert!(is_transient_prove_error(&witness));
    }

    /// A genuinely non-transient prover error (the tree unavailable) must NOT be classified as
    /// transient — it should still surface, so we don't silently swallow real failures.
    #[test]
    fn ironwood_tree_unavailable_is_not_transient() {
        let err: WalletProveError<(), (), (), ()> = WalletProveError::IronwoodTreeUnavailable;
        assert!(
            !is_transient_prove_error(&err),
            "a genuinely unrecoverable prover error must not be swallowed as transient"
        );
    }
}

/// RE-ANCHOR HEALING for a late-dependency-stuck transfer — now resolved UPSTREAM (rc.6).
///
/// A transfer whose funding dependency mined AFTER its committed `anchor_boundary` cannot be proven
/// against that boundary (the note is not in the commitment tree there). Healing requires
/// RE-ANCHORING: redrawing its `anchor_boundary` to a later bucket at-or-after the dependency's
/// ACTUAL mined height, so the funding note IS in the tree at the new anchor and the transfer proves
/// again.
///
/// When these tests were first written (`zcash_pool_migration` 0.1.0-rc.4) the engine exposed NO
/// non-expired redraw entry point, so backend-lib could only DEFER such a transfer via its local
/// late-dependency guard and the heal was pinned only as a hypothetical end-state constructed by
/// hand. That gap — escalated in `spec/2026-07-30-engine-change-request-unprovable-boundary.md` — is
/// now CLOSED: `engine::prove_transfer` re-validates the persisted boundary against the funding
/// note's real mined height and re-draws it to a covering grid bucket at prove time (its preferred
/// "lighter, no re-sign" option), keeping the pre-signed PCZT valid. The local guard has been
/// removed accordingly; the two tests that pinned the guard's DEFER/exclusion behavior
/// (`stuck_transfer_stays_unprovable_without_reanchor`, `reanchor_below_the_dep_mine_does_not_heal`)
/// tested a backend-lib-local mechanism that no longer exists and were deleted — the new
/// `late_dependency_anchor_tests` positively assert the transfer is now offered prove-ready, and the
/// covering-boundary end state remains pinned below.
#[cfg(test)]
mod reanchor_healing_tests {
    use super::late_dependency_anchor_tests::*;
    use super::*;

    /// THE HEALED END STATE: a transfer anchored to a boundary at-or-after the dependency's ACTUAL
    /// mined height is prove-ready — the funding note is in the tree at that anchor. This is exactly
    /// the boundary `engine::prove_transfer` now re-draws at prove time
    /// (`funding_creation = max(dep mined heights)` fed to `draw_anchor_boundary`); here we construct
    /// that end state directly (via `from_parts`) to pin the property the redraw must produce.
    #[test]
    fn reanchor_to_covering_boundary_heals_stuck_transfer() {
        let redrawn_anchor = LATE_MINED + 24; // >= the dependency's actual mined height

        let mut state = state_with(vec![
            preparation(1, MigrationTxState::Signed),
            // The re-anchored transfer: same id/kind/deps, NEW covering anchor_boundary — the state
            // prove_transfer's redraw persists for a stuck transfer.
            transfer(8, MigrationTxState::Signed, redrawn_anchor, vec![1]),
        ]);
        state.mark_broadcast(MigrationTransferId::new(1));
        state.mark_mined(
            MigrationTransferId::new(1),
            BlockHeight::from_u32(LATE_MINED),
        );

        // Chain past the redrawn boundary so it has settled.
        let target = BlockHeight::from_u32(redrawn_anchor + 100);
        let t8 = find(&state, 8);
        assert!(
            is_prove_ready(&state, t8, target),
            "after re-anchoring to a boundary >= the dependency's mined height, the funding note IS \
             in the tree at the new anchor and the transfer is prove-ready again — the end state the \
             upstream prove-time redraw delivers"
        );
    }
}

/// A `PoolMigrationRead`/`PoolMigrationWrite` store that answers every chain-fact query with the
/// healthy "nothing new to report" default (`Satisfiable`, never mined, no persisted migration) and
/// records writes nowhere. `next_step` (the pure decision the trace/synthetic tests used to call
/// directly) is `pub(crate)` in `zcash_pool_migration` — visible only WITHIN that crate, not to this
/// one's test code, in-crate `#[cfg(test)]` module or not, because the crate boundary that governs
/// `pub(crate)` is `zcash_pool_migration`'s, not this crate's. `advance_migration` is the only
/// remaining path to the same decision, and it always asks a store — this is the minimal one that
/// asks nothing the test's own `MigrationTxState` transitions don't already encode, so driving
/// `advance_migration` through it reproduces exactly what the old direct `next_step` calls exercised.
/// Modelled on `zcash_pool_migration::satisfiability::advance_tests::TestStore`'s default arm (a
/// private, in-crate-only fixture there), minus the configurable answer table this file's tests
/// don't need.
#[cfg(test)]
#[derive(Default)]
struct NoOpPoolMigrationStore;

#[cfg(test)]
impl PoolMigrationRead for NoOpPoolMigrationStore {
    type Error = EngineError;

    fn get_migration(&self) -> Result<Option<MigrationState>, Self::Error> {
        Ok(None)
    }

    /// Always `Satisfiable`: the healthy default, and the one that makes `advance_migration` take
    /// its offered candidate immediately (see its main loop) rather than deferring or marking it —
    /// exactly the pure `next_step`'s behaviour, which never consulted a store at all.
    fn check_step_satisfiability(
        &self,
        _tx: &MigrationTransaction,
        _settle: ReorgSettleDepth,
    ) -> Result<zcash_pool_migration::satisfiability::StepSatisfiability, Self::Error> {
        Ok(
            zcash_pool_migration::satisfiability::StepSatisfiability::Satisfiable {
                as_of_height: BlockHeight::from_u32(0),
            },
        )
    }

    /// Never mined: these tests drive mining themselves (`Driver::mine` / directly setting
    /// `MigrationTxState::Mined`), so the in-flight sweep this answers must find nothing new.
    fn mined_height(
        &self,
        _txid: zcash_protocol::TxId,
    ) -> Result<Option<BlockHeight>, Self::Error> {
        Ok(None)
    }
}

#[cfg(test)]
impl PoolMigrationWrite for NoOpPoolMigrationStore {
    fn replace_migration(&mut self, _state: &MigrationState) -> Result<(), Self::Error> {
        Ok(())
    }

    fn update_transaction(
        &mut self,
        _id: MigrationTransferId,
        _state: MigrationTxState,
    ) -> Result<(), Self::Error> {
        Ok(())
    }

    fn store_proved_transaction(
        &mut self,
        state: &mut MigrationState,
        proven: zcash_pool_migration::engine::ProvedTransaction,
    ) -> Result<(), Self::Error> {
        proven.apply(state);
        Ok(())
    }
}

#[cfg(test)]
mod state_machine_trace_tests {
    //! Golden execution traces over the pure `zcash_pool_migration` state machine — the contract
    //! the app's single-lane worker obeys (design:
    //! `spec/2026-07-30-engine-state-machine-adoption-design.md`). Each test replays the REAL plan
    //! shape from the 2026-07-30 live run (2 wallet notes → 4 preparations in 3 layers + 11
    //! transfers, 12-block bucket grid) against a simulated chain and asserts, height by height,
    //! exactly what `next_step` / `transaction_statuses` / `sync_wakeup_schedule` tell a consumer.
    //!
    //! The driver is FUNCTIONAL: the test owns a plain spec of every transaction plus its current
    //! `MigrationTxState`, and rebuilds the `MigrationState` via `from_parts` after every applied
    //! step — no engine-internal mutation, so the traces exercise the same read-only surface the
    //! JNI layer will.
    use std::num::NonZeroU32;

    use rand::SeedableRng;
    use rand::rngs::StdRng;
    use zcash_pool_migration::{
        denomination::DenominationPlan,
        engine::{
            MigrationState, MigrationStatus, MigrationTransaction, MigrationTransferId,
            MigrationTxKind, MigrationTxState,
        },
        preparation::PreparationPlan,
        satisfiability::{
            AdvanceConfig, DuenessTargets, ReorgSettleDepth, ReplanThreshold, advance_migration,
        },
        scheduling::{AnchorBucketInterval, WakeupParams},
        state::{AdvanceStep, Blocker},
    };
    use zcash_protocol::consensus::BlockHeight;

    use super::NoOpPoolMigrationStore;

    const EXPIRY: u32 = 4_285_440;
    const BUCKET: u32 = 12;

    /// A freshly-seeded deterministic RNG per drive call — `advance_migration`'s schedule-shift
    /// (new on this pin; see `zcash_pool_migration::satisfiability::advance_migration`'s own `rng`
    /// parameter) draws from it, and this module's traces assert an EXACT step sequence, so the
    /// draw must be reproducible. Mirrors the upstream crate's own `rng()` test helper
    /// (`zcash_pool_migration/src/satisfiability.rs` test module) exactly, including the seed.
    fn rng() -> StdRng {
        StdRng::seed_from_u64(0xA5)
    }

    /// One transaction of the plan, as the test owns it: everything `from_parts` needs except the
    /// live `MigrationTxState`, which the driver tracks separately.
    #[derive(Clone)]
    struct TxSpec {
        id: u32,
        kind: MigrationTxKind,
        deps: Vec<u32>,
        scheduled: u32,
        boundary: Option<u32>,
    }

    fn prep(id: u32, layer: usize, index: usize, deps: &[u32], scheduled: u32) -> TxSpec {
        TxSpec {
            id,
            kind: MigrationTxKind::Preparation { layer, index },
            deps: deps.to_vec(),
            scheduled,
            boundary: None,
        }
    }

    fn transfer(id: u32, crossing: usize, dep: u32, scheduled: u32, boundary: u32) -> TxSpec {
        TxSpec {
            id,
            kind: MigrationTxKind::Transfer { crossing },
            deps: vec![dep],
            scheduled,
            boundary: Some(boundary),
        }
    }

    /// The EXACT plan committed live on 2026-07-30 10:18 (referenceTip 4224538) — see
    /// `spec/tx9-investigation/db-readable.txt`. tx9 is the only transfer funded from the L2
    /// preparation (tx3); its drawn boundary (4224576) is one bucket past tx3's SCHEDULED height.
    fn live_plan() -> Vec<TxSpec> {
        vec![
            prep(0, 0, 0, &[], 4_224_541),
            prep(1, 0, 1, &[], 4_224_541),
            prep(2, 1, 0, &[0, 1], 4_224_551),
            prep(3, 2, 0, &[2], 4_224_562),
            transfer(4, 0, 0, 4_224_604, 4_224_588),
            transfer(5, 1, 0, 4_224_610, 4_224_576),
            transfer(6, 2, 0, 4_224_655, 4_224_612),
            transfer(7, 3, 0, 4_224_627, 4_224_612),
            transfer(8, 4, 1, 4_224_593, 4_224_576),
            transfer(9, 5, 3, 4_224_611, 4_224_576),
            transfer(10, 6, 0, 4_224_646, 4_224_612),
            transfer(11, 7, 0, 4_224_617, 4_224_600),
            transfer(12, 8, 0, 4_224_649, 4_224_636),
            transfer(13, 9, 0, 4_224_640, 4_224_600),
            transfer(14, 10, 0, 4_224_656, 4_224_636),
        ]
    }

    /// Drives the whole trace: rebuilds the `MigrationState` from the spec + live tx states.
    struct Driver {
        specs: Vec<TxSpec>,
        states: Vec<MigrationTxState>,
    }

    impl Driver {
        fn new(specs: Vec<TxSpec>) -> Self {
            let states = vec![MigrationTxState::Signed; specs.len()];
            Driver { specs, states }
        }

        fn idx(&self, id: u32) -> usize {
            self.specs
                .iter()
                .position(|s| s.id == id)
                .expect("known tx id")
        }

        fn set(&mut self, id: u32, state: MigrationTxState) {
            let i = self.idx(id);
            self.states[i] = state;
        }

        fn mine(&mut self, id: u32, height: u32) {
            self.set(
                id,
                MigrationTxState::Mined {
                    txid: zcash_protocol::TxId::from_bytes([id as u8; 32]),
                    height: BlockHeight::from_u32(height),
                },
            );
        }

        fn build(&self) -> MigrationState {
            let txs: Vec<MigrationTransaction> = self
                .specs
                .iter()
                .zip(self.states.iter())
                .map(|(s, st)| {
                    MigrationTransaction::from_parts(
                        MigrationTransferId::new(s.id),
                        s.kind,
                        vec![0u8; 32],
                        s.deps
                            .iter()
                            .map(|d| MigrationTransferId::new(*d))
                            .collect(),
                        BlockHeight::from_u32(s.scheduled),
                        BlockHeight::from_u32(EXPIRY),
                        s.boundary.map(BlockHeight::from_u32),
                        zcash_protocol::TxId::from_bytes([s.id as u8; 32]),
                        *st,
                        None,   // lock_owner
                        None,   // unsatisfiable
                        vec![], // spend_nullifiers
                        None,   // broadcast_failure_at
                    )
                })
                .collect();
            let note_split = DenominationPlan::from_stored_parts(
                vec![zcash_protocol::value::Zatoshis::const_from_u64(100_000_000)],
                zcash_protocol::value::Zatoshis::const_from_u64(5_000),
                None,
                zcash_protocol::value::Zatoshis::const_from_u64(10_000),
                zcash_protocol::value::Zatoshis::const_from_u64(100_010_000),
                zcash_protocol::value::Zatoshis::const_from_u64(100_000_000),
            )
            .expect("valid note split plan");
            MigrationState::from_parts(
                MigrationStatus::Committed,
                note_split,
                PreparationPlan::from_parts(vec![], vec![]),
                txs,
                AnchorBucketInterval::custom(NonZeroU32::new(BUCKET).expect("nonzero")),
                ReplanThreshold::DEFAULT,
            )
        }

        /// Drives one `advance_migration` call over a fresh `NoOpPoolMigrationStore` (see its doc:
        /// `next_step` is `pub(crate)` to `zcash_pool_migration`, not reachable from this crate's
        /// test code, so `advance_migration` — the only remaining public path to the same decision
        /// — is what every trace below actually drives). The store answers every chain-fact query
        /// with the healthy default, so it contributes nothing this test's own `MigrationTxState`
        /// transitions (`set`/`mine`, applied by the caller between calls) don't already encode —
        /// reproducing exactly what the old direct `next_step` calls exercised. Cloned out of the
        /// `Advance` (PR #2939: `.step()` now returns `&AdvanceStep`, and `Prove` — carrying the
        /// whole provable set — is no longer `Copy`).
        fn advance(&self, target: BlockHeight) -> AdvanceStep {
            let mut store = NoOpPoolMigrationStore;
            let mut state = self.build();
            advance_migration(
                &mut store,
                &mut state,
                DuenessTargets::at(target),
                &AdvanceConfig::new(ReorgSettleDepth::new(10)),
                &mut rng(),
            )
            .expect("advance_migration over the no-op store")
            .step()
            .clone()
        }

        /// Applies every step the engine emits at `tip` until it settles on Waiting/Complete/
        /// Rebuild: a batched Prove flips EVERY named transaction to Proved (PR #2939 serves the
        /// whole provable set in one step, not one at a time), Broadcast{id} to Broadcast. Returns
        /// the applied step sequence plus the settling step, both as [StepSummary] — see its doc
        /// for why (`ProveTarget`'s fields are private to `zcash_pool_migration`, so this crate's
        /// tests cannot construct an `AdvanceStep::Prove` value directly to compare against).
        /// Mirrors exactly what the app worker will do (modulo privacy timing, which never changes
        /// WHAT, only WHEN).
        fn drain(&mut self, tip: u32) -> (Vec<StepSummary>, StepSummary) {
            let target = BlockHeight::from_u32(tip + 1);
            let mut applied = Vec::new();
            loop {
                let step = self.advance(target);
                match &step {
                    AdvanceStep::Prove { transactions } => {
                        for t in transactions {
                            self.set(id_of(t.id()), MigrationTxState::Proved);
                        }
                        applied.push(summarize(&step));
                    }
                    AdvanceStep::Broadcast { id } => {
                        self.set(
                            id_of(*id),
                            MigrationTxState::Broadcast {
                                txid: zcash_protocol::TxId::from_bytes([id_of(*id) as u8; 32]),
                            },
                        );
                        applied.push(summarize(&step));
                    }
                    _ => return (applied, summarize(&step)),
                }
            }
        }
    }

    impl Driver {
        /// Like `drain`, but simulates a PROVER FAILURE for the given ids: the engine's Prove
        /// instruction is recorded but NOT applied (in reality `prove_transfer` errors with
        /// `Query(NotContained)` when the funding note is absent from the tree at the anchor).
        /// Returns on the first batch CONTAINING a vetoed id — the point where a real consumer is
        /// wedged, because asking again yields the same impossible instruction (the whole batch,
        /// not just the failing entry, since a real consumer proves the set as one unit).
        fn drain_with_failing_prover(
            &mut self,
            tip: u32,
            failing: &[u32],
        ) -> (Vec<StepSummary>, StepSummary) {
            let target = BlockHeight::from_u32(tip + 1);
            let mut applied = Vec::new();
            loop {
                let step = self.advance(target);
                match &step {
                    AdvanceStep::Prove { transactions }
                        if transactions
                            .iter()
                            .any(|t| failing.contains(&id_of(t.id()))) =>
                    {
                        return (applied, summarize(&step));
                    }
                    AdvanceStep::Prove { transactions } => {
                        for t in transactions {
                            self.set(id_of(t.id()), MigrationTxState::Proved);
                        }
                        applied.push(summarize(&step));
                    }
                    AdvanceStep::Broadcast { id } => {
                        self.set(
                            id_of(*id),
                            MigrationTxState::Broadcast {
                                txid: zcash_protocol::TxId::from_bytes([id_of(*id) as u8; 32]),
                            },
                        );
                        applied.push(summarize(&step));
                    }
                    _ => return (applied, summarize(&step)),
                }
            }
        }
    }

    fn id_of(id: MigrationTransferId) -> u32 {
        u32::from(id)
    }

    /// A comparable summary of one `AdvanceStep` — ids only. This crate's tests cannot construct
    /// an `AdvanceStep::Prove` directly to compare against with `assert_eq!` (PR #2939: `Prove`
    /// now carries `Vec<ProveTarget>`, and `ProveTarget`'s fields are private to
    /// `zcash_pool_migration` — no public constructor), so every trace below compares this
    /// hand-buildable summary instead. `Prove`'s ids are in the engine's own order
    /// (earliest-ready first, ties by id — see `AdvanceStep::Prove`'s doc), not re-sorted here.
    #[derive(Debug, Clone, PartialEq, Eq)]
    enum StepSummary {
        Prove(Vec<u32>),
        Broadcast(u32),
        Rebuild(u32),
        Replan,
        Reevaluate,
        Waiting,
        Complete,
    }

    fn summarize(step: &AdvanceStep) -> StepSummary {
        match step {
            AdvanceStep::Prove { transactions } => {
                StepSummary::Prove(transactions.iter().map(|t| id_of(t.id())).collect())
            }
            AdvanceStep::Broadcast { id } => StepSummary::Broadcast(id_of(*id)),
            AdvanceStep::Rebuild { id } => StepSummary::Rebuild(id_of(*id)),
            AdvanceStep::Replan => StepSummary::Replan,
            AdvanceStep::Reevaluate => StepSummary::Reevaluate,
            AdvanceStep::Waiting => StepSummary::Waiting,
            AdvanceStep::Complete => StepSummary::Complete,
        }
    }

    fn prove(id: u32) -> StepSummary {
        StepSummary::Prove(vec![id])
    }

    /// A single Prove step naming several transactions at once (PR #2939's batching) — ids in the
    /// order the assertion expects the engine to report them.
    fn prove_set(ids: &[u32]) -> StepSummary {
        StepSummary::Prove(ids.to_vec())
    }

    fn broadcast(id: u32) -> StepSummary {
        StepSummary::Broadcast(id)
    }

    fn blocker_of(state: &MigrationState, tip: u32, id: u32) -> Option<Blocker> {
        state
            .transaction_statuses(DuenessTargets::at(BlockHeight::from_u32(tip + 1)))
            .into_iter()
            .find(|s| id_of(s.id()) == id)
            .expect("status for id")
            .blocked_on()
    }

    /// (a)+(b) HAPPY TRACE with a late-but-in-margin L2 prep: tx3 mines at 4224566 — 4 blocks past
    /// its schedule but still BELOW tx9's boundary (4224576) — and the entire plan, tx9 included,
    /// executes to Complete. This is the end-to-end contract for the single-lane worker: the
    /// engine emits prove-first batches, then broadcasts in schedule order, and settles Waiting
    /// between events.
    ///
    /// Under librustzcash PR #2939 (`kn/batch_prove`), a Prove step now names the WHOLE set of
    /// transactions provable at once (`prove_set` below) rather than one id at a time — proving,
    /// unlike broadcasting, has no privacy implications to space out. The same pin also fixed a
    /// previously-missing anchor-stability gate: a transfer's boundary must sit at least
    /// `PROVABLE_ANCHOR_DEPTH` (10) blocks below the scanned tip before its Prove is offered, so
    /// some ticks now correctly yield nothing where the pre-#2939 behaviour this test used to
    /// document offered a proof against a boundary that had not yet settled.
    #[test]
    fn live_plan_happy_trace_completes_with_late_but_in_margin_prep() {
        let mut d = Driver::new(live_plan());

        // Layer 0 due: both preps' anchors are already settled at this tip, so PR #2939 serves
        // them as ONE Prove step naming the whole provable set (batching has no privacy
        // implications, unlike broadcasting), then each broadcasts immediately in list order.
        let (steps, settle) = d.drain(4_224_542);
        assert_eq!(steps, vec![prove_set(&[0, 1]), broadcast(0), broadcast(1)]);
        assert_eq!(settle, StepSummary::Waiting);
        d.mine(0, 4_224_544);
        d.mine(1, 4_224_546);

        // Layer 1 due once its deps mined.
        let (steps, settle) = d.drain(4_224_552);
        assert_eq!(steps, vec![prove(2), broadcast(2)]);
        assert_eq!(settle, StepSummary::Waiting);
        d.mine(2, 4_224_556);

        // Layer 2 — mines LATE (+4 past schedule) but within tx9's boundary margin.
        let (steps, _) = d.drain(4_224_563);
        assert_eq!(steps, vec![prove(3), broadcast(3)]);
        d.mine(3, 4_224_566);

        // Boundary 4224576 is not yet PROVABLE_ANCHOR_DEPTH (10 blocks) settled at this tip (PR
        // #2939 fixed a previously-missing anchor-stability gate on Prove — see the constant's
        // doc): nothing is offered yet, unlike the pre-#2939 behaviour this test used to
        // document.
        let (steps, settle) = d.drain(4_224_578);
        assert!(steps.is_empty());
        assert_eq!(settle, StepSummary::Waiting);

        // Now settled: the whole batch (5, 8, 9) proves together in one step — tx9 INCLUDED,
        // because its dependency mined at 4224566 <= 4224576. tx8's own broadcast schedule is
        // also due at this tip, so it goes out in the same call.
        let (steps, _) = d.drain(4_224_593);
        assert_eq!(steps, vec![prove_set(&[5, 8, 9]), broadcast(8)]);
        d.mine(8, 4_224_601);

        // Schedules 4604/4610/4611 broadcast — tx9 goes out. tx4 and tx11 batch-prove together
        // (both settled by this tip); tx4's own broadcast schedule is due in the same call, so it
        // follows immediately, while tx13 proves alone (its own boundary just settled) without a
        // due broadcast yet.
        let (steps, _) = d.drain(4_224_612);
        assert_eq!(
            steps,
            vec![
                broadcast(5),
                broadcast(9),
                prove_set(&[4, 11]),
                broadcast(4),
                prove(13),
            ]
        );
        d.mine(4, 4_224_616);
        d.mine(5, 4_224_616);
        d.mine(9, 4_224_616);

        // tx11 was already proved (prior batch) and its broadcast is due, so it goes out first.
        // tx6/tx7 batch-prove together; tx7 is also immediately broadcast-due so its pair stays
        // adjacent, while tx10 proves alone without a due broadcast yet.
        let (steps, _) = d.drain(4_224_638);
        assert_eq!(
            steps,
            vec![broadcast(11), prove_set(&[6, 7]), broadcast(7), prove(10),]
        );
        d.mine(11, 4_224_642);
        d.mine(7, 4_224_642);

        // The tail broadcasts; after the last mine the machine is Complete. Order is neither id
        // nor schedule order: `advance_migration`'s `shift_schedule` re-shifts the remaining
        // pending schedule by `served - scheduled` every time a step is served, redrawing
        // in-distribution anchor boundaries along with it — a genuine ZIP 318 privacy feature.
        // This exact sequence is deterministic ONLY because `rng()` above seeds a fixed RNG
        // (`StdRng::seed_from_u64(0xA5)`, mirroring `zcash_pool_migration`'s own test convention)
        // — a different seed reorders these, though never omits or duplicates one. tx12/tx14
        // batch-prove together once their boundaries settle, in the middle of the tail broadcasts.
        let (steps, _) = d.drain(4_224_660);
        assert_eq!(
            steps,
            vec![
                broadcast(13),
                broadcast(10),
                broadcast(6),
                prove_set(&[12, 14]),
                broadcast(12),
                broadcast(14),
            ]
        );
        for id in [13, 10, 12, 6, 14] {
            d.mine(id, 4_224_670);
        }
        let (steps, settle) = d.drain(4_224_680);
        assert!(steps.is_empty());
        assert_eq!(settle, StepSummary::Complete);

        // Nothing left to wake for.
        let mut rng = <rand::rngs::StdRng as rand::SeedableRng>::seed_from_u64(7);
        let wakeups = d
            .build()
            .sync_wakeup_schedule(
                BlockHeight::from_u32(4_224_680),
                &WakeupParams::default(),
                &mut rng,
            )
            .expect("schedule");
        assert!(wakeups.is_empty());
    }

    /// (c) THE tx9 CASE, exactly as captured live: tx3 mines at 4224587 — 11 blocks PAST tx9's
    /// boundary (4224576). This is the ACCEPTANCE ORACLE for the change request
    /// (`spec/2026-07-30-engine-change-request-unprovable-boundary.md`). It used to document a
    /// three-part GAP (a wedged `Prove {9}`, a dishonest READY status, a perpetual wake-up); the
    /// request's preferred fix shipped in `zcash_pool_migration` 0.1.0-rc.6, so those assertions
    /// have FLIPPED to their healed form:
    ///
    ///  HEAL 0: `next_step` still offers `Prove { 9 }`, but that instruction now COMPLETES — the
    ///          consumer's `engine::prove_transfer` re-draws tx9's boundary to a bucket covering
    ///          tx3's real mined height and proves against it. So a normal `drain` (which applies the
    ///          prove) advances tx9 all the way to Broadcast — no wedge.
    ///  HEAL 1: `transaction_statuses` reporting tx9 READY-to-Prove with no blocker is now HONEST:
    ///          the prove genuinely succeeds.
    ///  HEAL 2: the immediate wake-up covering tx9 is now correct work, not a dead poll.
    ///
    /// Note this trace drives the PURE state machine with a `NoOpPoolMigrationStore` and simulates
    /// proving by flipping state (it never calls `prove_transfer`); the heal it models is that the
    /// prover NO LONGER fails on tx9, so `drain` — which applies every offered prove — is the
    /// faithful model, where before only `drain_with_failing_prover` was.
    #[test]
    fn unprovable_boundary_heals_via_prove_time_redraw() {
        let mut d = Driver::new(live_plan());
        // Preps execute as live; tx3 mines LATE, past tx9's boundary.
        d.drain(4_224_542);
        d.mine(0, 4_224_544);
        d.mine(1, 4_224_549);
        d.drain(4_224_552);
        d.mine(2, 4_224_568);
        d.drain(4_224_563);
        d.mine(3, 4_224_587);

        // HEAL 1: with tx3 mined (late), tx9's boundary settled and deps mined, the status view
        // honestly reports tx9 as READY to Prove with no blocker — the prove will now succeed.
        let statuses = d
            .build()
            .transaction_statuses(DuenessTargets::at(BlockHeight::from_u32(4_224_701)));
        let tx9 = statuses
            .iter()
            .find(|st| id_of(st.id()) == 9)
            .expect("tx9 status");
        assert!(
            tx9.ready(),
            "tx9 is ready to prove — the redraw heals it at prove time"
        );
        assert_eq!(
            tx9.blocked_on(),
            None,
            "no blocker: the offered Prove now completes"
        );

        // HEAL 2: an immediate wake-up covers tx9 — now legitimate work, not a dead poll.
        let mut rng = <rand::rngs::StdRng as rand::SeedableRng>::seed_from_u64(7);
        let wakeups = d
            .build()
            .sync_wakeup_schedule(
                BlockHeight::from_u32(4_224_700),
                &WakeupParams::default(),
                &mut rng,
            )
            .expect("schedule");
        assert!(
            wakeups
                .iter()
                .any(|w| w.covers().iter().any(|t| id_of(*t) == 9)),
            "the wake-up covering tx9 now drives a prove that succeeds"
        );

        // HEAL 0: a NORMAL drain (the prover no longer fails on tx9) proves AND broadcasts tx9 —
        // no wedge. Everything is due at this tip, so the whole transfer set proves and broadcasts.
        let (applied, settle) = d.drain(4_224_700);
        // Batched (PR #2939): tx9 proves in the same Prove step as whatever else is due at this
        // tip (observed: `Prove([4, 8, 9])`), not alone — check membership, not an exact
        // single-id Prove.
        assert!(
            applied
                .iter()
                .any(|s| matches!(s, StepSummary::Prove(ids) if ids.contains(&9))),
            "tx9 is proved — prove_transfer's boundary redraw makes the offered Prove succeed"
        );
        assert!(
            applied.contains(&broadcast(9)),
            "tx9 advances to Broadcast; it is not wedged on an impossible prove"
        );
        assert_eq!(
            settle,
            StepSummary::Waiting,
            "settles Waiting for mines, not wedged"
        );

        // And the migration runs to Complete once everything mines — tx9 included.
        for id in 4..=14 {
            d.mine(id, 4_224_706);
        }
        let (_steps, settle) = d.drain(4_224_720);
        assert_eq!(
            settle,
            StepSummary::Complete,
            "the plan completes with tx9 healed"
        );
    }

    /// (d) EXPIRY still surfaces the rebuild path (independent of the late-dependency heal): if a
    /// transfer stays un-proved until its expiry height (here modelled with a failing prover on tx9),
    /// `next_step` emits `Rebuild { 9 }` and the status flips to the honest `Blocker::Expired`. The
    /// rc.6 prove-time redraw handles the late-dependency case earlier, but this expiry-driven
    /// rebuild remains the backstop for any transfer that never proves for other reasons.
    #[test]
    fn expiry_surfaces_rebuild_for_the_stuck_transfer() {
        let mut d = Driver::new(live_plan());
        d.drain(4_224_542);
        d.mine(0, 4_224_544);
        d.mine(1, 4_224_549);
        d.drain(4_224_552);
        d.mine(2, 4_224_568);
        d.drain(4_224_563);
        d.mine(3, 4_224_587);
        d.drain_with_failing_prover(4_224_700, &[9]);
        for id in [4, 5, 6, 7, 8, 10, 11, 12, 13, 14] {
            d.mine(id, 4_224_706);
        }

        let past_expiry = EXPIRY + 1;
        let (steps, settle) = d.drain(past_expiry);
        assert!(steps.is_empty());
        assert_eq!(settle, StepSummary::Rebuild(9));
        assert_eq!(
            blocker_of(&d.build(), past_expiry, 9),
            Some(Blocker::Expired)
        );
    }

    /// (e) EXTERNAL SIGNING round-trip: an `AwaitingSignature` transfer is reported via
    /// `Blocker::Signature`, the automatic driver takes no action on it, and `apply_signature`
    /// moves it to `Signed`, after which the ordinary prove/broadcast path resumes. This is the
    /// contract the Keystone flow adopts (replacing the app-side pending-PCZT plumbing).
    #[test]
    fn awaiting_signature_blocks_until_apply_signature() {
        let mut d = Driver::new(live_plan());
        // Whole prep chain + all-but-tx9 as in the happy path, but tx9 starts AwaitingSignature
        // (external signer) with its dependency mined IN margin.
        d.set(9, MigrationTxState::AwaitingSignature);
        d.drain(4_224_542);
        d.mine(0, 4_224_544);
        d.mine(1, 4_224_546);
        d.drain(4_224_552);
        d.mine(2, 4_224_556);
        d.drain(4_224_563);
        d.mine(3, 4_224_566);

        // Boundary settled, schedule due — yet the driver must not touch it.
        let (steps, settle) = d.drain(4_224_620);
        assert!(
            steps.iter().all(|s| match s {
                StepSummary::Prove(ids) => !ids.contains(&9),
                StepSummary::Broadcast(id) => *id != 9,
                _ => true,
            }),
            "an AwaitingSignature tx is driven by apply_signature, not the automatic loop"
        );
        assert_eq!(settle, StepSummary::Waiting);
        assert_eq!(
            blocker_of(&d.build(), 4_224_620, 9),
            Some(Blocker::Signature)
        );

        // The signed PCZT comes back from the device → Signed → the ordinary path resumes.
        let mut state = d.build();
        assert!(state.apply_signature(MigrationTransferId::new(9), vec![1u8; 32]));
        d.set(9, MigrationTxState::Signed);
        let (steps, _) = d.drain(4_224_620);
        assert_eq!(steps, vec![prove(9), broadcast(9)]);
    }

    /// (f) REPLAN → `mark_superseded`: a migration whose sole transfer is already marked
    /// unsatisfiable has its ENTIRE planned crossing value unsatisfiable, strictly past
    /// `ReplanThreshold::DEFAULT` (20%), so `next_step`'s EARLY replan slot fires
    /// (`state.rs`'s `next_step`: "Above the committed threshold, the replan preempts
    /// proving") without needing to drain anything to Complete first, and without the
    /// `NoOpPoolMigrationStore` ever being asked a satisfiability question (the transaction is
    /// `Signed`, never `Broadcast`/`Proved`, so the in-flight sweep skips it, and it is already
    /// marked, so no candidate check reaches it either).
    ///
    /// This proves both halves of the `mark_superseded` contract this test exists for:
    /// `advance_migration` reaching `Replan` is the sanctioned trigger, and `mark_superseded`
    /// is the sanctioned response — moving the migration to the terminal `Superseded` status
    /// that `Committer::start`'s commit guard (`CommitError::MigrationInProgress`, guarded by
    /// `existing.is_some_and(|existing| !existing.is_terminal())`) checks before accepting a
    /// replacement plan.
    #[test]
    fn replan_step_then_mark_superseded_unblocks_a_replacement_commit() {
        let crossing = zcash_protocol::value::Zatoshis::const_from_u64(100_000_000);
        let denominations = DenominationPlan::from_stored_parts(
            vec![crossing],
            zcash_protocol::value::Zatoshis::const_from_u64(5_000),
            None,
            zcash_protocol::value::Zatoshis::const_from_u64(10_000),
            zcash_protocol::value::Zatoshis::const_from_u64(100_010_000),
            crossing,
        )
        .expect("valid denomination plan");

        // The sole transfer, pre-marked unsatisfiable (as `InputsSpent` would leave it) — its
        // crossing value is the migration's entire planned value.
        let unsatisfiable_transfer = MigrationTransaction::from_parts(
            MigrationTransferId::new(0),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32],
            vec![], // depends_on
            BlockHeight::from_u32(1_000),
            BlockHeight::from_u32(EXPIRY),
            Some(BlockHeight::from_u32(1_000)),
            zcash_protocol::TxId::from_bytes([9; 32]),
            MigrationTxState::Signed,
            None, // lock_owner
            Some((
                BlockHeight::from_u32(999),
                zcash_pool_migration::satisfiability::UnsatisfiableKind::InputsSpent,
            )),
            vec![], // spend_nullifiers
            None,   // broadcast_failure_at
        );

        let mut state = MigrationState::from_parts(
            MigrationStatus::Committed,
            denominations,
            PreparationPlan::from_parts(vec![], vec![]),
            vec![unsatisfiable_transfer],
            AnchorBucketInterval::custom(NonZeroU32::new(BUCKET).expect("nonzero")),
            ReplanThreshold::DEFAULT,
        );

        // 1. `advance_step` (via `advance_migration`, the only path reachable from this crate's
        //    tests — `next_step` itself is `pub(crate)`) settles on `Replan`.
        let mut store = NoOpPoolMigrationStore;
        let step = advance_migration(
            &mut store,
            &mut state,
            DuenessTargets::at(BlockHeight::from_u32(1_001)),
            &AdvanceConfig::new(ReorgSettleDepth::new(10)),
            &mut rng(),
        )
        .expect("advance_migration over the no-op store")
        .step()
        .clone();
        assert_eq!(
            step,
            AdvanceStep::Replan,
            "the sole transfer's crossing value is entirely unsatisfiable, past \
             ReplanThreshold::DEFAULT"
        );
        assert!(state.replan_required());
        assert_ne!(
            state.status(),
            MigrationStatus::Superseded,
            "not yet marked"
        );

        // 2. The sanctioned response.
        state.mark_superseded();

        // 3. Terminal, and specifically Superseded (not Failed/Complete).
        assert_eq!(state.status(), MigrationStatus::Superseded);
        assert!(state.is_terminal());

        // 4. Mirror `Committer::start`'s guard directly (engine.rs: `backend.get_migration()...
        //    .is_some_and(|existing| !existing.is_terminal())` => `Err(MigrationInProgress)`).
        //    For `Some(state)` that reduces to `!state.is_terminal()`; asserting it's `false` is
        //    exactly "a replacement commit is unblocked" — the documented precondition the
        //    commit guard checks, without re-deriving the whole commit flow here.
        assert!(
            Some(&state).is_none_or(|existing| existing.is_terminal()),
            "mark_superseded's terminal state satisfies Committer::start's commit guard"
        );
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════════
// State-machine driver surface (engine adoption, 2026-07-30 — see
// spec/2026-07-30-engine-state-machine-adoption-design.md). The app's single migration worker
// obeys these three reads (`nextStep`, extended `transactionStates`, `syncWakeupSchedule`) plus
// `applySignature`; it never selects, orders or schedules transactions itself.
//
// LATE-DEPENDENCY GUARD VETO — REMOVED (resolved upstream, rc.6). This surface once applied a local
// veto on top of the engine's reads: a transfer whose dependency mined past its anchor boundary was
// withheld from Prove, reported with a synthetic UNPROVABLE_ANCHOR blocker, and dropped from sync
// wake-ups, because the pure `state.rs::prove_ready` offered a Prove that could never succeed
// (change request `spec/2026-07-30-engine-change-request-unprovable-boundary.md`). That request's
// preferred fix shipped in `zcash_pool_migration` 0.1.0-rc.6: `engine::prove_transfer` re-draws the
// boundary at prove time so the Prove the engine offers now genuinely completes. The engine's reads
// are therefore HONEST as-is, and the local veto is gone — the driver surface below delegates
// entirely to `advance_migration` / `transaction_statuses` / `sync_wakeup_schedule`.
// ════════════════════════════════════════════════════════════════════════════════════════════════

/// How far the chain must advance past a divergence before a displacement counts as PERMANENT —
/// caller policy that `zcash_pool_migration` deliberately does not default, since the right value
/// tracks block spacing. Ten blocks is ~12 minutes at the 75-second target. A depth judged too
/// aggressively self-corrects: the marks it produces are cleared by reorg truncation if the chain
/// swings back.
const SETTLE_DEPTH: ReorgSettleDepth = ReorgSettleDepth::new(10);

const STEP_WAITING: i64 = 0;
const STEP_PROVE: i64 = 1;
const STEP_BROADCAST: i64 = 2;
const STEP_REBUILD: i64 = 3;
const STEP_COMPLETE: i64 = 4;
/// Enough planned value can never mine (or dead value is stranded with no live work left): the
/// consumer supersedes this migration and re-plans the remaining balance. New with the engine's
/// drive loop; no hand-rolled equivalent existed.
const STEP_REPLAN: i64 = 5;
/// A node REJECTED a broadcast and the wallet cannot yet say why, its answers resting on chain
/// state below the tip that node reported. The consumer SYNCS to at least that tip and asks again.
/// Outranks every other step but Complete: a rejection means some other observer saw chain state
/// this wallet has not, so proceeding would act on a view already known to be stale.
const STEP_REEVALUATE: i64 = 6;

const ACTION_NONE: i32 = 0;
const ACTION_PROVE: i32 = 1;
const ACTION_BROADCAST: i32 = 2;

const BLOCKER_NONE: i32 = 0;
const BLOCKER_DEPENDENCIES: i32 = 1;
const BLOCKER_SCHEDULE: i32 = 2;
const BLOCKER_ANCHOR_BOUNDARY: i32 = 3;
const BLOCKER_SIGNATURE: i32 = 4;
const BLOCKER_EXPIRED: i32 = 5;
// Code 6 (BLOCKER_UNPROVABLE_ANCHOR) was a synthetic, app-facing blocker emitted by the now-removed
// late-dependency guard veto (resolved upstream in rc.6; see the driver-surface banner above). The
// backend no longer produces it. The value stays RESERVED — the Kotlin `MigrationBlocker` mapping
// keeps a legacy `6 -> UNPROVABLE_ANCHOR` arm for wire compatibility, and no new blocker should
// reuse the number.
/// `Blocker::ExpiryImminent` (rc.6): the caller's ESTIMATED tip has passed expiry but the wallet's
/// own scan has not caught up yet, so the kernel withholds without recording anything.
const BLOCKER_EXPIRY_IMMINENT: i32 = 7;
/// A node REJECTED a broadcast of this transaction and the wallet has not scanned far enough to
/// explain why. New in `zcash_pool_migration 0.1.0-rc.6`.
const BLOCKER_AWAITING_REEVALUATION: i32 = 8;
/// The transaction can never mine — marked unsatisfiable, or stranded behind one that is. The
/// remedy is a migration-level replan, never more syncing. New in `zcash_pool_migration 0.1.0-rc.6`.
const BLOCKER_UNSATISFIABLE: i32 = 9;

/// Two-tip decision (spec §3): the underlying `advance_migration` call is driven by
/// `DuenessTargets::new(scanned, estimated)`, which folds the ESTIMATED tip into its `effective`
/// target (broadcast timing, an optimistic estimate that only ever accelerates) while keeping the
/// SCANNED tip as its `scanned` target (proving/rebuild/expiry, which need real chain facts, not
/// an estimate) — see `DuenessTargets::new`'s doc. This delegates step selection entirely to the
/// crate's own `advance_migration`, rather than re-implementing next-broadcastable/prove-ready/
/// rebuild-on-expiry locally. (Historically the reporting surfaces applied a local late-dependency
/// guard veto on top; that veto has been removed now that rc.6's `engine::prove_transfer` heals the
/// condition at prove time — see the driver-surface banner above.)
/// Returns `(stepCode, transferId, nextHeight, nextKind)`. `transferId` is `-1` when the step
/// names no transaction. `nextHeight`/`nextKind` come from `Advance::next` — the engine's own
/// peek-ahead at the subsequent step, assuming the returned step is executed and recorded (see
/// that method's doc for the ADVISORY-outlook semantics); both are `-1` when `next` is `None`
/// (nothing height-schedulable: chain- or user-driven, or terminal). `nextKind` uses the SAME
/// `STEP_*` encoding as `stepCode` (`StepKind` and `AdvanceStep` are 1:1).
fn advance_step(
    backend: &mut impl PoolMigrationWrite<Error = EngineError>,
    state: &mut MigrationState,
    scanned_target: BlockHeight,
    estimated_target: BlockHeight,
) -> anyhow::Result<(i64, i64, i64, i64)> {
    let targets = DuenessTargets::new(scanned_target, estimated_target);
    let config = AdvanceConfig::new(SETTLE_DEPTH);
    let mut rng = OsRng;
    let advance = advance_migration(backend, state, targets, &config, &mut rng)
        .map_err(|e| anyhow!("Error advancing migration: {:?}", e))?;
    let (code, id) = match advance.step() {
        // The step now carries the WHOLE provable set (PR #2939) rather than one candidate — see
        // the type's doc. Our own JNI contract still reports one representative id (never empty,
        // per the doc, so `.first()` is total): the app's own `finalizeReadyTransfers` sweep
        // already proves every ready transaction in one pass regardless of which single id this
        // reports (core sync call §2.3 — Android was already structurally correct for this).
        AdvanceStep::Prove { transactions } => {
            let first = transactions
                .first()
                .expect("Prove's transaction set is never empty");
            (STEP_PROVE, i64::from(u32::from(first.id())))
        }
        AdvanceStep::Broadcast { id } => (STEP_BROADCAST, i64::from(u32::from(*id))),
        AdvanceStep::Rebuild { id } => (STEP_REBUILD, i64::from(u32::from(*id))),
        AdvanceStep::Replan => (STEP_REPLAN, -1),
        AdvanceStep::Reevaluate => (STEP_REEVALUATE, -1),
        AdvanceStep::Waiting => (STEP_WAITING, -1),
        AdvanceStep::Complete => (STEP_COMPLETE, -1),
    };
    let (next_height, next_kind) = match advance.next() {
        Some((height, kind)) => (
            i64::from(u32::from(height)),
            match kind {
                StepKind::Prove => STEP_PROVE,
                StepKind::Broadcast => STEP_BROADCAST,
                StepKind::Rebuild => STEP_REBUILD,
                StepKind::Replan => STEP_REPLAN,
                StepKind::Reevaluate => STEP_REEVALUATE,
                StepKind::Waiting => STEP_WAITING,
                StepKind::Complete => STEP_COMPLETE,
            },
        ),
        None => (-1, -1),
    };
    Ok((code, id, next_height, next_kind))
}

/// The single "what now?" read the app worker loops on. Returns
/// `[stepCode, transferId, nextHeight, nextKind]` (`transferId = -1` for Waiting/Complete;
/// `nextHeight`/`nextKind = -1` when the engine's own peek-ahead — `Advance::next`, see
/// `advance_step`'s doc — has nothing height-schedulable to report). Two-tip (spec §3): broadcast
/// timing is decided at the ESTIMATED target (`estimatedTip + 1`, clamped to never go below the
/// scanned target — an optimistic estimate only ever ACCELERATES broadcast, never substitutes for
/// a real checkpoint), while proving, rebuild, and completion stay on the SCANNED target
/// (`tip + 1`) — those need real mined/expiry facts, not an estimate. Pass `estimatedTip < 0` when
/// no estimate is available; the estimated target then equals the scanned target (no
/// acceleration).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_nextStepNative<
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
        let scanned = target_height(&wallet)?; // scanned tip + 1
        // estimated_tip < 0 → unavailable → no acceleration (estimated == scanned).
        let estimated = if estimated_tip < 0 {
            scanned
        } else {
            std::cmp::max(scanned, BlockHeight::from_u32(estimated_tip as u32) + 1)
        };
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let Some(mut state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(ptr::null_mut());
        };
        let (code, id, next_height, next_kind) =
            advance_step(&mut backend, &mut state, scanned, estimated)?;
        let arr = env.new_long_array(4)?;
        env.set_long_array_region(&arr, 0, &[code, id, next_height, next_kind])?;
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The minimal sync/prove wake-up schedule for the app's worker, from the engine's
/// `sync_wakeup_schedule` (windows, minimality, jitter, immediate-overdue), taken as-is. The former
/// guard-veto that dropped unprovable-anchor transfers from coverage is gone (resolved upstream in
/// rc.6 — see the driver-surface banner): a late-dependency transfer is no longer a permanent wedge
/// (`engine::prove_transfer` heals it at prove time), so waking to prove it is correct.
/// Encoding: an array of long-arrays, each `[wakeHeight, coveredId...]`; empty wake-ups are
/// dropped.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_syncWakeupScheduleNative<
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
        let target = target_height(&wallet)?;
        let tip = target - 1;
        let backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(ptr::null_mut());
        };
        let mut rng = OsRng;
        let wakeups = state
            .sync_wakeup_schedule(
                tip,
                &zcash_pool_migration::scheduling::WakeupParams::default(),
                &mut rng,
            )
            .map_err(|e| anyhow!("Error computing sync wake-up schedule: {:?}", e))?;
        let entries: Vec<Vec<i64>> = wakeups
            .iter()
            .filter_map(|w| {
                let ids: Vec<i64> = w
                    .covers()
                    .iter()
                    .map(|t| i64::from(u32::from(*t)))
                    .collect();
                if ids.is_empty() {
                    None
                } else {
                    let mut row = vec![i64::from(u32::from(w.height()))];
                    row.extend(ids);
                    Some(row)
                }
            })
            .collect();
        let long_array_class = env.find_class("[J")?;
        let outer =
            env.new_object_array(entries.len() as i32, long_array_class, JObject::null())?;
        for (i, row) in entries.iter().enumerate() {
            let inner = env.new_long_array(row.len() as i32)?;
            env.set_long_array_region(&inner, 0, row)?;
            env.set_object_array_element(&outer, i as i32, inner)?;
        }
        Ok(outer.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Stores an externally signed PCZT for one migration transaction, moving it
/// `AwaitingSignature → Signed` via the engine's `apply_signature` (the state-machine contract the
/// Keystone flow adopts). Returns whether the state changed.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_applySignatureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    transfer_id: jlong,
    signed_pczt: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let id = decode_transfer_id(transfer_id)?;
        let pczt_bytes = env.convert_byte_array(signed_pczt)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let mut state = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            .ok_or_else(|| anyhow!("No migration in progress"))?;
        let applied = state.apply_signature(id, pczt_bytes);
        if applied {
            backend
                .replace_migration(&state)
                .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
        }
        Ok(applied as jboolean)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// Marks a Replan-requesting migration Superseded (state.rs's `mark_superseded` — see its doc:
/// "after this, the commit guard accepts a replacement migration for the remaining balance"),
/// exactly matching the sim test's call sequence (immediately after `AdvanceStep::Replan`). A
/// no-op, returning `JNI_FALSE`, if the migration is already terminal or doesn't exist —
/// `mark_superseded` itself is a no-op on an already-terminal migration, so this is safe to call
/// repeatedly.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_markMigrationSupersededNative<
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn, *wallet.params())?;
        let Some(mut state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(JNI_FALSE);
        };
        let was_terminal = state.is_terminal();
        state.mark_superseded();
        if was_terminal {
            return Ok(JNI_FALSE);
        }
        backend
            .replace_migration(&state)
            .map_err(|e| anyhow!("Error persisting superseded migration: {:?}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// The engine's Keystone signing-round budget constants, so the app never hardcodes them:
/// `[max_actions_per_round, preparation_actions, transfer_actions]` — today `[96, 16, 3]` from
/// `zcash_pool_migration::signing_rounds` (`SigningRoundBudget::KEYSTONE`, `PREPARATION_ACTIONS`,
/// `TRANSFER_ACTIONS`). A signing ROUND is one QR interaction; the budget is the TOTAL Orchard
/// actions across every transaction in that round, never a per-transaction cap. Pure constants —
/// no wallet database is opened.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_keystoneSigningRoundBudgetNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jintArray {
    let res = catch_unwind(&mut env, |env| {
        use zcash_pool_migration::signing_rounds::{
            PREPARATION_ACTIONS, SigningRoundBudget, TRANSFER_ACTIONS,
        };
        let values = [
            SigningRoundBudget::KEYSTONE.max_actions() as jint,
            PREPARATION_ACTIONS as jint,
            TRANSFER_ACTIONS as jint,
        ];
        let arr = env.new_int_array(values.len() as i32)?;
        env.set_int_array_region(&arr, 0, &values)?;
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Coverage for the design doc's refutation of the originally-proposed `truncate_to_height` hook
/// (`.superpowers/sdd/2026-08-05-migration-engine-full-delegation-plan/task-8-brief.md`):
/// `zcash_client_sqlite` already drives `MigrationState::truncate_to_height` from inside its own
/// `WalletWrite::truncate_to_height` — the exact call our `rewindToHeight` JNI entry point makes
/// (`lib.rs`'s `Java_..._RustBackend_rewindToHeight`, via `db_data.truncate_to_height(height)`) —
/// so a wallet reorg demotes a `Mined` migration transaction with no hook of our own.
///
/// Verified directly against `zcash_client_sqlite` 0.22.0-rc.7 (the version pinned in
/// `Cargo.lock`): `wallet::truncate_to_height_internal` calls
/// `crate::pool_migration::orchard_ironwood::truncate_to_height(conn, truncation_height)`
/// unconditionally, in the SAME `rusqlite::Transaction` as the rest of the wallet's own
/// truncation, for every account with a stored migration — not just the caller's. That function
/// (`pool_migration::store::truncate_to_height`) reads each account's persisted `MigrationState`,
/// calls its own `MigrationState::truncate_to_height` (which demotes a `Mined` transaction whose
/// height is above the truncation point back to `Broadcast`, clears stale marks, and reverts a
/// `Complete` status a demotion unsettles — see that method's own doc comment in
/// `zcash_pool_migration::state`), and re-persists it if it changed.
///
/// This test proves that wiring holds for OUR pinned dependency versions, end to end against a
/// real (synthetic, in-memory) `WalletDb`. It goes around our own `Backend` adapter
/// (`migration_engine.rs`) — irrelevant here, since truncation is driven by
/// `WalletWrite::truncate_to_height` itself, never by anything our adapter calls — and straight at
/// `zcash_client_sqlite::pool_migration::orchard_ironwood::PoolMigrations`, the exact store our
/// adapter wraps.
///
/// Harness: `zcash_client_backend::data_api::testing::TestBuilder` /
/// `zcash_client_sqlite::testing::{db::TestDbFactory, BlockCache}` — the same synthetic,
/// self-contained wallet-DB harness `zcash_client_sqlite`'s own
/// `tests/pool_migration_prove_chain_sim.rs`
/// (`a_settled_reorg_below_a_broadcast_crossings_anchor_marks_it`) uses for its own reorg
/// coverage, minus the real funding/proving machinery that test needs and this one does not (no
/// crossing is ever proved or broadcast here — the migration transaction is planted directly in
/// `Mined` state via `MigrationTransaction::from_parts`, following this file's own
/// `late_dependency_anchor_tests`/`next_due_transfer_tests` fixture-builder pattern). This crate
/// has no fixture wallet-DB file checked in — the other real-DB tests in this file all gate on
/// `MIGRATION_TEST_WALLET_DB` and are `#[ignore]`d for exactly that reason — so a synthetic
/// in-memory wallet is what lets this run unattended in ordinary `cargo test`.
#[cfg(test)]
mod wallet_rewind_tests {
    use super::*;
    use zcash_client_backend::data_api::testing::TestBuilder;
    use zcash_client_backend::data_api::{Account, WalletWrite};
    use zcash_client_sqlite::pool_migration::orchard_ironwood::PoolMigrations;
    use zcash_client_sqlite::testing::BlockCache;
    use zcash_client_sqlite::testing::db::TestDbFactory;
    use zcash_pool_migration::denomination::DenominationPlan;
    use zcash_pool_migration::engine::MigrationStatus;
    use zcash_pool_migration::scheduling::AnchorBucketInterval;
    use zcash_primitives::block::BlockHash;

    fn note_split() -> DenominationPlan {
        DenominationPlan::from_stored_parts(
            vec![Zatoshis::const_from_u64(100_000_000)],
            Zatoshis::const_from_u64(5_000),
            None,
            Zatoshis::const_from_u64(10_000),
            Zatoshis::const_from_u64(100_010_000),
            Zatoshis::const_from_u64(100_000_000),
        )
        .expect("valid note split plan")
    }

    /// A single-transfer `MigrationState`, already `Mined` at `mined_height` and `Complete` — the
    /// exact shape `MigrationState::truncate_to_height`'s doc comment describes rolling back: a
    /// demotion here must also revert `Complete` back to `InProgress`.
    /// `status` is `InProgress`, not `Complete`, even though this fixture's one transaction is
    /// fully `Mined`: `PoolMigrationRead::get_migration` is now documented as PENDING-ONLY on this
    /// pin (`zcash_pool_migration::engine::PoolMigrationRead::get_migration`'s doc: "A migration
    /// whose status is terminal... is retained history and is NOT reported here") — a `Complete`
    /// fixture would make every `get_migration()` read in this test (including the pre-truncation
    /// sanity check, before truncation is even exercised) return `None`, which is a store-contract
    /// change unrelated to the reorg-demotion behavior this test targets. `InProgress` keeps the
    /// fixture visible to `get_migration()` while remaining in `store::truncate_to_height`'s walk
    /// (`WHERE status NOT IN (policy_terminal)` — `InProgress` is non-terminal either way).
    fn mined_state(mined_height: BlockHeight, txid: zcash_protocol::TxId) -> MigrationState {
        let tx = MigrationTransaction::from_parts(
            MigrationTransferId::new(1),
            MigrationTxKind::Transfer { crossing: 0 },
            vec![0u8; 32], // dummy pczt — never proved/broadcast/read as PCZT bytes here
            vec![],        // no dependencies
            mined_height,
            mined_height + 100,
            Some(mined_height - 10), // anchor_boundary
            txid,
            MigrationTxState::Mined {
                txid,
                height: mined_height,
            },
            None,
            None,
            vec![[7u8; 32]],
            None,
        );
        MigrationState::from_parts(
            MigrationStatus::InProgress,
            note_split(),
            PreparationPlan::from_parts(vec![], vec![]),
            vec![tx],
            AnchorBucketInterval::ZIP_318,
            ReplanThreshold::DEFAULT,
        )
    }

    #[test]
    fn wallet_truncate_to_height_demotes_a_mined_migration_transaction() {
        let mut st = TestBuilder::new()
            .with_data_store_factory(TestDbFactory::default())
            .with_block_cache(BlockCache::new())
            .with_account_from_sapling_activation(BlockHash([0; 32]))
            .build();

        // Real, scanned chain history: 20 blocks, so a rewind 10 blocks behind the tip stays well
        // inside the wallet's checkpoint-retention window (mirrors why
        // `a_settled_reorg_below_a_broadcast_crossings_anchor_marks_it` in `zcash_client_sqlite`'s
        // own test suite keeps its fork shallow — this crate retains the most recent hundred
        // checkpoints).
        let (first_height, _) = st.generate_empty_block();
        for _ in 0..19 {
            st.generate_empty_block();
        }
        st.scan_cached_blocks(first_height, 20);
        let tip = st
            .wallet()
            .chain_height()
            .expect("chain height lookup")
            .expect("wallet has a chain tip after scanning");

        let account = st
            .test_account()
            .expect("TestBuilder configured a test account")
            .id();

        // Plant a migration whose one transaction is ALREADY `Mined`, above where we're about to
        // truncate — directly into the real SQLite pool-migration store, bypassing our own
        // `Backend` adapter (irrelevant to this claim: nothing in `Backend`/`migration_engine.rs`
        // is on the truncation path) and any real signing/proving/broadcast machinery.
        let txid = zcash_protocol::TxId::from_bytes([9u8; 32]);
        let mined_height = tip;
        let truncate_to = tip - 10;
        let state = mined_state(mined_height, txid);
        let network = TestBuilder::<(), ()>::DEFAULT_NETWORK;
        {
            let mut store = PoolMigrations::for_account(
                network,
                SystemClock,
                st.wallet_mut().conn_mut(),
                account,
            )
            .expect("open pool-migration store to seed the fixture");
            store
                .replace_migration(&state)
                .expect("persist synthetic Mined migration");
        }

        // Sanity: before touching the wallet, a raw read still shows Mined.
        {
            let store = PoolMigrations::for_account(
                network,
                SystemClock,
                st.wallet_mut().conn_mut(),
                account,
            )
            .expect("reopen pool-migration store");
            let before = store
                .get_migration()
                .expect("read migration state")
                .expect("migration state committed");
            assert!(
                matches!(
                    before.transactions()[0].state(),
                    MigrationTxState::Mined { .. }
                ),
                "fixture setup must start Mined before exercising the wallet rewind"
            );
        }

        // The exact call our `rewindToHeight` JNI entry point makes
        // (`db_data.truncate_to_height(height)` in `lib.rs`) — no migration-specific code of ours
        // runs here at all.
        let achieved = st
            .wallet_mut()
            .truncate_to_height(truncate_to)
            .expect("wallet truncate_to_height");
        assert!(
            achieved < mined_height,
            "the achieved truncation height ({achieved:?}) must land below the migration's Mined \
             height ({mined_height:?}) for this test to exercise the demotion at all"
        );

        // If `zcash_client_sqlite` did NOT drive `MigrationState::truncate_to_height` from inside
        // the wallet's own truncation, this read would still show `Mined` — the demotion would
        // need a hook of our own to happen at all, i.e. the design doc's refutation would be
        // WRONG for our pinned dependency versions.
        let store =
            PoolMigrations::for_account(network, SystemClock, st.wallet_mut().conn_mut(), account)
                .expect("reopen pool-migration store after truncation");
        let after = store
            .get_migration()
            .expect("read migration state")
            .expect("migration state committed");
        let after_tx = &after.transactions()[0];
        match after_tx.state() {
            MigrationTxState::Broadcast { txid: demoted_txid } => {
                assert_eq!(
                    demoted_txid, txid,
                    "demotion must keep the txid the transaction was mined under"
                );
            }
            other => panic!(
                "expected the wallet's real WalletWrite::truncate_to_height (the same call our \
                 rewindToHeight JNI path makes) to demote the Mined migration transaction to \
                 Broadcast via zcash_client_sqlite's internal pool_migration truncation wiring — \
                 got {other:?} instead. If this is failing, the design doc's refutation of the \
                 originally-proposed truncate_to_height hook was WRONG for this pinned \
                 zcash_client_sqlite version and that hook needs to be resurrected."
            ),
        }
        assert_eq!(
            after.status(),
            MigrationStatus::InProgress,
            "a demotion that leaves the migration's only transaction unmined must also revert \
             `Complete` back to `InProgress`"
        );
    }
}
