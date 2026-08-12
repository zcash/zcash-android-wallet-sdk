use super::db::*;
use super::helpers::*;
use super::progress::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildSharePayloadsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    commitment: JObject<'local>,
    vote_decision: jint,
    num_options: jint,
    vc_tree_position: jlong,
    single_share_mode: jboolean,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let commitment = java_vote_commitment_bundle(env, &commitment)?;
        // voting::vote_commitment::build_share_payloads remains public in
        // zcash_voting 1.0.0 with this same signature; VotingDb::build_share_payloads
        // is a thin passthrough to it, so this call needs no db_handle.
        let payloads = voting::vote_commitment::build_share_payloads(
            &commitment.enc_shares,
            &commitment.bundle,
            jint_to_u32(vote_decision, "vote_decision")?,
            jint_to_u32(num_options, "num_options")?,
            jlong_to_u64(vc_tree_position, "vc_tree_position")?,
            single_share_mode == JNI_TRUE,
        )
        .map_err(|e| anyhow!("build_share_payloads: {}", e))?;
        make_jni_share_payload_array(env, payloads)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_buildVoteCommitmentNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    hotkey_secret: JByteArray<'local>,
    proposal_id: jint,
    choice: jint,
    num_options: jint,
    witness: JObject<'local>,
    single_share: jboolean,
    progress_callback: JObject<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let hotkey_secret = java_secret_bytes_at_least(
            env,
            &hotkey_secret,
            "hotkeySecret",
            HOTKEY_STORED_SECRET_BYTES,
        )?;
        let witness = java_van_witness(env, &witness)?;
        require_delegation_ready_for_vote(&db, &round_id, bundle_index, &witness)?;

        let hotkey = voting::types::VotingHotkey::from_stored_secret(
            hotkey_secret.expose_secret(),
            db.network,
        )
        .map_err(|e| anyhow!("VotingHotkey::from_stored_secret: {}", e))?;
        let draft = voting::vote::DraftVote {
            proposal_id: jint_to_u32(proposal_id, "proposal_id")?,
            choice: jint_to_u32(choice, "choice")?,
            num_options: jint_to_u32(num_options, "num_options")?,
            // The commit call below always builds share payloads against the
            // vote's freshly-signed commitment; the real vote-commitment-tree
            // position is only known once the cast-vote TX confirms on chain,
            // and is recorded later via recordVcPositionNative.
            vc_tree_position: 0,
            single_share: single_share == JNI_TRUE,
        };
        let signer = voting::vote::VoteSigner::hotkey(&hotkey);
        let reporter = progress_reporter_from_callback(env, &progress_callback)?;
        let stages = VoteCommitStageProgressBridge(reporter.as_ref());

        let committed = voting::vote::CommittedVote::commit(
            &db,
            &round_id,
            bundle_index,
            &draft,
            &witness,
            signer,
            &stages,
        )
        .map_err(|e| anyhow!("vote::commit: {}", e))?;
        let signed = committed
            .signed_commitment(&db)
            .map_err(|e| anyhow!("signed_commitment: {}", e))?;

        make_jni_vote_commit_result(env, signed, bundle_index)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

/// Forwards `vote::commit`'s coarse-grained proof progress stage to the
/// existing `ProgressReporter` callback bridge (`progress::JniProgressReporter`),
/// mirroring the blanket `VoteCommitStageReporter for ProgressReporter` impl
/// that only stable trait-object coercion cannot reach through a `&dyn`
/// reference of a different trait.
struct VoteCommitStageProgressBridge<'a>(&'a dyn ProgressReporter);

impl voting::types::VoteCommitStageReporter for VoteCommitStageProgressBridge<'_> {
    fn on_stage(&self, stage: voting::vote::VoteCommitStage) {
        if let voting::vote::VoteCommitStage::ProofProgress { progress, .. } = stage {
            self.0.on_progress(progress);
        }
    }
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
