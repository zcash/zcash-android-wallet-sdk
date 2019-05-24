#[macro_use]
extern crate log;

mod utils;

use android_logger::Filter;
use failure::format_err;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use log::Level;
use std::panic;
use std::path::Path;
use std::ptr;
use zcash_client_backend::{
    constants::SAPLING_CONSENSUS_BRANCH_ID,
    encoding::{
        decode_extended_spending_key, decode_payment_address, encode_extended_spending_key,
    },
    keys::spending_key,
};
use zcash_client_sqlite::{
    chain::{rewind_to_height, validate_combined_chain},
    get_address, get_balance, get_received_memo_as_utf8, get_sent_memo_as_utf8,
    get_verified_balance, init_accounts_table, init_blocks_table, init_data_database,
    scan_cached_blocks, send_to_address, ErrorKind,
};
use zcash_primitives::{
    block::BlockHash, note_encryption::Memo, transaction::components::Amount,
    zip32::ExtendedFullViewingKey,
};
use zcash_proofs::prover::LocalTxProver;

use crate::utils::exception::unwrap_exc_or;

#[cfg(feature = "mainnet")]
use zcash_client_backend::constants::mainnet::{
    HRP_SAPLING_EXTENDED_SPENDING_KEY, HRP_SAPLING_PAYMENT_ADDRESS,
};

