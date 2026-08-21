use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use payment_uri::parse_to_json;

use crate::utils::{catch_unwind, exception::unwrap_exc_or, java_string_to_rust};

/// Parses a supported payment URI and returns an internal JSON envelope.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustPaymentUriTool_parsePaymentUri<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    input: JString<'local>,
) -> jstring {
    let result = catch_unwind(&mut env, |env| {
        let input = java_string_to_rust(env, &input)?;
        let json = parse_to_json(&input).map_err(|_| anyhow::anyhow!("Invalid payment URI"))?;
        Ok(env.new_string(json)?.into_raw())
    });
    unwrap_exc_or(&mut env, result, std::ptr::null_mut())
}
