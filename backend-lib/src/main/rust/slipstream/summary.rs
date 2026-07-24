//! `walletSummary` object construction — the phase-resolving balance read (HOSTING.md §7.2),
//! bound to the `SlipstreamWalletSummary` / `SlipstreamAccountBalance` / `SlipstreamPoolBalance`
//! / `SlipstreamScanProgress` Kotlin models (the JNI binding contract §4.2/§4.5). The models
//! live in the `com.zodl.slipstream.model` package, so every descriptor below carries the
//! `model/` segment.
//!
//! The `WalletSummary` → Kotlin getter usage below mirrors the published Android SDK's own
//! summary JNI verbatim (`zcash-android-wallet-sdk@f386369e:backend-lib/src/main/rust/
//! lib.rs:1565-1655`), so the API is verified against the same librustzcash family the AAR
//! links (`zcash_client_backend 0.23`).
//!
//! **E-1 rationing IS ported (mirrors the iOS/macOS reference).** The expensive
//! `get_wallet_summary` walk is served from a handle-owned cache ([`SummaryCacheEntry`]) and
//! re-run only on a range boundary / state change / 2 s idle TTL (never between boundaries
//! while scanning), so hosts may call `walletSummary` every tick. The recovery-balance
//! replacement is intentionally NOT cached — it re-reads the cheap view every call so a
//! recovering host sees the per-tick climb.
//!
//! **Ironwood forward fields** (`SlipstreamAccountBalance.ironwood`,
//! `SlipstreamWalletSummary.nextIronwoodSubtreeIndex`) are populated whenever the linked
//! librustzcash generation is ironwood-capable, wired from `balance.ironwood_balance()` / the
//! summary's `next_ironwood_subtree_index()` (marked inline). They fall back to `null` only for
//! an engine/JNI build that predates ironwood support, and (deliberately) during recovery, where
//! the collapsed net already includes ironwood value (marked inline).

use anyhow::anyhow;
use std::num::NonZeroU32;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JObject, JValue};
use jni::sys::jobject;

use rand::rngs::OsRng;
use zcash_client_backend::data_api::WalletRead;
use zcash_client_backend::data_api::WalletSummary;
use zcash_client_backend::data_api::wallet::ConfirmationsPolicy;
use zcash_client_sqlite::AccountUuid;
use zcash_client_sqlite::WalletDb;
use zcash_client_sqlite::util::SystemClock;
use zcash_protocol::consensus::Network;
use zcash_protocol::value::ZatBalance;

use super::JniSlipstreamHandle;

const JNI_POOL_BALANCE: &str = "com/zodl/slipstream/model/SlipstreamPoolBalance";
const JNI_ACCOUNT_BALANCE: &str = "com/zodl/slipstream/model/SlipstreamAccountBalance";
const JNI_SCAN_PROGRESS: &str = "com/zodl/slipstream/model/SlipstreamScanProgress";
const JNI_WALLET_SUMMARY: &str = "com/zodl/slipstream/model/SlipstreamWalletSummary";

const POOL_BALANCE_CTOR: &str = "(JJJ)V";
const ACCOUNT_BALANCE_CTOR: &str = "([BLcom/zodl/slipstream/model/SlipstreamPoolBalance;Lcom/zodl/slipstream/model/SlipstreamPoolBalance;Lcom/zodl/slipstream/model/SlipstreamPoolBalance;J)V";
const SCAN_PROGRESS_CTOR: &str = "(JJ)V";
const WALLET_SUMMARY_CTOR: &str = "([Lcom/zodl/slipstream/model/SlipstreamAccountBalance;JJLcom/zodl/slipstream/model/SlipstreamScanProgress;Lcom/zodl/slipstream/model/SlipstreamScanProgress;JJLjava/lang/Long;)V";

/// [E-1] One cached upstream wallet summary + the engine facts it was captured under. Refresh
/// triggers: a range boundary (`ranges_completed` moved), a state change, or — outside a scan
/// — the 2 s idle TTL. While Syncing between boundaries the cache is served as-is (the
/// no-walk-while-scanning invariant). `pub(crate)` so the handle in `lib.rs` can hold it.
pub(crate) struct SummaryCacheEntry {
    captured_at: Instant,
    ranges_completed: u64,
    state: u8,
    summary: WalletSummary<AccountUuid>,
}

