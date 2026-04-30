//! EIP-681 transaction request parsing via JNI.

use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
    sys::{jobject, jstring},
};

use eip681::{TransactionRequest, U256};

use crate::utils::{
    catch_unwind, exception::unwrap_exc_or, java_nullable_string_to_rust, java_string_to_rust,
};

const JNI_CLASS_PREFIX: &str = "cash/z/ecc/android/sdk/internal/model/JniEip681TransactionRequest";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert an `Option<U256>` to a JNI `JObject` that is either a `JString`
/// containing a `0x`-prefixed hex representation, or null.
fn u256_option_to_jstring<'a>(
    env: &mut JNIEnv<'a>,
    value: Option<U256>,
) -> jni::errors::Result<JObject<'a>> {
    match value {
        Some(v) => Ok(env.new_string(format!("{:#x}", v))?.into()),
        None => Ok(JObject::null()),
    }
}

/// Convert an `Option<u64>` chain ID to a JNI `JObject` that is either a
/// `JString` containing the decimal representation, or null.
///
/// The chain ID is passed as a `String?` rather than a `Long?` across the JNI
/// boundary because the Rust crate represents it as `u64` (unsigned, max
/// 2^64−1), while Kotlin's `Long` is signed (max 2^63−1). Using a decimal
/// string avoids a potential overflow for chain IDs that exceed `Long.MAX_VALUE`.
fn chain_id_to_jstring<'a>(
    env: &mut JNIEnv<'a>,
    chain_id: Option<u64>,
) -> jni::errors::Result<JObject<'a>> {
    match chain_id {
        Some(id) => Ok(env.new_string(id.to_string())?.into()),
        None => Ok(JObject::null()),
    }
}

/// Parse a nullable `0x`-prefixed hex string into an `Option<U256>`.
///
/// Returns an error if the string is present but does not start with `0x` or `0X`.
fn hex_string_to_u256(s: Option<String>) -> anyhow::Result<Option<U256>> {
    match s {
        Some(hex) => {
            let stripped = hex
                .strip_prefix("0x")
                .or_else(|| hex.strip_prefix("0X"))
                .ok_or_else(|| anyhow::anyhow!("hex string '{}' missing 0x prefix", hex))?;
            Ok(Some(U256::from_str_radix(stripped, 16).map_err(|e| {
                anyhow::anyhow!("invalid hex U256 '{}': {}", hex, e)
            })?))
        }
        None => Ok(None),
    }
}

/// Parse a nullable decimal chain-ID string into an `Option<u64>`.
fn chain_id_string_to_u64(s: Option<String>) -> anyhow::Result<Option<u64>> {
    match s {
        Some(id) => {
            Ok(Some(id.parse::<u64>().map_err(|e| {
                anyhow::anyhow!("invalid chain ID '{}': {}", id, e)
            })?))
        }
        None => Ok(None),
    }
}

/// Read a non-null `String` field from a Java object.
fn get_string_field(env: &mut JNIEnv<'_>, obj: &JObject<'_>, name: &str) -> anyhow::Result<String> {
    let jstr = JString::from(env.get_field(obj, name, "Ljava/lang/String;")?.l()?);
    java_string_to_rust(env, &jstr)
}

/// Read a nullable `String` field from a Java object.
fn get_nullable_string_field(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
    name: &str,
) -> anyhow::Result<Option<String>> {
    let jstr = JString::from(env.get_field(obj, name, "Ljava/lang/String;")?.l()?);
    java_nullable_string_to_rust(env, &jstr)
}

/// Read a `boolean` field from a Java object.
fn get_bool_field(env: &mut JNIEnv<'_>, obj: &JObject<'_>, name: &str) -> anyhow::Result<bool> {
    Ok(env.get_field(obj, name, "Z")?.z()?)
}

// ---------------------------------------------------------------------------
// Encode: Rust TransactionRequest -> Kotlin JniEip681TransactionRequest
// ---------------------------------------------------------------------------

/// Encode a parsed [`TransactionRequest`] into the corresponding
/// `JniEip681TransactionRequest` sealed-class variant.
fn encode_eip681_transaction_request<'a>(
    env: &mut JNIEnv<'a>,
    request: &TransactionRequest,
) -> anyhow::Result<JObject<'a>> {
    match request {
        TransactionRequest::NativeRequest(native) => {
            let schema_prefix = env.new_string(native.schema_prefix())?;
            let has_pay = native.has_pay() as u8;
            let chain_id = chain_id_to_jstring(env, native.chain_id())?;
            let recipient = env.new_string(native.recipient_address())?;
            let value_hex = u256_option_to_jstring(env, native.value_atomic())?;
            let gas_limit_hex = u256_option_to_jstring(env, native.gas_limit())?;
            let gas_price_hex = u256_option_to_jstring(env, native.gas_price())?;

            Ok(env.new_object(
                &format!("{}$Native", JNI_CLASS_PREFIX),
                "(Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Object(&schema_prefix),
                    JValue::Bool(has_pay),
                    JValue::Object(&chain_id),
                    JValue::Object(&recipient),
                    JValue::Object(&value_hex),
                    JValue::Object(&gas_limit_hex),
                    JValue::Object(&gas_price_hex),
                ],
            )?)
        }
        TransactionRequest::Erc20Request(erc20) => {
            let schema_prefix = env.new_string(erc20.schema_prefix())?;
            let has_pay = erc20.has_pay() as u8;
            let chain_id = chain_id_to_jstring(env, erc20.chain_id())?;
            let token_contract = env.new_string(erc20.token_contract_address())?;
            let recipient = env.new_string(erc20.recipient_address())?;
            let value_hex = env.new_string(format!("{:#x}", erc20.value_atomic()))?;

            Ok(env.new_object(
                &format!("{}$Erc20", JNI_CLASS_PREFIX),
                "(Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Object(&schema_prefix),
                    JValue::Bool(has_pay),
                    JValue::Object(&chain_id),
                    JValue::Object(&token_contract),
                    JValue::Object(&recipient),
                    JValue::Object(&value_hex),
                ],
            )?)
        }
        TransactionRequest::Unrecognised(_) => {
            Ok(env.new_object(&format!("{}$Unrecognised", JNI_CLASS_PREFIX), "()V", &[])?)
        }
    }
}

