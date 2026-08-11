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

/// Records the transaction that carried a cast vote.
///
/// This is the only writer of a vote's transaction hash. It replaces the former
/// `storeVoteTxHashNative`, which wrote the same column unconditionally: since
/// `zcash_voting` dropped the standalone submitted flag, recording the
/// transaction *is* what marks a vote submitted, so the two entry points had
/// become the same operation with different conflict semantics. The surviving
/// one is conflict-checked, because overwriting the hash of an already-submitted
/// cast vote would lose the wallet's ability to keep polling that transaction.
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
    tx_hash: JString<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        db.mark_vote_submitted(
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            &java_string_to_rust(env, &tx_hash)?,
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

/// Records the on-chain vote commitment tree position of a confirmed vote.
///
/// This replaces the former `storeCommitmentBundleNative`, which also took the
/// commitment bundle itself. `zcash_voting` now owns that recovery material: it
/// is written when the vote is committed and has no public writer, so the caller
/// has nothing left to supply beyond the confirmed tree position.
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
        voting::vote::record_vc_position(
            &db,
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            jlong_to_u64(vc_tree_position, "vc_tree_position")?,
        )
        .map_err(|e| anyhow!("record_vc_position: {e}"))?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Reconstructs a committed vote and its recorded tree position after a restart.
///
/// Returns null until the vote reaches [`VotePhase::Confirmed`], which is the
/// phase in which its commitment tree position has been recorded, so callers
/// cannot resubmit helper-share payloads built on a stale position. A vote that
/// was never stored is likewise reported as null.
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
        let round_id = java_string_to_rust(env, &round_id)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let proposal_id = jint_to_u32(proposal_id, "proposal_id")?;
        // `vote::commit` persists the recovery bundle and `vote::record_vc_position`
        // persists the tree position, so between those two calls the bundle is
        // stored without a position -- a state in which `get_commitment_bundle`
        // deliberately fails rather than assume position 0. The canonical phase
        // recognizes that in-progress state directly, so the caller sees "not
        // ready yet" instead of an exception.
        let phase = match db.vote_phase(&round_id, bundle_index, proposal_id) {
            Ok(phase) => phase,
            // The sole invalid input `vote_phase` reports is a vote that has
            // never been stored, which for a recovery read means there is
            // nothing to reconstruct.
            Err(VotingError::InvalidInput { .. }) => return Ok(JObject::null().into_raw()),
            Err(e) => return Err(anyhow!("vote_phase: {e}")),
        };
        if phase != VotePhase::Confirmed {
            return Ok(JObject::null().into_raw());
        }

        let record = optional_recovery_lookup(
            db.get_commitment_bundle(&round_id, bundle_index, proposal_id),
            "get_commitment_bundle",
        )?;
        match record {
            Some((_, vc_tree_position)) => {
                // The stored recovery JSON is library-owned and opaque, so the
                // typed commitment comes back through the crate's own reader
                // rather than being parsed here.
                let commit =
                    voting::vote::recover_commit(&db, &round_id, bundle_index, proposal_id)
                        .map_err(|e| anyhow!("recover_commit: {e}"))?;
                make_jni_commitment_bundle_record(env, commit, bundle_index, vc_tree_position)
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
    submit_at: jlong,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let sent_to_urls = java_string_array(env, &sent_to_urls, "sentToUrls")?;
        // The nullifier is derived from the vote's persisted recovery state
        // rather than supplied by the caller, so a caller cannot record a
        // nullifier that disagrees with the share it belongs to. That is why the
        // former `nullifier` parameter is gone.
        voting::share::record(
            &db,
            &java_string_to_rust(env, &round_id)?,
            jint_to_u32(bundle_index, "bundle_index")?,
            jint_to_u32(proposal_id, "proposal_id")?,
            require_share_index(jint_to_u32(share_index, "share_index")?, "share_index")?,
            &sent_to_urls,
            jlong_to_u64(submit_at, "submit_at")?,
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
        store_vote_recovery_bundle_fixture(
            &conn,
            &round_id,
            &wallet_id,
            bundle_index,
            proposal_id,
            choice,
        )?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Persists the recovery bundle a real `vote::commit` would have written.
///
/// `share::record` derives the share nullifier from the vote's own recovery
/// bundle instead of taking one from the caller, so a vote row on its own is no
/// longer enough to record a helper share. Producing a genuine bundle means
/// running the ZKP #2 prover, which an instrumented test cannot afford, so this
/// stages the same shape `zcash_voting`'s own suite stages: the library-owned
/// JSON written straight onto the vote row. Only `vote_commitment` and the
/// per-share blind actually feed the nullifier, and both are canonical Pallas
/// encodings here.
///
/// `vc_tree_position` deliberately stays NULL. That leaves the vote in
/// `VotePhase::Committed` rather than `Confirmed`, which is what keeps
/// `get_commitment_bundle` reporting "not ready yet" for a vote whose on-chain
/// position has not been recorded.
#[cfg(feature = "android-test-fixtures")]
fn store_vote_recovery_bundle_fixture(
    conn: &rusqlite::Connection,
    round_id: &str,
    wallet_id: &str,
    bundle_index: u32,
    proposal_id: u32,
    choice: u32,
) -> anyhow::Result<()> {
    let bundle = vote_recovery_bundle_fixture(round_id, bundle_index, proposal_id, choice);
    let json = voting::vote::serialize_recovery(&bundle)
        .map_err(|e| anyhow!("serialize_recovery fixture: {e}"))?;
    let rows = conn
        .execute(
            "UPDATE votes SET commitment_bundle_json = :json
             WHERE round_id = :round_id
               AND wallet_id = :wallet_id
               AND bundle_index = :bundle_index
               AND proposal_id = :proposal_id",
            rusqlite::named_params! {
                ":json": json,
                ":round_id": round_id,
                ":wallet_id": wallet_id,
                ":bundle_index": bundle_index as i64,
                ":proposal_id": proposal_id as i64,
            },
        )
        .map_err(|e| anyhow!("store vote recovery bundle fixture: {e}"))?;
    if rows != 1 {
        return Err(anyhow!(
            "store vote recovery bundle fixture updated {rows} rows, expected 1"
        ));
    }
    Ok(())
}

/// Builds a syntactically valid vote recovery bundle for the fixture above.
///
/// Every helper slot gets a share so that any `share_index` the suite exercises
/// resolves, and each carries a distinct blind so distinct slots yield distinct
/// nullifiers.
#[cfg(feature = "android-test-fixtures")]
fn vote_recovery_bundle_fixture(
    round_id: &str,
    bundle_index: u32,
    proposal_id: u32,
    choice: u32,
) -> voting::vote::VoteRecoveryBundle {
    use voting::types::EncryptedShare;

    // A one-byte little-endian value is always below the Pallas modulus, so
    // every field element here is a canonical encoding.
    fn field_bytes(value: u8) -> [u8; PROTOCOL_FIELD_BYTES] {
        let mut bytes = [0u8; PROTOCOL_FIELD_BYTES];
        bytes[0] = value;
        bytes
    }

    voting::vote::VoteRecoveryBundle {
        vote_round_id: round_id.to_string(),
        bundle_index,
        proposal_id,
        vote_decision: choice,
        anchor_height: 1,
        vc_tree_position: 0,
        single_share: false,
        num_options: voting::types::MAX_VOTE_OPTIONS,
        van_nullifier: field_bytes(0x10),
        vote_authority_note_new: field_bytes(0x11),
        vote_commitment: field_bytes(0x12),
        proof: vec![0x13; 96],
        shares_hash: field_bytes(0x14),
        r_vpk: field_bytes(0x15),
        alpha_v: field_bytes(0x16),
        vote_auth_sig: [0x17; 64],
        encrypted_shares: (0..VOTE_SHARE_COUNT)
            .map(|index| EncryptedShare {
                c1: vec![0x21; PROTOCOL_FIELD_BYTES],
                c2: vec![0x22; PROTOCOL_FIELD_BYTES],
                share_index: index as u32,
                plaintext_value: index as u64,
                randomness: vec![0x23; PROTOCOL_FIELD_BYTES],
            })
            .collect(),
        share_blinds: (0..VOTE_SHARE_COUNT)
            .map(|index| field_bytes(index as u8 + 1))
            .collect(),
        share_comms: (0..VOTE_SHARE_COUNT)
            .map(|index| field_bytes(index as u8 + 0x51))
            .collect(),
    }
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

    // The former `commitment_store_key_must_match_payload` test is gone. It
    // asserted that a caller-supplied commitment payload agreed with the storage
    // key it was being written under. `zcash_voting` now owns that payload
    // entirely -- it is written by `vote::commit` and has no public writer -- so
    // there is no caller-supplied payload left to disagree with the key.
}
