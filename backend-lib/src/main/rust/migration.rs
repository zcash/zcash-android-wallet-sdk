//! Migration engine wiring: the logic behind the `MigrationRustBackend` JNI surface.
//!
//! Rewired (2026-07-21) from our own hand-rolled `zcash_pool_migration` crate onto the core/
//! upstream `zcash_pool_migration_backend` crate plus `zcash_client_sqlite::pool_migration`
//! (Danny/core team, `zcash/librustzcash` PR #2669 + stack; the SQLite persistence side was later
//! folded from a standalone `zcash_pool_migration_sqlite` crate into `zcash_client_sqlite` proper).
//! See `migration_engine.rs` for the adapter wiring our wallet DB into the new engine's traits, and
//! `docs/superpowers/specs/2026-07-21-current-migration-implementation-spec.md` (zashi-android
//! repo) for the full gap analysis this rewire is based on.
//!
//! Nothing in this module touches the JNI boundary (outside its tests): the `Java_*` exports that
//! call into it live in [`crate::jni::migration`], and that module's doc records the three known,
//! deliberate JNI-contract deviations (`anchorHeight`, ahead-of-broadcast proving via `try_prove`,
//! and the `PlanHandle` rule that keeps plan details from ever crossing the boundary inward) that
//! this wiring is built around. Read it alongside this one.

use anyhow::anyhow;
use rand::rngs::OsRng;
use rusqlite::Connection;

use zcash_client_backend::data_api::WalletRead;
use zcash_client_backend::keys::UnifiedSpendingKey;
use zcash_client_backend::wallet::LockOwner;
use zcash_client_sqlite::AccountUuid;
use zcash_client_sqlite::util::SystemClock;
use zcash_protocol::consensus::{BlockHeight, Network};

use zcash_pool_migration_backend::engine::{
    self, MigrationCrypto, MigrationPlan, MigrationState, MigrationTxId, MigrationTxKind,
    MigrationTxState, PoolMigrationRead, PoolMigrationWrite, ProveError,
};
use zcash_pool_migration_backend::wallet::{WalletMigrationProver, WalletProveError};

use crate::migration_engine::Backend;

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
pub(crate) fn open_at(
    db_path: &std::path::Path,
    network: Network,
) -> anyhow::Result<(Wallet, Connection)> {
    let wallet = Wallet::for_path(db_path.to_path_buf(), network, SystemClock, OsRng)
        .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))?;
    let store_conn = Connection::open(db_path)
        .map_err(|e| anyhow!("Error opening migration store connection: {}", e))?;
    // The pool-migration tables are created by `zcash_client_sqlite`'s own schema migrations
    // (`orchard_ironwood_migration_tables`, run as part of the wallet's normal `init_wallet_db`
    // call, see `lib.rs`), not by this crate — no separate init call needed here.
    ensure_migration_schema_current(&store_conn)?;
    Ok((wallet, store_conn))
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

