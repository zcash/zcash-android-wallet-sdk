//! JNI bindings for the wallet backend.
//!
//! Exports for `cash.z.ecc.android.sdk.internal.jni.RustBackend`, plus the
//! marshalling that serves them.

use std::convert::{Infallible, TryFrom, TryInto};
use std::error::Error;
use std::num::NonZeroU32;
use std::panic;
use std::ptr;
use std::time::UNIX_EPOCH;

use anyhow::{Context, anyhow};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobject, jobjectArray, jstring},
};
use nonempty::NonEmpty;
use prost::Message;
use secrecy::{ExposeSecret, SecretVec};
use tracing::{debug, error};
use tracing_subscriber::prelude::*;
use tracing_subscriber::reload;

use pczt::roles::{combiner::Combiner, prover::Prover, redactor::Redactor};
use transparent::{
    address::{Script, TransparentAddress},
    bundle::{OutPoint, TxOut},
    keys::TransparentKeyScope,
};
use zcash_address::{ToAddress, ZcashAddress};
use zcash_client_backend::{
    address::Address,
    data_api::{
        Account, AccountBalance, AccountBirthday, AccountPurpose, BirthdayError, CoinbaseFilter,
        InputSource, OutputStatusFilter, SeedRelevance, TransactionDataRequest, TransactionStatus,
        TransactionStatusFilter, TransparentKeyOrigin, WalletCommitmentTrees, WalletRead,
        WalletSummary, WalletWrite, Zip32Derivation,
        chain::{CommitmentTreeRoot, ScanSummary, scan_cached_blocks},
        scanning::{ScanPriority, ScanRange},
        wallet::{
            self, create_pczt_from_proposal, create_proposed_transactions,
            decrypt_and_store_transaction, extract_and_store_transaction_from_pczt,
            input_selection::SpendPolicy, propose_shielding, propose_transfer,
        },
    },
    encoding::AddressCodec,
    keys::{Era, UnifiedAddressRequest, UnifiedSpendingKey},
    proto::proposal::Proposal,
    wallet::{Exposure, GapMetadata, NoteId, OvkPolicy, WalletTransparentOutput},
    zip321::{Payment, TransactionRequest},
};
use zcash_client_sqlite::{
    AccountUuid,
    chain::{BlockMeta, init::init_blockmeta_db},
    error::SqliteClientError,
    wallet::init::{WalletMigrationError, init_wallet_db},
};
use zcash_primitives::{
    block::BlockHash,
    merkle_tree::HashSer,
    transaction::{Transaction, components::orchard::bundle_version_for_branch},
};
use zcash_proofs::prover::LocalTxProver;
use zcash_protocol::{
    consensus::{BlockHeight, BranchId, Network, NetworkType, Parameters},
    memo::{Memo, MemoBytes},
    value::{ZatBalance, Zatoshis},
};
use zcash_script::script;
use zip32::fingerprint::SeedFingerprint;

use crate::utils::{
    self, catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
};
use crate::{ReceiverFlags, parse_network, parse_protocol, print_debug_state, zip317_helper};

use super::{
    account_id_from_jni, block_db, decode_usk, parse_pczt, parse_treestate, parse_txid, parse_ua,
    parse_ufvk, path_from_jni, secret_from_jni, wallet_db, zip32_account_index_from_jlong,
};

/// Initializes global Rust state, such as the logging infrastructure and threadpools.
///
/// When `log_level` defines how the Rust layer logs its events. These values are supported:
/// `trace` - Logs very low priority, often extremely verbose, information
/// `debug` - Logs lower priority information
/// `off` - The logs are completely disabled
///
/// # Panics
///
/// This method panics if called more than once.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_initOnLoad<'local>(
    mut _env: JNIEnv<'local>,
    _: JClass<'local>,
    _log_level: JString<'local>,
) {
    // Set up the Android tracing layer.
    #[cfg(target_os = "android")]
    let android_layer = {
        let log_filter = _env
            .get_string(&_log_level)
            .ok()
            .as_ref()
            .and_then(|s| s.to_str().ok())
            .unwrap_or_else(|| {
                eprintln!("log_level not UTF-8, falling back on 'debug' level");
                "debug"
            })
            .parse()
            .unwrap_or_else(|_| {
                eprintln!("log_level not a valid level, falling back on 'debug' level");
                tracing::level_filters::LevelFilter::DEBUG
            });

        paranoid_android::layer("cash.z.rust.logs")
            .with_ansi(false)
            .with_filter(log_filter)
    };

    // Generate Android trace events from `tracing` spans.
    let (trace_event_layer, reload_handle) = reload::Layer::new(utils::trace::Layer::new(None));

    // Install the `tracing` subscriber.
    let registry = tracing_subscriber::registry();
    #[cfg(target_os = "android")]
    let registry = registry.with(android_layer);
    registry.with(trace_event_layer).init();

    // Log panics instead of writing them to stderr.
    log_panics::init();

    // Load optional NDK APIs. We do this via a reload so that we can capture any errors
    // that occur while trying to dynamically load the NDK.
    if let Err(e) = reload_handle.modify(|layer| match utils::target_ndk::load() {
        Ok(api) => *layer = utils::trace::Layer::new(Some(api)),
        Err(e) => error!("Could not open NDK library or load symbols: {}", e),
    }) {
        error!("Failed to reload tracing subscriber with NDK APIs: {}", e);
    }

    // Manually build the Rayon thread pool, so we can name the threads.
    rayon::ThreadPoolBuilder::new()
        .thread_name(|i| format!("zc-rayon-{}", i))
        .build_global()
        .expect("Only initialized once");

    debug!("Rust backend has been initialized successfully");
    print_debug_state();
}

/// Sets up the internal structure of the blockmeta database.
///
/// Returns 0 if successful, or -1 otherwise.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_initBlockMetaDb<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    fsblockdb_root: JString<'local>,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let mut db_meta = block_db(env, fsblockdb_root)?;

        match init_blockmeta_db(&mut db_meta) {
            Ok(()) => Ok(0),
            Err(e) => Err(anyhow!("Error while initializing block metadata DB: {}", e)),
        }
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// Sets up the internal structure of the data database.
///
/// If `seed` is `null`, database migrations will be attempted without it.
///
/// Returns:
/// - 0 if successful.
/// - 1 if the seed must be provided in order to execute the requested migrations.
/// - 2 if the provided seed is not relevant to any of the derived accounts in the wallet.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_initDataDb<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    seed: JByteArray<'local>,
    network_id: jint,
) -> jint {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)
            .map_err(|e| anyhow!("Error while opening data DB: {}", e))?;

        let seed = utils::java_nullable_bytes_to_rust(env, &seed)?.map(SecretVec::new);

        match init_wallet_db(&mut db_data, seed) {
            Ok(()) => Ok(0),
            Err(e)
                if matches!(
                    e.source().and_then(|e| e.downcast_ref()),
                    Some(&WalletMigrationError::SeedRequired)
                ) =>
            {
                Ok(1)
            }
            Err(e)
                if matches!(
                    e.source().and_then(|e| e.downcast_ref()),
                    Some(&WalletMigrationError::SeedNotRelevant)
                ) =>
            {
                Ok(2)
            }
            Err(e) => Err(anyhow!("Error while initializing data DB: {}", e)),
        }
    });
    unwrap_exc_or(&mut env, res, -1)
}

const JNI_ACCOUNT: &str = "cash/z/ecc/android/sdk/internal/model/JniAccount";

fn encode_account<'a, P: Parameters>(
    env: &mut JNIEnv<'a>,
    network: &P,
    account: zcash_client_sqlite::wallet::Account,
) -> jni::errors::Result<JObject<'a>> {
    let ufvk = match account.ufvk() {
        Some(ufvk) => env.new_string(ufvk.encode(network))?.into(),
        None => JObject::null(),
    };

    let uivk = match account.ufvk() {
        Some(ufvk) => {
            let uivk = ufvk.to_unified_incoming_viewing_key();
            env.new_string(uivk.encode(network))?.into()
        }
        None => JObject::null(),
    };

    let account_name = match account.name() {
        Some(name) => env.new_string(name)?.into(),
        None => JObject::null(),
    };

    let key_source = match account.source().key_source() {
        Some(source) => env.new_string(source)?.into(),
        None => JObject::null(),
    };

    let seed_fingerprint = match account.source().key_derivation() {
        Some(d) => env
            .byte_array_from_slice(&d.seed_fingerprint().to_bytes()[..])?
            .into(),
        None => JObject::null(),
    };

    let hd_account_index = match account.source().key_derivation() {
        Some(d) => JValue::Long(i64::from(u32::from(d.account_index()))),
        // Use -1 to return null across the FFI.
        None => JValue::Long(-1),
    };

    env.new_object(
        JNI_ACCOUNT,
        "(Ljava/lang/String;[BJLjava/lang/String;[BLjava/lang/String;Ljava/lang/String;)V",
        &[
            (&account_name).into(),
            (&env.byte_array_from_slice(account.id().expose_uuid().as_bytes())?).into(),
            hd_account_index,
            (&key_source).into(),
            (&seed_fingerprint).into(),
            (&ufvk).into(),
            (&uivk).into(),
        ],
    )
}

