use super::*;
use serde::{Deserialize, Serialize};

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
const JNI_VAN_WITNESS: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniVanWitness";
const JNI_WIRE_ENCRYPTED_SHARE: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare";
const JNI_VOTE_COMMITMENT_RESULT: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitmentResult";
const JNI_SHARE_PAYLOAD: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniSharePayload";
const JNI_COMMITMENT_BUNDLE_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniCommitmentBundleRecord";
const JNI_SHARE_DELEGATION_RECORD: &str =
    "cash/z/ecc/android/sdk/internal/model/voting/JniShareDelegationRecord";
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
// Guarded by JniVotingModelsTest.
const JNI_NOTE_INFO_CTOR_SIG: &str = "([B[BJJ[B[B[BILjava/lang/String;)V";
// Must match JniWitnessData(ByteArray, Long, ByteArray, Array<ByteArray>)
// in JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_WITNESS_DATA_CTOR_SIG: &str = "([BJ[B[[B)V";
// Must match JniVanWitness(Array<ByteArray>, Long, Long) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_VAN_WITNESS_CTOR_SIG: &str = "([[BJJ)V";
// Must match JniWireEncryptedShare(ByteArray, ByteArray, Int) in
// JniVotingModels.kt.
const JNI_WIRE_ENCRYPTED_SHARE_CTOR_SIG: &str = "([B[BI)V";
// Must match JniVoteCommitmentResult(ByteArray, ByteArray, ByteArray, Int,
// ByteArray, Array<JniWireEncryptedShare>, Long, String, ByteArray,
// Array<ByteArray>, Array<ByteArray>, ByteArray, ByteArray) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_VOTE_COMMITMENT_RESULT_CTOR_SIG: &str = "([B[B[BI[B[Lcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;JLjava/lang/String;[B[[B[[B[B[B)V";
// Must match JniCommitmentBundleRecord(JniVoteCommitmentResult, Long) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_COMMITMENT_BUNDLE_RECORD_CTOR_SIG: &str =
    "(Lcash/z/ecc/android/sdk/internal/model/voting/JniVoteCommitmentResult;J)V";
// Must match JniSharePayload(ByteArray, Int, Int, JniWireEncryptedShare,
// Long, Array<JniWireEncryptedShare>, Array<ByteArray>, ByteArray) in
// JniVotingModels.kt. Guarded by JniVotingModelsTest.
const JNI_SHARE_PAYLOAD_CTOR_SIG: &str = "([BIILcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;J[Lcash/z/ecc/android/sdk/internal/model/voting/JniWireEncryptedShare;[[B[B)V";
// Must match JniShareDelegationRecord(String, Int, Int, Int, Array<String>,
// ByteArray, Boolean, Long, Long) in JniVotingModels.kt. Guarded by
// JniVotingModelsTest.
const JNI_SHARE_DELEGATION_RECORD_CTOR_SIG: &str =
    "(Ljava/lang/String;III[Ljava/lang/String;[BZJJ)V";
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
pub(super) const HOTKEY_SECRET_KEY_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const HOTKEY_PUBLIC_KEY_BYTES: usize = PROTOCOL_FIELD_BYTES;
// Hotkeys use one stable Orchard address for voting identity and recovery.
pub(super) const HOTKEY_ADDRESS_INDEX: u32 = 0;
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

struct JniVoteCommitmentResultPayload {
    van_nullifier: Vec<u8>,
    vote_authority_note_new: Vec<u8>,
    vote_commitment: Vec<u8>,
    proposal_id: u32,
    proof: Vec<u8>,
    enc_shares: Vec<WireEncryptedShare>,
    anchor_height: u32,
    vote_round_id: String,
    shares_hash: Vec<u8>,
    share_blinds: Vec<Vec<u8>>,
    share_comms: Vec<Vec<u8>>,
    r_vpk_bytes: Vec<u8>,
    alpha_v: Vec<u8>,
}

