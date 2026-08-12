use super::*;

// Must match JNI_ROUND_PHASE_* constants in JniVotingModels.kt.
const PHASE_INITIALIZED: u32 = 0;
const PHASE_HOTKEY_GENERATED: u32 = 1;
const PHASE_DELEGATION_CONSTRUCTED: u32 = 2;
const PHASE_DELEGATION_PROVED: u32 = 3;
const PHASE_VOTE_READY: u32 = 4;

pub(super) const ORCHARD_RAW_ADDRESS_BYTES: usize = 43;
pub(super) const ORCHARD_FVK_BYTES: usize = 96;
pub(super) const PROTOCOL_FIELD_BYTES: usize = 32;
pub(super) const VOTE_COMMITMENT_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const BLIND_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const SHARE_NULLIFIER_BYTES: usize = PROTOCOL_FIELD_BYTES;
// Length of VotingHotkey::stored_secret(), the opaque app-owned secret Android
// must persist after a fresh generateHotkeyNative call.
pub(super) const HOTKEY_STORED_SECRET_BYTES: usize = 64;
// Hotkeys use one stable Orchard address for voting identity and recovery.
pub(super) const HOTKEY_ADDRESS_INDEX: u32 = 0;
// ZIP-32 account for deriving hotkey material from the hotkey seed. This is intentionally
// distinct from HOTKEY_ADDRESS_INDEX: account selects the Orchard account, address index
// selects the stable address within that account. zcash_voting's vote path currently derives
// hotkey signing material only for account 0.
pub(super) const HOTKEY_ACCOUNT_INDEX: u32 = 0;
pub(super) const SPEND_AUTH_SIG_BYTES: usize = 64;
pub(super) const NOTE_SCOPE_EXTERNAL: u32 = 0;
pub(super) const NOTE_SCOPE_INTERNAL: u32 = 1;
pub(super) const ORCHARD_DIVERSIFIER_BYTES: usize = 11;
pub(super) const ORCHARD_WITNESS_PATH_DEPTH: usize = 32;
// Must match JNI_VAN_WITNESS_PATH_DEPTH in JniConstants.kt.
pub(super) const VAN_WITNESS_PATH_DEPTH: usize = 24;
// Must match JNI_VOTE_SHARE_COUNT in JniConstants.kt.
pub(super) const VOTE_SHARE_COUNT: usize = 16;
pub(super) const DELEGATION_PUBLIC_INPUT_COUNT: usize = 14;
pub(super) const GOVERNANCE_NULLIFIER_COUNT: usize = 5;
pub(super) const ACCOUNT_UUID_BYTES: usize = 16;

pub(super) fn require_32(
    bytes: Vec<u8>,
    field: &str,
) -> anyhow::Result<[u8; PROTOCOL_FIELD_BYTES]> {
    let bytes = require_len(bytes, field, PROTOCOL_FIELD_BYTES)?;
    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {PROTOCOL_FIELD_BYTES} bytes"))
}

pub(super) fn require_share_index(share_index: u32, field: &str) -> anyhow::Result<u32> {
    if share_index < VOTE_SHARE_COUNT as u32 {
        Ok(share_index)
    } else {
        Err(anyhow!(
            "{field} must be in 0..{}, got {share_index}",
            VOTE_SHARE_COUNT - 1
        ))
    }
}

pub(super) fn round_phase_to_u32(phase: RoundPhase) -> u32 {
    match phase {
        RoundPhase::Initialized => PHASE_INITIALIZED,
        RoundPhase::HotkeyGenerated => PHASE_HOTKEY_GENERATED,
        RoundPhase::DelegationConstructed => PHASE_DELEGATION_CONSTRUCTED,
        RoundPhase::DelegationProved => PHASE_DELEGATION_PROVED,
        RoundPhase::VoteReady => PHASE_VOTE_READY,
    }
}

pub(super) fn java_bytes32(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; PROTOCOL_FIELD_BYTES]> {
    require_32(java_bytes(env, array, field)?, field)
}