/// Fetches the accounts in the given wallet.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getAccounts<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        let accounts = db_data
            .get_account_ids()?
            .into_iter()
            .map(|account_id| {
                db_data
                    .get_account(account_id)
                    .transpose()
                    .expect("account_id exists")
            })
            .collect::<Result<Vec<_>, _>>()?;

        Ok(
            utils::rust_vec_to_java(env, accounts, JNI_ACCOUNT, |env, account| {
                encode_account(env, &network, account)
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getAccountForUfvk<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    ufvk_string: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;
        let ufvk = parse_ufvk(env, ufvk_string, &network)?;

        let account = db_data.get_account_for_ufvk(&ufvk)?;

        if let Some(account) = account {
            Ok(encode_account(env, &network, account)?.into_raw())
        } else {
            Ok(ptr::null_mut())
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_usk<'a>(
    env: &mut JNIEnv<'a>,
    account_uuid: AccountUuid,
    usk: UnifiedSpendingKey,
) -> jni::errors::Result<JObject<'a>> {
    let encoded = SecretVec::new(usk.to_bytes(Era::Orchard));
    let bytes = env.byte_array_from_slice(encoded.expose_secret())?;
    env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniAccountUsk",
        "([B[B)V",
        &[
            (&env.byte_array_from_slice(account_uuid.expose_uuid().as_bytes())?).into(),
            (&bytes).into(),
        ],
    )
}

/// Adds the next available account-level spend authority, given the current set of
/// [ZIP 316] account identifiers known, to the wallet database.
///
/// Returns the newly created [ZIP 316] account identifier, along with the binary encoding
/// of the [`UnifiedSpendingKey`] for the newly created account. The caller should store
/// the returned spending key in a secure fashion.
///
/// If `seed` was imported from a backup and this method is being used to restore a
/// previous wallet state, you should use this method to add all of the desired
/// accounts before scanning the chain from the seed's birthday height.
///
/// By convention, wallets should only allow a new account to be generated after funds
/// have been received by the currently-available account (in order to enable
/// automated account recovery).
///
/// [ZIP 316]: https://zips.z.cash/zip-0316
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_createAccount<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_name: JString<'local>,
    key_source: JString<'local>,
    seed: JByteArray<'local>,
    treestate: JByteArray<'local>,
    recover_until: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let seed = secret_from_jni(env, seed)?;
        let treestate = parse_treestate(env, treestate)?;
        let recover_until = recover_until.try_into().ok();

        let birthday =
            AccountBirthday::from_treestate(treestate, recover_until).map_err(|e| match e {
                BirthdayError::HeightInvalid(e) => {
                    anyhow!("Invalid TreeState: Invalid height: {}", e)
                }
                BirthdayError::Decode(e) => {
                    anyhow!("Invalid TreeState: Invalid frontier encoding: {}", e)
                }
            })?;

        let account_name = java_string_to_rust(env, &account_name)?;
        let key_source = java_nullable_string_to_rust(env, &key_source)?;

        let (account_uuid, usk) = db_data
            .create_account(
                &account_name,
                &seed,
                &birthday,
                key_source.as_ref().map(|s| s.as_ref()),
            )
            .map_err(|e| anyhow!("Error while initializing accounts: {}", e))?;

        Ok(encode_usk(env, account_uuid, usk)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Tells the wallet to track an account using a unified full viewing key.
///
/// Returns details about the imported account, including the unique account identifier for
/// the newly-created wallet database entry. Unlike the other account creation APIs
/// ([`Self::create_account`] and [`Self::import_account_hd`]), no spending key is returned
/// because the wallet has no information about how the UFVK was derived.
///
/// Certain optimizations are possible for accounts which will never be used to spend funds.
/// If `purpose` is 1 (ViewOnly), the wallet may choose to optimize for this case, in which
/// case any attempt to spend funds from the account will result in an error.
///
/// The [`WalletWrite`] trait documentation has more details about account creation and import.
///
/// # Arguments
/// - `account_name`: A human-readable name for the account.
/// - `key_source`: A string identifier or other metadata describing the source of the seed.
///   This is treated as opaque metadata by the wallet backend; it is provided for use by
///   applications which need to track additional identifying information for an account.
/// - `ufvk_str`: The UFVK used to detect transactions involving the account.
/// - `treestate`: The tree state corresponding to the last block prior to the wallet's
///   birthday height.
/// - `recover_until`: An optional height at which the wallet should exit "recovery mode". In
///   order to avoid confusing shifts in wallet balance and spendability that may temporarily be
///   visible to a user during the process of recovering from seed, wallets may optionally set a
///   "recover until" height. The wallet is considered to be in "recovery mode" until there
///   exist no unscanned ranges between the wallet's birthday height and the provided
///   `recover_until` height, exclusive.
/// - `purpose`: 0 (Spending) if data required for spending should be tracked by the wallet,
///   or 1 (ViewOnly) if the account will never be used to spend funds.
/// - `seed_fingerprint_bytes`: the [seed fingerprint]. Only used if `purpose` is 0 (Spending).
/// - `hd_account_index_raw`: the ZIP 32 account index. Only used if `purpose` is 0 (Spending).
///
/// [seed fingerprint]: https://zips.z.cash/zip-0032#seed-fingerprints
///
/// # Panics
///
/// Panics if the length of the seed is not between 32 and 252 bytes inclusive.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_importAccountUfvk<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_name: JString<'local>,
    key_source: JString<'local>,
    ufvk_str: JString<'local>,
    treestate: JByteArray<'local>,
    recover_until: jlong,
    purpose: jint,
    seed_fingerprint_bytes: JByteArray<'local>,
    hd_account_index_raw: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let ufvk = parse_ufvk(env, ufvk_str, &network)?;
        let treestate = parse_treestate(env, treestate)?;
        let recover_until = recover_until.try_into().ok();

        let birthday =
            AccountBirthday::from_treestate(treestate, recover_until).map_err(|e| match e {
                BirthdayError::HeightInvalid(e) => {
                    anyhow!("Invalid TreeState: Invalid height: {}", e)
                }
                BirthdayError::Decode(e) => {
                    anyhow!("Invalid TreeState: Invalid frontier encoding: {}", e)
                }
            })?;

        let account_name = java_string_to_rust(env, &account_name)?;
        let key_source = java_nullable_string_to_rust(env, &key_source)?;

        let purpose = match purpose {
            0 => {
                let seed_fingerprint =
                    utils::java_nullable_bytes_to_rust(env, &seed_fingerprint_bytes)?
                        .and_then(|b| b.as_slice().try_into().ok())
                        .map(SeedFingerprint::from_bytes);

                let hd_account_index = zip32_account_index_from_jlong(hd_account_index_raw).ok();

                let derivation = seed_fingerprint
                    .zip(hd_account_index)
                    .map(|(seed_fp, idx)| Zip32Derivation::new(seed_fp, idx));

                Ok(AccountPurpose::Spending { derivation })
            }
            1 => Ok(AccountPurpose::ViewOnly),
            _ => Err(anyhow!(
                "Account purpose must be either 0 (Spending) or 1 (ViewOnly)"
            )),
        }?;

        let account = db_data
            .import_account_ufvk(
                &account_name,
                &ufvk,
                &birthday,
                purpose,
                key_source.as_ref().map(|s| s.as_ref()),
            )
            .map_err(|e| anyhow!("Error while initializing accounts: {}", e))?;

        Ok(encode_account(env, &network, account)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Checks whether the given seed is relevant to any of the derived accounts in the wallet.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isSeedRelevantToAnyDerivedAccounts<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    seed: JByteArray<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;
        let seed = secret_from_jni(env, seed)?;

        // Replicate the logic from `initWalletDb`.
        Ok(match db_data.seed_relevance_to_derived_accounts(&seed)? {
            SeedRelevance::Relevant { .. } | SeedRelevance::NoAccounts => JNI_TRUE,
            SeedRelevance::NotRelevant | SeedRelevance::NoDerivedAccounts => JNI_FALSE,
        })
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

/// Deletes the specified account, and all transactions that exclusively involve it, from the
/// wallet database.
///
/// WARNING: This is a destructive operation and may result in the permanent loss of
/// potentially important information that is not recoverable from chain data.
///
/// Returns `true` on success, or `false` if an error is raised.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_deleteAccount<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;

        db_data.delete_account(account_uuid)?;

        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getCurrentAddress<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    network_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getCurrentAddress").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;

        match db_data.get_last_generated_address_matching(
            account_uuid,
            UnifiedAddressRequest::AllAvailableKeys,
        ) {
            Ok(Some(addr)) => {
                let addr_str = addr.encode(&network);
                let output = env
                    .new_string(addr_str)
                    .expect("Couldn't create Java string!");
                Ok(output.into_raw())
            }
            Ok(None) => Err(anyhow!("{:?} is not known to the wallet", account_uuid)),
            Err(e) => Err(anyhow!("Error while fetching address: {}", e)),
        }
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_single_use_taddr<'a>(
    env: &mut JNIEnv<'a>,
    network: &Network,
    address: &TransparentAddress,
    gap_position: u32,
    gap_limit: u32,
) -> jni::errors::Result<JObject<'a>> {
    let address_str = address.encode(&network);
    env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniSingleUseTransparentAddress",
        "(Ljava/lang/String;II)V",
        &[
            (&env.new_string(address_str)?).into(),
            JValue::Int(i32::try_from(gap_position).expect("child index fits in u31")),
            JValue::Int(i32::try_from(gap_limit).expect("child index fits in u31")),
        ],
    )
}

