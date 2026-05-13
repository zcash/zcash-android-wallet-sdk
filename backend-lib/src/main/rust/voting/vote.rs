use super::db::*;
use super::helpers::*;
use super::progress::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_decomposeWeightNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    weight: jlong,
) -> jlongArray {
    let res = catch_unwind(&mut env, |env| {
        let parts = voting::decompose::decompose_weight(jlong_to_u64(weight, "weight")?)
            .into_iter()
            .map(|part| u64_to_jlong(part, "weight share"))
            .collect::<anyhow::Result<Vec<_>>>()?;
        let array = env.new_long_array(usize_to_jint(parts.len(), "weight share count")?)?;
        env.set_long_array_region(&array, 0, &parts)?;
        Ok(array.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildSharePayloadsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    commitment: JObject<'local>,
    vote_decision: jint,
    num_options: jint,
    vc_tree_position: jlong,
    single_share_mode: jboolean,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let commitment = java_vote_commitment_bundle(env, &commitment)?;
        let payloads = voting::vote_commitment::build_share_payloads(
            &commitment.enc_shares,
            &commitment.bundle,
            jint_to_u32(vote_decision, "vote_decision")?,
            jint_to_u32(num_options, "num_options")?,
            jlong_to_u64(vc_tree_position, "vc_tree_position")?,
            single_share_mode == JNI_TRUE,
        )
        .map_err(|e| anyhow!("build_share_payloads: {}", e))?;
        make_jni_share_payload_array(env, payloads)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_signCastVoteNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    hotkey_seed: JByteArray<'local>,
    network_id: jint,
    account_index: jint,
    commitment: JObject<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        require_supported_vote_account(account_index)?;
        let hotkey_seed =
            java_secret_bytes_at_least(env, &hotkey_seed, "hotkey_seed", PROTOCOL_FIELD_BYTES)?;
        let commitment = java_vote_commitment_bundle(env, &commitment)?.bundle;
        let sig = voting::vote_commitment::sign_cast_vote(
            hotkey_seed.expose_secret(),
            u32::try_from(network_id)
                .map_err(|_| anyhow!("network_id must be non-negative, got {network_id}"))?,
            &commitment.vote_round_id,
            &commitment.r_vpk_bytes,
            &commitment.van_nullifier,
            &commitment.vote_authority_note_new,
            &commitment.vote_commitment,
            commitment.proposal_id,
            commitment.anchor_height,
            &commitment.alpha_v,
        )
        .map_err(|e| anyhow!("sign_cast_vote: {}", e))?;
        Ok(env.byte_array_from_slice(&sig.vote_auth_sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildVoteCommitmentNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    hotkey_seed: JByteArray<'local>,
    proposal_id: jint,
    choice: jint,
    num_options: jint,
    witness: JObject<'local>,
    network_id: jint,
    account_index: jint,
    single_share: jboolean,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_supported_vote_account(account_index)?;
        let hotkey_seed =
            java_secret_bytes_at_least(env, &hotkey_seed, "hotkey_seed", PROTOCOL_FIELD_BYTES)?;
        let witness = java_van_witness(env, &witness)?;
        require_delegation_ready_for_vote(
            &db,
            &round_id,
            jint_to_u32(bundle_index, "bundle_index")?,
            &witness,
        )?;
        let reporter = progress_reporter_from_callback(env, &progress_callback)?;
        let bundle = db
            .build_vote_commitment(
                &round_id,
                jint_to_u32(bundle_index, "bundle_index")?,
                hotkey_seed.expose_secret(),
                u32::try_from(network_id)
                    .map_err(|_| anyhow!("network_id must be non-negative, got {network_id}"))?,
                jint_to_u32(proposal_id, "proposal_id")?,
                jint_to_u32(choice, "choice")?,
                jint_to_u32(num_options, "num_options")?,
                &witness.auth_path,
                witness.position,
                witness.anchor_height,
                single_share == JNI_TRUE,
                reporter.as_ref(),
            )
            .map_err(|e| anyhow!("build_vote_commitment: {}", e))?;
        make_jni_vote_commitment_result(env, bundle)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

fn require_supported_vote_account(account_index: jint) -> anyhow::Result<()> {
    let account_index = jint_to_u32(account_index, "account_index")?;
    if account_index == 0 {
        Ok(())
    } else {
        Err(anyhow!(
            "vote construction currently supports only account_index=0, got {account_index}"
        ))
    }
}

fn require_delegation_ready_for_vote(
    db: &VotingDbHandle,
    round_id: &str,
    bundle_index: u32,
    witness: &voting::tree_sync::VanWitness,
) -> anyhow::Result<()> {
    // Callers hold VotingDbHandle::access_lock while this read-check sequence
    // runs. Managed handles for the same DB path and wallet share that lock, so
    // SDK-exposed writers cannot change delegation/VAN state between these
    // reads and the subsequent vote commitment build.
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    voting::storage::queries::load_delegation_submission_data(
        &conn,
        round_id,
        &wallet_id,
        bundle_index,
    )
    .map_err(|e| anyhow!("delegation proof is not ready for vote commitment: {e}"))?;

    let stored_position =
        voting::storage::queries::load_van_position(&conn, round_id, &wallet_id, bundle_index)
            .map_err(|e| anyhow!("VAN position is not ready for vote commitment: {e}"))?;
    if stored_position != witness.position {
        return Err(anyhow!(
            "VAN witness position {} does not match stored VAN position {} for round={} bundle={}",
            witness.position,
            stored_position,
            round_id,
            bundle_index
        ));
    }

    Ok(())
}