/// [E-1] Idle refresh TTL — matches the iOS/macOS reference idle/error refetch cadence.
const SUMMARY_IDLE_TTL: Duration = Duration::from_secs(2);

/// Builds a `SlipstreamWalletSummary` jobject, or a null jobject for the C "no balance data
/// yet" sentinel (Kotlin callers see `null`, never `-1`). Called from the `walletSummary`
/// export inside its `catch_unwind`; errors propagate as a thrown `RuntimeException`.
pub(crate) fn wallet_summary_object<'local>(
    env: &mut JNIEnv<'local>,
    handle: &JniSlipstreamHandle,
    trusted: u32,
    untrusted: u32,
    allow_zero_conf_shielding: bool,
) -> anyhow::Result<jobject> {
    let db_path = &handle.inner.wallet_db_path;
    let network = handle.inner.network;
    let snap = handle.inner.snapshot();

    // ── [E-1] Serve-cached + refresh (mirrors the iOS/macOS reference rationing) — the walk
    // is rationed HERE, so hosts may call this every tick:
    //   • no cache yet → ONE synchronous walk (also validates the confirmations policy and
    //     throws on bad input), priming the cache;
    //   • cache present → serve it immediately, and — when the pass crossed a range boundary,
    //     the state changed, or (outside a scan) the 2 s idle TTL elapsed — spawn ONE
    //     background walk (owns only clones + Arcs, so it is safe against `free` racing it)
    //     that swaps the cache for later calls.
    // Between boundaries while Syncing NO walk runs: the no-walk-while-scanning invariant. The
    // recovery-balance REPLACEMENT below is NOT cached — it re-reads the cheap view on every
    // call, so a recovering host sees the per-tick climb.
    let cached: Option<SummaryCacheEntry> = {
        let guard = handle
            .summary_cache
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        guard.as_ref().map(|e| SummaryCacheEntry {
            captured_at: e.captured_at,
            ranges_completed: e.ranges_completed,
            state: e.state,
            summary: e.summary.clone(),
        })
    };

    let summary = match cached {
        None => {
            // First call on this handle: walk synchronously and prime the cache.
            let walked = walk_summary(
                db_path,
                network,
                trusted,
                untrusted,
                allow_zero_conf_shielding,
            )?;
            if let Some(ref s) = walked {
                *handle
                    .summary_cache
                    .lock()
                    .unwrap_or_else(|p| p.into_inner()) = Some(SummaryCacheEntry {
                    captured_at: Instant::now(),
                    ranges_completed: snap.ranges_completed,
                    state: snap.state,
                    summary: s.clone(),
                });
            }
            match walked {
                Some(s) => s,
                None => return Ok(std::ptr::null_mut()),
            }
        }
        Some(entry) => {
            let boundary_crossed =
                snap.ranges_completed != entry.ranges_completed || snap.state != entry.state;
            let idle_ttl_due = snap.state != 1 && entry.captured_at.elapsed() >= SUMMARY_IDLE_TTL;
            if (boundary_crossed || idle_ttl_due)
                && !handle.summary_refresh_inflight.swap(true, Ordering::SeqCst)
            {
                // Spawn ONE background walk; it owns only clones + Arcs, so it is safe against
                // `free()` racing it, and it swaps the cache for later calls.
                let cache = Arc::clone(&handle.summary_cache);
                let inflight = Arc::clone(&handle.summary_refresh_inflight);
                let thread_db_path = db_path.clone();
                let (ranges_at, state_at) = (snap.ranges_completed, snap.state);
                std::thread::spawn(move || {
                    if let Ok(Some(s)) = walk_summary(
                        &thread_db_path,
                        network,
                        trusted,
                        untrusted,
                        allow_zero_conf_shielding,
                    ) {
                        *cache.lock().unwrap_or_else(|p| p.into_inner()) =
                            Some(SummaryCacheEntry {
                                captured_at: Instant::now(),
                                ranges_completed: ranges_at,
                                state: state_at,
                                summary: s,
                            });
                    }
                    inflight.store(false, Ordering::SeqCst);
                });
            }
            entry.summary
        }
    };

    // Phase resolution keyed on the engine's `is_recovering` (its fail-safe latch already
    // applied — terminal states force NOT-recovering).
    let is_recovering = snap.is_recovering == 1;
    let recovery_nets = if is_recovering {
        read_recovery_nets(&handle.inner.wallet_db_path)?
    } else {
        std::collections::HashMap::new()
    };

    // ── per-account balances ────────────────────────────────────────────────────────────
    // NOTE (deferred): this constructs ~5 JNI local refs per account. For many-account
    // wallets a production port should wrap each iteration in `env.with_local_frame` (the
    // array retains each element across the frame pop) to bound the local-reference table.
    let balances: Vec<_> = summary.account_balances().iter().collect();
    let acc_array =
        env.new_object_array(balances.len() as i32, JNI_ACCOUNT_BALANCE, JObject::null())?;

    for (i, (account_uuid, balance)) in balances.into_iter().enumerate() {
        let uuid = account_uuid.expose_uuid();
        let uuid_bytes = uuid.as_bytes();

        let obj = if is_recovering {
            // Direction-B collapse (rust/src/ffi.rs:399): the whole clamped recovery net
            // becomes orchard `spendableValue`, every other component zero; `total()` == net.
            // ironwood stays null here deliberately (not a forward-field gap): the recovery net
            // comes from `slipstream_v_recovery_balance` (core/src/reconcile.rs:96-103), which
            // sums pool-agnostic `v_transactions.account_balance_delta` — ironwood value is
            // already folded into that single collapsed net, so adding an ironwood pool object
            // here would double-count it.
            let net = recovery_nets.get(uuid_bytes).copied().unwrap_or(0);
            let sapling = pool_balance(env, 0, 0, 0)?;
            let orchard = pool_balance(env, net.max(0), 0, 0)?;
            account_balance(env, uuid_bytes, &sapling, &orchard, &JObject::null(), 0)?
        } else {
            let sapling = pool_balance(
                env,
                zat(balance.sapling_balance().spendable_value()),
                zat(balance.sapling_balance().change_pending_confirmation()),
                zat(balance.sapling_balance().value_pending_spendability()),
            )?;
            let orchard = pool_balance(
                env,
                zat(balance.orchard_balance().spendable_value()),
                zat(balance.orchard_balance().change_pending_confirmation()),
                zat(balance.orchard_balance().value_pending_spendability()),
            )?;
            let unshielded = zat(balance.unshielded_balance().total());
            // FORWARD FIELD (§9.2): populated because the linked librustzcash pin is
            // ironwood-capable — `balance.ironwood_balance()` folds `ironwood_received_notes`
            // the same way `lib.rs:1647-1651` (`encode_account_balance`) does for the mainline
            // getWalletSummary JNI. Stays null only for an engine/JNI build that predates
            // ironwood support.
            let ironwood = pool_balance(
                env,
                zat(balance.ironwood_balance().spendable_value()),
                zat(balance.ironwood_balance().change_pending_confirmation()),
                zat(balance.ironwood_balance().value_pending_spendability()),
            )?;
            account_balance(env, uuid_bytes, &sapling, &orchard, &ironwood, unshielded)?
        };
        env.set_object_array_element(&acc_array, i as i32, &obj)?;
    }

    // ── scan / recovery progress ────────────────────────────────────────────────────────
    // Diagnostics only — the blessed UI progress is snapshot.progressPermille.
    let scan = summary.progress().scan();
    let scan_obj = scan_progress(env, *scan.numerator(), *scan.denominator())?;
    let recovery_obj = match summary.progress().recovery() {
        Some(r) => scan_progress(env, *r.numerator(), *r.denominator())?,
        None => JObject::null(),
    };

    // ── heights + subtree indices ───────────────────────────────────────────────────────
    let chain_tip = i64::from(u32::from(summary.chain_tip_height()));
    let fully_scanned = i64::from(u32::from(summary.fully_scanned_height()));
    let next_sapling = summary.next_sapling_subtree_index() as i64;
    let next_orchard = summary.next_orchard_subtree_index() as i64;
    // FORWARD FIELD (§9.2): boxed because the linked librustzcash pin is ironwood-capable —
    // mirrors the `java/lang/Long` boxing `lib.rs:1696-1706` uses for the recovery-progress
    // Option fields. `next_ironwood_subtree_index()` itself is not optional (always a valid
    // index once a summary exists), so it is always boxed, never left null.
    let next_ironwood = env.new_object(
        "java/lang/Long",
        "(J)V",
        &[JValue::Long(summary.next_ironwood_subtree_index() as i64)],
    )?;

    let summary_obj = env.new_object(
        JNI_WALLET_SUMMARY,
        WALLET_SUMMARY_CTOR,
        &[
            // `(&array).into()` (not `JValue::Object`) matches the upstream backend-lib's
            // proven form for the newtype array/byte-array wrappers (lib.rs:1588,1644).
            (&acc_array).into(),
            JValue::Long(chain_tip),
            JValue::Long(fully_scanned),
            JValue::Object(&scan_obj),
            JValue::Object(&recovery_obj),
            JValue::Long(next_sapling),
            JValue::Long(next_orchard),
            JValue::Object(&next_ironwood),
        ],
    )?;
    Ok(summary_obj.into_raw())
}