/// Generates and returns an ephemeral address for one-time use, such as when receiving a swap from
/// a decentralized exchange.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getSingleUseTaddr<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getSingleUseTaddr").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;

        match db_data.reserve_next_n_ephemeral_addresses(account_uuid, 1) {
            Ok(addrs) => {
                if let Some((addr, meta)) = addrs.first() {
                    match meta.exposure() {
                        Exposure::Exposed {
                            gap_metadata:
                                GapMetadata::InGap {
                                    gap_position,
                                    gap_limit,
                                },
                            ..
                        } => Ok(encode_single_use_taddr(
                            env,
                            &network,
                            addr,
                            gap_position,
                            gap_limit,
                        )?
                        .into_raw()),
                        _ => Err(anyhow!(
                            "Exposure metadata invalid for a newly generated address."
                        )),
                    }
                } else {
                    Err(anyhow!("Unable to reserve a new one-time-use address"))
                }
            }
            Err(e) => Err(anyhow!(
                "Error while generating one-time-use address: {}",
                e
            )),
        }
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns a newly-generated unified payment address for the specified account, with the next
/// available diversifier and the specified set of receivers.
///
/// The set of receivers to include in the generated address is specified by a byte which may have
/// any of the following bits set:
/// * P2PKH = 0b00000001
/// * SAPLING = 0b00000100
/// * ORCHARD = 0b00001000
///
/// Note that at present, p2pkh-only unified addresses are not supported.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getNextAvailableAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    receiver_flags: jint,
    network_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getNextAvailableAddress").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;

        let receiver_flags = <u32>::try_from(receiver_flags)
            .ok()
            .and_then(ReceiverFlags::from_bits)
            .ok_or_else(|| anyhow!("Invalid unified address receiver flags {}", receiver_flags))?;
        let address_request = receiver_flags.address_request().map_err(|_| {
            anyhow!(
                "Could not generate a valid unified address for flags {}",
                receiver_flags.bits()
            )
        })?;

        match db_data.get_next_available_address(account_uuid, address_request) {
            Ok(Some((ua, _))) => {
                let addr_str = ua.encode(&network);
                let output = env
                    .new_string(addr_str)
                    .expect("Couldn't create Java string!");
                Ok(output.into_raw())
            }
            Ok(None) => Err(anyhow!(
                "No payment address was available for account {:?}",
                account_uuid
            )),
            Err(e) => Err(anyhow!("Error while fetching address: {}", e)),
        }
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns the transparent receiver within the given Unified Address, if any.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getTransparentReceiverForUnifiedAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    ua: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span =
            tracing::info_span!("RustBackend.getTransparentReceiverForUnifiedAddress").entered();
        let (network, ua) = parse_ua(env, ua)?;

        if let Some(taddr) = ua.transparent() {
            let taddr = match taddr {
                TransparentAddress::PublicKeyHash(data) => {
                    ZcashAddress::from_transparent_p2pkh(network, *data)
                }
                TransparentAddress::ScriptHash(data) => {
                    ZcashAddress::from_transparent_p2sh(network, *data)
                }
            };

            let output = env
                .new_string(taddr.encode())
                .expect("Couldn't create Java string!");
            Ok(output.into_raw())
        } else {
            Err(anyhow!(
                "Unified Address doesn't contain a transparent receiver"
            ))
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns the Sapling receiver within the given Unified Address, if any.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getSaplingReceiverForUnifiedAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    ua: JString<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span =
            tracing::info_span!("RustBackend.getSaplingReceiverForUnifiedAddress").entered();
        let (network, ua) = parse_ua(env, ua)?;

        if let Some(addr) = ua.sapling() {
            let output = env
                .new_string(ZcashAddress::from_sapling(network, addr.to_bytes()).encode())
                .expect("Couldn't create Java string!");
            Ok(output.into_raw())
        } else {
            Err(anyhow!(
                "Unified Address doesn't contain a Sapling receiver"
            ))
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns a list of the transparent receivers for the diversified unified addresses that have
/// been allocated for the provided account.
///
/// # Safety
///
/// - `db_data` must be non-null and valid for reads for `db_data_len` bytes, and it must have an
///   alignment of `1`. Its contents must be a string representing a valid system path in the
///   operating system's preferred representation.
/// - The memory referenced by `db_data` must not be mutated for the duration of the function call.
/// - The total size `db_data_len` must be no larger than `isize::MAX`. See the safety
///   documentation of pointer::offset.
/// - Call [`zcashlc_free_keys`] to free the memory associated with the returned pointer
///   when done using it.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_listTransparentReceivers<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.listTransparentReceivers").entered();
        let network = parse_network(network_id as u32)?;
        let zcash_network = network.network_type();
        let db_data = wallet_db(env, network, db_data)?;
        let account = account_id_from_jni(env, account_uuid)?;

        // Zashi does not support standalone keys, so we do not request standalone receivers.
        match db_data.get_transparent_receivers(account, true, false) {
            Ok(receivers) => {
                let transparent_receivers = receivers
                    .keys()
                    .map(|taddr| {
                        let taddr = match taddr {
                            TransparentAddress::PublicKeyHash(data) => {
                                ZcashAddress::from_transparent_p2pkh(zcash_network, *data)
                            }
                            TransparentAddress::ScriptHash(data) => {
                                ZcashAddress::from_transparent_p2sh(zcash_network, *data)
                            }
                        };
                        taddr.encode()
                    })
                    .collect::<Vec<_>>();

                Ok(utils::rust_vec_to_java(
                    env,
                    transparent_receivers,
                    "java/lang/String",
                    |env, taddr| env.new_string(taddr),
                )?
                .into_raw())
            }
            Err(e) => Err(anyhow!("Error while fetching address: {}", e)),
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isValidSpendingKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    usk: JByteArray<'local>,
) -> jboolean {
    let res = panic::catch_unwind(|| {
        let _span = tracing::info_span!("RustBackend.isValidSpendingKey").entered();
        let _usk = decode_usk(&env, usk)?;
        Ok(JNI_TRUE)
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isValidSaplingAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    addr: JString<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.isValidSaplingAddress").entered();
        let network = parse_network(network_id as u32)?;
        let addr = utils::java_string_to_rust(env, &addr)?;

        match Address::decode(&network, &addr) {
            Some(addr) => match addr {
                Address::Sapling(_) => Ok(JNI_TRUE),
                Address::Transparent(_) | Address::Unified(_) | Address::Tex(_) => Ok(JNI_FALSE),
            },
            None => Err(anyhow!("Address is for the wrong network")),
        }
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isValidTransparentAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    addr: JString<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.isValidTransparentAddress").entered();
        let network = parse_network(network_id as u32)?;
        let addr = utils::java_string_to_rust(env, &addr)?;

        match Address::decode(&network, &addr) {
            Some(addr) => match addr {
                Address::Sapling(_) | Address::Unified(_) | Address::Tex(_) => Ok(JNI_FALSE),
                Address::Transparent(_) => Ok(JNI_TRUE),
            },
            None => Err(anyhow!("Address is for the wrong network")),
        }
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isValidUnifiedAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    addr: JString<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.isValidUnifiedAddress").entered();
        let network = parse_network(network_id as u32)?;
        let addr = utils::java_string_to_rust(env, &addr)?;

        match Address::decode(&network, &addr) {
            Some(addr) => match addr {
                Address::Unified(_) => Ok(JNI_TRUE),
                Address::Sapling(_) | Address::Transparent(_) | Address::Tex(_) => Ok(JNI_FALSE),
            },
            None => Err(anyhow!("Address is for the wrong network")),
        }
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_isValidTexAddress<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    addr: JString<'local>,
    network_id: jint,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.isValidTexAddress").entered();
        let network = parse_network(network_id as u32)?;
        let addr = utils::java_string_to_rust(env, &addr)?;

        match Address::decode(&network, &addr) {
            Some(addr) => match addr {
                Address::Sapling(_) | Address::Transparent(_) | Address::Unified(_) => {
                    Ok(JNI_FALSE)
                }
                Address::Tex(_) => Ok(JNI_TRUE),
            },
            None => Err(anyhow!("Address is for the wrong network")),
        }
    });
    unwrap_exc_or(&mut env, res, JNI_FALSE)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getTotalTransparentBalance<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    address: JString<'local>,
    network_id: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getTotalTransparentBalance").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;
        let addr = utils::java_string_to_rust(env, &address)?;
        let taddr = TransparentAddress::decode(&network, &addr)?;

        let min_confirmations = NonZeroU32::MIN;

        let (target, _) = db_data
            .get_target_and_anchor_heights(min_confirmations)
            .map_err(|e| anyhow!("Error while fetching target height: {}", e))?
            .context("Target height not available; scan required.")?;

        let amount = db_data
            .get_spendable_transparent_outputs(
                &taddr,
                target,
                wallet::ConfirmationsPolicy::MIN,
                CoinbaseFilter::AllTransparentOutputs,
            )
            .map_err(|e| anyhow!("Error while fetching verified balance: {}", e))?
            .iter()
            .map(|utxo| utxo.txout().value())
            .sum::<Option<Zatoshis>>()
            .ok_or_else(|| anyhow!("Balance overflowed MAX_MONEY"))?;

        Ok(ZatBalance::from(amount).into())
    });

    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getMemoAsUtf8<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    txid_bytes: JByteArray<'local>,
    pool_type: jint,
    output_index: jint,
    network_id: jint,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getMemoAsUtf8").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        let txid = parse_txid(env, txid_bytes)?;
        let protocol = parse_protocol(pool_type)?;
        let output_index = u16::try_from(output_index)?;

        let memo = db_data
            .get_memo(NoteId::new(txid, protocol, output_index))
            .map_err(|e| anyhow!("An error occurred retrieving the memo, {}", e))
            .and_then(|memo| match memo {
                Some(Memo::Empty) => Ok("".to_string()),
                Some(Memo::Text(memo)) => Ok(memo.into()),
                None => Err(anyhow!("Memo not available")),
                _ => Err(anyhow!("This memo does not contain UTF-8 text")),
            })?;

        let output = env.new_string(memo).expect("Couldn't create Java string!");
        Ok(output.into_raw())
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_blockmeta<'a>(env: &mut JNIEnv<'a>, meta: BlockMeta) -> jni::errors::Result<JObject<'a>> {
    let block_hash = env.byte_array_from_slice(&meta.block_hash.0)?;
    env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniBlockMeta",
        "(J[BJJJ)V",
        &[
            JValue::Long(i64::from(u32::from(meta.height))),
            (&block_hash).into(),
            JValue::Long(i64::from(meta.block_time)),
            JValue::Long(i64::from(meta.sapling_outputs_count)),
            JValue::Long(i64::from(meta.orchard_actions_count)),
        ],
    )
}

