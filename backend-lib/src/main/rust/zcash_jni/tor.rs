//! JNI bindings for Tor support.
//!
//! Exports for `cash.z.ecc.android.sdk.internal.model.TorClient` and
//! `TorWalletClient`, plus the marshalling that serves them. The runtime and
//! lightwalletd connection logic lives in [`crate::tor`].

use std::io;
use std::panic;
use std::ptr;
use std::time::SystemTime;

use anyhow::anyhow;
use bytes::Bytes;
use http_body_util::BodyExt;
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue},
    sys::{jbyteArray, jint, jlong, jobject, jstring},
};
use prost::Message;
use tor_rtcompat::ToplevelBlockOn;
use transparent::address::TransparentAddress;
use zcash_client_backend::{
    address::Address,
    data_api::{WalletRead, WalletWrite, wallet::decrypt_and_store_transaction},
    encoding::AddressCodec,
    tor::http::{HttpError, cryptex},
    wallet::Exposure,
};
use zcash_client_sqlite::error::SqliteClientError;
use zcash_primitives::transaction::Transaction;
use zcash_protocol::consensus::{BlockHeight, BranchId, Parameters};

use crate::utils::{self, catch_unwind, exception::unwrap_exc_or};
use crate::{
    account_id_from_jni, encode_transaction, parse_network, parse_optional_height,
    parse_tor_dormant_mode, parse_txid, path_from_jni, wallet_db,
};

/// Creates a Tor runtime
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_createTorRuntime<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_dir: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let tor_dir = path_from_jni(env, tor_dir)?;

        let tor = crate::tor::TorRuntime::create(&tor_dir)?;

        Ok(Box::into_raw(Box::new(tor)).expose_provenance() as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// Frees a Tor runtime.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_freeTorRuntime<'local>(
    _: JNIEnv<'local>,
    _: JClass<'local>,
    ptr: jlong,
) {
    let ptr = std::ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(ptr as usize);
    if !ptr.is_null() {
        let s = unsafe { Box::from_raw(ptr) };
        drop(s);
    }
}

