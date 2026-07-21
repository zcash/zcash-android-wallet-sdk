//! In-process cache of the most recent `MigrationPlan` per account, bridging the gap between
//! `plan_migration()` (a pure, unpersisted preview — see its doc comment) and the commit
//! functions (`commit_preparation`/`build_preparation_unsigned`) that need that exact same plan
//! value later.
//!
//! Unlike this file's neighbors, this is NOT backed by the wallet SQLite database: the new
//! engine's `MigrationPlan` (and its `NoteSplitPlan`/`PreparationPlan` fields) has no `serde`
//! support and no public constructor — the only way to obtain one is calling `plan_migration()`
//! itself (verified directly against `zcash_pool_migration_backend`'s source, not assumed), so it
//! can't be round-tripped through our own persistence the way `migration_finalize`'s proven-pczt
//! cache is. Instead this holds it in memory, in a process-lifetime static — valid because the
//! app's entire "review a migration proposal, then confirm/sign it" flow happens on one screen, in
//! one app-process lifetime (confirmed with the user), never across a process restart.
//!
//! If the plan is missing when a commit function needs it (e.g. the process was killed between
//! propose and sign), that function surfaces a clear "call propose first" error rather than
//! silently recomputing a fresh, differently-randomized plan — recomputing would mean signing a
//! migration whose schedule the user never actually saw or approved (ZIP 318's scheduling draws
//! fresh randomness on every `plan_migration()` call, so two calls essentially never produce an
//! identical schedule even with unchanged wallet state).

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use zcash_client_sqlite::AccountUuid;
use zcash_pool_migration_backend::engine::MigrationPlan;

fn store() -> &'static Mutex<HashMap<AccountUuid, MigrationPlan>> {
    static STORE: OnceLock<Mutex<HashMap<AccountUuid, MigrationPlan>>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Records the most recently previewed plan for `account`, replacing any previous one (matches
/// the old crate's "each propose call replaces any prior unconsumed proposal" semantics).
pub fn set(account: AccountUuid, plan: MigrationPlan) {
    store()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(account, plan);
}

/// Returns a clone of the cached plan for `account`, if any.
pub fn get(account: AccountUuid) -> Option<MigrationPlan> {
    store()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&account)
        .cloned()
}

/// Drops the cached plan for `account` — called once it's been committed, since the durable,
/// authoritative copy from that point on is what `PoolMigrationRead::get_migration()` persists.
pub fn clear(account: AccountUuid) {
    store().lock().unwrap_or_else(|e| e.into_inner()).remove(&account);
}
