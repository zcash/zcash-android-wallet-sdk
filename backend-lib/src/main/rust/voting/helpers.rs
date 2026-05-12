use super::*;

// Must match JNI_ROUND_PHASE_* constants in JniVotingModels.kt.
const PHASE_INITIALIZED: u32 = 0;
const PHASE_HOTKEY_GENERATED: u32 = 1;
const PHASE_DELEGATION_CONSTRUCTED: u32 = 2;
const PHASE_DELEGATION_PROVED: u32 = 3;
const PHASE_VOTE_READY: u32 = 4;

const JNI_ROUND_SUMMARY: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniRoundSummary";
const JNI_VOTE_RECORD: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniVoteRecord";
const JNI_NOTE_INFO: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniNoteInfo";
const JNI_WITNESS_DATA: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniWitnessData";
const JNI_VOTING_HOTKEY: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniVotingHotkey";
const JNI_BUNDLE_SETUP_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniBundleSetupResult";
const JNI_GOVERNANCE_PCZT: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniGovernancePczt";
const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationPirPrecomputeResult";
const JNI_DELEGATION_PROOF_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationProofResult";
const JNI_DELEGATION_SUBMISSION_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniDelegationSubmissionResult";

// Must match JniNoteInfo(ByteArray, ByteArray, Long, Long, ByteArray,
// ByteArray, ByteArray, Int, String) in JniVotingModels.kt.
const JNI_NOTE_INFO_CTOR_SIG: &str = "([B[BJJ[B[B[BILjava/lang/String;)V";
// Must match JniWitnessData(ByteArray, Long, ByteArray, Array<ByteArray>)
// in JniVotingModels.kt.
const JNI_WITNESS_DATA_CTOR_SIG: &str = "([BJ[B[[B)V";
// Must match JniVotingHotkey(ByteArray, String) in JniVotingModels.kt.
const JNI_VOTING_HOTKEY_CTOR_SIG: &str = "([BLjava/lang/String;)V";
// Must match JniBundleSetupResult(Int, Long, LongArray) in JniVotingModels.kt.
const JNI_BUNDLE_SETUP_RESULT_CTOR_SIG: &str = "(IJ[J)V";
// Must match JniGovernancePczt(ByteArray, ByteArray, ByteArray, Int) in
// JniVotingModels.kt.
const JNI_GOVERNANCE_PCZT_CTOR_SIG: &str = "([B[B[BI)V";
// Must match JniDelegationPirPrecomputeResult(Long, Long) in JniVotingModels.kt.
const JNI_DELEGATION_PIR_PRECOMPUTE_RESULT_CTOR_SIG: &str = "(JJ)V";
// Must match JniDelegationProofResult(ByteArray, Array<ByteArray>, ByteArray,
// ByteArray, Array<ByteArray>, ByteArray, ByteArray) in JniVotingModels.kt.
const JNI_DELEGATION_PROOF_RESULT_CTOR_SIG: &str = "([B[[B[B[B[[B[B[B)V";
// Must match JniDelegationSubmissionResult(ByteArray, ByteArray, ByteArray,
// ByteArray, ByteArray, ByteArray, ByteArray, Array<ByteArray>, String) in
// JniVotingModels.kt.
const JNI_DELEGATION_SUBMISSION_RESULT_CTOR_SIG: &str = "([B[B[B[B[B[B[B[[BLjava/lang/String;)V";

pub(super) const ORCHARD_RAW_ADDRESS_BYTES: usize = 43;
pub(super) const ORCHARD_FVK_BYTES: usize = 96;
pub(super) const PROTOCOL_FIELD_BYTES: usize = 32;
pub(super) const VOTE_COMMITMENT_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const BLIND_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const SHARE_NULLIFIER_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const HOTKEY_PUBLIC_KEY_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const SPEND_AUTH_SIG_BYTES: usize = 64;
pub(super) const NOTE_SCOPE_EXTERNAL: u32 = 0;
pub(super) const NOTE_SCOPE_INTERNAL: u32 = 1;
pub(super) const ORCHARD_DIVERSIFIER_BYTES: usize = 11;
pub(super) const ORCHARD_WITNESS_PATH_DEPTH: usize = 32;
pub(super) const DELEGATION_PUBLIC_INPUT_COUNT: usize = 14;
pub(super) const GOVERNANCE_NULLIFIER_COUNT: usize = 5;
pub(super) const ACCOUNT_UUID_BYTES: usize = 16;
pub(super) const NETWORK_ID_TESTNET: jint = 0;
pub(super) const NETWORK_ID_MAINNET: jint = 1;

