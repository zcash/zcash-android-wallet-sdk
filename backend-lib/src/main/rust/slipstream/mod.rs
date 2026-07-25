//! `slipstream-jni` — the JNI marshalling layer for the Slipstream Zcash sync engine.
//!
//! Every `Java_com_zodl_slipstream_SlipstreamNative_*` export here binds one Slipstream
//! C-ABI function (engine `HOSTING.md` §4) to JNI, calling `slipstream_core::ffi_handle`
//! **directly in Rust** — the same module the C ABI wraps — so the semantics are ONE
//! derivation, byte-identical to iOS/macOS. The documented C ABI stays THE contract;
//! this is a binding of it, function-for-function, snapshot-field-for-field. The full
//! normative spec is the JNI binding contract.
//!
//! ## What crosses the boundary (poll-based, no callbacks)
//! - `snapshot` returns a constructed `SlipstreamSnapshot` (14 fields, `HOSTING.md` §5).
//! - `drainEvents` returns `SlipstreamEvent[]` (the 64-slot ring, drained atomically).
//! - `walletSummary` returns a phase-resolving `SlipstreamWalletSummary` (see `summary.rs`).
//! - `restoreAnchor` returns `SlipstreamRestoreAnchor` (provisioning facts, handle-less).
//! No `repr(C)` layout ever crosses into Kotlin — objects are built field-by-field via
//! `env.new_object`, which is exactly what makes this binding immune to the mid-struct
//! C-ABI break a future engine branch could introduce.
//!
//! ## Threading (HOSTING.md §4)
//! "Never pass one handle to two FFI calls concurrently." Kotlin enforces this with a
//! single dedicated dispatcher thread (`SlipstreamDispatchers.SLIPSTREAM_IO`). This crate
//! assumes that discipline; `start`/`stop` are bounded REAL waits (task-join + writer
//! drain, worst ~20 s) and MUST NOT be called on the Android main thread.
//!
//! ## Status
//! NOT compiled on an Android toolchain; never built via cargo-ndk. Every JNI descriptor
//! string is hand-derived, not `javap`-verified. See `README.md`.
//!
//! ## Version
//! Binds the **v0.6.0** contract surface (9 functions). `set_alternate_servers`
//! (v0.7) is intentionally NOT bound here — a clearly-marked extension stub is at the end
//! of this file. Ironwood-era summary fields are forward-absorbed as nullable Kotlin
//! fields (see `summary.rs`), never as C-struct layout.
//!
//! `host_read` (`host_read.rs`) adds 5 more exports ON TOP of the v0.6.0 contract surface
//! above — typed, handle-less host DB reads (`listTransactions`, `getTransactionRaw`,
//! `listTransactionOutputs`, `findTransactionsByMemo`, `listResubmissionCandidates`) that are
//! `SlipstreamTransactionReader`'s production read path, constructing model objects the same
//! way `snapshot`/`walletSummary` do. Not part of HOSTING.md or the JNI binding contract's
//! v0.6.0 function table; documented alongside it (FFI_JNI_CONTRACT.md §2/§4.2/§9.3).
//!
//! `readQuery` (`read_query.rs`) is the DEBUG-ONLY host DB-read lane that predates
//! `host_read` (worklog `08-engine-sigbus-android.md`) — kept wired for
//! `Synchronizer.debugQuery` only; production reads no longer use it.

mod host_read;
mod read_query;
mod summary;

use std::any::Any;
use std::panic::{self, AssertUnwindSafe, UnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Once};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::anyhow;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobject, jstring};
use prost::Message;
use tokio::runtime::Builder;
use tokio::task::AbortHandle;

use slipstream_core::anchor::{AnchorIntent, offline_anchor, restore_anchor};
use slipstream_core::connector::TorConn;
use slipstream_core::events::Progress;
use slipstream_core::ffi_handle::{
    FfiSlipstreamEvent, SlipstreamHandle as CoreHandle, SyncState, spawn_supervised,
};
use slipstream_core::session::{SessionConfig, SessionReporter, TorSessionConfig, run_session};
use slipstream_core::{AnchorRetention, Endpoint, EngineConfig, Network, ProgressArc};

// The engine's `SessionConfig` now takes pre-parsed accounts; parse the host UFVK encoding here.
use zcash_client_backend::keys::UnifiedFullViewingKey;
use zcash_protocol::consensus::BlockHeight;

// ── JNI class descriptors + constructor signatures (normative; the JNI binding contract §4.2) ──
// Constructor arg ORDER is part of the contract; it must match the `@Keep data class`
// declarations in the `com.zodl.slipstream.model` package exactly — hence the `model/`
// segment in every descriptor below (the classes live under `.../model/`).
const JNI_SNAPSHOT: &str = "com/zodl/slipstream/model/SlipstreamSnapshot";
const JNI_EVENT: &str = "com/zodl/slipstream/model/SlipstreamEvent";
const JNI_RESTORE_ANCHOR: &str = "com/zodl/slipstream/model/SlipstreamRestoreAnchor";
// (JJJJJIJZJZIJZJ)V — 14 fields in HOSTING.md §5 order (long/int/boolean per field width).
const SNAPSHOT_CTOR: &str = "(JJJJJIJZJZIJZJ)V";
const EVENT_CTOR: &str = "(IJ)V";
const RESTORE_ANCHOR_CTOR: &str = "(J[B)V";

/// [E-2] Tip-freshness survives a stop→start hop shorter than this (SDKFlags parity).
const TIP_FRESH_STOP_WINDOW: Duration = Duration::from_secs(120);

// ════════════════════════════════════════════════════════════════════════════════════════
// Exception handling — our own `unwrap_exc_or` twin (modeled on the upstream backend-lib's
// house pattern: `catch_unwind` closure → `anyhow::Result` → RuntimeException on error).
// ════════════════════════════════════════════════════════════════════════════════════════

