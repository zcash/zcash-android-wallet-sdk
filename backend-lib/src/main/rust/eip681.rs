//! EIP-681 transaction request parsing.
//!
//! This module holds the parts of the EIP-681 surface that do not touch the JNI boundary. The
//! `Java_*` exports Kotlin's `RustEip681Tool` binds to, and the
//! `JniEip681TransactionRequest` encoders/decoders they use, both live in
//! [`crate::jni::eip681`].

use eip681::U256;

/// Parse a nullable `0x`-prefixed hex string into an `Option<U256>`.
///
/// Returns an error if the string is present but does not start with `0x` or `0X`.
pub(crate) fn hex_string_to_u256(s: Option<String>) -> anyhow::Result<Option<U256>> {
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
pub(crate) fn chain_id_string_to_u64(s: Option<String>) -> anyhow::Result<Option<u64>> {
    match s {
        Some(id) => {
            Ok(Some(id.parse::<u64>().map_err(|e| {
                anyhow::anyhow!("invalid chain ID '{}': {}", id, e)
            })?))
        }
        None => Ok(None),
    }
}
