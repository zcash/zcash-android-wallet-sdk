use super::*;

struct JniProgressReporter {
    vm: JavaVM,
    callback: GlobalRef,
}

impl ProofProgressReporter for JniProgressReporter {
    fn on_progress(&self, progress: f64) {
        match self.vm.attach_current_thread() {
            Ok(mut env) => {
                if let Err(e) = env.call_method(
                    self.callback.as_obj(),
                    "onProgress",
                    "(D)V",
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
