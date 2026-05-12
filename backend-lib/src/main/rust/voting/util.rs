use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_warmProvingCachesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) {
    let res = catch_unwind(&mut env, |_env| {
        voting::warm_proving_caches();
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}