/// Self-heals `orchard_ironwood_migration_transactions` against a wallet created between two
/// pre-release librustzcash schema revisions.
///
/// `orchard_ironwood_migration_tables`'s migration has repeatedly grown its DDL IN PLACE, under
/// the SAME never-changing `MIGRATION_ID` (account-keying, commit `ff15da7c8f`; this table's
/// `lock_owner` column, commit `fcf4ceb3b1`) — this is librustzcash's stated, deliberate policy
/// while the feature is unreleased ("these tables have not been part of a public release... a
/// developer database must be recreated", `ff15da7c8f`'s commit message), not an oversight. Since
/// `schemerz` never re-runs a migration whose id it already recorded as applied, and the DDL is
/// `CREATE TABLE IF NOT EXISTS` (a no-op on an existing table), a wallet that already ran this
/// migration under an OLDER shape keeps that older shape forever and crashes with "no such
/// column" the moment newer code queries the missing one (confirmed live twice now: `account_id`,
/// then `lock_owner`).
///
/// Rather than requiring a full wallet wipe on every such churn, patch the one column difference
/// we've hit directly: idempotent (checks before altering), and a no-op if the table doesn't
/// exist yet (a wallet that has never attempted a migration) or already has the column.
fn ensure_migration_schema_current(conn: &Connection) -> anyhow::Result<()> {
    let table_exists: bool = conn
        .prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'orchard_ironwood_migration_transactions'")
        .map_err(|e| anyhow!("Error checking migration schema: {}", e))?
        .exists([])
        .map_err(|e| anyhow!("Error checking migration schema: {}", e))?;
    if !table_exists {
        return Ok(());
    }
    let has_lock_owner: bool = conn
        .prepare(
            "SELECT 1 FROM pragma_table_info('orchard_ironwood_migration_transactions') \
             WHERE name = 'lock_owner'",
        )
        .map_err(|e| anyhow!("Error checking migration schema: {}", e))?
        .exists([])
        .map_err(|e| anyhow!("Error checking migration schema: {}", e))?;
    if !has_lock_owner {
        conn.execute_batch(
            "ALTER TABLE orchard_ironwood_migration_transactions ADD COLUMN lock_owner BLOB",
        )
        .map_err(|e| anyhow!("Error patching migration schema (lock_owner): {}", e))?;
    }
    Ok(())
}