/// Resolves the `voting::types::Network` a voting DB handle is opened for.
///
/// Android has no custom-network registry in this path, so only the two
/// well-known network ids are accepted; everything else (including the
/// legacy "custom network" id 2) is rejected here.
pub(super) fn voting_network_from_id(id: jint) -> anyhow::Result<voting::types::Network> {
    match id {
        NETWORK_ID_TESTNET => Ok(voting::types::Network::Testnet),
        NETWORK_ID_MAINNET => Ok(voting::types::Network::Mainnet),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

pub(super) fn hotkey_orchard_raw_address(
    hotkey_seed: &[u8],
    network: Network,
    account_index: u32,
) -> anyhow::Result<Vec<u8>> {
    let account_id = zip32::AccountId::try_from(account_index)
        .map_err(|_| anyhow!("invalid account_index {}", account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&network, hotkey_seed, account_id)
        .map_err(|e| anyhow!("failed to derive hotkey USK: {}", e))?;
    let fvk = usk.to_unified_full_viewing_key();
    let orchard_fvk = fvk
        .orchard()
        .ok_or_else(|| anyhow!("hotkey UFVK has no Orchard component"))?;
    let addr = orchard_fvk.address_at(HOTKEY_ADDRESS_INDEX, Scope::External);
    require_len(
        addr.to_raw_address_bytes().to_vec(),
        "hotkey_raw_address",
        ORCHARD_RAW_ADDRESS_BYTES,
    )
}

pub(super) fn orchard_fvk_bytes_from_wallet_seed(
    wallet_seed: &[u8],
    network: Network,
    account_index: u32,
) -> anyhow::Result<Vec<u8>> {
    let account_id = zip32::AccountId::try_from(account_index)
        .map_err(|_| anyhow!("invalid account_index {}", account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&network, wallet_seed, account_id)
        .map_err(|e| anyhow!("failed to derive USK from wallet seed: {}", e))?;
    let ufvk = usk.to_unified_full_viewing_key();
    let orchard_fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("derived UFVK has no Orchard component"))?;
    require_len(
        orchard_fvk.to_bytes().to_vec(),
        "derived_orchard_fvk",
        ORCHARD_FVK_BYTES,
    )
}

pub(super) fn orchard_fvk_bytes(ufvk_str: &str, network: Network) -> anyhow::Result<Vec<u8>> {
    let ufvk = UnifiedFullViewingKey::decode(&network, ufvk_str)
        .map_err(|e| anyhow!("failed to decode UFVK: {}", e))?;
    let fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("UFVK has no Orchard component"))?;
    require_len(fvk.to_bytes().to_vec(), "orchard_fvk", ORCHARD_FVK_BYTES)
}

pub(super) fn require_note_scope(scope: u32) -> anyhow::Result<u32> {
    match scope {
        NOTE_SCOPE_EXTERNAL | NOTE_SCOPE_INTERNAL => Ok(scope),
        _ => Err(anyhow!(
            "scope must be {NOTE_SCOPE_EXTERNAL} (external) or {NOTE_SCOPE_INTERNAL} (internal), got {scope}"
        )),
    }
}

// Must match JniDelegationPhase(Int, String) in JniVotingModels.kt.
const JNI_DELEGATION_PHASE_CTOR_SIG: &str = "(ILjava/lang/String;)V";

pub(super) fn make_jni_delegation_phases(
    env: &mut JNIEnv<'_>,
    phases: Vec<(u32, voting::phases::DelegationPhase)>,
) -> anyhow::Result<jobjectArray> {
    // jint conversion happens up front (anyhow::Result) since the rust_vec_to_java
    // element closure below must return a plain jni::errors::Result.
    let payloads = phases
        .into_iter()
        .map(|(bundle_index, phase)| Ok((u32_to_jint(bundle_index, "bundle_index")?, phase)))
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(rust_vec_to_java(
        env,
        payloads,
        JNI_DELEGATION_PHASE,
        |env, (bundle_index, phase)| {
            let phase_obj: JObject<'_> = env.new_string(phase.as_str())?.into();
            env.new_object(
                JNI_DELEGATION_PHASE,
                JNI_DELEGATION_PHASE_CTOR_SIG,
                &[JValue::Int(bundle_index), JValue::Object(&phase_obj)],
            )
        },
    )?
    .into_raw())
}

// JNI object construction needs a JNIEnv-bound local frame, so these builders
// stay explicit instead of being modeled as TryFrom conversions.
/// Builds the Kotlin hotkey JNI model, including the opaque stored secret.
///
/// Unlike the pre-1.0 wallet-seed-derived hotkey, `generateHotkeyNative` can
/// mint a fresh app-owned hotkey identity, so its stored secret must cross
/// JNI here for Android to persist in secure storage; the crate never
/// re-derives it from the wallet seed.
pub(super) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let stored_secret = require_len(
        hotkey.stored_secret().to_vec(),
        "hotkey_stored_secret",
        HOTKEY_STORED_SECRET_BYTES,
    )?;
    let raw_address = *hotkey.raw_orchard_address();
    let address = hotkey_unified_address(&raw_address, hotkey.network())?;

    let class = env.find_class(JNI_VOTING_HOTKEY)?;
    let secret_obj: JObject<'local> = env.byte_array_from_slice(&stored_secret)?.into();
    let raw_address_obj: JObject<'local> = env.byte_array_from_slice(&raw_address)?.into();
    let addr_obj: JObject<'local> = env.new_string(&address)?.into();
    let obj = env.new_object(
        &class,
        JNI_VOTING_HOTKEY_CTOR_SIG,
        &[
            JValue::Object(&secret_obj),
            JValue::Object(&raw_address_obj),
            JValue::Object(&addr_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

/// Encodes a hotkey's raw Orchard receiver as a Unified Address string.
fn hotkey_unified_address(
    raw_address: &[u8; ORCHARD_RAW_ADDRESS_BYTES],
    network: voting::types::Network,
) -> anyhow::Result<String> {
    let orchard_address =
        Option::<orchard::Address>::from(orchard::Address::from_raw_address_bytes(raw_address))
            .ok_or_else(|| anyhow!("hotkey raw Orchard address bytes are invalid"))?;
    let unified_address = zcash_client_backend::address::UnifiedAddress::from_receivers(
        Some(orchard_address),
        None,
        None,
    )
    .ok_or_else(|| anyhow!("failed to build unified address from hotkey Orchard receiver"))?;
    let encode_network = match network {
        voting::types::Network::Mainnet => Network::MainNetwork,
        voting::types::Network::Testnet | voting::types::Network::Regtest => Network::TestNetwork,
    };
    Ok(unified_address.encode(&encode_network))
}

pub(super) fn make_jni_governance_pczt<'local>(
    env: &mut JNIEnv<'local>,
    pczt: GovernancePczt,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_GOVERNANCE_PCZT)?;
    let action_index = u32_to_jint(
        u32::try_from(pczt.action_index)
            .map_err(|_| anyhow!("action_index is too large for u32: {}", pczt.action_index))?,
        "action_index",
    )?;
    let pczt_bytes = make_jni_bytes(env, &pczt.pczt_bytes)?;
    let rk = make_jni_fixed_bytes(env, pczt.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let sighash =
        make_jni_fixed_bytes(env, pczt.pczt_sighash, "pczt_sighash", PROTOCOL_FIELD_BYTES)?;

    let obj = env.new_object(
        &class,
        JNI_GOVERNANCE_PCZT_CTOR_SIG,
        &[
            JValue::Object(&pczt_bytes),
            JValue::Object(&rk),
            JValue::Object(&sighash),
            JValue::Int(action_index),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_delegation_pir_precompute_result<'local>(
    env: &mut JNIEnv<'local>,
    result: DelegationPirPrecomputeResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_PIR_PRECOMPUTE_RESULT)?;
    let obj = env.new_object(
        &class,
        JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG,
        &[
            JValue::Long(u64_to_jlong(
                u64::from(result.cached_count),
                "cached_count",
            )?),
            JValue::Long(u64_to_jlong(
                u64::from(result.fetched_count),
                "fetched_count",
            )?),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_delegation_proof_result<'local>(
    env: &mut JNIEnv<'local>,
    result: DelegationProofResult,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_PROOF_RESULT)?;
    let proof_obj = make_jni_bytes(env, &result.proof)?;
    let public_inputs_array = make_jni_fixed_byte_array_vec(
        env,
        result.public_inputs,
        "public_inputs",
        DELEGATION_PUBLIC_INPUT_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let nf_signed = make_jni_fixed_bytes(env, result.nf_signed, "nf_signed", PROTOCOL_FIELD_BYTES)?;
    let cmx_new = make_jni_fixed_bytes(env, result.cmx_new, "cmx_new", PROTOCOL_FIELD_BYTES)?;
    let gov_nullifiers_array = make_jni_fixed_byte_array_vec(
        env,
        result.gov_nullifiers,
        "gov_nullifiers",
        GOVERNANCE_NULLIFIER_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let van_comm = make_jni_fixed_bytes(env, result.van_comm, "van_comm", PROTOCOL_FIELD_BYTES)?;
    let rk = make_jni_fixed_bytes(env, result.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let public_inputs_obj = JObject::from(public_inputs_array);
    let gov_nullifiers_obj = JObject::from(gov_nullifiers_array);

    let obj = env.new_object(
        &class,
        JNI_DELEGATION_PROOF_RESULT_CTOR_SIG,
        &[
            JValue::Object(&proof_obj),
            JValue::Object(&public_inputs_obj),
            JValue::Object(&nf_signed),
            JValue::Object(&cmx_new),
            JValue::Object(&gov_nullifiers_obj),
            JValue::Object(&van_comm),
            JValue::Object(&rk),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_delegation_submission_result<'local>(
    env: &mut JNIEnv<'local>,
    data: DelegationSubmissionData,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_DELEGATION_SUBMISSION_RESULT)?;
    let proof = make_jni_bytes(env, &data.proof)?;
    let rk = make_jni_fixed_bytes(env, data.rk, "rk", PROTOCOL_FIELD_BYTES)?;
    let spend_auth_sig = make_jni_fixed_bytes(
        env,
        data.spend_auth_sig,
        "spend_auth_sig",
        SPEND_AUTH_SIG_BYTES,
    )?;
    let sighash = make_jni_fixed_bytes(env, data.sighash, "sighash", PROTOCOL_FIELD_BYTES)?;
    let tx1_effects = make_jni_fixed_bytes(
        env,
        data.tx1_effects,
        "tx1_effects",
        voting::tx1::TX1_EFFECTS_LEN,
    )?;
    let nf_signed = make_jni_fixed_bytes(env, data.nf_signed, "nf_signed", PROTOCOL_FIELD_BYTES)?;
    let cmx_new = make_jni_fixed_bytes(env, data.cmx_new, "cmx_new", PROTOCOL_FIELD_BYTES)?;
    let gov_comm = make_jni_fixed_bytes(env, data.gov_comm, "gov_comm", PROTOCOL_FIELD_BYTES)?;
    let gov_nullifiers_array = make_jni_fixed_byte_array_vec(
        env,
        data.gov_nullifiers,
        "gov_nullifiers",
        GOVERNANCE_NULLIFIER_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_round_id: JObject<'local> = env.new_string(data.vote_round_id)?.into();
    let gov_nullifiers = JObject::from(gov_nullifiers_array);

    let obj = env.new_object(
        &class,
        JNI_DELEGATION_SUBMISSION_RESULT_CTOR_SIG,
        &[
            JValue::Object(&proof),
            JValue::Object(&rk),
            JValue::Object(&spend_auth_sig),
            JValue::Object(&sighash),
            JValue::Object(&tx1_effects),
            JValue::Object(&nf_signed),
            JValue::Object(&cmx_new),
            JValue::Object(&gov_comm),
            JValue::Object(&gov_nullifiers),
            JValue::Object(&vote_round_id),
        ],
    )?;
    Ok(obj.into_raw())
}

/// Runs the voting note chunker and returns total count, total eligible weight,
/// and each bundle's quantized voting weight.
pub(super) fn bundle_setup_from_notes(notes: &[NoteInfo]) -> anyhow::Result<(u32, u64, Vec<u64>)> {
    // zcash_voting 1.0.0 (merged-library patch) moved `chunk_notes` from `types` to
    // `note_bundling`; same `&[NoteInfo] -> ChunkResult` signature.
    let chunk_result = voting::note_bundling::chunk_notes(notes);
    let bundle_weights = chunk_result
        .bundles
        .iter()
        .map(|bundle| {
            let total = bundle.iter().try_fold(0u64, |acc, note| {
                acc.checked_add(note.value)
                    .ok_or_else(|| anyhow!("bundle note value overflows u64"))
            })?;
            Ok((total / voting::BALLOT_DIVISOR) * voting::BALLOT_DIVISOR)
        })
        .collect::<anyhow::Result<Vec<_>>>()?;
    Ok((
        u32::try_from(chunk_result.bundles.len())
            .map_err(|_| anyhow!("bundle count is too large for u32"))?,
        chunk_result.eligible_weight,
        bundle_weights,
    ))
}

/// Recomputes deterministic note chunking and returns the requested bundle.
pub(super) fn bundled_notes_for_index(
    notes: &[NoteInfo],
    bundle_index: u32,
) -> anyhow::Result<Vec<NoteInfo>> {
    // zcash_voting 1.0.0 (merged-library patch) moved `chunk_notes` from `types` to
    // `note_bundling`; same `&[NoteInfo] -> ChunkResult` signature.
    let chunk_result = voting::note_bundling::chunk_notes(notes);
    let bundle_index = usize::try_from(bundle_index)
        .map_err(|_| anyhow!("bundle_index is too large for this platform: {bundle_index}"))?;

    chunk_result
        .bundles
        .get(bundle_index)
        .cloned()
        .ok_or_else(|| anyhow!("bundle_index {bundle_index} is not present in note bundle set"))
}

pub(super) fn select_bundle_notes(
    conn: &rusqlite::Connection,
    round_id: &str,
    wallet_id: &str,
    bundle_index: u32,
    notes: &[NoteInfo],
) -> anyhow::Result<Vec<NoteInfo>> {
    let positions = voting::storage::queries::load_bundle_note_positions(
        conn,
        round_id,
        wallet_id,
        bundle_index,
    )
    .map_err(|e| anyhow!("load_bundle_note_positions: {}", e))?;

    let mut notes_by_position = HashMap::with_capacity(notes.len());
    for note in notes.iter().cloned() {
        let position = note.position;
        if notes_by_position.insert(position, note).is_some() {
            return Err(anyhow!(
                "duplicate note position {} in provided notes",
                position
            ));
        }
    }

    let bundle_notes = positions
        .into_iter()
        .map(|position| {
            notes_by_position.remove(&position).ok_or_else(|| {
                anyhow!(
                    "bundle {} is missing note position {} from provided notes",
                    bundle_index,
                    position
                )
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    voting::storage::queries::require_bundle_notes(
        conn,
        round_id,
        wallet_id,
        bundle_index,
        &bundle_notes,
    )
    .map_err(|e| anyhow!("require_bundle_notes: {}", e))?;

    Ok(bundle_notes)
}

pub(super) fn received_note_to_note_info(
    note: &zcash_client_backend::wallet::ReceivedNote<
        zcash_client_sqlite::ReceivedNoteId,
        orchard::note::Note,
    >,
    ufvk: &UnifiedFullViewingKey,
    network: &Network,
) -> anyhow::Result<NoteInfo> {
    NoteInfo::from_orchard_note(
        note.note(),
        u64::from(note.note_commitment_tree_position()),
        note.spending_key_scope(),
        ufvk,
        network,
    )
    .map_err(|e| anyhow!("NoteInfo::from_orchard_note: {}", e))
}
