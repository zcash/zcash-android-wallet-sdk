//! JNI bindings for the `zcash_pool_migration` crate.
//!
//! `MigrationContext::new` is cheap (it just ensures the engine's own tables exist) and every
//! method opens its own connection internally, so every function here constructs a fresh
//! `MigrationContext` inline, calls one operation, and drops it — there is no handle/registry
//! (unlike `voting::db`, which manages genuinely expensive/stateful resources).

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JLongArray, JObject, JObjectArray, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jobjectArray},
};
use std::ptr;

use zcash_client_backend::data_api::WalletRead;
use zcash_pool_migration::{
    AttentionReason, MigrationContext, MigrationProgress, MigrationSchedule, MigrationState,
    NoteSplitProposal, PreparedTransfer, TransferId, TransferProposal, TransferResult,
};
use zcash_protocol::consensus::{BlockHeight, Network};
use zcash_protocol::value::Zatoshis;

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

fn migration_context(
    env: &mut JNIEnv,
    db_data: JString,
    network_id: jint,
    account_uuid: JByteArray,
) -> anyhow::Result<MigrationContext<Network>> {
    let network = crate::parse_network(network_id as u32)?;
    let db_path = crate::path_from_jni(env, db_data)?;
    let account = crate::account_id_from_jni(env, account_uuid)?;
    MigrationContext::new(&db_path, network, account)
        .map_err(|e| anyhow!("Error opening MigrationContext: {}", e))
}

fn encode_migration_progress<'a>(
    env: &mut JNIEnv<'a>,
    progress: &MigrationProgress,
) -> jni::errors::Result<JObject<'a>> {
    let next_transfer_ready_at_height = progress
        .next_transfer_ready_at_height()
        .map_or(-1i64, |h| i64::from(u32::from(h)));

    env.new_object(
        JNI_MIGRATION_PROGRESS,
        "(IIJJ)V",
        &[
            JValue::Int(progress.completed_transfers() as jint),
            JValue::Int(progress.total_transfers() as jint),
            JValue::Long(u64::from(progress.remaining_orchard_value()) as i64),
            JValue::Long(next_transfer_ready_at_height),
        ],
    )
}

fn encode_attention_reason<'a>(
    env: &mut JNIEnv<'a>,
    reason: &AttentionReason,
) -> jni::errors::Result<JObject<'a>> {
    match reason {
        AttentionReason::InvalidTransfer(transfer_id) => {
            let transfer_id = env.new_string(transfer_id.as_str())?;
            env.new_object(
                format!("{JNI_ATTENTION_REASON}$InvalidTransfer"),
                "(Ljava/lang/String;)V",
                &[JValue::Object(&transfer_id)],
            )
        }
        AttentionReason::TransferExpired => {
            env.new_object(format!("{JNI_ATTENTION_REASON}$TransferExpired"), "()V", &[])
        }
        AttentionReason::SyncRequiredBeforeNext => env.new_object(
            format!("{JNI_ATTENTION_REASON}$SyncRequiredBeforeNext"),
            "()V",
            &[],
        ),
    }
}

fn encode_migration_state<'a>(
    env: &mut JNIEnv<'a>,
    state: MigrationState,
) -> jni::errors::Result<JObject<'a>> {
    match state {
        MigrationState::NotStarted => {
            env.new_object(format!("{JNI_MIGRATION_STATE}$NotStarted"), "()V", &[])
        }
        MigrationState::SplitPendingConfirmation => env.new_object(
            format!("{JNI_MIGRATION_STATE}$SplitPendingConfirmation"),
            "()V",
            &[],
        ),
        MigrationState::ReadyToPropose => {
            env.new_object(format!("{JNI_MIGRATION_STATE}$ReadyToPropose"), "()V", &[])
        }
        MigrationState::InProgress(progress) => {
            let progress = encode_migration_progress(env, &progress)?;
            env.new_object(
                format!("{JNI_MIGRATION_STATE}$InProgress"),
                format!("(L{JNI_MIGRATION_PROGRESS};)V"),
                &[JValue::Object(&progress)],
            )
        }
        MigrationState::RequiresAttention(reason) => {
            let reason = encode_attention_reason(env, &reason)?;
            env.new_object(
                format!("{JNI_MIGRATION_STATE}$RequiresAttention"),
                format!("(L{JNI_ATTENTION_REASON};)V"),
                &[JValue::Object(&reason)],
            )
        }
        MigrationState::Complete => {
            env.new_object(format!("{JNI_MIGRATION_STATE}$Complete"), "()V", &[])
        }
    }
}