struct JniRoundSummaryPayload {
    round_id: String,
    phase: jint,
    snapshot_height: jlong,
    created_at: jlong,
}

struct JniVoteRecordPayload {
    proposal_id: jint,
    bundle_index: jint,
    choice: jint,
    submitted: bool,
}

pub(super) fn jint_to_u32(value: jint, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn jlong_to_u64(value: jlong, field: &str) -> anyhow::Result<u64> {
    u64::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn jlong_to_u32(value: jlong, field: &str) -> anyhow::Result<u32> {
    u32::try_from(value).map_err(|_| anyhow!("{field} must be in range 0..=u32::MAX, got {value}"))
}

pub(super) fn jint_to_usize(value: jint, field: &str) -> anyhow::Result<usize> {
    usize::try_from(value).map_err(|_| anyhow!("{field} must be non-negative, got {value}"))
}

pub(super) fn u32_to_jint(value: u32, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(super) fn usize_to_jint(value: usize, field: &str) -> anyhow::Result<jint> {
    jint::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Int range: {value}"))
}

pub(super) fn u64_to_jlong(value: u64, field: &str) -> anyhow::Result<jlong> {
    jlong::try_from(value).map_err(|_| anyhow!("{field} exceeds signed Long range: {value}"))
}

pub(super) fn require_len(bytes: Vec<u8>, field: &str, expected: usize) -> anyhow::Result<Vec<u8>> {
    if bytes.len() == expected {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be exactly {expected} bytes, got {}",
            bytes.len()
        ))
    }
}

fn require_each_len(
    values: Vec<Vec<u8>>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<Vec<u8>>> {
    values
        .into_iter()
        .enumerate()
        .map(|(index, value)| require_len(value, &format!("{field}[{index}]"), expected))
        .collect()
}

pub(super) fn require_min_len(
    bytes: Vec<u8>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<Vec<u8>> {
    if bytes.len() >= minimum {
        Ok(bytes)
    } else {
        Err(anyhow!(
            "{field} must be at least {minimum} bytes, got {}",
            bytes.len()
        ))
    }
}

pub(super) fn require_32(
    bytes: Vec<u8>,
    field: &str,
) -> anyhow::Result<[u8; PROTOCOL_FIELD_BYTES]> {
    let bytes = require_len(bytes, field, PROTOCOL_FIELD_BYTES)?;
    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {PROTOCOL_FIELD_BYTES} bytes"))
}

pub(super) fn java_bytes(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<u8>> {
    env.convert_byte_array(array)
        .map_err(|e| anyhow!("{field}: failed to read byte array: {e}"))
}

pub(super) fn java_bytes_exact(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<u8>> {
    require_len(java_bytes(env, array, field)?, field, expected)
}

pub(super) fn java_fixed_bytes<const N: usize>(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; N]> {
    fixed_bytes(java_bytes(env, array, field)?, field)
}

pub(super) fn fixed_bytes<const N: usize>(bytes: Vec<u8>, field: &str) -> anyhow::Result<[u8; N]> {
    let len = bytes.len();

    bytes
        .try_into()
        .map_err(|_| anyhow!("{field} must be exactly {N} bytes, got {len}"))
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

pub(super) fn java_secret_bytes_at_least(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<SecretVec<u8>> {
    require_min_len(java_bytes(env, array, field)?, field, minimum).map(SecretVec::new)
}

pub(super) fn java_bytes32(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
) -> anyhow::Result<[u8; PROTOCOL_FIELD_BYTES]> {
    require_32(java_bytes(env, array, field)?, field)
}

fn java_byte_array_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<u8>> {
    let field = JByteArray::from(env.get_field(obj, name, "[B")?.l()?);
    java_bytes(env, &field, name)
}

fn java_string_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<String> {
    let field = JString::from(env.get_field(obj, name, "Ljava/lang/String;")?.l()?);
    java_string_to_rust(env, &field)
}

fn java_byte_array_list_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<Vec<u8>>> {
    let list = env.get_field(obj, name, "Ljava/util/List;")?.l()?;
    let count = env.call_method(&list, "size", "()I", &[])?.i()?;
    if count < 0 {
        return Err(anyhow!("{name}.size() returned negative count {count}"));
    }

    (0..count)
        .map(|index| {
            let element = env
                .call_method(&list, "get", "(I)Ljava/lang/Object;", &[JValue::Int(index)])?
                .l()?;
            let bytes = JByteArray::from(element);
            java_bytes(env, &bytes, &format!("{name}[{index}]"))
        })
        .collect()
}

pub(super) fn network_from_id(id: jint) -> anyhow::Result<Network> {
    match id {
        NETWORK_ID_TESTNET => Ok(Network::TestNetwork),
        NETWORK_ID_MAINNET => Ok(Network::MainNetwork),
        _ => Err(anyhow!("invalid network_id {}", id)),
    }
}

pub(super) fn hotkey_orchard_raw_address_from_wallet_seed(
    wallet_seed: &[u8],
    network: Network,
    account_index: u32,
    address_index: u32,
) -> anyhow::Result<Vec<u8>> {
    let account_id = zip32::AccountId::try_from(account_index)
        .map_err(|_| anyhow!("invalid account_index {}", account_index))?;
    let usk = UnifiedSpendingKey::from_seed(&network, wallet_seed, account_id)
        .map_err(|e| anyhow!("failed to derive hotkey USK from wallet seed: {}", e))?;
    let fvk = usk.to_unified_full_viewing_key();
    let orchard_fvk = fvk
        .orchard()
        .ok_or_else(|| anyhow!("hotkey UFVK has no Orchard component"))?;
    // voting-circuits treats address_index as the diversifier index for the
    // external Orchard scope when reconstructing the hotkey address for ZKP #2.
    let addr = orchard_fvk.address_at(address_index, Scope::External);
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

pub(super) fn java_note_info_array(
    env: &mut JNIEnv<'_>,
    notes: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<NoteInfo>> {
    let count = env.get_array_length(notes)?;
    (0..count)
        .map(|index| {
            let note = env.get_object_array_element(notes, index)?;
            java_note_info(env, &note).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

fn java_note_info(env: &mut JNIEnv<'_>, note: &JObject<'_>) -> anyhow::Result<NoteInfo> {
    let scope = require_note_scope(u32::try_from(env.get_field(note, "scope", "I")?.i()?)?)?;

    Ok(NoteInfo {
        commitment: require_len(
            java_byte_array_field(env, note, "commitment")?,
            "commitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        nullifier: require_len(
            java_byte_array_field(env, note, "nullifier")?,
            "nullifier",
            PROTOCOL_FIELD_BYTES,
        )?,
        value: jlong_to_u64(env.get_field(note, "value", "J")?.j()?, "value")?,
        position: jlong_to_u64(env.get_field(note, "position", "J")?.j()?, "position")?,
        diversifier: require_len(
            java_byte_array_field(env, note, "diversifier")?,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?,
        rho: require_len(
            java_byte_array_field(env, note, "rho")?,
            "rho",
            PROTOCOL_FIELD_BYTES,
        )?,
        rseed: require_len(
            java_byte_array_field(env, note, "rseed")?,
            "rseed",
            PROTOCOL_FIELD_BYTES,
        )?,
        scope,
        ufvk_str: java_string_field(env, note, "ufvk")?,
    })
}

pub(super) fn java_witness_data_array(
    env: &mut JNIEnv<'_>,
    witnesses: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<WitnessData>> {
    let count = env.get_array_length(witnesses)?;
    (0..count)
        .map(|index| {
            let witness = env.get_object_array_element(witnesses, index)?;
            java_witness_data(env, &witness).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

pub(super) fn java_witness_data(
    env: &mut JNIEnv<'_>,
    witness: &JObject<'_>,
) -> anyhow::Result<WitnessData> {
    let auth_path = java_byte_array_list_field(env, witness, "authPath")?;
    if auth_path.len() != ORCHARD_WITNESS_PATH_DEPTH {
        return Err(anyhow!(
            "authPath must contain {ORCHARD_WITNESS_PATH_DEPTH} entries, got {}",
            auth_path.len()
        ));
    }

    Ok(WitnessData {
        note_commitment: require_len(
            java_byte_array_field(env, witness, "noteCommitment")?,
            "noteCommitment",
            PROTOCOL_FIELD_BYTES,
        )?,
        position: jlong_to_u64(env.get_field(witness, "position", "J")?.j()?, "position")?,
        root: require_len(
            java_byte_array_field(env, witness, "root")?,
            "root",
            PROTOCOL_FIELD_BYTES,
        )?,
        auth_path: require_each_len(auth_path, "authPath", PROTOCOL_FIELD_BYTES)?,
    })
}

fn require_note_scope(scope: u32) -> anyhow::Result<u32> {
    match scope {
        NOTE_SCOPE_EXTERNAL | NOTE_SCOPE_INTERNAL => Ok(scope),
        _ => Err(anyhow!(
            "scope must be {NOTE_SCOPE_EXTERNAL} (external) or {NOTE_SCOPE_INTERNAL} (internal), got {scope}"
        )),
    }
}

// NU6 branch ID used by the governance PCZT signer path. Revisit this when
// the voting transaction format moves to a later consensus branch.
pub(super) fn nu6_branch_id() -> u32 {
    BranchId::Nu6.into()
}

pub(super) fn make_jni_round_state<'local>(
    env: &mut JNIEnv<'local>,
    state: RoundState,
) -> anyhow::Result<jobject> {
    let phase = round_phase_to_u32(state.phase);
    let class = env.find_class("cash/z/ecc/android/sdk/internal/model/voting/JniRoundState")?;
    let round_id_obj: JObject<'local> = env.new_string(&state.round_id)?.into();
    let hotkey_obj: JObject<'local> = match &state.hotkey_address {
        Some(a) => env.new_string(a)?.into(),
        None => JObject::null(),
    };
    let long_class = env.find_class("java/lang/Long")?;
    let weight_obj: JObject<'local> = match state.delegated_weight {
        Some(w) => env.new_object(
            &long_class,
            "(J)V",
            &[JValue::Long(u64_to_jlong(w, "delegated_weight")?)],
        )?,
        None => JObject::null(),
    };
    let obj = env.new_object(
        &class,
        // Matches JniRoundState(roundId, phase, snapshotHeight, hotkeyAddress,
        //                       delegatedWeight, proofGenerated).
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/Long;Z)V",
        &[
            JValue::Object(&round_id_obj),
            JValue::Int(u32_to_jint(phase, "round_phase")?),
            JValue::Long(u64_to_jlong(state.snapshot_height, "snapshot_height")?),
            JValue::Object(&hotkey_obj),
            JValue::Object(&weight_obj),
            JValue::Bool(state.proof_generated as jboolean),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_round_summaries(
    env: &mut JNIEnv<'_>,
    rounds: Vec<RoundSummary>,
) -> anyhow::Result<jobjectArray> {
    let payloads = rounds
        .into_iter()
        .map(JniRoundSummaryPayload::try_from)
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(
        rust_vec_to_java(env, payloads, JNI_ROUND_SUMMARY, |env, round| {
            let round_id_obj: JObject<'_> = env.new_string(round.round_id)?.into();
            env.new_object(
                JNI_ROUND_SUMMARY,
                // Matches JniRoundSummary(roundId, phase, snapshotHeight, createdAt).
                "(Ljava/lang/String;IJJ)V",
                &[
                    JValue::Object(&round_id_obj),
                    JValue::Int(round.phase),
                    JValue::Long(round.snapshot_height),
                    JValue::Long(round.created_at),
                ],
            )
        })?
        .into_raw(),
    )
}

pub(super) fn make_jni_vote_records(
    env: &mut JNIEnv<'_>,
    votes: Vec<VoteRecord>,
) -> anyhow::Result<jobjectArray> {
    let payloads = votes
        .into_iter()
        .map(JniVoteRecordPayload::try_from)
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(
        rust_vec_to_java(env, payloads, JNI_VOTE_RECORD, |env, vote| {
            env.new_object(
                JNI_VOTE_RECORD,
                // Matches JniVoteRecord(proposalId, bundleIndex, choice, submitted).
                "(IIIZ)V",
                &[
                    JValue::Int(vote.proposal_id),
                    JValue::Int(vote.bundle_index),
                    JValue::Int(vote.choice),
                    JValue::Bool(vote.submitted as jboolean),
                ],
            )
        })?
        .into_raw(),
    )
}

impl TryFrom<RoundSummary> for JniRoundSummaryPayload {
    type Error = anyhow::Error;

    fn try_from(round: RoundSummary) -> anyhow::Result<Self> {
        Ok(JniRoundSummaryPayload {
            round_id: round.round_id,
            phase: u32_to_jint(round_phase_to_u32(round.phase), "phase")?,
            snapshot_height: u64_to_jlong(round.snapshot_height, "snapshot_height")?,
            created_at: u64_to_jlong(round.created_at, "created_at")?,
        })
    }
}

impl TryFrom<VoteRecord> for JniVoteRecordPayload {
    type Error = anyhow::Error;

    fn try_from(record: VoteRecord) -> anyhow::Result<Self> {
        Ok(JniVoteRecordPayload {
            proposal_id: u32_to_jint(record.proposal_id, "proposal_id")?,
            bundle_index: u32_to_jint(record.bundle_index, "bundle_index")?,
            choice: u32_to_jint(record.choice, "choice")?,
            submitted: record.submitted,
        })
    }
}

pub(super) fn make_jni_note_info_array<'local>(
    env: &mut JNIEnv<'local>,
    notes: Vec<NoteInfo>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(notes.len(), "notes length")?;
    let class = env.find_class(JNI_NOTE_INFO)?;
    let mut notes = notes.into_iter().enumerate();
    if let Some((_, first)) = notes.next() {
        let first = make_jni_note_info(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        for (index, note) in notes {
            let note = make_jni_note_info(env, note)?;
            env.set_object_array_element(&array, usize_to_jint(index, "notes index")?, note)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_note_info<'local>(
    env: &mut JNIEnv<'local>,
    note: NoteInfo,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_NOTE_INFO)?;
    let commitment =
        make_jni_fixed_bytes(env, note.commitment, "commitment", PROTOCOL_FIELD_BYTES)?;
    let nullifier = make_jni_fixed_bytes(env, note.nullifier, "nullifier", PROTOCOL_FIELD_BYTES)?;
    let diversifier = make_jni_fixed_bytes(
        env,
        note.diversifier,
        "diversifier",
        ORCHARD_DIVERSIFIER_BYTES,
    )?;
    let rho = make_jni_fixed_bytes(env, note.rho, "rho", PROTOCOL_FIELD_BYTES)?;
    let rseed = make_jni_fixed_bytes(env, note.rseed, "rseed", PROTOCOL_FIELD_BYTES)?;
    let ufvk: JObject<'local> = env.new_string(note.ufvk_str)?.into();

    Ok(env.new_object(
        &class,
        JNI_NOTE_INFO_CTOR_SIG,
        &[
            JValue::Object(&commitment),
            JValue::Object(&nullifier),
            JValue::Long(u64_to_jlong(note.value, "value")?),
            JValue::Long(u64_to_jlong(note.position, "position")?),
            JValue::Object(&diversifier),
            JValue::Object(&rho),
            JValue::Object(&rseed),
            JValue::Int(u32_to_jint(note.scope, "scope")?),
            JValue::Object(&ufvk),
        ],
    )?)
}

pub(super) fn make_jni_witness_data_array<'local>(
    env: &mut JNIEnv<'local>,
    witnesses: Vec<WitnessData>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(witnesses.len(), "witnesses length")?;
    let class = env.find_class(JNI_WITNESS_DATA)?;
    let mut witnesses = witnesses.into_iter().enumerate();
    if let Some((_, first)) = witnesses.next() {
        let first = make_jni_witness_data(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        for (index, witness) in witnesses {
            let witness = make_jni_witness_data(env, witness)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "witnesses index")?,
                witness,
            )?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_witness_data<'local>(
    env: &mut JNIEnv<'local>,
    witness: WitnessData,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_WITNESS_DATA)?;
    let note_commitment = make_jni_fixed_bytes(
        env,
        witness.note_commitment,
        "note_commitment",
        PROTOCOL_FIELD_BYTES,
    )?;
    let root = make_jni_fixed_bytes(env, witness.root, "root", PROTOCOL_FIELD_BYTES)?;
    let auth_path = make_jni_fixed_byte_array_vec(
        env,
        witness.auth_path,
        "auth_path",
        ORCHARD_WITNESS_PATH_DEPTH,
        PROTOCOL_FIELD_BYTES,
    )?;
    let auth_path = JObject::from(auth_path);

    Ok(env.new_object(
        &class,
        JNI_WITNESS_DATA_CTOR_SIG,
        &[
            JValue::Object(&note_commitment),
            JValue::Long(u64_to_jlong(witness.position, "position")?),
            JValue::Object(&root),
            JValue::Object(&auth_path),
        ],
    )?)
}

/// Builds the Kotlin hotkey JNI model after enforcing the expected key widths.
/// The secret key is intentionally not surfaced across JNI.
pub(super) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VOTING_HOTKEY)?;
    let secret_key = SecretVec::new(hotkey.secret_key);
    let secret_key_len = secret_key.expose_secret().len();
    if secret_key_len != PROTOCOL_FIELD_BYTES {
        return Err(anyhow!(
            "hotkey_secret_key must be exactly {PROTOCOL_FIELD_BYTES} bytes, got {secret_key_len}"
        ));
    }
    let public_key = require_len(
        hotkey.public_key,
        "hotkey_public_key",
        HOTKEY_PUBLIC_KEY_BYTES,
    )?;
    let pk_obj: JObject<'local> = env.byte_array_from_slice(&public_key)?.into();
    let addr_obj: JObject<'local> = env.new_string(&hotkey.address)?.into();
    let obj = env.new_object(
        &class,
        JNI_VOTING_HOTKEY_CTOR_SIG,
        &[JValue::Object(&pk_obj), JValue::Object(&addr_obj)],
    )?;
    Ok(obj.into_raw())
}

/// Builds the Kotlin bundle setup JNI model with width-checked Java primitives.
pub(super) fn make_jni_bundle_setup_result<'local>(
    env: &mut JNIEnv<'local>,
    count: u32,
    weight: u64,
    bundle_weights: &[u64],
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_BUNDLE_SETUP_RESULT)?;
    let weights = bundle_weights
        .iter()
        .enumerate()
        .map(|(index, weight)| u64_to_jlong(*weight, &format!("bundle_weights[{index}]")))
        .collect::<anyhow::Result<Vec<_>>>()?;
    let weights_array =
        env.new_long_array(usize_to_jint(weights.len(), "bundle_weights length")?)?;
    env.set_long_array_region(&weights_array, 0, &weights)?;
    let weights_array_obj = JObject::from(weights_array);
    let obj = env.new_object(
        &class,
        JNI_BUNDLE_SETUP_RESULT_CTOR_SIG,
        &[
            JValue::Int(u32_to_jint(count, "bundle_count")?),
            JValue::Long(u64_to_jlong(weight, "eligible_weight")?),
            JValue::Object(&weights_array_obj),
        ],
    )?;
    Ok(obj.into_raw())
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
            JValue::Object(&nf_signed),
            JValue::Object(&cmx_new),
            JValue::Object(&gov_comm),
            JValue::Object(&gov_nullifiers),
            JValue::Object(&vote_round_id),
        ],
    )?;
    Ok(obj.into_raw())
}

fn make_jni_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: &[u8],
) -> anyhow::Result<JObject<'local>> {
    Ok(env.byte_array_from_slice(bytes)?.into())
}

fn make_jni_fixed_bytes<'local>(
    env: &mut JNIEnv<'local>,
    bytes: Vec<u8>,
    field: &str,
    expected: usize,
) -> anyhow::Result<JObject<'local>> {
    make_jni_bytes(env, &require_len(bytes, field, expected)?)
}

fn make_jni_fixed_byte_array_vec<'local>(
    env: &mut JNIEnv<'local>,
    values: Vec<Vec<u8>>,
    field: &str,
    expected_count: usize,
    expected_size: usize,
) -> anyhow::Result<JObjectArray<'local>> {
    if values.len() != expected_count {
        return Err(anyhow!(
            "{field} must contain {expected_count} entries, got {}",
            values.len()
        ));
    }

    let values = require_each_len(values, field, expected_size)?;

    Ok(rust_vec_to_java(env, values, "[B", |env, bytes| {
        Ok(JObject::from(env.byte_array_from_slice(&bytes)?))
    })?)
}

/// Runs the voting note chunker and returns total count, total eligible weight,
/// and each bundle's quantized voting weight.
pub(super) fn bundle_setup_from_notes(notes: &[NoteInfo]) -> anyhow::Result<(u32, u64, Vec<u64>)> {
    let chunk_result = voting::types::chunk_notes(notes);
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
    let chunk_result = voting::types::chunk_notes(notes);
    let bundle_index = usize::try_from(bundle_index)
        .map_err(|_| anyhow!("bundle_index is too large for this platform: {bundle_index}"))?;

    chunk_result
        .bundles
        .get(bundle_index)
        .cloned()
        .ok_or_else(|| anyhow!("bundle_index {bundle_index} is not present in note bundle set"))
}

/// Advances a round phase without allowing regressions; equal phases are
/// treated as idempotent.
pub(super) fn update_round_phase_forward(
    db: &VotingDb,
    round_id: &str,
    phase: RoundPhase,
) -> anyhow::Result<()> {
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    let requested_rank = round_phase_to_u32(phase);

    let rows = conn
        .execute(
            "UPDATE rounds
             SET phase = ?1
             WHERE round_id = ?2
               AND wallet_id = ?3
               AND phase < ?1",
            rusqlite::params![phase as i32, round_id, wallet_id],
        )
        .map_err(|e| anyhow!("update_round_phase: {}", e))?;
    if rows > 0 {
        return Ok(());
    }

    let current = voting::storage::queries::get_round_state(&conn, round_id, &wallet_id)
        .map_err(|e| anyhow!("get_round_state after phase update: {}", e))?
        .phase;
    let current_rank = round_phase_to_u32(current);
    if current_rank < requested_rank {
        return Err(anyhow!(
            "failed to advance round phase for {round_id}: current={current_rank}, requested={requested_rank}"
        ));
    } else if current_rank > requested_rank {
        return Err(anyhow!(
            "refusing to regress round phase for {round_id}: current={current_rank}, requested={requested_rank}"
        ));
    }

    Ok(())
}

/// Requires the hotkey step before PCZT construction and rejects calls after
/// later workflow phases have already begun.
pub(super) fn require_round_phase_for_delegation_construction(
    db: &VotingDb,
    round_id: &str,
) -> anyhow::Result<()> {
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    let current = voting::storage::queries::get_round_state(&conn, round_id, &wallet_id)
        .map_err(|e| anyhow!("get_round_state before delegation construction: {}", e))?
        .phase;
    let current_rank = round_phase_to_u32(current);
    let hotkey_rank = round_phase_to_u32(RoundPhase::HotkeyGenerated);
    let constructed_rank = round_phase_to_u32(RoundPhase::DelegationConstructed);

    if current_rank < hotkey_rank {
        return Err(anyhow!(
            "round {round_id} must be HotkeyGenerated before building governance PCZT: current={current_rank}"
        ));
    }

    if current_rank > constructed_rank {
        return Err(anyhow!(
            "round {round_id} has already advanced beyond DelegationConstructed: current={current_rank}"
        ));
    }

    Ok(())
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

pub(super) fn replace_bundle_witnesses(
    conn: &rusqlite::Connection,
    round_id: &str,
    wallet_id: &str,
    bundle_index: u32,
    witnesses: &[WitnessData],
) -> anyhow::Result<()> {
    for witness in witnesses {
        let valid = voting::witness::verify_witness(witness)
            .map_err(|e| anyhow!("verify_witness: {}", e))?;
        if !valid {
            return Err(anyhow!(
                "witness verification failed for position {}",
                witness.position
            ));
        }
    }

    let tx = conn
        .unchecked_transaction()
        .map_err(|e| anyhow!("begin replace witnesses transaction: {}", e))?;

    tx.execute(
        "DELETE FROM witnesses
         WHERE round_id = :round_id AND wallet_id = :wallet_id AND bundle_index = :bundle_index",
        named_params! {
            ":round_id": round_id,
            ":wallet_id": wallet_id,
            ":bundle_index": bundle_index as i64,
        },
    )
    .map_err(|e| anyhow!("clear_witnesses: {}", e))?;

    voting::storage::queries::store_witnesses(&tx, round_id, wallet_id, bundle_index, witnesses)
        .map_err(|e| anyhow!("store_witnesses: {}", e))?;

    tx.commit()
        .map_err(|e| anyhow!("commit replace witnesses transaction: {}", e))
}

pub(super) fn received_note_to_note_info(
    note: &zcash_client_backend::wallet::ReceivedNote<
        zcash_client_sqlite::ReceivedNoteId,
        orchard::note::Note,
    >,
    ufvk: &UnifiedFullViewingKey,
    network: &Network,
) -> anyhow::Result<NoteInfo> {
    let orchard_note = note.note();
    let fvk = ufvk
        .orchard()
        .ok_or_else(|| anyhow!("UFVK has no Orchard component"))?;

    let nullifier = orchard_note.nullifier(fvk);
    let cmx: orchard::note::ExtractedNoteCommitment = orchard_note.commitment().into();
    let scope = match note.spending_key_scope() {
        Scope::External => NOTE_SCOPE_EXTERNAL,
        Scope::Internal => NOTE_SCOPE_INTERNAL,
    };

    Ok(NoteInfo {
        commitment: cmx.to_bytes().to_vec(),
        nullifier: nullifier.to_bytes().to_vec(),
        value: orchard_note.value().inner(),
        position: u64::from(note.note_commitment_tree_position()),
        diversifier: orchard_note.recipient().diversifier().as_array().to_vec(),
        rho: orchard_note.rho().to_bytes().to_vec(),
        rseed: orchard_note.rseed().as_bytes().to_vec(),
        scope,
        ufvk_str: ufvk.encode(network),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_ROUND_ID: &str = "round-id";
    const TEST_WALLET_ID: &str = "wallet-id";

    #[test]
    fn hotkey_orchard_raw_address_uses_address_index() {
        let seed = [0x42_u8; 64];

        let index_zero =
            hotkey_orchard_raw_address_from_wallet_seed(&seed, Network::TestNetwork, 0, 0).unwrap();
        let index_one =
            hotkey_orchard_raw_address_from_wallet_seed(&seed, Network::TestNetwork, 0, 1).unwrap();

        assert_eq!(ORCHARD_RAW_ADDRESS_BYTES, index_zero.len());
        assert_eq!(ORCHARD_RAW_ADDRESS_BYTES, index_one.len());
        assert_ne!(index_zero, index_one);
    }

    #[test]
    fn nu6_branch_id_comes_from_protocol_crate() {
        assert_eq!(nu6_branch_id(), u32::from(BranchId::Nu6));
    }

    #[test]
    fn update_round_phase_forward_is_idempotent() {
        let db = test_db();

        update_round_phase_forward(&db, TEST_ROUND_ID, RoundPhase::HotkeyGenerated)
            .expect("first phase update");
        update_round_phase_forward(&db, TEST_ROUND_ID, RoundPhase::HotkeyGenerated)
            .expect("idempotent phase update");

        let state = db.get_round_state(TEST_ROUND_ID).expect("round state");
        assert_eq!(RoundPhase::HotkeyGenerated, state.phase);
    }

    #[test]
    fn update_round_phase_forward_rejects_regression() {
        let db = test_db();

        update_round_phase_forward(&db, TEST_ROUND_ID, RoundPhase::DelegationConstructed)
            .expect("advance phase");
        let err = update_round_phase_forward(&db, TEST_ROUND_ID, RoundPhase::HotkeyGenerated)
            .expect_err("regression rejected");

        assert!(err.to_string().contains("refusing to regress round phase"));
    }

    fn test_db() -> VotingDb {
        let db = VotingDb::open(":memory:").expect("test DB");
        db.set_wallet_id(TEST_WALLET_ID);
        db.init_round(&test_round_params(), None)
            .expect("round initialized");
        db
    }

    fn test_round_params() -> voting::types::VotingRoundParams {
        voting::types::VotingRoundParams {
            vote_round_id: TEST_ROUND_ID.to_string(),
            snapshot_height: 100_000,
            ea_pk: vec![0xEA; PROTOCOL_FIELD_BYTES],
            nc_root: vec![0x01; PROTOCOL_FIELD_BYTES],
            nullifier_imt_root: vec![0x02; PROTOCOL_FIELD_BYTES],
        }
    }
}