/// Runs `f`, capturing an unwinding panic instead of letting it cross the FFI boundary
/// (undefined behavior). `&mut JNIEnv` is asserted unwind-safe (jni-rs issue #432): the
/// only observable state on a caught panic is a possibly-pending JVM exception, which
/// `unwrap_exc_or` reconciles.
pub(crate) fn catch_unwind<F, R>(env: &mut JNIEnv, f: F) -> thread::Result<anyhow::Result<R>>
where
    F: FnOnce(&mut JNIEnv) -> anyhow::Result<R> + UnwindSafe,
{
    let mut wrapped_env = AssertUnwindSafe(env);
    panic::catch_unwind({
        let mut inner = AssertUnwindSafe(&mut wrapped_env);
        move || (f)(***inner)
    })
}

/// Pure decision logic for [`unwrap_exc_or`]: given the `catch_unwind` result and whether a JVM
/// exception is already pending, decides the value to return and — when `Some` — the message to
/// throw as a `RuntimeException`. Never touches a `JNIEnv`, so it is unit-testable without a JVM
/// (see the `tests` module below).
fn classify<T>(
    res: thread::Result<anyhow::Result<T>>,
    exception_pending: bool,
    error_val: T,
) -> (T, Option<String>) {
    match res {
        Ok(Ok(val)) => (val, None),
        Ok(Err(err)) => {
            // Do not double-throw: a JNI call inside the closure may already have left a
            // pending exception, which the JVM will raise on return.
            if exception_pending {
                (error_val, None)
            } else {
                (error_val, Some(err.to_string()))
            }
        }
        Err(panic) => {
            // Same double-throw guard as the `Err` arm above: a panic can also unwind past a
            // JNI call that already left a pending exception (e.g. a failed `env.new_object`
            // call inside the closure, unwinding via a later `.expect`/`?`), and re-throwing on
            // top of it would mask the original, more specific exception.
            if exception_pending {
                (error_val, None)
            } else {
                (error_val, Some(any_to_string(&panic)))
            }
        }
    }
}

/// Unwraps a `catch_unwind` result, throwing a `java.lang.RuntimeException` (carrying the
/// error text — this is how the C ABI's `zcashlc_last_error_message` is absorbed) on either
/// an `Err` result or a caught panic, and returning `error_val` in that case. The thrown
/// exception surfaces when the native method returns to the JVM. The decision of whether to
/// throw, and with what message, is [`classify`]'s pure logic; this function is only the
/// `JNIEnv`-touching shell around it.
pub(crate) fn unwrap_exc_or<T>(
    env: &mut JNIEnv,
    res: thread::Result<anyhow::Result<T>>,
    error_val: T,
) -> T {
    let exception_pending = env.exception_check().unwrap_or(false);
    let (value, message) = classify(res, exception_pending, error_val);
    if let Some(message) = message {
        throw_runtime_exception(env, &message);
    }
    value
}

/// Queues a `RuntimeException` to be thrown when control returns to the JVM. Cannot itself
/// throw, so a failure to locate the class is logged rather than propagated.
fn throw_runtime_exception(env: &mut JNIEnv, description: &str) {
    match env.find_class("java/lang/RuntimeException") {
        Ok(class) => {
            if let Err(e) = env.throw_new(class, description) {
                tracing::error!(error = %e, "slipstream-jni: failed to throw RuntimeException");
            }
        }
        Err(e) => tracing::error!(error = %e, "slipstream-jni: RuntimeException class not found"),
    }
}

/// Best-effort description of a panic payload (`&str` / `String` are the common shapes).
fn any_to_string(any: &Box<dyn Any + Send>) -> String {
    if let Some(s) = any.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = any.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}

/// `JString → String`. All boundary strings are UTF-8 (paths, hosts, UFVKs, tor dirs).
pub(crate) fn java_string_to_rust(env: &mut JNIEnv, s: &JString) -> anyhow::Result<String> {
    Ok(env.get_string(s)?.into())
}

