use super::*;

// Must match JNI_ROUND_PHASE_* constants in JniVotingModels.kt.
const PHASE_INITIALIZED: u32 = 0;
const PHASE_HOTKEY_GENERATED: u32 = 1;
const PHASE_DELEGATION_CONSTRUCTED: u32 = 2;
const PHASE_DELEGATION_PROVED: u32 = 3;
const PHASE_VOTE_READY: u32 = 4;

const JNI_ROUND_SUMMARY: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniRoundSummary";
const JNI_VOTE_RECORD: &str = "cash/z/ecc/android/sdk/internal/model/voting/JniVoteRecord";

pub(super) const ORCHARD_RAW_ADDRESS_BYTES: usize = 43;
pub(super) const ORCHARD_FVK_BYTES: usize = 96;
pub(super) const PROTOCOL_FIELD_BYTES: usize = 32;
pub(super) const VOTE_COMMITMENT_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const BLIND_BYTES: usize = PROTOCOL_FIELD_BYTES;
pub(super) const SHARE_NULLIFIER_BYTES: usize = PROTOCOL_FIELD_BYTES;
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

pub(super) fn java_bytes_at_least(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    field: &str,
    minimum: usize,
) -> anyhow::Result<Vec<u8>> {
    require_min_len(java_bytes(env, array, field)?, field, minimum)
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
    let addr = orchard_fvk.address_at(address_index, Scope::External);
    require_len(
        addr.to_raw_address_bytes().to_vec(),
        "hotkey_raw_address",
        ORCHARD_RAW_ADDRESS_BYTES,
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

pub(super) fn make_jni_voting_hotkey<'local>(
    env: &mut JNIEnv<'local>,
    hotkey: voting::types::VotingHotkey,
) -> anyhow::Result<jobject> {
    let class = env.find_class("cash/z/ecc/android/sdk/internal/model/voting/JniVotingHotkey")?;
    let secret_key = SecretVec::new(hotkey.secret_key);
    let sk_obj: JObject<'local> = env
        .byte_array_from_slice(secret_key.expose_secret())?
        .into();
    let pk_obj: JObject<'local> = env.byte_array_from_slice(&hotkey.public_key)?.into();
    let addr_obj: JObject<'local> = env.new_string(&hotkey.address)?.into();
    let obj = env.new_object(
        &class,
        "([B[BLjava/lang/String;)V",
        &[
            JValue::Object(&sk_obj),
            JValue::Object(&pk_obj),
            JValue::Object(&addr_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

pub(super) fn make_jni_bundle_setup_result<'local>(
    env: &mut JNIEnv<'local>,
    count: u32,
    weight: u64,
    bundle_weights: &[u64],
) -> anyhow::Result<jobject> {
    let class =
        env.find_class("cash/z/ecc/android/sdk/internal/model/voting/JniBundleSetupResult")?;
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
        "(IJ[J)V",
        &[
            JValue::Int(u32_to_jint(count, "bundle_count")?),
            JValue::Long(u64_to_jlong(weight, "eligible_weight")?),
            JValue::Object(&weights_array_obj),
        ],
    )?;
    Ok(obj.into_raw())
}

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

#[cfg(test)]
mod tests {
    use super::*;

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
}
