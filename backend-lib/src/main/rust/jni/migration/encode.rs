//! Java-object marshalling for the migration exports in [`super`].
//!
//! The Java class descriptors those exports construct objects from, the encoders that build the
//! objects, and the decoders that read Kotlin-supplied arguments back into Rust types. Nothing
//! here implements a feature: the engine/wallet logic lives in [`crate::migration`], and the
//! exports that expose it are in [`super`]. Items are `pub(super)` only where an export actually
//! calls them; the rest stay private to this module.

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject, JString, JValue},
    sys::jint,
};
use rusqlite::Connection;

use zcash_protocol::consensus::{BLOCKS_PER_HOUR, BlockHeight, Network};
use zcash_protocol::value::Zatoshis;

use zcash_pool_migration_backend::engine::{
    self, MigrationPlan, MigrationState, MigrationTxId, MigrationTxState,
};

use crate::migration::{Wallet, open_at, plan_for};

const JNI_MIGRATION_PROGRESS: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationProgress";
const JNI_ATTENTION_REASON: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniAttentionReason";
const JNI_MIGRATION_STATE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationState";
const JNI_NOTE_SPLIT_PROPOSAL: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniNoteSplitProposal";
pub(super) const JNI_PREPARED_TRANSFER: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniPreparedTransfer";
const JNI_TRANSFER_PROPOSAL: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniTransferProposal";
const JNI_MIGRATION_SCHEDULE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationSchedule";
pub(super) const JNI_MIGRATION_TRANSFER_STATE: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferState";
pub(super) const JNI_MIGRATION_TRANSFER_STATES: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniMigrationTransferStates";
pub(super) const JNI_UNSIGNED_TRANSFER_PCZT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniUnsignedTransferPczt";
pub(super) const JNI_KEYSTONE_BATCH_DECODE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchDecodeResult";
pub(super) const JNI_KEYSTONE_BATCH_SIGNED_PCZTS: &str =
    "cash/z/ecc/android/sdk/internal/model/migration/JniKeystoneBatchSignedPczts";

pub(super) fn open(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
) -> anyhow::Result<(Network, Wallet, Connection)> {
    let network = crate::parse_network(network_id as u32)?;
    let db_path = crate::path_from_jni(env, db_data)?;
    let (wallet, store_conn) = open_at(&db_path, network)?;
    Ok((network, wallet, store_conn))
}

pub(super) fn plan(
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

pub(super) fn encode_migration_progress<'a>(
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
pub(super) fn derive_migration_state<'a>(
    env: &mut JNIEnv<'a>,
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

pub(super) fn encode_note_split_proposal<'a>(
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

pub(super) fn encode_transfer_id<'a>(
    env: &mut JNIEnv<'a>,
    id: MigrationTxId,
) -> jni::errors::Result<JString<'a>> {
    env.new_string(u32::from(id).to_string())
}

pub(super) fn decode_transfer_id(env: &mut JNIEnv, id: &JString) -> anyhow::Result<MigrationTxId> {
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
pub(super) fn encode_transfer_proposal<'a>(
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

pub(super) fn encode_migration_schedule<'a>(
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
