//! JNI exports for the migration engine (Kotlin's `MigrationRustBackend`).
//!
//! This module is exports only. The engine/wallet logic they drive lives in [`crate::migration`]
//! (over the wallet-DB adapter in `migration_engine.rs`), and the Java-object marshalling they
//! use lives in [`self::encode`].
//!
//! Every JNI function here keeps its original signature and JNI-visible behavior so no Kotlin
//! code needed to change — the engine swap is entirely internal to [`crate::migration`] and
//! `migration_engine.rs`. Two known, deliberate deviations from the old crate's exact semantics:
//!
//! 1. The new engine's `Schedule` type has no `anchor_height` (ZIP 374 defers anchor selection to
//!    proving time, not planning time) — `JniTransferProposal.anchorHeight` is populated with the
//!    schedule's `broadcast_height()` as a placeholder so the Kotlin type doesn't need to change;
//!    it no longer carries a real commitment-tree anchor value. Callers must not treat it as one.
//! 2. `finalizeReadyTransfersNative` and `nextDueTransferNative` prove transactions ahead of
//!    broadcast (ZIP 374) via `try_prove` (see its doc comment), which wraps
//!    `zcash_pool_migration_backend`'s own `WalletMigrationProver`/`engine::prove_transfer`/
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
use rusqlite::OptionalExtension;
use std::ptr;

use zcash_client_backend::data_api::wallet::input_selection::LockFilter;
use zcash_client_backend::data_api::{InputSource, WalletRead, WalletWrite};
use zcash_client_backend::wallet::OutputRef;
use zcash_protocol::consensus::{BlockHeight, NetworkConstants};
use zcash_protocol::value::Zatoshis;
use zcash_protocol::{PoolType, ShieldedPool};

use zcash_pool_migration_backend::engine::{
    self, MigrationCrypto, MigrationTxId, MigrationTxKind, MigrationTxState, PoolMigrationRead,
    PoolMigrationWrite,
};

use crate::jni::migration::encode::{
    JNI_KEYSTONE_BATCH_DECODE_RESULT, JNI_KEYSTONE_BATCH_SIGNED_PCZTS,
    JNI_MIGRATION_TRANSFER_STATE, JNI_MIGRATION_TRANSFER_STATES, JNI_PREPARED_TRANSFER,
    JNI_UNSIGNED_TRANSFER_PCZT, decode_transfer_id, derive_migration_state,
    encode_migration_progress, encode_migration_schedule, encode_note_split_proposal,
    encode_transfer_id, encode_transfer_proposal, open, plan,
};
use crate::migration::{
    DUST_LOCK_OWNER, MIGRATION_DUST_THRESHOLD_ZATOSHI, account_zip32_derivation, commit_or_reuse,
    compute_plan, finalize_note_split, is_prove_ready, natural_anchor_height, read_reconciled,
    target_height, try_prove,
};
use crate::migration_engine::Backend;
use crate::utils::{catch_unwind, decode_byte_array_list, exception::unwrap_exc_or};

mod encode;

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
/// the AUTOMATIC-mode, shuffled N-transfer engine plan via `zcash_pool_migration_backend`), this
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
        let id = decode_transfer_id(env, &transfer_id)?;
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
        Ok(derive_migration_state(env, persisted, tip)?.into_raw())
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let persisted = read_reconciled(&wallet, &mut backend)?;
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
        let mut backend = Backend::new(&wallet, account, None, &mut store_conn)?;
        let Some(state) = read_reconciled(&wallet, &mut backend)? else {
            return Ok(ptr::null_mut());
        };

        let mut due: Vec<_> = state
            .transactions()
            .iter()
            .filter(|t| {
                matches!(t.kind(), MigrationTxKind::Transfer { .. })
                    && matches!(t.state(), MigrationTxState::Proved)
                    && t.scheduled_height() <= tip
                    && state.deps_mined(t.depends_on())
            })
            .collect();
        due.sort_by_key(|t| t.scheduled_height());
        tracing::debug!(
            "MIGRATION_DIAG nextDueTransfer: tip={:?}, {} transfer(s) total, states={:?}, {} due now",
            tip,
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
            due.len(),
        );

        let Some(tx) = due.into_iter().next() else {
            return Ok(ptr::null_mut());
        };
        // `Proved` carries the fully witnessed/anchored/proven PCZT bytes (installed by
        // `finalizeReadyTransfersNative`'s `try_prove`) — extract the txid directly from them, no
        // separate cache lookup needed.
        let bytes = tx.pczt();
        let extracted = pczt::roles::tx_extractor::TransactionExtractor::new(
            pczt::Pczt::parse(bytes).map_err(|e| anyhow!("parse proven transfer pczt: {:?}", e))?,
        )
        .extract()
        .map_err(|e| anyhow!("extract proven transfer tx: {:?}", e))?;
        let txid: [u8; 32] = *extracted.txid().as_ref();

        let id = encode_transfer_id(env, tx.id())?;
        let txid_obj = crate::utils::rust_bytes_to_java(env, &txid)?;
        let pczt_obj = crate::utils::rust_bytes_to_java(env, bytes)?;
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

