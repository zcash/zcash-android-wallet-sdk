//! Vote marshalling: commitment bundles, the one-shot commit result, and the
//! recorded vote and committed-vote models.

use super::super::helpers::*;
use super::super::*;

// zcash_voting 1.0.0 (merged-library patch) no longer re-exports `VanWitness` from
// `tree_sync` (it's defined in, and now only publicly reachable via, `vote`); same fields.
pub(crate) struct JavaVoteCommitmentBundle {
    pub(crate) enc_shares: Vec<WireEncryptedShare>,
    pub(crate) bundle: VoteCommitmentBundle,
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

pub(crate) struct JniVoteRecordPayload {
    proposal_id: jint,
    bundle_index: jint,
    choice: jint,
    submitted: bool,
}

pub(crate) struct JniVoteCommitmentResultPayload {
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

impl JniVoteCommitmentResultPayload {
    // zcash_voting 1.0.0 persists commitment recovery state as its own
    // VoteRecoveryBundle JSON (crate::vote::parse_recovery), not the hand-rolled
    // hex-string JSON this SDK used to own. Recovery fields are already
    // typed byte arrays, so no hex encode/decode round trip is needed anymore.
    pub(crate) fn from_recovery_bundle(
        bundle: voting::vote::VoteRecoveryBundle,
        bundle_index: u32,
    ) -> Self {
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

pub(crate) fn java_vote_commitment_bundle(
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

pub(crate) fn make_jni_vote_records(
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

/// Builds the one-shot `vote::commit` result: the signed commitment bundle
/// plus the vote_auth_sig and share_payloads it produces.
pub(crate) fn make_jni_vote_commit_result<'local>(
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
pub(crate) fn make_jni_committed_vote_record<'local>(
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

pub(crate) fn make_jni_vote_commitment_result_payload<'local>(
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

pub(crate) fn make_jni_commitment_bundle_record<'local>(
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
