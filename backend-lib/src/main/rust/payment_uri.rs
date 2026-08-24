use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use payment_uri::parse_to_json;

use crate::utils::{catch_unwind, exception::unwrap_exc_or, java_string_to_rust};

/// Categorizes a [`payment_uri::Error`] without echoing any of its associated data (address,
/// amount, etc.) back to the caller -- those come directly from untrusted URI input, and this
/// crate's convention is to never splice raw caller-supplied input into an error message. The
/// category alone is enough to distinguish "bad user input, and which kind" from "an internal
/// bug", without carrying anything privacy-sensitive across the JNI boundary or into logs.
fn error_kind(error: &payment_uri::Error) -> &'static str {
    match error {
        payment_uri::Error::MissingScheme => "missing_scheme",
        payment_uri::Error::UnsupportedScheme(_) => "unsupported_scheme",
        payment_uri::Error::MissingRecipient => "missing_recipient",
        payment_uri::Error::InvalidAddress(_) => "invalid_address",
        payment_uri::Error::InvalidAmount(_) => "invalid_amount",
        payment_uri::Error::DuplicateParameter(_) => "duplicate_parameter",
        payment_uri::Error::UnsupportedRequiredParameter(_) => "unsupported_required_parameter",
        payment_uri::Error::InvalidEncoding(_) => "invalid_encoding",
        payment_uri::Error::InvalidTransactionLink(_) => "invalid_transaction_link",
        payment_uri::Error::Ethereum(_) => "ethereum",
        _ => "unknown",
    }
}

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
        let json = parse_to_json(&input)
            .map_err(|e| anyhow::anyhow!("invalid payment URI: {}", error_kind(&e)))?;
        Ok(env.new_string(json)?.into_raw())
    });
    unwrap_exc_or(&mut env, result, std::ptr::null_mut())
}