// ---------------------------------------------------------------------------
// Decode: Kotlin JniEip681TransactionRequest -> Rust TransactionRequest
// ---------------------------------------------------------------------------

/// Decode a `JniEip681TransactionRequest` sealed-class instance back into a
/// Rust [`TransactionRequest`] by extracting fields and calling the crate's
/// `from_*_parts` constructors.
fn decode_eip681_transaction_request(
    env: &mut JNIEnv<'_>,
    obj: &JObject<'_>,
) -> anyhow::Result<TransactionRequest> {
    let native_class = format!("{}$Native", JNI_CLASS_PREFIX);
    let erc20_class = format!("{}$Erc20", JNI_CLASS_PREFIX);

    if env.is_instance_of(obj, &native_class)? {
        let schema_prefix = get_string_field(env, obj, "schemaPrefix")?;
        let has_pay = get_bool_field(env, obj, "hasPay")?;
        let chain_id = chain_id_string_to_u64(get_nullable_string_field(env, obj, "chainId")?)?;
        let recipient = get_string_field(env, obj, "recipientAddress")?;
        let value = hex_string_to_u256(get_nullable_string_field(env, obj, "valueHex")?)?;
        let gas_limit = hex_string_to_u256(get_nullable_string_field(env, obj, "gasLimitHex")?)?;
        let gas_price = hex_string_to_u256(get_nullable_string_field(env, obj, "gasPriceHex")?)?;

        TransactionRequest::from_native_request_parts(
            &schema_prefix,
            has_pay,
            chain_id,
            &recipient,
            value,
            gas_limit,
            gas_price,
        )
        .map_err(|e| anyhow::anyhow!("Failed to construct NativeRequest: {}", e))
    } else if env.is_instance_of(obj, &erc20_class)? {
        let schema_prefix = get_string_field(env, obj, "schemaPrefix")?;
        let has_pay = get_bool_field(env, obj, "hasPay")?;
        let chain_id = chain_id_string_to_u64(get_nullable_string_field(env, obj, "chainId")?)?;
        let token_contract = get_string_field(env, obj, "tokenContractAddress")?;
        let recipient = get_string_field(env, obj, "recipientAddress")?;
        let value_hex = get_string_field(env, obj, "valueHex")?;
        let value = hex_string_to_u256(Some(value_hex))?
            .ok_or_else(|| anyhow::anyhow!("valueHex must be non-null for Erc20"))?;

        TransactionRequest::from_erc20_request_parts(
            &schema_prefix,
            has_pay,
            chain_id,
            &token_contract,
            &recipient,
            value,
        )
        .map_err(|e| anyhow::anyhow!("Failed to construct Erc20Request: {}", e))
    } else {
        Err(anyhow::anyhow!(
            "Cannot serialize Unrecognised transaction request to URI"
        ))
    }
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

/// Parse an EIP-681 URI string into a `JniEip681TransactionRequest`.
///
/// JNI method for `RustEip681Tool.parseEip681TransactionRequest`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustEip681Tool_parseEip681TransactionRequest<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    input: JString<'local>,
) -> jobject {
    let res = catch_unwind(&mut env, |env| {
        let input_str = java_string_to_rust(env, &input)?;

        let request = TransactionRequest::parse(&input_str)
            .map_err(|e| anyhow::anyhow!("EIP-681 parse error: {}", e))?;

        let result = encode_eip681_transaction_request(env, &request)?;
        Ok(result.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}

/// Serialize a `JniEip681TransactionRequest` to a normalized EIP-681 URI string.
///
/// JNI method for `RustEip681Tool.eip681TransactionRequestToUri`.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustEip681Tool_eip681TransactionRequestToUri<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    input: JObject<'local>,
) -> jstring {
    let res = catch_unwind(&mut env, |env| {
        let request = decode_eip681_transaction_request(env, &input)?;
        let uri = request.to_string();
        let juri = env.new_string(&uri)?;
        Ok(juri.into_raw())
    });
    unwrap_exc_or(&mut env, res, std::ptr::null_mut())
}
