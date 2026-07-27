use super::db::*;
use super::helpers::*;
use super::progress::*;
use super::*;
use orchard::primitives::redpallas::{Signature, SpendAuth, VerificationKey};
use std::collections::HashMap;

/// Builds a governance PCZT for one bundle from an explicitly supplied Orchard FVK.
///
/// `hotkeyStoredSecret` replaces the former `hotkeyRawAddress` parameter.
/// `zcash_voting` now takes a typed `DelegationKeys` whose only public
/// constructor is built from a `VotingHotkey`, and the raw address is derived
/// from the hotkey rather than supplied alongside it. Passing the address alone
/// can no longer reach that constructor.
#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildGovernancePcztNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    fvk_bytes: JByteArray<'local>,
    hotkey_stored_secret: JByteArray<'local>,
    network_id: jint,
    account_index: jint,
    notes: JObjectArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let network = voting_network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let fvk_bytes = java_bytes_exact(env, &fvk_bytes, "fvkBytes", ORCHARD_FVK_BYTES)?;
        let hotkey = java_voting_hotkey(env, &hotkey_stored_secret, network)?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;

        let notes = java_note_info_array(env, &notes, "notes")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let keys = delegation_keys(
            fvk_bytes,
            &hotkey,
            seed_fingerprint,
            account_index,
            round_name,
        )?;
        let pczt =
            build_governance_pczt_for_bundle(&db, &round_id, bundle_index, &notes, network, &keys)?;

        make_jni_governance_pczt(env, pczt)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Builds a governance PCZT for one bundle, validating the UFVK against the wallet seed.
#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildGovernancePcztFromSeedNative<
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
    hotkey_stored_secret: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let network = network_from_id(network_id)?;
        let voting_network = voting_network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let ufvk_str = java_string_to_rust(env, &ufvk)?;
        let fvk_bytes = orchard_fvk_bytes(&ufvk_str, network)?;
        let wallet_seed =
            java_secret_bytes_at_least(env, &wallet_seed, "walletSeed", PROTOCOL_FIELD_BYTES)?;
        let derived_fvk_bytes = orchard_fvk_bytes_from_wallet_seed(
            wallet_seed.expose_secret(),
            network,
            account_index,
        )?;
        if derived_fvk_bytes != fvk_bytes {
            return Err(anyhow!(
                "ufvk does not match walletSeed for network_id={network_id} account_index={account_index}"
            ));
        }
        let hotkey = java_voting_hotkey(env, &hotkey_stored_secret, voting_network)?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let keys = delegation_keys(
            fvk_bytes,
            &hotkey,
            seed_fingerprint,
            account_index,
            round_name,
        )?;
        let pczt = build_governance_pczt_for_bundle(
            &db,
            &round_id,
            bundle_index,
            &notes,
            voting_network,
            &keys,
        )?;

        make_jni_governance_pczt(env, pczt)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Assembles the typed delegation key material both PCZT paths and the prover need.
fn delegation_keys(
    fvk_bytes: Vec<u8>,
    hotkey: &VotingHotkey,
    seed_fingerprint: [u8; PROTOCOL_FIELD_BYTES],
    account_index: u32,
    round_name: String,
) -> anyhow::Result<voting::delegate::DelegationKeys> {
    voting::delegate::DelegationKeys::with_voting_hotkey(
        fvk_bytes,
        hotkey,
        seed_fingerprint,
        account_index,
        round_name,
    )
    .map_err(|e| anyhow!("failed to build delegation keys: {}", e))
}

/// Builds a governance PCZT for one deterministic bundle from the full snapshot note set.
///
/// Shared by the explicit-FVK Keystone path and the seed-validated software path. Callers must
/// provide already validated signer material; this helper verifies the bundle index, enforces the
/// round phase, persists the constructed delegation state, and advances the phase on success.
fn build_governance_pczt_for_bundle(
    db: &VotingDb,
    round_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
    network: VotingNetwork,
    keys: &voting::delegate::DelegationKeys,
) -> anyhow::Result<GovernancePczt> {
    let bundle_notes = bundled_notes_for_index(notes, bundle_index)?;
    require_round_phase_for_delegation_construction(db, round_id)?;
    let branch_id = round_consensus_branch_id(db, round_id, network)?;
    let pczt = db
        .build_governance_pczt(round_id, bundle_index, &bundle_notes, keys, branch_id)
        .map_err(|e| anyhow!("build_governance_pczt: {}", e))?;
    update_round_phase_forward(db, round_id, RoundPhase::DelegationConstructed)?;
    Ok(pczt)
}

