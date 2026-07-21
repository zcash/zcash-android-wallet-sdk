//! JNI bindings for the migration engine.
//!
//! Rewired (2026-07-21) from our own hand-rolled `zcash_pool_migration` crate onto the core/
//! upstream `zcash_pool_migration_backend` + `zcash_pool_migration_sqlite` crates (Danny/core
//! team, `zcash/librustzcash` PR #2669 + stack). See `migration_engine.rs` for the adapter wiring
//! our wallet DB into the new engine's traits, and
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
//! 2. `finalizeReadyTransfersNative` and `nextDueTransferNative` prove transactions at broadcast
//!    time (ZIP 374) via `migration_finalize.rs`, hand-ported from our old crate's
//!    `backend::finalize_self_funding_transfer`/`prove_pczt` (see that module's doc comment) since
//!    the new engine defers this to the consumer instead of doing it internally. Stopgap until
//!    core (`zcash_pool_migration_backend`) grows an equivalent built-in helper.
//! 3. The commit functions (`signNoteSplitNative`, `signAndStoreMigrationScheduleNative`,
//!    `createUnsignedNoteSplitPcztNative`, `createUnsignedTransferPcztsNative`) ignore the
//!    Kotlin-supplied schedule arrays and instead sign the plan cached by the most recent
//!    `propose*`/`prepare*` call, via `commit_or_reuse`/`migration_plan_cache` — see that module's
//!    doc comment for why (the new engine's plan types have no public constructor to rebuild one
//!    from primitives, verified directly, not assumed).

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JLongArray, JObject, JObjectArray, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jobjectArray},
};
use rand::rngs::OsRng;
use rusqlite::Connection;
use std::ptr;

use zcash_client_backend::data_api::WalletRead;
use zcash_client_backend::keys::UnifiedSpendingKey;
use zcash_client_sqlite::AccountUuid;
use zcash_client_sqlite::util::SystemClock;
use zcash_protocol::consensus::{BLOCKS_PER_HOUR, BlockHeight, Network};
use zcash_protocol::value::Zatoshis;

use zcash_pool_migration_backend::engine::{
    self, MigrationCrypto, MigrationPlan, MigrationState, MigrationTxId, MigrationTxKind,
    MigrationTxState, PoolMigrationRead, PoolMigrationWrite,
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
const JNI_TRANSFER_PROPOSAL: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniTransferProposal";
const JNI_MIGRATION_SCHEDULE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationSchedule";
const JNI_UNSIGNED_TRANSFER_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniUnsignedTransferPczt";
const JNI_KEYSTONE_BATCH_DECODE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchDecodeResult";
const JNI_KEYSTONE_BATCH_SIGNED_PCZTS: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchSignedPczts";

type Wallet = zcash_client_sqlite::WalletDb<
    Connection,
    Network,
    SystemClock,
    OsRng,
>;

/// Opens a fresh wallet-read connection plus a second, independent connection for the migration
/// store (same on-disk file — SQLite supports multiple connections to one file; mirrors the old
/// `MigrationContext::open_wallet`/`store_conn` pattern, which also opened two connections).
/// Every JNI function here calls this fresh and drops it at the end (no persistent handle),
/// exactly like the old file's documented contract.
fn open(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
) -> anyhow::Result<(Network, Wallet, Connection)> {
    let network = crate::parse_network(network_id as u32)?;
    let db_path = crate::path_from_jni(env, db_data)?;
    let wallet = Wallet::for_path(
        db_path.clone(),
        network,
        SystemClock,
        OsRng,
    )
    .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))?;
    let store_conn = Connection::open(&db_path)
        .map_err(|e| anyhow!("Error opening migration store connection: {}", e))?;
    zcash_pool_migration_sqlite::init_migration_tables(&store_conn)
        .map_err(|e| anyhow!("Error initializing migration tables: {:?}", e))?;
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
/// `None` (see `migration_finalize::finalize_transaction`'s doc comment).
fn natural_anchor_height(wallet: &Wallet) -> anyhow::Result<BlockHeight> {
    wallet
        .get_target_and_anchor_heights(std::num::NonZeroU32::MIN)
        .map_err(|e| anyhow!("Error fetching anchor height: {}", e))?
        .map(|(_, anchor)| anchor)
        .ok_or_else(|| anyhow!("wallet has no anchor height yet; scan required"))
}

