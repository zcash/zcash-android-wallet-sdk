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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let tx_hash = java_string_to_rust(env, &tx_hash)?;
        db.store_delegation_tx_hash(&round_id, bundle_index, &tx_hash)
            .map_err(|e| anyhow!("store_delegation_tx_hash: {e}"))?;
        let stored = optional_recovery_lookup(
            db.get_delegation_tx_hash(&round_id, bundle_index),
            "get_delegation_tx_hash",
        )?;
        require_stored_value(stored.as_deref(), &tx_hash, "delegation tx hash")?;
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        let tx_hash = java_string_to_rust(env, &tx_hash)?;
        db.store_vote_tx_hash(&round_id, bundle_index, proposal_id, &tx_hash)
            .map_err(|e| anyhow!("store_vote_tx_hash: {e}"))?;
        let stored = optional_recovery_lookup(
            db.get_vote_tx_hash(&round_id, bundle_index, proposal_id),
            "get_vote_tx_hash",
        )?;
        require_stored_value(stored.as_deref(), &tx_hash, "vote tx hash")?;
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

fn require_stored_value(stored: Option<&str>, expected: &str, label: &str) -> anyhow::Result<()> {
    match stored {
        Some(value) if value == expected => Ok(()),
        Some(_) => Err(anyhow!(
            "{label} store verification returned a different value"
        )),
        None => Err(anyhow!("{label} store did not update any recovery row")),
    }
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        let vc_tree_position = jlong_to_u64(vc_tree_position, "vc_tree_position")?;
        let commitment = java_vote_commitment_bundle(env, &commitment)?;
        require_commitment_matches_key(&commitment, &round_id, bundle_index, proposal_id)?;
        let commitment = StoredVoteCommitmentBundle::try_from(commitment)?.to_storage_json()?;
        db.store_commitment_bundle(
            &round_id,
            bundle_index,
            proposal_id,
            &commitment,
            vc_tree_position,
        )
        .map_err(|e| anyhow!("store_commitment_bundle: {e}"))?;
        let stored = optional_recovery_lookup(
            db.get_commitment_bundle(&round_id, bundle_index, proposal_id),
            "get_commitment_bundle",
        )?;
        require_stored_commitment(stored, &commitment, vc_tree_position)?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Requires the commitment payload identity to match the recovery storage key.
/// Rejects mismatched round or proposal data before it can be persisted.
fn require_commitment_matches_key(
    commitment: &JavaVoteCommitmentBundle,
    round_id: &str,
    bundle_index: u32,
    proposal_id: u32,
) -> anyhow::Result<()> {
    if commitment.bundle.vote_round_id != round_id {
        return Err(anyhow!(
            "commitment voteRoundId {} does not match roundId {round_id}",
            commitment.bundle.vote_round_id
        ));
    }
    if commitment.bundle.proposal_id != proposal_id {
        return Err(anyhow!(
            "commitment proposalId {} does not match proposalId {proposal_id}",
            commitment.bundle.proposal_id
        ));
    }
    if commitment.bundle_index != bundle_index {
        return Err(anyhow!(
            "commitment bundleIndex {} does not match bundleIndex {bundle_index}",
            commitment.bundle_index
        ));
    }
    Ok(())
}

/// Requires a just-stored commitment lookup to return the exact JSON payload
/// and VC tree position. Fails closed when the row is missing or was overwritten.
fn require_stored_commitment(
    stored: Option<(String, u64)>,
    expected_json: &str,
    expected_position: u64,
) -> anyhow::Result<()> {
    match stored {
        Some((json, position)) if json == expected_json && position == expected_position => Ok(()),
        Some(_) => Err(anyhow!(
            "commitment bundle store verification returned a different value"
        )),
        None => Err(anyhow!(
            "commitment bundle store did not update any recovery row"
        )),
    }
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
                make_jni_commitment_bundle_record(
                    env,
                    commitment,
                    jint_to_u32(bundle_index, "bundle_index")?,
                    vc_tree_position,
                )
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
            require_share_index(jint_to_u32(share_index, "share_index")?, "share_index")?,
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
            require_share_index(jint_to_u32(share_index, "share_index")?, "share_index")?,
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
            require_share_index(jint_to_u32(share_index, "share_index")?, "share_index")?,
            &new_urls,
        )
        .map_err(|e| anyhow!("add_sent_servers: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeVoteFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    choice: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        let choice = jint_to_u32(choice, "choice")?;
        let conn = db.conn();
        let wallet_id = db.wallet_id();
        voting::storage::queries::store_vote(
            &conn,
            &round_id,
            &wallet_id,
            bundle_index,
            proposal_id,
            choice,
            &[0xAA; PROTOCOL_FIELD_BYTES],
        )
        .map_err(|e| anyhow!("store_vote fixture: {e}"))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
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

    #[test]
    fn stored_value_verification_rejects_missing_or_changed_rows() {
        require_stored_value(Some("tx-1"), "tx-1", "vote tx hash").unwrap();

        assert!(
            require_stored_value(None, "tx-1", "vote tx hash")
                .unwrap_err()
                .to_string()
                .contains("did not update")
        );
        assert!(
            require_stored_value(Some("tx-2"), "tx-1", "vote tx hash")
                .unwrap_err()
                .to_string()
                .contains("different value")
        );
    }

    #[test]
    fn commitment_store_key_must_match_payload() {
        let commitment = commitment_bundle("round-1", 7);

        require_commitment_matches_key(&commitment, "round-1", 1, 7).unwrap();
        assert!(
            require_commitment_matches_key(&commitment, "round-2", 1, 7)
                .unwrap_err()
                .to_string()
                .contains("voteRoundId")
        );
        assert!(
            require_commitment_matches_key(&commitment, "round-1", 1, 8)
                .unwrap_err()
                .to_string()
                .contains("proposalId")
        );
        assert!(
            require_commitment_matches_key(&commitment, "round-1", 2, 7)
                .unwrap_err()
                .to_string()
                .contains("bundleIndex")
        );
    }

    #[test]
    fn stored_commitment_verification_rejects_missing_or_changed_rows() {
        require_stored_commitment(Some(("json".to_string(), 12)), "json", 12).unwrap();

        assert!(
            require_stored_commitment(None, "json", 12)
                .unwrap_err()
                .to_string()
                .contains("did not update")
        );
        assert!(
            require_stored_commitment(Some(("other".to_string(), 12)), "json", 12)
                .unwrap_err()
                .to_string()
                .contains("different value")
        );
        assert!(
            require_stored_commitment(Some(("json".to_string(), 13)), "json", 12)
                .unwrap_err()
                .to_string()
                .contains("different value")
        );
    }

    fn commitment_bundle(round_id: &str, proposal_id: u32) -> JavaVoteCommitmentBundle {
        JavaVoteCommitmentBundle {
            bundle_index: 1,
            enc_shares: vec![],
            bundle: VoteCommitmentBundle {
                van_nullifier: vec![1; PROTOCOL_FIELD_BYTES],
                vote_authority_note_new: vec![2; PROTOCOL_FIELD_BYTES],
                vote_commitment: vec![3; PROTOCOL_FIELD_BYTES],
                proposal_id,
                proof: vec![4; PROTOCOL_FIELD_BYTES],
                enc_shares: vec![],
                anchor_height: 5,
                vote_round_id: round_id.to_string(),
                shares_hash: vec![6; PROTOCOL_FIELD_BYTES],
                share_blinds: vec![vec![7; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
                share_comms: vec![vec![8; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
                r_vpk_bytes: vec![9; PROTOCOL_FIELD_BYTES],
                alpha_v: vec![10; PROTOCOL_FIELD_BYTES],
            },
        }
    }
}