/// Resolves the consensus branch active at the round's snapshot height.
///
/// `zcash_voting` now checks the supplied branch id against the stored round, so
/// a branch id pinned to one network upgrade would silently break every round
/// snapshotted after the next one. The branch is derived from the round instead.
fn round_consensus_branch_id(
    db: &VotingDb,
    round_id: &str,
    network: VotingNetwork,
) -> anyhow::Result<u32> {
    let snapshot_height = {
        let conn = db.conn();
        let wallet_id = db.wallet_id();
        voting::storage::queries::load_round_params(&conn, round_id, &wallet_id)
            .map_err(|e| anyhow!("load_round_params: {}", e))?
            .snapshot_height
    };
    voting::delegate::branch_id_for_height(network, snapshot_height)
        .map_err(|e| anyhow!("branch_id_for_height: {}", e))
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
        // zcash_voting owns the search: NU6.3 governance actions live in the
        // Ironwood bundle, not the Orchard one, and the PCZT builder shuffles
        // action order, so the crate falls back to scanning when the expected
        // index does not hold the signed action.
        let sig = voting::action::extract_spend_auth_sig(&bytes, action_index)
            .map_err(|e| anyhow!("extract_spend_auth_sig: {}", e))?;
        Ok(env.byte_array_from_slice(&sig)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractPcztOutputRecipientFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt_bytes: JByteArray<'local>,
    action_index: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let bytes = java_bytes(env, &pczt_bytes, "pcztBytes")?;
        let action_index = jint_to_usize(action_index, "action_index")?;
        let pczt = pczt::Pczt::parse(&bytes).map_err(|e| anyhow!("parse PCZT: {:?}", e))?;
        // Governance actions are Ironwood/V3 under NU6.3; the Orchard bundle of a
        // governance PCZT is empty.
        let action = pczt.ironwood().actions().get(action_index).ok_or_else(|| {
            anyhow!(
                "PCZT Ironwood action index {action_index} out of range; action_count={}",
                pczt.ironwood().actions().len()
            )
        })?;
        let recipient = action.output().recipient().as_ref().ok_or_else(|| {
            anyhow!("PCZT Ironwood action {action_index} output missing recipient")
        })?;
        Ok(env.byte_array_from_slice(recipient)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
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
        let network = voting_network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_client = connect_pir_client(&pir_url)?;
        let result = db
            .precompute_delegation_pir(&round_id, bundle_index, &bundle_notes, &pir_client, network)
            .map_err(|e| anyhow!("precompute_delegation_pir: {}", e))?;

        make_jni_delegation_pir_precompute_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Builds and proves the delegation ZKP for one bundle. Long-running.
///
/// The former `hotkeyRawAddress` parameter is replaced by the full delegation
/// key material (`fvkBytes`, `hotkeyStoredSecret`, `seedFingerprint`,
/// `accountIndex`, `roundName`): `zcash_voting` now takes the same typed
/// `DelegationKeys` the PCZT builder takes, and validates it against the stored
/// round rather than re-reading pieces of it from the database.
#[allow(clippy::too_many_arguments)]
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
    fvk_bytes: JByteArray<'local>,
    hotkey_stored_secret: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    account_index: jint,
    round_name: JString<'local>,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let network = voting_network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let fvk_bytes = java_bytes_exact(env, &fvk_bytes, "fvkBytes", ORCHARD_FVK_BYTES)?;
        let hotkey = java_voting_hotkey(env, &hotkey_stored_secret, network)?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let keys = delegation_keys(
            fvk_bytes,
            &hotkey,
            seed_fingerprint,
            account_index,
            round_name,
        )?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_client = connect_pir_client(&pir_url)?;
        let reporter = delegation_progress_reporter_from_callback(env, &progress_callback)?;
        let result = db
            .build_and_prove_delegation(
                &round_id,
                bundle_index,
                &bundle_notes,
                &keys,
                &pir_client,
                reporter.as_ref(),
            )
            .map_err(|e| anyhow!("build_and_prove_delegation: {}", e))?;

        make_jni_delegation_proof_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Assembles the delegation transaction payload from an externally produced signature.
///
/// This replaces both the former seed-signing entry point and its Keystone twin.
/// `zcash_voting` no longer derives account keys or signs on the caller's behalf:
/// every signer, software or hardware, now hands back a SpendAuth signature over
/// the ZIP-244 sighash, and the crate verifies it against the stored PCZT sighash.
/// The `senderSeed` / `networkId` / `accountIndex` parameters have no remaining
/// role, and the Keystone path needs no separate entry point.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationSubmissionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    spend_auth_sig: JByteArray<'local>,
    sighash: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let data = db
            .get_delegation_submission_with_signature(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                &java_bytes_exact(env, &spend_auth_sig, "spendAuthSig", SPEND_AUTH_SIG_BYTES)?,
                &java_bytes_exact(env, &sighash, "sighash", PROTOCOL_FIELD_BYTES)?,
            )
            .map_err(|e| anyhow!("get_delegation_submission_with_signature: {}", e))?;

        // Signatures are supplied externally; verify them at the bridge boundary.
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
    use orchard::keys::{FullViewingKey, SpendAuthorizingKey, SpendingKey};
    use orchard::primitives::redpallas::SigningKey;
    use rand::rngs::OsRng;
    use voting::types::VotingRoundParams;

    #[test]
    fn extract_spend_auth_sig_yields_a_signature_over_the_governance_rk_and_sighash() {
        let spending_key = SpendingKey::from_bytes([0x42; 32]).expect("valid spending key");
        let result = test_governance_pczt(&spending_key);

        let pczt = pczt::Pczt::parse(&result.pczt_bytes).expect("parse PCZT");
        let mut signer = pczt::roles::signer::Signer::new(pczt).expect("signer");
        let spend_authorizing_key = SpendAuthorizingKey::from(&spending_key);
        signer
            .sign_ironwood(result.action_index, &spend_authorizing_key)
            .expect("sign ironwood action");
        let signed_pczt = signer.finish().serialize().expect("serialize signed PCZT");

        let sig =
            voting::action::extract_spend_auth_sig(&signed_pczt, result.action_index).unwrap();
        let sighash = voting::action::extract_pczt_sighash(&signed_pczt).expect("pczt sighash");
        let data = delegation_submission_data(result.rk.clone(), sig.to_vec(), sighash.to_vec());

        // Asserting the signature is non-zero would prove nothing: the builder
        // already signs the zero-value padding action, so an unsigned governance
        // PCZT also yields bytes here. What distinguishes a signed one is that the
        // extracted signature verifies against the governance action's own rk.
        verify_delegation_submission_sig(&data).expect("governance signature verifies");
    }

    // The former `extract_spend_auth_sig_rejects_unsigned_governance_pczt` test is
    // gone. It asserted that extraction fails on an unsigned PCZT, which the
    // Ironwood builder made unreachable: it signs the padding action itself, and
    // `zcash_voting::action::extract_spend_auth_sig` deliberately falls back to
    // scanning every action, so extraction always succeeds. The signature check in
    // the test above is what now separates a genuinely signed governance PCZT from
    // an unsigned one.

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

    // `zcash_voting` made the standalone governance PCZT builder crate-private,
    // so a test PCZT now has to come from a real round: the builder validates the
    // bundle notes and the consensus branch against persisted round state.
    fn test_governance_pczt(spending_key: &SpendingKey) -> GovernancePczt {
        let fvk = FullViewingKey::from(spending_key);
        let hotkey = voting::hotkey::generate_random_voting_hotkey(VotingNetwork::Regtest)
            .expect("voting hotkey");
        let keys = delegation_keys(
            fvk.to_bytes().to_vec(),
            &hotkey,
            [0xAA; PROTOCOL_FIELD_BYTES],
            0,
            "Test Round".to_string(),
        )
        .expect("delegation keys");

        let db = VotingDb::open(":memory:").expect("test DB");
        db.set_wallet_id("wallet-id");
        db.init_round(VotingNetwork::Regtest, &round_params(), None)
            .expect("round initialized");
        let notes = [note_info()];
        db.ensure_bundles(&round_params().vote_round_id, &notes)
            .expect("bundles");

        db.build_governance_pczt(
            &round_params().vote_round_id,
            0,
            &notes,
            &keys,
            round_consensus_branch_id(&db, &round_params().vote_round_id, VotingNetwork::Regtest)
                .expect("branch id"),
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
            // zcash_voting builds Ironwood/V3 governance actions only, so the
            // round snapshot must sit at or after NU6.3 activation. Regtest is
            // the network whose NU6.3 activation this crate pins outright.
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