fn encode_note_split_proposal<'a>(
    env: &mut JNIEnv<'a>,
    proposal: &NoteSplitProposal,
) -> jni::errors::Result<JObject<'a>> {
    let values: Vec<i64> = proposal
        .output_values()
        .iter()
        .map(|&v| u64::from(v) as i64)
        .collect();
    let values_array = env.new_long_array(values.len() as i32)?;
    env.set_long_array_region(&values_array, 0, &values)?;

    env.new_object(
        JNI_NOTE_SPLIT_PROPOSAL,
        "([JJ)V",
        &[
            JValue::Object(&values_array),
            JValue::Long(u64::from(proposal.fee()) as i64),
        ],
    )
}

fn encode_prepared_transfer<'a>(
    env: &mut JNIEnv<'a>,
    transfer: &PreparedTransfer,
) -> anyhow::Result<JObject<'a>> {
    let id = env.new_string(transfer.id().as_str())?;
    let txid = crate::utils::rust_bytes_to_java(env, transfer.txid().as_ref())?;
    let pczt_bytes = crate::utils::rust_bytes_to_java(env, transfer.pczt_bytes())?;

    Ok(env.new_object(
        JNI_PREPARED_TRANSFER,
        "(Ljava/lang/String;[B[B)V",
        &[
            JValue::Object(&id),
            JValue::Object(&txid),
            JValue::Object(&pczt_bytes),
        ],
    )?)
}

fn decode_note_split_proposal(
    env: &mut JNIEnv,
    output_values_zatoshi: JLongArray,
    fee_zatoshi: jlong,
) -> anyhow::Result<NoteSplitProposal> {
    let length = env.get_array_length(&output_values_zatoshi)?;
    let mut buf = vec![0i64; length as usize];
    env.get_long_array_region(&output_values_zatoshi, 0, &mut buf)?;

    let output_values = buf
        .into_iter()
        .map(|v| {
            Zatoshis::from_nonnegative_i64(v)
                .map_err(|e| anyhow!("Invalid note-split output value {}: {}", v, e))
        })
        .collect::<anyhow::Result<Vec<_>>>()?;
    let fee = Zatoshis::from_nonnegative_i64(fee_zatoshi)
        .map_err(|e| anyhow!("Invalid note-split fee {}: {}", fee_zatoshi, e))?;

    Ok(NoteSplitProposal::from_parts(output_values, fee))
}

fn decode_transfer_result(
    env: &JNIEnv,
    result_tag: jint,
    retryable: jboolean,
    tx_id: JByteArray,
) -> anyhow::Result<TransferResult> {
    Ok(match result_tag {
        0 => TransferResult::Success(crate::parse_txid(env, tx_id)?),
        1 => TransferResult::NetworkError {
            retryable: retryable == JNI_TRUE,
        },
        2 => TransferResult::InvalidNote,
        3 => TransferResult::Expired,
        other => return Err(anyhow!("Unknown TransferResult tag: {}", other)),
    })
}

fn encode_transfer_proposal<'a>(
    env: &mut JNIEnv<'a>,
    transfer: &TransferProposal,
) -> jni::errors::Result<JObject<'a>> {
    let id = env.new_string(transfer.id().as_str())?;
    env.new_object(
        JNI_TRANSFER_PROPOSAL,
        "(Ljava/lang/String;JJJJ)V",
        &[
            JValue::Object(&id),
            JValue::Long(u64::from(transfer.amount()) as i64),
            JValue::Long(i64::from(u32::from(transfer.anchor_height()))),
            JValue::Long(i64::from(u32::from(transfer.next_executable_after_height()))),
            JValue::Long(i64::from(u32::from(transfer.expiry_height()))),
        ],
    )
}

