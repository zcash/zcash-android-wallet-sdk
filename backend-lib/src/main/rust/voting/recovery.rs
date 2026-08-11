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
        // zcash_voting 1.0.0 folded the old store-then-mark-submitted split into
        // one atomic hash+submitted recorder.
        db.record_vote_submission(&round_id, bundle_index, proposal_id, &tx_hash)
            .map_err(|e| anyhow!("record_vote_submission: {e}"))?;
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;

        // mark_vote_submitted now takes the tx_hash itself (it is the idempotent,
        // conflict-checked half of record_vote_submission); recover it from the
        // hash storeVoteTxHashNative already recorded for this vote.
        let tx_hash = db
            .get_vote_tx_hash(&round_id, bundle_index, proposal_id)
            .map_err(|e| anyhow!("get_vote_tx_hash: {e}"))?
            .ok_or_else(|| {
                anyhow!(
                    "no vote tx_hash recorded for round={round_id}, bundle={bundle_index}, proposal={proposal_id}; call store_vote_tx_hash first"
                )
            })?;
        db.mark_vote_submitted(&round_id, bundle_index, proposal_id, &tx_hash)
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
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let record = optional_recovery_lookup(
            db.get_commitment_bundle(
                &java_string_to_rust(env, &round_id)?,
                bundle_index,
                jint_to_u32(proposal_id, "proposal_id")?,
            ),
            "get_commitment_bundle",
        )?;
        match record {
            Some((commitment_bundle_json, vc_tree_position)) => {
                // zcash_voting 1.0.0 persists this JSON in its own VoteRecoveryBundle
                // format (crate::vote::parse_recovery), not this SDK's old hand-rolled
                // hex-string format.
                let bundle = voting::vote::parse_recovery(&commitment_bundle_json)
                    .map_err(|e| anyhow!("parse_recovery: {}", e))?;
                make_jni_commitment_bundle_record(env, bundle, bundle_index, vc_tree_position)
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
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_recordVcPositionNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    proposal_id: jint,
    vc_tree_position: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        let vc_tree_position = jlong_to_u64(vc_tree_position, "vc_tree_position")?;

        let committed =
            voting::vote::CommittedVote::recover(&db, &round_id, bundle_index, proposal_id)
                .map_err(|e| anyhow!("CommittedVote::recover: {}", e))?;
        committed
            .record_vc_position(&db, vc_tree_position)
            .map_err(|e| anyhow!("record_vc_position: {}", e))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_recoverCommittedVoteNative<
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;

        let committed =
            voting::vote::CommittedVote::recover(&db, &round_id, bundle_index, proposal_id)
                .map_err(|e| anyhow!("CommittedVote::recover: {}", e))?;
        let signed = committed
            .signed_commitment(&db)
            .map_err(|e| anyhow!("signed_commitment: {}", e))?;
        let recoverable = voting::recovery::recoverable_commitment_bundle(
            &db,
            &round_id,
            bundle_index,
            proposal_id,
        )
        .map_err(|e| anyhow!("recoverable_commitment_bundle: {}", e))?
        .ok_or_else(|| {
            anyhow!(
                "no recoverable vote commitment tree position for round={round_id}, bundle={bundle_index}, proposal={proposal_id}"
            )
        })?;

        make_jni_committed_vote_record(env, signed, bundle_index, recoverable.vc_tree_position)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        let share_index =
            require_share_index(jint_to_u32(share_index, "share_index")?, "share_index")?;
        let sent_to_urls = java_string_array(env, &sent_to_urls, "sentToUrls")?;
        let submit_at = jlong_to_u64(submit_at, "submit_at")?;

        // share::record derives and persists the authoritative nullifier from the
        // vote's own recovery state; the caller-supplied nullifier here is only
        // shape-validated (when present) and is never itself stored.
        let nullifier = java_bytes(env, &nullifier, "nullifier")?;
        if !nullifier.is_empty() {
            require_len(nullifier, "nullifier", SHARE_NULLIFIER_BYTES)?;
        }

        voting::share::record(
            &db,
            &round_id,
            bundle_index,
            proposal_id,
            share_index,
            &sent_to_urls,
            submit_at,
        )
        .map_err(|e| anyhow!("share::record: {e}"))?;
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

        let recovery = voting::vote::VoteRecoveryBundle {
            vote_round_id: round_id.clone(),
            bundle_index,
            proposal_id,
            vote_decision: choice,
            anchor_height: 100,
            vc_tree_position: 456,
            single_share: false,
            num_options: 3,
            van_nullifier: [0x31; PROTOCOL_FIELD_BYTES],
            vote_authority_note_new: [0x32; PROTOCOL_FIELD_BYTES],
            vote_commitment: [0x01; PROTOCOL_FIELD_BYTES],
            proof: vec![0x34; 8],
            shares_hash: [0x35; PROTOCOL_FIELD_BYTES],
            r_vpk: [0x36; PROTOCOL_FIELD_BYTES],
            alpha_v: [0x37; PROTOCOL_FIELD_BYTES],
            vote_auth_sig: [0x38; SPEND_AUTH_SIG_BYTES],
            encrypted_shares: (0..VOTE_SHARE_COUNT)
                .map(|share_index| voting::types::EncryptedShare {
                    c1: vec![0x21; PROTOCOL_FIELD_BYTES],
                    c2: vec![0x22; PROTOCOL_FIELD_BYTES],
                    share_index: share_index as u32,
                    plaintext_value: 5,
                    randomness: vec![0x23; PROTOCOL_FIELD_BYTES],
                })
                .collect(),
            share_blinds: vec![[0x02; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
            share_comms: vec![[0x51; PROTOCOL_FIELD_BYTES]; VOTE_SHARE_COUNT],
        };
        let recovery_json = voting::vote::serialize_recovery(&recovery)
            .map_err(|e| anyhow!("serialize vote recovery fixture: {e}"))?;
        conn.execute(
            "UPDATE votes
             SET commitment_bundle_json = :recovery_json,
                 vc_tree_position = :vc_tree_position
             WHERE round_id = :round_id
               AND wallet_id = :wallet_id
               AND bundle_index = :bundle_index
               AND proposal_id = :proposal_id",
            rusqlite::named_params! {
                ":recovery_json": recovery_json,
                ":vc_tree_position": 456_i64,
                ":round_id": round_id,
                ":wallet_id": wallet_id,
                ":bundle_index": i64::from(bundle_index),
                ":proposal_id": i64::from(proposal_id),
            },
        )
        .map_err(|e| anyhow!("store vote recovery fixture: {e}"))?;
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
}
