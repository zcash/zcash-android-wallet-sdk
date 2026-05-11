use super::db::*;
use super::helpers::*;
use super::*;

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeDelegationTxHashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    tx_hash: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.store_delegation_tx_hash(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            &java_string_to_rust(env, &tx_hash)?,
        )
        .map_err(|e| anyhow!("store_delegation_tx_hash: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getDelegationTxHashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let tx_hash = optional_recovery_lookup(
            db.get_delegation_tx_hash(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
            ),
            "get_delegation_tx_hash",
        )?;
        match tx_hash {
            Some(value) => Ok(env.new_string(value)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeVoteTxHashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    tx_hash: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.store_vote_tx_hash(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            &java_string_to_rust(env, &tx_hash)?,
        )
        .map_err(|e| anyhow!("store_vote_tx_hash: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_markVoteSubmittedNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.mark_vote_submitted(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
        )
        .map_err(|e| anyhow!("mark_vote_submitted: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getVoteTxHashNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let tx_hash = optional_recovery_lookup(
            db.get_vote_tx_hash(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                jint_to_u32(proposal_id, "proposal_id")?,
            ),
            "get_vote_tx_hash",
        )?;
        match tx_hash {
            Some(value) => Ok(env.new_string(value)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

fn optional_recovery_lookup<T, E>(
    result: Result<Option<T>, E>,
    label: &str,
) -> anyhow::Result<Option<T>>
where
    E: std::fmt::Display,
{
    match result {
        Ok(value) => Ok(value),
        Err(error) if is_query_returned_no_rows(&error) => Ok(None),
        Err(error) => Err(anyhow!("{label}: {error}")),
    }
}

fn is_query_returned_no_rows(error: &impl std::fmt::Display) -> bool {
    error
        .to_string()
        .to_ascii_lowercase()
        .contains("query returned no rows")
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeCommitmentBundleNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    commitment: JObject<'local>,
    vc_tree_position: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let commitment = java_vote_commitment_bundle(env, &commitment)?;
        let commitment = StoredVoteCommitmentBundle::try_from(commitment)?.to_storage_json()?;
        db.store_commitment_bundle(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            &commitment,
            jlong_to_u64(vc_tree_position, "vc_tree_position")?,
        )
        .map_err(|e| anyhow!("store_commitment_bundle: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getCommitmentBundleNative<
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
        let record = optional_recovery_lookup(
            db.get_commitment_bundle(
                &java_string_to_rust(env, &round_id)?,
                jint_to_u32(bundle_index, "bundle_index")?,
                jint_to_u32(proposal_id, "proposal_id")?,
            ),
            "get_commitment_bundle",
        )?;
        match record {
            Some((commitment, vc_tree_position)) => {
                let commitment = StoredVoteCommitmentBundle::from_storage_json(&commitment)?;
                make_jni_commitment_bundle_record(env, commitment, vc_tree_position)
            }
            None => Ok(JObject::null().into_raw()),
        }
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_clearRecoveryStateNative<
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
        db.clear_recovery_state(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("clear_recovery_state: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_recordShareDelegationNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
    sent_to_urls: JObjectArray<'local>,
    nullifier: JByteArray<'local>,
    submit_at: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let sent_to_urls = java_string_array(env, &sent_to_urls, "sentToUrls")?;
        db.record_share_delegation(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            jint_to_u32(share_index, "share_index")?,
            &sent_to_urls,
            &java_bytes_exact(env, &nullifier, "nullifier", SHARE_NULLIFIER_BYTES)?,
            jlong_to_u64(submit_at, "submit_at")?,
        )
        .map_err(|e| anyhow!("record_share_delegation: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getShareDelegationsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let records = db
            .get_share_delegations(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_share_delegations: {e}"))?;
        make_jni_share_delegation_record_array(env, records)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getUnconfirmedDelegationsNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let records = db
            .get_unconfirmed_delegations(&java_string_to_rust(env, &round_id)?)
            .map_err(|e| anyhow!("get_unconfirmed_delegations: {e}"))?;
        make_jni_share_delegation_record_array(env, records)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_markShareConfirmedNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.mark_share_confirmed(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            jint_to_u32(share_index, "share_index")?,
        )
        .map_err(|e| anyhow!("mark_share_confirmed: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_addSentServersNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    share_index: jint,
    new_urls: JObjectArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let new_urls = java_string_array(env, &new_urls, "newUrls")?;
        db.add_sent_servers(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            jint_to_u32(share_index, "share_index")?,
            &new_urls,
        )
        .map_err(|e| anyhow!("add_sent_servers: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

fn java_string_array(
    env: &mut JNIEnv<'_>,
    array: &JObjectArray<'_>,
    field: &str,
) -> anyhow::Result<Vec<String>> {
    let count = env.get_array_length(array)?;
    (0..count)
        .map(|index| {
            let value = env.get_object_array_element(array, index)?;
            let value = JString::from(value);
            java_string_to_rust(env, &value).map_err(|e| anyhow!("{field}[{index}]: {e}"))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn optional_recovery_lookup_maps_missing_rows_to_none() {
        let result: anyhow::Result<Option<String>> =
            optional_recovery_lookup(Err("Query returned no rows"), "get_vote_tx_hash");

        assert!(result.unwrap().is_none());
    }

    #[test]
    fn optional_recovery_lookup_keeps_unexpected_errors_fatal() {
        let result: anyhow::Result<Option<String>> =
            optional_recovery_lookup(Err("database is locked"), "get_vote_tx_hash");

        let error = result.unwrap_err().to_string();
        assert!(error.contains("get_vote_tx_hash"));
        assert!(error.contains("database is locked"));
    }
}