#[cfg(not(feature = "mainnet"))]
use zcash_client_backend::constants::testnet::{
    HRP_SAPLING_EXTENDED_SPENDING_KEY, HRP_SAPLING_PAYMENT_ADDRESS,
};

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_initLogs(
    _env: JNIEnv<'_>,
    _: JClass<'_>,
) {
    android_logger::init_once(
        Filter::default().with_min_level(Level::Trace),
        Some("cash.z.rust.logs"),
    );

    log_panics::init();

    debug!("logs have been initialized successfully");
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_initDataDb(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
) -> jboolean {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);

        init_data_database(&db_data)
            .map(|()| JNI_TRUE)
            .map_err(|e| format_err!("Error while initializing data DB: {}", e))
    });
    unwrap_exc_or(&env, res, JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_initAccountsTable(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    seed: jbyteArray,
    accounts: jint,
) -> jobjectArray {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let seed = env.convert_byte_array(seed).unwrap();
        let accounts = if accounts >= 0 {
            accounts as u32
        } else {
            return Err(format_err!("accounts argument must be positive"));
        };

        let extsks: Vec<_> = (0..accounts)
            .map(|account| spending_key(&seed, 1, account))
            .collect();
        let extfvks: Vec<_> = extsks.iter().map(ExtendedFullViewingKey::from).collect();

        match init_accounts_table(&db_data, &extfvks) {
            Ok(()) => {
                // Return the ExtendedSpendingKeys for the created accounts
                Ok(utils::rust_vec_to_java(
                    &env,
                    extsks,
                    "java/lang/String",
                    |env, extsk| {
                        env.new_string(encode_extended_spending_key(
                            HRP_SAPLING_EXTENDED_SPENDING_KEY,
                            &extsk,
                        ))
                    },
                    |env| env.new_string(""),
                ))
            }
            Err(e) => Err(format_err!("Error while initializing accounts: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_initBlocksTable(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    height: jint,
    hash_string: JString<'_>,
    time: jlong,
    sapling_tree_string: JString<'_>,
) -> jboolean {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let hash = {
            let mut hash = hex::decode(utils::java_string_to_rust(&env, hash_string)).unwrap();
            hash.reverse();
            BlockHash::from_slice(&hash)
        };
        let time = if time >= 0 && time <= jlong::from(u32::max_value()) {
            time as u32
        } else {
            return Err(format_err!("time argument must fit in a u32"));
        };
        let sapling_tree =
            hex::decode(utils::java_string_to_rust(&env, sapling_tree_string)).unwrap();

        match init_blocks_table(&db_data, height, hash, time, &sapling_tree) {
            Ok(()) => Ok(JNI_TRUE),
            Err(e) => Err(format_err!("Error while initializing blocks table: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_getAddress(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    account: jint,
) -> jstring {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let account = if account >= 0 {
            account as u32
        } else {
            return Err(format_err!("account argument must be positive"));
        };

        match get_address(&db_data, account) {
            Ok(addr) => {
                let output = env.new_string(addr).expect("Couldn't create Java string!");
                Ok(output.into_inner())
            }
            Err(e) => Err(format_err!("Error while fetching address: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_getBalance(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    account: jint,
) -> jlong {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let account = if account >= 0 {
            account as u32
        } else {
            return Err(format_err!("account argument must be positive"));
        };

        match get_balance(&db_data, account) {
            Ok(balance) => Ok(balance.0),
            Err(e) => Err(format_err!("Error while fetching balance: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, -1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_getVerifiedBalance(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    account: jint,
) -> jlong {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let account = if account >= 0 {
            account as u32
        } else {
            return Err(format_err!("account argument must be positive"));
        };

        match get_verified_balance(&db_data, account) {
            Ok(balance) => Ok(balance.0),
            Err(e) => Err(format_err!("Error while fetching verified balance: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, -1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_getReceivedMemoAsUtf8(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    id_note: jlong,
) -> jstring {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);

        let memo = match get_received_memo_as_utf8(db_data, id_note) {
            Ok(memo) => memo.unwrap_or_default(),
            Err(e) => return Err(format_err!("Error while fetching memo: {}", e)),
        };

        let output = env.new_string(memo).expect("Couldn't create Java string!");
        Ok(output.into_inner())
    });
    unwrap_exc_or(&env, res, ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_getSentMemoAsUtf8(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    id_note: jlong,
) -> jstring {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);

        let memo = match get_sent_memo_as_utf8(db_data, id_note) {
            Ok(memo) => memo.unwrap_or_default(),
            Err(e) => return Err(format_err!("Error while fetching memo: {}", e)),
        };

        let output = env.new_string(memo).expect("Couldn't create Java string!");
        Ok(output.into_inner())
    });
    unwrap_exc_or(&env, res, ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_validateCombinedChain(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_cache: JString<'_>,
    db_data: JString<'_>,
) -> jint {
    let res = panic::catch_unwind(|| {
        let db_cache = utils::java_string_to_rust(&env, db_cache);
        let db_data = utils::java_string_to_rust(&env, db_data);

        if let Err(e) = validate_combined_chain(&db_cache, &db_data) {
            match e.kind() {
                ErrorKind::InvalidChain(upper_bound, _) => Ok(*upper_bound),
                _ => Err(format_err!("Error while validating chain: {}", e)),
            }
        } else {
            // All blocks are valid, so "highest invalid block height" is below genesis.
            Ok(-1)
        }
    });
    unwrap_exc_or(&env, res, 0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_rewindToHeight(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    height: jint,
) -> jboolean {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);

        match rewind_to_height(&db_data, height) {
            Ok(()) => Ok(JNI_TRUE),
            Err(e) => Err(format_err!(
                "Error while rewinding data DB to height {}: {}",
                height,
                e
            )),
        }
    });
    unwrap_exc_or(&env, res, JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_scanBlocks(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_cache: JString<'_>,
    db_data: JString<'_>,
) -> jboolean {
    let res = panic::catch_unwind(|| {
        let db_cache = utils::java_string_to_rust(&env, db_cache);
        let db_data = utils::java_string_to_rust(&env, db_data);

        match scan_cached_blocks(&db_cache, &db_data) {
            Ok(()) => Ok(JNI_TRUE),
            Err(e) => Err(format_err!("Error while scanning blocks: {}", e)),
        }
    });
    unwrap_exc_or(&env, res, JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "C" fn Java_cash_z_wallet_sdk_jni_RustBackend_sendToAddress(
    env: JNIEnv<'_>,
    _: JClass<'_>,
    db_data: JString<'_>,
    account: jint,
    extsk: JString<'_>,
    to: JString<'_>,
    value: jlong,
    memo: JString<'_>,
    spend_params: JString<'_>,
    output_params: JString<'_>,
) -> jlong {
    let res = panic::catch_unwind(|| {
        let db_data = utils::java_string_to_rust(&env, db_data);
        let account = if account >= 0 {
            account as u32
        } else {
            return Err(format_err!("account argument must be positive"));
        };
        let extsk = utils::java_string_to_rust(&env, extsk);
        let to = utils::java_string_to_rust(&env, to);
        let value = Amount(value);
        let memo = utils::java_string_to_rust(&env, memo);
        let spend_params = utils::java_string_to_rust(&env, spend_params);
        let output_params = utils::java_string_to_rust(&env, output_params);

        let extsk = match decode_extended_spending_key(HRP_SAPLING_EXTENDED_SPENDING_KEY, &extsk) {
            Ok(Some(extsk)) => extsk,
            Ok(None) => {
                return Err(format_err!("ExtendedSpendingKey is for the wrong network"));
            }
            Err(e) => {
                return Err(format_err!("Invalid ExtendedSpendingKey: {}", e));
            }
        };

        let to = match decode_payment_address(HRP_SAPLING_PAYMENT_ADDRESS, &to) {
            Ok(Some(to)) => to,
            Ok(None) => {
                return Err(format_err!("PaymentAddress is for the wrong network"));
            }
            Err(e) => {
                return Err(format_err!("Invalid PaymentAddress: {}", e));
            }
        };

        let memo = Memo::from_str(&memo);

        let prover = LocalTxProver::new(Path::new(&spend_params), Path::new(&output_params));

        send_to_address(
            &db_data,
            SAPLING_CONSENSUS_BRANCH_ID,
            prover,
            (account, &extsk),
            &to,
            value,
            memo,
        )
        .map_err(|e| format_err!("Error while sending funds: {}", e))
    });
    unwrap_exc_or(&env, res, -1)
}
