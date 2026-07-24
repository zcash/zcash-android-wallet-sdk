//! The JNI export surface.
//!
//! Every `Java_*` export lives under this module, one submodule per area,
//! together with the marshalling that serves it. The sibling logic modules
//! hold no `extern "C"` functions.
//!
//! The boundary rule is whether a symbol *speaks JNI*, not whether it is an
//! export: anything whose signature mentions [`JNIEnv`], a `J*` / `j*` type, a
//! Java class descriptor, or `env.new_object` belongs here. Everything
//! expressible in plain Rust types stays in the logic module.
//!
//! Marshalling lives beside the exports it serves. What is defined in this
//! file is only what more than one submodule needs; anything used by a single
//! area lives in that area's submodule.
//!
//! The module is named `zcash_jni` rather than `jni` so that a bare `jni::`
//! path keeps resolving to the `jni` crate everywhere, including in the crate
//! root next to `mod zcash_jni;`.

use std::path::PathBuf;

use anyhow::anyhow;
use jni::{
    JNIEnv,
    objects::{JByteArray, JString},
    sys::jlong,
};
use pczt::Pczt;
use prost::Message;
use rand::rngs::OsRng;
use secrecy::{ExposeSecret, SecretVec};
use uuid::Uuid;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    address::UnifiedAddress,
    keys::{DecodingError, Era, UnifiedFullViewingKey, UnifiedSpendingKey},
    proto::service::TreeState,
};
use zcash_client_sqlite::{AccountUuid, FsBlockDb, WalletDb, util::SystemClock};
use zcash_primitives::transaction::TxId;
use zcash_protocol::consensus::{Network, NetworkType, Parameters};

use crate::UnifiedAddressParser;
use crate::utils;

pub(crate) mod derivation;
pub(crate) mod eip681;
pub(crate) mod tor;
pub(crate) mod wallet;

fn wallet_db<P: Parameters>(
    env: &mut JNIEnv,
    params: P,
    db_data: JString,
) -> anyhow::Result<WalletDb<rusqlite::Connection, P, SystemClock, OsRng>> {
    WalletDb::for_path(path_from_jni(env, db_data)?, params, SystemClock, OsRng)
        .map_err(|e| anyhow!("Error opening wallet database connection: {}", e))
}

fn block_db(env: &mut JNIEnv, fsblockdb_root: JString) -> anyhow::Result<FsBlockDb> {
    FsBlockDb::for_path(path_from_jni(env, fsblockdb_root)?)
        .map_err(|e| anyhow!("Error opening block source database connection: {:?}", e))
}

fn path_from_jni(env: &mut JNIEnv, path: JString) -> anyhow::Result<PathBuf> {
    Ok(PathBuf::from(env.get_string(&path)?.to_str()?))
}

fn secret_from_jni(env: &JNIEnv, secret_bytes: JByteArray) -> anyhow::Result<SecretVec<u8>> {
    Ok(SecretVec::new(utils::java_bytes_to_rust(
        env,
        &secret_bytes,
    )?))
}

fn zip32_account_index_from_jlong(account_index: jlong) -> anyhow::Result<zip32::AccountId> {
    u32::try_from(account_index)
        .map_err(|_| ())
        .and_then(|id| zip32::AccountId::try_from(id).map_err(|_| ()))
        .map_err(|_| anyhow!("Invalid account ID"))
}

fn account_id_from_jni(env: &JNIEnv, account_uuid: JByteArray) -> anyhow::Result<AccountUuid> {
    Ok(AccountUuid::from_uuid(Uuid::from_slice(
        &utils::java_bytes_to_rust(env, &account_uuid)?,
    )?))
}

fn parse_txid(env: &JNIEnv, txid_bytes: JByteArray) -> anyhow::Result<TxId> {
    let txid_bytes = utils::java_bytes_to_rust(env, &txid_bytes)?;
    Ok(TxId::read(&txid_bytes[..])?)
}

fn parse_treestate(env: &JNIEnv, treestate: JByteArray) -> anyhow::Result<TreeState> {
    TreeState::decode(utils::java_bytes_to_rust(env, &treestate)?.as_slice())
        .map_err(|e| anyhow!("Invalid TreeState: {}", e))
}

fn parse_pczt(env: &JNIEnv, pczt: JByteArray) -> anyhow::Result<Pczt> {
    Pczt::parse(&utils::java_bytes_to_rust(env, &pczt)?)
        .map_err(|e| anyhow!("Invalid PCZT: {:?}", e))
}

fn parse_ufvk(
    env: &mut JNIEnv,
    ufvk_string: JString,
    network: &Network,
) -> anyhow::Result<UnifiedFullViewingKey> {
    let ufvk_string = utils::java_string_to_rust(env, &ufvk_string)?;
    UnifiedFullViewingKey::decode(network, &ufvk_string)
        .map_err(|e| anyhow!("Value \"{ufvk_string}\" did not decode as a valid UFVK: {e}"))
}
fn parse_ua(env: &mut JNIEnv, ua: JString) -> anyhow::Result<(NetworkType, UnifiedAddress)> {
    let ua_str = utils::java_string_to_rust(env, &ua)?;
    match ZcashAddress::try_from_encoded(&ua_str) {
        Ok(addr) => addr
            .convert::<UnifiedAddressParser>()
            .map_err(|e| anyhow!("Not a Unified Address: {}", e))
            .map(|ua| ua.0),
        Err(e) => Err(anyhow!("Invalid Zcash address: {}", e)),
    }
}

fn decode_usk(env: &JNIEnv, usk: JByteArray) -> anyhow::Result<UnifiedSpendingKey> {
    let usk_bytes = secret_from_jni(env, usk)?;

    // The remainder of the function is safe.
    UnifiedSpendingKey::from_bytes(Era::Orchard, usk_bytes.expose_secret()).map_err(|e| match e {
        DecodingError::EraMismatch(era) => anyhow!(
            "Spending key was from era {:?}, but {:?} was expected.",
            era,
            Era::Orchard
        ),
        e => anyhow!(
            "An error occurred decoding the provided unified spending key: {:?}",
            e
        ),
    })
}