#[derive(Deserialize, Serialize)]
struct StoredWireEncryptedShare {
    c1: String,
    c2: String,
    share_index: u32,
}

// Recovery replay needs the reveal and signing inputs below. Keep this JSON out
// of logs; it contains the same sensitive fields as JniVoteCommitmentResult.
#[derive(Deserialize, Serialize)]
pub(super) struct StoredVoteCommitmentBundle {
    van_nullifier: String,
    vote_authority_note_new: String,
    vote_commitment: String,
    proposal_id: u32,
    proof: String,
    enc_shares: Vec<StoredWireEncryptedShare>,
    anchor_height: u32,
    vote_round_id: String,
    shares_hash: String,
    share_blinds: Vec<String>,
    share_comms: Vec<String>,
    r_vpk_bytes: String,
    alpha_v: String,
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

pub(super) fn require_count<T>(
    values: Vec<T>,
    field: &str,
    expected: usize,
) -> anyhow::Result<Vec<T>> {
    if values.len() == expected {
        Ok(values)
    } else {
        Err(anyhow!(
            "{field} must contain {expected} entries, got {}",
            values.len()
        ))
    }
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

fn hex_enc(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

fn hex_dec(value: &str, field: &str) -> anyhow::Result<Vec<u8>> {
    hex::decode(value).map_err(|e| anyhow!("{field}: invalid hex: {e}"))
}

fn hex_dec_exact(value: &str, field: &str, expected: usize) -> anyhow::Result<Vec<u8>> {
    require_len(hex_dec(value, field)?, field, expected)
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
    let scope = require_note_scope(jint_to_u32(
        env.get_field(note, "scope", "I")?.i()?,
        "scope",
    )?)?;

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

pub(super) fn java_van_witness(
    env: &mut JNIEnv<'_>,
    witness: &JObject<'_>,
) -> anyhow::Result<voting::tree_sync::VanWitness> {
    let auth_path = java_byte_array_list_field(env, witness, "authPath")?;
    if auth_path.len() != VAN_WITNESS_PATH_DEPTH {
        return Err(anyhow!(
            "authPath must contain {VAN_WITNESS_PATH_DEPTH} entries, got {}",
            auth_path.len()
        ));
    }

    let auth_path = auth_path
        .into_iter()
        .enumerate()
        .map(|(index, bytes)| fixed_bytes(bytes, &format!("authPath[{index}]")))
        .collect::<anyhow::Result<Vec<_>>>()?;

    Ok(voting::tree_sync::VanWitness {
        auth_path,
        position: jlong_to_u32(env.get_field(witness, "position", "J")?.j()?, "position")?,
        anchor_height: jlong_to_u32(
            env.get_field(witness, "anchorHeight", "J")?.j()?,
            "anchorHeight",
        )?,
    })
}

fn java_wire_encrypted_share(
    env: &mut JNIEnv<'_>,
    share: &JObject<'_>,
) -> anyhow::Result<WireEncryptedShare> {
    let share_index = require_share_index(
        jint_to_u32(env.get_field(share, "shareIndex", "I")?.i()?, "shareIndex")?,
        "shareIndex",
    )?;

    Ok(WireEncryptedShare {
        c1: require_len(
            java_byte_array_field(env, share, "c1")?,
            "c1",
            PROTOCOL_FIELD_BYTES,
        )?,
        c2: require_len(
            java_byte_array_field(env, share, "c2")?,
            "c2",
            PROTOCOL_FIELD_BYTES,
        )?,
        share_index,
    })
}

fn java_wire_encrypted_share_list_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Vec<WireEncryptedShare>> {
    let list = env.get_field(obj, name, "Ljava/util/List;")?.l()?;
    let count = env.call_method(&list, "size", "()I", &[])?.i()?;
    if count < 0 {
        return Err(anyhow!("{name}.size() returned negative count {count}"));
    }

    (0..count)
        .map(|index| {
            let share = env
                .call_method(&list, "get", "(I)Ljava/lang/Object;", &[JValue::Int(index)])?
                .l()?;
            java_wire_encrypted_share(env, &share).map_err(|e| anyhow!("{name}[{index}]: {e}"))
        })
        .collect()
}

pub(super) struct JavaVoteCommitmentBundle {
    pub(super) enc_shares: Vec<WireEncryptedShare>,
    pub(super) bundle: VoteCommitmentBundle,
}

pub(super) fn java_vote_commitment_bundle(
    env: &mut JNIEnv<'_>,
    commitment: &JObject<'_>,
) -> anyhow::Result<JavaVoteCommitmentBundle> {
    let enc_shares = require_count(
        java_wire_encrypted_share_list_field(env, commitment, "encShares")?,
        "encShares",
        VOTE_SHARE_COUNT,
    )?;
    let share_blinds = require_count(
        java_byte_array_list_field(env, commitment, "shareBlinds")?,
        "shareBlinds",
        VOTE_SHARE_COUNT,
    )?;
    let share_comms = require_count(
        java_byte_array_list_field(env, commitment, "shareComms")?,
        "shareComms",
        VOTE_SHARE_COUNT,
    )?;

    Ok(JavaVoteCommitmentBundle {
        enc_shares,
        bundle: VoteCommitmentBundle {
            van_nullifier: require_len(
                java_byte_array_field(env, commitment, "vanNullifier")?,
                "vanNullifier",
                PROTOCOL_FIELD_BYTES,
            )?,
            vote_authority_note_new: require_len(
                java_byte_array_field(env, commitment, "voteAuthorityNoteNew")?,
                "voteAuthorityNoteNew",
                PROTOCOL_FIELD_BYTES,
            )?,
            vote_commitment: require_len(
                java_byte_array_field(env, commitment, "voteCommitment")?,
                "voteCommitment",
                PROTOCOL_FIELD_BYTES,
            )?,
            proposal_id: jint_to_u32(
                env.get_field(commitment, "proposalId", "I")?.i()?,
                "proposalId",
            )?,
            proof: java_byte_array_field(env, commitment, "proof")?,
            // Java carries WireEncryptedShare values plus transient reveal and
            // signing inputs. The encrypted-share plaintext/randomness fields
            // intentionally never cross JNI.
            enc_shares: Vec::new(),
            anchor_height: jlong_to_u32(
                env.get_field(commitment, "anchorHeight", "J")?.j()?,
                "anchorHeight",
            )?,
            vote_round_id: java_string_field(env, commitment, "voteRoundId")?,
            shares_hash: require_len(
                java_byte_array_field(env, commitment, "sharesHash")?,
                "sharesHash",
                PROTOCOL_FIELD_BYTES,
            )?,
            share_blinds: require_each_len(share_blinds, "shareBlinds", PROTOCOL_FIELD_BYTES)?,
            share_comms: require_each_len(share_comms, "shareComms", PROTOCOL_FIELD_BYTES)?,
            r_vpk_bytes: require_len(
                java_byte_array_field(env, commitment, "rVpk")?,
                "rVpk",
                PROTOCOL_FIELD_BYTES,
            )?,
            alpha_v: require_len(
                java_byte_array_field(env, commitment, "alphaV")?,
                "alphaV",
                PROTOCOL_FIELD_BYTES,
            )?,
        },
    })
}

impl TryFrom<JavaVoteCommitmentBundle> for StoredVoteCommitmentBundle {
    type Error = anyhow::Error;

    fn try_from(commitment: JavaVoteCommitmentBundle) -> anyhow::Result<Self> {
        let bundle = commitment.bundle;
        Ok(StoredVoteCommitmentBundle {
            van_nullifier: hex_enc(&bundle.van_nullifier),
            vote_authority_note_new: hex_enc(&bundle.vote_authority_note_new),
            vote_commitment: hex_enc(&bundle.vote_commitment),
            proposal_id: bundle.proposal_id,
            proof: hex_enc(&bundle.proof),
            enc_shares: commitment
                .enc_shares
                .into_iter()
                .map(StoredWireEncryptedShare::from)
                .collect(),
            anchor_height: bundle.anchor_height,
            vote_round_id: bundle.vote_round_id,
            shares_hash: hex_enc(&bundle.shares_hash),
            share_blinds: bundle
                .share_blinds
                .iter()
                .map(|value| hex_enc(value))
                .collect(),
            share_comms: bundle
                .share_comms
                .iter()
                .map(|value| hex_enc(value))
                .collect(),
            r_vpk_bytes: hex_enc(&bundle.r_vpk_bytes),
            alpha_v: hex_enc(&bundle.alpha_v),
        })
    }
}

impl StoredVoteCommitmentBundle {
    pub(super) fn to_storage_json(&self) -> anyhow::Result<String> {
        serde_json::to_string(self)
            .map_err(|e| anyhow!("commitment bundle JSON serialization failed: {e}"))
    }

    pub(super) fn from_storage_json(value: &str) -> anyhow::Result<Self> {
        serde_json::from_str(value).map_err(|e| anyhow!("commitment bundle JSON parse failed: {e}"))
    }
}

impl From<WireEncryptedShare> for StoredWireEncryptedShare {
    fn from(share: WireEncryptedShare) -> Self {
        StoredWireEncryptedShare {
            c1: hex_enc(&share.c1),
            c2: hex_enc(&share.c2),
            share_index: share.share_index,
        }
    }
}

impl TryFrom<StoredWireEncryptedShare> for WireEncryptedShare {
    type Error = anyhow::Error;

    fn try_from(share: StoredWireEncryptedShare) -> anyhow::Result<Self> {
        Ok(WireEncryptedShare {
            c1: hex_dec_exact(&share.c1, "encShares[].c1", PROTOCOL_FIELD_BYTES)?,
            c2: hex_dec_exact(&share.c2, "encShares[].c2", PROTOCOL_FIELD_BYTES)?,
            share_index: require_share_index(share.share_index, "encShares[].shareIndex")?,
        })
    }
}

impl From<VoteCommitmentBundle> for JniVoteCommitmentResultPayload {
    fn from(bundle: VoteCommitmentBundle) -> Self {
        JniVoteCommitmentResultPayload {
            van_nullifier: bundle.van_nullifier,
            vote_authority_note_new: bundle.vote_authority_note_new,
            vote_commitment: bundle.vote_commitment,
            proposal_id: bundle.proposal_id,
            proof: bundle.proof,
            enc_shares: bundle
                .enc_shares
                .into_iter()
                .map(WireEncryptedShare::from)
                .collect(),
            anchor_height: bundle.anchor_height,
            vote_round_id: bundle.vote_round_id,
            shares_hash: bundle.shares_hash,
            share_blinds: bundle.share_blinds,
            share_comms: bundle.share_comms,
            r_vpk_bytes: bundle.r_vpk_bytes,
            alpha_v: bundle.alpha_v,
        }
    }
}

impl TryFrom<StoredVoteCommitmentBundle> for JniVoteCommitmentResultPayload {
    type Error = anyhow::Error;

    fn try_from(bundle: StoredVoteCommitmentBundle) -> anyhow::Result<Self> {
        Ok(JniVoteCommitmentResultPayload {
            van_nullifier: hex_dec_exact(
                &bundle.van_nullifier,
                "vanNullifier",
                PROTOCOL_FIELD_BYTES,
            )?,
            vote_authority_note_new: hex_dec_exact(
                &bundle.vote_authority_note_new,
                "voteAuthorityNoteNew",
                PROTOCOL_FIELD_BYTES,
            )?,
            vote_commitment: hex_dec_exact(
                &bundle.vote_commitment,
                "voteCommitment",
                PROTOCOL_FIELD_BYTES,
            )?,
            proposal_id: bundle.proposal_id,
            proof: hex_dec(&bundle.proof, "proof")?,
            enc_shares: require_count(
                bundle
                    .enc_shares
                    .into_iter()
                    .enumerate()
                    .map(|(index, share)| {
                        WireEncryptedShare::try_from(share)
                            .map_err(|e| anyhow!("encShares[{index}]: {e}"))
                    })
                    .collect::<anyhow::Result<Vec<_>>>()?,
                "encShares",
                VOTE_SHARE_COUNT,
            )?,
            anchor_height: bundle.anchor_height,
            vote_round_id: bundle.vote_round_id,
            shares_hash: hex_dec_exact(&bundle.shares_hash, "sharesHash", PROTOCOL_FIELD_BYTES)?,
            share_blinds: require_count(
                bundle
                    .share_blinds
                    .iter()
                    .enumerate()
                    .map(|(index, value)| {
                        hex_dec_exact(
                            value,
                            &format!("shareBlinds[{index}]"),
                            PROTOCOL_FIELD_BYTES,
                        )
                    })
                    .collect::<anyhow::Result<Vec<_>>>()?,
                "shareBlinds",
                VOTE_SHARE_COUNT,
            )?,
            share_comms: require_count(
                bundle
                    .share_comms
                    .iter()
                    .enumerate()
                    .map(|(index, value)| {
                        hex_dec_exact(value, &format!("shareComms[{index}]"), PROTOCOL_FIELD_BYTES)
                    })
                    .collect::<anyhow::Result<Vec<_>>>()?,
                "shareComms",
                VOTE_SHARE_COUNT,
            )?,
            r_vpk_bytes: hex_dec_exact(&bundle.r_vpk_bytes, "rVpk", PROTOCOL_FIELD_BYTES)?,
            alpha_v: hex_dec_exact(&bundle.alpha_v, "alphaV", PROTOCOL_FIELD_BYTES)?,
        })
    }
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
        env.delete_local_ref(first)?;
        for (index, note) in notes {
            let note = make_jni_note_info(env, note)?;
            env.set_object_array_element(&array, usize_to_jint(index, "notes index")?, &note)?;
            env.delete_local_ref(note)?;
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
    env.with_local_frame_returning_local(16, |env| {
        let class = env.find_class(JNI_NOTE_INFO)?;
        let commitment =
            make_jni_fixed_bytes(env, note.commitment, "commitment", PROTOCOL_FIELD_BYTES)?;
        let nullifier =
            make_jni_fixed_bytes(env, note.nullifier, "nullifier", PROTOCOL_FIELD_BYTES)?;
        let diversifier = make_jni_fixed_bytes(
            env,
            note.diversifier,
            "diversifier",
            ORCHARD_DIVERSIFIER_BYTES,
        )?;
        let rho = make_jni_fixed_bytes(env, note.rho, "rho", PROTOCOL_FIELD_BYTES)?;
        let rseed = make_jni_fixed_bytes(env, note.rseed, "rseed", PROTOCOL_FIELD_BYTES)?;
        let ufvk: JObject<'_> = env.new_string(note.ufvk_str)?.into();

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
    })
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
        env.delete_local_ref(first)?;
        for (index, witness) in witnesses {
            let witness = make_jni_witness_data(env, witness)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "witnesses index")?,
                &witness,
            )?;
            env.delete_local_ref(witness)?;
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
    env.with_local_frame_returning_local(48, |env| {
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
    })
}

// JNI object construction needs a JNIEnv-bound local frame, so these builders
// stay explicit instead of being modeled as TryFrom conversions.
pub(super) fn make_jni_van_witness<'local>(
    env: &mut JNIEnv<'local>,
    witness: voting::tree_sync::VanWitness,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VAN_WITNESS)?;
    let auth_path = witness
        .auth_path
        .into_iter()
        .map(|bytes| bytes.to_vec())
        .collect();
    let auth_path = make_jni_fixed_byte_array_vec(
        env,
        auth_path,
        "auth_path",
        VAN_WITNESS_PATH_DEPTH,
        PROTOCOL_FIELD_BYTES,
    )?;
    let auth_path = JObject::from(auth_path);

    Ok(env
        .new_object(
            &class,
            JNI_VAN_WITNESS_CTOR_SIG,
            &[
                JValue::Object(&auth_path),
                JValue::Long(u64_to_jlong(u64::from(witness.position), "position")?),
                JValue::Long(u64_to_jlong(
                    u64::from(witness.anchor_height),
                    "anchor_height",
                )?),
            ],
        )?
        .into_raw())
}

fn make_jni_wire_encrypted_share<'local>(
    env: &mut JNIEnv<'local>,
    share: WireEncryptedShare,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_WIRE_ENCRYPTED_SHARE)?;
    let c1 = make_jni_fixed_bytes(env, share.c1, "c1", PROTOCOL_FIELD_BYTES)?;
    let c2 = make_jni_fixed_bytes(env, share.c2, "c2", PROTOCOL_FIELD_BYTES)?;

