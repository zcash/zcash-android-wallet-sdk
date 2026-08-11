use super::db::*;
use super::helpers::*;
use super::progress::*;
use super::*;

/// Builds, signs, persists, and returns one cast-vote commitment.
///
/// This single entry point replaces the former `buildVoteCommitmentNative` /
/// `signCastVoteNative` / `buildSharePayloadsNative` sequence. `zcash_voting`
/// absorbed all three steps into `vote::commit` and made the intermediate steps
/// crate-private, so the sequence can no longer be driven from outside the
/// crate. Nothing is lost by the collapse: the returned encrypted shares are the
/// ciphertexts the vote proof commits to, and the call is idempotent, so a
/// repeated call for the same `(roundId, bundleIndex, proposalId)` returns the
/// persisted recovery bundle instead of rebuilding the proof.
///
/// `hotkeyStoredSecret` is the app-owned voting hotkey secret previously
/// returned as `JniVotingHotkey.storedSecret`, not wallet seed material; it
/// replaces the `hotkeySeed` parameter of the superseded entry points. The
/// network the vote is signed for is taken from the hotkey, so `networkId` must
/// match the network the round was initialized with.
///
/// Returns a `JniVoteCommitResult`, or throws a RuntimeException and returns
/// null on failure.
#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_commitVoteNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    hotkey_stored_secret: JByteArray<'local>,
    network_id: jint,
    proposal_id: jint,
    choice: jint,
    num_options: jint,
    vc_tree_position: jlong,
    witness: JObject<'local>,
    single_share: jboolean,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let network = voting_network_from_id(network_id)?;
        let hotkey = java_voting_hotkey(env, &hotkey_stored_secret, network)?;
        let witness = java_van_witness(env, &witness)?;
        require_delegation_ready_for_vote(&db, &round_id, bundle_index, &witness)?;

        let draft = voting::vote::DraftVote {
            proposal_id: jint_to_u32(proposal_id, "proposal_id")?,
            choice: jint_to_u32(choice, "choice")?,
            num_options: jint_to_u32(num_options, "num_options")?,
            vc_tree_position: jlong_to_u64(vc_tree_position, "vc_tree_position")?,
            single_share: single_share == JNI_TRUE,
        };

        let stages = vote_commit_stage_reporter_from_callback(env, &progress_callback)?;
        let commit = voting::vote::commit(
            &db,
            &round_id,
            bundle_index,
            &draft,
            &witness,
            voting::vote::VoteSigner::hotkey(&hotkey),
            stages.as_ref(),
        )
        .map_err(|e| anyhow!("vote commit: {}", e))?;

        make_jni_vote_commit_result(env, commit, bundle_index)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Reconstructs the chain-ready cast-vote fields for a committed but unconfirmed vote.
///
/// This is the entry point for resending a cast-vote transaction after a restart.
/// It deliberately carries no helper-share payloads: before the cast-vote is
/// confirmed the vote commitment tree position is not yet known, so any payloads
/// built now would be stale. Recover those through `getCommitmentBundleNative`
/// once the position has been recorded.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_voteSubmissionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let submission = voting::vote::submission(
            &db,
            &round_id,
            bundle_index,
            jint_to_u32(proposal_id, "proposal_id")?,
        )
        .map_err(|e| anyhow!("vote submission: {}", e))?;

        make_jni_vote_submission(env, submission, bundle_index)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

fn require_delegation_ready_for_vote(
    db: &VotingDbHandle,
    round_id: &str,
    bundle_index: u32,
    witness: &voting::vote::VanWitness,
) -> anyhow::Result<()> {
    // Callers hold VotingDbHandle::access_lock while this read-check sequence
    // runs. Managed handles for the same DB path and wallet share that lock, so
    // SDK-exposed writers cannot change delegation/VAN state between these
    // reads and the subsequent vote commitment build.
    let conn = db.conn();
    let wallet_id = db.wallet_id();
    voting::storage::queries::load_delegation_submission_data(
        &conn,
        round_id,
        &wallet_id,
        bundle_index,
    )
    .map_err(|e| anyhow!("delegation proof is not ready for vote commitment: {e}"))?;

    let stored_position =
        voting::storage::queries::load_van_position(&conn, round_id, &wallet_id, bundle_index)
            .map_err(|e| anyhow!("VAN position is not ready for vote commitment: {e}"))?;
    if stored_position != witness.position {
        return Err(anyhow!(
            "VAN witness position {} does not match stored VAN position {} for round={} bundle={}",
            witness.position,
            stored_position,
            round_id,
            bundle_index
        ));
    }

    Ok(())
}