/// Returns a new isolated `TorClient` handle.
///
/// The two `TorClient`s will share internal state and configuration, but their streams
/// will never share circuits with one another.
///
/// Use this method when you want separate parts of your program to each have a
/// `TorClient` handle, but where you don't want their activities to be linkable to one
/// another over the Tor network.
///
/// Calling this method is usually preferable to creating a completely separate
/// `TorClient` instance, since it can share its internals with the existing `TorClient`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_isolatedClient<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
) -> jlong {
    let res = panic::catch_unwind(|| {
        let tor_runtime =
            ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;

        let isolated_client = tor_runtime.isolated_client();

        Ok(Box::into_raw(Box::new(isolated_client)).expose_provenance() as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// Changes the client's current dormant mode, putting background tasks to sleep or waking
/// them up as appropriate.
///
/// This can be used to conserve CPU usage if you aren’t planning on using the client for
/// a while, especially on mobile platforms.
///
/// The `mode` argument specifies what level of sleep to put a Tor client into:
/// - 0: Normal - The client functions as normal, and background tasks run periodically.
/// - 1: Soft - Background tasks are suspended, conserving CPU usage. Attempts to use the
///   client will wake it back up again.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_setDormant<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
    mode: jint,
) {
    let res = panic::catch_unwind(|| {
        let tor_runtime =
            ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;
        let mode = parse_tor_dormant_mode(mode as u32)?;

        tor_runtime.set_dormant(mode);

        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

const JNI_HTTP_HEADER: &str = "cash/z/ecc/android/sdk/internal/model/JniHttpHeader";

fn encode_http_header<'a>(
    env: &mut JNIEnv<'a>,
    name: &http::HeaderName,
    value: &str,
) -> jni::errors::Result<JObject<'a>> {
    let name = JObject::from(env.new_string(name.as_str())?);
    let value = JObject::from(env.new_string(value)?);

    env.new_object(
        JNI_HTTP_HEADER,
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[(&name).into(), (&value).into()],
    )
}

fn encode_http_response_bytes<'a>(
    env: &mut JNIEnv<'a>,
    response: http::Response<Bytes>,
) -> anyhow::Result<JObject<'a>> {
    let (parts, body) = response.into_parts();

    let version = JObject::from(env.new_string(format!("{:?}", parts.version))?);

    let headers = parts
        .headers
        .iter()
        .map(|(name, value)| {
            value
                .to_str()
                .map_err(|e| anyhow!(e))
                .map(|value| (name, value))
        })
        .collect::<Result<Vec<_>, _>>()?;

    let headers = utils::rust_vec_to_java(env, headers, JNI_HTTP_HEADER, |env, (name, value)| {
        encode_http_header(env, name, value)
    })?;

    Ok(env.new_object(
        "cash/z/ecc/android/sdk/internal/model/JniHttpResponseBytes",
        format!("(ILjava/lang/String;[L{};[B)V", JNI_HTTP_HEADER),
        &[
            JValue::Int(parts.status.as_u16().into()),
            (&version).into(),
            (&headers).into(),
            (&env.byte_array_from_slice(&body)?).into(),
        ],
    )?)
}

/// Makes an HTTP GET request over Tor.
///
/// `retry_limit` is the maximum number of times that a failed request should be retried.
/// You can disable retries by setting this to 0.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_httpGet<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
    url: JString<'local>,
    headers: JObjectArray<'local>,
    retry_limit: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let tor_runtime =
            std::ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;

        let url = utils::java_string_to_rust(env, &url)?
            .try_into()
            .map_err(|e| anyhow!("Invalid URL: {e}"))?;
        let headers = parse_http_headers(env, headers)?;
        let retry_limit =
            u8::try_from(retry_limit).map_err(|e| anyhow!("retryLimit is too large: {e}"))?;

        let response = tor_runtime.runtime().block_on(async {
            tor_runtime
                .client()
                .http_get(
                    url,
                    |builder| {
                        headers
                            .iter()
                            .fold(builder, |builder, (key, value)| builder.header(key, value))
                    },
                    |body| async { Ok(body.collect().await.map_err(HttpError::from)?.to_bytes()) },
                    retry_limit,
                    |res| {
                        res.is_err()
                            .then_some(zcash_client_backend::tor::http::Retry::Same)
                    },
                )
                .await
        })?;

        Ok(encode_http_response_bytes(env, response)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Makes an HTTP POST request over Tor.
///
/// `retry_limit` is the maximum number of times that a failed request should be retried.
/// You can disable retries by setting this to 0.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_httpPost<'local>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
    url: JString<'local>,
    headers: JObjectArray<'local>,
    body: JByteArray<'local>,
    retry_limit: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let tor_runtime =
            std::ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;

        let url = utils::java_string_to_rust(env, &url)?
            .try_into()
            .map_err(|e| anyhow!("Invalid URL: {e}"))?;
        let headers = parse_http_headers(env, headers)?;
        let body = utils::java_bytes_to_rust(env, &body)?;
        let retry_limit =
            u8::try_from(retry_limit).map_err(|e| anyhow!("retryLimit is too large: {e}"))?;

        let response = tor_runtime.runtime().block_on(async {
            tor_runtime
                .client()
                .http_post(
                    url,
                    |builder| {
                        headers
                            .iter()
                            .fold(builder, |builder, (key, value)| builder.header(key, value))
                    },
                    http_body_util::Full::new(io::Cursor::new(body)),
                    |body| async { Ok(body.collect().await.map_err(HttpError::from)?.to_bytes()) },
                    retry_limit,
                    |res| {
                        res.is_err()
                            .then_some(zcash_client_backend::tor::http::Retry::Same)
                    },
                )
                .await
        })?;

        Ok(encode_http_response_bytes(env, response)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Fetches the current ZEC-USD exchange rate over Tor.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_getExchangeRateUsd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let tor_runtime =
            std::ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;

        let exchanges = cryptex::Exchanges::builder(cryptex::exchanges::Gemini::unauthenticated())
            .with(cryptex::exchanges::Binance::unauthenticated())
            .with(cryptex::exchanges::Coinbase::unauthenticated())
            .with(cryptex::exchanges::Kraken::unauthenticated())
            .with(cryptex::exchanges::KuCoin::unauthenticated())
            .with(cryptex::exchanges::Mexc::unauthenticated())
            .build();

        let rate = tor_runtime.runtime().block_on(async {
            tor_runtime
                .client()
                .get_latest_zec_to_usd_rate(&exchanges)
                .await
        })?;

        let mantissa = env.byte_array_from_slice(&rate.mantissa().to_be_bytes())?;
        let unscaled_val =
            env.new_object("java/math/BigInteger", "([B)V", &[(&mantissa).into()])?;

        Ok(env
            .new_object(
                "java/math/BigDecimal",
                "(Ljava/math/BigInteger;I)V",
                &[
                    JValue::Object(&unscaled_val),
                    JValue::Int(rate.scale() as i32),
                ],
            )?
            .into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Connects to the lightwalletd server at the given endpoint.
///
/// Each connection returned by this method is isolated from any other Tor usage.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorClient_connectToLightwalletd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    tor_runtime: jlong,
    endpoint: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let tor_runtime =
            ptr::with_exposed_provenance_mut::<crate::tor::TorRuntime>(tor_runtime as usize);
        let tor_runtime =
            unsafe { tor_runtime.as_mut() }.ok_or_else(|| anyhow!("A Tor runtime is required"))?;

        let endpoint = utils::java_string_to_rust(env, &endpoint)?;
        let lwd_conn = tor_runtime.connect_to_lightwalletd(
            endpoint
                .try_into()
                .map_err(|e| anyhow!("Invalid lightwalletd endpoint: {e}"))?,
        )?;

        Ok(Box::into_raw(Box::new(lwd_conn)).expose_provenance() as jlong)
    });
    unwrap_exc_or(&mut env, res, -1)
}

/// Frees a lightwalletd connection.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_freeLightwalletdConnection<
    'local,
>(
    _: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
) {
    let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
    if !lwd_conn.is_null() {
        let s = unsafe { Box::from_raw(lwd_conn) };
        drop(s);
    }
}

/// Returns information about this lightwalletd instance and the blockchain.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_getServerInfo<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let info = lwd_conn.get_lightd_info()?;

        Ok(utils::rust_bytes_to_java(env, &info.encode_to_vec())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Returns information about this lightwalletd instance and the blockchain.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_getLatestBlock<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let block_id = lwd_conn.get_latest_block()?;

        Ok(utils::rust_bytes_to_java(env, &block_id.encode_to_vec())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Fetches the transaction with the given ID.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_fetchTransaction<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    txid_bytes: JByteArray<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        // This means we have to serialize back into a `Vec<u8>` next, but it is cheap and
        // we may as well confirm we were actually passed something shaped correctly.
        let txid = parse_txid(env, txid_bytes)?;

        let (tx, height) = lwd_conn.get_transaction(txid)?;

        Ok(encode_transaction(env, height, tx)?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Submits a transaction to the Zcash network via the given lightwalletd connection.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_submitTransaction<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    tx_bytes: JByteArray<'local>,
) {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let tx_bytes = utils::java_bytes_to_rust(env, &tx_bytes)?;

        lwd_conn.send_transaction(tx_bytes)
    });
    unwrap_exc_or(&mut env, res, ())
}

/// Fetches the note commitment tree state corresponding to the given block height.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_getTreeState<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    height: jlong,
) -> jbyteArray {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let height = BlockHeight::try_from(height)?;

        let treestate = lwd_conn.get_tree_state(height)?;

        Ok(utils::rust_bytes_to_java(env, &treestate.encode_to_vec())?.into_raw())
    });
    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Checks to find any single-use ephemeral addresses exposed in the past day that have not yet