    Ok(env.new_object(
        &class,
        JNI_WIRE_ENCRYPTED_SHARE_CTOR_SIG,
        &[
            JValue::Object(&c1),
            JValue::Object(&c2),
            JValue::Int(u32_to_jint(share.share_index, "share_index")?),
        ],
    )?)
}

fn make_jni_wire_encrypted_share_array<'local>(
    env: &mut JNIEnv<'local>,
    shares: Vec<WireEncryptedShare>,
) -> anyhow::Result<JObjectArray<'local>> {
    let len = usize_to_jint(shares.len(), "shares length")?;
    let class = env.find_class(JNI_WIRE_ENCRYPTED_SHARE)?;
    let mut shares = shares.into_iter().enumerate();
    if let Some((_, first)) = shares.next() {
        let first = make_jni_wire_encrypted_share(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        for (index, share) in shares {
            let share = make_jni_wire_encrypted_share(env, share)?;
            env.set_object_array_element(&array, usize_to_jint(index, "shares index")?, share)?;
        }
        Ok(array)
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?)
    }
}

pub(super) fn make_jni_vote_commitment_result<'local>(
    env: &mut JNIEnv<'local>,
    bundle: VoteCommitmentBundle,
) -> anyhow::Result<jobject> {
    make_jni_vote_commitment_result_payload(env, JniVoteCommitmentResultPayload::from(bundle))
}

