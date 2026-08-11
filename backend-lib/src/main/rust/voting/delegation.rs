use super::db::*;
use super::helpers::*;
use super::notes::open_wallet_db_read_only;
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
    fvk_bytes: JByteArray<'local>,
    hotkey_secret: JByteArray<'local>,
    account_index: jint,
    notes: JObjectArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let fvk_bytes = java_bytes_exact(env, &fvk_bytes, "fvkBytes", ORCHARD_FVK_BYTES)?;
        let hotkey_secret = java_secret_bytes_at_least(
            env,
            &hotkey_secret,
            "hotkeySecret",
            HOTKEY_STORED_SECRET_BYTES,
        )?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;

        let notes = java_note_info_array(env, &notes, "notes")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let pczt = build_governance_pczt_for_bundle(
            &db,
            &round_id,
            bundle_index,
            &notes,
            fvk_bytes,
            hotkey_secret.expose_secret(),
            &seed_fingerprint,
            account_index,
            &round_name,
        )?;

        make_jni_governance_pczt(env, pczt)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

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
    hotkey_secret: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    round_name: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        // network_id here only validates walletSeed against the caller-supplied
        // ufvk (a zcash_protocol::consensus::Network concern); the delegation
        // hotkey's own voting::types::Network still rides the db handle.
        let network = network_from_id(network_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let ufvk_str = java_string_to_rust(env, &ufvk)?;
        let fvk_bytes = orchard_fvk_bytes(&ufvk_str, network)?;
        let wallet_seed =
            java_secret_bytes_at_least(env, &wallet_seed, "walletSeed", PROTOCOL_FIELD_BYTES)?;
        let hotkey_secret = java_secret_bytes_at_least(
            env,
            &hotkey_secret,
            "hotkeySecret",
            HOTKEY_STORED_SECRET_BYTES,
        )?;
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
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let pczt = build_governance_pczt_for_bundle(
            &db,
            &round_id,
            bundle_index,
            &notes,
            fvk_bytes,
            hotkey_secret.expose_secret(),
            &seed_fingerprint,
            account_index,
            &round_name,
        )?;

        make_jni_governance_pczt(env, pczt)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Builds a governance PCZT for one deterministic bundle from the full snapshot note set.
///
/// Shared by the explicit-FVK Keystone path and the seed-validated software path. Callers must
/// provide already validated signer material; this helper verifies the bundle index and persists
/// the constructed delegation state.
///
/// Deliberately does NOT touch the round-level `phase` column (the crate's own
/// per-bundle `DelegationPhase`, derived on read from persisted artifacts via
/// `zcash_voting::phases`, is the source of truth for "has this bundle been constructed" —
/// mirrors upstream `zcash_voting::delegate::setup`/Vizor, which does zero phase manipulation
/// here. The round `phase` column only ever advances via `build_and_prove_delegation` and
/// `build_vote_commitment`. A prior version advanced it to `HotkeyGenerated`/`DelegationConstructed`
/// here too, which made this call fail with "refusing to regress round phase" for any bundle
/// after the first once an earlier bundle had already been proved — multi-bundle rounds always
/// crashed constructing bundle 1+ with a NULL `alpha` at prove time).
#[allow(clippy::too_many_arguments)]
fn build_governance_pczt_for_bundle(
    db: &VotingDbHandle,
    round_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
    fvk_bytes: Vec<u8>,
    hotkey_secret: &[u8],
    seed_fingerprint: &[u8; PROTOCOL_FIELD_BYTES],
    account_index: u32,
    round_name: &str,
) -> anyhow::Result<GovernancePczt> {
    let bundle_notes = bundled_notes_for_index(notes, bundle_index)?;

    let hotkey = voting::types::VotingHotkey::from_stored_secret(hotkey_secret, db.network)
        .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?;
    let keys = voting::delegate::DelegationKeys::with_voting_hotkey(
        fvk_bytes,
        &hotkey,
        *seed_fingerprint,
        account_index,
        round_name.to_string(),
    )
    .map_err(|e| anyhow!("DelegationKeys::with_voting_hotkey: {}", e))?;

    // build_governance_pczt now validates the branch id against the round's
    // own snapshot_height (see lwd::branch_id_for_height), so it must be
    // resolved per round instead of the old hardcoded Nu6 constant.
    let round_state = db
        .get_round_state(round_id)
        .map_err(|e| anyhow!("get_round_state: {}", e))?;
    let consensus_branch_id =
        voting::delegate::branch_id_for_height(db.network, round_state.snapshot_height)
            .map_err(|e| anyhow!("branch_id_for_height: {}", e))?;

    let pczt = db
        .build_governance_pczt(
            round_id,
            bundle_index,
            &bundle_notes,
            &keys,
            consensus_branch_id,
        )
        .map_err(|e| anyhow!("build_governance_pczt: {}", e))?;
    Ok(pczt)
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
        // Governance actions for Ironwood/NU6.3 voting notes ride the PCZT's
        // ironwood bundle unconditionally (zcash_voting::action::build_governance_pczt),
        // not the orchard bundle.
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
    // Governance actions for Ironwood/NU6.3 voting notes ride the PCZT's
    // ironwood bundle unconditionally (zcash_voting::action::build_governance_pczt),
    // not the orchard bundle.
    let actions = pczt.ironwood().actions();
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

fn connect_pir_client(
    pir_url: &str,
    pir_layout: voting::config::PirLayout,
) -> anyhow::Result<voting::PirClientBlocking> {
    voting::connect_pir_blocking(pir_layout, pir_url, Arc::new(voting::HyperTransport::new()))
        .map_err(|e| anyhow!("connect to PIR server failed: {}", e))
}

fn pir_layout_from_jni(
    pir_depth: jint,
    tier0_layers: jint,
    tier1_layers: jint,
) -> anyhow::Result<voting::config::PirLayout> {
    Ok(voting::config::PirLayout {
        pir_depth: jint_to_u32(pir_depth, "pir_depth")?,
        tier0_layers: jint_to_u32(tier0_layers, "tier0_layers")?,
        tier1_layers: jint_to_u32(tier1_layers, "tier1_layers")?,
    })
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
    pir_depth: jint,
    pir_tier0_layers: jint,
    pir_tier1_layers: jint,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;
        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout = pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout)?;
        let result = db
            .precompute_delegation_pir(
                &round_id,
                bundle_index,
                &bundle_notes,
                &pir_client,
                db.network,
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
    pir_depth: jint,
    pir_tier0_layers: jint,
    pir_tier1_layers: jint,
    notes: JObjectArray<'local>,
    fvk_bytes: JByteArray<'local>,
    hotkey_secret: JByteArray<'local>,
    seed_fingerprint: JByteArray<'local>,
    account_index: jint,
    round_name: JString<'local>,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let bundle_notes = bundled_notes_for_index(&notes, bundle_index)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)?;
        require_bundle_notes_match(&db, &round_id, bundle_index, &bundle_notes)?;

        let fvk_bytes = java_bytes_exact(env, &fvk_bytes, "fvkBytes", ORCHARD_FVK_BYTES)?;
        let hotkey_secret = java_secret_bytes_at_least(
            env,
            &hotkey_secret,
            "hotkeySecret",
            HOTKEY_STORED_SECRET_BYTES,
        )?;
        let seed_fingerprint = java_bytes32(env, &seed_fingerprint, "seedFingerprint")?;
        let account_index = jint_to_u32(account_index, "account_index")?;
        let round_name = java_string_to_rust(env, &round_name)?;

        let hotkey = voting::types::VotingHotkey::from_stored_secret(
            hotkey_secret.expose_secret(),
            db.network,
        )
        .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?;
        let keys = voting::delegate::DelegationKeys::with_voting_hotkey(
            fvk_bytes,
            &hotkey,
            seed_fingerprint,
            account_index,
            round_name,
        )
        .map_err(|e| anyhow!("DelegationKeys::with_voting_hotkey: {}", e))?;

        let pir_url = java_string_to_rust(env, &pir_server_url)?;
        let pir_layout = pir_layout_from_jni(pir_depth, pir_tier0_layers, pir_tier1_layers)?;
        let pir_client = connect_pir_client(&pir_url, pir_layout)?;
        let reporter = progress_reporter_from_callback(env, &progress_callback)?;
        let stages = DelegationProgressReporterBridge(reporter.as_ref());
        let result = db
            .build_and_prove_delegation(
                &round_id,
                bundle_index,
                &bundle_notes,
                &keys,
                &pir_client,
                &stages,
            )
            .map_err(|e| anyhow!("build_and_prove_delegation: {}", e))?;

        make_jni_delegation_proof_result(env, result)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Forwards `build_and_prove_delegation`'s coarse-grained proof progress to the
/// existing `ProgressReporter` callback bridge, mirroring the blanket
/// `DelegationProgressReporter for ProgressReporter` impl (including its
/// `[0.0, 1.0]` clamp) that stable trait-object coercion cannot reach through
/// a `&dyn` reference of a different trait.
struct DelegationProgressReporterBridge<'a>(&'a dyn ProgressReporter);

impl voting::types::DelegationProgressReporter for DelegationProgressReporterBridge<'_> {
    fn on_progress(&self, progress: voting::delegate::DelegationProgress) {
        if let voting::delegate::DelegationProgress::ProofProgress(value) = progress {
            self.0.on_progress(value.clamp(0.0, 1.0));
        }
    }
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
    wallet_db_path: JString<'local>,
    account_uuid: JString<'local>,
    hotkey_secret: JByteArray<'local>,
    round_name: JString<'local>,
    sender_seed: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let wallet_db_path = java_string_to_rust(env, &wallet_db_path)?;
        let account_uuid = java_string_to_rust(env, &account_uuid)?;
        let hotkey_secret = java_secret_bytes_at_least(
            env,
            &hotkey_secret,
            "hotkeySecret",
            HOTKEY_STORED_SECRET_BYTES,
        )?;
        let round_name = java_string_to_rust(env, &round_name)?;
        let seed =
            java_secret_bytes_at_least(env, &sender_seed, "senderSeed", PROTOCOL_FIELD_BYTES)?;

        let keys = gather_delegation_keys_for_submission(
            &db,
            &round_id,
            &wallet_db_path,
            &account_uuid,
            hotkey_secret.expose_secret(),
            &round_name,
        )?;
        let request = voting::delegate::signing_request(&db, &round_id, bundle_index, &keys)
            .map_err(|e| anyhow!("signing_request: {}", e))?;

        let sig = sign_delegation_sighash_with_seed(seed.expose_secret(), &request)?;
        let data = db
            .get_delegation_submission_with_signature(
                &round_id,
                bundle_index,
                &sig,
                &request.sighash,
            )
            .map_err(|e| anyhow!("get_delegation_submission_with_signature: {}", e))?;

        verify_delegation_submission_sig(&data)?;
        make_jni_delegation_submission_result(env, data)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Reconstructs the same `DelegationKeys` used at PCZT-setup time from wallet
/// state, so `delegate::signing_request` can load the persisted signing fields
/// for this bundle. Mirrors the iOS FFI's shared delegation-gather helper.
fn gather_delegation_keys_for_submission(
    db: &VotingDbHandle,
    round_id: &str,
    wallet_db_path: &str,
    account_uuid: &str,
    hotkey_secret: &[u8],
    round_name: &str,
) -> anyhow::Result<voting::delegate::DelegationKeys> {
    let hotkey = voting::types::VotingHotkey::from_stored_secret(hotkey_secret, db.network)
        .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?;

    let round_state = db
        .get_round_state(round_id)
        .map_err(|e| anyhow!("get_round_state: {}", e))?;

    let tree_state_bytes = {
        let conn = db.conn();
        let wallet_id = db.wallet_id();
        voting::storage::queries::load_tree_state(&conn, round_id, &wallet_id).map_err(|e| {
            anyhow!("load_tree_state: {e}; call storeTreeStateNative for this round first")
        })?
    };

    let network = zcash_protocol_network(db.network)?;
    let wallet_db = open_wallet_db_read_only(wallet_db_path, network)?;
    let scanned_height = zcash_client_backend::data_api::WalletRead::get_wallet_summary(
        &wallet_db,
        zcash_client_backend::data_api::wallet::ConfirmationsPolicy::default(),
    )
    .map_err(|e| anyhow!("get_wallet_summary: {}", e))?
    .map(
        |summary: zcash_client_backend::data_api::WalletSummary<
            zcash_client_sqlite::AccountUuid,
        >| { u64::from(u32::from(summary.fully_scanned_height())) },
    )
    .unwrap_or(0);

    let wallet_inputs = voting::selection::gather_delegation_wallet_inputs(
        voting::selection::GatherDelegationWalletParams {
            wallet_db: &wallet_db,
            account_uuid,
            voting_hotkey: &hotkey,
            snapshot_height: round_state.snapshot_height,
            scanned_height,
            anchor_tree_state_bytes: tree_state_bytes,
            resolved_round_name: round_name.to_string(),
        },
    )
    .map_err(|e| anyhow!("gather_delegation_wallet_inputs: {}", e))?;

    Ok(wallet_inputs.delegation_keys)
}

/// Derives the account SpendAuth key locally from `seed` and signs the
/// delegation PCZT sighash. The seed never enters zcash_voting; this is the
/// FFI-side half of the delegation signing recipe zcash_voting's
/// `DelegationSigningRequest` documents for software wallets.
fn sign_delegation_sighash_with_seed(
    seed: &[u8],
    request: &voting::delegate::DelegationSigningRequest,
) -> anyhow::Result<[u8; SPEND_AUTH_SIG_BYTES]> {
    use ff::PrimeField;
    use pasta_curves::pallas;

    let seed_fingerprint = zip32::fingerprint::SeedFingerprint::from_seed(seed)
        .ok_or_else(|| anyhow!("senderSeed must be 32 to 252 bytes"))?;
    if seed_fingerprint.to_bytes() != request.seed_fingerprint {
        return Err(anyhow!(
            "senderSeed does not match the delegation signing request seed fingerprint"
        ));
    }

    let account = zip32::AccountId::try_from(request.account_index)
        .map_err(|_| anyhow!("invalid account_index {}", request.account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&request.network, seed, account)
        .map_err(|e| anyhow!("failed to derive USK from senderSeed: {}", e))?;
    let ask = orchard::keys::SpendAuthorizingKey::from(usk.orchard());
    let alpha = Option::<pallas::Scalar>::from(pallas::Scalar::from_repr(request.alpha))
        .ok_or_else(|| anyhow!("delegation signing request alpha is not a canonical scalar"))?;
    let rsk = ask.randomize(&alpha);
    let sig: [u8; SPEND_AUTH_SIG_BYTES] = (&rsk.sign(rand::rngs::OsRng, &request.sighash)).into();
    Ok(sig)
}

/// Converts the voting DB handle's network to the `zcash_protocol::consensus::Network`
/// wallet-DB APIs expect. Voting DB handles only ever carry Testnet or Mainnet
/// (`openVotingDbNative` rejects anything else), so Regtest is unreachable here.
fn zcash_protocol_network(network: voting::types::Network) -> anyhow::Result<Network> {
    match network {
        voting::types::Network::Testnet => Ok(Network::TestNetwork),
        voting::types::Network::Mainnet => Ok(Network::MainNetwork),
        voting::types::Network::Regtest => {
            Err(anyhow!("regtest is not supported for this operation"))
        }
    }
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
            .get_delegation_submission_with_signature(
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
            .map_err(|e| anyhow!("get_delegation_submission_with_signature: {}", e))?;

        // Keystone signatures are supplied externally; verify them at the bridge boundary.
        verify_delegation_submission_sig(&data)?;
        make_jni_delegation_submission_result(env, data)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Persists a Keystone-signed delegation bundle's signature so a later round-wide
/// `resetVotingSessionStateNative` preserves this bundle instead of wiping its unsigned setup
/// fields for a rebuild. Wraps the crate's own `store_keystone_signature`
/// (`storage/queries.rs`/`storage/operations.rs`), which `clear_unsigned_delegation_setup_fields`
/// already checks against (`bundle_index NOT IN (SELECT bundle_index FROM keystone_signatures ...)`)
/// — so simply calling this after a successful `getDelegationSubmissionWithKeystoneSigNative` is
/// enough to make that preservation take effect; nothing else needs to change.
///
/// Callers should pass the `rk`/`sighash` pair `getDelegationSubmissionWithKeystoneSigNative`
/// already verified `keystone_sig` against (its returned submission result's `rk`), not
/// arbitrary caller-supplied values — this call does not itself re-verify the signature.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeKeystoneSignatureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    keystone_sig: JByteArray<'local>,
    keystone_sighash: JByteArray<'local>,
    rk: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let sig = java_bytes_exact(env, &keystone_sig, "keystoneSig", SPEND_AUTH_SIG_BYTES)?;
        let sighash = java_bytes_exact(
            env,
            &keystone_sighash,
            "keystoneSighash",
            PROTOCOL_FIELD_BYTES,
        )?;
        let rk = java_bytes_exact(env, &rk, "rk", PROTOCOL_FIELD_BYTES)?;
        db.store_keystone_signature(&round_id, bundle_index, &sig, &sighash, &rk)
            .map_err(|e| anyhow!("store_keystone_signature: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
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

/// Fails fast if the round has already advanced past `max_phase`, instead of letting a stale
/// retry silently re-enter minutes-long Halo2 proving for an already-past-submission round.
///
/// This is a read-only check (unlike the round-level `phase` writes `build_governance_pczt_for_bundle`
/// deliberately no longer performs — see that function's doc comment) and does not reintroduce
/// the multi-bundle "refusing to regress round phase" regression: it only ever rejects a round
/// that is *further along* than `max_phase`, never blocks a bundle that simply hasn't reached it
/// yet.
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
    use rand::rngs::OsRng;
    use voting::types::VotingHotkey;

    #[test]
    fn require_round_phase_not_after_allows_phase_at_or_before_max() {
        let (db, round_id) = test_db_with_round();

        require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)
            .expect("Initialized is not after DelegationProved");

        db.advance_round_phase(&round_id, RoundPhase::DelegationProved)
            .expect("advance to DelegationProved");
        require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)
            .expect("DelegationProved is not after DelegationProved");
    }

    #[test]
    fn require_round_phase_not_after_fails_fast_once_round_moved_past_max() {
        let (db, round_id) = test_db_with_round();
        db.advance_round_phase(&round_id, RoundPhase::VoteReady)
            .expect("advance to VoteReady");

        let err = require_round_phase_not_after(&db, &round_id, RoundPhase::DelegationProved)
            .unwrap_err();

        assert!(err.to_string().contains("already past"));
    }

    #[test]
    fn store_keystone_signature_persists_and_is_retrievable() {
        let (db, round_id) = test_db_with_round();
        let notes = [note_info()];
        db.ensure_bundles_with_skipped_suffix_with_policy(
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
        )
        .expect("bundle setup");

        db.store_keystone_signature(&round_id, 0, &[0x11; 64], &[0xAA; 32], &[0x22; 32])
            .expect("store keystone signature");

        let signatures = db
            .get_keystone_signatures(&round_id)
            .expect("get keystone signatures");
        assert_eq!(signatures.len(), 1);
        assert_eq!(signatures[0].bundle_index, 0);
        assert_eq!(signatures[0].sig, vec![0x11; 64]);
        assert_eq!(signatures[0].sighash, vec![0xAA; 32]);
        assert_eq!(signatures[0].rk, vec![0x22; 32]);
    }

    #[test]
    fn extract_spend_auth_sig_accepts_signed_governance_pczt() {
        let (db, round_id) = test_db_with_round();
        let notes = [note_info()];
        db.ensure_bundles_with_skipped_suffix_with_policy(
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
        )
        .expect("bundle setup");
        let hotkey = test_hotkey();
        let account_seed = [0x42u8; 32];
        let account_usk = UnifiedSpendingKey::from_seed(
            &Network::TestNetwork,
            &account_seed,
            zip32::AccountId::ZERO,
        )
        .expect("account USK");
        let fvk = account_usk.to_unified_full_viewing_key();
        let fvk_bytes = fvk.orchard().expect("orchard fvk").to_bytes().to_vec();
        let keys = voting::delegate::DelegationKeys::with_voting_hotkey(
            fvk_bytes,
            &hotkey,
            [0xAA; 32],
            0,
            "Test Round".to_string(),
        )
        .expect("delegation keys");

        let pczt = db
            .build_governance_pczt(&round_id, 0, &notes, &keys, test_branch_id())
            .expect("governance PCZT");

        let parsed = pczt::Pczt::parse(&pczt.pczt_bytes).expect("parse PCZT");
        let mut signer = pczt::roles::signer::Signer::new(parsed).expect("signer");
        let spend_authorizing_key = orchard::keys::SpendAuthorizingKey::from(account_usk.orchard());
        // Governance actions for Ironwood/NU6.3 voting notes ride the PCZT's
        // ironwood bundle, not the orchard bundle.
        signer
            .sign_ironwood(pczt.action_index, &spend_authorizing_key)
            .expect("sign ironwood action");
        let signed_pczt = signer.finish().serialize().expect("serialize signed PCZT");
        let sig = extract_indexed_spend_auth_sig(&signed_pczt, pczt.action_index).unwrap();

        assert_ne!(sig, [0u8; 64]);
    }

    #[test]
    fn extract_spend_auth_sig_rejects_unsigned_governance_pczt() {
        let (db, round_id, pczt) = test_governance_pczt();
        let err = extract_indexed_spend_auth_sig(&pczt.pczt_bytes, pczt.action_index).unwrap_err();
        drop(round_id);
        drop(db);

        assert!(err.to_string().contains("has no spend_auth_sig"));
    }

    #[test]
    fn delegation_submission_sig_verification_checks_rk_and_sighash() {
        use orchard::primitives::redpallas::SigningKey;

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

    fn test_hotkey() -> VotingHotkey {
        VotingHotkey::from_stored_secret(&[0x77; 64], voting::types::Network::Regtest)
            .expect("test hotkey")
    }

    /// build_governance_pczt validates the branch id against the round's own
    /// snapshot_height, so tests must resolve it the same way the real JNI
    /// path does instead of hardcoding a constant.
    fn test_branch_id() -> u32 {
        voting::delegate::branch_id_for_height(
            voting::types::Network::Regtest,
            round_params().snapshot_height,
        )
        .expect("branch id for test round snapshot height")
    }

    fn test_db_with_round() -> (VotingDb, String) {
        let db = VotingDb::open(":memory:").expect("test DB");
        db.set_wallet_id("delegation-test-wallet");
        let params = round_params();
        db.init_round(voting::types::Network::Regtest, &params, None)
            .expect("round initialized");
        (db, params.vote_round_id)
    }

    fn test_governance_pczt() -> (VotingDb, String, GovernancePczt) {
        let (db, round_id) = test_db_with_round();
        let notes = [note_info()];
        db.ensure_bundles_with_skipped_suffix_with_policy(
            &round_id,
            &notes,
            voting::BundlePolicy::default(),
        )
        .expect("bundle setup");
        let hotkey = test_hotkey();
        let keys = voting::delegate::DelegationKeys::with_voting_hotkey(
            vec![8; 96],
            &hotkey,
            [9; 32],
            0,
            "Test Round".to_string(),
        )
        .expect("delegation keys");
        let pczt = db
            .build_governance_pczt(&round_id, 0, &notes, &keys, test_branch_id())
            .expect("governance PCZT");
        (db, round_id, pczt)
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

    fn round_params() -> voting::types::VotingRoundParams {
        voting::types::VotingRoundParams {
            vote_round_id: "0101010101010101010101010101010101010101010101010101010101010101"
                .to_string(),
            // zcash_voting only builds governance PCZTs for Ironwood/NU6.3 snapshot
            // heights; Regtest activates NU6.3 at height 10 (matches the crate's
            // own test fixtures, since that constant isn't public).
            snapshot_height: 10,
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
            // `sighash` (32 bytes) is still required for local Keystone-signature
            // verification (see `verify_delegation_submission_sig`), but the
            // vote-chain server now requires `tx1_effects` (821 bytes,
            // zcash_voting::tx1::TX1_EFFECTS_LEN) on submission instead of sighash
            // — see JNI_DELEGATION_SUBMISSION_RESULT_CTOR_SIG's doc comment in
            // helpers.rs. Both fields are exercised here since this fixture feeds
            // signature-verification tests, not submission-payload tests.
            tx1_effects: vec![8; voting::tx1::TX1_EFFECTS_LEN],
        }
    }
}
