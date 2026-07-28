use super::*;

// Must match VotingProofProgressCallback.onProgress(Double) in VotingRustBackend.kt.
const VOTING_PROOF_PROGRESS_CALLBACK_METHOD: &str = "onProgress";
const VOTING_PROOF_PROGRESS_CALLBACK_SIG: &str = "(D)V";

/// Bridges the Kotlin progress callback to `zcash_voting`'s reporter traits.
///
/// Only `ProgressReporter` is implemented here. `zcash_voting` supplies blanket
/// impls of `DelegationProgressReporter` and `VoteCommitStageReporter` for every
/// `ProgressReporter`, so implementing either by hand would collide with them.
/// The blanket impls forward the clamped proof-progress fraction and drop the
/// non-proof lifecycle stages, which is exactly this callback's contract.
struct JniProgressReporter {
    vm: JavaVM,
    callback: GlobalRef,
}

impl ProgressReporter for JniProgressReporter {
    fn on_progress(&self, progress: f64) {
        // zcash_voting calls this at coarse milestones outside the spawned Halo2
        // proving closure. Attach on each callback so the bridge remains correct
        // if future progress calls come from another native thread.
        match self.vm.attach_current_thread() {
            Ok(mut env) => {
                if let Err(e) = env.call_method(
                    self.callback.as_obj(),
                    VOTING_PROOF_PROGRESS_CALLBACK_METHOD,
                    VOTING_PROOF_PROGRESS_CALLBACK_SIG,
                    &[JValue::Double(progress)],
                ) {
                    let _ = env.exception_clear();
                    tracing::warn!("proof progress callback failed: {e}");
                }
            }
            Err(e) => tracing::warn!("attach_current_thread for progress callback failed: {e}"),
        }
    }
}

fn reporter_from_callback(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
) -> anyhow::Result<Option<JniProgressReporter>> {
    if callback.is_null() {
        Ok(None)
    } else {
        Ok(Some(JniProgressReporter {
            vm: env.get_java_vm()?,
            callback: env.new_global_ref(callback)?,
        }))
    }
}

/// Builds the delegation progress reporter `build_and_prove_delegation` requires.
///
/// See `vote_commit_stage_reporter_from_callback` for why the concrete type has
/// to be boxed as this trait rather than converted from a `dyn ProgressReporter`.
pub(super) fn delegation_progress_reporter_from_callback(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
) -> anyhow::Result<Box<dyn voting::DelegationProgressReporter>> {
    Ok(match reporter_from_callback(env, callback)? {
        Some(reporter) => Box::new(reporter),
        None => Box::new(NoopProgressReporter),
    })
}

/// Builds the cast-vote stage reporter `vote::commit` requires.
///
/// The concrete bridge type is boxed as `VoteCommitStageReporter` directly:
/// a `dyn ProgressReporter` satisfies that trait through the blanket impl, but
/// one trait object never coerces to another, so the choice of trait object has
/// to be made where the concrete type is still known.
pub(super) fn vote_commit_stage_reporter_from_callback(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
) -> anyhow::Result<Box<dyn VoteCommitStageReporter>> {
    Ok(match reporter_from_callback(env, callback)? {
        Some(reporter) => Box::new(reporter),
        None => Box::new(NoopProgressReporter),
    })
}