fn make_jni_vote_commitment_result_payload<'local>(
    env: &mut JNIEnv<'local>,
    payload: JniVoteCommitmentResultPayload,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VOTE_COMMITMENT_RESULT)?;
    let enc_shares = require_count(payload.enc_shares, "enc_shares", VOTE_SHARE_COUNT)?;
    let van_nullifier = make_jni_fixed_bytes(
        env,
        payload.van_nullifier,
        "van_nullifier",
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_authority_note_new = make_jni_fixed_bytes(
        env,
        payload.vote_authority_note_new,
        "vote_authority_note_new",
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_commitment = make_jni_fixed_bytes(
        env,
        payload.vote_commitment,
        "vote_commitment",
        PROTOCOL_FIELD_BYTES,
    )?;
    let proof = make_jni_bytes(env, &payload.proof)?;
    let enc_shares = make_jni_wire_encrypted_share_array(env, enc_shares)?;
    let enc_shares = JObject::from(enc_shares);
    let vote_round_id: JObject<'local> = env.new_string(payload.vote_round_id)?.into();
    let shares_hash = make_jni_fixed_bytes(
        env,
        payload.shares_hash,
        "shares_hash",
        PROTOCOL_FIELD_BYTES,
    )?;
    let share_blinds = make_jni_fixed_byte_array_vec(
        env,
        payload.share_blinds,
        "share_blinds",
        VOTE_SHARE_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let share_comms = make_jni_fixed_byte_array_vec(
        env,
        payload.share_comms,
        "share_comms",
        VOTE_SHARE_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let share_blinds = JObject::from(share_blinds);
    let share_comms = JObject::from(share_comms);
    let r_vpk = make_jni_fixed_bytes(env, payload.r_vpk_bytes, "r_vpk", PROTOCOL_FIELD_BYTES)?;
    let alpha_v = make_jni_fixed_bytes(env, payload.alpha_v, "alpha_v", PROTOCOL_FIELD_BYTES)?;

    Ok(env
        .new_object(
            &class,
            JNI_VOTE_COMMITMENT_RESULT_CTOR_SIG,
            &[
                JValue::Object(&van_nullifier),
                JValue::Object(&vote_authority_note_new),
                JValue::Object(&vote_commitment),
                JValue::Int(u32_to_jint(payload.proposal_id, "proposal_id")?),
                JValue::Object(&proof),
                JValue::Object(&enc_shares),
                JValue::Long(u64_to_jlong(
                    u64::from(payload.anchor_height),
                    "anchor_height",
                )?),
                JValue::Object(&vote_round_id),
                JValue::Object(&shares_hash),
                JValue::Object(&share_blinds),
                JValue::Object(&share_comms),
                JValue::Object(&r_vpk),
                JValue::Object(&alpha_v),
            ],
        )?
        .into_raw())
}

pub(super) fn make_jni_commitment_bundle_record<'local>(
    env: &mut JNIEnv<'local>,
    bundle: StoredVoteCommitmentBundle,
    vc_tree_position: u64,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_COMMITMENT_BUNDLE_RECORD)?;
    let commitment = make_jni_vote_commitment_result_payload(env, bundle.try_into()?)?;
    let commitment = unsafe { JObject::from_raw(commitment) };
    let record = env.new_object(
        &class,
        JNI_COMMITMENT_BUNDLE_RECORD_CTOR_SIG,
        &[
            JValue::Object(&commitment),
            JValue::Long(u64_to_jlong(vc_tree_position, "vc_tree_position")?),
        ],
    )?;
    Ok(record.into_raw())
}

pub(super) fn make_jni_share_payload_array<'local>(
    env: &mut JNIEnv<'local>,
    payloads: Vec<SharePayload>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(payloads.len(), "payloads length")?;
    let class = env.find_class(JNI_SHARE_PAYLOAD)?;
    let mut payloads = payloads.into_iter().enumerate();
    if let Some((_, first)) = payloads.next() {
        let first = make_jni_share_payload(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        for (index, payload) in payloads {
            let payload = make_jni_share_payload(env, payload)?;
            env.set_object_array_element(&array, usize_to_jint(index, "payloads index")?, payload)?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_share_payload<'local>(
    env: &mut JNIEnv<'local>,
    payload: SharePayload,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_SHARE_PAYLOAD)?;
    let shares_hash = make_jni_fixed_bytes(
        env,
        payload.shares_hash,
        "shares_hash",
        PROTOCOL_FIELD_BYTES,
    )?;
    let enc_share = make_jni_wire_encrypted_share(env, payload.enc_share)?;
    let all_enc_shares = require_count(payload.all_enc_shares, "all_enc_shares", VOTE_SHARE_COUNT)?;
    let all_enc_shares = make_jni_wire_encrypted_share_array(env, all_enc_shares)?;
    let share_comms = make_jni_fixed_byte_array_vec(
        env,
        payload.share_comms,
        "share_comms",
        VOTE_SHARE_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let primary_blind = make_jni_fixed_bytes(
        env,
        payload.primary_blind,
        "primary_blind",
        PROTOCOL_FIELD_BYTES,
    )?;
    let all_enc_shares = JObject::from(all_enc_shares);
    let share_comms = JObject::from(share_comms);

    Ok(env.new_object(
        &class,
        JNI_SHARE_PAYLOAD_CTOR_SIG,
        &[
            JValue::Object(&shares_hash),
            JValue::Int(u32_to_jint(payload.proposal_id, "proposal_id")?),
            JValue::Int(u32_to_jint(payload.vote_decision, "vote_decision")?),
            JValue::Object(&enc_share),
            JValue::Long(u64_to_jlong(payload.tree_position, "tree_position")?),
            JValue::Object(&all_enc_shares),
            JValue::Object(&share_comms),
            JValue::Object(&primary_blind),
        ],
    )?)
}

pub(super) fn make_jni_share_delegation_record_array<'local>(
    env: &mut JNIEnv<'local>,
    records: Vec<voting::ShareDelegationRecord>,
) -> anyhow::Result<jobjectArray> {
    let len = usize_to_jint(records.len(), "share delegation record length")?;
    let class = env.find_class(JNI_SHARE_DELEGATION_RECORD)?;
    let mut records = records.into_iter().enumerate();
    if let Some((_, first)) = records.next() {
        let first = make_jni_share_delegation_record(env, first)?;
        let array = env.new_object_array(len, &class, &first)?;
        for (index, record) in records {
            let record = make_jni_share_delegation_record(env, record)?;
            env.set_object_array_element(
                &array,
                usize_to_jint(index, "share delegation record index")?,
                record,
            )?;
        }
        Ok(array.into_raw())
    } else {
        Ok(env.new_object_array(0, &class, JObject::null())?.into_raw())
    }
}

fn make_jni_share_delegation_record<'local>(
    env: &mut JNIEnv<'local>,
    record: voting::ShareDelegationRecord,
) -> anyhow::Result<JObject<'local>> {
    let class = env.find_class(JNI_SHARE_DELEGATION_RECORD)?;
    let round_id: JObject<'local> = env.new_string(record.round_id)?.into();
    let sent_to_urls = make_jni_string_array(env, record.sent_to_urls)?;
    let sent_to_urls = JObject::from(sent_to_urls);
    let nullifier = make_jni_fixed_bytes(
        env,
        record.nullifier,
        "share_delegation.nullifier",
        SHARE_NULLIFIER_BYTES,
    )?;

    Ok(env.new_object(
        &class,
        JNI_SHARE_DELEGATION_RECORD_CTOR_SIG,
        &[
            JValue::Object(&round_id),
            JValue::Int(u32_to_jint(record.bundle_index, "bundle_index")?),
            JValue::Int(u32_to_jint(record.proposal_id, "proposal_id")?),
            JValue::Int(u32_to_jint(record.share_index, "share_index")?),
            JValue::Object(&sent_to_urls),
            JValue::Object(&nullifier),
            JValue::Bool(record.confirmed as jboolean),
            JValue::Long(u64_to_jlong(record.submit_at, "submit_at")?),
            JValue::Long(u64_to_jlong(record.created_at, "created_at")?),
        ],
    )?)
}

fn make_jni_string_array<'local>(
    env: &mut JNIEnv<'local>,
    values: Vec<String>,
) -> anyhow::Result<JObjectArray<'local>> {
    Ok(rust_vec_to_java(
        env,
        values,
        "java/lang/String",
        |env, value| Ok(JObject::from(env.new_string(value)?)),
    )?)
}

/// Builds the Kotlin hotkey JNI model after enforcing the expected key widths.
/// The secret key is intentionally not surfaced across JNI.
pub(super) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VOTING_HOTKEY)?;
    require_len(
        hotkey.secret_key,
        "hotkey_secret_key",
        HOTKEY_SECRET_KEY_BYTES,
    )?;
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
    db.advance_round_phase(round_id, phase)
        .map_err(|e| anyhow!("advance_round_phase: {}", e))
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
    db: &VotingDb,
    round_id: &str,
    bundle_index: u32,
    witnesses: &[WitnessData],
) -> anyhow::Result<()> {
    db.replace_bundle_witnesses(round_id, bundle_index, witnesses)
        .map_err(|e| anyhow!("replace_bundle_witnesses: {}", e))
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
