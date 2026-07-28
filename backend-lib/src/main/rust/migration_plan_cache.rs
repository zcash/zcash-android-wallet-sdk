//! In-process cache of the most recent `MigrationPlan` per account, bridging the gap between
//! `plan_migration()` (a pure, unpersisted preview — see its doc comment) and the commit
//! functions (`commit_preparation`/`build_preparation_unsigned`) that need that exact same plan
//! value later.
//!
//! Every cached plan is identified by an opaque, randomly drawn [`PlanHandle`], returned to the
//! caller alongside the plan and carried through the JNI proposal objects
//! (`JniNoteSplitProposal.proposalHandle`/`JniMigrationSchedule.proposalHandle`). A commit call
//! passes the handle back, and [`get`] refuses to release a plan under any other handle — so a
//! commit can only ever sign the exact plan the caller was shown, never one that a later
//! `propose*`/`prepare*` call happened to cache in the meantime (ZIP 318's scheduling draws fresh
//! randomness on every `plan_migration()` call, so two plans essentially never agree even with
//! unchanged wallet state; without the handle gate this was a real sign-what-the-user-never-saw
//! hazard).
//!
//! Unlike this file's neighbors, this is NOT backed by the wallet SQLite database: the new
//! engine's `MigrationPlan` (and its `DenominationPlan`/`PreparationPlan` fields) has no `serde`
//! support and no public constructor — the only way to obtain one is calling `plan_migration()`
//! itself (verified directly against `zcash_pool_migration`'s source, not assumed), so it
//! can't be round-tripped through our own persistence the way `migration_finalize`'s proven-pczt
//! cache was. Instead this holds it in memory, in a process-lifetime static — valid because the
//! app's entire "review a migration proposal, then confirm/sign it" flow happens on one screen, in
//! one app-process lifetime (confirmed with the user), never across a process restart.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use rand::RngCore;
use rand::rngs::OsRng;
use zcash_client_sqlite::AccountUuid;
use zcash_pool_migration::engine::MigrationPlan;

/// Opaque identifier of one cached [`MigrationPlan`]. Drawn fresh (randomly) for every plan, so a
/// handle from an earlier proposal can never accidentally match a later one.
pub type PlanHandle = u64;

/// Why [`get`] could not release a plan for the requested handle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlanLookupError {
    /// No plan is cached for the account at all — the process was likely restarted (the cache is
    /// in-memory only) or the plan was already committed and cleared.
    Missing,
    /// A plan is cached for the account, but under a different handle: a later
    /// `propose*`/`prepare*` call replaced the plan the caller was shown.
    Superseded,
}

impl std::fmt::Display for PlanLookupError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PlanLookupError::Missing => f.write_str(
                "No pending migration proposal for this account — call propose/prepare first",
            ),
            PlanLookupError::Superseded => f.write_str(
                "The migration proposal identified by this handle has been superseded by a newer \
                 proposal — re-propose and show the user the new schedule before signing",
            ),
        }
    }
}

impl std::error::Error for PlanLookupError {}

fn store() -> &'static Mutex<HashMap<AccountUuid, (PlanHandle, MigrationPlan)>> {
    static STORE: OnceLock<Mutex<HashMap<AccountUuid, (PlanHandle, MigrationPlan)>>> =
        OnceLock::new();
    STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Records the most recently previewed plan for `account`, replacing any previous one (matches
/// the old crate's "each propose call replaces any prior unconsumed proposal" semantics), and
/// returns the fresh handle that now identifies it. Any handle previously issued for `account`
/// is thereby invalidated: committing with it fails with [`PlanLookupError::Superseded`].
pub fn set(account: AccountUuid, plan: MigrationPlan) -> PlanHandle {
    let handle = OsRng.next_u64();
    store()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(account, (handle, plan));
    handle
}

/// Returns a clone of the cached plan for `account`, but only if `handle` identifies it —
/// i.e. only if no later `propose*`/`prepare*` call has replaced the plan the caller was shown.
pub fn get(account: AccountUuid, handle: PlanHandle) -> Result<MigrationPlan, PlanLookupError> {
    match store()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&account)
    {
        None => Err(PlanLookupError::Missing),
        Some((cached_handle, _)) if *cached_handle != handle => Err(PlanLookupError::Superseded),
        Some((_, plan)) => Ok(plan.clone()),
    }
}

/// Drops the cached plan for `account` — called once it's been committed, since the durable,
/// authoritative copy from that point on is what `PoolMigrationRead::get_migration()` persists.
pub fn clear(account: AccountUuid) {
    store()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&account);
}