/// Computes a fresh preview plan and caches it (see `migration_plan_cache`'s module doc for why)
/// so a later commit call signs exactly this plan, not an independently re-randomized one. Also
/// returns the wallet's current tip, needed as the "now" reference point when encoding transfer
/// proposals (see `encode_transfer_proposal`'s doc comment for why this matters).
fn plan(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
    account_uuid: JByteArray,
) -> anyhow::Result<(MigrationPlan, BlockHeight)> {
    let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
    let account = crate::account_id_from_jni(env, account_uuid)?;
    let backend = Backend::new(&wallet, account, None, &mut store_conn);
    let mut rng = OsRng;
    let migration_plan = engine::plan_migration(&network, &backend, &mut rng)
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
    crate::migration_plan_cache::set(account, migration_plan.clone());
    Ok((migration_plan, tip))
}

/// Returns the already-committed migration state if one exists (non-terminal), otherwise commits
/// the plan cached by the most recent `plan()` call for `account` — erroring if none was cached
/// (see `migration_plan_cache`'s module doc). Shared by both the in-process-signing and
/// external-signer commit paths below; `sign` picks which `commit_preparation`/
/// `build_preparation_unsigned` variant to run, and whether a spending key is available to the
/// `Backend` while doing so.
fn commit_or_reuse(
    network: &Network,
    wallet: &Wallet,
    account: AccountUuid,
    store_conn: &mut Connection,
    target: BlockHeight,
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
        let backend = Backend::new(wallet, account, None, store_conn);
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
    let migration_plan = crate::migration_plan_cache::get(account).ok_or_else(|| {
        anyhow!("No pending migration proposal — call propose/prepare first")
    })?;
    let mut backend = Backend::new(wallet, account, usk, store_conn);
    let mut rng = OsRng;
    let result = sign(network, target, &mut backend, &migration_plan, &mut rng)?;
    crate::migration_plan_cache::clear(account);
    Ok(result)
}

fn encode_migration_progress<'a>(
    env: &mut JNIEnv<'a>,
    completed: usize,
    total: usize,
    remaining_orchard_value: Zatoshis,
    next_transfer_ready_at_height: i64,
) -> jni::errors::Result<JObject<'a>> {
    env.new_object(
        JNI_MIGRATION_PROGRESS,
        "(IIJJ)V",
        &[
            JValue::Int(completed as jint),
            JValue::Int(total as jint),
            JValue::Long(u64::from(remaining_orchard_value) as i64),
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
    wallet: &Wallet,
    account: AccountUuid,
    persisted: Option<MigrationState>,
    tip: BlockHeight,
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
                let reason = env.new_object(
                    format!("{JNI_ATTENTION_REASON}$TransferExpired"),
                    "()V",
                    &[],
                )?;
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
    let remaining_orchard_value = wallet
        .get_account(account)
        .map_err(|e| anyhow!("account lookup failed: {}", e))?
        .and_then(|_| None::<Zatoshis>)
        .unwrap_or(Zatoshis::ZERO);
    let next_ready = state.next_broadcastable(tip);
    let next_transfer_ready_at_height = next_ready
        .and_then(|id| transactions.iter().find(|t| t.id() == id))
        .map_or(-1i64, |_| i64::from(u32::from(tip)));

    let progress = encode_migration_progress(
        env,
        completed,
        total,
        remaining_orchard_value,
        next_transfer_ready_at_height,
    )?;
    Ok(env.new_object(
        format!("{JNI_MIGRATION_STATE}$InProgress"),
        format!("(L{JNI_MIGRATION_PROGRESS};)V"),
        &[JValue::Object(&progress)],
    )?)
}

fn encode_note_split_proposal<'a>(
    env: &mut JNIEnv<'a>,
    plan: &MigrationPlan,
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
        "([JJ)V",
        &[JValue::Object(&values_array), JValue::Long(fee)],
    )
}

