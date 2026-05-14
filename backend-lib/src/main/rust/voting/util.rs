use super::helpers::*;
use super::*;

#[cfg(feature = "android-test-fixtures")]
const TEST_TREE_STATE_HASH: &str =
    "0000000000000000000000000000000000000000000000000000000000000000";

#[cfg(feature = "android-test-fixtures")]
fn tree_state_fixture(orchard_tree: String) -> zcash_client_backend::proto::service::TreeState {
    zcash_client_backend::proto::service::TreeState {
        network: "test".to_string(),
        height: 1,
        hash: TEST_TREE_STATE_HASH.to_string(),
        time: 0,
        sapling_tree: String::new(),
        orchard_tree,
    }
}

#[cfg(feature = "android-test-fixtures")]
fn non_empty_orchard_tree_hex() -> anyhow::Result<String> {
    use incrementalmerkletree::frontier::CommitmentTree;
    use orchard::tree::MerkleHashOrchard;
    use zcash_primitives::merkle_tree::write_commitment_tree;

    let mut tree =
        CommitmentTree::<MerkleHashOrchard, { orchard::NOTE_COMMITMENT_TREE_DEPTH as u8 }>::empty();
    let mut leaf_bytes = [0u8; PROTOCOL_FIELD_BYTES];
    leaf_bytes[0] = 1;
    let leaf = MerkleHashOrchard::from_bytes(&leaf_bytes).unwrap();
    tree.append(leaf)
        .map_err(|_| anyhow!("append orchard commitment tree leaf"))?;

    let mut tree_bytes = vec![];
    write_commitment_tree(&tree, &mut tree_bytes)
        .map_err(|e| anyhow!("write orchard commitment tree: {}", e))?;
    Ok(hex::encode(tree_bytes))
}

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

/// Derives the canonical raw Orchard address for the hotkey identity associated with
/// `hotkey_seed` on `network_id`.
///
/// The ZIP-32 account index is intentionally fixed at 0 and is not exposed as a parameter.
/// `voting::vote_commitment::sign_cast_vote` / `voting::vote_commitment::build_vote_commitment`
/// in `zcash_voting` derive the hotkey spending key via
/// `crate::zkp2::derive_spending_key`, which hardcodes `derive_spending_key_for_account(.., 0)`.
/// Letting callers pass an arbitrary account here would allow delegation to be built against
/// a hotkey the vote-construction path cannot subsequently sign for, so the API surface
/// hides the constraint instead of leaving it as a runtime trap.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_deriveHotkeyRawAddressNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    hotkey_seed: JByteArray<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let network = network_from_id(network_id)?;
        let hotkey_seed =
            java_secret_bytes_at_least(env, &hotkey_seed, "hotkeySeed", PROTOCOL_FIELD_BYTES)?;
        let bytes = hotkey_orchard_raw_address(
            hotkey_seed.expose_secret(),
            network,
            HOTKEY_ACCOUNT_INDEX,
        )?;
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
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let witness = java_witness_data(env, &witness)?;
        let valid = voting::witness::verify_witness(&witness)
            .map_err(|e| anyhow!("verify_witness: {}", e))?;
        Ok(if valid { JNI_TRUE } else { JNI_FALSE })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_noteInfoArrayFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        make_jni_note_info_array(
            env,
            vec![NoteInfo {
                commitment: vec![0x01; PROTOCOL_FIELD_BYTES],
                nullifier: vec![0x02; PROTOCOL_FIELD_BYTES],
                value: 123_456,
                position: 7,
                diversifier: vec![0x03; ORCHARD_DIVERSIFIER_BYTES],
                rho: vec![0x04; PROTOCOL_FIELD_BYTES],
                rseed: vec![0x05; PROTOCOL_FIELD_BYTES],
                scope: NOTE_SCOPE_INTERNAL,
                ufvk_str: "ufvk-fixture".to_string(),
            }],
        )
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_witnessDataArrayFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        make_jni_witness_data_array(
            env,
            vec![WitnessData {
                note_commitment: vec![0x11; PROTOCOL_FIELD_BYTES],
                position: 9,
                root: vec![0x12; PROTOCOL_FIELD_BYTES],
                auth_path: (0..ORCHARD_WITNESS_PATH_DEPTH)
                    .map(|index| vec![0x20u8.wrapping_add(index as u8); PROTOCOL_FIELD_BYTES])
                    .collect(),
            }],
        )
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_treeStateFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        use prost::Message;

        let tree_state = tree_state_fixture(String::new());
        Ok(env
            .byte_array_from_slice(&tree_state.encode_to_vec())?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

#[cfg(feature = "android-test-fixtures")]
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_nonEmptyTreeStateFixtureNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        use prost::Message;

        let tree_state = tree_state_fixture(non_empty_orchard_tree_hex()?);
        Ok(env
            .byte_array_from_slice(&tree_state.encode_to_vec())?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