/// received funds, excluding any whose next check time is in the future. This will then choose the
/// address that is most overdue for checking, retrieve any UTXOs for that address over Tor, and
/// add them to the wallet database. If no such UTXOs are found, the check will be rescheduled
/// following an expoential-backoff-with-jitter algorithm.
///
/// Returns the address for which UTXOs were added to the wallet, or `null` otherwise.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_checkSingleUseTaddr<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let _span = tracing::info_span!("RustBackend.checkSingleUseTaddr").entered();
        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)?;
        let account_uuid = account_id_from_jni(env, account_uuid)?;

        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        // one day's worth of blocks.
        let max_exposure_depth = (24 * 60 * 60) / 75;
        let addrs =
            db_data.get_ephemeral_transparent_receivers(account_uuid, max_exposure_depth, true)?;

        // pick the address with the minimum check time that is less than or equal to now (or
        // absent)
        let now = SystemTime::now();
        let selected_addr_meta = addrs
            .into_iter()
            .filter(|(_, meta)| {
                meta.next_check_time().iter().all(|t| t <= &now)
                    && matches!(meta.exposure(), Exposure::Exposed { .. })
            })
            .min_by_key(|(_, meta)| meta.next_check_time());

        let cur_height = db_data
            .chain_height()?
            .ok_or(SqliteClientError::ChainHeightUnknown)?;

        let mut found = None;
        if let Some((addr, meta)) = selected_addr_meta {
            lwd_conn.with_taddress_transactions(
                &network,
                addr,
                match meta.exposure() {
                    Exposure::Exposed { at_height, .. } => at_height,
                    Exposure::Unknown | Exposure::CannotKnow => {
                        panic!("unexposed addresses should have already been filtered out");
                    }
                },
                Some(cur_height + 1),
                |tx_data, mined_height| {
                    found = Some(addr);
                    let consensus_branch_id =
                        BranchId::for_height(&network, mined_height.unwrap_or(cur_height + 1));

                    let tx = Transaction::read(&tx_data[..], consensus_branch_id)?;
                    decrypt_and_store_transaction(&network, &mut db_data, &tx, mined_height)?;

                    Ok(())
                },
            )?;

            if found.is_none() {
                let blocks_since_exposure = match meta.exposure() {
                    Exposure::Exposed { at_height, .. } => {
                        f64::from(std::cmp::max(cur_height - at_height, 1))
                    }
                    Exposure::Unknown => 1.0,
                    Exposure::CannotKnow => 1.0,
                };

                // We will schedule the next check to occur after approximately
                // log2(blocks_since_exposure) additional blocks.
                let offset_blocks = blocks_since_exposure.log2();
                // Convert the offset in blocks to an offset in seconds; this will always fit in a
                // u32.
                let offset_seconds = (offset_blocks * 75.0).round() as u32;
                db_data.schedule_next_check(&addr, offset_seconds)?;
            }
        }

        match found {
            Some(address) => {
                let address_str = address.encode(&network);
                Ok(env.new_string(address_str)?.into_raw())
            }
            None => Ok(ptr::null_mut()),
        }
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn encode_address_check_result<'a, P: Parameters>(
    env: &mut JNIEnv<'a>,
    network: &P,
    found: Option<TransparentAddress>,
) -> jni::errors::Result<JObject<'a>> {
    match found {
        None => {
            let nf_class = env.find_class(
                "cash/z/ecc/android/sdk/internal/model/JniAddressCheckResult$NotFound",
            )?;

            let instance_sig =
                "Lcash/z/ecc/android/sdk/internal/model/JniAddressCheckResult$NotFound;";

            let value = env.get_static_field(nf_class, "INSTANCE", instance_sig)?;
            value.l()
        }
        Some(address) => env.new_object(
            "cash/z/ecc/android/sdk/internal/model/JniAddressCheckResult$Found",
            "(Ljava/lang/String;)V",
            &[(&env.new_string(address.encode(network))?).into()],
        ),
    }
}