/// Builds the ZIP-315 confirmations policy from the three flattened scalars (the JNI binding
/// contract §3.9): `(0, 0, _)` selects the defaults `{3, 10, true}`; `trusted == 0 &&
/// untrusted != 0` is a validation error; otherwise `trusted` must be `<= untrusted`.
fn build_policy(
    trusted: u32,
    untrusted: u32,
    allow_zero_conf_shielding: bool,
) -> anyhow::Result<ConfirmationsPolicy> {
    if trusted == 0 && untrusted == 0 {
        Ok(ConfirmationsPolicy::default())
    } else {
        let t = NonZeroU32::new(trusted).ok_or_else(|| {
            anyhow!(
                "trustedConfirmations must be nonzero unless both confirmations are zero (defaults)"
            )
        })?;
        let u = NonZeroU32::new(untrusted)
            .ok_or_else(|| anyhow!("untrustedConfirmations must be nonzero"))?;
        ConfirmationsPolicy::new(t, u, allow_zero_conf_shielding)
            .map_err(|_| anyhow!("trustedConfirmations must be <= untrustedConfirmations"))
    }
}

/// One upstream `get_wallet_summary` walk — the expensive read the E-1 cache rations. Opens a
/// fresh `WalletDb` (same `WalletDb::for_path(path, params, SystemClock, OsRng)` shape as the
/// published Android SDK) and returns the summary, or `None` for "no balance data yet". Takes
/// the raw confirmations scalars (all `Copy`) so it is callable both synchronously and from the
/// background refresh thread.
fn walk_summary(
    db_path: &Path,
    network: Network,
    trusted: u32,
    untrusted: u32,
    allow_zero_conf_shielding: bool,
) -> anyhow::Result<Option<WalletSummary<AccountUuid>>> {
    let policy = build_policy(trusted, untrusted, allow_zero_conf_shielding)?;
    let db = WalletDb::for_path(db_path, network, SystemClock, OsRng)
        .map_err(|e| anyhow!("open wallet db: {e}"))?;
    db.get_wallet_summary(policy)
        .map_err(|e| anyhow!("get_wallet_summary: {e}"))
}