fn encode_transfer_id<'a>(env: &mut JNIEnv<'a>, id: MigrationTxId) -> jni::errors::Result<JString<'a>> {
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
) -> anyhow::Result<JObject<'a>> {
    // `funding_notes()`, NOT `note_split().crossing_values()`: the funding notes are the
    // post-reconciliation values (crossing_values() minus whatever the smallest denominations
    // dropped to cover preparation fees) and `schedule()`'s doc explicitly pairs "one entry per
    // funding note" — zipping against crossing_values() instead silently mispairs amounts with
    // schedule heights whenever reconciliation drops anything (confirmed live: this produced a
    // suspiciously perfectly-sorted-by-size transfer list, the opposite of ZIP 318 SHUFFLE's
    // intent, plus every transfer immediately overdue).
    let crossings = plan.funding_notes();
    let schedule = plan.schedule();
    if crossings.len() != schedule.len() {
        return Err(anyhow!(
            "Migration plan invariant violated: {} funding notes but {} schedule entries",
            crossings.len(),
            schedule.len()
        ));
    }

    // The real `MigrationTxId` the engine will assign at commit time numbers every preparation
    // transaction (across all layers) first, THEN transfers in `schedule()` order (confirmed
    // directly against `commit_preparation_inner` in `zcash_pool_migration_backend::engine`) — so
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
        .zip(schedule.iter().map(|e| u32::from(e.broadcast_height())).min())
        .map(|(max, min)| max.saturating_sub(min) / BLOCKS_PER_HOUR)
        .unwrap_or(0);

    Ok(env.new_object(
        JNI_MIGRATION_SCHEDULE,
        format!("([L{JNI_TRANSFER_PROPOSAL};I)V"),
        &[
            JValue::Object(&transfers),
            JValue::Int(estimated_duration_hours as jint),
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
        let (migration_plan, _tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_note_split_proposal(env, &migration_plan)?.into_raw())
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
        let (migration_plan, tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// The new engine plans the note split and the transfer schedule together in one
/// `plan_migration()` call (the split's realized output values ARE `plan.note_split()
/// .crossing_values()`, which is exactly what `encode_migration_schedule` already derives the
/// schedule from) — so this and `proposeMigrationTransfersNative` above are now equivalent; kept
/// as two JNI entry points only so the Kotlin call sites don't need to change. The double-spend
/// class of bug this function existed to fix in the old crate (schedule computed independently of
/// the split's realized output) cannot recur here: there is only ever one plan.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_proposeMigrationTransfersFromSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    _output_values_zatoshi: JLongArray<'local>,
    _fee_zatoshi: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (migration_plan, tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_proposeImmediateMigrationTransfersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (migration_plan, tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// In-process signing (software key, not Keystone) of the note split, as its own standalone,
/// immediately-broadcastable transaction — this is currently zodl's primary tested migration path
/// (Keystone hasn't been exercised yet), so unlike most of this file's other functions this one is
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
/// The split is a preparation transaction spending an already-witnessed wallet note directly, so
/// per `migration_finalize::finalize_transaction`'s doc comment it has no deferred witness to
/// resolve (`anchor_boundary() == None`) — it should already be complete right after commit.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_signNoteSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    _output_values_zatoshi: JLongArray<'local>,
    _fee_zatoshi: jlong,
    usk: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let (network, mut wallet, mut store_conn) = open(env, db_data, network_id)?;
        crate::migration_finalize::init_proven_cache(&store_conn)
            .map_err(|e| anyhow!("Error initializing proven-pczt cache: {}", e))?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let usk = crate::decode_usk(env, usk)?;
        let target = target_height(&wallet)?;
        let (state, _unsigned) = commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state = engine::commit_preparation(network, target, backend, migration_plan, rng)
                    .map_err(|e| anyhow!("Error committing migration: {:?}", e))?;
                Ok((state, Vec::new()))
            },
        )?;
        let (fvk, spendable) = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn);
            let fvk = backend
                .orchard_fvk()
                .map_err(|e| anyhow!("Error reading account FVK: {:?}", e))?;
            let spendable = backend
                .spendable_orchard_notes()
                .map_err(|e| anyhow!("Error reading spendable notes: {:?}", e))?;
            (fvk, spendable)
        };
        let split_tx = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .ok_or_else(|| anyhow!("Migration has no note-split preparation transaction"))?;

        // The split is a preparation transaction: `anchor_boundary()` is `None` for these (they
        // wait on their dependencies rather than a drawn ZIP 318 boundary — see
        // `migration_finalize::finalize_transaction`'s doc comment), but it still has a deferred
        // witness to resolve like any other ZIP 374 transaction, just against the wallet's current
        // natural anchor instead of a scheduled one (confirmed live: the split's spend is redacted
        // just like a transfer's, this fallback was documented but not wired up before).
        let anchor_height = match split_tx.anchor_boundary() {
            Some(h) => Some(h),
            None => Some(natural_anchor_height(&wallet)?),
        };
        let (proven_pczt, txid) = crate::migration_finalize::finalize_transaction(
            &mut wallet,
            &fvk,
            &spendable,
            anchor_height,
            split_tx.pczt(),
        )
        .map_err(|e| anyhow!("Error finalizing note split: {}", e))?
        .ok_or_else(|| {
            anyhow!("Note-split transaction is not yet finalizable — its funding note isn't witnessable yet")
        })?;
        crate::migration_finalize::put_proven(&store_conn, split_tx.id(), &proven_pczt, &txid)
            .map_err(|e| anyhow!("Error caching proven pczt: {}", e))?;

        let id = encode_transfer_id(env, split_tx.id())?;
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
        let pczt = pczt::Pczt::parse(&pczt_bytes)
            .map_err(|e| anyhow!("Error parsing PCZT: {:?}", e))?;
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
        let id = decode_transfer_id(env, &transfer_id)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn);
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
                    .put_migration(&state)
                    .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))
            }
            // NetworkError/InvalidNote/Expired: no destructive state transition exists in the new
            // engine's public API for "this attempt failed, try again later" — the transaction
            // stays `Signed`/`AwaitingSignature` and `next_step` will offer it again on the next
            // call. Nothing to persist.
            1 | 2 | 3 => Ok(()),
            other => Err(anyhow!("Unknown TransferResult tag: {}", other)),
        }
    });
    unwrap_exc_or(&mut env, res, ())
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
        Ok(derive_migration_state(env, &wallet, account, persisted, tip)?.into_raw())
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
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
                encode_migration_progress(
                    env,
                    completed,
                    transactions.len(),
                    Zatoshis::ZERO,
                    next_ready_height,
                )?
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
        let (migration_plan, _tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(
            if migration_plan.preparation().transaction_count() > 0 {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
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
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                if state.next_broadcastable(tip).is_some() {
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
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
    _ids: JObjectArray<'local>,
    _amounts_zatoshi: JLongArray<'local>,
    _anchor_heights: JLongArray<'local>,
    _next_executable_after_heights: JLongArray<'local>,
    _expiry_heights: JLongArray<'local>,
    _estimated_duration_hours: jint,
    usk: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let usk = crate::decode_usk(env, usk)?;
        // The Kotlin-supplied schedule arrays are ignored — `commit_preparation` takes a
        // `MigrationPlan` value directly, not a caller-echoed schedule, and the new engine's plan
        // types have no public constructor to rebuild one from primitives (verified against
        // `zcash_pool_migration_backend`'s source, not assumed). Instead of re-deriving a fresh
        // (differently-randomized) plan here, `commit_or_reuse` signs exactly the plan the most
        // recent `propose*`/`prepare*` call cached — see `migration_plan_cache`'s module doc for
        // why that matters (this was a real, discussed regression risk versus the old crate, which
        // did sign exactly the caller-echoed values).
        let target = target_height(&wallet)?;
        commit_or_reuse(
            &network,
            &wallet,
            account,
            &mut store_conn,
            target,
            Some(usk),
            |network, target, backend, migration_plan, rng| {
                let state = engine::commit_preparation(network, target, backend, migration_plan, rng)
                    .map_err(|e| anyhow!("Error committing migration schedule: {:?}", e))?;
                Ok((state, Vec::new()))
            },
        )?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_isSyncRequiredBeforeNextTransferNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jboolean {
    // ZIP 318's sync/broadcast decoupling MUST is enforced app-side (`MigrationWorker.kt`'s
    // `isSyncRequiredBeforeNextTransfer()` check, unaffected by this rewire — see spec doc §6.8),
    // not by the migration engine itself in either the old or new crate. This function's own
    // contract (does the engine think a sync is due before the next transfer) has no direct
    // equivalent surfaced by the new engine's public API; conservatively return false (no
    // additional engine-side gate) since the app-side check already covers the ZIP 318
    // requirement independently.
    let res = catch_unwind(&mut env, |env| {
        let _ = open(env, db_data, network_id)?;
        let _ = crate::account_id_from_jni(env, account_uuid)?;
        Ok(JNI_FALSE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Advances every due, signed transaction's proving (ZIP 374: installs its real anchor + witness
/// via the `pczt` `Updater` role, runs the `Prover`, finalizes spends — see
/// `migration_finalize::finalize_transaction`, ported from `librustzcash` branch
/// `feature/orchard_migration`'s `backend::finalize_self_funding_transfer`/`prove_pczt`, historical
/// reference only, not merged). Proven bytes are cached in `migration_proven_cache` (this file's
/// own side table — the engine's own persistence never stores anything past the original signed
/// PCZT) for `nextDueTransferNative` to pick up. Idempotent: already-cached transactions are
/// skipped; returns the count of transactions newly proven this call, 0 (not an error) if nothing
/// was ready — matches the old function's documented contract.
///
/// TODO: this is a stopgap so the SDK can be exercised against the new engine before core
/// (`zcash_pool_migration_backend`) grows an equivalent built-in helper — prefer that when it
/// lands, over this hand-ported copy (see `migration_finalize.rs`'s module doc).
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
        crate::migration_finalize::init_proven_cache(&store_conn)
            .map_err(|e| anyhow!("Error initializing proven-pczt cache: {}", e))?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let tip = target_height(&wallet)? - 1;

        let (state, fvk, spendable) = {
            let backend = Backend::new(&wallet, account, None, &mut store_conn);
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
            let spendable = backend
                .spendable_orchard_notes()
                .map_err(|e| anyhow!("Error reading spendable notes: {:?}", e))?;
            (state, fvk, spendable)
        };

        let mut finalized_count = 0;
        for tx in state.transactions() {
            if !matches!(tx.state(), MigrationTxState::Signed) {
                continue;
            }
            if tx.scheduled_height() > tip || !state.deps_mined(tx.depends_on()) {
                continue;
            }
            if crate::migration_finalize::get_proven(&store_conn, tx.id())
                .map_err(|e| anyhow!("Error reading proven-pczt cache: {}", e))?
                .is_some()
            {
                continue;
            }
            // Preparation transactions have no drawn `anchor_boundary` — fall back to the
            // wallet's current natural anchor for their deferred witness (see `signNoteSplitNative`
            // for the full explanation).
            let anchor_height = match tx.anchor_boundary() {
                Some(h) => Some(h),
                None => Some(natural_anchor_height(&wallet)?),
            };
            if let Some((proven_pczt, txid)) = crate::migration_finalize::finalize_transaction(
                &mut wallet,
                &fvk,
                &spendable,
                anchor_height,
                tx.pczt(),
            )
            .map_err(|e| anyhow!("Error finalizing transfer {:?}: {}", tx.id(), e))?
            {
                crate::migration_finalize::put_proven(&store_conn, tx.id(), &proven_pczt, &txid)
                    .map_err(|e| anyhow!("Error caching proven pczt: {}", e))?;
                finalized_count += 1;
            }
        }
        Ok(finalized_count)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// The next transfer that's due, deps-mined, and already proven (see
/// `finalizeReadyTransfersNative`, which must have run first this session for anything to be
/// ready) — or `null` if nothing qualifies yet.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_nextDueTransferNative<
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let Some(state) = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
        else {
            return Ok(ptr::null_mut());
        };

        let mut due: Vec<_> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.kind(), MigrationTxKind::Transfer { .. })
                    && matches!(t.state(), MigrationTxState::Signed)
                    && t.scheduled_height() <= tip
                    && state.deps_mined(t.depends_on())
            })
            .collect();
        due.sort_by_key(|t| t.scheduled_height());

        for tx in due {
            if let Some((proven_pczt, txid)) = crate::migration_finalize::get_proven(&store_conn, tx.id())
                .map_err(|e| anyhow!("Error reading proven-pczt cache: {}", e))?
            {
                let id = encode_transfer_id(env, tx.id())?;
                let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
                let pczt_obj = crate::utils::rust_bytes_to_java(env, &proven_pczt)?;
                return Ok(env
                    .new_object(
                        JNI_PREPARED_TRANSFER,
                        "(Ljava/lang/String;[B[B)V",
                        &[
                            JValue::Object(&id),
                            JValue::Object(&txid_obj),
                            JValue::Object(&pczt_obj),
                        ],
                    )?
                    .into_raw());
            }
        }
        Ok(ptr::null_mut())
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
        let (migration_plan, tip) = plan(env, db_data, network_id, account_uuid)?;
        Ok(encode_migration_schedule(env, &migration_plan, tip)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
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
        let backend = Backend::new(&wallet, account, None, &mut store_conn);
        let persisted = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?;
        Ok(match persisted {
            Some(state) if !state.is_terminal() => {
                match state.next_broadcastable(tip).and_then(|id| {
                    state.transactions().iter().find(|t| t.id() == id).map(|t| (id, t))
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

// ----- External signer (Keystone hardware wallet) -----

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_createUnsignedNoteSplitPcztNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
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
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) =
                    engine::build_preparation_unsigned(network, target, backend, migration_plan, rng)
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
        let (_network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let signed_pczt_bytes = crate::utils::java_bytes_to_rust(env, &signed_pczt)?;
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn);
        let mut state = backend
            .get_migration()
            .map_err(|e| anyhow!("Error reading migration state: {:?}", e))?
            .ok_or_else(|| anyhow!("No migration committed yet"))?;
        let split_id = state
            .transactions()
            .iter()
            .find(|t| matches!(t.kind(), MigrationTxKind::Preparation { layer: 0, .. }))
            .map(|t| t.id())
            .ok_or_else(|| anyhow!("Migration has no note-split preparation transaction"))?;
        if !state.apply_signature(split_id, signed_pczt_bytes.clone()) {
            return Err(anyhow!("Error applying note-split signature"));
        }
        backend
            .put_migration(&state)
            .map_err(|e| anyhow!("Error persisting migration state: {:?}", e))?;
        let id = encode_transfer_id(env, split_id)?;
        let txid_placeholder = crate::utils::rust_bytes_to_java(env, &[0u8; 32])?;
        let pczt_bytes = crate::utils::rust_bytes_to_java(env, &signed_pczt_bytes)?;
        Ok(env
            .new_object(
                JNI_PREPARED_TRANSFER,
                "(Ljava/lang/String;[B[B)V",
                &[
                    JValue::Object(&id),
                    JValue::Object(&txid_placeholder),
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
    _ids: JObjectArray<'local>,
    _amounts_zatoshi: JLongArray<'local>,
    _anchor_heights: JLongArray<'local>,
    _next_executable_after_heights: JLongArray<'local>,
    _expiry_heights: JLongArray<'local>,
    _estimated_duration_hours: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let (network, wallet, mut store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        // Mirrors `createUnsignedNoteSplitPcztNative`: the caller-supplied schedule arrays are
        // ignored — `commit_or_reuse` signs exactly the plan cached by the preceding
        // `propose*`/`prepare*` call, or (this being the *second* external-signer call in the
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
            None,
            |network, target, backend, migration_plan, rng| {
                let (state, unsigned) =
                    engine::build_preparation_unsigned(network, target, backend, migration_plan, rng)
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
        let transfers: Vec<_> = unsigned
            .into_iter()
            .filter(|(id, _)| transfer_ids.contains(id))
            .collect();
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn);
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
            .put_migration(&state)
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
        let result =
            crate::migration_keystone::decode_sign_batch_part(&part, &expected_request_id)
                .map_err(|e| anyhow!("Error decoding Keystone sign-batch QR part: {}", e))?;
        let data = match &result.data {
            Some(bytes) => crate::utils::rust_bytes_to_java(env, bytes)?.into(),
            None => JObject::null(),
        };
        Ok(env
            .new_object(
                JNI_KEYSTONE_BATCH_DECODE_RESULT,
                "(ZI[B)V",
                &[
                    JValue::Bool(if result.complete { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Int(result.progress as jint),
                    JValue::Object(&data),
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
