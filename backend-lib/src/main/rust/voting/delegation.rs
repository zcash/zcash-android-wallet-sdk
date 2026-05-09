use super::db::*;
use super::helpers::*;
use super::json::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildGovernancePcztJsonNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    ufvk: JString<'local>,
    network_id: jint,
    account_index: jint,
    notes_json: JString<'local>,
    wallet_seed: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
    address_index: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let network = network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let address_index = jint_to_u32(address_index, "address_index")?;
        let ufvk_str = java_string_to_rust(env, &ufvk)?;
        let fvk_bytes = orchard_fvk_bytes(&ufvk_str, network)?;

        let seed_bytes =
            java_secret_bytes_at_least(env, &wallet_seed, "walletSeed", PROTOCOL_FIELD_BYTES)?;
        let hotkey_raw_address = hotkey_orchard_raw_address_from_wallet_seed(
            seed_bytes.expose_secret(),
            network,
            account_index,
            address_index,
        )?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;

        let json_notes: Vec<JsonNoteInfo> = json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;

        let round_id = java_string_to_rust(env, &round_id)?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let pczt = db
            .build_governance_pczt(
                &round_id,
                bundle_index,
                &bundle_notes,
                &fvk_bytes,
                &hotkey_raw_address,
                nu6_branch_id(),
                network.coin_type(),
                &seed_fingerprint,
                account_index,
                &round_name,
                address_index,
            )
            .map_err(|e| anyhow!("build_governance_pczt: {}", e))?;

        json_to_jstring(env, &JsonGovernancePczt::try_from(pczt)?)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractPcztSighashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes(env, &pczt_bytes, "pcztBytes")?;
        let sighash = voting::action::extract_pczt_sighash(&bytes)
            .map_err(|e| anyhow!("extract_pczt_sighash: {}", e))?;
        Ok(env.byte_array_from_slice(&sighash)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractSpendAuthSigNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    signed_pczt_bytes: JByteArray<'local>,
    action_index: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes(env, &signed_pczt_bytes, "signedPcztBytes")?;
        let sig = voting::action::extract_spend_auth_sig(
            &bytes,
            jint_to_usize(action_index, "action_index")?,
        )
        .map_err(|e| anyhow!("extract_spend_auth_sig: {}", e))?;
        Ok(env.byte_array_from_slice(&sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
