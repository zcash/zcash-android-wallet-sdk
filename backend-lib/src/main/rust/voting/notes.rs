use super::db::*;
use super::helpers::*;
use super::*;

/// Validate that a cached lightwalletd TreeState is anchored to the voting
/// round it will be used for.
///
/// Witness generation trusts the cached Orchard frontier as the historical
/// checkpoint input. The generated Merkle path can verify against that
/// frontier's own root, so also enforce that the frontier is exactly the round
/// snapshot: same block height and same note commitment tree root.
fn validate_cached_tree_state_for_round(
    tree_state: &zcash_client_backend::proto::service::TreeState,
    orchard_root: &[u8],
    params: &voting::types::VotingRoundParams,
) -> anyhow::Result<()> {
    if tree_state.height != params.snapshot_height {
        return Err(anyhow!(
            "cached TreeState height {} does not match round snapshot_height {}",
            tree_state.height,
            params.snapshot_height
        ));
    }

    if orchard_root != params.nc_root.as_slice() {
        return Err(anyhow!(
            "cached TreeState orchard root does not match round nc_root"
        ));
    }

    Ok(())
}

fn validate_tree_state_bytes_for_round(
    tree_state_bytes: &[u8],
    params: &voting::types::VotingRoundParams,
) -> anyhow::Result<()> {
    use prost::Message;
    use zcash_client_backend::proto::service::TreeState;

    let tree_state =
        TreeState::decode(tree_state_bytes).map_err(|e| anyhow!("decode TreeState: {}", e))?;
    let orchard_ct = tree_state
        .orchard_tree()
        .map_err(|e| anyhow!("parse orchard_tree: {}", e))?;
    let orchard_root = orchard_ct.root().to_bytes();
    validate_cached_tree_state_for_round(&tree_state, &orchard_root[..], params)
}

fn require_fully_scanned_to_snapshot(
    fully_scanned_height: Option<zcash_protocol::consensus::BlockHeight>,
    snapshot_height: zcash_protocol::consensus::BlockHeight,
) -> anyhow::Result<()> {
    let snapshot_height_u32 = u32::from(snapshot_height);
    let Some(fully_scanned_height) = fully_scanned_height else {
        return Err(anyhow!(
            "wallet DB has no fully scanned height; snapshot_height={snapshot_height_u32}"
        ));
    };

    let fully_scanned_height_u32 = u32::from(fully_scanned_height);
    if fully_scanned_height_u32 < snapshot_height_u32 {
        return Err(anyhow!(
            "wallet DB fully scanned height {fully_scanned_height_u32} is below snapshot_height {snapshot_height_u32}"
        ));
    }

    Ok(())
}

type ReadOnlyWalletDb = zcash_client_sqlite::WalletDb<
    rusqlite::Connection,
    Network,
    zcash_client_sqlite::util::SystemClock,
    rand::rngs::OsRng,
>;

fn open_wallet_db_read_only(path: &str, network: Network) -> anyhow::Result<ReadOnlyWalletDb> {
    let conn =
        rusqlite::Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(|e| anyhow!("open wallet DB read-only: {}", e))?;
    rusqlite::vtab::array::load_module(&conn)
        .map_err(|e| anyhow!("load sqlite array module: {}", e))?;

    Ok(zcash_client_sqlite::WalletDb::from_connection(
        conn,
        network,
        zcash_client_sqlite::util::SystemClock,
        rand::rngs::OsRng,
    ))
}