/// A signed zatoshi i64 from any balance component (mirrors backend-lib:
/// `ZatBalance::from(<Zatoshis>).into()`).
fn zat<T>(v: T) -> i64
where
    ZatBalance: From<T>,
{
    i64::from(ZatBalance::from(v))
}

fn pool_balance<'local>(
    env: &mut JNIEnv<'local>,
    spendable: i64,
    change_pending: i64,
    value_pending: i64,
) -> anyhow::Result<JObject<'local>> {
    Ok(env.new_object(
        JNI_POOL_BALANCE,
        POOL_BALANCE_CTOR,
        &[
            JValue::Long(spendable),
            JValue::Long(change_pending),
            JValue::Long(value_pending),
        ],
    )?)
}

fn account_balance<'local>(
    env: &mut JNIEnv<'local>,
    uuid: &[u8; 16],
    sapling: &JObject,
    orchard: &JObject,
    ironwood: &JObject,
    unshielded: i64,
) -> anyhow::Result<JObject<'local>> {
    let uuid_arr = env.byte_array_from_slice(uuid)?;
    Ok(env.new_object(
        JNI_ACCOUNT_BALANCE,
        ACCOUNT_BALANCE_CTOR,
        &[
            // `(&byte_array).into()` — the backend-lib-proven form (lib.rs:1588).
            (&uuid_arr).into(),
            JValue::Object(sapling),
            JValue::Object(orchard),
            JValue::Object(ironwood),
            JValue::Long(unshielded),
        ],
    )?)
}

