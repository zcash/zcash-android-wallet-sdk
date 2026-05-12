use super::helpers::*;
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

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractOrchardFvkFromUfvkNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    ufvk: JString<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let network = network_from_id(network_id)?;
        let bytes = orchard_fvk_bytes(&java_string_to_rust(env, &ufvk)?, network)?;
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_extractNcRootNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tree_state_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        use prost::Message;
        use zcash_client_backend::proto::service::TreeState;

        let bytes = java_bytes(env, &tree_state_bytes, "treeStateBytes")?;
        let tree_state =
            TreeState::decode(bytes.as_slice()).map_err(|e| anyhow!("decode TreeState: {}", e))?;
        let orchard_ct = tree_state
            .orchard_tree()
            .map_err(|e| anyhow!("parse orchard_tree: {}", e))?;
        let nc_root = orchard_ct.root().to_bytes();
        Ok(env.byte_array_from_slice(&nc_root)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_verifyWitnessNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    witness: JObject<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let witness = java_witness_data(env, &witness)?;
        let valid = voting::witness::verify_witness(&witness)
            .map_err(|e| anyhow!("verify_witness: {}", e))?;
        Ok(if valid { 1 } else { 0 })
    });
    unwrap_exc_or(&mut env, res, -1)
}