fn decode_blockmeta(env: &mut JNIEnv, obj: JObject) -> anyhow::Result<BlockMeta> {
    fn long_as_u32(env: &mut JNIEnv, obj: &JObject, name: &str) -> anyhow::Result<u32> {
        Ok(u32::try_from(env.get_field(obj, name, "J")?.j()?)?)
    }

    fn byte_array<const N: usize>(
        env: &mut JNIEnv,
        obj: &JObject,
        name: &str,
    ) -> anyhow::Result<[u8; N]> {
        let field = JByteArray::from(env.get_field(obj, name, "[B")?.l()?);
        Ok(utils::java_bytes_to_rust(env, &field)?[..].try_into()?)
    }

    Ok(BlockMeta {
        height: BlockHeight::from_u32(long_as_u32(env, &obj, "height")?),
        block_hash: BlockHash(byte_array(env, &obj, "hash")?),
        block_time: long_as_u32(env, &obj, "time")?,
        sapling_outputs_count: long_as_u32(env, &obj, "saplingOutputsCount")?,
        orchard_actions_count: long_as_u32(env, &obj, "orchardOutputsCount")?,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_writeBlockMetadata<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_cache: JString<'local>,
    block_meta: JObjectArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.writeBlockMetadata").entered();
        let block_db = block_db(env, db_cache)?;

        let block_meta = {
            let count = env.get_array_length(&block_meta)?;
            (0..count)
                .scan(env, |env, i| {
                    Some(
                        env.get_object_array_element(&block_meta, i)
                            .map_err(|e| e.into())
                            .and_then(|jobj| decode_blockmeta(env, jobj)),
                    )
                })
                .collect::<Result<Vec<_>, _>>()?
        };

        block_db
            .write_block_metadata(&block_meta)
            .map_err(|e| anyhow!("Failed to write block metadata to FsBlockDb: {:?}", e))
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getLatestCacheHeight<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    fsblockdb_root: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getLatestCacheHeight").entered();
        let block_db = block_db(env, fsblockdb_root)?;

        match block_db.get_max_cached_height() {
            Ok(Some(block_height)) => Ok(i64::from(u32::from(block_height))),
            // Use -1 to return null across the FFI.
            Ok(None) => Ok(-1),
            Err(e) => Err(anyhow!(
                "Failed to read block metadata from FsBlockDb: {:?}",
                e
            )),
        }
    });
    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_findBlockMetadata<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    fsblockdb_root: JString<'local>,
    height: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.findBlockMetadata").entered();
        let block_db = block_db(env, fsblockdb_root)?;
        let height = BlockHeight::try_from(height)?;

        match block_db.find_block(height) {
            Ok(Some(meta)) => Ok(encode_blockmeta(env, meta)?.into_raw()),
            Ok(None) => Ok(ptr::null_mut()),
            Err(e) => Err(anyhow!(
                "Failed to read block metadata from FsBlockDb: {:?}",
                e
            )),
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_rewindBlockMetadataToHeight<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    fsblockdb_root: JString<'local>,
    height: jlong,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.rewindBlockMetadataToHeight").entered();
        let block_db = block_db(env, fsblockdb_root)?;
        let height = BlockHeight::try_from(height)?;

        block_db.truncate_to_height(height).map_err(|e| {
            anyhow!(
                "Error while rewinding block metadata DB to height {}: {}",
                height,
                e
            )
        })
    });

    unwrap_exc_or(&mut env, res, ())
}

fn encode_rewind_result<'a>(
    env: &mut JNIEnv<'a>,
    requested_height: BlockHeight,
    rewind_result: Result<BlockHeight, SqliteClientError>,
) -> anyhow::Result<JObject<'a>> {
    match rewind_result {
        Ok(height) => Ok(env.new_object(
            "cash/z/ecc/android/sdk/internal/model/JniRewindResult$Success",
            "(J)V",
            &[JValue::Long(u32::from(height).into())],
        )?),
        Err(SqliteClientError::RequestedRewindInvalid {
            safe_rewind_height: Some(safe_rewind_height),
            ..
        }) => Ok(env.new_object(
            "cash/z/ecc/android/sdk/internal/model/JniRewindResult$Invalid",
            "(JJ)V",
            &[
                JValue::Long(u32::from(safe_rewind_height).into()),
                JValue::Long(u32::from(requested_height).into()),
            ],
        )?),
        Err(e) => Err(anyhow!(
            "Error while rewinding data DB to height {}: {}",
            requested_height,
            e,
        )),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_rewindToHeight<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    height: jlong,
    network_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.rewindToHeight").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;

        let height = BlockHeight::try_from(height)?;
        let rewind_result = db_data.truncate_to_height(height);

        Ok(encode_rewind_result(env, height, rewind_result)?.into_raw())
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Truncates the data database to the specified chain state.
///
/// In contrast to `rewindToHeight`, this function allows the caller to truncate the wallet
/// database to a precise height by providing additional chain state information needed for
/// note commitment tree maintenance after the truncation.
///
/// The `chain_state` parameter is a protobuf-encoded `TreeState` value representing the chain
/// state at the height to which the database should be truncated.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_truncateToChainState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    chain_state: JByteArray<'local>,
    network_id: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.truncateToChainState").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let chain_state = parse_treestate(env, chain_state)?.to_chain_state()?;

        db_data
            .truncate_to_chain_state(chain_state)
            .map_err(|e| anyhow!("Error while truncating to chain state: {}", e))?;

        Ok(())
    });

    unwrap_exc_or(&mut env, res, ())
}

