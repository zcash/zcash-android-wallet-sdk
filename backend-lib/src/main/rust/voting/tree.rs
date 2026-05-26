use super::db::*;
use super::helpers::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_syncVoteTreeNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    node_url: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let node_url = java_string_to_rust(env, &node_url)?;
        let height = db
            .tree_sync
            .sync(&db, &round_id, &node_url)
            .map_err(|e| anyhow!("sync_vote_tree: {}", e))?;
        u64_to_jlong(u64::from(height), "synced_height")
    });
    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_resetTreeClientNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.tree_sync
            .reset(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("reset_tree_client: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeVanPositionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    position: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.store_van_position(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jlong_to_u32(position, "position")?,
        )
        .map_err(|e| anyhow!("store_van_position: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateVanWitnessNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    anchor_height: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let witness = db
            .tree_sync
            .generate_van_witness(
                &db,
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                jlong_to_u32(anchor_height, "anchor_height")?,
            )
            .map_err(|e| anyhow!("generate_van_witness: {}", e))?;
        make_jni_van_witness(env, witness)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}
