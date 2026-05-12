use super::*;

// Must match VotingProofProgressCallback.onProgress(Double) in VotingRustBackend.kt.
const VOTING_PROOF_PROGRESS_CALLBACK_METHOD: &str = "onProgress";
const VOTING_PROOF_PROGRESS_CALLBACK_SIG: &str = "(D)V";

struct JniProgressReporter {
    vm: JavaVM,
    callback: GlobalRef,
}

impl ProofProgressReporter for JniProgressReporter {
    fn on_progress(&self, progress: f64) {
        // zcash_voting 0.5.9 calls this at coarse milestones outside the spawned
        // Halo2 proving closure. Attach on each callback so the bridge remains
        // correct if future progress calls come from another native thread.
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

pub(super) fn progress_reporter_from_callback(
    env: &mut JNIEnv<'_>,
    callback: &JObject<'_>,
) -> anyhow::Result<Box<dyn ProofProgressReporter>> {
    if callback.is_null() {
        Ok(Box::new(NoopProgressReporter))
    } else {
        Ok(Box::new(JniProgressReporter {
            vm: env.get_java_vm()?,
            callback: env.new_global_ref(callback)?,
        }))
    }
}