fn encode_migration_schedule<'a>(
    env: &mut JNIEnv<'a>,
    schedule: &MigrationSchedule,
) -> anyhow::Result<JObject<'a>> {
    let transfers = crate::utils::rust_vec_to_java(
        env,
        schedule.transfers().to_vec(),
        JNI_TRANSFER_PROPOSAL,
        |env, t| encode_transfer_proposal(env, &t),
    )?;
    Ok(env.new_object(
        JNI_MIGRATION_SCHEDULE,
        format!("([L{JNI_TRANSFER_PROPOSAL};I)V"),
        &[
            JValue::Object(&transfers),
            JValue::Int(schedule.estimated_duration_hours() as jint),
        ],
    )?)
}

#[allow(clippy::too_many_arguments)]
fn decode_migration_schedule(
    env: &mut JNIEnv,
    ids: JObjectArray,
    amounts_zatoshi: JLongArray,
    anchor_heights: JLongArray,
    next_executable_after_heights: JLongArray,
    expiry_heights: JLongArray,
    estimated_duration_hours: jint,
) -> anyhow::Result<MigrationSchedule> {
    let count = env.get_array_length(&ids)?;

    let mut amounts = vec![0i64; count as usize];
    env.get_long_array_region(&amounts_zatoshi, 0, &mut amounts)?;
    let mut anchors = vec![0i64; count as usize];
    env.get_long_array_region(&anchor_heights, 0, &mut anchors)?;
    let mut next_execs = vec![0i64; count as usize];
    env.get_long_array_region(&next_executable_after_heights, 0, &mut next_execs)?;
    let mut expiries = vec![0i64; count as usize];
    env.get_long_array_region(&expiry_heights, 0, &mut expiries)?;

    let mut transfers = Vec::with_capacity(count as usize);
    for i in 0..count {
        let id_obj = env.get_object_array_element(&ids, i)?;
        let id = crate::utils::java_string_to_rust(env, &JString::from(id_obj))?;
        let idx = i as usize;
        transfers.push(TransferProposal::from_parts(
            TransferId::from_raw(id),
            Zatoshis::from_nonnegative_i64(amounts[idx])
                .map_err(|e| anyhow!("Invalid transfer amount {}: {}", amounts[idx], e))?,
            BlockHeight::try_from(anchors[idx])?,
            BlockHeight::try_from(next_execs[idx])?,
            BlockHeight::try_from(expiries[idx])?,
        ));
    }

    Ok(MigrationSchedule::from_parts(
        transfers,
        estimated_duration_hours as u32,
    ))
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let proposal = context
            .prepare_note_split()
            .map_err(|e| anyhow!("Error preparing note split: {}", e))?;
        Ok(encode_note_split_proposal(env, &proposal)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_signNoteSplitNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    output_values_zatoshi: JLongArray<'local>,
    fee_zatoshi: jlong,
    usk: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let proposal = decode_note_split_proposal(env, output_values_zatoshi, fee_zatoshi)?;
        let usk = crate::decode_usk(env, usk)?;
        let prepared = context
            .sign_note_split(&proposal, &usk)
            .map_err(|e| anyhow!("Error signing note split: {}", e))?;
        Ok(encode_prepared_transfer(env, &prepared)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_MigrationRustBackend_extractBroadcastTxNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    pczt_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let pczt_bytes = crate::utils::java_bytes_to_rust(env, &pczt_bytes)?;
        let tx_bytes = context
            .extract_broadcast_tx(&pczt_bytes)
            .map_err(|e| anyhow!("Error extracting broadcast transaction: {}", e))?;
        Ok(crate::utils::rust_bytes_to_java(env, &tx_bytes)?.into_raw())
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
    retryable: jboolean,
    tx_id: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let transfer_id = TransferId::from_raw(crate::utils::java_string_to_rust(env, &transfer_id)?);
        let result = decode_transfer_result(env, result_tag, retryable, tx_id)?;
        context
            .record_transfer_result(&transfer_id, result)
            .map_err(|e| anyhow!("Error recording transfer result: {}", e))
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let state = context
            .migration_state()
            .map_err(|e| anyhow!("Error reading migration state: {}", e))?;
        Ok(encode_migration_state(env, state)?.into_raw())
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let progress = context
            .migration_progress()
            .map_err(|e| anyhow!("Error reading migration progress: {}", e))?;
        Ok(match progress {
            Some(progress) => encode_migration_progress(env, &progress)?.into_raw(),
            None => ptr::null_mut(),
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        Ok(
            if context
                .is_note_split_needed()
                .map_err(|e| anyhow!("Error checking note split need: {}", e))?
            {
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        Ok(
            if context
                .has_overdue_transfers()
                .map_err(|e| anyhow!("Error checking overdue transfers: {}", e))?
            {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        Ok(
            if context
                .has_invalid_transfers()
                .map_err(|e| anyhow!("Error checking invalid transfers: {}", e))?
            {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
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
    include_residual: jboolean,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let schedule = context
            .propose_migration_transfers(include_residual == JNI_TRUE)
            .map_err(|e| anyhow!("Error proposing migration transfers: {}", e))?;
        Ok(encode_migration_schedule(env, &schedule)?.into_raw())
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let schedule = context
            .propose_immediate_migration_transfers()
            .map_err(|e| anyhow!("Error proposing immediate migration: {}", e))?;
        Ok(encode_migration_schedule(env, &schedule)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
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
    ids: JObjectArray<'local>,
    amounts_zatoshi: JLongArray<'local>,
    anchor_heights: JLongArray<'local>,
    next_executable_after_heights: JLongArray<'local>,
    expiry_heights: JLongArray<'local>,
    estimated_duration_hours: jint,
    usk: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let schedule = decode_migration_schedule(
            env,
            ids,
            amounts_zatoshi,
            anchor_heights,
            next_executable_after_heights,
            expiry_heights,
            estimated_duration_hours,
        )?;
        let usk = crate::decode_usk(env, usk)?;
        context
            .sign_and_store_migration_schedule(&schedule, &usk)
            .map_err(|e| anyhow!("Error signing and storing migration schedule: {}", e))
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
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        Ok(
            if context
                .is_sync_required_before_next_transfer()
                .map_err(|e| anyhow!("Error checking sync requirement: {}", e))?
            {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Completes every `SignedAwaitingProof` transfer whose funding note is now witnessed, attaching
/// its real witness/anchor and running the Prover role. Idempotent and safe to call redundantly —
/// returns 0, not an error, when there is nothing to finalize yet (see
/// `MigrationContext::finalize_ready_transfers`'s doc comment for the full contract).
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let finalized_count = context
            .finalize_ready_transfers()
            .map_err(|e| anyhow!("Error finalizing ready transfers: {}", e))?;
        Ok(finalized_count as jint)
    });
    unwrap_exc_or(&mut env, res, 0)
}

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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let prepared = context
            .next_due_transfer()
            .map_err(|e| anyhow!("Error fetching next due transfer: {}", e))?;
        Ok(match prepared {
            Some(prepared) => encode_prepared_transfer(env, &prepared)?.into_raw(),
            None => ptr::null_mut(),
        })
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
    include_residual: jboolean,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let schedule = context
            .restart_current_migration_step(include_residual == JNI_TRUE)
            .map_err(|e| anyhow!("Error restarting migration step: {}", e))?;
        Ok(encode_migration_schedule(env, &schedule)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Lists every account's UUID (16 raw bytes each) in the wallet database, independent of any
/// `MigrationContext`/live `Synchronizer` — used to resolve which account(s) to check before one
/// is otherwise known (e.g. gating sync at wallet-bootstrap time, before a `Synchronizer` exists).
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
        // A wallet can be persisted (seed stored) before its data.db schema has ever been
        // initialized (that only happens once a Synchronizer actually starts) — WalletDb::for_path
        // itself may create an empty, table-less SQLite file. Treat that as "no accounts yet"
        // rather than an error: this is called independent of any live Synchronizer, so it must
        // tolerate running before one has ever existed.
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

/// The pending (due-or-not-yet-due) scheduled transfer's full [`TransferProposal`] fields, or
/// `null` if nothing is scheduled yet (or only the note-split prep transaction is pending).
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
        let context = migration_context(env, db_data, network_id, account_uuid)?;
        let proposal = context
            .pending_transfer_proposal()
            .map_err(|e| anyhow!("Error reading pending transfer proposal: {}", e))?;
        Ok(match proposal {
            Some(proposal) => encode_transfer_proposal(env, &proposal)?.into_raw(),
            None => ptr::null_mut(),
        })
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}
