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
    bundle_index: u32,
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

// zcash_voting 1.0.0 (merged-library patch) no longer re-exports `VanWitness` from
// `tree_sync` (it's defined in, and now only publicly reachable via, `vote`); same fields.
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

impl JniVoteCommitmentResultPayload {
    // zcash_voting 1.0.0 persists commitment recovery state as its own
    // VoteRecoveryBundle JSON (crate::vote::parse_recovery), not the hand-rolled
    // hex-string JSON this SDK used to own. Recovery fields are already
    // typed byte arrays, so no hex encode/decode round trip is needed anymore.
    fn from_recovery_bundle(bundle: voting::vote::VoteRecoveryBundle, bundle_index: u32) -> Self {
        Self {
            van_nullifier: bundle.van_nullifier.to_vec(),
            vote_authority_note_new: bundle.vote_authority_note_new.to_vec(),
            vote_commitment: bundle.vote_commitment.to_vec(),
            proposal_id: bundle.proposal_id,
            bundle_index,
            proof: bundle.proof,
            enc_shares: bundle
                .encrypted_shares
                .iter()
                .map(WireEncryptedShare::from)
                .collect(),
            anchor_height: bundle.anchor_height,
            vote_round_id: bundle.vote_round_id,
            shares_hash: bundle.shares_hash.to_vec(),
            share_blinds: bundle
                .share_blinds
                .iter()
                .map(|value| value.to_vec())
                .collect(),
            share_comms: bundle
                .share_comms
                .iter()
                .map(|value| value.to_vec())
                .collect(),
            r_vpk_bytes: bundle.r_vpk.to_vec(),
            alpha_v: bundle.alpha_v.to_vec(),
        }
    }
}

