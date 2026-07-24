//! JNI bindings for key derivation.
//!
//! Exports for `cash.z.ecc.android.sdk.internal.jni.RustDerivationTool`, plus
//! the marshalling that serves them.

use std::panic;
use std::ptr;

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JString},
    sys::{jbyteArray, jint, jlong, jobject, jobjectArray, jstring},
};
use secrecy::{ExposeSecret, SecretVec};
use zcash_address::unified::{self, Container, Encoding, Item as _};
use zcash_client_backend::keys::{Era, UnifiedAddressRequest, UnifiedSpendingKey};
use zcash_protocol::consensus::{NetworkConstants, Parameters};
use zip32::{ChainCode, ChildIndex, DiversifierIndex, registered::PathElement};

use crate::parse_network;
use crate::utils::{self, catch_unwind, exception::unwrap_exc_or};

use super::{decode_usk, parse_ufvk, secret_from_jni, zip32_account_index_from_jlong};

/// Derives and returns a unified spending key from the given seed for the given account ID.
///
/// Returns the newly created [ZIP 316] account identifier, along with the binary encoding
/// of the [`UnifiedSpendingKey`] for the newly created account. The caller should store
/// the returned spending key in a secure fashion.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveSpendingKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    seed: JByteArray<'local>,
    account_index: jlong,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustDerivationTool.deriveSpendingKey").entered();
        let network = parse_network(network_id as u32)?;
        let seed = secret_from_jni(env, seed)?;
        let account = zip32_account_index_from_jlong(account_index)?;

        let usk = UnifiedSpendingKey::from_seed(&network, seed.expose_secret(), account)
            .map_err(|e| anyhow!("error generating unified spending key from seed: {:?}", e))?;

        let encoded = SecretVec::new(usk.to_bytes(Era::Orchard));
        Ok(utils::rust_bytes_to_java(env, encoded.expose_secret())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveUnifiedFullViewingKeysFromSeed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    seed: JByteArray<'local>,
    accounts: jint,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustDerivationTool.deriveUnifiedFullViewingKeysFromSeed")
            .entered();
        let network = parse_network(network_id as u32)?;
        let seed = secret_from_jni(env, seed)?;
        let accounts = if accounts > 0 {
            accounts as u32
        } else {
            return Err(anyhow!("accounts argument must be greater than zero"));
        };

        let ufvks: Vec<_> = (0..accounts)
            .map(|account| {
                let account_id = zip32::AccountId::try_from(account)
                    .map_err(|_| anyhow!("Invalid account ID"))?;
                UnifiedSpendingKey::from_seed(&network, seed.expose_secret(), account_id)
                    .map_err(|e| {
                        anyhow!("error generating unified spending key from seed: {:?}", e)
                    })
                    .map(|usk| usk.to_unified_full_viewing_key().encode(&network))
            })
            .collect::<Result<_, _>>()?;

        Ok(
            utils::rust_vec_to_java(env, ufvks, "java/lang/String", |env, ufvk| {
                env.new_string(ufvk)
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveUnifiedAddressFromSeed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    seed: JByteArray<'local>,
    account_index: jlong,
    network_id: jint,
) -> jstring {
    let res = panic::catch_unwind(|| {
        let _span =
            tracing::info_span!("RustDerivationTool.deriveUnifiedAddressFromSeed").entered();
        let network = parse_network(network_id as u32)?;
        let seed = secret_from_jni(&env, seed)?;
        let account_id = zip32_account_index_from_jlong(account_index)?;

        let ufvk = UnifiedSpendingKey::from_seed(&network, seed.expose_secret(), account_id)
            .map_err(|e| anyhow!("error generating unified spending key from seed: {:?}", e))
            .map(|usk| usk.to_unified_full_viewing_key())?;

        let (ua, _) = ufvk
            .find_address(
                DiversifierIndex::new(),
                UnifiedAddressRequest::AllAvailableKeys,
            )
            .expect("At least one Unified Address should be derivable");
        let address_str = ua.encode(&network);
        let output = env
            .new_string(address_str)
            .expect("Couldn't create Java string!");
        Ok(output.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveUnifiedAddressFromViewingKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    ufvk_string: JString<'local>,
    network_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span =
            tracing::info_span!("RustDerivationTool.deriveUnifiedAddressFromViewingKey").entered();
        let network = parse_network(network_id as u32)?;
        let ufvk = parse_ufvk(env, ufvk_string, &network)?;

        // Derive the default Unified Address (containing the default Sapling payment
        // address that older SDKs used).
        let (ua, _) = ufvk.default_address(UnifiedAddressRequest::AllAvailableKeys)?;
        let address_str = ua.encode(&network);
        let output = env
            .new_string(address_str)
            .expect("Couldn't create Java string!");
        Ok(output.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveUnifiedFullViewingKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    usk: JByteArray<'local>,
    network_id: jint,
) -> jstring {
    let res = panic::catch_unwind(|| {
        let _span = tracing::info_span!("RustDerivationTool.deriveUnifiedFullViewingKey").entered();
        let usk = decode_usk(&env, usk)?;
        let network = parse_network(network_id as u32)?;

        let ufvk = usk.to_unified_full_viewing_key();

        let output = env
            .new_string(ufvk.encode(&network))
            .expect("Couldn't create Java string!");

        Ok(output.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_metadata_key<'a>(
    env: &mut JNIEnv<'a>,
    key: zip32::registered::SecretKey,
) -> anyhow::Result<JObject<'a>> {
    Ok(env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniMetadataKey",
        "([B[B)V",
        &[
            (&env.byte_array_from_slice(key.data())?).into(),
            (&env.byte_array_from_slice(key.chain_code().as_bytes())?).into(),
        ],
    )?)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveAccountMetadataKeyFromSeed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    seed: JByteArray<'local>,
    account_index: jlong,
    network_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span =
            tracing::info_span!("RustDerivationTool.deriveAccountMetadataKeyFromSeed").entered();
        let network = parse_network(network_id as u32)?;
        let seed = secret_from_jni(env, seed)?;
        let account = zip32_account_index_from_jlong(account_index)?;

        let key = zip32::registered::SecretKey::from_subpath(
            b"MetadataKeys",
            seed.expose_secret(),
            // TODO: Change this to whatever ZIP number is assigned to the metadata key ZIP draft.
            325,
            &[
                PathElement::new(ChildIndex::hardened(network.coin_type()), &[]),
                PathElement::new(ChildIndex::hardened(account.into()), &[]),
            ],
        )?;

        Ok(encode_metadata_key(env, key)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_derivePrivateUseMetadataKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    account_metadata_key_sk: JByteArray<'local>,
    account_metadata_key_c: JByteArray<'local>,
    ufvk_string: JString<'local>,
    private_use_subject: JByteArray<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustDerivationTool.derivePrivateUseMetadataKey").entered();
        let account_metadata_key_sk = utils::java_bytes_to_rust(env, &account_metadata_key_sk)?;
        let account_metadata_key_c = utils::java_bytes_to_rust(env, &account_metadata_key_c)?;
        let ufvk_string = utils::java_nullable_string_to_rust(env, &ufvk_string)?;
        let private_use_subject = utils::java_bytes_to_rust(env, &private_use_subject)?;
        let network = parse_network(network_id as u32)?;

        let account_metadata_key = {
            let sk = account_metadata_key_sk
                .as_slice()
                .try_into()
                .map_err(|_| anyhow!("Incorrect length for account_metadata_key_sk"))?;

            let chain_code = ChainCode::new(
                account_metadata_key_c
                    .as_slice()
                    .try_into()
                    .map_err(|_| anyhow!("Incorrect length for account_metadata_key_c"))?,
            );

            zip32::registered::SecretKey::from_parts(sk, chain_code)
        };

        let private_use_keys = match ufvk_string {
            // For the inherent subtree, there is only ever one key.
            None => vec![
                account_metadata_key
                    .derive_child_with_tag(ChildIndex::hardened(0), &[])
                    .derive_child_with_tag(ChildIndex::PRIVATE_USE, &private_use_subject),
            ],
            // For the external subtree, we derive keys from the UFVK's items.
            Some(ufvk_string) => {
                let (net, ufvk) =
                    unified::Ufvk::decode(&ufvk_string).map_err(|e| anyhow!("{e}"))?;
                let expected_net = network.network_type();
                if net != expected_net {
                    return Err(anyhow!(
                        "UFVK is for network {:?} but we expected {:?}",
                        net,
                        expected_net,
                    ));
                }

                // Any metadata should always be associated with the key derived from the
                // most preferred FVK item. However, we don't know which FVK items the
                // UFVK contained the last time we were asked to derive keys. So we derive
                // every key and return them to the caller in preference order. If the
                // caller finds data associated with an older FVK item, they will migrate
                // it to the first key we return.
                ufvk.items()
                    .into_iter()
                    .map(|fvk_item| {
                        account_metadata_key
                            .derive_child_with_tag(ChildIndex::hardened(1), &[])
                            .derive_child_with_tag(
                                ChildIndex::hardened(0),
                                &fvk_item.typed_encoding(),
                            )
                            .derive_child_with_tag(ChildIndex::PRIVATE_USE, &private_use_subject)
                    })
                    .collect()
            }
        };

        Ok(
            utils::rust_vec_to_java(env, private_use_keys, "[B", |env, key| {
                utils::rust_bytes_to_java(env, key.data())
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveArbitraryWalletKeyFromSeed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    context_string: JByteArray<'local>,
    seed: JByteArray<'local>,
) -> jbyteArray {
    let res = panic::catch_unwind(|| {
        let _span =
            tracing::info_span!("RustDerivationTool.deriveArbitraryWalletKeyFromSeed").entered();
        let context_string = utils::java_bytes_to_rust(&env, &context_string)?;
        let seed = secret_from_jni(&env, seed)?;

        let key =
            zip32::arbitrary::SecretKey::from_path(&context_string, seed.expose_secret(), &[]);

        Ok(utils::rust_bytes_to_java(&env, key.data())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustDerivationTool_deriveArbitraryAccountKeyFromSeed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    context_string: JByteArray<'local>,
    seed: JByteArray<'local>,
    account_index: jlong,
    network_id: jint,
) -> jbyteArray {
    let res = panic::catch_unwind(|| {
        let _span =
            tracing::info_span!("RustDerivationTool.deriveArbitraryAccountKeyFromSeed").entered();
        let network = parse_network(network_id as u32)?;
        let context_string = utils::java_bytes_to_rust(&env, &context_string)?;
        let seed = secret_from_jni(&env, seed)?;
        let account = zip32_account_index_from_jlong(account_index)?;

        let key = zip32::arbitrary::SecretKey::from_path(
            &context_string,
            seed.expose_secret(),
            &[
                ChildIndex::hardened(32),
                ChildIndex::hardened(network.coin_type()),
                ChildIndex::hardened(account.into()),
            ],
        );

        Ok(utils::rust_bytes_to_java(&env, key.data())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}