fn decode_subtree_root<H>(
    env: &mut JNIEnv,
    obj: JObject,
    node_parser: impl FnOnce(&[u8]) -> std::io::Result<H>,
) -> anyhow::Result<CommitmentTreeRoot<H>> {
    fn long_as_u32(env: &mut JNIEnv, obj: &JObject, name: &str) -> anyhow::Result<u32> {
        Ok(u32::try_from(env.get_field(obj, name, "J")?.j()?)?)
    }

    fn byte_array(env: &mut JNIEnv, obj: &JObject, name: &str) -> anyhow::Result<Vec<u8>> {
        let field = JByteArray::from(env.get_field(obj, name, "[B")?.l()?);
        utils::java_bytes_to_rust(env, &field)
    }

    Ok(CommitmentTreeRoot::from_parts(
        BlockHeight::from_u32(long_as_u32(env, &obj, "completingBlockHeight")?),
        node_parser(&byte_array(env, &obj, "rootHash")?[..])?,
    ))
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_putSubtreeRoots<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    sapling_start_index: jlong,
    sapling_roots: JObjectArray<'local>,
    orchard_start_index: jlong,
    orchard_roots: JObjectArray<'local>,
    ironwood_start_index: jlong,
    ironwood_roots: JObjectArray<'local>,
    network_id: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.putSubtreeRoots").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;

        fn parse_roots<H>(
            env: &mut JNIEnv,
            roots: JObjectArray,
            node_parser: impl Fn(&[u8]) -> std::io::Result<H>,
        ) -> anyhow::Result<Vec<CommitmentTreeRoot<H>>> {
            let count = env.get_array_length(&roots)?;
            (0..count)
                .scan(env, |env, i| {
                    Some(
                        env.get_object_array_element(&roots, i)
                            .map_err(|e| e.into())
                            .and_then(|jobj| decode_subtree_root(env, jobj, &node_parser)),
                    )
                })
                .collect::<Result<Vec<_>, _>>()
        }

        let sapling_start_index = if sapling_start_index >= 0 {
            sapling_start_index as u64
        } else {
            return Err(anyhow!("Sapling start index must be nonnegative."));
        };
        let sapling_roots = parse_roots(env, sapling_roots, |n| sapling::Node::read(n))?;

        let orchard_start_index = if orchard_start_index >= 0 {
            orchard_start_index as u64
        } else {
            return Err(anyhow!("Orchard start index must be nonnegative."));
        };
        let orchard_roots = parse_roots(env, orchard_roots, |n| {
            orchard::tree::MerkleHashOrchard::read(n)
        })?;

        let ironwood_start_index = if ironwood_start_index >= 0 {
            ironwood_start_index as u64
        } else {
            return Err(anyhow!("Ironwood start index must be nonnegative."));
        };
        let ironwood_roots = parse_roots(env, ironwood_roots, |n| {
            orchard::tree::MerkleHashOrchard::read(n)
        })?;

        db_data
            .put_sapling_subtree_roots(sapling_start_index, &sapling_roots)
            .map_err(|e| anyhow!("Error while storing Sapling subtree roots: {}", e))?;

        db_data
            .put_orchard_subtree_roots(orchard_start_index, &orchard_roots)
            .map_err(|e| anyhow!("Error while storing Orchard subtree roots: {}", e))?;

        db_data
            .put_ironwood_subtree_roots(ironwood_start_index, &ironwood_roots)
            .map_err(|e| anyhow!("Error while storing Ironwood subtree roots: {}", e))?;

        Ok(())
    });

    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_updateChainTip<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    height: jlong,
    network_id: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.updateChainTip").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let height = BlockHeight::try_from(height)?;

        db_data
            .update_chain_tip(height)
            .map_err(|e| anyhow!("Error while updating chain tip to height {}: {}", height, e))
    });

    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getFullyScannedHeight<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getFullyScannedHeight").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        match db_data.block_fully_scanned() {
            Ok(Some(metadata)) => Ok(i64::from(u32::from(metadata.block_height()))),
            // Use -1 to return null across the FFI.
            Ok(None) => Ok(-1),
            Err(e) => Err(anyhow!(
                "Failed to read block metadata from WalletDb: {:?}",
                e
            )),
        }
    });
    unwrap_exc_or(&mut env, res, -1)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getMaxScannedHeight<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getMaxScannedHeight").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        match db_data.block_max_scanned() {
            Ok(Some(metadata)) => Ok(i64::from(u32::from(metadata.block_height()))),
            // Use -1 to return null across the FFI.
            Ok(None) => Ok(-1),
            Err(e) => Err(anyhow!(
                "Failed to read block metadata from WalletDb: {:?}",
                e
            )),
        }
    });
    unwrap_exc_or(&mut env, res, -1)
}

const JNI_ACCOUNT_BALANCE: &str = "cash/z/ecc/android/sdk/internal/model/JniAccountBalance";

fn encode_account_balance<'a>(
    env: &mut JNIEnv<'a>,
    account_uuid: &AccountUuid,
    balance: &AccountBalance,
) -> jni::errors::Result<JObject<'a>> {
    let sapling_verified_balance = ZatBalance::from(balance.sapling_balance().spendable_value());
    let sapling_change_pending =
        ZatBalance::from(balance.sapling_balance().change_pending_confirmation());
    let sapling_value_pending =
        ZatBalance::from(balance.sapling_balance().value_pending_spendability());

    let orchard_verified_balance = ZatBalance::from(balance.orchard_balance().spendable_value());
    let orchard_change_pending =
        ZatBalance::from(balance.orchard_balance().change_pending_confirmation());
    let orchard_value_pending =
        ZatBalance::from(balance.orchard_balance().value_pending_spendability());

    let ironwood_verified_balance = ZatBalance::from(balance.ironwood_balance().spendable_value());
    let ironwood_change_pending =
        ZatBalance::from(balance.ironwood_balance().change_pending_confirmation());
    let ironwood_value_pending =
        ZatBalance::from(balance.ironwood_balance().value_pending_spendability());

    let unshielded = ZatBalance::from(balance.unshielded_balance().total());

    env.new_object(
        JNI_ACCOUNT_BALANCE,
        "([BJJJJJJJJJJ)V",
        &[
            (&env.byte_array_from_slice(account_uuid.expose_uuid().as_bytes())?).into(),
            JValue::Long(sapling_verified_balance.into()),
            JValue::Long(sapling_change_pending.into()),
            JValue::Long(sapling_value_pending.into()),
            JValue::Long(orchard_verified_balance.into()),
            JValue::Long(orchard_change_pending.into()),
            JValue::Long(orchard_value_pending.into()),
            JValue::Long(ironwood_verified_balance.into()),
            JValue::Long(ironwood_change_pending.into()),
            JValue::Long(ironwood_value_pending.into()),
            JValue::Long(unshielded.into()),
        ],
    )
}

