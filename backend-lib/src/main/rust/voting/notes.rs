use super::db::*;
use super::helpers::*;
use super::json::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeBundleSetupNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    notes_json: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let json_notes: Vec<JsonNoteInfo> = json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;
        let (count, weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        make_jni_bundle_setup_result(env, count, weight, &bundle_weights)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setupBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    notes_json: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let json_notes: Vec<JsonNoteInfo> = json_from_jstring(env, &notes_json, "notesJson")?;
        let notes: Vec<NoteInfo> = json_notes
            .into_iter()
            .map(NoteInfo::try_from)
            .collect::<anyhow::Result<_>>()?;
        let (expected_count, expected_weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        let (count, weight) = db
            .setup_bundles(&java_string_to_rust(env, &round_id)?, &notes)
            .map_err(|e| anyhow!("setup_bundles: {}", e))?;
        if count != expected_count || weight != expected_weight {
            return Err(anyhow!(
                "setup_bundles result mismatch: db=({}, {}) chunk=({}, {})",
                count,
                weight,
                expected_count,
                expected_weight
            ));
        }
        make_jni_bundle_setup_result(env, count, weight, &bundle_weights)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}