fn scan_progress<'local>(
    env: &mut JNIEnv<'local>,
    numerator: u64,
    denominator: u64,
) -> anyhow::Result<JObject<'local>> {
    Ok(env.new_object(
        JNI_SCAN_PROGRESS,
        SCAN_PROGRESS_CTOR,
        &[
            JValue::Long(numerator as i64),
            JValue::Long(denominator as i64),
        ],
    )?)
}

/// Reads the engine-owned `slipstream_v_recovery_balance` view into a per-account net map
/// (`account_uuid` blob → Σ fully-reconciled tx deltas). Its own read-only rusqlite
/// connection with `busy_timeout` 5 s (concurrent with the engine writer; HOSTING.md §7.3).
/// Verbatim in behavior from the C-ABI recovery branch (rust/src/lib.rs:5465).
fn read_recovery_nets(
    db_path: &std::path::Path,
) -> anyhow::Result<std::collections::HashMap<[u8; 16], i64>> {
    let conn =
        rusqlite::Connection::open(db_path).map_err(|e| anyhow!("recovery balance open: {e}"))?;
    conn.busy_timeout(std::time::Duration::from_secs(5))
        .map_err(|e| anyhow!("recovery balance busy_timeout: {e}"))?;
    let mut nets: std::collections::HashMap<[u8; 16], i64> = std::collections::HashMap::new();
    let mut stmt = conn
        .prepare("SELECT account_uuid, balance_zat FROM slipstream_v_recovery_balance")
        .map_err(|e| anyhow!("recovery balance prepare: {e}"))?;
    let mut rows = stmt
        .query([])
        .map_err(|e| anyhow!("recovery balance query: {e}"))?;
    while let Some(row) = rows
        .next()
        .map_err(|e| anyhow!("recovery balance row: {e}"))?
    {
        let uuid: Vec<u8> = row
            .get(0)
            .map_err(|e| anyhow!("recovery balance uuid: {e}"))?;
        let net: i64 = row
            .get(1)
            .map_err(|e| anyhow!("recovery balance net: {e}"))?;
        if let Ok(uuid16) = <[u8; 16]>::try_from(uuid.as_slice()) {
            nets.insert(uuid16, net);
        }
    }
    Ok(nets)
}

// Hermetic host tests (no JVM, no database, no new dependencies) for `build_policy`'s pure
// validation logic.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_policy_both_zero_uses_the_zip_315_defaults() {
        let policy = build_policy(0, 0, true).expect("both-zero must succeed with defaults");
        assert_eq!(policy, ConfirmationsPolicy::default());
    }

    #[test]
    fn build_policy_trusted_zero_untrusted_nonzero_is_an_error() {
        assert!(build_policy(0, 5, true).is_err());
    }

    #[test]
    fn build_policy_trusted_above_untrusted_is_an_error() {
        assert!(build_policy(10, 3, true).is_err());
    }
}