/// `JString? → Option<String>` — a null Java reference maps to `None`.
pub(crate) fn java_nullable_string_to_rust(
    env: &mut JNIEnv,
    s: &JString,
) -> anyhow::Result<Option<String>> {
    if s.is_null() {
        Ok(None)
    } else {
        Ok(Some(java_string_to_rust(env, s)?))
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════
// The handle — mirrors the iOS/macOS reference wrapper, MINUS the v0.7 alternate-servers
// state (bound only from AAR 0.7.x). It owns the core engine handle, the FFI-side
// tip-freshness latch (E-2), and the E-1 summary-cache state that rations the expensive
// wallet-summary walk so hosts may call `walletSummary` every tick (see `summary.rs`).
// ════════════════════════════════════════════════════════════════════════════════════════

pub(crate) struct JniSlipstreamHandle {
    /// The engine's own opaque handle: 4-worker tokio runtime + progress atomics + state +
    /// 64-slot event ring + abort handle + pass-lock + endpoint + db path + network.
    pub(crate) inner: CoreHandle,
    /// [E-1] Rationed upstream-summary cache: the expensive `get_wallet_summary` walk runs at
    /// most once per range boundary / state change / 2 s idle TTL, so hosts may call
    /// `walletSummary` every tick. `Arc` because the background refresh thread outlives the
    /// FFI call.
    pub(crate) summary_cache: Arc<Mutex<Option<summary::SummaryCacheEntry>>>,
    /// [E-1] One background summary refresh in flight at a time.
    pub(crate) summary_refresh_inflight: Arc<AtomicBool>,
    /// [E-2] Engine tip-refresh counter captured at the last `start` — a later advance
    /// proves THIS run persisted a freshly-fetched chain tip (`shouldMarkChainTipUpdated`).
    tip_refreshes_at_run_start: AtomicU64,
    /// [E-2] Latched tip-freshness (until a >120 s stop→start gap re-masks it in `start`).
    tip_fresh: AtomicBool,
    /// [E-2] `stop` timestamp — freshness survives a stop→start hop shorter than 120 s.
    last_stop_at: Mutex<Option<Instant>>,
}

impl JniSlipstreamHandle {
    /// [E-2] Lazily evaluates + latches tip freshness (the exact `shouldMarkChainTipUpdated`
    /// semantics the SDK derived host-side; lifted from rust/src/lib.rs:5156):
    /// - already fresh → stays fresh;
    /// - the engine's refresh counter advanced past its `start` baseline (the engine bumps
    ///   it only after `update_chain_tip` succeeds) → fresh — counter-based, so a DB-seeded
    ///   tip can neither fake freshness nor mask a genuine refresh of the same height;
    /// - a pass reached Done (`state == 3`) → fresh (`sync_once` cannot complete without a
    ///   successful `update_chain_tip`).
    fn tip_fresh_now(&self, state: u8) -> bool {
        if self.tip_fresh.load(Ordering::Relaxed) {
            return true;
        }
        let advanced = self.inner.progress.tip_refreshes()
            > self.tip_refreshes_at_run_start.load(Ordering::Relaxed);
        if advanced || state == 3 {
            self.tip_fresh.store(true, Ordering::Relaxed);
            return true;
        }
        false
    }
}

/// Reconstitutes the handle from the `jlong` the Kotlin owner stores (strict-provenance;
/// the TorRuntime pattern the upstream backend-lib uses at their lib.rs:2959,2996).
///
/// # Safety
/// `ptr` must be a value returned by `open` that has not been passed to `free`. The Kotlin
/// single-dispatcher rule (the JNI binding contract §5) makes concurrent use impossible.
unsafe fn handle_from_jlong<'a>(ptr: jlong) -> anyhow::Result<&'a mut JniSlipstreamHandle> {
    unsafe { std::ptr::with_exposed_provenance_mut::<JniSlipstreamHandle>(ptr as usize).as_mut() }
        .ok_or_else(|| anyhow!("slipstream handle is null"))
}

// ════════════════════════════════════════════════════════════════════════════════════════
// Engine-layer helpers — the C-layer-only logic (NOT in slipstream_core), lifted verbatim
// in behavior from rust/src/lib.rs. Tracked in lockstep with the engine tag.
// ════════════════════════════════════════════════════════════════════════════════════════

/// [quiescence] Bounded wait (≤10 s) for an aborted sync task to finish unwinding.
/// `abort()` is ASYNCHRONOUS — the task keeps running until its next await point, so a
/// synchronous in-flight wallet write can land AFTER `abort()` returns. Behavior matches the
/// iOS/macOS reference wrapper. Returns `true` once the task is confirmed finished, `false`
/// if the 10 s deadline was hit first (a warning is still logged in that case).
fn join_aborted_task(task: &AbortHandle) -> bool {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !task.is_finished() {
        if Instant::now() >= deadline {
            tracing::warn!(
                "slipstream stop/start: aborted pass still unwinding after 10 s — proceeding"
            );
            return false;
        }
        thread::sleep(Duration::from_millis(10));
    }
    true
}

/// [drain] Bounded wait (≤10 s) for the engine's detached write-behind commit (a
/// `spawn_blocking` closure `abort()` cannot cancel). Combined with `join_aborted_task`, a
/// returned stop/start means the wallet file is QUIESCENT — the host's next write cannot
/// interleave with an orphan commit. Behavior matches the iOS/macOS reference wrapper. Returns
/// `true` once no wallet writer is in flight, `false` if the 10 s deadline was hit first (a
/// warning is still logged in that case).
fn drain_wallet_writers(progress: &ProgressArc) -> bool {
    let deadline = Instant::now() + Duration::from_secs(10);
    while progress.wallet_writers() > 0 {
        if Instant::now() >= deadline {
            tracing::warn!(
                "slipstream stop/start: in-flight wallet commit still running after 10 s — proceeding (busy_timeouts remain the backstop)"
            );
            return false;
        }
        thread::sleep(Duration::from_millis(10));
    }
    true
}

/// Allocates the handle: a 4-worker tokio runtime, the core handle, and the
/// truthful-from-open snapshot seed. Seed failure degrades to the zero snapshot (truthful
/// for a fresh wallet) — the seed is presentation state and must never fail `open`. Lifted
/// from rust/src/lib.rs:4662.
fn open_handle(
    db_path: &str,
    host: &str,
    port: u16,
    use_tls: bool,
    network_id: u32,
    total_memory_bytes: u64,
) -> anyhow::Result<JniSlipstreamHandle> {
    let db_pathbuf = PathBuf::from(db_path);
    // networkId: 1 = mainnet, 0 = testnet (any other value → testnet; the adapter passes
    // only 0 or 1).
    let network = if network_id == 1 {
        Network::MainNetwork
    } else {
        Network::TestNetwork
    };

    let runtime = Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .map_err(|e| anyhow!("tokio runtime: {e}"))?;

    let inner = CoreHandle {
        runtime,
        progress: Arc::new(Progress::default()),
        state: Arc::new(Mutex::new(SyncState::Idle)),
        events: Arc::new(Mutex::new(Vec::new())),
        task: None,
        pass_lock: Arc::new(tokio::sync::Mutex::new(())),
        endpoint: Endpoint {
            host: host.to_string(),
            port,
            tls: use_tls,
        },
        wallet_db_path: db_pathbuf.clone(),
        network,
        total_memory_bytes,
    };

    // [E-3] Truthful-from-open seed: fill the progress atomics from the persisted wallet so
    // a pre-pass snapshot never lies (correct `is_recovering` on a mid-restore relaunch, the
    // permille floor holds a 99%-synced wallet's real position, `chain_tip` reports the last
    // persisted tip). The host runs DB create + migrations before `open`, so this cannot race
    // wallet creation.
    match slipstream_core::wallet_session::WalletSession::open(network, &db_pathbuf) {
        Ok(session) => {
            if let Err(e) =
                slipstream_core::scheduler::seed_progress_from_wallet(&inner.progress, &session)
            {
                tracing::warn!(error = %e, "open-time snapshot seed failed — snapshot starts cold");
            }
        }
        Err(e) => {
            tracing::warn!(error = %e, "open-time seed skipped (wallet not openable) — snapshot starts cold");
        }
    }
    tracing::info!(total_memory_bytes, "slipstream handle opened");

    Ok(JniSlipstreamHandle {
        inner,
        summary_cache: Arc::new(Mutex::new(None)),
        summary_refresh_inflight: Arc::new(AtomicBool::new(false)),
        tip_refreshes_at_run_start: AtomicU64::new(0),
        tip_fresh: AtomicBool::new(false),
        last_stop_at: Mutex::new(None),
    })
}

/// [MOB-1455 fix] The lowest `anchor_boundary` height any account's still-pending Orchard→
/// Ironwood migration transfer needs protected from Slipstream's ordinary checkpoint pruning,
/// or `None` if nothing needs protecting. Fed into `EngineConfig::anchor_retention_height` at
/// every session (re)start (`start_session`, below): without it, `zcash_client_backend`'s
/// block-persistence path prunes commitment-tree checkpoints past `PRUNING_DEPTH` (100 blocks)
/// unconditionally, including one a not-yet-proved migration transfer is anchored to (drawn once
/// at scheduling time, ZIP 374) — so ordinary sync can silently prune the very checkpoint the
/// SDK's `finalizeReadyTransfersNative` needs, potentially days later, to prove that transfer,
/// failing with `commitment-tree query failed: Query(NotContained(..))`.
///
/// A transaction counts only if its `anchor_boundary` is set (`NULL` for a preparation
/// transaction, which anchors to a freshly current tip at prove time instead of a boundary drawn
/// in advance — see the SDK repo's `migration.rs::natural_anchor_height` doc comment) AND its
/// `state` is not yet `broadcast`/`mined` (those no longer need their original anchor's
/// checkpoint kept around). `status` excludes `complete`/`failed` migrations (terminal; nothing
/// left to protect). See `zcash_client_sqlite::pool_migration::store`'s DDL builders
/// (`create_migrations_sql`/`create_transactions_sql`) for the column set this mirrors.
///
/// ## Why raw SQL instead of calling into `backend-lib`'s typed `migration.rs`
/// The obvious-looking alternative — calling `migration.rs`'s `Backend`/`PoolMigrationRead` API
/// (typed `MigrationState`/`MigrationTxState`, no ad-hoc SQL) — is not just less convenient here,
/// it is IMPOSSIBLE: `backend-lib`'s own `Cargo.toml` already depends on this crate
/// (`slipstream-jni = { path = "slipstream-jni" }`, so `backend-lib` can re-export/link this
/// crate's JNI symbols into one merged `libzcashlc.so` — see this crate's module doc). Adding a
/// dependency in the other direction would make `slipstream-jni` depend on `backend-lib` depend
/// on `slipstream-jni`: a cyclic package dependency, which Cargo rejects regardless of the two
/// crates' separate `[workspace]` roots. Raw SQL also keeps this crate from having to link
/// `zcash_pool_migration`/`orchard` at all, which it otherwise has no reason to depend
/// on.
///
/// Reads via `read_query::open_read_only` (the shared bundled-SQLite-instance path — see that
/// module's doc comment for the dual-SQLite-instance/SIGBUS hazard it avoids), never a second,
/// independent `rusqlite::Connection::open`.
///
/// Deliberately returns `anyhow::Result` rather than swallowing errors internally: this function
/// has no JNI boundary of its own, so the CALLER (`start_session`) is responsible for treating
/// any `Err` as "no retention floor" (log + fall back to `None`) — a wallet DB read glitch here
/// must never block a sync session from starting. The overwhelmingly common case (no migration
/// ever attempted in this wallet) resolves via the `Ok(None)` arm below (aggregate `MIN` over
/// zero matching rows is SQL `NULL`, not a query error) or the "no such table" arm on a wallet
/// that predates the migration schema entirely — neither should ever be logged as a failure.
fn min_pending_migration_anchor_boundary(db_path: &str) -> anyhow::Result<Option<u32>> {
    let conn = read_query::open_read_only(db_path)?;
    let boundary = conn.query_row(
        "SELECT MIN(t.anchor_boundary) \
         FROM orchard_ironwood_migration_transactions t \
         JOIN orchard_ironwood_migrations m ON m.id = t.migration_id \
         WHERE m.status NOT IN ('complete', 'failed') \
           AND t.state NOT IN ('broadcast', 'mined') \
           AND t.anchor_boundary IS NOT NULL",
        [],
        |row| row.get::<_, Option<i64>>(0),
    );
    match boundary {
        Ok(Some(h)) => {
            Ok(Some(u32::try_from(h).map_err(|_| {
                anyhow!("anchor_boundary {h} out of u32 range")
            })?))
        }
        Ok(None) => Ok(None),
        // A wallet that has never attempted a migration doesn't have these tables at all — not
        // an error, just nothing to protect.
        Err(rusqlite::Error::SqliteFailure(_, Some(ref msg))) if msg.contains("no such table") => {
            Ok(None)
        }
        Err(e) => Err(anyhow!("reading pending migration anchor boundary: {e}")),
    }
}

/// Spawns a sync session (initial pass → follow + mempool). Aborts any in-flight pass,
/// performs the quiescence drains, then spawns `run_session` under the panic
/// supervisor. Behavior matches the iOS/macOS reference wrapper.
fn start_session(
    handle: &mut JniSlipstreamHandle,
    ufvk: Option<String>,
    birthday: u64,
    tor_dir: Option<String>,
) -> anyhow::Result<()> {
    // [E-2] Capture the tip-refresh baseline BEFORE the pass; a >120 s stop→start gap
    // re-masks freshness until the new pass proves the tip again.
    let refreshes_now = handle.inner.progress.tip_refreshes();
    handle
        .tip_refreshes_at_run_start
        .store(refreshes_now, Ordering::Relaxed);
    let stale_stop = handle
        .last_stop_at
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .map(|t| t.elapsed() >= TIP_FRESH_STOP_WINDOW)
        .unwrap_or(false);
    if stale_stop {
        handle.tip_fresh.store(false, Ordering::Relaxed);
    }

    let h = &mut handle.inner;

    // Cancel any in-flight task, then wait for quiescence (task unwind + detached writer),
    // so the new pass's first writes never collide with an orphan commit.
    if let Some(task) = h.task.take() {
        task.abort();
        join_aborted_task(&task);
    }
    drain_wallet_writers(&h.progress);
    *h.state.lock().unwrap_or_else(|p| p.into_inner()) = SyncState::Syncing;

    // v0.6.0 engine config. `scaled_for_device_memory` derates fetch/split budgets on
    // <3 GiB devices from the open-time RAM hint (0 = unknown → defaults). The engine's
    // internal performance tuning keeps its production defaults (owned by the engine, not
    // exposed here). The v0.7-only config surface is intentionally NOT set — this is the
    // v0.6.0 host recipe.
    let mut cfg = EngineConfig::new(h.network, h.wallet_db_path.clone(), h.endpoint.clone())
        .scaled_for_device_memory(h.total_memory_bytes);

    // [MOB-1455 fix] Protect the checkpoint(s) any not-yet-broadcast migration transfer is
    // anchored to from this session's own checkpoint pruning — see
    // `min_pending_migration_anchor_boundary`'s doc comment for the full rationale (including
    // why this queries raw SQL instead of calling into backend-lib's migration.rs). A lookup
    // failure must never block sync from starting: fall back to no retention floor (the
    // pre-fix behavior) and log loudly enough to notice, since silently reverting to "no
    // protection" is itself worth knowing about.
    let anchor_retention_floor = h
        .wallet_db_path
        .to_str()
        .ok_or_else(|| anyhow!("wallet_db_path is not valid UTF-8"))
        .and_then(min_pending_migration_anchor_boundary)
        .unwrap_or_else(|e| {
            tracing::warn!(
                error = %e,
                "anchor-retention floor lookup failed — starting sync without one"
            );
            None
        });
    // The grid must be the one this crate configures on the wallet (see
    // `crate::anchor_retention_interval`): the engine runs its own tree-update path, so a
    // boundary it does not retain is one a migration transfer cannot later be proved against.
    cfg.anchor_retention = anchor_retention_floor.map(|floor| {
        AnchorRetention::new(
            BlockHeight::from(floor),
            crate::anchor_retention_interval(zcash_protocol::consensus::Parameters::network_type(
                &h.network,
            )),
        )
    });

    // `ufvk` present = view-only import on the first pass (keyless — a UFVK is viewing
    // capability, never a spending key); absent = an account must already exist. The engine's
    // session API takes a list of pre-parsed `(UnifiedFullViewingKey, birthday)` accounts to
    // bootstrap on the initial pass (empty = import nothing); the host encoding is parsed here
    // rather than inside the engine.
    let accounts: Vec<(UnifiedFullViewingKey, BlockHeight)> = match ufvk {
        Some(s) => {
            let ufvk = UnifiedFullViewingKey::decode(&h.network, &s)
                .map_err(|e| anyhow!("Invalid UFVK for Slipstream session import: {e}"))?;
            vec![(ufvk, BlockHeight::from(birthday as u32))]
        }
        None => Vec::new(),
    };

    // Engine-owned Tor. On Android `dangerously_trust_everyone` MUST be `false`
    // (the JNI binding contract §3.0 delta #2 / §11 — the iOS C layer sets it via
    // `cfg!(target_os = "ios")`, which is false here regardless; set explicitly).
    let tor = tor_dir.map(|dir| TorSessionConfig {
        dir: PathBuf::from(dir),
        dangerously_trust_everyone: false,
    });

    let session_config = SessionConfig {
        engine: cfg,
        accounts,
        tor,
    };
    let reporter = SessionReporter {
        progress: Arc::clone(&h.progress),
        state: Arc::clone(&h.state),
        events: Arc::clone(&h.events),
    };

    // Supervised spawn: a panic in the session body becomes SyncState::Error(2) + a
    // tag-4/value-2 event, never a silent hang stuck at "Syncing" (rust/src/lib.rs:5002).
    let sup_state = Arc::clone(&h.state);
    let sup_events = Arc::clone(&h.events);
    h.task = Some(spawn_supervised(
        &h.runtime,
        run_session(session_config, reporter, Arc::clone(&h.pass_lock)),
        sup_state,
        sup_events,
    ));
    Ok(())
}

/// Process-global init: panic hook + `tracing` → logcat (Android) / stderr (host). Idempotent
/// (the C twin panics on a second call; JNI MUST NOT — Kotlin `ensureLoaded` may call it more
/// than once across class reloads).
fn init_runtime(level: &str) {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        // Report every Rust panic through `tracing` before delegating to the previous hook.
        let previous = panic::take_hook();
        panic::set_hook(Box::new(move |info| {
            tracing::error!(panic = %info, "rust panic (slipstream-jni)");
            previous(info);
        }));

        // Per-target filter: cap zcash_client_backend at WARN (a measured sync-throughput
        // fix inherited from the upstream backend-lib), everything else at `level`.
        let directives = format!("{level},zcash_client_backend=warn");

        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::layer::SubscriberExt;
            use tracing_subscriber::util::SubscriberInitExt;
            match tracing_android::layer("com.zodl.slipstream") {
                Ok(layer) => {
                    let filter = tracing_subscriber::EnvFilter::new(&directives);
                    let _ = tracing_subscriber::registry()
                        .with(filter)
                        .with(layer)
                        .try_init();
                }
                Err(_) => {
                    let filter = tracing_subscriber::EnvFilter::new(&directives);
                    let _ = tracing_subscriber::fmt().with_env_filter(filter).try_init();
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            let filter = tracing_subscriber::EnvFilter::new(&directives);
            let _ = tracing_subscriber::fmt().with_env_filter(filter).try_init();
        }
    });
}

// ════════════════════════════════════════════════════════════════════════════════════════
// JNI exports — one per bound C-ABI function. Every body is `catch_unwind` → closure
// returning `anyhow::Result<T>` → `unwrap_exc_or(env, res, <sentinel>)`.
// Sentinels: open → -1 (jlong); start/stop/notify → JNI_FALSE; snapshot/drain/summary/
// restore → null (jobject); free/init → ().
// ════════════════════════════════════════════════════════════════════════════════════════

/// Process-global native init (logging, panic hook). No C-twin call from Kotlin; runs first
/// via `SlipstreamNative.ensureLoaded`. `log_level`: error|warn|info|debug|trace|off.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_initOnLoad<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    log_level: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let level = java_string_to_rust(env, &log_level)?;
        init_runtime(&level);
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Build stamp: `slipstream-android <aar-version> (engine <tag>)`. Additive; no C twin. The
/// AAR version and engine tag are injected by the cargo-ndk release build via the
/// `SLIPSTREAM_AAR_VERSION` / `SLIPSTREAM_ENGINE_TAG` env vars; a plain host `cargo` build
/// falls back to this crate's version and the pinned engine tag.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_version<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let stamp = format!(
            "slipstream-android {} (engine {})",
            option_env!("SLIPSTREAM_AAR_VERSION").unwrap_or(env!("CARGO_PKG_VERSION")),
            option_env!("SLIPSTREAM_ENGINE_TAG").unwrap_or("v0.6.0"),
        );
        Ok(env.new_string(stamp)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `open` (HOSTING.md §4 row 1): allocate the handle (runtime + atomics + event ring), run
/// `data.db` migrations, install the read views, seed the snapshot from persisted state.
/// Returns the handle pointer as a `jlong`, or throws on failure (never returns 0/-1 on
/// success). The handle pattern (strict-provenance raw pointer in a jlong) is normative for
/// every handle-taking export.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_open<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    server_host: JString<'local>,
    server_port: jint,
    use_tls: jboolean,
    network_id: jint,
    total_memory_bytes: jlong,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let host = java_string_to_rust(env, &server_host)?;
        let port = u16::try_from(server_port).map_err(|_| anyhow!("server_port out of range"))?;
        let mem = u64::try_from(total_memory_bytes).unwrap_or(0);
        let handle = open_handle(&db_path, &host, port, use_tls != 0, network_id as u32, mem)?;
        Ok(Box::into_raw(Box::new(handle)).expose_provenance() as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// `start` (HOSTING.md §4 row 2). `ufvk` null = keyless (an account must already exist,
/// `birthdayHeight` ignored); non-null = view-only UFVK import on the first pass. `torDir`
/// non-null = a DEDICATED engine Tor state dir (never shared with the app's TorClient).
/// Bounded real wait, worst ~20 s — Slipstream dispatcher only, never the main thread.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_start<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
    ufvk: JString<'local>,
    birthday_height: jlong,
    tor_dir: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let ufvk = java_nullable_string_to_rust(env, &ufvk)?;
        let tor_dir = java_nullable_string_to_rust(env, &tor_dir)?;
        let birthday = u64::try_from(birthday_height).unwrap_or(0);
        let h = unsafe { handle_from_jlong(handle) }?;
        start_session(h, ufvk, birthday, tor_dir)?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// `stop`: cancel the sync task and perform the bounded join + writer-drain. Sets state Idle;
/// stamps the 120 s tip-fresh window. The handle stays usable (snapshot/drain still work).
///
/// Returns `JNI_TRUE` only if BOTH the task join and the writer drain confirmed quiescence
/// within their 10 s deadlines; `JNI_FALSE` if either timed out, meaning the wallet file may
/// not actually be quiescent yet (a warning is logged for each timeout that occurs).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_stop<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |_env| {
        let h = unsafe { handle_from_jlong(handle) }?;
        // [E-2] Stamp the stop: a start() within 120 s keeps tip freshness.
        *h.last_stop_at.lock().unwrap_or_else(|p| p.into_inner()) = Some(Instant::now());
        let inner = &mut h.inner;
        let mut quiescent = true;
        if let Some(task) = inner.task.take() {
            task.abort();
            quiescent &= join_aborted_task(&task);
        }
        quiescent &= drain_wallet_writers(&inner.progress);
        *inner.state.lock().unwrap_or_else(|p| p.into_inner()) = SyncState::Idle;
        Ok(if quiescent { JNI_TRUE } else { JNI_FALSE })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// `snapshot` (HOSTING.md §5) — THE poll read. Cheap, non-blocking, call every tick. Builds
/// the 14-field `SlipstreamSnapshot` from the core snapshot (13 fields) plus the FFI-side
/// `tip_fresh` latch. Truthful from open — hosts MUST NOT compensate/smooth/re-derive.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_snapshot<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let h = unsafe { handle_from_jlong(handle) }?;
        let s = h.inner.snapshot();
        let tip_fresh = h.tip_fresh_now(s.state);
        let obj = env.new_object(
            JNI_SNAPSHOT,
            SNAPSHOT_CTOR,
            &[
                JValue::Long(s.chain_tip as i64),
                JValue::Long(s.fetched_blocks as i64),
                JValue::Long(s.scanned_blocks as i64),
                JValue::Long(s.enhanced_txs as i64),
                JValue::Long(s.current_range_end as i64),
                JValue::Int(i32::from(s.state)),
                JValue::Long(s.pass_total_blocks as i64),
                JValue::Bool(s.spendable_hint),
                JValue::Long(s.ranges_completed as i64),
                JValue::Bool(s.is_recovering),
                JValue::Int(i32::from(s.progress_permille)),
                JValue::Long(i64::from(s.stalled_seconds)),
                JValue::Bool(u8::from(tip_fresh)),
                JValue::Long(s.tx_set_version as i64),
            ],
        )?;
        Ok(obj.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `drainEvents` — shape-adapted (the JNI binding contract §3.8): the C caller-buffer idiom does
/// not survive JNI, so the ENTIRE ring is drained atomically and returned as
/// `SlipstreamEvent[]` (bounded at 64). Semantics-preserving — C hosts drain `buf_len = 64`
/// per tick anyway. MUST be called every tick even if ignored, so the ring never overflows.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_drainEvents<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let h = unsafe { handle_from_jlong(handle) }?;
        let drained: Vec<FfiSlipstreamEvent> = {
            let mut ring = h.inner.events.lock().unwrap_or_else(|p| p.into_inner());
            ring.drain(..).collect()
        };
        let arr = env.new_object_array(drained.len() as jint, JNI_EVENT, JObject::null())?;
        for (i, ev) in drained.iter().enumerate() {
            let obj = env.new_object(
                JNI_EVENT,
                EVENT_CTOR,
                &[
                    JValue::Int(i32::from(ev.tag)),
                    JValue::Long(ev.value as i64),
                ],
            )?;
            env.set_object_array_element(&arr, i as jint, &obj)?;
        }
        Ok(arr.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `walletSummary` (HOSTING.md §7.2) — THE phase-resolving balance read. Object construction
/// lives in `summary.rs`. Returns a `SlipstreamWalletSummary`, or Kotlin `null` for the C
/// "no balance data yet" sentinel. `(0,0,_)` selects the ZIP-315 defaults; `trusted == 0 &&
/// untrusted != 0` is a validation error → exception.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_walletSummary<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
    trusted_confirmations: jint,
    untrusted_confirmations: jint,
    allow_zero_conf_shielding: jboolean,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let h = unsafe { handle_from_jlong(handle) }?;
        let trusted = u32::try_from(trusted_confirmations)
            .map_err(|_| anyhow!("trustedConfirmations out of range"))?;
        let untrusted = u32::try_from(untrusted_confirmations)
            .map_err(|_| anyhow!("untrustedConfirmations out of range"))?;
        summary::wallet_summary_object(env, h, trusted, untrusted, allow_zero_conf_shielding != 0)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `notifyTxChange`: host poke after the HOST stored a just-broadcast transaction. Bumps
/// `tx_set_version` (the loss-proof, snapshot-carried signal) and pushes a tag-5 event so the
/// host's own poll loop picks up its own write uniformly.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_notifyTxChange<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |_env| {
        let h = unsafe { handle_from_jlong(handle) }?;
        h.inner.progress.bump_tx_set_version();
        h.inner.push_event(FfiSlipstreamEvent { tag: 5, value: 0 });
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// `restoreAnchor` (HOSTING.md §8) — handle-less wallet-provisioning facts. Creates a
/// short-lived 2-worker runtime and blocks for one network round-trip. `intent`: 1 = restore
/// (returns `recover_until`; treestate null), 0 = new (returns the reorg-safe tree state).
/// Tor-or-offline law: a requested-but-failed Tor bootstrap resolves OFFLINE, never a silent
/// direct retry. The treestate is copied into a `jbyteArray` in-call (the C free fn is
/// absorbed). Any background thread (no handle to serialize); never the main thread.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_restoreAnchor<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    server_host: JString<'local>,
    server_port: jint,
    use_tls: jboolean,
    network_id: jint,
    intent: jint,
    birthday_height: jlong,
    fallback_checkpoint_height: jlong,
    tor_dir: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let host = java_string_to_rust(env, &server_host)?;
        let tor_dir = java_nullable_string_to_rust(env, &tor_dir)?;
        let port = u16::try_from(server_port).map_err(|_| anyhow!("server_port out of range"))?;
        let birthday = u64::try_from(birthday_height).unwrap_or(0);
        let fallback = u64::try_from(fallback_checkpoint_height).unwrap_or(0);
        let network = if network_id as u32 == 1 {
            Network::MainNetwork
        } else {
            Network::TestNetwork
        };
        let intent = if intent == 1 {
            AnchorIntent::Restore {
                birthday,
                fallback_checkpoint: fallback,
            }
        } else {
            AnchorIntent::New
        };
        let endpoint = Endpoint {
            host,
            port,
            tls: use_tls != 0,
        };
        let tor_dir_opt = tor_dir.map(PathBuf::from);

        let runtime = Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| anyhow!("tokio runtime: {e}"))?;

        let anchor = runtime.block_on(async {
            let tor_conn = match &tor_dir_opt {
                Some(dir) => match TorConn::bootstrap(dir, false).await {
                    Ok(t) => Some(t),
                    // Requested-but-failed Tor resolves OFFLINE (never a de-anonymising
                    // direct retry) — matches the app's TOR_NOT_AVAILABLE posture.
                    Err(e) => {
                        tracing::warn!(error = %e, "restore anchor: Tor bootstrap failed — resolving OFFLINE");
                        return offline_anchor(intent);
                    }
                },
                None => None,
            };
            // Monorepo `restore_anchor` takes `SlipstreamNetwork`; `Network` converts via
            // `From<Network>` (network.rs). `EngineConfig::new` / `WalletSession::open` take
            // `impl Into<SlipstreamNetwork>` and accept a bare `Network`, so only this call
            // needs the explicit `.into()`.
            restore_anchor(&endpoint, network.into(), intent, tor_conn.as_ref()).await
        });

        // Copy the treestate into a jbyteArray (or null); the Rust Vec is dropped here.
        let treestate_obj: JObject = match anchor.treestate {
            Some(ts) => env.byte_array_from_slice(&ts.encode_to_vec())?.into(),
            None => JObject::null(),
        };
        let obj = env.new_object(
            JNI_RESTORE_ANCHOR,
            RESTORE_ANCHOR_CTOR,
            &[
                JValue::Long(anchor.height as i64),
                JValue::Object(&treestate_obj),
            ],
        )?;
        Ok(obj.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

// ════════════════════════════════════════════════════════════════════════════════════════
// DEBUG-ONLY host-utility export — NOT part of the v0.6.0 C-ABI contract (no C-layer twin, no
// handle, no engine state; handle-less like `restoreAnchor` above). Added while the engine
// owner is away to close the dual-SQLite-instance hazard: see `read_query.rs`'s module doc
// and worklog `08-engine-sigbus-android.md` for the SIGBUS this replaces. Production reads now
// go through the 5 typed exports in `host_read.rs`; this one stays wired for
// `Synchronizer.debugQuery` only.
// ════════════════════════════════════════════════════════════════════════════════════════

/// `readQuery` (debug lane only — see `read_query.rs`'s module doc): runs one read-only
/// SELECT against `dbPath` on the SAME bundled SQLite instance the engine's own writer uses
/// (never the Android-framework SQLite), with at most one of `blobParam`/`textParam` non-null
/// bound as the statement's sole `?1` parameter. Returns the rows as a JSON array of arrays
/// (`read_query`'s column encoding), or Kotlin `null` on error (an exception is also thrown).
/// The host MUST bind, never concatenate, any user-influenced text into `sql`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_readQuery<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    sql: JString<'local>,
    blob_param: JByteArray<'local>,
    text_param: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let db_path = java_string_to_rust(env, &db_path)?;
        let sql_text = java_string_to_rust(env, &sql)?;
        let blob: Option<Vec<u8>> = if blob_param.is_null() {
            None
        } else {
            Some(env.convert_byte_array(&blob_param)?)
        };
        let text = java_nullable_string_to_rust(env, &text_param)?;
        let json = read_query::read_query(&db_path, &sql_text, blob.as_deref(), text.as_deref())?;
        Ok(env.new_string(json)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// `free`: cancel everything and drop the runtime. The task is aborted BEFORE the runtime is
/// dropped (dropping a Runtime with live tasks panics on some platforms). After this the
/// `jlong` is dangling — the Kotlin owner MUST zero it immediately and route `free` through
/// the same dispatcher as every other call.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_free<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(&mut env, |_env| {
        if handle != 0 {
            let mut boxed = unsafe {
                Box::from_raw(
                    std::ptr::with_exposed_provenance_mut::<JniSlipstreamHandle>(handle as usize),
                )
            };
            if let Some(task) = boxed.inner.task.take() {
                task.abort();
            }
            drop(boxed);
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

// Hermetic host tests (no JVM, no new dependencies) for the pure error-classification logic
// above. `classify` is exercised directly; `unwrap_exc_or` itself is not (it needs a live
// `JNIEnv`), but it is a thin, untested-on-purpose shell around `classify`.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_ok_returns_value_and_no_message() {
        let res: thread::Result<anyhow::Result<i32>> = Ok(Ok(42));
        let (value, message) = classify(res, false, -1);
        assert_eq!(value, 42);
        assert!(message.is_none());
    }

    #[test]
    fn classify_err_with_pending_exception_is_suppressed() {
        let res: thread::Result<anyhow::Result<i32>> = Ok(Err(anyhow!("boom")));
        let (value, message) = classify(res, true, -1);
        assert_eq!(value, -1);
        assert!(message.is_none());
    }

    #[test]
    fn classify_err_without_pending_exception_throws_its_message() {
        let res: thread::Result<anyhow::Result<i32>> = Ok(Err(anyhow!("boom")));
        let (value, message) = classify(res, false, -1);
        assert_eq!(value, -1);
        assert_eq!(message.as_deref(), Some("boom"));
    }

    #[test]
    fn classify_panic_with_pending_exception_is_suppressed() {
        // Locks in the M9 fix: a panic must be suppressed exactly like an `Err`, not
        // unconditionally re-thrown, when a JNI exception is already pending.
        let res: thread::Result<anyhow::Result<i32>> =
            panic::catch_unwind(|| -> anyhow::Result<i32> { panic!("boom") });
        let (value, message) = classify(res, true, -1);
        assert_eq!(value, -1);
        assert!(message.is_none());
    }

    #[test]
    fn classify_panic_without_pending_exception_throws_payload_message() {
        let res: thread::Result<anyhow::Result<i32>> =
            panic::catch_unwind(|| -> anyhow::Result<i32> { panic!("boom") });
        let (value, message) = classify(res, false, -1);
        assert_eq!(value, -1);
        assert_eq!(message.as_deref(), Some("boom"));
    }

    #[test]
    fn any_to_string_str_payload() {
        let payload = panic::catch_unwind(|| -> () { panic!("boom") }).unwrap_err();
        assert_eq!(any_to_string(&payload), "boom");
    }

    #[test]
    fn any_to_string_string_payload() {
        let payload = panic::catch_unwind(|| -> () { panic!("boom {}", "formatted") }).unwrap_err();
        assert_eq!(any_to_string(&payload), "boom formatted");
    }

    #[test]
    fn any_to_string_non_string_payload() {
        let payload = panic::catch_unwind(|| std::panic::panic_any(42u8)).unwrap_err();
        assert_eq!(any_to_string(&payload), "unknown panic payload");
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════
// v0.7 EXTENSION STUB (NOT bound at v0.6.0) — `setAlternateServers`
// ════════════════════════════════════════════════════════════════════════════════════════
// The engine C ABI gains `zcashlc_slipstream_set_alternate_servers(handle, uris, uris_len)`
// at v0.7 (probe-then-commit + mid-pass wire failover; production-proven in the Mac store
// build). This binding targets v0.6.0, so it is intentionally UNBOUND here. When the AAR is
// built from an engine tag ≥ v0.7, add BOTH:
//
//   1. the Kotlin declaration (already specified in the JNI binding contract §3.4):
//        @JvmStatic external fun setAlternateServers(handle: Long, urisNewlineSeparated: String?): Boolean
//
//   2. the export below (uncomment; the v0.7 core adds `Endpoint::parse_uri` and the
//      `EngineConfig.alternate_endpoints` / `wire_failover` fields that `start_session` then
//      merges deduped against the primary):
//
// #[unsafe(no_mangle)]
// pub extern "C" fn Java_com_zodl_slipstream_SlipstreamNative_setAlternateServers<'local>(
//     mut env: JNIEnv<'local>,
//     _: JClass<'local>,
//     handle: jlong,
//     uris_newline_separated: JString<'local>,
// ) -> jboolean {
//     let res = catch_unwind(&mut env, |env| {
//         let uris = java_nullable_string_to_rust(env, &uris_newline_separated)?;
//         let h = unsafe { handle_from_jlong(handle) }?;
//         // Parse newline-separated http(s)://host:port (blank lines ignored; null clears);
//         // all-or-nothing; store on the handle for the next start() to merge. Requires the
//         // v0.7 handle to carry an `alternate_servers: Mutex<Vec<Endpoint>>` field and
//         // `Endpoint::parse_uri` (both absent at v0.6.0). See rust/src/lib.rs:4770.
//         let _ = (uris, h);
//         Ok(JNI_TRUE)
//     });
//     unwrap_exc_or(&mut env, res, JNI_FALSE)
// }
