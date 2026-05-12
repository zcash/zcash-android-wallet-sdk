use super::db::*;
use super::helpers::*;
use super::progress::*;
use super::*;
use orchard::primitives::redpallas::{Signature, SpendAuth, VerificationKey};
use std::collections::HashMap;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildGovernancePcztNative<
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
    notes: JObjectArray<'local>,
    wallet_seed: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
    address_index: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let network = network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let address_index = jint_to_u32(address_index, "address_index")?;
        let ufvk_str = java_string_to_rust(env, &ufvk)?;
        let fvk_bytes = orchard_fvk_bytes(&ufvk_str, network)?;

        let seed_bytes =
            java_secret_bytes_at_least(env, &wallet_seed, "walletSeed", PROTOCOL_FIELD_BYTES)?;
        let derived_fvk_bytes =
            orchard_fvk_bytes_from_wallet_seed(seed_bytes.expose_secret(), network, account_index)?;
        if derived_fvk_bytes != fvk_bytes {
            return Err(anyhow!(
                "ufvk does not match walletSeed for network_id={network_id} account_index={account_index}"
            ));
        }
        let hotkey_raw_address = hotkey_orchard_raw_address_from_wallet_seed(
            seed_bytes.expose_secret(),
            network,
            account_index,
            address_index,
        )?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;

        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;

        let round_id = java_string_to_rust(env, &round_id)?;
        require_round_phase_for_delegation_construction(&db, &round_id)?;
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
        update_round_phase_forward(&db, &round_id, RoundPhase::DelegationConstructed)?;

        make_jni_governance_pczt(env, pczt)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
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
        let action_index = jint_to_usize(action_index, "action_index")?;
        let sig = extract_indexed_spend_auth_sig(&bytes, action_index)?;
        Ok(env.byte_array_from_slice(&sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

fn extract_indexed_spend_auth_sig(
    signed_pczt_bytes: &[u8],
    action_index: usize,
) -> anyhow::Result<[u8; SPEND_AUTH_SIG_BYTES]> {
    let pczt = pczt::Pczt::parse(signed_pczt_bytes).map_err(|e| {
        anyhow!(
            "extract_spend_auth_sig: failed to parse signed PCZT: {:?}",
            e
        )
    })?;
    let actions = pczt.orchard().actions();
    if action_index < actions.len() {
        if let Some(sig) = actions[action_index].spend().spend_auth_sig() {
            return Ok(*sig);
        }

        return Err(anyhow!(
            "extract_spend_auth_sig: action {action_index} has no spend_auth_sig"
        ));
    }
    Err(anyhow!(
        "extract_spend_auth_sig: action_index {action_index} out of bounds for {} actions",
        actions.len()
    ))
}

fn connect_pir_client(pir_url: &str) -> anyhow::Result<voting::PirClientBlocking> {
    voting::PirClientBlocking::with_transport(pir_url, Arc::new(voting::HyperTransport::new()))
        .map_err(|e| anyhow!("connect to PIR server failed: {}", e))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeWitnessesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    notes: JObjectArray<'local>,
    witnesses: JObjectArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let witnesses = java_witness_data_array(env, &witnesses, "witnesses")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        require_witnesses_match_bundle(&db, &round_id, bundle_index, &bundle_notes, &witnesses)?;
        db.store_witnesses(&round_id, bundle_index, &witnesses)
            .map_err(|e| anyhow!("store_witnesses: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_precomputeDelegationPirNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    pir_server_url: JString<'local>,
    network_id: jint,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        network_from_id(network_id)?;
        let network_id = jint_to_u32(network_id, "network_id")?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_client = connect_pir_client(&pir_url)?;
        let result = db
            .precompute_delegation_pir(
                &round_id,
                bundle_index,
                &bundle_notes,
                &pir_client,
                network_id,
            )
            .map_err(|e| anyhow!("precompute_delegation_pir: {}", e))?;

        make_jni_delegation_pir_precompute_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildAndProveDelegationNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    pir_server_url: JString<'local>,
    network_id: jint,
    notes: JObjectArray<'local>,
    wallet_seed: JByteArray<'local>,
    account_index: jint,
    address_index: jint,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let network = network_from_id(network_id)?;
        let network_id = jint_to_u32(network_id, "network_id")?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let address_index = jint_to_u32(address_index, "address_index")?;
        let seed_bytes =
            java_secret_bytes_at_least(env, &wallet_seed, "walletSeed", PROTOCOL_FIELD_BYTES)?;
        let hotkey_raw_address = hotkey_orchard_raw_address_from_wallet_seed(
            seed_bytes.expose_secret(),
            network,
            account_index,
            address_index,
        )?;
        drop(seed_bytes);

        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_client = connect_pir_client(&pir_url)?;
        let reporter = progress_reporter_from_callback(env, &progress_callback)?;
        let result = db
            .build_and_prove_delegation(
                &round_id,
                bundle_index,
                &bundle_notes,
                &hotkey_raw_address,
                &pir_client,
                network_id,
                reporter.as_ref(),
            )
            .map_err(|e| anyhow!("build_and_prove_delegation: {}", e))?;

        make_jni_delegation_proof_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationSubmissionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    sender_seed: JByteArray<'local>,
    network_id: jint,
    account_index: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        network_from_id(network_id)?;
        let seed =
            java_secret_bytes_at_least(env, &sender_seed, "senderSeed", PROTOCOL_FIELD_BYTES)?;
        let data = db
            .get_delegation_submission(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                seed.expose_secret(),
                jint_to_u32(network_id, "network_id")?,
                jint_to_u32(account_index, "account_index")?,
            )
            .map_err(|e| anyhow!("get_delegation_submission: {}", e))?;

        verify_delegation_submission_sig(&data)?;
        make_jni_delegation_submission_result(env, data)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationSubmissionWithKeystoneSigNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    keystone_sig: JByteArray<'local>,
    keystone_sighash: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let data = db
            .get_delegation_submission_with_keystone_sig(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                &java_bytes_exact(env, &keystone_sig, "keystoneSig", SPEND_AUTH_SIG_BYTES)?,
                &java_bytes_exact(
                    env,
                    &keystone_sighash,
                    "keystoneSighash",
                    PROTOCOL_FIELD_BYTES,
                )?,
            )
            .map_err(|e| anyhow!("get_delegation_submission_with_keystone_sig: {}", e))?;

        // Keystone signatures are supplied externally; verify them at the bridge boundary.
        verify_delegation_submission_sig(&data)?;
        make_jni_delegation_submission_result(env, data)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_delegationProofResultFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        make_jni_delegation_proof_result(
            env,
            DelegationProofResult {
                proof: vec![0xA0; 96],
                public_inputs: fixed_field_vec(0x10, DELEGATION_PUBLIC_INPUT_COUNT),
                nf_signed: vec![0x21; PROTOCOL_FIELD_BYTES],
                cmx_new: vec![0x22; PROTOCOL_FIELD_BYTES],
                gov_nullifiers: fixed_field_vec(0x30, GOVERNANCE_NULLIFIER_COUNT),
                van_comm: vec![0x41; PROTOCOL_FIELD_BYTES],
                rk: vec![0x42; PROTOCOL_FIELD_BYTES],
            },
        )
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeDelegationProofFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proof: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proof = java_bytes(env, &proof, "proof")?;
        let conn = db.conn();
        let wallet_id = db.wallet_id();
        voting::storage::queries::store_proof(&conn, &round_id, &wallet_id, bundle_index, &proof)
            .map_err(|e| anyhow!("store_proof fixture: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

fn require_bundle_notes_match(
    db: &VotingDb,
    round_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
) -> anyhow::Result<()> {
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    voting::storage::queries::require_bundle_notes(&conn, round_id, &wallet_id, bundle_index, notes)
        .map_err(|e| anyhow!("bundle notes do not match persisted setup: {}", e))
}

fn require_witnesses_match_bundle(
    db: &VotingDb,
    round_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
    witnesses: &[WitnessData],
) -> anyhow::Result<()> {
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    voting::storage::queries::require_bundle_notes(
        &conn,
        round_id,
        &wallet_id,
        bundle_index,
        notes,
    )
    .map_err(|e| anyhow!("bundle notes do not match persisted setup: {}", e))?;
    let params = voting::storage::queries::load_round_params(&conn, round_id, &wallet_id)
        .map_err(|e| anyhow!("load_round_params: {}", e))?;

    if witnesses.len() != notes.len() {
        return Err(anyhow!(
            "witness count ({}) does not match selected bundle note count ({})",
            witnesses.len(),
            notes.len()
        ));
    }

    let mut witnesses_by_commitment = HashMap::with_capacity(witnesses.len());
    for (index, witness) in witnesses.iter().enumerate() {
        if witness.root != params.nc_root {
            return Err(anyhow!(
                "witness[{index}].root does not match round nc_root"
            ));
        }
        if witnesses_by_commitment
            .insert(witness.note_commitment.as_slice(), witness)
            .is_some()
        {
            return Err(anyhow!(
                "duplicate witness note_commitment at witness[{index}]"
            ));
        }
    }

    for (index, note) in notes.iter().enumerate() {
        let Some(witness) = witnesses_by_commitment.get(note.commitment.as_slice()) else {
            return Err(anyhow!(
                "missing witness for selected note[{index}] commitment {}",
                hex::encode(&note.commitment)
            ));
        };
        if witness.position != note.position {
            return Err(anyhow!(
                "witness for selected note[{index}] has position {}, expected {}",
                witness.position,
                note.position
            ));
        }
    }

    Ok(())
}

fn require_round_phase_not_after(
    db: &VotingDb,
    round_id: &str,
    max_phase: RoundPhase,
) -> anyhow::Result<()> {
    let state = db
        .get_round_state(round_id)
        .map_err(|e| anyhow!("get_round_state: {}", e))?;
    if state.phase as i32 > max_phase as i32 {
        return Err(anyhow!(
            "round {round_id} is already past {:?}: {:?}",
            max_phase,
            state.phase
        ));
    }

    Ok(())
}

fn verify_delegation_submission_sig(data: &DelegationSubmissionData) -> anyhow::Result<()> {
    let rk = fixed_bytes::<PROTOCOL_FIELD_BYTES>(data.rk.clone(), "rk")?;
    let sighash = fixed_bytes::<PROTOCOL_FIELD_BYTES>(data.sighash.clone(), "sighash")?;
    let sig = fixed_bytes::<SPEND_AUTH_SIG_BYTES>(data.spend_auth_sig.clone(), "spend_auth_sig")?;
    let vk = VerificationKey::<SpendAuth>::try_from(rk)
        .map_err(|_| anyhow!("rk is not a valid spend authorization verification key"))?;
    if vk.is_identity() {
        return Err(anyhow!(
            "rk is not a valid spend authorization verification key"
        ));
    }

    vk.verify(&sighash, &Signature::<SpendAuth>::from(sig))
        .map_err(|_| anyhow!("spend_auth_sig does not verify against rk and sighash"))
}

#[cfg(feature = "android-test-fixtures")]
fn fixed_field_vec(start: u8, count: usize) -> Vec<Vec<u8>> {
    (0..count)
        .map(|index| vec![start.wrapping_add(index as u8); PROTOCOL_FIELD_BYTES])
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use orchard::keys::{FullViewingKey, Scope, SpendAuthorizingKey, SpendingKey};
    use orchard::primitives::redpallas::SigningKey;
    use rand::rngs::OsRng;
    use voting::types::VotingRoundParams;

    #[test]
    fn extract_spend_auth_sig_accepts_signed_governance_pczt() {
        let spending_key = SpendingKey::from_bytes([0x42; 32]).expect("valid spending key");
        let fvk = FullViewingKey::from(&spending_key);
        let hotkey_spending_key = SpendingKey::from_bytes([0x43; 32]).expect("valid hotkey");
        let hotkey_fvk = FullViewingKey::from(&hotkey_spending_key);
        let hotkey_address = hotkey_fvk
            .address_at(0u32, Scope::External)
            .to_raw_address_bytes()
            .to_vec();
        let result = voting::action::build_governance_pczt(
            &[note_info()],
            &round_params(),
            &fvk.to_bytes().to_vec(),
            &hotkey_address,
            nu6_branch_id(),
            Network::TestNetwork.coin_type(),
            &[0xAA; 32],
            0,
            "Test Round",
        )
        .expect("governance PCZT");

        let pczt = pczt::Pczt::parse(&result.pczt_bytes).expect("parse PCZT");
        let mut signer = pczt::roles::signer::Signer::new(pczt).expect("signer");
        let spend_authorizing_key = SpendAuthorizingKey::from(&spending_key);
        signer
            .sign_orchard(result.action_index, &spend_authorizing_key)
            .expect("sign orchard action");
        let signed_pczt = signer.finish().serialize();
        let sig = extract_indexed_spend_auth_sig(&signed_pczt, result.action_index).unwrap();

        assert_ne!(sig, [0u8; 64]);
    }

    #[test]
    fn extract_spend_auth_sig_rejects_unsigned_governance_pczt() {
        let result = test_governance_pczt();
        let err =
            extract_indexed_spend_auth_sig(&result.pczt_bytes, result.action_index).unwrap_err();

        assert!(err.to_string().contains("has no spend_auth_sig"));
    }

    #[test]
    fn delegation_submission_sig_verification_checks_rk_and_sighash() {
        let mut signing_key_bytes = [0u8; PROTOCOL_FIELD_BYTES];
        signing_key_bytes[0] = 1;
        let signing_key =
            SigningKey::<SpendAuth>::try_from(signing_key_bytes).expect("valid signing key");
        let verification_key = VerificationKey::<SpendAuth>::from(&signing_key);
        let rk: [u8; PROTOCOL_FIELD_BYTES] = (&verification_key).into();
        let sighash = vec![0xAA; PROTOCOL_FIELD_BYTES];
        let sig: [u8; SPEND_AUTH_SIG_BYTES] = (&signing_key.sign(OsRng, &sighash)).into();
        let data = delegation_submission_data(rk.to_vec(), sig.to_vec(), sighash);

        verify_delegation_submission_sig(&data).expect("matching signature verifies");

        let mut bad_sig = data.clone();
        bad_sig.spend_auth_sig[0] ^= 1;
        assert!(verify_delegation_submission_sig(&bad_sig).is_err());

        let mut bad_sighash = data.clone();
        bad_sighash.sighash[0] ^= 1;
        assert!(verify_delegation_submission_sig(&bad_sighash).is_err());
    }

    fn test_governance_pczt() -> GovernancePczt {
        let spending_key = SpendingKey::from_bytes([0x42; 32]).expect("valid spending key");
        let fvk = FullViewingKey::from(&spending_key);
        let hotkey_spending_key = SpendingKey::from_bytes([0x43; 32]).expect("valid hotkey");
        let hotkey_fvk = FullViewingKey::from(&hotkey_spending_key);
        let hotkey_address = hotkey_fvk
            .address_at(0u32, Scope::External)
            .to_raw_address_bytes()
            .to_vec();
        voting::action::build_governance_pczt(
            &[note_info()],
            &round_params(),
            &fvk.to_bytes().to_vec(),
            &hotkey_address,
            nu6_branch_id(),
            Network::TestNetwork.coin_type(),
            &[0xAA; 32],
            0,
            "Test Round",
        )
        .expect("governance PCZT")
    }

    fn note_info() -> NoteInfo {
        NoteInfo {
            commitment: vec![1; PROTOCOL_FIELD_BYTES],
            nullifier: vec![2; PROTOCOL_FIELD_BYTES],
            value: 15_000_000,
            position: 0,
            diversifier: vec![0; 11],
            rho: vec![0; PROTOCOL_FIELD_BYTES],
            rseed: vec![0; PROTOCOL_FIELD_BYTES],
            scope: 0,
            ufvk_str: String::new(),
        }
    }

    fn round_params() -> VotingRoundParams {
        VotingRoundParams {
            vote_round_id: "0101010101010101010101010101010101010101010101010101010101010101"
                .to_string(),
            snapshot_height: 100_000,
            ea_pk: vec![0xEA; PROTOCOL_FIELD_BYTES],
            nc_root: vec![0x01; PROTOCOL_FIELD_BYTES],
            nullifier_imt_root: vec![0x02; PROTOCOL_FIELD_BYTES],
        }
    }

    fn delegation_submission_data(
        rk: Vec<u8>,
        spend_auth_sig: Vec<u8>,
        sighash: Vec<u8>,
    ) -> DelegationSubmissionData {
        DelegationSubmissionData {
            proof: vec![1; 3],
            rk,
            nf_signed: vec![3; PROTOCOL_FIELD_BYTES],
            cmx_new: vec![4; PROTOCOL_FIELD_BYTES],
            gov_comm: vec![5; PROTOCOL_FIELD_BYTES],
            gov_nullifiers: vec![vec![6; PROTOCOL_FIELD_BYTES]; GOVERNANCE_NULLIFIER_COUNT],
            alpha: vec![7; PROTOCOL_FIELD_BYTES],
            vote_round_id: "round-1".to_string(),
            spend_auth_sig,
            sighash,
        }
    }
}