/// The height migration transactions are built/planned against — one past the current tip,
/// matching the old crate's convention (and `migration_engine::Backend`'s own note-selection
/// target).
pub(crate) fn target_height(wallet: &Wallet) -> anyhow::Result<BlockHeight> {
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
pub(crate) fn natural_anchor_height(wallet: &Wallet) -> anyhow::Result<BlockHeight> {
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
pub(crate) fn compute_plan(
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
pub(crate) fn plan_for(
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

/// Returns the already-committed migration state if one exists (non-terminal), otherwise commits
/// the cached plan that `plan_handle` identifies — erroring if no plan is cached or if a later
/// `propose*`/`prepare*` call replaced the plan the caller was shown (see `migration_plan_cache`'s
/// module doc: the handle gate is what guarantees a commit can only sign the exact plan the user
/// reviewed). On the reuse path the handle is not consulted: the commitment already happened —
/// with a handle-verified plan — and is durable, so there is nothing left the handle could
/// protect. Shared by both the in-process-signing and external-signer commit paths below; `sign`
/// picks which `commit_preparation`/`build_preparation_unsigned` variant to run, and whether a
/// spending key is available to the `Backend` while doing so.
pub(crate) fn commit_or_reuse(
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

/// Whether transaction `tx` is ready to PROVE at `target_height` (`chain_tip + 1`) — a local copy
/// of `zcash_pool_migration_backend::state`'s private `MigrationState::prove_ready`, using only its
/// public surface (`deps_mined`, `anchor_boundary`, `scheduled_height`). Duplicated rather than
/// relying on `MigrationState::next_provable` because that returns only the SINGLE next-ready
/// transaction — looping it would re-return the same id forever on a transient witness/anchor
/// failure (see `try_prove`'s doc comment), whereas our JNI contract proves every ready transaction
/// in one call.
pub(crate) fn is_prove_ready(
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
/// `zcash_pool_migration_backend`'s own `WalletMigrationProver` (the core-team-maintained
/// replacement for this crate's former hand-ported `migration_finalize` stopgap; see that module's
/// removal and `docs` for context). A transfer proves against its own persisted `anchor_boundary`
/// (read internally by `engine::prove_transfer`); a preparation transaction carries no drawn
/// boundary and proves against the wallet's current natural anchor instead, matching
/// `zcash_pool_migration_backend`'s own `prove_chain_sim.rs` integration test.
///
/// Returns `Ok(true)` if proved (`state` now has this transaction `Proved`, with the proven PCZT
/// replacing the stored one), `Ok(false)` if its witness/anchor isn't resolvable yet — the funding
/// note hasn't been observed as spendable yet, or its checkpoint hasn't been reached or was pruned
/// (`WalletProveError::UnknownSpentNote`/`AnchorNotFound`/`WitnessNotFound`) — this is the ordinary
/// transient "not ready yet" condition, not a failure, matching the old stopgap's `Ok(None)`
/// contract. Any other error is propagated.
pub(crate) fn try_prove(
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
pub(crate) fn finalize_note_split(
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

/// Reconciles mined-ness against the wallet's own transaction history before returning migration
/// state, so `InProgress`/`Complete` derivation reflects broadcast truth instead of staying stuck at
/// whatever `mark_broadcast` last recorded. The engine's own contract intentionally leaves mining
/// detection to the caller (`state.rs` module doc: "the state machine's only job is to ORDER the
/// broadcasts") — this is that caller-side reconciliation, run at read time rather than a background
/// job, matching the iOS SDK's own `derive_state` reconciliation approach.
pub(crate) fn read_reconciled(
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

/// A fixed, well-known lock owner for the "Lock balance" dust-lock feature
/// (`MigrationSdk.lockRemainingOrchardBalance`) — not a per-proposal lock, so a stable constant
/// (not `LockOwner::random`) lets re-invoking the feature re-extend the same lock idempotently
/// (see `WalletWrite::lock_outputs`'s doc comment on same-owner re-locking) and would let a future
/// "undo" flow release it via this same token.
pub(crate) const DUST_LOCK_OWNER: LockOwner = LockOwner::new(*b"zashi-migration-dust-lock-owner!");

/// Fetches the account's ZIP 32 seed fingerprint and account index, required to annotate
/// external-signer (Keystone) migration PCZTs with `spend_zip32_derivation` — see
/// `migration_keystone::annotate_spend_zip32_derivation`'s doc comment for why this is needed.
///
/// Applied as a post-processing step on whatever unsigned PCZT bytes `commit_or_reuse` returns
/// (freshly built, or reused from an already-committed migration) rather than inside the `sign`
/// closure passed to it: `commit_or_reuse` only calls that closure on first commit, so annotating
/// only there would silently skip already-committed migrations (e.g. ones committed before this
/// annotation existed) on every later re-entry into the Keystone sign screen.
pub(crate) fn account_zip32_derivation(
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
    // `zcash_pool_migration_backend`'s own `WalletMigrationProver`/`engine::prove_transfer`/
    // `prove_preparation` (see `try_prove`'s doc comment). Those require the transaction to be
    // `MigrationTxState::Signed` (`ProveError::NotReady` otherwise) — an UNSIGNED transaction from
    // `build_preparation_unsigned` is `AwaitingSignature`, so it is correctly rejected before ever
    // reaching witness/anchor resolution, not after (a stricter, better safety property than our old
    // stopgap had, but one this test's exact premise can no longer exercise). The witness/anchor
    // resolution logic this test covered now lives in `WalletMigrationProver` (core-team-owned,
    // exercised by its own `zcash_pool_migration_backend/tests/prove_chain_sim.rs`); our own
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
    use zcash_protocol::ShieldedPool;

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
        let signed_pczt = zcash_pool_migration_backend::build::sign_pczt(unsigned_pczt, &ask)
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
/// `zcash_pool_migration_backend::state`, so it is not duplicated here; these tests are only for
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
    /// keeps its own `rusqlite::Connection` private, so this queries the wallet's
    /// `transactions` table directly through the migration store's own second connection to the
    /// same on-disk file — the same raw-query pattern `debugRescheduleTransfersNative` already
    /// uses against this DB.
    fn a_mined_txid_in_fixture(store_conn: &Connection) -> TxId {
        let txid_bytes: [u8; 32] = store_conn
            .query_row(
                "SELECT txid FROM transactions WHERE mined_height IS NOT NULL LIMIT 1",
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
        // `zcash_pool_migration_backend::state`), so an `AwaitingSignature` transaction from
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