// =============================================================================
// C. Note setup
// =============================================================================

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_computeBundleSetupNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let notes = java_note_info_array(env, &notes, "notes")?;
        let (count, weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        make_jni_bundle_setup_result(env, count, weight, &bundle_weights)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_storeTreeStateNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    tree_state_bytes: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let bytes = java_bytes(env, &tree_state_bytes, "treeStateBytes")?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let params = {
            let conn = db.conn();
            let wallet_id = db.wallet_id();
            voting::storage::queries::load_round_params(&conn, &round_id, &wallet_id)
                .map_err(|e| anyhow!("load_round_params: {}", e))?
        };
        validate_tree_state_bytes_for_round(&bytes, &params)?;
        db.store_tree_state(&round_id, &bytes)
            .map_err(|e| anyhow!("store_tree_state: {}", e))?;
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_getWalletNotesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    wallet_db_path: JString<'local>,
    snapshot_height: jlong,
    network_id: jint,
    account_uuid_bytes: JByteArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        use zcash_client_backend::data_api::{Account, WalletRead};
        use zcash_protocol::consensus::BlockHeight;

        let path = java_string_to_rust(env, &wallet_db_path)?;
        let network = network_from_id(network_id)?;
        let height = BlockHeight::from_u32(jlong_to_u32(snapshot_height, "snapshot_height")?);
        let account_uuid_bytes =
            java_fixed_bytes::<ACCOUNT_UUID_BYTES>(env, &account_uuid_bytes, "accountUuidBytes")?;
        let account_uuid =
            zcash_client_sqlite::AccountUuid::from_uuid(uuid::Uuid::from_bytes(account_uuid_bytes));

        let wallet_db = open_wallet_db_read_only(&path, network)?;
        let fully_scanned_height = wallet_db
            .block_fully_scanned()
            .map_err(|e| anyhow!("block_fully_scanned: {}", e))?
            .map(|metadata| metadata.block_height());
        require_fully_scanned_to_snapshot(fully_scanned_height, height)?;

        let account = wallet_db
            .get_account(account_uuid)
            .map_err(|e| anyhow!("get_account: {}", e))?
            .ok_or_else(|| anyhow!("account not found in wallet DB"))?;
        let ufvk = account
            .ufvk()
            .ok_or_else(|| anyhow!("account has no UFVK"))?
            .clone();

        // Upstream interprets "unspent" at the requested height: spends mined
        // after the snapshot remain eligible for that snapshot.
        let notes = wallet_db
            .get_unspent_orchard_notes_at_historical_height(account_uuid, height)
            .map_err(|e| anyhow!("get_unspent_orchard_notes_at_historical_height: {}", e))?;

        let notes = notes
            .iter()
            .map(|note| received_note_to_note_info(note, &ufvk, &network))
            .collect::<anyhow::Result<Vec<_>>>()?;

        make_jni_note_info_array(env, notes)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateNoteWitnessesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    bundle_index: jint,
    wallet_db_path: JString<'local>,
    network_id: jint,
    notes: JObjectArray<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        use incrementalmerkletree::Position;
        use orchard::tree::MerkleHashOrchard;
        use prost::Message;
        use zcash_client_backend::proto::service::TreeState;
        use zcash_protocol::consensus::BlockHeight;

        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let wallet_path = java_string_to_rust(env, &wallet_db_path)?;
        let bundle_index = jint_to_u32(bundle_index, "bundle_index")?;
        let network = network_from_id(network_id)?;

        let core_notes = java_note_info_array(env, &notes, "notes")?;

        let conn = db.conn();
        let wallet_id = db.wallet_id();
        let bundle_notes =
            select_bundle_notes(&conn, &round_id, &wallet_id, bundle_index, &core_notes)?;
        let tree_state_bytes =
            voting::storage::queries::load_tree_state(&conn, &round_id, &wallet_id)
                .map_err(|e| anyhow!("load_tree_state: {}", e))?;
        let params = voting::storage::queries::load_round_params(&conn, &round_id, &wallet_id)
            .map_err(|e| anyhow!("load_round_params: {}", e))?;
        drop(conn);

        let tree_state = TreeState::decode(tree_state_bytes.as_slice())
            .map_err(|e| anyhow!("decode TreeState: {}", e))?;
        let orchard_ct = tree_state
            .orchard_tree()
            .map_err(|e| anyhow!("parse orchard_tree: {}", e))?;
        let frontier_root = orchard_ct.root();
        let frontier_root_bytes = frontier_root.to_bytes();
        validate_cached_tree_state_for_round(&tree_state, &frontier_root_bytes[..], &params)?;
        let nonempty_frontier = orchard_ct
            .to_frontier()
            .take()
            .ok_or_else(|| anyhow!("empty orchard frontier - no Orchard activity at snapshot"))?;

        let height =
            BlockHeight::from_u32(u32::try_from(params.snapshot_height).map_err(|_| {
                anyhow!(
                    "stored snapshot_height must be in range 0..=u32::MAX, got {}",
                    params.snapshot_height
                )
            })?);

        let positions = bundle_notes
            .iter()
            .map(|note| Position::from(note.position))
            .collect::<Vec<_>>();

        let mut wallet_db = open_wallet_db_read_only(&wallet_path, network)?;
        // Keep shard roots, shard rows, and cap reads on one read-transaction snapshot.
        let merkle_paths = wallet_db
            .transactionally(|wallet_db| {
                wallet_db
                    .generate_orchard_witnesses_at_historical_height(
                        &positions,
                        nonempty_frontier,
                        height,
                    )
                    .map_err(anyhow::Error::from)
            })
            .map_err(|e| anyhow!("generate_orchard_witnesses_at_historical_height: {}", e))?;

        let root_bytes = frontier_root_bytes.to_vec();
        let witnesses = merkle_paths
            .into_iter()
            .zip(bundle_notes.iter())
            .map(|(path, note)| {
                let auth_path = path
                    .path_elems()
                    .iter()
                    .map(|hash: &MerkleHashOrchard| hash.to_bytes().to_vec())
                    .collect();
                WitnessData {
                    note_commitment: note.commitment.clone(),
                    position: note.position,
                    root: root_bytes.clone(),
                    auth_path,
                }
            })
            .collect::<Vec<_>>();

        replace_bundle_witnesses(&db, &round_id, bundle_index, &witnesses)?;

        make_jni_witness_data_array(env, witnesses)
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_setupBundlesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    notes: JObjectArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let notes = java_note_info_array(env, &notes, "notes")?;
        let (expected_count, expected_weight, bundle_weights) = bundle_setup_from_notes(&notes)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let (count, weight) = db
            .setup_bundles(&round_id, &notes)
            .map_err(|e| anyhow!("setup_bundles: {}", e))?;
        if count != expected_count || weight != expected_weight {
            // setup_bundles has already persisted the round's bundles. Treat a
            // mismatch as an internal bug; callers must clear the round before retrying.
            return Err(anyhow!(
                "setup_bundles result mismatch after persisting bundles; call clearRound before retrying: db=({}, {}) chunk=({}, {})",
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

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_generateHotkeyNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
    round_id: JString<'local>,
    seed: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let db = db_from_handle(db_handle)?;
        let _access_lock = db.access_lock()?;
        let seed = java_secret_bytes_at_least(env, &seed, "seed", PROTOCOL_FIELD_BYTES)?;
        let round_id = java_string_to_rust(env, &round_id)?;
        let hotkey = db
            .generate_hotkey(&round_id, seed.expose_secret())
            .map_err(|e| anyhow!("generate_hotkey: {}", e))?;
        update_round_phase_forward(&db, &round_id, RoundPhase::HotkeyGenerated)?;
        make_jni_voting_hotkey(env, hotkey)
    });
    unwrap_exc_or(&mut env, res, JObject::null().into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use zcash_protocol::consensus::BlockHeight;

    #[test]
    fn fully_scanned_guard_allows_snapshot_height() {
        require_fully_scanned_to_snapshot(
            Some(BlockHeight::from_u32(100)),
            BlockHeight::from_u32(100),
        )
        .unwrap();
        require_fully_scanned_to_snapshot(
            Some(BlockHeight::from_u32(101)),
            BlockHeight::from_u32(100),
        )
        .unwrap();
    }

    #[test]
    fn fully_scanned_guard_rejects_missing_or_low_height() {
        let missing =
            require_fully_scanned_to_snapshot(None, BlockHeight::from_u32(100)).unwrap_err();
        assert!(missing.to_string().contains("no fully scanned height"));

        let low = require_fully_scanned_to_snapshot(
            Some(BlockHeight::from_u32(99)),
            BlockHeight::from_u32(100),
        )
        .unwrap_err();
        assert!(low.to_string().contains("below snapshot_height"));
    }
}
