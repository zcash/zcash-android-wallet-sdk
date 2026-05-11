use super::db::*;
use super::helpers::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_initRoundNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    snapshot_height: jlong,
    ea_pk: JByteArray<'local>,
    nc_root: JByteArray<'local>,
    nullifier_imt_root: JByteArray<'local>,
    session_json: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let params = voting::types::VotingRoundParams {
            vote_round_id: java_string_to_rust(env, &round_id)?,
            snapshot_height: jlong_to_u64(snapshot_height, "snapshot_height")?,
            ea_pk: java_bytes_exact(env, &ea_pk, "ea_pk", PROTOCOL_FIELD_BYTES)?,
            nc_root: java_bytes_exact(env, &nc_root, "nc_root", PROTOCOL_FIELD_BYTES)?,
            nullifier_imt_root: java_bytes_exact(
                env,
                &nullifier_imt_root,
                "nullifier_imt_root",
                PROTOCOL_FIELD_BYTES,
            )?,
        };
        let session = java_nullable_string_to_rust(env, &session_json)?;
        db.init_round(&params, session.as_deref())
            .map_err(|e| anyhow!("init_round: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getRoundStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        if !round_exists(&db, &round_id)? {
            Ok(JObject::null().into_raw())
        } else {
            let state = db
                .get_round_state(&round_id)
                .map_err(|e| anyhow!("get_round_state: {}", e))?;
            make_ffi_round_state(env, state)
        }
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_listRoundsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let rounds = db
            .list_rounds()
            .map_err(|e| anyhow!("list_rounds: {}", e))?;
        make_jni_round_summaries(env, rounds)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getVotesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let votes = db
            .get_votes(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_votes: {}", e))?;
        make_jni_vote_records(env, votes)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_clearRoundNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        db.clear_round(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("clear_round: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_deleteSkippedBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    keep_count: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let deleted_rows = db
            .delete_skipped_bundles(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(keep_count, "keep_count")?,
            )
            .map_err(|e| anyhow!("delete_skipped_bundles: {}", e))?;
        Ok(deleted_rows as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}