/// DEBUG ONLY: wipes this account's in-progress migration entirely (every preparation and
/// transfer transaction, signed or not, proved or not, broadcast or not), so the next
/// propose/commit call starts completely fresh — for manual testing, not exposed to production
/// users. Deletes the account's single row in `orchard_ironwood_migrations`; every child table
/// (`_transactions`, `_crossing_values`, `_prep_inputs`/`_prep_outputs`, `_transaction_deps`)
/// cascades via its own `ON DELETE CASCADE` foreign key — no separate cleanup needed. Distinct
/// from `restartCurrentMigrationStepNative`, which recovers a RequiresAttention migration by
/// re-planning the remaining balance, not wiping it.
///
/// Returns the number of migration rows deleted (0 or 1 — the table enforces at most one
/// in-progress migration per account).
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
        let (_network, _wallet, store_conn) = open(env, db_data, network_id)?;
        let account = crate::account_id_from_jni(env, account_uuid)?;
        let deleted = store_conn
            .execute(
                "DELETE FROM orchard_ironwood_migrations \
                 WHERE account_id = (SELECT id FROM accounts WHERE uuid = ?1)",
                rusqlite::params![account.expose_uuid()],
            )
            .map_err(|e| anyhow!("Error clearing migration: {}", e))?;
        Ok(deleted as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

/// DEBUG ONLY: overrides this account's persisted migration schedule so its transfers become due
/// in quick succession, for manually testing real broadcast execution without waiting out ZIP
/// 318's privacy-motivated delay (mean ~3h between transfers — see `zcash_pool_migration_backend::
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
/// about `FIRST_DELAY_BLOCKS * 75s`, each subsequent one `STRIDE_BLOCKS * 75s` after that.
///
/// Returns the number of transfers rescheduled.
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
    // ~2.5 min to the first transfer, ~5 min between each subsequent one, at the ~75s/block
    // testnet/mainnet target spacing.
    const FIRST_DELAY_BLOCKS: u32 = 2;
    const STRIDE_BLOCKS: u32 = 4;

    let res = catch_unwind(&mut env, |env| {
        let (_network, wallet, store_conn) = open(env, db_data, network_id)?;
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

        let migration_id: Option<i64> = store_conn
            .query_row(
                "SELECT m.id FROM orchard_ironwood_migrations m \
                 JOIN accounts a ON a.id = m.account_id \
                 WHERE a.uuid = ?1",
                rusqlite::params![account.expose_uuid()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| anyhow!("Error reading migration row: {}", e))?;
        let Some(migration_id) = migration_id else {
            return Ok(0);
        };

        let tx_ids: Vec<i64> = {
            let mut stmt = store_conn
                .prepare(
                    "SELECT tx_id FROM orchard_ironwood_migration_transactions \
                     WHERE migration_id = ?1 AND kind = 'transfer' \
                       AND state NOT IN ('broadcast', 'mined') \
                     ORDER BY scheduled_height ASC",
                )
                .map_err(|e| anyhow!("Error preparing transfer query: {}", e))?;
            stmt.query_map(rusqlite::params![migration_id], |row| row.get(0))
                .map_err(|e| anyhow!("Error reading pending transfers: {}", e))?
                .collect::<Result<_, _>>()
                .map_err(|e| anyhow!("Error reading pending transfers: {}", e))?
        };

        for (i, tx_id) in tx_ids.iter().enumerate() {
            let new_height = u32::from(target) + FIRST_DELAY_BLOCKS + (i as u32) * STRIDE_BLOCKS;
            // `is_prove_ready` (`finalizeReadyTransfers`) gates purely on `anchor_boundary`, NOT on
            // `scheduled_height` — so rewriting every transfer's anchor here would make ALL of them
            // prove-ready in the same `finalizeReadyTransfers` call (confirmed live: 12 real Halo2
            // proofs run back-to-back under one MIGRATION_DB_ACCESS_MUTEX hold, ~4-5 minutes,
            // starving a concurrent "Send Now" tap the whole time — not a hang, just an unrealistic
            // proving batch this debug tool itself created). Only the earliest-due transfer (i==0,
            // this loop's existing scheduled_height-ascending order) gets a valid anchor; the rest
            // keep their original, still-in-the-future one, matching production's natural
            // one-becomes-ready-at-a-time shape. Re-invoke this debug action once this transfer
            // broadcasts to unlock the next one.
            let anchor_boundary = if i == 0 {
                Some(debug_anchor_boundary)
            } else {
                None
            };
            store_conn
                .execute(
                    "UPDATE orchard_ironwood_migration_transactions \
                     SET scheduled_height = ?1, \
                         anchor_boundary = COALESCE(?2, anchor_boundary) \
                     WHERE migration_id = ?3 AND tx_id = ?4",
                    rusqlite::params![new_height, anchor_boundary, migration_id, tx_id],
                )
                .map_err(|e| anyhow!("Error rescheduling transfer {tx_id}: {}", e))?;
        }
        Ok(tx_ids.len() as jint)
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