/// Returns a `JniWalletSummary` object, provided that `progress_numerator` is
/// nonnegative, `progress_denominator` is positive, and the represented ratio is in the
/// range 0.0 to 1.0 inclusive.
///
/// If these conditions are not met, this fails and leaves an `IllegalArgumentException`
/// pending.
fn encode_wallet_summary<'a>(
    env: &mut JNIEnv<'a>,
    summary: WalletSummary<AccountUuid>,
) -> anyhow::Result<JObject<'a>> {
    let account_balances = summary.account_balances().iter().collect::<Vec<_>>();

    let account_balances = utils::rust_vec_to_java(
        env,
        account_balances,
        JNI_ACCOUNT_BALANCE,
        |env, (account_uuid, balance)| encode_account_balance(env, account_uuid, balance),
    )?;

    let (recovery_progress_numerator, recovery_progress_denominator) =
        if let Some(recovery_progress) = summary.progress().recovery() {
            (
                env.new_object(
                    "java/lang/Long",
                    "(J)V",
                    &[JValue::Long(*recovery_progress.numerator() as i64)],
                )?,
                env.new_object(
                    "java/lang/Long",
                    "(J)V",
                    &[JValue::Long(*recovery_progress.denominator() as i64)],
                )?,
            )
        } else {
            (JObject::null(), JObject::null())
        };

    Ok(env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniWalletSummary",
        format!(
            "([L{};JJJJLjava/lang/Long;Ljava/lang/Long;JJJ)V",
            JNI_ACCOUNT_BALANCE
        ),
        &[
            (&account_balances).into(),
            JValue::Long(i64::from(u32::from(summary.chain_tip_height()))),
            JValue::Long(i64::from(u32::from(summary.fully_scanned_height()))),
            JValue::Long(*summary.progress().scan().numerator() as i64),
            JValue::Long(*summary.progress().scan().denominator() as i64),
            JValue::Object(&recovery_progress_numerator),
            JValue::Object(&recovery_progress_denominator),
            JValue::Long(summary.next_sapling_subtree_index() as i64),
            JValue::Long(summary.next_orchard_subtree_index() as i64),
            JValue::Long(summary.next_ironwood_subtree_index() as i64),
        ],
    )?)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_getWalletSummary<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.getWalletSummary").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        match db_data
            .get_wallet_summary(wallet::ConfirmationsPolicy::default())
            .map_err(|e| anyhow!("Error while fetching scan progress: {}", e))?
        {
            Some(summary) => Ok(encode_wallet_summary(env, summary)?.into_raw()),
            None => Ok(ptr::null_mut()),
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_scan_range<'a>(
    env: &mut JNIEnv<'a>,
    scan_range: ScanRange,
) -> jni::errors::Result<JObject<'a>> {
    let priority = match scan_range.priority() {
        ScanPriority::Ignored => 0,
        ScanPriority::Scanned => 10,
        ScanPriority::Historic => 20,
        ScanPriority::OpenAdjacent => 30,
        ScanPriority::FoundNote => 40,
        ScanPriority::ChainTip => 50,
        ScanPriority::Verify => 60,
    };
    env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniScanRange",
        "(JJJ)V",
        &[
            JValue::Long(i64::from(u32::from(scan_range.block_range().start))),
            JValue::Long(i64::from(u32::from(scan_range.block_range().end))),
            JValue::Long(priority),
        ],
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_suggestScanRanges<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.suggestScanRanges").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        let ranges = db_data
            .suggest_scan_ranges()
            .map_err(|e| anyhow!("Error while fetching suggested scan ranges: {}", e))?;

        Ok(utils::rust_vec_to_java(
            env,
            ranges,
            "cash/z/ecc/android/sdk/internal/model/JniScanRange",
            |env, scan_range| encode_scan_range(env, scan_range),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_scan_summary<'a>(
    env: &mut JNIEnv<'a>,
    scan_summary: ScanSummary,
) -> anyhow::Result<JObject<'a>> {
    let scanned_range = scan_summary.scanned_range();
    Ok(env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniScanSummary",
        "(JJJJ)V",
        &[
            i64::from(u32::from(scanned_range.start)).into(),
            i64::from(u32::from(scanned_range.end)).into(),
            i64::try_from(scan_summary.spent_sapling_note_count())?.into(),
            i64::try_from(scan_summary.received_sapling_note_count())?.into(),
        ],
    )?)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_scanBlocks<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_cache: JString<'local>,
    db_data: JString<'local>,
    from_height: jlong,
    from_state: JByteArray<'local>,
    limit: jlong,
    network_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.scanBlocks").entered();
        let network = parse_network(network_id as u32)?;
        let db_cache = block_db(env, db_cache)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let from_height = BlockHeight::try_from(from_height)?;
        let from_state = parse_treestate(env, from_state)?.to_chain_state()?;
        let limit = usize::try_from(limit)?;

        match scan_cached_blocks(
            &network,
            &db_cache,
            &mut db_data,
            from_height,
            &from_state,
            limit,
        ) {
            Ok(scan_summary) => Ok(encode_scan_summary(env, scan_summary)?.into_raw()),
            Err(e) => Err(anyhow!(
                "Rust error while scanning blocks (limit {:?}): {}",
                limit,
                e
            )),
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

const TRANSACTION_STATUS_FILTER: &str =
    "cash/z/ecc/android/sdk/internal/model/JniTransactionStatusFilter";
const OUTPUT_STATUS_FILTER: &str = "cash/z/ecc/android/sdk/internal/model/JniOutputStatusFilter";

fn encode_transaction_data_request<'a>(
    env: &mut JNIEnv<'a>,
    net: NetworkType,
    transaction_data_request: TransactionDataRequest,
) -> jni::errors::Result<JObject<'a>> {
    match transaction_data_request {
        TransactionDataRequest::GetStatus(txid) => env.new_object(
            "cash/z/ecc/android/sdk/internal/model/JniTransactionDataRequest$GetStatus",
            "([B)V",
            &[(&env.byte_array_from_slice(txid.as_ref())?).into()],
        ),
        TransactionDataRequest::Enhancement(txid) => env.new_object(
            "cash/z/ecc/android/sdk/internal/model/JniTransactionDataRequest$Enhancement",
            "([B)V",
            &[(&env.byte_array_from_slice(txid.as_ref())?).into()],
        ),
        TransactionDataRequest::TransactionsInvolvingAddress(txs) => {
            let address = txs.address();
            let block_range_start = txs.block_range_start();
            let block_range_end = txs.block_range_end();
            let request_at = txs.request_at();
            let tx_status_filter = txs.tx_status_filter();
            let output_status_filter = txs.output_status_filter();

            let taddr = match address {
                TransparentAddress::PublicKeyHash(data) => {
                    ZcashAddress::from_transparent_p2pkh(net, data)
                }
                TransparentAddress::ScriptHash(data) => {
                    ZcashAddress::from_transparent_p2sh(net, data)
                }
            };

            let request_at = request_at.map_or(-1, |t| {
                t.duration_since(UNIX_EPOCH)
                    .expect("SystemTime should never be before the epoch")
                    .as_secs()
                    .try_into()
                    .expect("we have time before a SystemTime overflows i64")
            });

            let tx_status_filter = env.new_object(
                format!(
                    "{TRANSACTION_STATUS_FILTER}${}",
                    match tx_status_filter {
                        TransactionStatusFilter::Mined => {
                            "Mined"
                        }
                        TransactionStatusFilter::Mempool => {
                            "Mempool"
                        }
                        TransactionStatusFilter::All => {
                            "All"
                        }
                    }
                ),
                "()V",
                &[],
            )?;

            let output_status_filter = env.new_object(
                format!(
                    "{OUTPUT_STATUS_FILTER}${}",
                    match output_status_filter {
                        OutputStatusFilter::Unspent => {
                            "Unspent"
                        }
                        OutputStatusFilter::All => {
                            "All"
                        }
                    }
                ),
                "()V",
                &[],
            )?;

            env.new_object(
                "cash/z/ecc/android/sdk/internal/model/JniTransactionDataRequest$TransactionsInvolvingAddress",
                format!(
                    "(Ljava/lang/String;JJJL{TRANSACTION_STATUS_FILTER};L{OUTPUT_STATUS_FILTER};)V"
                ),
                &[
                    (&env.new_string(taddr.encode())?).into(),
                    JValue::Long(i64::from(u32::from(block_range_start))),
                    JValue::Long(block_range_end.map(u32::from).map(i64::from).unwrap_or(-1)),
                    JValue::Long(request_at),
                    JValue::Object(&tx_status_filter),
                    JValue::Object(&output_status_filter),
                ],
            )
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_transactionDataRequests<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.transactionDataRequests").entered();
        let network = parse_network(network_id as u32)?;
        let db_data = wallet_db(env, network, db_data)?;

        let ranges = db_data
            .transaction_data_requests()
            .map_err(|e| anyhow!("Error while fetching transaction data requests: {}", e))?;

        let net = network.network_type();

        Ok(utils::rust_vec_to_java(
            env,
            ranges,
            "cash/z/ecc/android/sdk/internal/model/JniTransactionDataRequest",
            |env, request| encode_transaction_data_request(env, net, request),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_fixWitnesses<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    network_id: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;

        let corrupt_ranges = db_data.check_witnesses()?;
        if let Some(nel_ranges) = NonEmpty::from_vec(corrupt_ranges) {
            db_data.queue_rescans(nel_ranges, ScanPriority::FoundNote)?;
        }

        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_putUtxo<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    txid_bytes: JByteArray<'local>,
    index: jint,
    script: JByteArray<'local>,
    value: jlong,
    height: jlong,
    network_id: jint,
) {
    // debug!("For height {} found consensus branch {:?}", height, branch);
    debug!("preparing to store UTXO in db_data");
    #[allow(deprecated)]
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.putUtxo").entered();
        let network = parse_network(network_id as u32)?;
        let txid = parse_txid(env, txid_bytes)?;

        let script_pubkey = Script(script::Code(utils::java_bytes_to_rust(env, &script)?));
        let mut db_data = wallet_db(env, network, db_data)?;

        let output = WalletTransparentOutput::from_parts(
            OutPoint::new(*txid.as_ref(), index as u32),
            TxOut {
                value: Zatoshis::from_nonnegative_i64(value)
                    .map_err(|_| anyhow!("Invalid UTXO value"))?,
                script_pubkey,
            },
            Some(BlockHeight::from(height as u32)),
            None,
            None,
            None,
        )
        .ok_or_else(|| anyhow!("UTXO is not P2PKH or P2SH"))?;

        debug!("Storing UTXO in db_data");
        match db_data.put_received_transparent_utxo(&output) {
            Ok(_) => Ok(()),
            Err(e) => Err(anyhow!("Error while inserting UTXO: {}", e)),
        }
    });
    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_decryptAndStoreTransaction<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    tx: JByteArray<'local>,
    mined_height: jlong,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.decryptAndStoreTransaction").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let tx_bytes = utils::java_bytes_to_rust(env, &tx)?;
        // The consensus branch ID passed in here does not matter:
        // - v4 and below cache it internally, but all we do with this transaction while
        //   it is in memory is decryption and serialization, neither of which use the
        //   consensus branch ID.
        // - v5 and above transactions ignore the argument, and parse the correct value
        //   from their encoding.
        let tx = Transaction::read(&tx_bytes[..], BranchId::Sapling)?;
        let mined_height = BlockHeight::try_from(mined_height).ok();

        decrypt_and_store_transaction(&network, &mut db_data, &tx, mined_height)
            .map_err(|e| anyhow!("Error while decrypting transaction: {}", e))?;

        Ok(utils::rust_bytes_to_java(env, tx.txid().as_ref())?.into_raw())
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_setTransactionStatus<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    txid_bytes: JByteArray<'local>,
    status: jlong,
    network_id: jint,
) {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.setTransactionStatus").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let txid = parse_txid(env, txid_bytes)?;
        let status = match status {
            -2 => TransactionStatus::TxidNotRecognized,
            -1 => TransactionStatus::NotInMainChain,
            height => TransactionStatus::Mined(BlockHeight::try_from(height)?),
        };

        db_data
            .set_transaction_status(txid, status)
            .map_err(|e| anyhow!("Error while setting transaction status: {}", e))
    });

    unwrap_exc_or(&mut env, res, ())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_proposeTransferFromUri<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    payment_uri: JString<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.proposeTransfer").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;
        let payment_uri = utils::java_string_to_rust(env, &payment_uri)?;

        // Always use ZIP 317 fees
        let (change_strategy, input_selector) = zip317_helper(None);

        let request = TransactionRequest::from_uri(&payment_uri)
            .map_err(|e| anyhow!("Error creating transaction request: {:?}", e))?;

        let proposal = propose_transfer::<_, _, _, _, Infallible>(
            &mut db_data,
            &network,
            account_uuid,
            &input_selector,
            &change_strategy,
            request,
            wallet::ConfirmationsPolicy::default(),
            &SpendPolicy::default(),
            None,
        )
        .map_err(|e| anyhow!("Error creating transaction proposal: {}", e))?;

        Ok(utils::rust_bytes_to_java(
            env,
            Proposal::from_standard_proposal(&proposal)
                .encode_to_vec()
                .as_ref(),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_proposeTransfer<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    to: JString<'local>,
    value: jlong,
    memo: JByteArray<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.proposeTransfer").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;
        let to = utils::java_string_to_rust(env, &to)?;
        let value = Zatoshis::from_nonnegative_i64(value)
            .map_err(|_| anyhow!("Invalid amount, out of range"))?;

        let to = to
            .parse()
            .map_err(|e| anyhow!("Can't parse recipient address: {}", e))?;

        let memo = utils::java_nullable_bytes_to_rust(env, &memo)?
            .as_deref()
            .map(MemoBytes::from_bytes)
            .transpose()
            .map_err(|e| anyhow!("Invalid MemoBytes: {}", e))?;

        // Always use ZIP 317 fees
        let (change_strategy, input_selector) = zip317_helper(None);

        let request = TransactionRequest::new(vec![
            Payment::new(to, Some(value), memo, None, None, vec![])
                .map_err(|e| anyhow!("Unable to construct payment: {}.", e))?,
        ])
        .map_err(|e| anyhow!("Error creating transaction request: {:?}", e))?;

        let proposal = propose_transfer::<_, _, _, _, Infallible>(
            &mut db_data,
            &network,
            account_uuid,
            &input_selector,
            &change_strategy,
            request,
            wallet::ConfirmationsPolicy::default(),
            &SpendPolicy::default(),
            None,
        )
        .map_err(|e| anyhow!("Error creating transaction proposal: {}", e))?;

        Ok(utils::rust_bytes_to_java(
            env,
            Proposal::from_standard_proposal(&proposal)
                .encode_to_vec()
                .as_ref(),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_proposeShielding<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    shielding_threshold: jlong,
    memo: JByteArray<'local>,
    transparent_receiver: JString<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.proposeShielding").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;
        let shielding_threshold = Zatoshis::from_nonnegative_i64(shielding_threshold)
            .map_err(|_| anyhow!("Invalid shielding threshold, out of range"))?;
        let confirmations_policy = wallet::ConfirmationsPolicy::MIN;
        let transparent_receiver =
            match utils::java_nullable_string_to_rust(env, &transparent_receiver)? {
                None => Ok(None),
                Some(addr) => match Address::decode(&network, &addr) {
                    None => Err(anyhow!("Transparent receiver is for the wrong network")),
                    Some(addr) => match addr {
                        Address::Sapling(_) | Address::Unified(_) | Address::Tex(_) => {
                            Err(anyhow!("Transparent receiver is not a transparent address"))
                        }
                        Address::Transparent(addr) => {
                            // Zashi does not support standalone keys, so we do not request standalone receivers.
                            if db_data
                                .get_transparent_receivers(account_uuid, true, false)?
                                .contains_key(&addr)
                            {
                                Ok(Some(addr))
                            } else {
                                Err(anyhow!("Transparent receiver does not belong to account"))
                            }
                        }
                    },
                },
            }?;

        let account_receivers = db_data
            .get_target_and_anchor_heights(NonZeroU32::MIN)
            .map_err(|e| anyhow!("Error while fetching anchor height: {}", e))
            .and_then(|opt| {
                opt.map(|(target, _)| target) // Include unconfirmed funds.
                    .ok_or_else(|| anyhow!("height not available; scan required."))
            })
            .and_then(|target_height| {
                db_data
                    .get_transparent_balances(account_uuid, target_height, confirmations_policy)
                    .map_err(|e| {
                        anyhow!(
                            "Error while fetching transparent balances for {:?}: {}",
                            account_uuid,
                            e,
                        )
                    })
            })?;

        // If a specific address is specified, or balance only exists for one address, select the
        // value for that address.
        //
        // Otherwise, if there are any non-ephemeral addresses, select value for all those
        // addresses. See the warnings associated with the documentation of the
        // `transparent_receiver` argument in the method documentation for privacy considerations.
        //
        // Finally, if there are only ephemeral addresses, select value for exactly one of those
        // addresses.
        let from_addrs: Vec<TransparentAddress> = match transparent_receiver {
            Some(addr) => account_receivers
                .get(&addr)
                .and_then(|(_, balance)| {
                    (balance.spendable_value() >= shielding_threshold).then_some(addr)
                })
                .into_iter()
                .collect(),
            None => {
                let (ephemeral, non_ephemeral): (Vec<_>, Vec<_>) = account_receivers
                    .into_iter()
                    .filter(|(_, (_, balance))| balance.spendable_value() >= shielding_threshold)
                    .partition(|(_, (origin, _))| matches!(origin, TransparentKeyOrigin::Derived { scope } if *scope == TransparentKeyScope::EPHEMERAL));

                if non_ephemeral.is_empty() {
                    ephemeral
                        .into_iter()
                        .take(1)
                        .map(|(addr, _)| addr)
                        .collect()
                } else {
                    non_ephemeral.into_iter().map(|(addr, _)| addr).collect()
                }
            }
        };

        if from_addrs.is_empty() {
            // There are no transparent funds to shield; don't create a proposal.
            return Ok(ptr::null_mut());
        };

        let memo = utils::java_nullable_bytes_to_rust(env, &memo)?
            .as_deref()
            .map(MemoBytes::from_bytes)
            .transpose()
            .map_err(|e| anyhow!("Invalid MemoBytes: {}", e))?;

        // Always use ZIP 317 fees
        let (change_strategy, input_selector) = zip317_helper(memo);

        let proposal = propose_shielding::<_, _, _, _, Infallible>(
            &mut db_data,
            &network,
            &input_selector,
            &change_strategy,
            shielding_threshold,
            &from_addrs,
            account_uuid,
            confirmations_policy,
            CoinbaseFilter::AllTransparentOutputs,
        )
        .map_err(|e| anyhow!("Error while shielding transaction: {}", e))?;

        Ok(utils::rust_bytes_to_java(
            env,
            Proposal::from_standard_proposal(&proposal)
                .encode_to_vec()
                .as_ref(),
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_createProposedTransactions<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    proposal: JByteArray<'local>,
    usk: JByteArray<'local>,
    spend_params: JString<'local>,
    output_params: JString<'local>,
    network_id: jint,
) -> jobjectArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.createProposedTransaction").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let usk = decode_usk(env, usk)?;
        let spend_params = path_from_jni(env, spend_params)?;
        let output_params = path_from_jni(env, output_params)?;

        let prover = LocalTxProver::new(&spend_params, &output_params);

        let proposal = Proposal::decode(utils::java_bytes_to_rust(env, &proposal)?.as_slice())
            .map_err(|e| anyhow!("Invalid proposal: {}", e))?
            .try_into_standard_proposal(&network, &db_data)?;

        let txids = create_proposed_transactions::<_, _, Infallible, _, Infallible, _>(
            &mut db_data,
            &network,
            &prover,
            &prover,
            &wallet::SpendingKeys::from_unified_spending_key(usk),
            OvkPolicy::Sender,
            &proposal,
        )
        .map_err(|e| anyhow!("Error while creating transactions: {}", e))?;

        Ok(
            utils::rust_vec_to_java(env, txids.into(), "[B", |env, txid| {
                utils::rust_bytes_to_java(env, txid.as_ref())
            })?
            .into_raw(),
        )
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Creates a partially-created (unsigned without proofs) transaction from the given proposal.
///
/// Returns the partially created transaction in its serialized format.
///
/// Do not call this multiple times in parallel, or you will generate PCZT instances that, if
/// finalized, would double-spend the same notes.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_createPcztFromProposal<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    account_uuid: JByteArray<'local>,
    proposal: JByteArray<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.createPcztFromProposal").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_id = account_id_from_jni(env, account_uuid)?;

        let proposal = Proposal::decode(utils::java_bytes_to_rust(env, &proposal)?.as_slice())
            .map_err(|e| anyhow!("Invalid proposal: {}", e))?
            .try_into_standard_proposal(&network, &db_data)?;

        if proposal.steps().len() == 1 {
            let pczt = create_pczt_from_proposal::<_, _, Infallible, _, Infallible, _>(
                &mut db_data,
                &network,
                account_id,
                OvkPolicy::Sender,
                &proposal,
                None,
                orchard::builder::BundleType::DEFAULT,
            )
            .map_err(|e| anyhow!("Error creating PCZT from single-step proposal: {}", e))?;

            Ok(utils::rust_bytes_to_java(
                env,
                &pczt
                    .serialize()
                    .map_err(|e| anyhow!("Failed to serialize PCZT: {:?}", e))?,
            )?
            .into_raw())
        } else {
            Err(anyhow!(
                "Multi-step proposals are not yet supported for PCZT generation."
            ))
        }
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Redacts information from the given PCZT that is unnecessary for the Signer role.
///
/// Returns the updated PCZT in its serialized format.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_redactPcztForSignerRole<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt: JByteArray<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.redactPcztForSignerRole").entered();

        let pczt = parse_pczt(env, pczt)?;

        let pczt_with_proofs = Redactor::new(pczt)
            .redact_global_with(|mut r| r.redact_proprietary("zcash_client_backend:proposal_info"))
            .redact_orchard_with(|mut r| {
                r.redact_actions(|mut ar| {
                    ar.clear_spend_witness();
                    ar.redact_output_proprietary("zcash_client_backend:output_info");
                })
            })
            .redact_sapling_with(|mut r| {
                r.redact_spends(|mut sr| sr.clear_witness());
                r.redact_outputs(|mut or| {
                    or.redact_proprietary("zcash_client_backend:output_info")
                });
            })
            .redact_transparent_with(|mut r| {
                r.redact_outputs(|mut or| {
                    or.redact_proprietary("zcash_client_backend:output_info")
                });
            })
            .finish();

        Ok(utils::rust_bytes_to_java(
            env,
            &pczt_with_proofs
                .serialize()
                .map_err(|e| anyhow!("Failed to serialize PCZT: {:?}", e))?,
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns `true` if this PCZT requires Sapling proofs (and thus the caller needs to have
/// downloaded them).
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_requiresSaplingProofs<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt: JByteArray<'local>,
) -> jboolean {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.pcztRequiresSaplingProofs").entered();

        let pczt = parse_pczt(env, pczt)?;

        let prover = Prover::new(pczt);

        Ok(if prover.requires_sapling_proofs() {
            JNI_TRUE
        } else {
            JNI_FALSE
        })
    });
    unwrap_exc_or(&mut env, res, JNI_TRUE)
}

/// Adds proofs to the given PCZT.
///
/// Returns the updated PCZT in its serialized format.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_addProofsToPczt<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    pczt: JByteArray<'local>,
    spend_params: JString<'local>,
    output_params: JString<'local>,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.addProofsToPczt").entered();

        let pczt = parse_pczt(env, pczt)?;

        // The Orchard circuit version is fixed by the consensus branch under which the
        // transaction will be mined; derive it from the branch id carried by the PCZT.
        let orchard_circuit_version = BranchId::try_from(*pczt.global().consensus_branch_id())
            .ok()
            .and_then(|branch_id| bundle_version_for_branch(branch_id, orchard::ValuePool::Orchard))
            .map(|bundle_version| bundle_version.circuit_version());

        let mut prover = Prover::new(pczt);

        if prover.requires_orchard_proof() {
            let circuit_version = orchard_circuit_version.ok_or_else(|| {
                anyhow!("PCZT requires an Orchard proof but its consensus branch does not support Orchard")
            })?;
            prover = prover
                .create_orchard_proof(&orchard::circuit::ProvingKey::build(circuit_version))
                .map_err(|e| anyhow!("Failed to create Orchard proof for PCZT: {:?}", e))?;
        }
        assert!(!prover.requires_orchard_proof());

        if prover.requires_sapling_proofs() {
            let spend_params = path_from_jni(env, spend_params)?;
            let output_params = path_from_jni(env, output_params)?;
            let local_prover = LocalTxProver::new(&spend_params, &output_params);

            prover = prover
                .create_sapling_proofs(&local_prover, &local_prover)
                .map_err(|e| anyhow!("Failed to create Sapling proofs for PCZT: {:?}", e))?;
        }
        assert!(!prover.requires_sapling_proofs());

        let pczt_with_proofs = prover.finish();

        Ok(utils::rust_bytes_to_java(
            env,
            &pczt_with_proofs
                .serialize()
                .map_err(|e| anyhow!("Failed to serialize PCZT: {:?}", e))?,
        )?
        .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Takes a PCZT that has been separately proven and signed, finalizes it, and stores it
/// in the wallet.
///
/// Returns the txid of the completed transaction.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_extractAndStoreTxFromPczt<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_data: JString<'local>,
    pczt_with_proofs: JByteArray<'local>,
    pczt_with_signatures: JByteArray<'local>,
    spend_params: JString<'local>,
    output_params: JString<'local>,
    network_id: jint,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.extractAndStoreTxFromPczt").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;

        let pczt_with_proofs = parse_pczt(env, pczt_with_proofs)
            .map_err(|e| anyhow!("Invalid PCZT-with-proofs: {:?}", e))?;

        let pczt_with_signatures = parse_pczt(env, pczt_with_signatures)
            .map_err(|e| anyhow!("Invalid PCZT-with-signatures: {:?}", e))?;

        let spend_params = path_from_jni(env, spend_params)?;
        let output_params = path_from_jni(env, output_params)?;
        let prover = LocalTxProver::new(&spend_params, &output_params);
        let (spend_vk, output_vk) = prover.verifying_keys();

        let pczt = Combiner::new(vec![pczt_with_proofs, pczt_with_signatures])
            .combine()
            .map_err(|e| anyhow!("Failed to combine PCZTs: {:?}", e))?;

        let txid = extract_and_store_transaction_from_pczt::<_, ()>(
            &mut db_data,
            pczt,
            Some((&spend_vk, &output_vk)),
            // Passing `None` lets the extractor build the verifying key for the
            // circuit version fixed by the bundle's own `BundleVersion`.
            None,
        )
        .map_err(|e| anyhow!("Failed to extract transaction from PCZT: {:?}", e))?;

        Ok(utils::rust_bytes_to_java(env, txid.as_ref())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustBackend_branchIdForHeight<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    height: jlong,
    network_id: jint,
) -> jlong {
    let res = panic::catch_unwind(|| {
        let _span = tracing::info_span!("RustBackend.branchIdForHeight").entered();
        let network = parse_network(network_id as u32)?;
        let branch: BranchId = BranchId::for_height(&network, BlockHeight::from(height as u32));
        let branch_id: u32 = u32::from(branch);
        debug!(
            "For height {} found consensus branch {:?} with id {}",
            height, branch, branch_id
        );
        Ok(branch_id.into())
    });
    unwrap_exc_or(&mut env, res, -1)
}