pub(super) fn require_note_scope(scope: u32) -> anyhow::Result<u32> {
    match scope {
        NOTE_SCOPE_EXTERNAL | NOTE_SCOPE_INTERNAL => Ok(scope),
        _ => Err(anyhow!(
            "scope must be {NOTE_SCOPE_EXTERNAL} (external) or {NOTE_SCOPE_INTERNAL} (internal), got {scope}"
        )),
    }
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

pub(super) fn make_jni_vote_records(
    env: &mut JNIEnv<'_>,
    votes: Vec<VoteRecovery>,
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

// VoteRecord no longer carries a `submitted` flag in zcash_voting 1.0; derive it
// from recovery state instead (a recorded tx_hash means the vote was submitted).
impl TryFrom<VoteRecovery> for JniVoteRecordPayload {
    type Error = anyhow::Error;

    fn try_from(record: VoteRecovery) -> anyhow::Result<Self> {
        Ok(JniVoteRecordPayload {
            proposal_id: u32_to_jint(record.proposal_id, "proposal_id")?,
            bundle_index: u32_to_jint(record.bundle_index, "bundle_index")?,
            choice: u32_to_jint(record.choice, "choice")?,
            submitted: record.tx_hash.is_some(),
        })
    }
}

// JNI object construction needs a JNIEnv-bound local frame, so these builders
// stay explicit instead of being modeled as TryFrom conversions.
/// Builds the one-shot `vote::commit` result: the signed commitment bundle
/// plus the vote_auth_sig and share_payloads it produces.
pub(super) fn make_jni_vote_commit_result<'local>(
    env: &mut JNIEnv<'local>,
    commit: voting::vote::SignedVoteCommitment,
    bundle_index: u32,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_VOTE_COMMIT_RESULT)?;
    let vote_round_id: JObject<'local> = env.new_string(&commit.vote_round_id)?.into();
    let van_nullifier = make_jni_fixed_bytes(
        env,
        commit.van_nullifier.to_vec(),
        "van_nullifier",
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_authority_note_new = make_jni_fixed_bytes(
        env,
        commit.vote_authority_note_new.to_vec(),
        "vote_authority_note_new",
        PROTOCOL_FIELD_BYTES,
    )?;
    let vote_commitment = make_jni_fixed_bytes(
        env,
        commit.vote_commitment.to_vec(),
        "vote_commitment",
        PROTOCOL_FIELD_BYTES,
    )?;
    let proof = make_jni_bytes(env, &commit.proof)?;
    let enc_shares = require_count(commit.encrypted_shares, "enc_shares", VOTE_SHARE_COUNT)?;
    let enc_shares = make_jni_wire_encrypted_share_array(env, enc_shares)?;
    let enc_shares = JObject::from(enc_shares);
    let shares_hash = make_jni_fixed_bytes(
        env,
        commit.shares_hash.to_vec(),
        "shares_hash",
        PROTOCOL_FIELD_BYTES,
    )?;
    let share_comms = commit
        .share_comms
        .iter()
        .map(|value| value.to_vec())
        .collect::<Vec<_>>();
    let share_comms = make_jni_fixed_byte_array_vec(
        env,
        share_comms,
        "share_comms",
        VOTE_SHARE_COUNT,
        PROTOCOL_FIELD_BYTES,
    )?;
    let share_comms = JObject::from(share_comms);
    let r_vpk = make_jni_fixed_bytes(env, commit.r_vpk.to_vec(), "r_vpk", PROTOCOL_FIELD_BYTES)?;
    let vote_auth_sig = make_jni_fixed_bytes(
        env,
        commit.vote_auth_sig.to_vec(),
        "vote_auth_sig",
        SPEND_AUTH_SIG_BYTES,
    )?;
    let share_payloads = make_jni_share_payload_array(env, commit.share_payloads)?;
    let share_payloads = unsafe { JObject::from_raw(share_payloads) };

    Ok(env
        .new_object(
            &class,
            JNI_VOTE_COMMIT_RESULT_CTOR_SIG,
            &[
                JValue::Int(u32_to_jint(bundle_index, "bundle_index")?),
                JValue::Int(u32_to_jint(commit.proposal_id, "proposal_id")?),
                JValue::Int(u32_to_jint(commit.choice, "choice")?),
                JValue::Object(&vote_round_id),
                JValue::Object(&van_nullifier),
                JValue::Object(&vote_authority_note_new),
                JValue::Object(&vote_commitment),
                JValue::Object(&proof),
                JValue::Object(&enc_shares),
                JValue::Long(u64_to_jlong(
                    u64::from(commit.anchor_height),
                    "anchor_height",
                )?),
                JValue::Object(&shares_hash),
                JValue::Object(&share_comms),
                JValue::Object(&r_vpk),
                JValue::Object(&vote_auth_sig),
                JValue::Object(&share_payloads),
            ],
        )?
        .into_raw())
}

/// Wraps a recovered `vote::commit` result with its confirmed vote commitment
/// tree position, for `recoverCommittedVoteNative`.
pub(super) fn make_jni_committed_vote_record<'local>(
    env: &mut JNIEnv<'local>,
    commit: voting::vote::SignedVoteCommitment,
    bundle_index: u32,
    vc_tree_position: u64,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_COMMITTED_VOTE_RECORD)?;
    let result = make_jni_vote_commit_result(env, commit, bundle_index)?;
    let result = unsafe { JObject::from_raw(result) };
    let record = env.new_object(
        &class,
        JNI_COMMITTED_VOTE_RECORD_CTOR_SIG,
        &[
            JValue::Object(&result),
            JValue::Long(u64_to_jlong(vc_tree_position, "vc_tree_position")?),
        ],
    )?;
    Ok(record.into_raw())
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
                JValue::Int(u32_to_jint(payload.bundle_index, "bundle_index")?),
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
    bundle: voting::vote::VoteRecoveryBundle,
    bundle_index: u32,
    vc_tree_position: u64,
) -> anyhow::Result<jobject> {
    let class = env.find_class(JNI_COMMITMENT_BUNDLE_RECORD)?;
    let commitment = make_jni_vote_commitment_result_payload(
        env,
        JniVoteCommitmentResultPayload::from_recovery_bundle(bundle, bundle_index),
    )?;
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
