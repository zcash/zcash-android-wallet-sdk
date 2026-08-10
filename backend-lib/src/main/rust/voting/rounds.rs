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
        let _access_lock = db.access_lock()?;
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
        db.init_round(db.network, &params, session.as_deref())
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
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        if !db
            .has_round(&round_id)
            .map_err(|e| anyhow!("has_round: {}", e))?
        {
            Ok(JObject::null().into_raw())
        } else {
            let state = db
                .get_round_state(&round_id)
                .map_err(|e| anyhow!("get_round_state: {}", e))?;
            make_jni_round_state(env, state)
        }
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Returns the canonical, per-bundle delegation phase for every bundle in a round (`prepared`,
/// `pczt_built`, `proved`, `submitted`, `confirmed` — see `zcash_voting::phases::DelegationPhase`),
/// derived on read from persisted artifacts rather than the coarse round-level `phase` column.
/// This is the primitive multi-bundle-aware callers should use instead of `getRoundStateNative`'s
/// round-level phase for per-bundle "is this already done" decisions (2026-08-10, see
/// `build_governance_pczt_for_bundle`'s doc comment for why the round-level phase can't do this).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_delegationPhasesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let phases = db
            .delegation_phases(&round_id)
            .map_err(|e| anyhow!("delegation_phases: {}", e))?;
        make_jni_delegation_phases(env, phases)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Clears unsigned/unproved delegation setup fields for one round (preserving submitted bundles
/// and bundles with persisted Keystone signatures) so an interrupted or corrupted per-bundle setup
/// can be safely rebuilt from scratch. Wraps `zcash_voting::precompute::reset_voting_session_state`
/// — the crate's sanctioned recovery path (also drops the process-local vote-tree cache for this
/// round). Callers should treat this as the response to a `refusing to overwrite pczt_sighash`
/// (or similar) error from `buildGovernancePcztNative`/`buildGovernancePcztFromSeedNative`, not a
/// routine call.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_resetVotingSessionStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        voting::precompute::reset_voting_session_state(&db, &round_id)
            .map_err(|e| anyhow!("reset_voting_session_state: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
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
        let _access_lock = db.access_lock()?;
        let rounds = db
            .list_rounds()
            .map_err(|e| anyhow!("list_rounds: {}", e))?;
        make_jni_round_summaries(env, rounds)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getBundleCountNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let count = db
            .get_bundle_count(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_bundle_count: {}", e))?;
        u32_to_jint(count, "bundle_count")
    });
    unwrap_exc_or(&mut env, res, -1)
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
        let _access_lock = db.access_lock()?;
        // VoteRecord no longer carries a `submitted` flag in zcash_voting 1.0;
        // the round recovery snapshot's tx_hash presence stands in for it.
        let round_id = java_string_to_rust(env, &round_id)?;
        let snapshot = voting::recovery::round_snapshot(&db, &round_id)
            .map_err(|e| anyhow!("round_snapshot: {}", e))?;
        make_jni_vote_records(env, snapshot.votes)
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
        let _access_lock = db.access_lock()?;
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
        let _access_lock = db.access_lock()?;
        let deleted_rows = db
            .delete_skipped_bundles(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(keep_count, "keep_count")?,
            )
            .map_err(|e| anyhow!("delete_skipped_bundles: {}", e))?;
        u64_to_jlong(deleted_rows, "deleted_rows")
    });
    unwrap_exc_or(&mut env, res, -1)
}