/// Retrieves transactions corresponding to the given t-address from the light wallet server that
/// were mined within the given block range, and adds them to the wallet using
/// [`decrypt_and_store_transaction`].
///
/// The start height must be in the range of a valid u32.
///
/// The end height is optional; to omit the end height for the query range use the sentinel value
/// `-1`. If any other value is specified, it must be in the range of a valid u32. Note that older
/// versions of `lightwalletd` will return an error if the end height is not specified.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_updateTransparentAddressTransactions<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    db_data: JString<'local>,
    address: JString<'local>,
    start: jlong,
    end: jlong,
    network_id: jint,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)
            .map_err(|e| anyhow!("Error while opening data DB: {}", e))?;

        let address = match Address::decode(&network, &utils::java_string_to_rust(env, &address)?) {
            None => Err(anyhow!("Address is for the wrong network")),
            Some(addr) => match addr {
                Address::Sapling(_) | Address::Unified(_) | Address::Tex(_) => {
                    Err(anyhow!("Address is not a transparent address"))
                }
                Address::Transparent(addr) => Ok(addr),
            },
        }?;
        let start = parse_optional_height(start)?
            .ok_or_else(|| anyhow!("Start height for address queries is non-optional."))?;
        let end = parse_optional_height(end)?;

        let mut found = None;
        lwd_conn.with_taddress_transactions(
            &network,
            address,
            start,
            end,
            |tx_bytes, mined_height| {
                // The consensus branch ID passed in here does not matter:
                // - v4 and below cache it internally, but all we do with this transaction
                //   while it is in memory is decryption and serialization, neither of
                //   which use the consensus branch ID.
                // - v5 and above transactions ignore the argument, and parse the correct
                //   value from their encoding.
                let tx = Transaction::read(&tx_bytes[..], BranchId::Sapling)?;
                found = Some(address);

                decrypt_and_store_transaction(&network, &mut db_data, &tx, mined_height)
                    .map_err(|e| anyhow!("Error while decrypting transaction: {}", e))
            },
        )?;

        Ok(encode_address_check_result(env, &network, found)?.into_raw())
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

/// Queries the light wallet server to find any UTXOs associated with the given transparent
/// address, and adds any UTXOs discovered to the wallet.
///
/// This check will cover the block range starting at the exposure height for that address, if
/// known, or otherwise at the birthday height of the specified account.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_model_TorWalletClient_fetchUtxosByAddress<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    lwd_conn: jlong,
    db_data: JString<'local>,
    network_id: jint,
    account_uuid: JByteArray<'local>,
    address: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let lwd_conn = ptr::with_exposed_provenance_mut::<crate::tor::LwdConn>(lwd_conn as usize);
        let lwd_conn = unsafe { lwd_conn.as_mut() }
            .ok_or_else(|| anyhow!("A Tor lightwalletd connection is required"))?;

        let network = parse_network(network_id as u32)?;
        let mut db_data = wallet_db(env, network, db_data)
            .map_err(|e| anyhow!("Error while opening data DB: {}", e))?;

        let account_uuid = account_id_from_jni(env, account_uuid)?;
        let address = match Address::decode(&network, &utils::java_string_to_rust(env, &address)?) {
            None => Err(anyhow!("Address is for the wrong network")),
            Some(addr) => match addr {
                Address::Sapling(_) | Address::Unified(_) | Address::Tex(_) => {
                    Err(anyhow!("Address is not a transparent address"))
                }
                Address::Transparent(addr) => Ok(addr),
            },
        }?;

        let mut found = None;
        if let Some(meta) = db_data.get_transparent_address_metadata(account_uuid, &address)? {
            lwd_conn.with_taddress_utxos(
                &network,
                address,
                match meta.exposure() {
                    Exposure::Exposed { at_height, .. } => Some(at_height),
                    Exposure::Unknown | Exposure::CannotKnow => {
                        Some(db_data.get_account_birthday(account_uuid)?)
                    }
                },
                None,
                |output| {
                    found = Some(address);
                    db_data.put_received_transparent_utxo(&output)?;
                    Ok(())
                },
            )?;
        }

        Ok(encode_address_check_result(env, &network, found)?.into_raw())
    });

    unwrap_exc_or(&mut env, res, ptr::null_mut())
}

fn parse_http_headers(
    env: &mut JNIEnv,
    headers: JObjectArray,
) -> anyhow::Result<Vec<(String, String)>> {
    let count = env.get_array_length(&headers)?;
    (0..count)
        .scan(env, |env, i| {
            Some(
                env.get_object_array_element(&headers, i)
                    .map_err(|e| e.into())
                    .and_then(|obj| {
                        let key = {
                            let jkey = JString::from(
                                env.get_field(&obj, "key", "Ljava/lang/String;")?.l()?,
                            );
                            utils::java_string_to_rust(env, &jkey)?
                        };

                        let value = {
                            let jvalue = JString::from(
                                env.get_field(&obj, "value", "Ljava/lang/String;")?.l()?,
                            );
                            utils::java_string_to_rust(env, &jvalue)?
                        };

                        Ok((key, value))
                    }),
            )
        })
        .collect()
}
